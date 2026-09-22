package com.lumina.studio.core.data.store

import java.util.Locale

object ProjectStoreLayout {
    const val PROJECTS_DIR = "projects"
    const val TRASH_DIR = ".trash"
    const val LUTS_DIR = "luts"
    const val ORIGINAL_DIR = "original"
    const val PREVIEWS_DIR = "previews"
    const val THUMBNAILS_DIR = "thumbnails"
    const val SIDECARS_DIR = "sidecars"
    const val METADATA_DIR = "metadata"

    val PROJECT_SUBDIRS: List<String> =
        listOf(ORIGINAL_DIR, PREVIEWS_DIR, THUMBNAILS_DIR, SIDECARS_DIR, METADATA_DIR)

    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun sanitizeProjectId(id: String): String {
        if (SAFE_ID.matches(id)) return id
        val cleaned = id.map { c ->
            if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
        }.joinToString("").take(64).trim('_', '-', ' ')
        if (cleaned.isNotEmpty()) return cleaned
        return "proj_" + (id.hashCode().toLong() and 0xffffffffL).toString(16)
    }

    fun sanitizeFileName(name: String?, fallback: String = "photo.jpg"): String {
        if (name.isNullOrBlank()) return fallback
        var base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isBlank()) return fallback
        base = base.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("")
        base = base.trim('.', ' ', '_', '-')
        if (base.isBlank()) return fallback
        if (base.length > 120) {
            val dot = base.lastIndexOf('.')
            base = if (dot > 0) {
                val ext = base.substring(dot).take(12)
                base.substring(0, (120 - ext.length).coerceAtLeast(1)) + ext
            } else {
                base.take(120)
            }
        }
        return base
    }

    fun extensionOf(displayName: String?): String {
        if (displayName.isNullOrBlank()) return ""
        val dot = displayName.lastIndexOf('.')
        if (dot < 0 || dot == displayName.length - 1) return ""
        return displayName.substring(dot + 1).lowercase(Locale.US)
            .filter { it.isLetterOrDigit() }.take(8)
    }

    fun originalFileName(displayName: String?, fallbackExt: String = "jpg"): String {
        val ext = extensionOf(displayName).ifEmpty {
            fallbackExt.lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(8).ifEmpty { "jpg" }
        }
        val rawBase = displayName?.substringBeforeLast('.')?.ifBlank { "photo" } ?: "photo"
        val safeBase = sanitizeFileName(rawBase, "photo").substringBeforeLast('.')
        val stem = safeBase.ifBlank { "photo" }
        return "$stem.$ext"
    }

    fun projectRelativeDir(projectId: String): String =
        "$PROJECTS_DIR/${sanitizeProjectId(projectId)}"

    fun originalRelativeDir(projectId: String): String =
        "${projectRelativeDir(projectId)}/$ORIGINAL_DIR"

    fun isCachePath(cacheDirPath: String, photoUri: String?): Boolean {
        if (photoUri.isNullOrBlank()) return false
        if (photoUri == cacheDirPath) return true
        return photoUri.startsWith(cacheDirPath.trimEnd('/') + "/")
    }

    fun ownerIdOf(relativePath: String): String? {
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0] != PROJECTS_DIR) return null
        return parts[1].takeIf { it.isNotEmpty() }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

data class ProjectRef(val id: String, val photoUri: String?)

data class IntegrityReport(
    val checkedProjects: Int = 0,
    val checkedFiles: Int = 0,
    val rowsWithoutFiles: List<String> = emptyList(),
    val filesWithoutRows: List<String> = emptyList()
)

object StorageIntegrity {
    fun check(
        projects: List<ProjectRef>,
        diskRelativePaths: List<String>,
        exists: (String) -> Boolean
    ): IntegrityReport {
        val knownIds = projects.map { it.id }.toSet()
        val rowsWithout = projects.filter { ref ->
            val uri = ref.photoUri
            uri.isNullOrBlank() || !runCatching { exists(uri) }.getOrDefault(false)
        }.map { it.id }.sorted()
        val filesWithout = diskRelativePaths.filter { rel ->
            val owner = ProjectStoreLayout.ownerIdOf(rel)
            owner == null || owner !in knownIds
        }.sorted()
        return IntegrityReport(
            checkedProjects = projects.size,
            checkedFiles = diskRelativePaths.size,
            rowsWithoutFiles = rowsWithout,
            filesWithoutRows = filesWithout
        )
    }
}
