package com.lumina.studio.core.ai

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * M12 AI/heuristic field cache (§§24-25).
 *
 * Fields are computed once per project+source at analysis size, stored as a
 * small binary blob in `files/<id>/metadata/` (next to the existing project
 * files — see ProjectStore.pathsFor), and reused on reopen with NO recompute.
 * The recipe ([EditMask]) stores only `{tool, cacheKey, feather/opacity/
 * invert/op}` — never the bitmap. [AiMaskFieldStore] is the in-memory side.
 *
 * Format: magic "LAI1" + version + w + h + w*h floats (all big-endian).
 * Analysis fields are <= 256px on the long side, so blobs stay < 256KB.
 */
object AiMaskCache {
    const val KIND_SUBJECT = "subject"
    const val KIND_SKY = "sky"
    const val FILE_VERSION = 1
    const val MAX_FIELD_PIXELS = 512 * 512

    private const val MAGIC = 0x4C414931 // "LAI1"

    fun kindFor(toolName: String?): String? = when (toolName) {
        "AI_SUBJECT" -> KIND_SUBJECT
        "AI_SKY" -> KIND_SKY
        else -> null
    }

    /**
     * Stable source hash for a project image. File-backed sources hash
     * path+length+mtime (cheap, no full read); content URIs fall back to the
     * URI string (length/mtime -1). Stable across reopen either way.
     */
    fun sourceHash(photoUri: String?, length: Long, lastModified: Long): String {
        val seed = "${photoUri.orEmpty()}|$length|$lastModified"
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(seed.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(bytes.size * 2)
            for (b in bytes) hex.append(String.format("%02x", b))
            hex.toString().take(16)
        } catch (_: Exception) {
            (seed.hashCode().toLong() and 0xffffffffL).toString(16)
        }
    }

    fun cacheKey(kind: String, sourceHash: String, width: Int, height: Int): String {
        val safeKind = if (kind == KIND_SKY) KIND_SKY else KIND_SUBJECT
        val safeHash = sourceHash.filter { it.isLetterOrDigit() }.take(32).ifEmpty { "unknown" }
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        return "ai_" + safeKind + "_" + safeHash + "_" + w + "x" + h + "_v" + FILE_VERSION + ".bin"
    }

    fun cacheFile(metadataDir: File, cacheKey: String): File {
        val safe = cacheKey.map { c ->
            if (c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') c else '_'
        }.joinToString("").take(96)
        return File(metadataDir, safe.ifEmpty { "ai_unknown.bin" })
    }

    fun save(file: File, mask: FloatArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || mask.size != width * height) return false
        if (mask.size > MAX_FIELD_PIXELS) return false
        return try {
            file.parentFile?.mkdirs()
            // Atomic-ish write (tmp + rename), mirroring ProjectStore.
            val tmp = File(file.parentFile, file.name + ".part-" + java.util.UUID.randomUUID())
            var ok = false
            try {
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(FILE_VERSION)
                    out.writeInt(width)
                    out.writeInt(height)
                    for (v in mask) out.writeFloat(v.coerceIn(0f, 1f))
                    out.flush()
                }
                ok = tmp.renameTo(file)
                if (!ok) {
                    tmp.inputStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    tmp.delete()
                    ok = file.isFile && file.length() > 0L
                }
            } finally {
                if (!ok) tmp.delete()
            }
            ok && file.isFile && file.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    data class LoadedField(val mask: FloatArray, val width: Int, val height: Int)

    /** Null on missing/corrupt/version-mismatched files — caller falls back. */
    fun load(file: File): LoadedField? {
        return try {
            if (!file.isFile) return null
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                if (magic != MAGIC || version != FILE_VERSION) return null
                val w = input.readInt()
                val h = input.readInt()
                if (w <= 0 || h <= 0 || w * h > MAX_FIELD_PIXELS) return null
                val mask = FloatArray(w * h)
                for (i in mask.indices) {
                    val v = input.readFloat()
                    if (!v.isFinite()) return null
                    mask[i] = v.coerceIn(0f, 1f)
                }
                LoadedField(mask, w, h)
            }
        } catch (_: Exception) {
            null
        }
    }
}
