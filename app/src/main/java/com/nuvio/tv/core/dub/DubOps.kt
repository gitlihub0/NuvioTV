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

    // ---- Rapidrame (HDFilmCehennemi) ---------------------------------------------------

    private const val ALPHA62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val ALPHA95 = (32..126).map { it.toChar() }.joinToString("")
    private val PACKER = Regex(
        """\}\('(.*)', *(\d+|\[\]), *(\d+), *'(.*)'\.split\('\|'\), *(\d+), *(.*)\)\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun unbase(word: String, radix: Int): Int? {
        val alpha = if (radix <= 62) ALPHA62 else ALPHA95
        var value = 0L
        for (ch in word) {
            val idx = alpha.indexOf(ch)
            if (idx < 0) return null
            value = value * radix + idx
            if (value > Int.MAX_VALUE) return null
        }
        return value.toInt()
    }

    /** Unpacks Dean Edwards p,a,c,k,e,d packed JavaScript. Returns null when no packer is found. */
    fun jsUnpack(script: String): String? {
        val m = PACKER.find(script) ?: return null
        val payload = m.groupValues[1].replace("\\\\", "\\").replace("\\'", "'")
        val radix = if (m.groupValues[2] == "[]") 62 else m.groupValues[2].toIntOrNull() ?: return null
        val symtab = m.groupValues[4].split("|")
        return Regex("""\b\w+\b""").replace(payload) { w ->
            val i = unbase(w.value, radix)
            if (i != null && i < symtab.size && symtab[i].isNotEmpty()) symtab[i] else w.value
        }
    }

    /** Finds the last `"PAYLOAD".split("SEP")` in unpacked JS and decodes it to the video URL. */
    fun rapidrameDecode(unpacked: String): String? {
        val m = Regex(""""([^"]+)"\s*\.split\(\s*"([^"]*)"\s*\)""").findAll(unpacked).lastOrNull() ?: return null
        val payload = m.groupValues[1]
        val delim = m.groupValues[2]
        if (payload.isEmpty() || delim.isEmpty()) return null
        return try {
            decodeObfuscated(payload.split(delim))
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeObfuscated(input: List<String>): String {
        val arr = ArrayList(input)
        val myq = arr.size - 2
        val b0r = myq % 7
        val d3n = 8 + (myq % 5)
        val dwtqp = arr.removeAt(d3n)
        val l6w = arr.removeAt(b0r)
        var x = arr.joinToString("")
        if (dwtqp.length > 2048) x = x.reversed()

        var uv47f = 0
        var czb = 0
        for (i in l6w.indices) {
            val z = l6w[i].code
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
                else -> rotLetters(x, (26 - ((seq.code - 96) % 26)) % 26)
            }
        }
        if (l6w.length > 4096) x = jsAtob(x)

        val len = x.length
        val perm = IntArray(len)
        for (i in len - 1 downTo 1) {
            uyiz = (uyiz * 97 + 41) % 65519
            perm[i] = uyiz % (i + 1)
        }
        val chars = x.toCharArray()
        for (i in 1 until len) {
            val j = perm[i]
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }

        var bmrq = y99wm
        val sb = StringBuilder(len)
        for (ch in chars) {
            val z = ch.code
            bmrq = (bmrq * 5 + hkaa) % 256
            sb.append((z xor bmrq).toChar())
            bmrq = (bmrq + z) % 256
        }
        return sb.toString()
    }

    private fun rotLetters(s: String, shift: Int): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                val base = if (ch <= 'Z') 65 else 97
                sb.append((((ch.code - base + shift) % 26) + base).toChar())
            } else sb.append(ch)
        }
        return sb.toString()
    }

    /** JS atob(): every byte becomes one char (ISO-8859-1). */
    private fun jsAtob(s: String): String {
        val cleaned = s.replace(Regex("""\s"""), "")
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.ISO_8859_1)
    }
}
