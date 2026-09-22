package com.lumina.studio.core.lut

import java.io.File
import java.io.InputStream

object LutLimits {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_TEXT_CHARS = 8 * 1024 * 1024
    const val MIN_SIZE = 2
    const val MAX_SIZE = 64
    const val MAX_DATA_POINTS = MAX_SIZE * MAX_SIZE * MAX_SIZE
    const val INLINE_CUBE_THRESHOLD_BYTES = 64 * 1024
    const val REGISTRY_MAX_ENTRIES = 32
    const val SHARE_EMBED_LUT_BYTES = 256 * 1024
    const val FILE_MARKER_PREFIX = "LUT_FILE:"

    fun isFileRef(cubeText: String?): Boolean =
        cubeText != null && cubeText.startsWith(FILE_MARKER_PREFIX)

    fun fileNameFor(presetId: String): String {
        val cleaned = presetId.map { c ->
            if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
        }.joinToString("").take(64).trim('_', '-', ' ')
        val stem = cleaned.ifEmpty {
            "lut_" + (presetId.hashCode().toLong() and 0xffffffffL).toString(16)
        }
        return "$stem.cube"
    }

    fun fileNameFromRef(cubeText: String?): String? {
        if (!isFileRef(cubeText)) return null
        val name = cubeText!!.removePrefix(FILE_MARKER_PREFIX).trim()
        if (name.isEmpty() || '/' in name || '\\' in name || name.length > 80) return null
        return name
    }

    fun fileRefFor(fileName: String): String = FILE_MARKER_PREFIX + fileName

    fun shouldInline(byteCount: Int): Boolean = byteCount < INLINE_CUBE_THRESHOLD_BYTES

    fun readBounded(input: InputStream, maxBytes: Int = MAX_FILE_BYTES): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun readBoundedFile(file: File, maxBytes: Int = MAX_FILE_BYTES): String? {
        return try {
            if (!file.isFile) return null
            if (file.length() > maxBytes) return null
            file.inputStream().use { input ->
                readBounded(input, maxBytes)?.toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun writeAtomically(dir: File, fileName: String, bytes: ByteArray): File? {
        return try {
            if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return null
            dir.mkdirs()
            val dest = File(dir, fileName)
            val tmp = File(dir, "$fileName.part-${java.util.UUID.randomUUID()}")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dest)) {
                dest.writeBytes(bytes)
                tmp.delete()
            }
            if (!dest.isFile || dest.length() != bytes.size.toLong()) {
                dest.delete()
                return null
            }
            dest
        } catch (_: Exception) {
            null
        }
    }
}
