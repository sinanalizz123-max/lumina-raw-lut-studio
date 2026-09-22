package com.lumina.studio.core.data.store

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

object ProjectStore {

    const val MAX_IMPORT_BYTES = 200_000_000L

    data class ProjectPaths(
        val projectDir: File,
        val originalDir: File,
        val previewsDir: File,
        val thumbnailsDir: File,
        val sidecarsDir: File,
        val metadataDir: File
    )

    fun projectsRoot(context: Context): File =
        File(context.filesDir, ProjectStoreLayout.PROJECTS_DIR)

    fun lutsDir(context: Context): File =
        File(context.filesDir, ProjectStoreLayout.LUTS_DIR).apply { mkdirs() }

    fun pathsFor(context: Context, projectId: String): ProjectPaths {
        val root = File(projectsRoot(context), ProjectStoreLayout.sanitizeProjectId(projectId))
        root.mkdirs()
        fun sub(name: String): File = File(root, name).apply { mkdirs() }
        return ProjectPaths(
            projectDir = root,
            originalDir = sub(ProjectStoreLayout.ORIGINAL_DIR),
            previewsDir = sub(ProjectStoreLayout.PREVIEWS_DIR),
            thumbnailsDir = sub(ProjectStoreLayout.THUMBNAILS_DIR),
            sidecarsDir = sub(ProjectStoreLayout.SIDECARS_DIR),
            metadataDir = sub(ProjectStoreLayout.METADATA_DIR)
        )
    }

    fun originalExists(photoUri: String?): Boolean {
        if (photoUri.isNullOrBlank()) return false
        return try {
            val file = File(photoUri)
            file.isFile && file.exists()
        } catch (_: Exception) {
            false
        }
    }

