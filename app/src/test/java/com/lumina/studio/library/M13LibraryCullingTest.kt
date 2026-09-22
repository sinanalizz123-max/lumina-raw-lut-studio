package com.lumina.studio.library

import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.library.Album
import com.lumina.studio.core.library.AlbumEntry
import com.lumina.studio.core.library.AlbumJson
import com.lumina.studio.core.library.AlbumLimits
import com.lumina.studio.core.library.AlbumOps
import com.lumina.studio.core.library.AlbumStore
import com.lumina.studio.core.library.ClipFractions
import com.lumina.studio.core.library.CullFlags
import com.lumina.studio.core.library.CullScore
import com.lumina.studio.core.library.CullScoreJson
import com.lumina.studio.core.library.CullScoring
import com.lumina.studio.core.library.CullSignals
import com.lumina.studio.core.library.CullThresholds
import com.lumina.studio.core.library.JsonVal
import com.lumina.studio.core.library.LibraryFilter
import com.lumina.studio.core.library.LibraryFilterState
import com.lumina.studio.core.library.MiniJson
import com.lumina.studio.core.library.PresetFilter
import com.lumina.studio.core.library.RECENT_WINDOW_MS
import com.lumina.studio.core.library.RecencyFilter
import com.lumina.studio.core.library.TypeFilter
import com.lumina.studio.core.library.array
import com.lumina.studio.core.library.bool
import com.lumina.studio.core.library.float
import com.lumina.studio.core.library.long
import com.lumina.studio.core.library.obj
import com.lumina.studio.core.library.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M13 pure-JVM guards (§§43-44, no android.*, no Robolectric).
 *
 * Covers: album entry logic + JSON round-trips, MiniJson behavior,
 * library filter predicates, aHash/Hamming math, Laplacian blur math,
 * clipping math, cull thresholds + duplicate assignment, score JSON
 * round-trip, and the no-Room-migration assessment pins (extends the
 * Entities/Migrations suites without touching them).
 */
class M13LibraryCullingTest {

    private val nowMs = 1_000_000_000_000L

    private fun project(
        id: String,
        name: String = id,
        fileType: String = "JPEG",
        presetName: String? = null,
        favorite: Boolean = false,
        createdAt: Long = nowMs - 30L * 24L * 3600L * 1000L,
        updatedAt: Long = nowMs - 30L * 24L * 3600L * 1000L,
        editParamsJson: String? = null
    ) = Project(
        id = id,
        name = name,
        fileType = fileType,
        presetName = presetName,
        isFavorite = favorite,
        createdAt = createdAt,
        updatedAt = updatedAt,
        editParamsJson = editParamsJson
    )

    // ---------- album entry logic ----------

    @Test
    fun `album names are cleaned and blank names rejected`() {
        assertEquals("", AlbumOps.cleanName(null))
        assertEquals("", AlbumOps.cleanName("   "))
        assertEquals("Trip", AlbumOps.cleanName("  Trip  "))
        assertEquals("a b", AlbumOps.cleanName("a   b"))
        assertEquals(AlbumLimits.MAX_NAME_LEN, AlbumOps.cleanName("x".repeat(200)).length)
    }

    @Test
    fun `add entries is idempotent and ignores unknown albums`() {
        val store = AlbumStore(listOf(Album("a1", "One")))
        val added = AlbumOps.addEntries(store, "a1", listOf("p1", "p2", "p1"))
        assertEquals(2, added.entries.size)
        val again = AlbumOps.addEntries(added, "a1", listOf("p1"))
        assertEquals(added, again)
        assertEquals(store, AlbumOps.addEntries(store, "nope", listOf("p1")))
        assertEquals(store, AlbumOps.addEntries(store, "a1", emptyList()))
    }

