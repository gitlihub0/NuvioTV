package com.nuvio.tv.core.dub

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

/** Small, fixed set of text transforms a remote recipe can chain together. */
internal object DubOps {

    fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when (c) {
                in 'a'..'z' -> sb.append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                in 'A'..'Z' -> sb.append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Lenient Base64 decode: ignores stray characters, accepts the URL-safe alphabet and
     * missing padding. With [charset] ISO-8859-1 every byte survives as one char, which the
     * byte-shifting step relies on.
     */
    fun base64Decode(input: String, charset: Charset): String? {
        val cleaned = StringBuilder(input.length)
        for (c in input) {
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' -> cleaned.append(c)
                c == '-' -> cleaned.append('+')
                c == '_' -> cleaned.append('/')
            }
        }
        if (cleaned.length % 4 == 1) cleaned.setLength(cleaned.length - 1)
        while (cleaned.length % 4 != 0) cleaned.append('=')
        return try {
            String(Base64.decode(cleaned.toString(), Base64.DEFAULT), charset)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Shifts every char by a key-dependent offset: offset(i) = key[i % key.length] % mod + add.
     * [subtract] true: out = in - offset, false: out = in + offset.
     */
    fun shiftKey(input: String, key: String, mod: Int, add: Int, subtract: Boolean): String {
        if (key.isEmpty() || mod <= 0) return input
        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            val value = input[i].code and 0xFF
            val offset = key[i % key.length].code % mod + add
            val shifted = if (subtract) value - offset else value + offset
            sb.append(shifted.toChar())
        }
        return sb.toString()
    }

    /** Reads `a.b[0].c` style paths from a JSON text. Returns null when anything is missing. */
    fun jsonPath(json: String, path: String): String? {
        var node: Any? = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val tokens = Regex("""([^.\[\]]+)|\[(\d+)\]""")
        for (m in tokens.findAll(path)) {
            val key = m.groups[1]?.value
            val index = m.groups[2]?.value?.toIntOrNull()
            node = when {
                key != null -> (node as? JSONObject)?.opt(key)
                index != null -> (node as? JSONArray)?.opt(index)
                else -> null
            } ?: return null
        }
        return when {
            node == null || node == JSONObject.NULL -> null
            node is String -> node
            else -> node.toString()
        }
    }

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line).associate { it.groupValues[1].uppercase() to it.groupValues[2].trim('"') }

