package io.github.bakeron.tapedo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bakeron.tapedo.core.ai.AiProblem
import io.github.bakeron.tapedo.core.ai.NoteStructurer
import io.github.bakeron.tapedo.core.ai.StructureResult
import io.github.bakeron.tapedo.core.data.NoteRepository
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.settings.RecordingEnd
import io.github.bakeron.tapedo.core.settings.SettingsStore
import io.github.bakeron.tapedo.core.speech.Phrases
import io.github.bakeron.tapedo.core.speech.SilenceCountdown
import io.github.bakeron.tapedo.core.speech.SpeechError
import io.github.bakeron.tapedo.core.speech.SpeechEvent
import io.github.bakeron.tapedo.core.speech.SpeechInput
import io.github.bakeron.tapedo.data.LockedNotesNotice
import java.io.IOException
import java.time.LocalDate
import kotlin.math.max
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RecordingState {
    data object Idle : RecordingState

    data class Active(
        val elapsedSeconds: Int,
        val limitSeconds: Int,
        val text: String,
        /** Recent voice levels 0..1, oldest first - the seismograph line. */
        val levels: List<Float>,
        /** How many samples were ever taken - keeps the zigzag stable while the line scrolls. */
        val sampleCount: Long,
        /** False while the phone restarts its recognizer after a pause - the pen lifts, "one moment". */
        val listening: Boolean = true,
    ) : RecordingState

    /** Speech is done; the rules or the AI are cutting the text into cards. */
    data class Structuring(val transcript: String) : RecordingState

    /**
     * The cards of a recording, already written to the database (a card with id 0 is not saved yet -
     * see [saveFailed]). The user can still edit them, record more or delete them.
     */
    data class Review(
        val transcript: String,
        val cards: List<Note>,
        /** AI was turned on but every provider failed - the rules wrote these cards; each reason is listed. */
        val aiProblems: List<AiProblem> = emptyList(),
        /** Some cards could not be written - they are only on screen until "try again" works. */
        val saveFailed: Boolean = false,
        /** "Record more" heard nothing usable (or could not start) - the cards are unchanged. */
        val moreFailed: SpeechError? = null,
    ) : RecordingState

    data class Failed(val error: SpeechError) : RecordingState
}

sealed interface StackState {
    data object Loading : StackState
    data class Loaded(val notes: List<Note>) : StackState

    /** The database could not be opened or read (disk, Keystore) - the screen offers "try again". */
    data object Failed : StackState
}

/** What the full-screen card editor is open on. */
sealed interface Editing {
    val note: Note

    /** A card from the review window (saved right after the recording, unless the save failed). */
    data class Draft(val index: Int, override val note: Note) : Editing

    /** A card from the stack. */
    data class Saved(override val note: Note) : Editing
}

/** Reasons are shown only when the rules had to write the cards - a provider that took over hides the others. */
private fun StructureResult.rulesProblems(): List<AiProblem>? =
    if (notes.any { it.byAi }) null else problems.ifEmpty { null }