    @Test
    fun `remove entries never touches other albums`() {
        val store = AlbumStore(
            listOf(Album("a1", "One"), Album("a2", "Two")),
            listOf(AlbumEntry("a1", "p1"), AlbumEntry("a1", "p2"), AlbumEntry("a2", "p1"))
        )
        val next = AlbumOps.removeEntries(store, "a1", listOf("p1"))
        assertEquals(setOf("p2"), AlbumOps.memberIds(next.entries, "a1"))
        assertEquals(setOf("p1"), AlbumOps.memberIds(next.entries, "a2"))
        assertEquals(2, next.albums.size)
    }

    @Test
    fun `delete album keeps photos and drops only its entries`() {
        val store = AlbumStore(
            listOf(Album("a1", "One"), Album("a2", "Two")),
            listOf(AlbumEntry("a1", "p1"), AlbumEntry("a2", "p1"))
        )
        val next = AlbumOps.deleteAlbum(store, "a1")
        assertEquals(listOf(Album("a2", "Two")), next.albums)
        assertEquals(listOf(AlbumEntry("a2", "p1")), next.entries)
        assertEquals(store, AlbumOps.deleteAlbum(store, "missing"))
    }

    @Test
    fun `prune drops entries for deleted projects only`() {
        val store = AlbumStore(
            listOf(Album("a1", "One")),
            listOf(AlbumEntry("a1", "p1"), AlbumEntry("a1", "gone"))
        )
        val next = AlbumOps.pruneMissingProjects(store, setOf("p1"))
        assertEquals(listOf(AlbumEntry("a1", "p1")), next.entries)
        assertEquals(store.albums, next.albums)
        assertEquals(store, AlbumOps.pruneMissingProjects(store, setOf("p1", "gone")))
    }

    @Test
    fun `membership lookups and counts`() {
        val entries = listOf(
            AlbumEntry("a1", "p1"), AlbumEntry("a1", "p2"), AlbumEntry("a2", "p1")
        )
        assertEquals(setOf("p1", "p2"), AlbumOps.memberIds(entries, "a1"))
        assertEquals(setOf("a1", "a2"), AlbumOps.albumIdsOf(entries, "p1"))
        assertTrue(AlbumOps.memberIds(entries, "missing").isEmpty())
        assertEquals(mapOf("a1" to 2, "a2" to 1), AlbumOps.counts(entries))
    }

    // ---------- album JSON ----------

    @Test
    fun `album json round-trips names with quotes newlines and emoji`() {
        val store = AlbumStore(
            listOf(
                Album("a1", "Trip \"25\"\nBeach 🏖", 123L),
                Album("a2", "Back\\slash", 456L)
            ),
            listOf(AlbumEntry("a1", "p1"), AlbumEntry("a2", "p1"))
        )
        assertEquals(store, AlbumJson.decode(AlbumJson.encode(store)))
    }

    @Test
    fun `album json decode is total and strict about membership`() {
        assertEquals(AlbumStore.EMPTY, AlbumJson.decode(""))
        assertEquals(AlbumStore.EMPTY, AlbumJson.decode("{broken"))
        assertEquals(AlbumStore.EMPTY, AlbumJson.decode("[1,2]"))
        assertEquals(AlbumStore.EMPTY, AlbumJson.decode("{\"albums\":[]} trailing"))
        // Entries for unknown albums and dupes are dropped, not kept.
        val decoded = AlbumJson.decode(
            "{\"v\":1,\"albums\":[{\"id\":\"a1\",\"name\":\"One\",\"createdAt\":7}," +
                "{\"id\":\"a1\",\"name\":\"Dupe\",\"createdAt\":8}]," +
                "\"entries\":[{\"a\":\"a1\",\"p\":\"p1\"},{\"a\":\"a1\",\"p\":\"p1\"}," +
                "{\"a\":\"ghost\",\"p\":\"p9\"},{\"a\":\"\",\"p\":\"p1\"}]}"
        )
        assertEquals(1, decoded.albums.size)
        assertEquals("One", decoded.albums[0].name)
        assertEquals(listOf(AlbumEntry("a1", "p1")), decoded.entries)
    }

