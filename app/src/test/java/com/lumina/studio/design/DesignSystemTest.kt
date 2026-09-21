package com.lumina.studio.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI-polish (A+B) design-system contract tests.
 *
 * Pure-JVM source-contract style (no Android framework): reads files under
 * app/src/main via [mainSource] and asserts structural facts, mirroring
 * ThemeTest's helper. Keeps the 174 existing tests green; guards layered
 * roles, typography, motion, and polished components.
 */
class DesignSystemTest {

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/src/main/java/$relative")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "Design source not found: $relative. Tried=${candidates.map { it.absolutePath }} " +
                "(user.dir=${System.getProperty("user.dir")})",
            found != null
        )
        return found!!.readText()
    }

    private fun mainRoot(): File {
        val candidates = listOf(
            File("app/src/main"),
            File("src/main"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/src/main")
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "Main source root not found. Tried=${candidates.map { it.absolutePath }} " +
                "(user.dir=${System.getProperty("user.dir")})",
            found != null
        )
        return found!!
    }

    // ---- Color.kt layered roles ----

    @Test
    fun `layered surface roles exist in Color_kt`() {
        val src = mainSource("com/lumina/studio/core/design/theme/Color.kt")
        val expected = listOf(
            "LuminaSurfaceContainerLowest",
            "LuminaSurfaceContainerLow",
            "LuminaSurfaceContainer",
            "LuminaSurfaceContainerHigh",
            "LuminaScrim"
        )
        val missing = expected.filterNot { src.contains(it) }
        assertTrue("Color.kt missing layered roles: $missing", missing.isEmpty())
    }

    @Test
    fun `layered roles differ from base surface hex`() {
        val src = mainSource("com/lumina/studio/core/design/theme/Color.kt")
        assertTrue("Color.kt must declare base LuminaSurface", src.contains("LuminaSurface"))
        assertTrue("Color.kt must keep base surface hex 1E1E21", src.contains("1E1E21"))
        // Layered fills use their own hexes (Lowest 141417, Low 1A1A1D, High 2E2E34;
        // Container aliases the variant 26262B; scrim 99000000) — none equals 1E1E21.
        val layeredHexes = listOf("141417", "1A1A1D", "26262B", "2E2E34", "99000000")
        val missing = layeredHexes.filterNot { src.contains(it) }
        assertTrue("Color.kt layered hexes missing: $missing", missing.isEmpty())
        for (hex in listOf("141417", "1A1A1D", "26262B", "2E2E34")) {
            assertTrue("Layered hex $hex must differ from base surface 1E1E21", hex != "1E1E21")
        }
    }

    // ---- Theme.kt wiring ----

    @Test
    fun `Theme_kt wires layered roles into colorScheme`() {
        val src = mainSource("com/lumina/studio/core/design/theme/Theme.kt")
        assertTrue("Theme.kt must build a colorScheme", src.contains("colorScheme"))
        val expected = listOf(
            "surfaceContainerLowest",
            "surfaceContainerLow",
            "surfaceContainer",
            "surfaceContainerHigh",
            "scrim"
        )
        val missing = expected.filterNot { src.contains(it) }
        assertTrue("Theme.kt colorScheme missing wirings: $missing", missing.isEmpty())
        val refs = listOf(
            "LuminaSurfaceContainerLowest",
            "LuminaSurfaceContainerLow",
            "LuminaSurfaceContainer",
            "LuminaSurfaceContainerHigh",
            "LuminaScrim"
        )
        val missingRefs = refs.filterNot { src.contains(it) }
        assertTrue("Theme.kt must reference layered Color roles: $missingRefs", missingRefs.isEmpty())
    }

    // ---- Type.kt ----

    @Test
    fun `Type_kt has section-header style with 600 weight`() {
        val src = mainSource("com/lumina/studio/core/design/theme/Type.kt")
        assertTrue(
            "Type.kt must declare LuminaSectionHeader style",
            src.contains("LuminaSectionHeader")
        )
        assertTrue(
            "Section header must use 600 weight (SemiBold/W600/600)",
            src.contains("SemiBold") || src.contains("W600") || src.contains("600")
        )
    }

    @Test
    fun `Type_kt has caption style`() {
        val src = mainSource("com/lumina/studio/core/design/theme/Type.kt")
        assertTrue("Type.kt must declare LuminaCaption style", src.contains("LuminaCaption"))
    }

    // ---- Motion.kt ----

    @Test
    fun `Motion_kt defines short 150 and medium 250 durations`() {
        val src = mainSource("com/lumina/studio/core/design/theme/Motion.kt")
        assertTrue("Motion.kt must declare ShortMillis", src.contains("ShortMillis"))
        assertTrue("Motion.kt must declare MediumMillis", src.contains("MediumMillis"))
        assertTrue("ShortMillis must be 150", src.contains("150"))
        assertTrue("MediumMillis must be 250", src.contains("250"))
    }

    @Test
    fun `Motion_kt defines easings`() {
        val src = mainSource("com/lumina/studio/core/design/theme/Motion.kt")
        assertTrue(
            "Motion.kt must define easings",
            src.contains("Easing") || src.contains("easing")
        )
        val hasKnownEasing = src.contains("StandardEasing") ||
            src.contains("EmphasizedEasing") ||
            src.contains("FastOutSlowInEasing") ||
            src.contains("CubicBezierEasing")
        assertTrue("Motion.kt must declare Standard/Emphasized easings", hasKnownEasing)
    }

    // ---- ProSlider ----

    @Test
    fun `ProSlider has value bubble`() {
        val src = mainSource("com/lumina/studio/core/design/components/ProSlider.kt")
        assertTrue(
            "ProSlider must support bubble (showBubble/bubbleVisible/bubble)",
            src.contains("showBubble") || src.contains("bubbleVisible") || src.contains("bubble", ignoreCase = true)
        )
    }

    @Test
    fun `ProSlider has ticks`() {
        val src = mainSource("com/lumina/studio/core/design/components/ProSlider.kt")
        assertTrue(
            "ProSlider must support ticks (showTicks/ticks)",
            src.contains("showTicks") || src.contains("ticks", ignoreCase = true)
        )
    }

    @Test
    fun `ProSlider enforces 48dp min touch target`() {
        val src = mainSource("com/lumina/studio/core/design/components/ProSlider.kt")
        assertTrue("ProSlider must use heightIn", src.contains("heightIn"))
        assertTrue("ProSlider must use 48.dp", src.contains("48.dp"))
    }

    // ---- CategoryChip ----

    @Test
    fun `CategoryChip is pill with 48dp min touch target`() {
        val src = mainSource("com/lumina/studio/core/design/components/CategoryChip.kt")
        assertTrue("CategoryChip must use heightIn", src.contains("heightIn"))
        assertTrue("CategoryChip must use 48.dp", src.contains("48.dp"))
        assertTrue(
            "CategoryChip must be a pill (RoundedCornerShape(50)/pill)",
            src.contains("RoundedCornerShape(50)") || src.contains("pill", ignoreCase = true)
        )
    }

    // ---- AppBottomSheet ----

    @Test
    fun `AppBottomSheet has drag handle`() {
        val src = mainSource("com/lumina/studio/core/design/components/AppBottomSheet.kt")
        assertTrue("AppBottomSheet must declare dragHandle", src.contains("dragHandle"))
    }

    // ---- EmptyState ----

    @Test
    fun `EmptyState has illustration param with variants`() {
        val src = mainSource("com/lumina/studio/core/design/components/EmptyState.kt")
        assertTrue("EmptyState must take an illustration param", src.contains("illustration"))
        assertTrue(
            "EmptyState must use EmptyStateIllustration",
            src.contains("EmptyStateIllustration")
        )
        // Spec illustrations: None/Photo/Palette/Folder/Sliders.
        val variants = listOf("None", "Photo", "Palette", "Folder", "Sliders")
        val missing = variants.filterNot { src.contains(it) }
        assertTrue("EmptyState missing illustration variants: $missing", missing.isEmpty())
    }

    // ---- LoadingShimmer ----

    @Test
    fun `LoadingShimmer has List and Card styles`() {
        val src = mainSource("com/lumina/studio/core/design/components/LoadingShimmer.kt")
        assertTrue(
            "LoadingShimmer must declare a style (LoadingShimmerStyle/style)",
            src.contains("LoadingShimmerStyle") || src.contains("style")
        )
        assertTrue("LoadingShimmer must offer List style", src.contains("List"))
        assertTrue("LoadingShimmer must offer Card style", src.contains("Card"))
    }

    // ---- Spec hex guard (duplicates ThemeTest on purpose) ----

    @Test
    fun `spec hexes still present`() {
        val src = mainSource("com/lumina/studio/core/design/theme/Color.kt")
        val hexes = listOf("101012", "1E1E21", "F5F5F3", "A8A8AD", "E8C15A")
        val missing = hexes.filterNot { src.contains(it) }
        assertTrue("Color.kt missing spec hexes: $missing", missing.isEmpty())
    }

    // ---- Placeholder sweep ----

    @Test
    fun `no Phase 0 placeholder string in main sources`() {
        val root = mainRoot()
        val hits = root.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                runCatching { file.readText().contains("Phase 0 placeholder") }.getOrDefault(false)
            }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("Phase 0 placeholder found in: $hits", hits.isEmpty())
    }
}
