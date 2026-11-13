package io.github.bakeron.tapedo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bakeron.tapedo.core.ai.AiConnection
import io.github.bakeron.tapedo.core.ai.AiProblem
import io.github.bakeron.tapedo.core.ai.AiUsage
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.ai.Spent
import io.github.bakeron.tapedo.core.ai.claudeSpent
import io.github.bakeron.tapedo.core.ai.UsedUp
import io.github.bakeron.tapedo.core.ai.usedUp
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.SettingsStore
import io.github.bakeron.tapedo.core.speech.ListeningMode
import io.github.bakeron.tapedo.core.speech.SpeechInput
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** "Check connection" under a provider. */
sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data class Ok(val model: String) : CheckState
    data class Failed(val problem: AiProblem) : CheckState
}

/** "Show free models" under a free provider. */
sealed interface ModelsState {
    data object Idle : ModelsState
    data object Loading : ModelsState
    data class Loaded(val list: AiConnection.FreeModels) : ModelsState
}

/** The short note under the key field after a save or a delete. */
enum class KeyNote { SAVED, DELETED }

data class ProviderState(
    /** Last 4 characters of the saved key - enough to recognise it, useless to steal. */
    val keySuffix: String? = null,
    /**
     * Key typed but not saved yet. Held here in memory, not in the screen's saved state:
     * it survives a rotation or a language switch, but is never written to disk by the system.
     */
    val draft: String = "",
    /** "Show key" is on - the field shows its text, and the screen may not be captured. */
    val shown: Boolean = false,
    /** The saved key read from the key store for "Show key" (memory only, cleared on "Hide"). */
    val revealed: String? = null,
    val keyNote: KeyNote? = null,
    val check: CheckState = CheckState.Idle,
    val models: ModelsState = ModelsState.Idle,
    val callsToday: Int = 0,
    val callsMonth: Int = 0,
    val usedUp: UsedUp? = null,
    /** Claude only: what the spending counter added up. */
    val spent: Spent? = null,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val secrets: SecretStore,
    private val usage: AiUsage,
    private val connection: AiConnection,
    private val speech: SpeechInput,
    private val todayEpochDay: () -> Long = { LocalDate.now().toEpochDay() },
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _ai = MutableStateFlow(AiProvider.entries.associateWith { ProviderState() })
    val ai: StateFlow<Map<AiProvider, ProviderState>> = _ai.asStateFlow()

    /** The last write (setting or key) failed - shown as one line on the screen. */
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    /** Continuous or restarted after pauses - learnt from the last recording. */
    private val _listeningMode = MutableStateFlow(ListeningMode.UNKNOWN)
    val listeningMode: StateFlow<ListeningMode> = _listeningMode.asStateFlow()

    init {
        viewModelScope.launch {
            refreshKeys()
            refreshUsage()
        }
        viewModelScope.launch {
            // Only a display hint - a read failure just leaves "not known yet".
            try {
                _listeningMode.value = speech.listeningMode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
        }
    }

    private fun edit(provider: AiProvider, change: (ProviderState) -> ProviderState) =
        _ai.update { all -> all + (provider to change(all.getValue(provider))) }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            write { store.update(transform) }
            refreshUsage()
        }
    }

    fun toggle(provider: AiProvider, on: Boolean) = update { s ->
        s.copy(aiProviders = if (on) s.aiProviders + provider else s.aiProviders - provider)
    }

    /** A new model: the provider is asked again today - its "limit" answer was usually about the old model only. */
    fun setModel(provider: AiProvider, model: String) {
        val clean = model.trim().take(AppSettings.MAX_MODEL_NAME)
        viewModelScope.launch {
            val ok = write {
                store.update { s -> s.copy(aiModels = if (clean.isEmpty()) s.aiModels - provider else s.aiModels + (provider to clean)) }
                usage.clearLimit(provider, todayEpochDay())
            }
            if (ok) edit(provider) { it.copy(check = CheckState.Idle) }
            refreshUsage()
        }
    }

    /** A new top-up of the Claude account: counted from today; null clears it. */
    fun setTopUp(cents: Int?) {
        val today = todayEpochDay()
        update { s -> s.copy(claudeTopUpCents = cents, claudeTopUpDay = cents?.let { today }) }
    }

    fun editKey(provider: AiProvider, text: String) = edit(provider) { it.copy(draft = text.trim(), keyNote = null) }

    /** "Show key": with an empty field the saved key is read from the key store - only on this tap. */
    fun showKey(provider: AiProvider, show: Boolean) {
        val state = _ai.value.getValue(provider)
        if (!show) {
            edit(provider) { it.copy(shown = false, revealed = null, draft = if (it.draft == it.revealed) "" else it.draft) }
            return
        }
        edit(provider) { it.copy(shown = true) }
        if (state.draft.isEmpty() && state.keySuffix != null) {
            viewModelScope.launch {
                val key = try {
                    secrets.apiKey(provider)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _saveFailed.value = true
                    null
                }
                if (key != null) edit(provider) { if (it.shown) it.copy(draft = key, revealed = key) else it }
            }
        }
    }

    fun saveKey(provider: AiProvider) {
        val key = _ai.value.getValue(provider).draft
        if (key.isBlank()) return
        viewModelScope.launch {
            // The typed key is cleared only once it is safely stored.
            if (write { secrets.setApiKey(provider, key) }) {
                edit(provider) { it.copy(draft = "", revealed = null, shown = false, keyNote = KeyNote.SAVED, check = CheckState.Idle) }
            }
            refreshKeys()
        }
    }

    fun deleteKey(provider: AiProvider) {
        viewModelScope.launch {
            if (write { secrets.setApiKey(provider, null) }) {
                edit(provider) { it.copy(draft = "", revealed = null, shown = false, keyNote = KeyNote.DELETED, check = CheckState.Idle) }
            }
            refreshKeys()
        }
    }

    fun keyNoteSeen(provider: AiProvider) = edit(provider) { it.copy(keyNote = null) }

    /** One tiny question with the model from the settings - counts as one call of the day. */
    fun check(provider: AiProvider) {
        if (_ai.value.getValue(provider).check == CheckState.Running) return
        edit(provider) { it.copy(check = CheckState.Running) }
        viewModelScope.launch {
            val s = settings.value ?: store.settings.first()
            val state = when (val r = connection.check(provider, s, todayEpochDay())) {
                is AiConnection.Check.Ok -> CheckState.Ok(r.model)
                is AiConnection.Check.Failed -> CheckState.Failed(r.problem)
            }
            edit(provider) { it.copy(check = state) }
            refreshUsage()
        }
    }

    fun fetchModels(provider: AiProvider) {
        if (_ai.value.getValue(provider).models == ModelsState.Loading) return
        edit(provider) { it.copy(models = ModelsState.Loading) }
        viewModelScope.launch {
            val s = settings.value ?: store.settings.first()
            val list = connection.freeModels(provider, s)
            edit(provider) { it.copy(models = ModelsState.Loaded(list)) }
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

    private suspend fun refreshKeys() {
        for (p in AiProvider.entries) {
            val key = try {
                secrets.apiKey(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Read failed only for now - keep showing what was known, never "no key".
                continue
            }
            val suffix = key?.takeIf { it.length >= 8 }?.takeLast(4)
            edit(p) { it.copy(keySuffix = suffix) }
        }
    }

    private suspend fun refreshUsage() {
        val today = todayEpochDay()
        // Straight from the store: right after a change the shared state may still hold the old settings.
        val s = store.settings.first()
        val limit = s.aiDailyLimit
        try {
            val spent = usage.claudeSpent(today, s.claudeTopUpDay)
            edit(AiProvider.ANTHROPIC) { it.copy(spent = spent) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only shown - the last sums stay on screen.
        }
        for (p in AiProvider.entries) {
            try {
                val calls = usage.callsToday(p, today)
                val month = usage.callsLast30Days(p, today)
                val used = usage.usedUp(p, today, limit)
                edit(p) { it.copy(callsToday = calls, callsMonth = month, usedUp = used) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Counts are only shown - a read failure leaves the last ones on screen.
                continue
            }
        }
    }
}
