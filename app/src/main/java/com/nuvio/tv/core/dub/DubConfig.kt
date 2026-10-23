package com.nuvio.tv.core.dub

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Highest recipe schema this build understands. A newer file needs an app update. */
internal const val DUB_SUPPORTED_SCHEMA = 1

private const val CONFIG_TAG = "DubConfig"

/** Remote list of Turkish-dub sources. Pure data: nothing in it is executed as code. */
internal data class DubConfig(
    val schemaVersion: Int,
    val autoSearch: Boolean,
    val providers: List<DubProvider>
)

internal data class DubProvider(
    val id: String,
    val label: String,
    val lang: String,
    val base: String,
    /** Extra request headers for every page request of this provider. */
    val headers: Map<String, String>,
    /** Headers sent when the player later loads the audio stream itself. */
    val audioHeaders: Map<String, String>,
    val movieQueries: List<String>,
    val seriesQueries: List<String>,
    val searchUrl: String,
    /** Regexes tried in order; group 1 must capture the result page link. */
    val searchPicks: List<String>,
    val maxCandidates: Int,
    val steps: List<DubStep>,
    /** Name of the variable that holds the final audio URL (defaults to the last step result). */
    val result: String
)

internal data class DubStep(
    val op: String,
    val params: JSONObject
)

/** What the player knows about the content that is playing. */
internal data class DubRequest(
    val imdbId: String?,
    val title: String?,
    val year: Int?,
    val isMovie: Boolean,
    val season: Int?,
    val episode: Int?,
    val durationMin: Int?
) {
    fun cacheKey(): String =
        "${if (isMovie) "m" else "s"}|${imdbId.orEmpty()}|${title.orEmpty()}|${year ?: ""}|${season ?: ""}|${episode ?: ""}"

    fun toVars(): Map<String, String> {
        val vars = LinkedHashMap<String, String>()
        imdbId?.takeIf { it.isNotBlank() }?.let { vars["imdb"] = it }
        title?.takeIf { it.isNotBlank() }?.let {
            vars["title_raw"] = it
            vars["title"] = it.replace(Regex("""\s+\(\d{4}\)$"""), "").trim()
        }
        year?.let { vars["year"] = it.toString() }
        season?.let { vars["season"] = it.toString() }
        episode?.let { vars["episode"] = it.toString() }
        durationMin?.let { vars["durationMin"] = it.toString() }
        return vars
    }
}

internal data class DubHit(
    val url: String,
    val headers: Map<String, String>,
    val label: String,
    val lang: String,
    val providerId: String
)

internal object DubConfigParser {

    fun parse(text: String): DubConfig? {
        return try {
            val root = JSONObject(text)
            val schema = root.optInt("schemaVersion", 1)
            if (schema > DUB_SUPPORTED_SCHEMA) {
                Log.w(CONFIG_TAG, "Config schema $schema is newer than supported $DUB_SUPPORTED_SCHEMA; update the app")
                return null
            }
            val providers = mutableListOf<DubProvider>()
            val array = root.optJSONArray("providers") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseProvider(obj)?.let { providers.add(it) }
            }
            DubConfig(
                schemaVersion = schema,
                autoSearch = root.optBoolean("autoSearch", true),
                providers = providers
            )
        } catch (e: Exception) {
            Log.w(CONFIG_TAG, "Config parse failed: ${e.message}")
            null
        }
    }

    private fun parseProvider(o: JSONObject): DubProvider? {
        if (!o.optBoolean("enabled", true)) return null
        val id = o.optString("id", "").ifBlank { return null }
        val base = o.optString("base", "").trimEnd('/')
        if (!base.startsWith("https://")) return null
        val search = o.optJSONObject("search") ?: return null
        val searchUrl = search.optString("url", "").ifBlank { return null }
        val picks = stringList(search.optJSONArray("pick"))
        if (picks.isEmpty()) return null
        val steps = mutableListOf<DubStep>()
        val stepArray = o.optJSONArray("steps") ?: return null
        for (i in 0 until stepArray.length()) {
            val s = stepArray.optJSONObject(i) ?: return null
            val op = s.optString("op", "").ifBlank { return null }
            steps.add(DubStep(op, s))
        }
        if (steps.isEmpty() || steps.size > MAX_STEPS) return null
        return DubProvider(
            id = id,
            label = o.optString("label", "Türkçe Dublaj"),
            lang = o.optString("lang", "tr"),
            base = base,
            headers = stringMap(o.optJSONObject("headers")),
            audioHeaders = stringMap(o.optJSONObject("audioHeaders")),
            movieQueries = stringList(o.optJSONObject("movie")?.optJSONArray("queries")),
            seriesQueries = stringList(o.optJSONObject("series")?.optJSONArray("queries")),
            searchUrl = searchUrl,
            searchPicks = picks,
            maxCandidates = search.optInt("maxCandidates", 3).coerceIn(1, 6),
            steps = steps,
            result = o.optString("result", "")
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val v = array.optString(i, "")
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    private fun stringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k, "")
            if (v.isNotEmpty()) out[k] = v
        }
        return out
    }

    private const val MAX_STEPS = 40
}
