package com.lumina.studio.core.library

/**
 * M13 tiny JSON reader/writer (pure JVM, no dependencies).
 *
 * Only what the library codecs need: objects, arrays, strings, numbers,
 * booleans, null. [parse] returns null on any malformed input — callers
 * fall back to empty state, never crash. Numbers keep their raw text so
 * codecs can parse Long/Float/Double without precision loss.
 */
sealed interface JsonVal {
    data class Obj(val map: Map<String, JsonVal>) : JsonVal
    data class Arr(val items: List<JsonVal>) : JsonVal
    data class Str(val value: String) : JsonVal
    data class Num(val raw: String) : JsonVal
    data class Bool(val value: Boolean) : JsonVal
    data object Null : JsonVal
}

fun JsonVal.Obj.string(key: String): String? = (map[key] as? JsonVal.Str)?.value

fun JsonVal.Obj.long(key: String): Long? {
    val raw = (map[key] as? JsonVal.Num)?.raw ?: return null
    return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
}

fun JsonVal.Obj.float(key: String): Float? {
    val raw = (map[key] as? JsonVal.Num)?.raw ?: return null
    return raw.toFloatOrNull()
}

fun JsonVal.Obj.bool(key: String): Boolean? = (map[key] as? JsonVal.Bool)?.value

fun JsonVal.Obj.array(key: String): List<JsonVal>? = (map[key] as? JsonVal.Arr)?.items

fun JsonVal.Obj.obj(key: String): JsonVal.Obj? = map[key] as? JsonVal.Obj

object MiniJson {
    private const val MAX_DEPTH = 32

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 2)
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') {
                    out.append("\\u")
                    out.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        return out.toString()
    }

    fun quoted(value: String): String = "\"" + escape(value) + "\""

    fun parse(text: String): JsonVal? {
        if (text.isBlank()) return null
        // Guard against hostile oversized payloads in a prefs string.
        if (text.length > 512 * 1024) return null
        return try {
            val parser = Parser(text)
            parser.ws()
            val value = parser.readValue(0) ?: return null
            parser.ws()
            if (!parser.atEnd()) null else value
        } catch (_: Exception) {
            null
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        fun readValue(depth: Int): JsonVal? {
            if (depth > MAX_DEPTH || atEnd()) return null
            return when (s[i]) {
                '{' -> readObj(depth)
                '[' -> readArr(depth)
                '"' -> readStr()?.let { JsonVal.Str(it) }
                't' -> readLit("true", JsonVal.Bool(true))
                'f' -> readLit("false", JsonVal.Bool(false))
                'n' -> readLit("null", JsonVal.Null)
                else -> readNum()
            }
        }

        private fun readLit(word: String, value: JsonVal): JsonVal? {
            if (!s.startsWith(word, i)) return null
            i += word.length
            return value
        }

        private fun readNum(): JsonVal? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' ||
                    s[i] == '.' || s[i] == 'e' || s[i] == 'E')
            ) {
                i++
            }
            if (start == i) return null
            val raw = s.substring(start, i)
            if (raw.toDoubleOrNull() == null) return null
            return JsonVal.Num(raw)
        }

        private fun readStr(): String? {
            if (atEnd() || s[i] != '"') return null
            i++
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) return null
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) return null
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                    }
                    else -> {
                        if (c < ' ') return null
                        out.append(c)
                    }
                }
            }
        }

        private fun readObj(depth: Int): JsonVal? {
            i++ // {
            ws()
            val map = LinkedHashMap<String, JsonVal>()
            if (!atEnd() && s[i] == '}') {
                i++
                return JsonVal.Obj(map)
            }
            while (true) {
                ws()
                val key = readStr() ?: return null
                ws()
                if (atEnd() || s[i] != ':') return null
                i++
                ws()
                val value = readValue(depth + 1) ?: return null
                map[key] = value
                ws()
                if (atEnd()) return null
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return JsonVal.Obj(map) }
                    else -> return null
                }
            }
        }

        private fun readArr(depth: Int): JsonVal? {
            i++ // [
            ws()
            val items = ArrayList<JsonVal>()
            if (!atEnd() && s[i] == ']') {
                i++
                return JsonVal.Arr(items)
            }
            while (true) {
                ws()
                items.add(readValue(depth + 1) ?: return null)
                ws()
                if (atEnd()) return null
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return JsonVal.Arr(items) }
                    else -> return null
                }
            }
        }
    }
}
