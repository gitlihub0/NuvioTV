package com.nuvio.tv.core.dub

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.domain.model.ExternalAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** Every distinct Turkish dub that was found, in provider order (the first one is auto-selected). */
    data class Found(val audios: List<ExternalAudio>) : DubOutcome()
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

    /** An empty [audios] list is a remembered miss. */
    private class Cached(val audios: List<ExternalAudio>, val at: Long)

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
                val found = cached.audios.isNotEmpty()
                if (now - cached.at < (if (found) FOUND_TTL_MS else MISS_TTL_MS)) {
                    return if (found) DubOutcome.Found(cached.audios) else DubOutcome.NotFound
                }
            }
        }

        // Every provider runs in parallel; one slow or broken site cannot hide the others.
        val runner = DubPipelineRunner(client)
        val hits = coroutineScope {
            config.providers.map { provider ->
                async {
                    try {
                        withTimeoutOrNull(SEARCH_TIMEOUT_MS) { runner.find(provider, request) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(MANAGER_TAG, "[${provider.id}] search crashed: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        val audios = mergeHits(hits)
        resultCache[key] = Cached(audios, System.currentTimeMillis())
        return if (audios.isEmpty()) DubOutcome.NotFound else DubOutcome.Found(audios)
    }

    /**
     * Turns provider hits into the audio list for the menu. Two recipes that resolve to the same
     * file (the URL differs only by its access token) show up once. Equal labels get a number so
     * the rows stay distinguishable.
     */
    private fun mergeHits(hits: List<DubHit>): List<ExternalAudio> {
        val seenFiles = HashSet<String>()
        val labelCount = HashMap<String, Int>()
        val out = ArrayList<ExternalAudio>()
        for (hit in hits) {
            val fileKey = hit.url.substringBefore('#').substringBefore('?')
            if (!seenFiles.add(fileKey)) {
                Log.i(MANAGER_TAG, "[${hit.providerId}] same audio file as an earlier source, skipped")
                continue
            }
            val n = (labelCount[hit.label] ?: 0) + 1
            labelCount[hit.label] = n
            val label = if (n == 1) hit.label else "${hit.label} ($n)"
            Log.i(MANAGER_TAG, "[${hit.providerId}] added as '$label'")
            out.add(
                ExternalAudio(
                    url = hit.url,
                    lang = hit.lang,
                    label = label,
                    headers = hit.headers.ifEmpty { null },
                    audioDelayMs = hit.audioDelayMs.takeIf { it != 0 },
                    sourceId = hit.providerId
                )
            )
        }
        return out
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
