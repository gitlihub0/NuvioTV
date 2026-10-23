package com.nuvio.tv.core.dub

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.domain.model.ExternalAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val MANAGER_TAG = "DubSource"

internal sealed class DubOutcome {
    data class Found(val audio: ExternalAudio, val providerId: String) : DubOutcome()
    data object NotFound : DubOutcome()
    data object AutoDisabled : DubOutcome()
    data class ConfigError(val message: String) : DubOutcome()
}

/**
 * Downloads the remote dub-source list, keeps a local copy, and finds a Turkish dub for the
 * content that is playing. Updating the list never needs a new app build.
 */
internal object DubSourceManager {

    /** Raw URL of the recipe file. Can be overridden at runtime through SharedPreferences. */
    const val DEFAULT_CONFIG_URL =
        "https://raw.githubusercontent.com/gitlihub0/nuvio-dublaj/main/dublaj_kaynaklari.json"

    private const val PREFS = "nuvio_dub"
    private const val PREF_CONFIG_URL = "config_url"
    private const val CACHE_FILE = "dub_sources.json"
    private const val CONFIG_TTL_MS = 6L * 60 * 60 * 1000
    private const val MAX_CONFIG_BYTES = 512L * 1024
    private const val FOUND_TTL_MS = 30L * 60 * 1000
    private const val MISS_TTL_MS = 10L * 60 * 1000
    private const val SEARCH_TIMEOUT_MS = 30_000L

    private class Cached(val audio: ExternalAudio?, val providerId: String?, val at: Long)

    private val resultCache = ConcurrentHashMap<String, Cached>()
    private val configMutex = Mutex()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun configUrl(context: Context): String {
        val custom = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_CONFIG_URL, null)
        return custom?.takeIf { it.startsWith("https://") } ?: DEFAULT_CONFIG_URL
    }

    suspend fun find(context: Context, request: DubRequest, manual: Boolean): DubOutcome {
        val config = loadConfig(context, forceRefresh = manual)
            ?: return DubOutcome.ConfigError("Dublaj kaynak listesi alınamadı")
        if (!manual && !config.autoSearch) return DubOutcome.AutoDisabled

        val key = request.cacheKey()
        val now = System.currentTimeMillis()
        if (!manual) {
            val cached = resultCache[key]
            if (cached != null) {
                val audio = cached.audio
                if (audio != null && now - cached.at < FOUND_TTL_MS) {
                    return DubOutcome.Found(audio, cached.providerId.orEmpty())
                }
                if (audio == null && now - cached.at < MISS_TTL_MS) return DubOutcome.NotFound
            }
        }

        val runner = DubPipelineRunner(client)
        for (provider in config.providers) {
            val hit = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { runner.find(provider, request) } ?: continue
            val audio = ExternalAudio(
                url = hit.url,
                lang = hit.lang,
                label = hit.label,
                headers = hit.headers.ifEmpty { null }
            )
            resultCache[key] = Cached(audio, hit.providerId, System.currentTimeMillis())
            return DubOutcome.Found(audio, hit.providerId)
        }
        resultCache[key] = Cached(null, null, System.currentTimeMillis())
        return DubOutcome.NotFound
    }

    /** Fresh copy when the cache is old (or [forceRefresh]); falls back to the stored copy offline. */
    suspend fun loadConfig(context: Context, forceRefresh: Boolean): DubConfig? = configMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(context.applicationContext.filesDir, CACHE_FILE)
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CONFIG_TTL_MS
            if (fresh && !forceRefresh) {
                readCached(file)?.let { return@withContext it }
            }
            val downloaded = download(configUrl(context))
            if (downloaded != null) {
                val parsed = DubConfigParser.parse(downloaded)
                if (parsed != null) {
                    writeAtomically(file, downloaded)
                    resultCache.clear()
                    return@withContext parsed
                }
                Log.w(MANAGER_TAG, "Downloaded config is invalid, keeping the stored copy")
            }
            readCached(file)
        }
    }

    private fun readCached(file: File): DubConfig? {
        if (!file.exists()) return null
        return try {
            DubConfigParser.parse(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun download(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.peekBody(MAX_CONFIG_BYTES).string() else null
            }
        } catch (e: Exception) {
            Log.w(MANAGER_TAG, "Config download failed: ${e.message}")
            null
        }
    }

    private fun writeAtomically(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(MANAGER_TAG, "Could not store config: ${e.message}")
        }
    }
}