    // ---------- MiniJson ----------

    @Test
    fun `mini json parses nested values and rejects garbage`() {
        val root = MiniJson.parse(
            "{\"s\":\"a\\\"b\\n☃\",\"n\":-12.5,\"b\":true,\"z\":null," +
                "\"o\":{\"k\":1},\"a\":[1,\"x\"]}"
        ) as? JsonVal.Obj
        assertNotNull(root)
        assertEquals("a\"b\n☃", root!!.string("s"))
        assertEquals(-12.5, root.float("n")!!.toDouble(), 1e-6)
        assertEquals(true, root.bool("b"))
        assertEquals(1L, root.obj("o")!!.long("k"))
        assertEquals(2, root.array("a")!!.size)
        assertNull(MiniJson.parse(""))
        assertNull(MiniJson.parse("{"))
        assertNull(MiniJson.parse("{\"a\":}"))
        assertNull(MiniJson.parse("{\"a\":1} x"))
        assertNull(MiniJson.parse("{\"a\":1,}"))
        assertEquals("\"\\u0001\"", MiniJson.quoted("\u0001"))
    }

    // ---------- filter predicates ----------

    @Test
    fun `type buckets split raw dng edited other`() {
        val raw = project("r", fileType = "RAW")
        val dng = project("d", fileType = "DNG")
        val edited = project("e", presetName = "Noir")
        val editedJson = project("j", editParamsJson = "{\"exposure\":1}")
        val plain = project("p")
        for ((p, type) in listOf(
            raw to TypeFilter.RAW, dng to TypeFilter.DNG,
            edited to TypeFilter.EDITED, editedJson to TypeFilter.EDITED,
            plain to TypeFilter.OTHER
        )) {
            assertTrue("$type matches ${p.id}", LibraryFilter.matchesType(p, type))
            assertTrue("ALL matches ${p.id}", LibraryFilter.matchesType(p, TypeFilter.ALL))
        }
        assertFalse(LibraryFilter.matchesType(plain, TypeFilter.RAW))
        assertFalse(LibraryFilter.matchesType(edited, TypeFilter.OTHER))
        assertFalse(LibraryFilter.matchesType(raw, TypeFilter.EDITED))
    }

    @Test
    fun `preset filter follows presetName only`() {
        val with = project("w", presetName = "Mono")
        val without = project("wo")
        val blank = project("b", presetName = "  ")
        assertTrue(LibraryFilter.matchesPreset(with, PresetFilter.WITH_PRESET))
        assertFalse(LibraryFilter.matchesPreset(without, PresetFilter.WITH_PRESET))
        assertFalse(LibraryFilter.matchesPreset(blank, PresetFilter.WITH_PRESET))
        assertTrue(LibraryFilter.matchesPreset(without, PresetFilter.WITHOUT_PRESET))
        assertTrue(LibraryFilter.matchesPreset(blank, PresetFilter.WITHOUT_PRESET))
        assertFalse(LibraryFilter.matchesPreset(with, PresetFilter.WITHOUT_PRESET))
    }

    @Test
    fun `recency windows use a fixed now`() {
        val day = 24L * 3600L * 1000L
        val freshImport = project("i", createdAt = nowMs - day, updatedAt = nowMs - 30L * day)
        val staleImport = project("s", createdAt = nowMs - 30L * day, updatedAt = nowMs - day)
        assertTrue(LibraryFilter.matchesRecency(freshImport, RecencyFilter.RECENT_IMPORT, nowMs))
        assertFalse(LibraryFilter.matchesRecency(staleImport, RecencyFilter.RECENT_IMPORT, nowMs))
        assertTrue(LibraryFilter.matchesRecency(staleImport, RecencyFilter.RECENT_EDIT, nowMs))
        assertFalse(LibraryFilter.matchesRecency(freshImport, RecencyFilter.RECENT_EDIT, nowMs))
        val edge = project("e", createdAt = nowMs - RECENT_WINDOW_MS)
        assertTrue(LibraryFilter.matchesRecency(edge, RecencyFilter.RECENT_IMPORT, nowMs))
        assertTrue(
            LibraryFilter.matchesRecency(project("f", createdAt = nowMs + day), RecencyFilter.RECENT_IMPORT, nowMs)
        )
    }

