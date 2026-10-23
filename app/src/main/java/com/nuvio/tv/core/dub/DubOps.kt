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
}
