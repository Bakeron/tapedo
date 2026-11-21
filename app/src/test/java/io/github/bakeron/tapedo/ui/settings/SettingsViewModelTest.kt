package io.github.bakeron.tapedo.ui.settings

import io.github.bakeron.tapedo.core.ai.AiAnswer
import io.github.bakeron.tapedo.core.ai.AiChat
import io.github.bakeron.tapedo.core.ai.AiConnection
import io.github.bakeron.tapedo.core.ai.AiGateway
import io.github.bakeron.tapedo.core.ai.MemoryAiUsage
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.SettingsStore
import io.github.bakeron.tapedo.core.speech.ListeningMode
import io.github.bakeron.tapedo.core.speech.SpeechEvent
import io.github.bakeron.tapedo.core.speech.SpeechInput
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
        SettingsViewModel(store, secrets, usage, AiConnection(secrets, usage, OkGateway), NoSpeech, todayEpochDay = { 20_400L })

    private val usage = MemoryAiUsage()
    private val store = FakeStore()

    private object OkGateway : AiGateway {
        override fun chat(provider: AiProvider, model: String, key: String) = AiChat { _, _, _ -> AiAnswer("OK", model) }
        override suspend fun freeModels(provider: AiProvider, key: String?) = listOf("m:free")
    }

    private object NoSpeech : SpeechInput {
        override fun listen(languageTag: String, allowOnline: Boolean, skipFirstMs: Long, pauseMillis: Long?) = emptyFlow<SpeechEvent>()
        override fun stop() = Unit
        override suspend fun listeningMode() = ListeningMode.CONTINUOUS
    }

    private fun SettingsViewModel.of(p: AiProvider) = ai.value.getValue(p)

    @Test
    fun `a failed key save keeps the typed key, and a good save clears it and shows the suffix`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { failWrites = true }
        val vm = viewModel(secrets)
        runCurrent()
        val key = "AIzaSy-test-key-WXYZ"

        vm.editKey(AiProvider.GEMINI, key)
        vm.saveKey(AiProvider.GEMINI)
        runCurrent()
        assertTrue(vm.saveFailed.value)
        assertEquals("the typed key must not be lost", key, vm.of(AiProvider.GEMINI).draft)
        assertNull(vm.of(AiProvider.GEMINI).keySuffix)

        secrets.failWrites = false
        vm.saveKey(AiProvider.GEMINI)
        runCurrent()
        assertFalse(vm.saveFailed.value)
        assertEquals("", vm.of(AiProvider.GEMINI).draft)
        assertEquals("WXYZ", vm.of(AiProvider.GEMINI).keySuffix)
        assertEquals(KeyNote.SAVED, vm.of(AiProvider.GEMINI).keyNote)
        assertEquals(key, secrets.keys[AiProvider.GEMINI])
    }

    @Test
    fun `a key that cannot be read for a moment neither crashes the screen nor disappears`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { keys[AiProvider.GROQ] = "gsk_abcdefgh1234" }
        val vm = viewModel(secrets)
        runCurrent()
        assertEquals("1234", vm.of(AiProvider.GROQ).keySuffix)

        secrets.failReads = true
        vm.editKey(AiProvider.GEMINI, "AIzaSy-test-key-WXYZ")
        vm.saveKey(AiProvider.GEMINI)
        runCurrent()
        assertEquals("the saved Groq key must still show", "1234", vm.of(AiProvider.GROQ).keySuffix)
    }

    @Test
    fun `deleting a key removes its suffix`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { keys[AiProvider.GROQ] = "gsk_abcdefgh1234" }
        val vm = viewModel(secrets)
        runCurrent()
        vm.deleteKey(AiProvider.GROQ)
        runCurrent()
        assertNull(vm.of(AiProvider.GROQ).keySuffix)
        assertFalse(vm.saveFailed.value)
    }

    @Test
    fun `show key reads the saved key only on demand, and hide takes it off the screen`() = runTest(dispatcher) {
        val secrets = FakeSecrets().apply { keys[AiProvider.GROQ] = "gsk_abcdefgh1234" }
        val vm = viewModel(secrets)
        runCurrent()
        assertEquals("", vm.of(AiProvider.GROQ).draft)

        vm.showKey(AiProvider.GROQ, true)
        runCurrent()
        assertEquals("gsk_abcdefgh1234", vm.of(AiProvider.GROQ).draft)
        assertTrue(vm.of(AiProvider.GROQ).shown)

        vm.showKey(AiProvider.GROQ, false)
        assertEquals("", vm.of(AiProvider.GROQ).draft)
        assertNull(vm.of(AiProvider.GROQ).revealed)
    }

    @Test
    fun `a new model lets the provider be asked again today`() = runTest(dispatcher) {
        val vm = viewModel(FakeSecrets())
        runCurrent()
        usage.markLimit(AiProvider.GROQ, 20_400L)
        vm.setModel(AiProvider.GROQ, " llama-3.1-8b-instant ")
        runCurrent()
        assertFalse(usage.limitHit(AiProvider.GROQ, 20_400L))
        assertEquals("llama-3.1-8b-instant", store.state.value.aiModels[AiProvider.GROQ])
    }

    @Test
    fun `check connection counts one call and shows the model`() = runTest(dispatcher) {
        val vm = viewModel(FakeSecrets().apply { keys[AiProvider.GROQ] = "gsk_abcdefgh" })
        runCurrent()
        vm.check(AiProvider.GROQ)
        runCurrent()
        assertEquals(CheckState.Ok(AiProvider.GROQ.defaultModel), vm.of(AiProvider.GROQ).check)
        assertEquals(1, vm.of(AiProvider.GROQ).callsToday)
    }
}
