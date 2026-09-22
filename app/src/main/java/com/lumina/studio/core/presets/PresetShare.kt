package com.lumina.studio.core.presets

import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import java.io.File

object PresetShare {
    const val KIND = "lumina-preset"
    const val VERSION = 1
    const val SHARE_EXTENSION = ".lumina-preset.json"
    const val RECIPE_SUFFIX = ".recipe.json"

    data class Shared(
        val name: String,
        val category: String,
        val intensity: Float,
        val groups: Set<SettingGroup>,
        val recipe: EditParams,
        val lutInline: String?
    )

    fun recipeFileFor(lutsDir: File, presetId: String): File =
        File(lutsDir, sanitize(presetId) + RECIPE_SUFFIX)

    fun readRecipeParams(lutsDir: File, presetId: String): EditParams? {
        return try {
            val file = recipeFileFor(lutsDir, presetId)
            if (!file.isFile) return null
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return null
            EditParamsJson.decode(text)
        } catch (_: Exception) {
            null
        }
    }

    fun writeRecipeParams(lutsDir: File, presetId: String, params: EditParams): Boolean {
        return try {
            lutsDir.mkdirs()
            recipeFileFor(lutsDir, presetId).writeText(
                EditParamsJson.encode(params), Charsets.UTF_8
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deleteRecipeParams(lutsDir: File, presetId: String): Boolean {
        return try {
            val file = recipeFileFor(lutsDir, presetId)
            if (!file.exists()) return true
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun buildShareJson(
        name: String,
        category: String,
        intensity: Float,
        groups: Set<SettingGroup>,
        recipe: EditParams,
        lutInline: String?
    ): String {
        val sb = StringBuilder("{")
        sb.append("\"app\":\"Lumina RAW & LUT Studio\",")
        sb.append("\"kind\":\"").append(KIND).append("\",")
        sb.append("\"v\":").append(VERSION).append(",")
        sb.append("\"name\":\"").append(escape(name)).append("\",")
        sb.append("\"category\":\"").append(escape(category)).append("\",")
        sb.append("\"intensity\":").append(intensity.coerceIn(0f, 1f)).append(",")
        sb.append("\"groups\":[")
        SettingGroups.keysOf(groups).forEachIndexed { index, key ->
            if (index > 0) sb.append(",")
            sb.append("\"").append(key).append("\"")
        }
        sb.append("],")
        sb.append("\"recipe\":").append(EditParamsJson.encode(recipe)).append(",")
        sb.append("\"lutInline\":")
        if (lutInline.isNullOrEmpty()) sb.append("null") else sb.append("\"").append(escape(lutInline)).append("\"")
        sb.append("}")
        return sb.toString()
    }

    fun parseShareJson(text: String?): Shared? {
        if (text.isNullOrBlank()) return null
        return try {
            val kind = extractStringOrNull(text, "kind")
            if (kind != KIND) return null
            val version = extractNumber(text, "\"v\"")?.toInt() ?: 0
            if (version < 0 || version > VERSION) return null
            val name = extractStringOrNull(text, "name")?.takeIf { it.isNotBlank() } ?: return null
            val category = extractStringOrNull(text, "category")?.takeIf { it.isNotBlank() } ?: "Film"
            val intensity = extractNumber(text, "\"intensity\"") ?: 1f
            val groups = extractStringList(text, "groups")?.let { SettingGroups.sanitizeKeys(it) }
                ?: SettingGroups.PRESET_GROUPS
            val recipeJson = extractObject(text, "recipe") ?: return null
            val recipe = EditParamsJson.decode(recipeJson)
            val lutInline = extractStringOrNull(text, "lutInline")
            Shared(
                name = name,
                category = category,
                intensity = intensity.coerceIn(0f, 1f),
                groups = groups.ifEmpty { SettingGroups.PRESET_GROUPS },
                recipe = recipe,
                lutInline = lutInline?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun shareFileName(name: String): String {
        val stem = name.map { c ->
            if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
        }.joinToString("").take(48).trim('_', '-', ' ').ifEmpty { "preset" }
        return stem + SHARE_EXTENSION
    }

    private fun sanitize(presetId: String): String {
        val cleaned = presetId.map { c ->
            if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
        }.joinToString("").take(64).trim('_', '-', ' ')
        return cleaned.ifEmpty {
            "preset_" + (presetId.hashCode().toLong() and 0xffffffffL).toString(16)
        }
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun extractObject(json: String, key: String): String? {
        val token = "\"$key\""
        val keyIndex = json.indexOf(token)
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex + token.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        var k = i
        while (k < json.length) {
            val c = json[k]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return json.substring(i, k + 1)
                    }
                }
            }
            k++
        }
        return null
    }

    private fun extractNumber(json: String, keyToken: String): Float? {
        val regex = Regex(Regex.escape(keyToken) + "\\s*:\\s*(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)")
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractStringOrNull(json: String, key: String): String? {
        val nullRegex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*null")
        if (nullRegex.containsMatchIn(json)) return null
        val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val raw = regex.find(json)?.groupValues?.get(1) ?: return null
        return raw.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun extractStringList(json: String, key: String): List<String>? {
        val token = "\"$key\""
        val keyIndex = json.indexOf(token)
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex + token.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '[') return null
        val end = json.indexOf(']', i)
        if (end < 0) return null
        val inner = json.substring(i + 1, end)
        if (inner.isBlank()) return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(inner).map {
            it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        }.toList()
    }
}
