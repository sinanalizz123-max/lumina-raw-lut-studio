package com.lumina.studio.core.export

import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson

object Sidecar {
    const val SCHEMA_VERSION = 1
    const val SIDECAR_SUFFIX = ".lumina.json"

    data class Parsed(
        val params: EditParams,
        val schemaVersion: Int,
        val sourceFile: String?,
        val warnings: List<String>
    )

    fun parse(text: String?): Parsed? {
        if (text.isNullOrBlank()) return null
        val recipeJson = extractObject(text, "recipe") ?: return null
        val params = EditParamsJson.decode(recipeJson)
        val warnings = ArrayList<String>()
        val schema = extractNumber(text, "schemaVersion")?.toInt()
        val schemaVersion = if (schema == null) {
            warnings.add("Sidecar has no schema version; assuming v0 layout.")
            0
        } else {
            if (schema < 0 || schema > SCHEMA_VERSION) {
                warnings.add(
                    "Sidecar schema v$schema is newer than supported v$SCHEMA_VERSION; " +
                        "unknown fields were ignored."
                )
            }
            schema
        }
        val sourceFile = extractStringOrNull(text, "sourceFile")
        return Parsed(params, schemaVersion, sourceFile, warnings)
    }

    fun withoutLut(params: EditParams): EditParams =
        params.copy(presetId = null, presetIntensity = 1f)

    fun missingLutWarning(presetId: String?): String =
        "LUT “${presetId ?: "unknown"}” is not installed; applied everything else."

    fun matchBaseName(sidecarName: String?): String? {
        if (sidecarName.isNullOrBlank()) return null
        val base = sidecarName.substringAfterLast('/').substringAfterLast('\\')
        if (!base.endsWith(SIDECAR_SUFFIX)) return null
        return base.dropLast(SIDECAR_SUFFIX.length).takeIf { it.isNotBlank() }
    }

    fun recipeObject(sidecarJson: String): String? = extractObject(sidecarJson, "recipe")

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

    private fun extractNumber(json: String, key: String): Float? {
        val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)")
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractStringOrNull(json: String, key: String): String? {
        val nullRegex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*null")
        if (nullRegex.containsMatchIn(json)) return null
        val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val raw = regex.find(json)?.groupValues?.get(1) ?: return null
        return raw.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