    @Test
    fun `filters combine with search favorites and album membership`() {
        val projects = listOf(
            project("p1", "Beach RAW", fileType = "RAW", favorite = true, createdAt = nowMs - 1000L),
            project("p2", "Beach Party", fileType = "JPEG", createdAt = nowMs - 1000L),
            project("p3", "Mountains", fileType = "DNG", presetName = "Film", createdAt = nowMs - 1000L)
        )
        val members = setOf("p1", "p3")
        val state = LibraryFilterState(
            query = "beach",
            favoritesOnly = true,
            type = TypeFilter.RAW,
            albumId = "a1"
        )
        val out = LibraryFilter.apply(projects, state, nowMs) { it in members }
        assertEquals(listOf("p1"), out.map { it.id })
        assertEquals(3, LibraryFilter.apply(projects, LibraryFilterState(), nowMs).size)
        assertTrue(LibraryFilter.apply(projects, LibraryFilterState(albumId = "a1"), nowMs).isEmpty())
    }

    // ---------- aHash + Hamming ----------

    private fun halfSplitFrame(size: Int = 8): IntArray {
        val black = (0xFF shl 24)
        val white = (0xFF shl 24) or (0xFFFFFF)
        return IntArray(size * size) { i -> if ((i % size) < size / 2) black else white }
    }

    @Test
    fun `ahash is stable and hamming counts bit flips`() {
        val frame = halfSplitFrame()
        val hash = CullSignals.averageHash(frame, 8, 8)
        assertNotNull(hash)
        assertEquals(hash, CullSignals.averageHash(frame, 8, 8))
        assertEquals(0, CullSignals.hammingDistance(hash!!, hash))
        val inverted = IntArray(frame.size) { frame[it] xor 0xFFFFFF }
        val flipped = CullSignals.averageHash(inverted, 8, 8)!!
        assertEquals(64, CullSignals.hammingDistance(hash, flipped))
        assertEquals(64, CullSignals.hammingDistance(0L, -1L))
        assertEquals(4, CullSignals.hammingDistance(10L, 5L))
        assertEquals(0, CullSignals.hammingDistance(42L, 42L))
    }

    @Test
    fun `ahash rejects bad input`() {
        assertNull(CullSignals.averageHash(IntArray(0), 0, 0))
        assertNull(CullSignals.averageHash(IntArray(3), 2, 2))
        assertNull(CullSignals.averageHash(IntArray(4), 0, 4))
        assertNotNull(CullSignals.averageHash(IntArray(4) { -1 }, 2, 2))
    }

    @Test
    fun `near-duplicates fall inside the hamming threshold`() {
        val frame = halfSplitFrame()
        val hash = CullSignals.averageHash(frame, 8, 8)!!
        val speckled = frame.copyOf()
        speckled[0] = speckled[0] xor 0x080808
        val near = CullSignals.averageHash(speckled, 8, 8)!!
        assertTrue(
            CullSignals.hammingDistance(hash, near) <= CullThresholds.DUPLICATE_HAMMING_MAX
        )
    }

    // ---------- blur variance ----------

    @Test
    fun `flat frame scores zero blur and checkerboard scores sharp`() {
        val flat = FloatArray(8 * 8) { 128f }
        assertEquals(0f, CullSignals.laplacianVariance(flat, 8, 8)!!, 0f)
        val checker = FloatArray(16 * 16) { i ->
            if (((i / 16) + (i % 16)) % 2 == 0) 0f else 255f
        }
        val sharp = CullSignals.laplacianVariance(checker, 16, 16)!!
        assertTrue("checkerboard variance $sharp reads sharp", sharp > CullThresholds.BLUR_VARIANCE_MAX)
    }

