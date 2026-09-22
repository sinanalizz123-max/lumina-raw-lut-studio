package com.lumina.studio.core.batch

import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.presets.SettingGroup
import com.lumina.studio.core.presets.SettingGroups

object SettingsClipboard {
    @Volatile
    private var copiedParams: EditParams? = null

    @Volatile
    private var copiedGroups: Set<SettingGroup> = SettingGroups.COPY_DEFAULT

    @Synchronized
    fun copy(params: EditParams, groups: Set<SettingGroup> = SettingGroups.COPY_DEFAULT) {
        copiedParams = params
        copiedGroups = groups.toSet()
    }

    @Synchronized
    fun paste(base: EditParams): EditParams? {
        val source = copiedParams ?: return null
        return SettingGroups.applyCopy(source, base, copiedGroups)
    }

    @Synchronized
    fun pasteWith(source: EditParams, base: EditParams, groups: Set<SettingGroup>): EditParams =
        SettingGroups.applyCopy(source, base, groups)

    fun hasContent(): Boolean = copiedParams != null

    fun groups(): Set<SettingGroup> = copiedGroups

    @Synchronized
    fun clear() {
        copiedParams = null
        copiedGroups = SettingGroups.COPY_DEFAULT
    }
}

data class BatchItemResult(
    val projectId: String,
    val ok: Boolean,
    val error: String? = null
)

object BatchOps {
    fun mergeForBatch(source: EditParams, target: EditParams, groups: Set<SettingGroup>): EditParams =
        SettingGroups.applyCopy(source, target, groups)

    fun progress(done: Int, total: Int): Float {
        if (total <= 0 || done <= 0) return 0f
        if (done >= total) return 1f
        return (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun summarize(results: List<BatchItemResult>): String {
        if (results.isEmpty()) return "Nothing to export."
        val ok = results.count { it.ok }
        val failed = results.size - ok
        return if (failed == 0) {
            if (ok == 1) "Exported 1 photo." else "Exported $ok photos."
        } else {
            "Exported $ok of ${results.size}; $failed failed."
        }
    }

    fun failures(results: List<BatchItemResult>): List<BatchItemResult> =
        results.filterNot { it.ok }
}
