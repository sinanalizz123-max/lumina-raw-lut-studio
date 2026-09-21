package com.lumina.studio.datastore

import com.lumina.studio.core.data.datastore.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SettingsRepository defaults + read/write against a real DataStore file
 * under Robolectric (no device/emulator). Each test method gets a fresh
 * Robolectric Application sandbox, so the DataStore starts empty and the
 * spec defaults ("dark" / false) are observable.
 *
 * Robolectric config notes (host is Termux Linux/aarch64 with bionic libc,
 * no glibc): sdk=34 avoids the API-35+ upfront native-runtime preload
 * (DefaultNativeRuntimeLoader only supports linux-x86_64/mac/windows);
 * Conscrypt OFF avoids loading libconscrypt .so which needs libpthread.so.0;
 * LEGACY graphics/looper avoid other native paths. DataStore file I/O needs
 * none of those, so these tests run green here and on x86_64 CI alike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.LEGACY)
class SettingsRepositoryTest {

    private fun repository(): SettingsRepository =
        SettingsRepository(RuntimeEnvironment.getApplication())

    @Test
    fun `theme defaults to dark`() = runBlocking {
        assertEquals("dark", repository().theme.first())
    }

    @Test
    fun `onboardingDone defaults to false`() = runBlocking {
        assertFalse(repository().onboardingDone.first())
    }

    @Test
    fun `theme read write round-trip`() = runBlocking {
        val repo = repository()
        repo.setTheme("light")
        assertEquals("light", repo.theme.first())
        repo.setTheme("dark")
        assertEquals("dark", repo.theme.first())
    }

    @Test
    fun `onboardingDone read write round-trip`() = runBlocking {
        val repo = repository()
        repo.setOnboardingDone(true)
        assertTrue(repo.onboardingDone.first())
        repo.setOnboardingDone(false)
        assertFalse(repo.onboardingDone.first())
    }
}