    @Test
    fun `laplacian rejects undersized frames`() {
        assertNull(CullSignals.laplacianVariance(FloatArray(4) { 1f }, 2, 2))
        assertNull(CullSignals.laplacianVariance(FloatArray(3), 2, 2))
        assertNull(CullSignals.laplacianVariance(FloatArray(9) { 1f }, 0, 9))
    }

    // ---------- clipping ----------

    @Test
    fun `clipping fractions count near-white and near-black`() {
        val white = (0xFF shl 24) or 0xFFFFFF
        val black = (0xFF shl 24)
        val mid = (0xFF shl 24) or (0x808080)
        val allWhite = CullSignals.clipping(IntArray(10) { white })
        assertEquals(1f, allWhite.highlight, 0f)
        assertEquals(0f, allWhite.shadow, 0f)
        val allBlack = CullSignals.clipping(IntArray(10) { black })
        assertEquals(0f, allBlack.highlight, 0f)
        assertEquals(1f, allBlack.shadow, 0f)
        val allMid = CullSignals.clipping(IntArray(10) { mid })
        assertEquals(0f, allMid.highlight, 0f)
        assertEquals(0f, allMid.shadow, 0f)
        val mixed = CullSignals.clipping(IntArray(20) { i -> if (i == 0) white else mid })
        assertEquals(0.05f, mixed.highlight, 1e-6f)
        assertEquals(ClipFractions(0f, 0f), CullSignals.clipping(IntArray(0)))
    }

    // ---------- cull thresholds + scoring ----------

    @Test
    fun `flags trip exactly at their thresholds`() {
        val over = CullScoring.flagsFor(1000f, ClipFractions(CullThresholds.HIGHLIGHT_CLIP_MIN, 0f))
        assertTrue(over.overexposed)
        assertFalse(over.blurry)
        assertFalse(over.underexposed)
        val under = CullScoring.flagsFor(1000f, ClipFractions(0f, CullThresholds.SHADOW_CLIP_MIN))
        assertTrue(under.underexposed)
        val blurry = CullScoring.flagsFor(CullThresholds.BLUR_VARIANCE_MAX, ClipFractions(0f, 0f))
        assertTrue(blurry.blurry)
        val sharp = CullScoring.flagsFor(CullThresholds.BLUR_VARIANCE_MAX + 0.01f, ClipFractions(0f, 0f))
        assertFalse(sharp.hasAny)
        val badBlur = CullScoring.flagsFor(Float.NaN, ClipFractions(0f, 0f))
        assertFalse(badBlur.blurry)
    }

    @Test
    fun `scoreFrame flags synthetic frames end to end`() {
        val flatBlack = CullScoring.scoreFrame(IntArray(8 * 8) { 0xFF000000 }, 8, 8)!!
        assertTrue(flatBlack.blurry)
        assertTrue(flatBlack.underexposed)
        val flatWhite = CullScoring.scoreFrame(IntArray(8 * 8) { -1 }, 8, 8)!!
        assertTrue(flatWhite.blurry)
        assertTrue(flatWhite.overexposed)
        assertNull(CullScoring.scoreFrame(IntArray(3), 2, 2))
    }

    @Test
    fun `duplicate assignment is deterministic and earliest-wins`() {
        val hashes = mapOf("a" to 0L, "b" to 1L, "c" to -1L)
        assertEquals(mapOf("b" to "a"), CullScoring.assignDuplicates(listOf("a", "b", "c"), hashes))
        assertEquals(mapOf("a" to "b"), CullScoring.assignDuplicates(listOf("b", "a"), hashes))
        assertTrue(CullScoring.assignDuplicates(listOf("a", "c"), hashes).isEmpty())
        assertTrue(
            CullScoring.assignDuplicates(listOf("a", "missing"), mapOf("a" to 0L)).isEmpty()
        )
    }

