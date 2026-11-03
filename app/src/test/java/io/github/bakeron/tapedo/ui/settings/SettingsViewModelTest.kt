package io.github.bakeron.tapedo.ui.settings

import io.github.bakeron.tapedo.core.ai.NoteStructurer
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.SettingsStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeStore : SettingsStore {
        val state = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = state
        override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
    }

    /** Keys in memory; while [failWrites] is set every write throws, like a Keystore or disk error. */
    private class FakeSecrets : SecretStore {
        val keys = mutableMapOf<AiProvider, String>()
        var failWrites = false
        var failReads = false
        override suspend fun apiKey(provider: AiProvider): String? {
            if (failReads) throw IOException("keystore busy")
            return keys[provider]
        }
        override suspend fun setApiKey(provider: AiProvider, key: String?) {
            if (failWrites) throw IOException("keystore busy")
            if (key.isNullOrBlank()) keys -= provider else keys[provider] = key
        }
    }

    private fun viewModel(secrets: SecretStore) =
        SettingsViewModel(FakeStore(), secrets, NoteStructurer(secrets) { _, _ -> null })

    @Test
    fun `a failed key save keeps the typed key, and a good save clears it and shows the suffix`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { failWrites = true }
        val vm = viewModel(secrets)
        runCurrent()
        val key = "AIzaSy-test-key-WXYZ"

        vm.editKey(AiProvider.GEMINI, key)
        vm.saveKey(AiProvider.GEMINI, key)
        runCurrent()
        assertTrue(vm.saveFailed.value)
        assertEquals("the typed key must not be lost", key, vm.keys.value.drafts[AiProvider.GEMINI])
        assertNull(vm.keys.value.savedSuffix[AiProvider.GEMINI])

        secrets.failWrites = false
        vm.saveKey(AiProvider.GEMINI, key)
        runCurrent()
        assertFalse(vm.saveFailed.value)
        assertNull(vm.keys.value.drafts[AiProvider.GEMINI])
        assertEquals("WXYZ", vm.keys.value.savedSuffix[AiProvider.GEMINI])
        assertEquals(key, secrets.keys[AiProvider.GEMINI])
    }

    @Test
    fun `a key that cannot be read for a moment neither crashes the screen nor disappears`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { keys[AiProvider.GROQ] = "gsk_abcdefgh1234" }
        val vm = viewModel(secrets)
        runCurrent()
        assertEquals("1234", vm.keys.value.savedSuffix[AiProvider.GROQ])

        secrets.failReads = true
        vm.saveKey(AiProvider.GEMINI, "AIzaSy-test-key-WXYZ")
        runCurrent()
        assertEquals("the saved Groq key must still show", "1234", vm.keys.value.savedSuffix[AiProvider.GROQ])
    }

    @Test
    fun `deleting a key removes its suffix`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { keys[AiProvider.GROQ] = "gsk_abcdefgh1234" }
        val vm = viewModel(secrets)
        runCurrent()
        assertEquals("1234", vm.keys.value.savedSuffix[AiProvider.GROQ])

        vm.deleteKey(AiProvider.GROQ)
        runCurrent()
        assertNull(vm.keys.value.savedSuffix[AiProvider.GROQ])
        assertFalse(vm.saveFailed.value)
    }
}
