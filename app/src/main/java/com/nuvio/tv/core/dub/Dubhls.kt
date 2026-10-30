package com.nuvio.tv.core.dub

import java.net.URI

/**
 * Picks the audio-only HLS playlist for the wanted language.
 * - Media playlist (no #EXT-X-STREAM-INF): returned as is.
 * - Master playlist without any video stream (audio only): returned as is.
 * - Master playlist with video: returns the separate audio rendition for [lang], or null
 *   if there is none (audio is muxed into the video, or the language is missing).
 */
internal object DubHls {
    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    private val VIDEO_CODEC = Regex("avc|hvc|hev|vp0?9|av01", RegexOption.IGNORE_CASE)

    private fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in ATTR.findAll(line)) {
            out[m.groupValues[1].uppercase()] = m.groupValues[2].trim('"').trim()
        }
        return out
    }

    fun audioUrl(text: String, baseUrl: String, lang: String, name: String): String? {
        if (!text.contains("#EXT-X-STREAM-INF")) return baseUrl

        val lines = text.lines()
        val streams = lines.filter { it.startsWith("#EXT-X-STREAM-INF") }.map { attrs(it) }
        val hasVideo = streams.any { a ->
            a.containsKey("RESOLUTION") || VIDEO_CODEC.containsMatchIn(a["CODECS"].orEmpty())
        }
        if (!hasVideo) return baseUrl

        val media = lines
            .filter { it.startsWith("#EXT-X-MEDIA") && it.uppercase().contains("TYPE=AUDIO") }
            .map { attrs(it) }
            .filter { !it["URI"].isNullOrEmpty() }
        if (media.isEmpty()) return null

        val wantLang = lang.lowercase()
        val wantName = name.lowercase()
        val pick = media.firstOrNull { a ->
            val l = a["LANGUAGE"].orEmpty().lowercase()
            val n = a["NAME"].orEmpty().lowercase()
            when {
                wantName.isNotEmpty() && !n.contains(wantName) -> false
                wantLang.isEmpty() -> true
                else -> l.startsWith(wantLang) || n.contains(wantLang) ||
                    (wantLang == "tr" && (n.contains("türk") || n.contains("turk") || l.startsWith("tur")))
            }
        } ?: return null

        val uri = pick["URI"].orEmpty()
        return try {
            URI(baseUrl).resolve(uri).toString()
        } catch (e: Exception) {
            uri
        }
    }
}
