package com.lumina.studio.data

import com.lumina.studio.core.data.local.LuminaMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MigrationsTest {

    @Test
    fun `v1 to v2 adds editParamsJson`() {
        assertEquals(
            listOf("ALTER TABLE projects ADD COLUMN editParamsJson TEXT"),
            LuminaMigrations.V1_V2_SQL
        )
        assertEquals(1, LuminaMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, LuminaMigrations.MIGRATION_1_2.endVersion)
    }

    @Test
    fun `v2 to v3 adds preset library columns`() {
        val sql = LuminaMigrations.V2_V3_SQL
        assertEquals(4, sql.size)
        assertTrue(sql.any { it.contains("ADD COLUMN category") && it.contains("presets") })
        assertTrue(sql.any { it.contains("ADD COLUMN isFavorite") && it.contains("INTEGER") })
        assertTrue(sql.any { it.contains("ADD COLUMN defaultIntensity") && it.contains("REAL") })
        assertTrue(sql.any { it.contains("ADD COLUMN cubeText") && it.contains("TEXT") })
        assertEquals(2, LuminaMigrations.MIGRATION_2_3.startVersion)
        assertEquals(3, LuminaMigrations.MIGRATION_2_3.endVersion)
        assertEquals(2, LuminaMigrations.ALL.size)
    }

    @Test
    fun `database provider uses explicit migrations without destructive fallback`() {
        val src = mainSource("com/lumina/studio/core/data/local/DatabaseProvider.kt")
        assertTrue(src.contains("addMigrations"))
        assertTrue(src.contains("LuminaMigrations.MIGRATION_1_2"))
        assertTrue(src.contains("LuminaMigrations.MIGRATION_2_3"))
        assertTrue(
            "fallbackToDestructiveMigration must be gone",
            !src.contains("fallbackToDestructiveMigration")
        )
        assertTrue("corrupt DB must be backed up before recreate", src.contains("backupCorruptFiles"))
    }

    @Test
    fun `database exports schema and dao exposes full listing`() {
        val db = mainSource("com/lumina/studio/core/data/local/LuminaDatabase.kt")
        assertTrue(db.contains("exportSchema = true"))
        assertTrue(!db.contains("fallbackToDestructiveMigration"))
        val dao = mainSource("com/lumina/studio/core/data/local/Dao.kt")
        assertTrue(dao.contains("suspend fun getAll()"))
    }

    @Test
    fun `backup rules wire database and durable files`() {
        val manifest = mainFile("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("@xml/backup_rules"))
        assertTrue(manifest.contains("@xml/data_extraction_rules"))
        val legacy = mainFile("src/main/res/xml/backup_rules.xml")
        val modern = mainFile("src/main/res/xml/data_extraction_rules.xml")
        for (xml in listOf(legacy, modern)) {
            assertTrue(xml.contains("<include domain=\"database\""))
            assertTrue(xml.contains("<include domain=\"file\" path=\"projects\""))
            assertTrue(xml.contains("<include domain=\"file\" path=\"luts\""))
        }
    }

    @Test
    fun `project store exposes durable copy and delete`() {
        val src = mainSource("com/lumina/studio/core/data/store/ProjectStore.kt")
        assertTrue(src.contains("fun copyUriToOriginal"))
        assertTrue(src.contains("fun deleteProjectFiles"))
        assertTrue(src.contains("fun projectSize"))
        val gradle = mainFile("build.gradle.kts")
        assertTrue(gradle.contains("room.schemaLocation"))
    }

    private fun mainSource(relative: String): String = mainFile("src/main/java/$relative")

    private fun mainFile(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/$relative")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "Expected file missing: $relative. Tried=${candidates.map { it.absolutePath }}",
            found != null
        )
        return found!!.readText()
    }
}
