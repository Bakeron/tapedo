package io.github.bakeron.tapedo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bakeron.tapedo.core.ai.AiError
import io.github.bakeron.tapedo.core.ai.NoteStructurer
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.SettingsStore
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface KeyTest {
    data object Idle : KeyTest
    data object Running : KeyTest
    data class Ok(val firstTitle: String) : KeyTest
    data class Failed(val error: AiError) : KeyTest
}

data class AiKeyState(
    /** Last 4 characters of the saved key per provider - enough to recognise it, useless to steal. */
    val savedSuffix: Map<AiProvider, String> = emptyMap(),
    val test: KeyTest = KeyTest.Idle,
    /**
     * Key typed but not saved yet, per provider. Held here in memory, not in the screen's saved state:
     * it survives a rotation or a language switch, but is never written to disk by the system.
     */
    val drafts: Map<AiProvider, String> = emptyMap(),
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val secrets: SecretStore,
    private val structurer: NoteStructurer,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _keys = MutableStateFlow(AiKeyState())
    val keys: StateFlow<AiKeyState> = _keys.asStateFlow()

    /** The last write (setting or key) failed - shown as one line on the screen. */
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    init {
        viewModelScope.launch { refreshKeys() }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { write { store.update(transform) } }
        _keys.update { it.copy(test = KeyTest.Idle) }
    }

    fun editKey(provider: AiProvider, text: String) {
        _keys.update { it.copy(drafts = it.drafts + (provider to text)) }
    }

    fun saveKey(provider: AiProvider, key: String) {
        viewModelScope.launch {
            // The typed key is cleared only once it is safely stored.
            if (write { secrets.setApiKey(provider, key) }) _keys.update { it.copy(drafts = it.drafts - provider) }
            refreshKeys()
            _keys.update { it.copy(test = KeyTest.Idle) }
        }
    }

    /** Runs a write; a failure (full disk, Keystore error) shows a message instead of closing the app. */
    private suspend fun write(block: suspend () -> Unit): Boolean = try {
        block()
        _saveFailed.value = false
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _saveFailed.value = true
        false
    }

    fun deleteKey(provider: AiProvider) = saveKey(provider, "")

    /** Sends a harmless sample sentence (no user data) to check the key end to end. */
    fun testKey(provider: AiProvider, sample: String, listTitles: ListTitles) {
        _keys.update { it.copy(test = KeyTest.Running) }
        viewModelScope.launch {
            val s = settings.value ?: AppSettings()
            val result = structurer.structure(
                transcript = sample,
                provider = provider,
                chosenType = null,
                defaultPriority = s.defaultPriority,
                listTitles = listTitles,
                todayEpochDay = LocalDate.now().toEpochDay(),
                nowMillis = System.currentTimeMillis(),
            )
            val test = result.aiError?.let { KeyTest.Failed(it) }
                ?: KeyTest.Ok(result.notes.firstOrNull()?.title.orEmpty())
            _keys.update { it.copy(test = test) }
        }
    }

    private suspend fun refreshKeys() {
        val suffixes = listOf(AiProvider.GEMINI, AiProvider.GROQ).mapNotNull { p ->
            val key = try {
                secrets.apiKey(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Read failed only for now - keep showing what was known, never "no key".
                return@mapNotNull _keys.value.savedSuffix[p]?.let { p to it }
            }
            key?.takeIf { it.length >= 8 }?.let { p to it.takeLast(4) }
        }.toMap()
        _keys.update { it.copy(savedSuffix = suffixes) }
    }
}