    fun copyUriToOriginal(
        context: Context,
        projectId: String,
        uri: Uri,
        displayName: String?
    ): File? {
        var partial: File? = null
        return try {
            val paths = pathsFor(context, projectId)
            val name = displayName ?: displayNameOf(context, uri)
            val dest = resolveUniqueFile(paths.originalDir, ProjectStoreLayout.originalFileName(name))
            partial = File(paths.originalDir, dest.name + ".part-" + UUID.randomUUID())
            val target = partial!!
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val expected = sourceSizeOf(context, uri)
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (copied > MAX_IMPORT_BYTES) {
                            runCatching { output.flush() }
                            throw IllegalStateException("Import too large")
                        }
                    }
                    output.flush()
                }
            } ?: run {
                target.delete()
                return null
            }
            if (copied <= 0L || (expected > 0L && copied != expected)) {
                target.delete()
                return null
            }
            if (!target.renameTo(dest)) {
                target.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                target.delete()
            }
            if (!dest.isFile || dest.length() != copied) {
                dest.delete()
                return null
            }
            dest
        } catch (_: Exception) {
            runCatching { partial?.delete() }
            null
        }
    }

    fun copyFileIntoOriginal(
        context: Context,
        projectId: String,
        src: File,
        displayName: String?
    ): File? {
        var partial: File? = null
        return try {
            if (!src.isFile || !src.exists() || src.length() <= 0L) return null
            val paths = pathsFor(context, projectId)
            val dest = resolveUniqueFile(
                paths.originalDir,
                ProjectStoreLayout.originalFileName(displayName ?: src.name)
            )
            partial = File(paths.originalDir, dest.name + ".part-" + UUID.randomUUID())
            val target = partial!!
            src.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() != src.length() || target.length() <= 0L) {
                target.delete()
                return null
            }
            if (!target.renameTo(dest)) {
                target.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                target.delete()
            }
            if (!dest.isFile || dest.length() != src.length()) {
                dest.delete()
                return null
            }
            dest
        } catch (_: Exception) {
            runCatching { partial?.delete() }
            null
        }
    }

    fun saveBitmapToOriginal(
        context: Context,
        projectId: String,
        bitmap: Bitmap,
        baseName: String = "camera_capture"
    ): File? {
        var partial: File? = null
        return try {
            val paths = pathsFor(context, projectId)
            val dest = resolveUniqueFile(
                paths.originalDir,
                ProjectStoreLayout.originalFileName("$baseName.jpg")
            )
            partial = File(paths.originalDir, dest.name + ".part-" + UUID.randomUUID())
            val target = partial!!
            var ok = false
            target.outputStream().use { out ->
                ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (!ok || target.length() <= 0L) {
                target.delete()
                return null
            }
            if (!target.renameTo(dest)) {
                target.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                target.delete()
            }
            if (!dest.isFile || dest.length() <= 0L) {
                dest.delete()
                return null
            }
            dest
        } catch (_: Exception) {
            runCatching { partial?.delete() }
            null
        }
    }

    fun deleteProjectFiles(context: Context, projectId: String): Boolean {
        return try {
            val root = File(projectsRoot(context), ProjectStoreLayout.sanitizeProjectId(projectId))
            if (!root.exists()) return true
            root.deleteRecursively()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Move a project directory into app-private trash instead of deleting it.
     * The returned token is required to restore it. This makes the single-item
     * UI "Undo" action real without exposing deleted originals to the gallery.
     */
    fun trashProjectFiles(context: Context, projectId: String): String? {
        return try {
            val id = ProjectStoreLayout.sanitizeProjectId(projectId)
            val root = File(projectsRoot(context), id)
            if (!root.exists()) return null
            val trashRoot = File(context.filesDir, ProjectStoreLayout.TRASH_DIR).apply { mkdirs() }
            val token = id + "-" + UUID.randomUUID().toString()
            val target = File(trashRoot, token)
            if (!root.renameTo(target)) return null
            token
        } catch (_: Exception) {
            null
        }
    }

    fun restoreTrashedProjectFiles(context: Context, projectId: String, token: String): Boolean {
        return try {
            val safeToken = token.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)
            val source = File(File(context.filesDir, ProjectStoreLayout.TRASH_DIR), safeToken)
            val target = File(projectsRoot(context), ProjectStoreLayout.sanitizeProjectId(projectId))
            if (!source.isDirectory || target.exists()) return false
            target.parentFile?.mkdirs()
            source.renameTo(target)
        } catch (_: Exception) {
            false
        }
    }

    fun deleteOwnedFile(context: Context, photoUri: String?): Boolean {
        if (photoUri.isNullOrBlank()) return false
        return try {
            val file = File(photoUri)
            if (!file.isFile) return false
            val canonical = file.canonicalPath
            val cacheRoot = context.cacheDir.canonicalPath
            val filesRoot = projectsRoot(context).canonicalPath
            val owned = canonical == cacheRoot || canonical.startsWith(cacheRoot + File.separator) ||
                canonical == filesRoot || canonical.startsWith(filesRoot + File.separator)
            if (!owned) return false
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun projectSize(context: Context, projectId: String): Long {
        return try {
            val root = File(projectsRoot(context), ProjectStoreLayout.sanitizeProjectId(projectId))
            if (!root.exists()) return 0L
            root.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (_: Exception) {
            0L
        }
    }

    fun projectsSize(context: Context): Long {
        return try {
            val root = projectsRoot(context)
            if (!root.exists()) return 0L
            root.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (_: Exception) {
            0L
        }
    }

    fun scanDiskRelativePaths(context: Context): List<String> {
        return try {
            val root = projectsRoot(context)
            if (!root.exists()) return emptyList()
            val base = root.absolutePath.trimEnd('/') + "/"
            root.walkTopDown()
                .filter { it.isFile }
                .map { it.absolutePath.removePrefix(base) }
                .filter { it.isNotEmpty() }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveUniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (n < 1000) {
            candidate = File(dir, "$stem-$n$ext")
            if (!candidate.exists()) return candidate
            n++
        }
        return File(dir, "$stem-${UUID.randomUUID()}$ext")
    }

    private fun displayNameOf(context: Context, uri: Uri): String? {
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

    private fun sourceSizeOf(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }
}
