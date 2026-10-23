package com.nuvio.tv.core.dub

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlin.math.abs

private const val RUNNER_TAG = "DubPipeline"
private const val MAX_BODY_BYTES = 3L * 1024 * 1024
private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * Runs a provider recipe: search page, pick a result, then walk the listed steps
 * (http_get, regex, json_path, rot13, base64, reverse, shift_key, set, check) until one
 * of them yields the audio URL. Only these built-in operations exist; the recipe is data.
 */
internal class DubPipelineRunner(private val http: OkHttpClient) {

    suspend fun find(provider: DubProvider, request: DubRequest): DubHit? = withContext(Dispatchers.IO) {
        val queries = if (request.isMovie) provider.movieQueries else provider.seriesQueries
        if (queries.isEmpty()) return@withContext null

        val baseVars = LinkedHashMap<String, String>(request.toVars())
        baseVars["base"] = provider.base
        val tried = HashSet<String>()

        for (rawQuery in queries) {
            ensureActive()
            val query = expand(rawQuery, baseVars, strict = true) ?: continue
            val searchVars = baseVars + ("q" to URLEncoder.encode(query, "UTF-8"))
            val searchUrl = expand(provider.searchUrl, searchVars, strict = true) ?: continue
            val html = try {
                fetch(searchUrl, provider.base + "/", provider.headers)
            } catch (e: Exception) {
                Log.w(RUNNER_TAG, "[${provider.id}] search failed: ${e.message}")
                null
            } ?: continue

            for (candidate in pickCandidates(html, provider)) {
                ensureActive()
                if (!tried.add(candidate)) continue
                Log.i(RUNNER_TAG, "[${provider.id}] trying $candidate")
                val hit = try {
                    runSteps(provider, baseVars + ("page" to candidate))
                } catch (e: Exception) {
                    Log.w(RUNNER_TAG, "[${provider.id}] steps failed for $candidate: ${e.message}")
                    null
                }
                if (hit != null) return@withContext hit
            }
        }
        null
    }

    private fun pickCandidates(html: String, provider: DubProvider): List<String> {
        val found = LinkedHashSet<String>()
        for (pattern in provider.searchPicks) {
            val regex = try {
                Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            } catch (e: Exception) {
                Log.w(RUNNER_TAG, "[${provider.id}] bad pick pattern: ${e.message}")
                continue
            }
            for (m in regex.findAll(html)) {
                val href = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (href.isEmpty()) continue
                found.add(absoluteUrl(href, provider.base))
                if (found.size >= provider.maxCandidates) return found.toList()
            }
        }
        return found.toList()
    }

    private fun absoluteUrl(href: String, base: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base + href
        else -> "$base/$href"
    }

