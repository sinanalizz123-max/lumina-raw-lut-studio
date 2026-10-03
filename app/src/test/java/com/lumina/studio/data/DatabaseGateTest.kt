package com.lumina.studio.data

import com.lumina.studio.core.data.local.DatabaseFailure
import com.lumina.studio.core.data.local.DatabaseGate
import com.lumina.studio.core.data.local.DatabaseRecovery
import com.lumina.studio.core.data.local.DatabaseUnavailableException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Startup gate transitions with a fake database opener (no SQLite/Room).
 * The architectural property under test: RecoveryRequired is surfaced before
 * any real database work, and Retry can transition back to Ready.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.LEGACY)
class DatabaseGateTest {

    private fun appContext() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        DatabaseGate.resetForTest()
        DatabaseRecovery.clear(appContext())
    }

    @After
    fun tearDown() {
        DatabaseGate.resetForTest()
        DatabaseRecovery.clear(appContext())
    }

    @Test
    fun `failed open surfaces recovery required`() {
        DatabaseGate.opener = {
            throw DatabaseUnavailableException(
                DatabaseFailure.LOCKED,
                RuntimeException("database is locked")
            )
        }

        DatabaseGate.initialize(appContext())

        val state = DatabaseGate.state.value
        assertTrue(state is DatabaseGate.State.RecoveryRequired)
        assertEquals(
            DatabaseFailure.LOCKED,
            (state as DatabaseGate.State.RecoveryRequired).info.failure
        )
    }

    @Test
    fun `retry transitions recovery required to ready`() {
        var calls = 0
        DatabaseGate.opener = {
            if (calls++ == 0) {
                throw DatabaseUnavailableException(
                    DatabaseFailure.CORRUPT,
                    RuntimeException("file is not a database")
                )
            }
        }

        DatabaseGate.initialize(appContext())
        assertTrue(DatabaseGate.state.value is DatabaseGate.State.RecoveryRequired)

        DatabaseGate.retry(appContext())
        assertTrue(DatabaseGate.state.value is DatabaseGate.State.Ready)
    }

    @Test
    fun `stored recovery flag gates before opener runs`() {
        DatabaseRecovery.markRequired(
            appContext(),
            DatabaseFailure.CORRUPT,
            "stamp",
            null,
            "file is not a database"
        )
        var opened = false
        DatabaseGate.opener = { opened = true }

        DatabaseGate.initialize(appContext())

        assertFalse(opened)
        assertTrue(DatabaseGate.state.value is DatabaseGate.State.RecoveryRequired)
    }
}
