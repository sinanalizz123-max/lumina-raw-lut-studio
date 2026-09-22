package com.lumina.studio.core.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.UUID

class CacheFileManager(private val context: Context) {

    fun cacheDir(): File = context.cacheDir

    fun previewFile(name: String): File = File(context.cacheDir, name)

    fun clearCache(): Boolean {
        var ok = true
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.exists() && !file.deleteRecursively()) ok = false
        }
        return ok
    }

    fun cacheSizeBytes(): Long {
        return context.cacheDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    fun copyUriToCache(uri: Uri, displayName: String?): File? {
        return try {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val ext = extensionFromName(displayName)
            val name = "lumina_${UUID.randomUUID()}" + (if (ext.isNotEmpty()) ".$ext" else "")
            val dest = File(context.cacheDir, name)
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (copied > MAX_CACHE_BYTES) throw IllegalStateException("Cache copy too large")
                    }
                    output.flush()
                }
            } ?: return null
            if (copied <= 0L) {
                dest.delete()
                return null
            }
            dest
        } catch (_: Exception) {
            null
        }
    }

    fun saveBitmapToCache(bitmap: Bitmap, prefix: String = "camera"): File? {
        return try {
            val dest = File(context.cacheDir, "${prefix}_${UUID.randomUUID()}.jpg")
            dest.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            dest
        } catch (_: Exception) {
            null
        }
    }

    fun cacheSizeDisplay(): String {
        val bytes = cacheSizeBytes()
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun extensionFromName(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.US)
            .filter { it.isLetterOrDigit() }.take(8)
    }

    companion object {
        const val MAX_CACHE_BYTES = 200_000_000L
    }
}
