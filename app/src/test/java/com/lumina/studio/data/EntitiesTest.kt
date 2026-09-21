package com.lumina.studio.data

import com.lumina.studio.core.data.local.EditHistory
import com.lumina.studio.core.data.local.Pack
import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.data.local.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 0 data-layer scaffold tests that run on the plain JVM (no device,
 * no Robolectric).
 *
 * Room annotations are CLASS/BINARY retention so they are not visible via
 * reflection at runtime; table/column mapping and DAO SQL are therefore
 * guarded by asserting on the scaffold source text plus entity defaults and
 * data-class round-trips.
 *
 * NOTE: a live in-memory Room round-trip (Room.inMemoryDatabaseBuilder under
 * Robolectric) could not run on this host — see the test report. Blockers:
 * (1) the scaffold wires no Room annotation processor (no KSP/KAPT +
 * room-compiler), so LuminaDatabase_Impl is never generated
 * ("Cannot find implementation for LuminaDatabase"); adding KSP 2.0.2 fails
 * in kspDebugKotlin with "unexpected jvm signature V" under Kotlin 2.2.20,
 * and AGP 9.4.1 built-in Kotlin rejects KSP's source-set wiring unless
 * android.disallowKotlinSourceSets=false; (2) even with codegen, Room needs
 * a SQLite native (Robolectric NATIVE needs librobolectric-nativeruntime,
 * unsupported on linux-aarch64; LEGACY needs sqlite4java JNI, and like
 * Conscrypt's lib it is glibc-linked and cannot dlopen on Termux/bionic).
 * On an x86_64/generic-Linux host with KSP + room-compiler wired, the same
 * round-trip test can run as written in the report.
 */
class EntitiesTest {

    // ---------- entity defaults ----------

    @Test
    fun `project defaults`() {
        val p = Project(id = "p1", name = "Test")
        assertEquals("p1", p.id)
        assertEquals("Test", p.name)
        assertNull(p.photoUri)
        assertEquals(0L, p.createdAt)
        assertEquals(0L, p.updatedAt)
    }

    @Test
    fun `editHistory defaults`() {
        val e = EditHistory(id = "e1", projectId = "p1", toolName = "Adjust")
        assertEquals("e1", e.id)
        assertEquals("p1", e.projectId)
        assertEquals("Adjust", e.toolName)
        assertEquals(0L, e.createdAt)
    }

    @Test
    fun `preset defaults`() {
        val p = Preset(id = "pr1", name = "Golden")
        assertNull(p.packId)
        assertEquals(0L, p.createdAt)
    }

    @Test
    fun `pack defaults`() {
        val p = Pack(id = "pack1", name = "Essentials")
        assertEquals("", p.description)
        assertEquals(0L, p.createdAt)
    }

    // ---------- data-class round-trips (in-memory copy/equality) ----------

    @Test
    fun `project copy round-trip preserves fields`() {
        val original = Project(
            id = "p1",
            name = "Shoot",
            photoUri = "content://photo/1",
            createdAt = 100L,
            updatedAt = 200L
        )
        val copy = original.copy()
        assertEquals(original, copy)
        assertEquals("content://photo/1", copy.photoUri)
        assertEquals(200L, copy.updatedAt)
    }

    @Test
    fun `editHistory copy round-trip preserves fields`() {
        val original = EditHistory(id = "e1", projectId = "p1", toolName = "Curves", createdAt = 5L)
        assertEquals(original, original.copy())
    }

    @Test
    fun `preset copy round-trip preserves pack link`() {
        val original = Preset(id = "pr1", packId = "pack1", name = "Noir", createdAt = 7L)
        val copy = original.copy()
        assertEquals(original, copy)
        assertEquals("pack1", copy.packId)
    }

    @Test
    fun `pack copy round-trip preserves description`() {
        val original = Pack(id = "pack1", name = "Essentials", description = "Everyday looks")
        assertEquals(original, original.copy())
        assertEquals("Everyday looks", original.copy().description)
    }

    // ---------- source-mapping guards ----------

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/src/main/java/$relative")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "Scaffold source not found: $relative. Tried=${candidates.map { it.absolutePath }} " +
                "(user.dir=${System.getProperty("user.dir")})",
            found != null
        )
        return found!!.readText()
    }

    @Test
    fun `entities declare expected tables and primary keys`() {
        val src = mainSource("com/lumina/studio/core/data/local/Entities.kt")
        assertTrue(src.contains("@Entity(tableName = \"projects\")"))
        assertTrue(src.contains("@Entity(tableName = \"edit_history\")"))
        assertTrue(src.contains("@Entity(tableName = \"presets\")"))
        assertTrue(src.contains("@Entity(tableName = \"packs\")"))
        // Every entity keys on `id`.
        val pkCount = src.split("@PrimaryKey val id").size - 1
        assertEquals("Each of the 4 entities must declare '@PrimaryKey val id'", 4, pkCount)
        // Column names the DAOs rely on.
        for (column in listOf("projectId", "toolName", "packId", "photoUri", "createdAt", "updatedAt")) {
            assertTrue("Entities.kt must declare column '$column'", src.contains(column))
        }
    }

    @Test
    fun `dao sql targets expected tables and ordering`() {
        val src = mainSource("com/lumina/studio/core/data/local/Dao.kt")
        assertTrue(src.contains("SELECT * FROM projects ORDER BY updatedAt DESC"))
        assertTrue(src.contains("SELECT * FROM projects WHERE id = :id"))
        assertTrue(src.contains("DELETE FROM projects WHERE id = :id"))
        assertTrue(
            src.contains("SELECT * FROM edit_history WHERE projectId = :projectId ORDER BY createdAt DESC")
        )
        assertTrue(src.contains("DELETE FROM edit_history WHERE projectId = :projectId"))
        assertTrue(src.contains("SELECT * FROM presets ORDER BY name ASC"))
        assertTrue(src.contains("SELECT * FROM packs ORDER BY name ASC"))
        // Writes use REPLACE upserts.
        assertTrue(src.contains("OnConflictStrategy.REPLACE"))
        // All four DAOs present.
        for (dao in listOf("ProjectDao", "EditHistoryDao", "PresetDao", "PackDao")) {
            assertTrue("Dao.kt must declare $dao", src.contains(dao))
        }
    }

    @Test
    fun `database wires all entities with version 3 and stable name`() {
        val src = mainSource("com/lumina/studio/core/data/local/LuminaDatabase.kt")
        for (entity in listOf("Project::class", "EditHistory::class", "Preset::class", "Pack::class")) {
            assertTrue("LuminaDatabase must list entity $entity", src.contains(entity))
        }
        // M2: explicit migrations replaced the destructive fallback; schema is v3.
        assertTrue(src.contains("version = 3"))
        assertFalse(
            "Destructive migration must never return for user data",
            src.contains("fallbackToDestructiveMigration")
        )
        assertTrue(src.contains("DATABASE_NAME = \"lumina.db\""))
        for (dao in listOf("projectDao()", "editHistoryDao()", "presetDao()", "packDao()")) {
            assertTrue("LuminaDatabase must expose $dao", src.contains(dao))
        }
    }
}
