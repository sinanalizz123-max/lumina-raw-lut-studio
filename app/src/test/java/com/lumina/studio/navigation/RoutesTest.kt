package com.lumina.studio.navigation

import com.lumina.studio.ui.editor.EditorTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Phase 0 scaffold guards for navigation.
 *
 * Covers: route uniqueness, well-formedness, bottom tabs, and that editor
 * tool panels are in-editor UI state (bottom sheet), NOT nav routes.
 */
class RoutesTest {

    /** All `const val String` route constants declared on [Routes], via reflection. */
    private fun allRoutes(): List<String> {
        val fields = Routes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .onEach { it.isAccessible = true }
        val routes = fields.map { it.get(null) as String }
        // Sanity: reflection must actually find the scaffold routes.
        assertTrue(
            "Expected to discover route constants via reflection, found none " +
                "(fields=${Routes::class.java.declaredFields.map { it.name }})",
            routes.isNotEmpty()
        )
        return routes
    }

    private fun routeBase(route: String): String = route.substringBefore("/")

    @Test
    fun `all expected route constants exist`() {
        val routes = allRoutes().toSet()
        val expected = setOf(
            Routes.HOME,
            Routes.PROJECTS,
            Routes.PROJECT_DETAIL,
            Routes.PRESETS,
            Routes.PACK_DETAIL,
            Routes.PRESET_DETAIL,
            Routes.SETTINGS,
            Routes.APPEARANCE,
            Routes.STORAGE,
            Routes.ABOUT,
            Routes.IMPORT,
            Routes.EDITOR,
            Routes.EXPORT,
            Routes.HISTORY
        )
        assertEquals(
            "Routes.kt route set changed; update nav + tests together. Found=$routes",
            expected,
            routes
        )
    }

    @Test
    fun `routes are unique`() {
        val routes = allRoutes()
        assertEquals(
            "Duplicate route strings found: $routes",
            routes.size,
            routes.toSet().size
        )
    }

    @Test
    fun `routes are well-formed - no spaces or leading slash issues`() {
        val basePattern = Regex("^[A-Za-z][A-Za-z0-9]*$")
        val argPattern = Regex("^\\{[A-Za-z][A-Za-z0-9]*\\}$")
        for (route in allRoutes()) {
            assertTrue("Route must not be blank", route.isNotBlank())
            assertFalse("Route must not have leading slash: '$route'", route.startsWith("/"))
            assertFalse("Route must not contain spaces: '$route'", route.contains(" "))
            assertFalse("Route must not contain empty segment: '$route'", route.contains("//"))
            assertFalse("Route must not end with slash: '$route'", route.endsWith("/"))
            val segments = route.split("/")
            assertTrue(
                "Route base segment must match [A-Za-z][A-Za-z0-9]*: '$route'",
                segments[0].matches(basePattern)
            )
            for (extra in segments.drop(1)) {
                assertTrue(
                    "Only '{arg}' placeholders allowed after base: '$route' (segment='$extra')",
                    extra.matches(argPattern)
                )
            }
        }
    }

    @Test
    fun `parameterized routes declare placeholders and helpers build matching paths`() {
        assertTrue(Routes.PROJECT_DETAIL.contains("{projectId}"))
        assertTrue(Routes.PACK_DETAIL.contains("{packId}"))
        assertTrue(Routes.PRESET_DETAIL.contains("{presetId}"))

        assertEquals("projectDetail/abc", Routes.projectDetail("abc"))
        assertEquals("packDetail/pack-1", Routes.packDetail("pack-1"))
        assertEquals("presetDetail/preset-9", Routes.presetDetail("preset-9"))
    }

    @Test
    fun `bottom tabs are exactly Home Projects Presets Settings in order`() {
        assertEquals(
            listOf(Routes.HOME, Routes.PROJECTS, Routes.PRESETS, Routes.SETTINGS),
            BottomTabs
        )
    }

    @Test
    fun `bottom tabs are registered routes with no duplicates`() {
        val routes = allRoutes().toSet()
        for (tab in BottomTabs) {
            assertTrue("Bottom tab '$tab' must be a registered route", routes.contains(tab))
            // Bottom tabs are top-level destinations: no args.
            assertFalse("Bottom tab must not take args: '$tab'", tab.contains("/"))
        }
        assertEquals("Bottom tabs must not repeat", BottomTabs.size, BottomTabs.toSet().size)
    }

    @Test
    fun `editor tool panels are NOT registered as nav routes`() {
        val bases = allRoutes().map { routeBase(it).lowercase() }.toSet()

        // The 10 in-editor tools from EditorTools.kt. "presets" alone is the
        // presets LIBRARY destination (allowed); the Presets TOOL panel must
        // not add its own route.
        val tools = EditorTool.entries
        assertEquals(
            "EditorTool set changed; update nav guard. Found=${tools.map { it.name }}",
            setOf("PRESETS", "ADJUST", "COLOR", "GRADE", "CURVES", "DETAILS", "CROP", "MASK", "RETOUCH", "BLUR"),
            tools.map { it.name }.toSet()
        )

        // Tool-specific destinations must not exist (adjust/color/curves/...).
        val forbiddenBases = setOf("adjust", "color", "grade", "curves", "details", "crop", "mask", "retouch", "blur")
        for (forbidden in forbiddenBases) {
            assertFalse(
                "Editor tool panel must not be a nav route: '$forbidden' (routes=$bases)",
                bases.contains(forbidden)
            )
        }
        // No tool-panel-style routes even under other names.
        val forbiddenFragments = listOf("tool", "adjust", "curves", "details", "/crop", "mask")
        for (route in allRoutes()) {
            val lower = route.lowercase()
            if (lower == Routes.PRESETS || lower == Routes.EDITOR) continue
            for (fragment in forbiddenFragments) {
                assertFalse(
                    "Route '$route' looks like an editor tool panel route (fragment='$fragment')",
                    lower.contains(fragment)
                )
            }
        }
        // The presets library route exists, but there is no separate presets-tool route.
        assertTrue(bases.contains("presets"))
        assertFalse(bases.contains("presetstool"))
        assertFalse(bases.contains("presets-tool"))
        assertFalse(bases.contains("presets_tool"))
    }
}
