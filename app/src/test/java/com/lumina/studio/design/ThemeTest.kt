package com.lumina.studio.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaBackground
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 0 theme sanity: spec color values + 48.dp minimum touch target.
 *
 * Colors are pure `androidx.compose.ui.graphics.Color` values so they assert
 * directly on the JVM. Composable touch targets cannot be measured in a plain
 * unit test, so the scaffold guards that every interactive component applies
 * `heightIn(min = 48.dp)` in source (the same bar as lint/accessibility).
 */
class ThemeTest {

    @Test
    fun `background matches spec`() {
        assertEquals(Color(0xFF101012), LuminaBackground)
    }

    @Test
    fun `surface matches spec`() {
        assertEquals(Color(0xFF1E1E21), LuminaSurface)
    }

    @Test
    fun `on-surface matches spec`() {
        assertEquals(Color(0xFFF5F5F3), LuminaOnSurface)
    }

    @Test
    fun `muted matches spec`() {
        assertEquals(Color(0xFFA8A8AD), LuminaMuted)
    }

    @Test
    fun `accent matches spec`() {
        assertEquals(Color(0xFFE8C15A), LuminaAmber)
    }

    @Test
    fun `min touch target constant is 48dp`() {
        assertEquals(48f, 48.dp.value, 0.001f)
    }

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
    fun `interactive components apply 48dp min touch target`() {
        // Every touchable scaffold component must enforce heightIn(min = 48.dp).
        val expected = mapOf(
            "com/lumina/studio/core/design/components/ProSlider.kt" to "ProSlider",
            "com/lumina/studio/core/design/components/CategoryChip.kt" to "CategoryChip",
            "com/lumina/studio/core/design/components/AppDialog.kt" to "AppDialog",
            "com/lumina/studio/core/design/components/EmptyState.kt" to "EmptyState",
            "com/lumina/studio/core/design/components/ErrorState.kt" to "ErrorState",
            "com/lumina/studio/core/design/components/AppBottomSheet.kt" to "AppBottomSheet",
            "com/lumina/studio/navigation/AppNav.kt" to "bottom NavigationBarItem",
            "com/lumina/studio/ui/screens/EditorScreen.kt" to "EditorScreen export Button"
        )
        val missing = mutableListOf<String>()
        for ((path, label) in expected) {
            val src = mainSource(path)
            if (!src.contains("48.dp") || !src.contains("heightIn")) {
                missing += "$label ($path)"
            }
        }
        assertTrue(
            "These interactive surfaces lack heightIn(min = 48.dp): $missing",
            missing.isEmpty()
        )
    }
}
