package com.lumina.studio.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class M14SimplificationHonestyTest {

    private fun mainRoot(): File {
        val candidates = listOf(
            File("app/src/main"),
            File("src/main"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/src/main")
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue("Main source root not found", found != null)
        return found!!
    }

    private fun readMain(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/src/main/java/$relative")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("Source not found: $relative", found != null)
        return found!!.readText()
    }

    private fun uiFiles(): List<File> {
        val root = mainRoot()
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.path.contains("/ui/") }.toList()
    }

    @Test
    fun `no dead presetLibraryPath control in Settings UI`() {
        val src = readMain("com/lumina/studio/ui/screens/SettingsScreen.kt")
        assertTrue(
            "Settings UI must not reference dead presetLibraryPath key",
            !src.contains("presetLibraryPath")
        )
        assertTrue(
            "Settings UI must not show dead Library path field",
            !src.contains("Library path")
        )
    }

    @Test
    fun `rawQuality is wired to RAW decode path`() {
        val settings = readMain("com/lumina/studio/ui/screens/SettingsScreen.kt")
        assertTrue("Settings must keep RAW quality control", settings.contains("rawQuality"))
        val vm = readMain("com/lumina/studio/ui/screens/EditorViewModel.kt")
        assertTrue("EditorViewModel must consume rawQuality", vm.contains("rawQuality") || vm.contains("useFullDevelop"))
        val screen = readMain("com/lumina/studio/ui/screens/EditorScreen.kt")
        assertTrue("EditorScreen must forward rawQuality", screen.contains("rawQuality"))
    }

    @Test
    fun `export defaults load into ExportSettings`() {
        val export = readMain("com/lumina/studio/ui/screens/ExportScreen.kt")
        assertTrue("Export must read exportFormat default", export.contains("exportFormat"))
        assertTrue("Export must read exportQuality default", export.contains("exportQuality"))
        assertTrue("Export must read exportResolution default", export.contains("exportResolution"))
        assertTrue("Export must read exportColorSpace default", export.contains("exportColorSpace"))
        val batch = readMain("com/lumina/studio/core/export/BatchExporter.kt")
        assertTrue("Batch must read exportFormat default", batch.contains("exportFormat"))
        assertTrue("Batch must read exportQuality default", batch.contains("exportQuality"))
        assertTrue("Batch must read exportResolution default", batch.contains("exportResolution"))
    }

    @Test
    fun `gpu control is honestly labeled`() {
        val src = readMain("com/lumina/studio/ui/screens/SettingsScreen.kt")
        assertTrue(
            "GPU switch must use precise performance-mode label",
            src.contains("Preview performance mode")
        )
        assertTrue(
            "Old misleading GPU acceleration title must be gone",
            !src.contains("\"GPU acceleration\"")
        )
    }

    @Test
    fun `honesty copy guard forbids phase promises and soft approximation`() {
        val forbidden = listOf("coming in Phase", "Phase 2", "Phase 3", "soft approximation")
        val bad = ArrayList<String>()
        for (file in uiFiles()) {
            val text = runCatching { file.readText() }.getOrDefault("") 
            val lines = text.lines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                for (phrase in forbidden) {
                    if (line.contains(phrase)) {
                        bad.add("${file.name}:${index + 1}: $phrase")
                    }
                }
            }
        }
        assertTrue("Forbidden honesty-copy phrases in UI: $bad", bad.isEmpty())
    }

    @Test
    fun `honesty copy guard forbids AI-powered claims outside not-AI qualifiers`() {
        val bad = ArrayList<String>()
        for (file in uiFiles()) {
            val text = runCatching { file.readText() }.getOrDefault("")
            for ((index, line) in text.lines().withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                if (!line.contains("Text(") && !line.contains("message") && !line.contains("snackbar")) continue
                if (line.contains("AI-powered")) {
                    bad.add("${file.name}:${index + 1}: AI-powered")
                }
                val aiSpace = Regex("AI[ -][A-Za-z]")
                val match = aiSpace.find(line)
                if (match != null && !line.contains("not AI")) {
                    bad.add("${file.name}:${index + 1}: ${match.value}")
                }
            }
        }
        assertTrue("Forbidden AI claims in user-visible UI strings: $bad", bad.isEmpty())
    }

    @Test
    fun `ProSlider exposes slider semantics`() {
        val src = readMain("com/lumina/studio/core/design/components/ProSlider.kt")
        assertTrue("ProSlider must expose contentDescription", src.contains("contentDescription"))
        assertTrue("ProSlider must keep 48dp touch target", src.contains("48.dp"))
    }

    @Test
    fun `grade wheel and curve editor expose contentDescription`() {
        val grade = readMain("com/lumina/studio/ui/editor/GradePanel.kt")
        assertTrue("Grade wheel must expose contentDescription", grade.contains("contentDescription"))
        val curves = readMain("com/lumina/studio/ui/editor/CurvesPanel.kt")
        assertTrue("Curves editor must expose contentDescription", curves.contains("contentDescription"))
    }

    @Test
    fun `icon buttons carry contentDescription`() {
        var buttons = 0
        var described = 0
        for (file in uiFiles()) {
            val text = runCatching { file.readText() }.getOrDefault("")
            buttons += Regex("IconButton\\s*\\(").findAll(text).count()
            described += Regex("contentDescription\\s*=").findAll(text).count()
        }
        assertTrue("UI must contain IconButtons ($buttons found)", buttons > 0)
        assertTrue(
            "Every IconButton should have a contentDescription somewhere ($described vs $buttons)",
            described >= buttons
        )
    }

    @Test
    fun `debug screen stays hidden behind About version taps`() {
        val about = readMain("com/lumina/studio/ui/screens/AboutScreen.kt")
        assertTrue("About must gate debug behind 7 taps", about.contains("versionTaps") && about.contains(">= 7"))
        assertTrue("Debug must report honest CPU GPU note", about.contains("GPU_INFO") || about.contains("no GLES"))
        val settings = readMain("com/lumina/studio/ui/screens/SettingsScreen.kt")
        assertTrue("Debug must not appear in Settings list", !settings.contains("Debug"))
    }

    @Test
    fun `bounded reads guard raw export and import paths`() {
        val exporter = readMain("com/lumina/studio/core/export/Exporter.kt")
        assertTrue("Exporter raw read must be capped", exporter.contains("MAX_RAW_READ_BYTES"))
        val store = readMain("com/lumina/studio/core/data/store/ProjectStore.kt")
        assertTrue("Import copy must be capped", store.contains("MAX_IMPORT_BYTES"))
    }
}