    @Test
    fun `flag badges name duplicates only when known`() {
        assertEquals(
            listOf("Blurry", "Overexposed", "Underexposed", "Duplicate of Sunset"),
            CullFlags(true, true, true, "id1").badges("Sunset")
        )
        assertEquals(listOf("Duplicate"), CullFlags(duplicateOf = "id1").badges(null))
        assertTrue(CullFlags().badges().isEmpty())
        assertFalse(CullFlags().hasAny)
        assertTrue(CullFlags(blurry = true).hasAny)
    }

    // ---------- cull score JSON ----------

    @Test
    fun `cull score json round-trips without duplicate links`() {
        val score = CullScore("p1", "key9", 123456789L, 12.5f, 0.01f, 0.5f, CullFlags(true, false, true))
        val decoded = CullScoreJson.decode(CullScoreJson.encode(score))!!
        assertEquals(score.copy(flags = score.flags.copy(duplicateOf = null)), decoded)
        // Duplicate links are live corpus state, never stored.
        val withDup = score.copy(flags = score.flags.copy(duplicateOf = "other"))
        assertNull(CullScoreJson.decode(CullScoreJson.encode(withDup))!!.flags.duplicateOf)
    }

    @Test
    fun `cull score json rejects garbage`() {
        assertNull(CullScoreJson.decode(""))
        assertNull(CullScoreJson.decode("{bad"))
        assertNull(CullScoreJson.decode("{\"v\":1}"))
        assertNull(CullScoreJson.decode("{\"v\":1,\"id\":\"p\",\"key\":\"k\",\"hash\":1,\"blur\":NaN}"))
    }

    // ---------- migration assessment pins (extend-only, existing suites untouched) ----------

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

    @Test
    fun `m13 adds no room migration - albums live in datastore by design`() {
        val migrations = mainSource("com/lumina/studio/core/data/local/Migrations.kt")
        assertTrue(migrations.contains("MIGRATION_1_2"))
        assertTrue(migrations.contains("MIGRATION_2_3"))
        assertFalse("M13 must not add Migration(3→4): albums are DataStore JSON", migrations.contains("Migration(3, 4)"))
        val db = mainSource("com/lumina/studio/core/data/local/LuminaDatabase.kt")
        assertTrue("Room stays at v3 for M13", db.contains("version = 3"))
        val dao = mainSource("com/lumina/studio/core/data/local/Dao.kt")
        assertFalse("Album membership is not a DAO concern", dao.lowercase().contains("album"))
        val entities = mainSource("com/lumina/studio/core/data/local/Entities.kt")
        assertFalse(entities.lowercase().contains("album"))
        val repo = mainSource("com/lumina/studio/core/library/AlbumRepository.kt")
        assertTrue(repo.contains("preferencesDataStore"))
        assertFalse("AlbumRepository must never delete photo bytes", repo.contains("deleteProjectFiles"))
    }

    @Test
    fun `cull review is advisory - scores cannot delete`() {
        val engine = mainSource("com/lumina/studio/core/library/CullEngine.kt")
        assertTrue(engine.contains("fun analyzeProject"))
        assertFalse("CullEngine must not delete rows", engine.contains("deleteById"))
        val scoring = mainSource("com/lumina/studio/core/library/CullScoring.kt")
        assertTrue(scoring.contains("duplicateOf"))
        val vm = mainSource("com/lumina/studio/ui/screens/ProjectsViewModel.kt")
        assertTrue(vm.contains("fun startCullReview"))
        assertTrue(vm.contains("Dispatchers.Default"))
        val screen = mainSource("com/lumina/studio/ui/screens/ProjectsScreen.kt")
        assertTrue("Review sheet states the honest scope", screen.contains("You decide"))
    }
}