    /**
     * Makes sure an HLS link is audio only. A media playlist or an audio-only master is returned
     * unchanged. A master playlist that carries video is replaced by the URI of its separate
     * audio rendition matching [lang] / [name]; returns null when there is none (so we never
     * attach a video stream as "audio").
     */
    fun hlsAudio(text: String, playlistUrl: String, lang: String, name: String): String? {
        if (!text.contains("#EXT-X-STREAM-INF")) return playlistUrl
        val lines = text.lines()
        val infs = lines.filter { it.startsWith("#EXT-X-STREAM-INF") }.map { attrs(it) }
        val hasVideo = infs.any {
            it.containsKey("RESOLUTION") ||
                Regex("avc|hvc|hev|vp0?9|av01", RegexOption.IGNORE_CASE).containsMatchIn(it["CODECS"].orEmpty())
        }
        if (!hasVideo) return playlistUrl
        val wantLang = lang.lowercase()
        val wantName = name.lowercase()
        val media = lines.filter { it.startsWith("#EXT-X-MEDIA") && it.uppercase().contains("TYPE=AUDIO") }
            .map { attrs(it) }.filter { !it["URI"].isNullOrBlank() }
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
        return try {
            java.net.URI(playlistUrl).resolve(pick["URI"]!!).toString()
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------------------------
    // js_unpack: Dean Edwards p,a,c,k,e,d packer (same logic as dubtool.py)
    // ------------------------------------------------------------------------------------

    private const val ALNUM62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val ASCII_ALPHA = (32..126).map { it.toChar() }.joinToString("")

    private val PACKER = Regex(
        """\}\('(.*)', *(\d+|\[\]), *(\d+), *'(.*)'\.split\('\|'\), *(\d+), *(.*)\)\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val WORD = Regex("""\b\w+\b""")

    private fun unbase(word: String, radix: Int): Long? {
        if (word.length > 8) return null
        val alpha = if (radix <= 62) ALNUM62 else ASCII_ALPHA
        var value = 0L
        var mul = 1L
        for (ch in word.reversed()) {
            val idx = alpha.indexOf(ch)
            if (idx < 0) return null
            value += idx * mul
            mul *= radix
        }
        return value
    }

    /** Returns the unpacked script, or null when no packed script is found. */
    fun jsUnpack(script: String): String? {
        val m = PACKER.find(script) ?: return null
        val payload = m.groupValues[1].replace("\\\\", "\\").replace("\\'", "'")
        val radix = if (m.groupValues[2] == "[]") 62 else m.groupValues[2].toInt()
        val symtab = m.groupValues[4].split("|")
        return WORD.replace(payload) { w ->
            val i = unbase(w.value, radix)
            if (i != null && i < symtab.size && symtab[i.toInt()].isNotEmpty()) symtab[i.toInt()] else w.value
        }
    }

    // ------------------------------------------------------------------------------------
    // rapidrame_decode (same logic as dubtool.py)
    // ------------------------------------------------------------------------------------

    private fun jsAtob(s: String): String {
        var t = s.filter { !it.isWhitespace() }
        t += when (t.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return String(Base64.decode(t, Base64.DEFAULT), Charsets.ISO_8859_1)
    }

    private fun rotLetters(s: String, shift: Int): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                val base = if (ch <= 'Z') 65 else 97
                val v = (((ch.code - base + shift) % 26) + 26) % 26 + base
                sb.append(v.toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun decodeObfuscated(parts: List<String>): String {
        val arr = parts.toMutableList()
        val myq = arr.size - 2
        require(myq >= 0) { "payload too short" }
        val b0r = myq % 7
        val d3n = 8 + (myq % 5)
        require(d3n < arr.size) { "payload too short" }
        val dwtqp = arr.removeAt(d3n)
        require(b0r < arr.size) { "payload too short" }
        val l6w = arr.removeAt(b0r)
        var x = arr.joinToString("")
        if (dwtqp.length > 2048) x = x.reversed()

        var uv47f = 0
        var czb = 0
        for ((i, ch) in l6w.withIndex()) {
            val z = ch.code
            uv47f = (uv47f * 37 + z) % 241
            czb = (czb + ((z shl 1) xor i)) and 255
        }
        val y99wm = (uv47f * 3 + czb) % 256
        val hkaa = (czb % 11) + 5
        var uyiz = ((czb * 251 + uv47f) % 65519) + 1

        for (i in dwtqp.length - 1 downTo 0) {
            val seq = dwtqp[i]
            x = when (seq) {
                '7' -> jsAtob(x)
                '3' -> x.reversed()
                else -> rotLetters(x, (26 - (seq.code - 96) % 26) % 26)
            }
        }
        if (l6w.length > 4096) x = jsAtob(x)

        val n = x.length
        val perm = IntArray(n)
        for (i in n - 1 downTo 1) {
            uyiz = (uyiz * 97 + 41) % 65519
            perm[i] = uyiz % (i + 1)
        }
        val chars = x.toCharArray()
        for (i in 1 until n) {
            val j = perm[i]
            val t = chars[i]
            chars[i] = chars[j]
            chars[j] = t
        }
        var bmrq = y99wm
        val out = StringBuilder(n)
        for (ch in chars) {
            val z = ch.code
            bmrq = (bmrq * 5 + hkaa) % 256
            out.append((z xor bmrq).toChar())
            bmrq = (bmrq + z) % 256
        }
        return out.toString()
    }

    private val SPLIT_CALL = Regex(""""([^"]+)"\s*\.split\(\s*"([^"]*)"\s*\)""")

    /** Throws IllegalArgumentException / IllegalStateException on malformed input. */
    fun rapidrameDecode(unpacked: String): String {
        val m = SPLIT_CALL.findAll(unpacked).lastOrNull()
            ?: throw IllegalArgumentException("payload.split not found")
        val payload = m.groupValues[1]
        val delim = m.groupValues[2]
        require(payload.isNotEmpty() && delim.isNotEmpty()) { "empty payload/delimiter" }
        return decodeObfuscated(payload.split(delim))
    }
}
