package com.lumina.studio.core.lut

data class LutCube(
    val title: String?,
    val size: Int,
    val is3D: Boolean,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    val data: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LutCube) return false
        return title == other.title && size == other.size && is3D == other.is3D &&
            domainMin.contentEquals(other.domainMin) &&
            domainMax.contentEquals(other.domainMax) &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + size
        result = 31 * result + is3D.hashCode()
        result = 31 * result + domainMin.contentHashCode()
        result = 31 * result + domainMax.contentHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

sealed interface CubeParseResult {
    data class Ok(val lut: LutCube) : CubeParseResult
    data class Err(val reason: String) : CubeParseResult
}

private class CubeSyntaxException(message: String) : Exception(message)

object CubeParser {
    const val MIN_SIZE = 2
    const val MAX_SIZE = 64
    const val MAX_TEXT_CHARS = 16 * 1024 * 1024

    fun parse(text: String, fallbackTitle: String? = null): CubeParseResult {
        return try {
            parseThrowing(text, fallbackTitle)
        } catch (e: CubeSyntaxException) {
            CubeParseResult.Err(e.message ?: "Invalid .CUBE file")
        } catch (e: Exception) {
            CubeParseResult.Err("Could not parse .CUBE file (${e.message ?: "unknown error"})")
        }
    }

    private fun parseThrowing(text: String, fallbackTitle: String?): CubeParseResult {
        if (text.length > MAX_TEXT_CHARS) {
            throw CubeSyntaxException("File is too large to be a .CUBE LUT (over 16 MB)")
        }
        if (text.isBlank()) throw CubeSyntaxException("Empty file — no LUT data found")

        var title: String? = null
        var size1D: Int? = null
        var size3D: Int? = null
        var domainMin: FloatArray? = null
        var domainMax: FloatArray? = null
        val rows = ArrayList<FloatArray>()
        var dataStarted = false

        val lines = text.split('\n')
        for ((index, rawLine) in lines.withIndex()) {
            val lineNo = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val firstSpace = line.indexOfFirst { it == ' ' || it == '\t' }
            val keyword = if (firstSpace < 0) line else line.substring(0, firstSpace)
            val rest = if (firstSpace < 0) "" else line.substring(firstSpace + 1).trim()

            when (keyword) {
                "TITLE" -> {
                    if (rest.isEmpty()) throw CubeSyntaxException("TITLE is empty (line $lineNo)")
                    title = rest.unquoteTitle()
                }
                "LUT_1D_SIZE", "LUT_3D_SIZE" -> {
                    val size = rest.toIntOrNull()
                        ?: throw CubeSyntaxException("$keyword needs an integer size (line $lineNo)")
                    if (size < MIN_SIZE || size > MAX_SIZE) {
                        throw CubeSyntaxException(
                            "$keyword size $size is out of range ($MIN_SIZE..$MAX_SIZE) (line $lineNo)"
                        )
                    }
                    if (keyword == "LUT_1D_SIZE") {
                        if (size1D != null) throw CubeSyntaxException("Duplicate LUT_1D_SIZE (line $lineNo)")
                        if (size3D != null) {
                            throw CubeSyntaxException("Cannot declare both LUT_1D_SIZE and LUT_3D_SIZE (line $lineNo)")
                        }
                        size1D = size
                    } else {
                        if (size3D != null) throw CubeSyntaxException("Duplicate LUT_3D_SIZE (line $lineNo)")
                        if (size1D != null) {
                            throw CubeSyntaxException("Cannot declare both LUT_1D_SIZE and LUT_3D_SIZE (line $lineNo)")
                        }
                        size3D = size
                    }
                }
                "DOMAIN_MIN", "DOMAIN_MAX" -> {
                    val parts = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (parts.size != 3) {
                        throw CubeSyntaxException("$keyword needs exactly 3 numbers (line $lineNo)")
                    }
                    val values = FloatArray(3)
                    for (i in 0..2) {
                        val v = parts[i].toFloatOrNull()
                            ?: throw CubeSyntaxException("$keyword has a non-numeric value '${parts[i]}' (line $lineNo)")
                        if (!v.isFinite()) throw CubeSyntaxException("$keyword must be finite (line $lineNo)")
                        values[i] = v
                    }
                    if (keyword == "DOMAIN_MIN") domainMin = values else domainMax = values
                }
                else -> {
                    if (keyword.toFloatOrNull() != null || keyword.startsWith("-") ||
                        keyword.startsWith("+") || keyword.startsWith(".")
                    ) {
                        val declared = size1D ?: size3D
                            ?: throw CubeSyntaxException(
                                "LUT data found before LUT_1D_SIZE/LUT_3D_SIZE (line $lineNo)"
                            )
                        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        if (parts.size != 3) {
                            throw CubeSyntaxException("Each DATA line needs exactly 3 numbers (line $lineNo)")
                        }
                        val triple = FloatArray(3)
                        for (i in 0..2) {
                            val v = parts[i].toFloatOrNull()
                                ?: throw CubeSyntaxException(
                                    "Non-numeric DATA value '${parts[i]}' (line $lineNo)"
                                )
                            if (!v.isFinite()) {
                                throw CubeSyntaxException("DATA values must be finite (line $lineNo)")
                            }
                            triple[i] = v
                        }
                        val expected = if (size3D != null) declared * declared * declared else declared
                        if (rows.size >= expected) {
                            throw CubeSyntaxException(
                                "Too many DATA lines: expected $expected for size $declared (line $lineNo)"
                            )
                        }
                        rows.add(triple)
                        dataStarted = true
                    }
                }
            }
        }

        val s1 = size1D
        val s3 = size3D
        if (s1 == null && s3 == null) {
            throw CubeSyntaxException("Missing LUT_1D_SIZE or LUT_3D_SIZE header")
        }
        val size = (s3 ?: s1)!!
        val is3D = s3 != null
        val expected = if (is3D) size * size * size else size
        if (rows.size != expected) {
            throw CubeSyntaxException(
                "Expected $expected DATA lines for size $size but found ${rows.size}"
            )
        }
        val min = domainMin ?: floatArrayOf(0f, 0f, 0f)
        val max = domainMax ?: floatArrayOf(1f, 1f, 1f)
        for (i in 0..2) {
            if (min[i] >= max[i]) {
                throw CubeSyntaxException("DOMAIN_MIN must be below DOMAIN_MAX on every channel")
            }
        }
        if (!dataStarted) throw CubeSyntaxException("No DATA lines found")

        val flat = FloatArray(rows.size * 3)
        for (i in rows.indices) {
            flat[i * 3] = rows[i][0]
            flat[i * 3 + 1] = rows[i][1]
            flat[i * 3 + 2] = rows[i][2]
        }
        return CubeParseResult.Ok(
            LutCube(
                title = title ?: fallbackTitle,
                size = size,
                is3D = is3D,
                domainMin = min,
                domainMax = max,
                data = flat
            )
        )
    }

    private fun String.unquoteTitle(): String {
        val t = trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length - 1)
        }
        return t
    }
}
