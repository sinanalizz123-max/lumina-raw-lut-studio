package com.lumina.studio.core.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

object ImageFiles {

    val RAW_EXTENSIONS = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "raf", "rw2", "orf", "pef", "srw")

    val SUPPORTED_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "tiff", "tif",
        "heic", "heif", "avif", "bmp", "gif",
        "dng", "cr2", "cr3", "nef", "nrw", "arw", "raf", "rw2", "orf", "pef", "srw"
    )

    const val FORMAT_FOOTER = "RAW / DNG / JPEG / PNG / WebP / TIFF / HEIC / AVIF / BMP / GIF"

    fun extensionOf(displayName: String?): String {
        if (displayName.isNullOrBlank()) return ""
        val dot = displayName.lastIndexOf('.')
        if (dot < 0 || dot == displayName.length - 1) return ""
        return displayName.substring(dot + 1).lowercase(Locale.US)
    }

    fun displayNameOf(context: Context, uri: Uri): String? {
        return try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
            } else {
                uri.lastPathSegment?.substringAfterLast('/')
            }
        } catch (_: Exception) {
            null
        }
    }

    fun mimeOf(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
    }

    fun isRaw(extension: String): Boolean = extension.lowercase(Locale.US) in RAW_EXTENSIONS

    fun isSupported(extension: String, mime: String?): Boolean {
        val ext = extension.lowercase(Locale.US)
        if (ext in SUPPORTED_EXTENSIONS) return true
        if (ext.isEmpty() && mime != null) {
            val m = mime.lowercase(Locale.US)
            return m.startsWith("image/")
        }
        return false
    }

    fun typeBadge(extension: String, mime: String?): String {
        val ext = extension.lowercase(Locale.US)
        if (ext.isNotEmpty()) {
            return when (ext) {
                "jpg", "jpeg" -> "JPEG"
                "tif", "tiff" -> "TIFF"
                "dng" -> "DNG"
                "heic" -> "HEIC"
                "heif" -> "HEIF"
                "avif" -> "AVIF"
                "bmp" -> "BMP"
                "gif" -> "GIF"
                "cr2", "cr3", "nef", "nrw", "arw", "raf", "rw2", "orf", "pef", "srw" -> "RAW"
                else -> ext.uppercase(Locale.US)
            }
        }
        if (mime != null) {
            val sub = mime.substringAfter('/', "").uppercase(Locale.US)
            if (sub.isNotEmpty()) return sub
        }
        return "IMAGE"
    }

    data class Bounds(val width: Int, val height: Int)

    fun decodeBounds(file: File): Bounds {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            Bounds(opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0))
        } catch (_: Exception) {
            Bounds(0, 0)
        }
    }

    fun resolutionLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "–"
        return "$width × $height"
    }
}

fun timeAgo(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - timestampMs).coerceAtLeast(0L)
    val seconds = diff / 1000L
    if (seconds < 60) return "just now"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes min ago"
    val hours = minutes / 60
    if (hours < 24) return "$hours h ago"
    val days = hours / 24
    if (days < 30) return if (days == 1L) "yesterday" else "$days days ago"
    val months = days / 30
    if (months < 12) return if (months == 1L) "1 month ago" else "$months months ago"
    val years = months / 12
    return if (years == 1L) "1 year ago" else "$years years ago"
}