    private fun runSteps(provider: DubProvider, startVars: Map<String, String>): DubHit? {
        val vars = HashMap<String, String>(startVars)
        var cur = ""

        for (step in provider.steps) {
            val p = step.params
            val from = p.optString("from", "")
            val input = if (from.isEmpty() || from == "cur") cur else (vars[from] ?: return fail(provider, step, "missing var '$from'"))

            val out: String = when (step.op) {
                "http_get" -> {
                    val url = expand(p.optString("url", ""), vars, strict = true)
                        ?: return fail(provider, step, "url has unresolved variables")
                    val referer = p.optString("referer", "").takeIf { it.isNotEmpty() }
                        ?.let { expand(it, vars, strict = false) }
                    fetch(url, referer, provider.headers)
                        ?: return fail(provider, step, "request failed: $url")
                }
                "regex" -> {
                    val regex = buildRegex(p.optString("pattern", ""), p.optBoolean("dotAll", false), p.optBoolean("ignoreCase", false))
                        ?: return fail(provider, step, "bad pattern")
                    regex.find(input)?.groupValues?.getOrNull(p.optInt("group", 1))
                        ?: return fail(provider, step, "no match")
                }
                "json_path" -> DubOps.jsonPath(input, p.optString("path", ""))
                    ?: return fail(provider, step, "path not found")
                "rot13" -> DubOps.rot13(input)
                "reverse" -> input.reversed()
                "trim" -> input.trim()
                "base64" -> DubOps.base64Decode(input, charsetOf(p.optString("charset", "latin1")))
                    ?: return fail(provider, step, "bad base64")
                "shift_key" -> DubOps.shiftKey(
                    input = input,
                    key = p.optString("key", ""),
                    mod = p.optInt("mod", 5),
                    add = p.optInt("add", 1),
                    subtract = p.optString("dir", "sub") != "add"
                )
                "set" -> expand(p.optString("value", ""), vars + ("cur" to cur), strict = false)
                    ?: return fail(provider, step, "bad template")
                "check" -> {
                    if (!passesCheck(p, input, vars)) return fail(provider, step, "check '${p.optString("expect")}' did not match")
                    cur
                }
                else -> return fail(provider, step, "unknown op '${step.op}'")
            }

            cur = out
            val name = p.optString("as", "")
            if (name.isNotEmpty()) vars[name] = out
        }

        val resultValue = (if (provider.result.isNotEmpty()) vars[provider.result] else cur)?.trim().orEmpty()
        if (!(resultValue.startsWith("https://") || resultValue.startsWith("http://"))) {
            Log.w(RUNNER_TAG, "[${provider.id}] final value is not a URL")
            return null
        }
        val headers = LinkedHashMap<String, String>()
        for ((k, v) in provider.audioHeaders) {
            expand(v, vars, strict = false)?.takeIf { it.isNotBlank() }?.let { headers[k] = it }
        }
        Log.i(RUNNER_TAG, "[${provider.id}] audio found: ${resultValue.take(80)}")
        return DubHit(resultValue, headers, provider.label, provider.lang, provider.id)
    }

    /**
     * Compares a number read from the page with one we know (for example the movie length).
     * Skipped when the expected value is not available, so series without a length still work.
     */
    private fun passesCheck(p: org.json.JSONObject, input: String, vars: Map<String, String>): Boolean {
        val expected = vars[p.optString("expect", "")]?.toIntOrNull() ?: return true
        val regex = buildRegex(p.optString("pattern", ""), p.optBoolean("dotAll", false), p.optBoolean("ignoreCase", false))
            ?: return true
        val actual = regex.find(input)?.groupValues?.getOrNull(p.optInt("group", 1))?.toIntOrNull()
            ?: return !p.optBoolean("required", false)
        return abs(actual - expected) <= p.optInt("tolerance", 0)
    }

    private fun fail(provider: DubProvider, step: DubStep, reason: String): DubHit? {
        Log.i(RUNNER_TAG, "[${provider.id}] step '${step.op}' stopped: $reason")
        return null
    }

    private fun buildRegex(pattern: String, dotAll: Boolean, ignoreCase: Boolean): Regex? {
        if (pattern.isEmpty()) return null
        val options = mutableSetOf<RegexOption>()
        if (dotAll) options.add(RegexOption.DOT_MATCHES_ALL)
        if (ignoreCase) options.add(RegexOption.IGNORE_CASE)
        return try {
            Regex(pattern, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun charsetOf(name: String) = when (name.lowercase()) {
        "utf-8", "utf8" -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun fetch(url: String, referer: String?, headers: Map<String, String>): String? {
        if (!url.startsWith("https://")) return null
        val builder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
        for ((k, v) in headers) builder.header(k, v)
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        return http.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.peekBody(MAX_BODY_BYTES).string() else null
        }
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")

        /** Replaces `{name}` with the variable value. With [strict], returns null if any is empty. */
        fun expand(template: String, vars: Map<String, String>, strict: Boolean): String? {
            var missing = false
            val result = PLACEHOLDER.replace(template) { m ->
                val value = vars[m.groupValues[1]]
                if (value.isNullOrEmpty()) {
                    missing = true
                    ""
                } else {
                    value
                }
            }
            return if (strict && missing) null else result
        }
    }
}
