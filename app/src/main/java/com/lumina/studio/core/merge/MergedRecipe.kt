package com.lumina.studio.core.merge

import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.toEditParams

/**
 * M17 merged-project recipe (pure JVM, no android.*).
 *
 * A merged HDR/panorama base becomes a NORMAL Lumina project: the fused
 * pixels are the new "original" and the recipe is factory-default. No
 * hidden exposure/contrast bump, no fake "HDR look" preset — the extended
 * range (such as it is from 8-bit sources) lives in the F16 pixels, and
 * the user grades from zero like any import.
 */
object MergedRecipe {
    /** The recipe every merged project starts from: factory default. */
    fun recipeFor(kind: MergeKind): EditParams = EditParams.DEFAULT

    fun baseNameFor(kind: MergeKind): String = when (kind) {
        MergeKind.HDR -> "hdr_merge"
        MergeKind.PANORAMA -> "panorama"
    }

    fun displayNameFor(kind: MergeKind, index: Int = 0): String {
        val stem = baseNameFor(kind)
        return if (index <= 0) "$stem.jpg" else "$stem-$index.jpg"
    }

    fun fileTypeFor(kind: MergeKind): String = when (kind) {
        MergeKind.HDR -> "HDR"
        MergeKind.PANORAMA -> "PANO"
    }

    /**
     * Pure project-row constructor (the Android edge fills photoUri from
     * ProjectStore, then upserts). editParamsJson stays null = DEFAULT
     * recipe (see EditParamsJson.decode(null)).
     */
    fun mergedProject(
        id: String,
        photoUri: String,
        width: Int,
        height: Int,
        kind: MergeKind,
        now: Long
    ): Project = Project(
        id = id,
        name = displayNameFor(kind),
        photoUri = photoUri,
        createdAt = now,
        updatedAt = now,
        fileType = fileTypeFor(kind),
        mimeType = "image/jpeg",
        width = width,
        height = height,
        editParamsJson = null
    )

    /** Integration gate: a merged row must decode to the default recipe. */
    fun recipeOf(project: Project): EditParams = project.toEditParams()
}
