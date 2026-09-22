package com.lumina.studio.core.export

/**
 * M11 safe-transaction state machine (§39). Pure JVM (no android.*) so the
 * stage order and the cleanup order are unit-pinned; the on-device
 * [Exporter.publishBytes] executes exactly this order.
 *
 * Order: temp file -> render -> validate -> metadata -> MediaStore pending
 * -> finalize. On any failure the transaction releases in this order:
 * 1. delete the incomplete MediaStore item (never a corrupt gallery file),
 * 2. delete the temp file,
 * 3. recycle bitmaps (project source untouched, project intact).
 * Cancellation ([kotlinx.coroutines.CancellationException]) follows the same
 * cleanup path via finally blocks.
 */
object ExportTransaction {

    /** Pipeline stages in execution order. */
    enum class Stage {
        RENDER,
        VALIDATE,
        METADATA,
        PENDING,
        FINALIZE
    }

    /** Cleanup actions; list order is execution order. */
    enum class Cleanup {
        DELETE_PENDING_URI,
        DELETE_TEMP,
        RECYCLE_BITMAPS
    }

    val ORDERED_STAGES: List<Stage> = listOf(
        Stage.RENDER,
        Stage.VALIDATE,
        Stage.METADATA,
        Stage.PENDING,
        Stage.FINALIZE
    )

    /**
     * Ordered cleanup for a failure at [failedAt]. Null means success (only
     * the staging temp remains to be deleted). The pending-URI delete is
     * idempotent: [Exporter.saveToGallery] already deletes its own URI on
     * failure, this entry is the defensive second sweep for PENDING/FINALIZE.
     */
    fun cleanupFor(failedAt: Stage?): List<Cleanup> = when (failedAt) {
        null -> listOf(Cleanup.DELETE_TEMP)
        Stage.RENDER, Stage.VALIDATE, Stage.METADATA ->
            listOf(Cleanup.DELETE_TEMP, Cleanup.RECYCLE_BITMAPS)
        Stage.PENDING, Stage.FINALIZE ->
            listOf(
                Cleanup.DELETE_PENDING_URI,
                Cleanup.DELETE_TEMP,
                Cleanup.RECYCLE_BITMAPS
            )
    }
}