/** Home screen: type choice, recording, the review window after it and the card stack. */
class HomeViewModel(
    private val store: SettingsStore,
    private val speech: SpeechInput,
    private val notes: NoteRepository,
    private val structurer: NoteStructurer,
    private val lockedNotice: LockedNotesNotice,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val todayEpochDay: () -> Long = { LocalDate.now().toEpochDay() },
    /** Clock for the recording limit - a virtual one in tests. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    /** Default list titles in the app language - set by the screen, which can read resources. */
    var listTitles: ListTitles = ListTitles()

    val settings: StateFlow<AppSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _recording = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recording: StateFlow<RecordingState> = _recording.asStateFlow()

    private val reload = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val stack: StateFlow<StackState> = reload
        .flatMapLatest {
            notes.observeAll()
                .map<List<Note>, StackState> { StackState.Loaded(it) }
                .onStart { emit(StackState.Loading) }
                .catch { emit(StackState.Failed) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StackState.Loading)

    private val _editing = MutableStateFlow<Editing?>(null)
    val editing: StateFlow<Editing?> = _editing.asStateFlow()

    /** The card open in the preview (one card, whole screen), read fresh from the stack after every change. */
    private val previewId = MutableStateFlow<Long?>(null)
    val preview: StateFlow<Note?> = combine(stack, previewId) { st, id ->
        if (id == null) null else (st as? StackState.Loaded)?.notes?.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Words said in the preview are being added to the card. */
    private val _appending = MutableStateFlow(false)
    val appending: StateFlow<Boolean> = _appending.asStateFlow()

    /** The last card deleted from the stack, while "undo" is still offered. */
    private val _deleted = MutableStateFlow<Note?>(null)
    val deleted: StateFlow<Note?> = _deleted.asStateFlow()

    /** A change to a saved card (tick, edit, delete) could not be written. */
    private val _actionFailed = MutableStateFlow(false)
    val actionFailed: StateFlow<Boolean> = _actionFailed.asStateFlow()

    /** Old notes were set aside after Android lost the database key - told once. */
    private val _lockedNotes = MutableStateFlow(false)
    val lockedNotes: StateFlow<Boolean> = _lockedNotes.asStateFlow()

    private var session: Job? = null
    private var undoTimer: Job? = null
    private var saving = false

    /** The review window put aside while "record more" listens - it comes back whatever happens. */
    private var continuing: RecordingState.Review? = null

    init {
        // The set-aside happens while the database opens, so look for it after the first good read.
        viewModelScope.launch {
            stack.first { it is StackState.Loaded }
            _lockedNotes.value = lockedNotice.pending()
        }
    }

    // --- Type and recording ---

    /** Remembers the type for the next recording; null = "auto". */
    fun chooseType(type: NoteType?) {
        viewModelScope.launch {
            try {
                store.update { it.copy(noteTypeChoice = type) }
            } catch (e: IOException) {
                // A failed write just leaves the old choice on screen - nothing is lost, so no message.
            }
        }
    }

    /** No offline language pack: the user agreed to online recognition - always from now on. */
    fun allowOnlineAlways(appendTo: Long? = null) {
        viewModelScope.launch {
            try {
                store.update { it.copy(onlineSpeech = OnlineSpeech.ALWAYS) }
            } catch (e: IOException) {
                // Not remembered, but the consent still covers this recording.
            }
            startRecording(allowOnlineOnce = true, appendTo = appendTo)
        }
    }

    /**
     * @param allowOnlineOnce the user agreed to online recognition for this one recording.
     * @param appendTo id of a saved card: what is said is added to it, with no review window.
     */
    fun startRecording(allowOnlineOnce: Boolean = false, appendTo: Long? = null) {
        if (session?.isActive == true) return
        // Cards still waiting for a decision are never replaced by a new recording.
        if (_recording.value is RecordingState.Review || _recording.value is RecordingState.Structuring) return
        val s = settings.value ?: AppSettings()
        val allowOnline = allowOnlineOnce || s.onlineSpeech == OnlineSpeech.ALWAYS
        val limit = s.maxRecordingSeconds
        // "On silence" is counted here, not by the recognizer - its own pause is far too short to think.
        val silence = if (s.recordingEnd == RecordingEnd.SILENCE) SilenceCountdown(s.silenceSeconds * 1000L) else null
        _recording.value = RecordingState.Active(0, limit, "", emptyList(), 0)

        session = viewModelScope.launch {
            var level = 0f
            // Real elapsed time: delay() runs a little late each tick, so counting ticks drifts behind the clock.
            val started = timeSource.markNow()
            // Fixed-rate sampling: the pen keeps moving in silence, like paper under a seismograph.
            val sampler = launch {
                var limitHit = false
                while (true) {
                    delay(TICK_MS)
                    val elapsedMs = started.elapsedNow().inWholeMilliseconds
                    val sample = level
                    _recording.update { st ->
                        if (st !is RecordingState.Active) st
                        else st.copy(
                            elapsedSeconds = (elapsedMs / 1000).toInt(),
                            levels = (st.levels + sample).takeLast(SEISMOGRAPH_SAMPLES),
                            sampleCount = st.sampleCount + 1,
                        )
                    }
                    level *= DECAY // falls back between words
                    if (!limitHit && (elapsedMs >= limit * 1000L || silence?.isOver(elapsedMs) == true)) {
                        limitHit = true
                        speech.stop() // the limit or the silence ends the recording like the stop button would
                    }
                }
            }
            try {
                speech.listen(s.speechLanguage, allowOnline = allowOnline).collect { e ->
                    when (e) {
                        is SpeechEvent.Level -> level = max(level, e.value)
                        is SpeechEvent.Partial -> {
                            silence?.heard(e.text, started.elapsedNow().inWholeMilliseconds)
                            _recording.update { if (it is RecordingState.Active) it.copy(text = e.text) else it }
                        }
                        is SpeechEvent.Listening -> {
                            // Back to listening: a short jolt of the pen shows "you can speak again".
                            if (e.on && !s.reduceMotion) level = max(level, LISTENING_AGAIN_JOLT)
                            _recording.update { if (it is RecordingState.Active) it.copy(listening = e.on) else it }
                        }
                        is SpeechEvent.Done -> when {
                            appendTo != null -> appendSpoken(appendTo, e.text)
                            continuing != null -> continueReview(e.text)
                            else -> onTranscript(e.text)
                        }
                        is SpeechEvent.Failed -> endRecording(e.error)
                    }
                }
                if (_recording.value is RecordingState.Active) endRecording(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The recognizer itself crashed - a message instead of closing the app.
                endRecording(SpeechError.OTHER)
            } finally {
                sampler.cancel()
            }
        }
    }

    fun stopRecording() = speech.stop()

    /** Back to the review window when "record more" was listening; otherwise the error (or nothing) on the stack. */
    private fun endRecording(error: SpeechError?) {
        val review = continuing
        continuing = null
        _recording.value = when {
            review != null -> review.copy(moreFailed = error)
            error != null -> RecordingState.Failed(error)
            else -> RecordingState.Idle
        }
    }

    /**
     * "Record more" in the review window: keeps listening for the same dictation, e.g. a shopping list
     * finished at the open fridge. The cards on screen are never lost - they come back even if nothing is heard.
     */
    fun recordMore(allowOnlineOnce: Boolean = false) {
        val review = _recording.value as? RecordingState.Review ?: return
        if (saving || session?.isActive == true) return
        continuing = review.copy(moreFailed = null)
        _recording.value = RecordingState.Idle
        startRecording(allowOnlineOnce)
    }

    /** The microphone was refused for "record more" - the review stays, with the reason. */
    fun recordMoreDenied() {
        _recording.update { if (it is RecordingState.Review) it.copy(moreFailed = SpeechError.NO_PERMISSION) else it }
    }

    /**
     * Adds what was said to the review: to the list card as new items (keeping the user's edits),
     * or - with no list among the cards - cuts the whole dictation again, as if it had been one recording.
     */
    private fun continueReview(text: String) {
        val review = continuing ?: return
        continuing = null
        val spoken = text.trim()
        if (spoken.isEmpty()) {
            _recording.value = review.copy(moreFailed = SpeechError.NO_SPEECH)
            return
        }
        val transcript = Phrases.join(listOf(review.transcript, spoken))
        val listIndex = review.cards.indexOfLast { it.type.hasItems }
        if (listIndex < 0) {
            onTranscript(transcript, replacing = review.cards)
            return
        }
        _recording.value = RecordingState.Structuring(transcript)
        viewModelScope.launch {
            val (added, problems) = spokenItems(spoken, review.cards[listIndex].type)
            val cards = review.cards.mapIndexed { i, card -> if (i == listIndex) card.copy(items = card.items + added) else card }
            _recording.value = persist(review.copy(transcript = transcript, cards = cards, aiProblems = problems ?: review.aiProblems))
        }
    }

    /** Items said for a list of [type], split and grouped like a new list (rules or the chosen AI); never empty. */
    private suspend fun spokenItems(spoken: String, type: NoteType): Pair<List<ChecklistItem>, List<AiProblem>?> {
        val s = settings.value ?: AppSettings()
        val result = try {
            structurer.structure(
                transcript = spoken,
                settings = s,
                chosenType = type,
                listTitles = listTitles,
                todayEpochDay = todayEpochDay(),
                nowMillis = nowMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val group = if (type == NoteType.SHOPPING) ShoppingGroup.OTHER else null
        val items = result?.notes?.flatMap { it.items }.orEmpty().ifEmpty { listOf(ChecklistItem(spoken, group = group)) }
        return items to result?.rulesProblems()
    }

    /** The user refused the microphone permission. */
    fun micDenied() {
        _recording.value = RecordingState.Failed(SpeechError.NO_PERMISSION)
    }

    /** Closes the error message; the stack shows again. */
    fun dismissError() {
        if (_recording.value is RecordingState.Failed) _recording.value = RecordingState.Idle
    }

    /**
     * Cuts what was said into cards (rules or AI), writes them to the database at once and opens the review window.
     * @param replacing saved cards of the same dictation that the new cards take the place of ("record more").
     */
    fun onTranscript(text: String, replacing: List<Note> = emptyList()) {
        val transcript = text.trim()
        if (transcript.isEmpty()) {
            _recording.value = RecordingState.Failed(SpeechError.NO_SPEECH)
            return
        }
        _recording.value = RecordingState.Structuring(transcript)
        viewModelScope.launch {
            val s = settings.value ?: AppSettings()
            val now = nowMillis()
            val result = try {
                structurer.structure(
                    transcript = transcript,
                    settings = s,
                    chosenType = s.noteTypeChoice,
                    listTitles = listTitles,
                    todayEpochDay = todayEpochDay(),
                    nowMillis = now,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected (e.g. the rules crashed) - the words are kept as one plain card.
                null
            }
            val cards = result?.notes?.takeIf { it.isNotEmpty() }
                ?: listOf(Note(title = firstWords(transcript), body = transcript, createdAtMillis = now))
            val review = persist(RecordingState.Review(transcript, cards, result?.rulesProblems().orEmpty()))
            _recording.value = review
            // The old cards go only once the new ones are safely written.
            if (!review.saveFailed) replacing.filter { it.id != 0L }.forEach { old -> write { notes.delete(old.id) } }
        }
    }

    /**
     * Writes the review cards to the database right away - a dictation must survive the phone closing the app
     * in the background (it did: a shopping list said at home was gone in the shop). Saved cards are updated
     * in place; on a failure the rest stay on screen, marked, and "try again" writes them.
     */
    private suspend fun persist(review: RecordingState.Review): RecordingState.Review {
        val cards = review.cards.toMutableList()
        return try {
            // Last card first, so the first card lands on top of the stack.
            for (i in cards.indices.reversed()) {
                cards[i] = cards[i].copy(id = notes.save(cards[i]))
            }
            review.copy(cards = cards, saveFailed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            review.copy(cards = cards, saveFailed = true)
        }
    }

    /**
     * The app goes to the background (screen off, another app): the card being edited and any card
     * not saved yet are written now - the phone may close the app before the user comes back.
     */
    fun onBackground() {
        val edited = _editing.value?.note?.tidy()
        val review = _recording.value as? RecordingState.Review
        viewModelScope.launch {
            if (edited != null && edited.id != 0L) write { notes.save(edited) }
            if (review != null && review.saveFailed && !saving) {
                saving = true
                try {
                    _recording.value = persist(review)
                } finally {
                    saving = false
                }
            }
        }
    }

    /**
     * Adds what was said in the preview to a saved card: to a list as new items (split and grouped like a
     * new list of that type - by the rules or the chosen AI), to any other card as a new line of text.
     */
    private fun appendSpoken(id: Long, text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) {
            _recording.value = RecordingState.Failed(SpeechError.NO_SPEECH)
            return
        }
        _recording.value = RecordingState.Idle
        _appending.value = true
        viewModelScope.launch {
            try {
                val note = notes.observeAll().first().firstOrNull { it.id == id } ?: return@launch
                val updated = if (note.type.hasItems) {
                    note.copy(items = note.items + spokenItems(spoken, note.type).first)
                } else {
                    note.copy(body = listOf(note.body.trim(), spoken).filter { it.isNotEmpty() }.joinToString("\n"))
                }
                write { notes.save(updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionFailed.value = true
            } finally {
                _appending.value = false
            }
        }
    }

    // --- Preview ---

    fun openPreview(note: Note) {
        previewId.value = note.id
    }

    /** Back from the preview; a recording still running there ends and its words are still added. */
    fun closePreview() {
        if (_recording.value is RecordingState.Active) speech.stop()
        dismissError()
        previewId.value = null
    }

    /** Back to the stack first, so undo there brings the card back in the box, not on a preview. */
    fun deleteFromPreview(note: Note) {
        closePreview()
        delete(note)
    }

    // --- Review window ---

    /** "Done" (or back): the cards are already saved - closes the window; a failed save is tried again first. */
    fun saveReview() {
        val review = _recording.value as? RecordingState.Review ?: return
        if (saving) return
        if (!review.saveFailed) {
            _recording.value = RecordingState.Idle
            return
        }
        saving = true
        viewModelScope.launch {
            try {
                val saved = persist(review)
                _recording.value = if (saved.saveFailed) saved else RecordingState.Idle
            } finally {
                saving = false
            }
        }
    }

    /** "Delete" (two taps): removes the whole recording - the saved cards too. */
    fun discardReview() {
        val review = _recording.value as? RecordingState.Review ?: return
        _recording.value = RecordingState.Idle
        viewModelScope.launch {
            review.cards.filter { it.id != 0L }.forEach { card -> write { notes.delete(card.id) } }
        }
    }

    /** "Delete this card": off the screen and out of the database. */
    fun removeCard(index: Int) {
        val review = _recording.value as? RecordingState.Review ?: return
        val card = review.cards.getOrNull(index) ?: return
        val left = review.cards.filterIndexed { i, _ -> i != index }
        _recording.value = if (left.isEmpty()) RecordingState.Idle else review.copy(cards = left)
        if (card.id != 0L) viewModelScope.launch { write { notes.delete(card.id) } }
    }

    // --- Editor ---

    fun editDraft(index: Int) {
        val review = _recording.value as? RecordingState.Review ?: return
        review.cards.getOrNull(index)?.let { _editing.value = Editing.Draft(index, it) }
    }

    fun editSaved(note: Note) {
        _editing.value = Editing.Saved(note)
    }

    fun changeEditing(note: Note) {
        _editing.update {
            when (it) {
                is Editing.Draft -> it.copy(note = note)
                is Editing.Saved -> it.copy(note = note)
                null -> null
            }
        }
    }

    /** "Done" or back: keeps the changes. A saved card stays open if the write fails. */
    fun finishEditing() {
        when (val e = _editing.value) {
            null -> Unit
            is Editing.Draft -> {
                val card = e.note.tidy()
                _recording.update { st ->
                    if (st !is RecordingState.Review || e.index !in st.cards.indices) st
                    else st.copy(cards = st.cards.mapIndexed { i, c -> if (i == e.index) card else c })
                }
                _editing.value = null
                // Already in the database - the edit goes there too (a card not saved yet waits for "try again").
                if (card.id != 0L) viewModelScope.launch { write { notes.save(card) } }
            }
            is Editing.Saved -> viewModelScope.launch {
                if (write { notes.save(e.note.tidy()) }) _editing.value = null
            }
        }
    }

    fun deleteEditing() {
        when (val e = _editing.value) {
            null -> Unit
            is Editing.Draft -> removeCard(e.index)
            is Editing.Saved -> {
                // Back to the stack, not to the preview of a deleted card (undo would bring it back on screen).
                previewId.value = null
                delete(e.note)
            }
        }
        _editing.value = null
    }

    // --- Stack ---

    fun retryStack() {
        reload.update { it + 1 }
    }

    fun toggleItem(note: Note, index: Int) {
        val items = note.items.mapIndexed { i, item -> if (i == index) item.copy(checked = !item.checked) else item }
        viewModelScope.launch { write { notes.save(note.copy(items = items)) } }
    }

    fun toggleDone(note: Note) {
        viewModelScope.launch { write { notes.save(note.copy(done = !note.done)) } }
    }

    fun delete(note: Note) {
        viewModelScope.launch {
            if (write { notes.delete(note.id) }) {
                _deleted.value = note
                undoTimer?.cancel()
                undoTimer = launch {
                    delay(UNDO_MS)
                    _deleted.value = null
                }
            }
        }
    }

    /** Puts the deleted card back with the same id, so it returns to the same place in the stack. */
    fun undoDelete() {
        val note = _deleted.value ?: return
        _deleted.value = null
        undoTimer?.cancel()
        viewModelScope.launch { write { notes.save(note) } }
    }

    fun dismissActionFailed() {
        _actionFailed.value = false
    }

    fun dismissLockedNotes() {
        _lockedNotes.value = false
        viewModelScope.launch {
            try {
                lockedNotice.dismiss()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Shown again next launch - harmless.
            }
        }
    }

    /** Runs a database write; a failure shows one line on screen instead of closing the app. */
    private suspend fun write(block: suspend () -> Unit): Boolean = try {
        block()
        _actionFailed.value = false
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _actionFailed.value = true
        false
    }

    private companion object {
        const val TICK_MS = 50L
        const val DECAY = 0.75f
        const val LISTENING_AGAIN_JOLT = 0.5f
        const val UNDO_MS = 10_000L
    }
}

/** Samples across the seismograph strip: 120 x 50 ms = the last 6 seconds. */
const val SEISMOGRAPH_SAMPLES = 120

/** Before saving an edited card: no empty list items, a title even when the user cleared it. */
internal fun Note.tidy(): Note {
    val cleanItems = items.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
    val cleanTitle = title.trim().ifEmpty { firstWords(body.ifBlank { cleanItems.firstOrNull()?.text.orEmpty() }) }
    return copy(title = cleanTitle, body = body.trim(), items = cleanItems, place = place?.trim()?.ifEmpty { null })
}

/** Up to ~6 words of the text, for a card that has no title. */
internal fun firstWords(text: String): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.take(6).joinToString(" ") + if (words.size > 6) "…" else ""
}
