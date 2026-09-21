package com.lumina.studio.store

import com.lumina.studio.core.data.store.ProjectRef
import com.lumina.studio.core.data.store.ProjectStoreLayout
import com.lumina.studio.core.data.store.StorageIntegrity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectStoreLayoutTest {

    @Test
    fun `sanitizeProjectId keeps uuids untouched`() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(id, ProjectStoreLayout.sanitizeProjectId(id))
    }

    @Test
    fun `sanitizeProjectId neutralizes traversal`() {
        val cleaned = ProjectStoreLayout.sanitizeProjectId("../../etc/passwd")
        assertFalse(cleaned.contains('/'))
        assertFalse(cleaned.contains('.'))
        assertTrue(cleaned.isNotEmpty())
    }

    @Test
    fun `sanitizeProjectId fallback is deterministic`() {
        val a = ProjectStoreLayout.sanitizeProjectId("...")
        val b = ProjectStoreLayout.sanitizeProjectId("...")
        assertEquals(a, b)
        assertTrue(a.startsWith("proj_"))
    }

    @Test
    fun `originalFileName falls back for null`() {
        assertEquals("photo.jpg", ProjectStoreLayout.originalFileName(null))
        assertEquals("photo.jpg", ProjectStoreLayout.originalFileName("   "))
    }

    @Test
    fun `originalFileName sanitizes and keeps extension`() {
        assertEquals("My_Photo__1.jpg", ProjectStoreLayout.originalFileName("My Photo (1).JPG"))
        assertEquals("IMG_001.dng", ProjectStoreLayout.originalFileName("IMG_001.dng"))
        assertEquals("scan.jpg", ProjectStoreLayout.originalFileName("scan"))
    }

    @Test
    fun `extensionOf lowercases`() {
        assertEquals("tiff", ProjectStoreLayout.extensionOf("a.TIFF"))
        assertEquals("", ProjectStoreLayout.extensionOf(null))
        assertEquals("", ProjectStoreLayout.extensionOf("noext"))
    }

    @Test
    fun `isCachePath matches subtree only`() {
        assertTrue(ProjectStoreLayout.isCachePath("/data/cache", "/data/cache/lumina_x.jpg"))
        assertFalse(ProjectStoreLayout.isCachePath("/data/cache", "/data/cache2/x.jpg"))
        assertFalse(ProjectStoreLayout.isCachePath("/data/cache", "/data/files/x.jpg"))
        assertFalse(ProjectStoreLayout.isCachePath("/data/cache", null))
    }

    @Test
    fun `ownerIdOf parses projects tree`() {
        assertEquals("abc", ProjectStoreLayout.ownerIdOf("projects/abc/original/x.jpg"))
        assertEquals(null, ProjectStoreLayout.ownerIdOf("luts/x.cube"))
        assertEquals(null, ProjectStoreLayout.ownerIdOf("projects"))
        assertEquals(null, ProjectStoreLayout.ownerIdOf("stray.jpg"))
    }

    @Test
    fun `formatBytes tiers`() {
        assertEquals("0 B", ProjectStoreLayout.formatBytes(0L))
        assertEquals("512 B", ProjectStoreLayout.formatBytes(512L))
        assertEquals("2.0 KB", ProjectStoreLayout.formatBytes(2048L))
        assertEquals("5.0 MB", ProjectStoreLayout.formatBytes(5L * 1024L * 1024L))
    }

    @Test
    fun `integrity check passes for healthy store`() {
        val report = StorageIntegrity.check(
            projects = listOf(ProjectRef("a", "/files/projects/a/original/x.jpg")),
            diskRelativePaths = listOf("projects/a/original/x.jpg"),
            exists = { true }
        )
        assertEquals(1, report.checkedProjects)
        assertEquals(1, report.checkedFiles)
        assertTrue(report.rowsWithoutFiles.isEmpty())
        assertTrue(report.filesWithoutRows.isEmpty())
    }

    @Test
    fun `integrity check flags missing originals`() {
        val report = StorageIntegrity.check(
            projects = listOf(
                ProjectRef("gone", "/files/projects/gone/original/x.jpg"),
                ProjectRef("blank", null)
            ),
            diskRelativePaths = emptyList(),
            exists = { false }
        )
        assertEquals(listOf("blank", "gone"), report.rowsWithoutFiles)
    }

    @Test
    fun `integrity check flags orphan files`() {
        val report = StorageIntegrity.check(
            projects = listOf(ProjectRef("a", "/files/projects/a/original/x.jpg")),
            diskRelativePaths = listOf(
                "projects/a/original/x.jpg",
                "projects/deleted-id/original/y.jpg",
                "stray.jpg"
            ),
            exists = { true }
        )
        assertTrue(report.rowsWithoutFiles.isEmpty())
        assertEquals(
            listOf("projects/deleted-id/original/y.jpg", "stray.jpg"),
            report.filesWithoutRows
        )
    }
}
