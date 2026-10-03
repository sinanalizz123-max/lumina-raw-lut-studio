package com.lumina.studio.data

import com.lumina.studio.core.data.local.DatabaseFailure
import com.lumina.studio.core.data.local.DatabaseFailureClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseFailureClassifierTest {

    @Test
    fun `sqlite corruption message is corrupt`() {
        assertEquals(
            DatabaseFailure.CORRUPT,
            DatabaseFailureClassifier.classify(IllegalStateException("file is not a database"))
        )
    }

    @Test
    fun `missing migration is migration`() {
        assertEquals(
            DatabaseFailure.MIGRATION,
            DatabaseFailureClassifier.classify(
                IllegalStateException("A migration from 2 to 3 was required")
            )
        )
    }

    @Test
    fun `locked database is locked`() {
        assertEquals(
            DatabaseFailure.LOCKED,
            DatabaseFailureClassifier.classify(RuntimeException("database is locked"))
        )
    }

    @Test
    fun `unopenable database is io`() {
        assertEquals(
            DatabaseFailure.IO,
            DatabaseFailureClassifier.classify(RuntimeException("unable to open database file"))
        )
    }

    @Test
    fun `wrapped corruption cause is corrupt`() {
        assertEquals(
            DatabaseFailure.CORRUPT,
            DatabaseFailureClassifier.classify(
                RuntimeException(
                    "Room open failed",
                    IllegalStateException("database disk image is malformed")
                )
            )
        )
    }

    @Test
    fun `unrecognized failure is unknown`() {
        assertEquals(
            DatabaseFailure.UNKNOWN,
            DatabaseFailureClassifier.classify(RuntimeException("boom"))
        )
    }

    @Test
    fun `room style wrapper around sqlite corruption is corrupt`() {
        assertEquals(
            DatabaseFailure.CORRUPT,
            DatabaseFailureClassifier.classify(
                IllegalStateException(
                    "Room cannot verify the data integrity",
                    RuntimeException("file is not a database")
                )
            )
        )
    }

    @Test
    fun `fatal vm errors are never classified as recovery events`() {
        assertEquals(
            DatabaseFailure.UNKNOWN,
            DatabaseFailureClassifier.classify(OutOfMemoryError("boom"))
        )
    }
}
