package com.lumina.studio.core.util

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SharedImport(
    val primary: Uri,
    val total: Int = 1
)

object IncomingImages {
    private val _pending = MutableStateFlow<SharedImport?>(null)
    val pending: StateFlow<SharedImport?> = _pending.asStateFlow()

    fun emit(uri: Uri, total: Int = 1) {
        _pending.value = SharedImport(uri, total.coerceAtLeast(1))
    }

    fun emitAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        emit(uris.first(), uris.size)
    }

    fun consume(): SharedImport? {
        val current = _pending.value ?: return null
        _pending.value = null
        return current
    }

    fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return try {
            when (intent.action) {
                Intent.ACTION_VIEW -> {
                    val data = intent.data ?: return emptyList()
                    val type = intent.type ?: ""
                    if (type.startsWith("image/") || type.isEmpty()) listOf(data) else emptyList()
                }
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    if (uri == null) emptyList() else listOf(uri)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    @Suppress("DEPRECATION")
                    val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    list?.filterNotNull()?.takeIf { it.isNotEmpty() } ?: emptyList()
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
