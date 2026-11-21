package io.github.bakeron.tapedo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bakeron.tapedo.core.ai.AiProblem
import io.github.bakeron.tapedo.core.ai.NoteStructurer
import io.github.bakeron.tapedo.core.ai.Problem
import io.github.bakeron.tapedo.core.ai.Refined
import io.github.bakeron.tapedo.core.ai.LowFunds
import io.github.bakeron.tapedo.core.ai.StructureResult
import io.github.bakeron.tapedo.core.ai.asDictation
import io.github.bakeron.tapedo.core.data.NoteRepository
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.AiMode
import io.github.bakeron.tapedo.core.settings.AiProvider
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
import io.github.bakeron.tapedo.ui.cards.isDone
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
        /**
         * True only while the microphone really listens: false until the recognizer is ready and while
         * the phone restarts it after a pause - the pen lifts, "one moment".
         */
        val listening: Boolean = false,
        /** The microphone has opened at least once - later gaps are restarts, not the start. */
        val micOpened: Boolean = false,
        /** Seconds left of the "3, 2, 1" before recording; 0 = recording. The recognizer warms up meanwhile. */
        val countdown: Int = 0,
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
        /** Claude wrote the cards, but less than 10% of the top-up is left. */
        val lowFunds: LowFunds? = null,
        /** The provider that wrote the cards; null = the rules. "More exact" is offered unless it was Claude. */
        val by: AiProvider? = null,
        /** The type picked for the recording - "More exact" asks for the same. */
        val type: NoteType? = null,
        /**
         * Which AI the button under rule-written cards asks: [AiMode.CLAUDE] ("More exact", paid Claude chosen and
         * its key saved - the only way Claude is asked) or [AiMode.FREE] ("Try AI again", a free provider is on but
         * failed - author 21.11.2026); null = no button.
         */
        val refineMode: AiMode? = null,
        val refine: Refine? = null,
    ) : RecordingState

    /** "More exact" / "Try AI again": the AI is writing the cards again, offers its cards, or could not. */
    sealed interface Refine {
        data object Working : Refine
        data class Offer(val cards: List<Note>, val by: AiProvider = AiProvider.ANTHROPIC) : Refine
        data class Failed(val problem: AiProblem) : Refine
    }

    /** @param code the recognizer's own error number, for the error report to the author. */
    data class Failed(val error: SpeechError, val code: Int? = null) : RecordingState
}

sealed interface StackState {
    data object Loading : StackState
    data class Loaded(val notes: List<Note>) : StackState

    /** The database could not be opened or read (disk, Keystore) - the screen offers "try again". */
    data object Failed : StackState
}

/**
 * "More exact" under a card of the stack that the AI did not write (author 21.11.2026: once free AI is on, older
 * cards are worth asking too). [mode] picks the AI as after a recording; [state] is the question and its answer.
 */
data class CardRefine(
    val mode: AiMode,
    val state: RecordingState.Refine? = null,
    /** Claude answered and less than 10% of the top-up is left. */
    val lowFunds: LowFunds? = null,
)

/** A tick moved [note] to the [to] divider - said on a strip, with a button to look there. */
data class Moved(val note: Note, val to: StackTab)

/** How long the undo / moved strip stays at the bottom of the stack, unless the phone asks for more. */
internal const val UNDO_MS = 10_000L

/**
 * What the card view ("Karteczka" - one card on the whole screen, written on directly) is open on.
 * Every change is saved by itself; there is no separate editor any more (author 13.11.2026).
 */
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
    /** "3, 2, 1" before each recording, so the user knows when to speak (the author's decision 07.11.2026). */
    private val countdownMillis: Long = COUNTDOWN_MS,
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

    /** The card open in the card view. Kept here while open, so typing never waits for the database. */
    private val _editing = MutableStateFlow<Editing?>(null)
    val editing: StateFlow<Editing?> = _editing.asStateFlow()

    /** The open card's changes are written a moment after the last one - not on every letter. */
    private var autosave: Job? = null

    /** Whether the open card counted as done when it was opened - a change is told on a strip after closing. */
    private var openedDone = false

    /** The review window put aside while the user adds by voice to one of its cards - it comes back after. */
    private var held: RecordingState.Review? = null

    /** "More exact" for the card open in the card view; null = no button (the AI wrote it, or no AI to ask). */
    private val _cardRefine = MutableStateFlow<CardRefine?>(null)
    val cardRefine: StateFlow<CardRefine?> = _cardRefine.asStateFlow()
    private var cardRefining: Job? = null

    /** Words said in the card view are being added to the card. */
    private val _appending = MutableStateFlow(false)
    val appending: StateFlow<Boolean> = _appending.asStateFlow()

    /** The last card deleted from the stack, while "undo" is still offered. */
    private val _deleted = MutableStateFlow<Note?>(null)
    val deleted: StateFlow<Note?> = _deleted.asStateFlow()

    /** The last card a tick moved to another divider, while the strip about it is shown. */
    private val _moved = MutableStateFlow<Moved?>(null)
    val moved: StateFlow<Moved?> = _moved.asStateFlow()

    /** A change to a saved card (tick, edit, delete) could not be written. */
    private val _actionFailed = MutableStateFlow(false)
    val actionFailed: StateFlow<Boolean> = _actionFailed.asStateFlow()

    /**
     * The phone's recognizer refused Tapedo's own recording (its error number), so recordings go the old way -
     * told once, with a button that mails the error to the app's author. Null: nothing to tell.
     */
    private val _ownAudioRefused = MutableStateFlow<Int?>(null)
    val ownAudioRefused: StateFlow<Int?> = _ownAudioRefused.asStateFlow()

    /** Old notes were set aside after Android lost the database key - told once. */
    private val _lockedNotes = MutableStateFlow(false)
    val lockedNotes: StateFlow<Boolean> = _lockedNotes.asStateFlow()

    private var session: Job? = null
    private var undoTimer: Job? = null

    /**
     * How long the undo / moved strip stays - longer when the phone asks for more time (a screen reader user
     * needs it to reach "Undo"). Set by the screen; the strip's time line runs for the same time.
     */
    var stripMs: Long = UNDO_MS
    private var movedTimer: Job? = null
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

    /** Remembers the open divider's type - the next recording becomes a card of it. */
    fun chooseType(type: NoteType) {
        viewModelScope.launch {
            try {
                store.update { it.copy(noteTypeChoice = type) }
            } catch (e: IOException) {
                // A failed write just leaves the old choice on screen - nothing is lost, so no message.
            }
        }
    }

    /** "First steps" gone through or skipped - not shown again until asked for in Settings. */
    fun finishFirstSteps() {
        viewModelScope.launch {
            try {
                store.update { it.copy(firstStepsSeen = true) }
            } catch (e: IOException) {
                // Not remembered: the tips come back on the next start - annoying, but nothing is lost.
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
        // Adding by voice to a card of the review window: the window waits aside and comes back after.
        val review = _recording.value as? RecordingState.Review
        if (appendTo != null && review != null && held == null && review.cards.any { it.id == appendTo }) {
            held = review
            _recording.value = RecordingState.Idle
        }
        // Cards still waiting for a decision are never replaced by a new recording.
        if (_recording.value is RecordingState.Review || _recording.value is RecordingState.Structuring) return
        val s = settings.value ?: AppSettings()
        val allowOnline = allowOnlineOnce || s.onlineSpeech == OnlineSpeech.ALWAYS
        val limit = s.maxRecordingSeconds
        // "On silence" is counted here, not by the recognizer - its own pause is far too short to think.
        val silence = if (s.recordingEnd == RecordingEnd.SILENCE) SilenceCountdown(s.silenceSeconds * 1000L) else null
        _recording.value = RecordingState.Active(0, limit, "", emptyList(), 0, countdown = countdownLeft(0))

        session = viewModelScope.launch {
            var level = 0f
            // Real elapsed time: delay() runs a little late each tick, so counting ticks drifts behind the clock.
            val started = timeSource.markNow()
            // The counter, the limit and "on silence" start after the countdown. The recognizer is started
            // at once, so it is ready by "go"; what is said during the countdown is not used.
            fun recordedMs() = (started.elapsedNow().inWholeMilliseconds - countdownMillis).coerceAtLeast(0)
            // Fixed-rate sampling: the pen keeps moving in silence, like paper under a seismograph.
            val sampler = launch {
                var limitHit = false
                while (true) {
                    delay(TICK_MS)
                    val elapsedMs = started.elapsedNow().inWholeMilliseconds
                    val recordedMs = recordedMs()
                    val sample = level
                    _recording.update { st ->
                        if (st !is RecordingState.Active) st
                        else st.copy(
                            elapsedSeconds = (recordedMs / 1000).toInt(),
                            levels = (st.levels + sample).takeLast(SEISMOGRAPH_SAMPLES),
                            sampleCount = st.sampleCount + 1,
                            countdown = countdownLeft(elapsedMs),
                        )
                    }
                    level *= DECAY // falls back between words
                    if (!limitHit && (recordedMs >= limit * 1000L || silence?.isOver(recordedMs) == true)) {
                        limitHit = true
                        speech.stop() // the limit or the silence ends the recording like the stop button would
                    }
                }
            }
            try {
                speech.listen(s.speechLanguage, allowOnline = allowOnline, skipFirstMs = countdownMillis, pauseMillis = s.pauseMillis).collect { e ->
                    when (e) {
                        is SpeechEvent.Level -> level = max(level, e.value)
                        is SpeechEvent.Partial -> {
                            silence?.heard(e.text, recordedMs())
                            _recording.update { if (it is RecordingState.Active) it.copy(text = e.text) else it }
                        }
                        is SpeechEvent.Listening -> {
                            // Back to listening: a short jolt of the pen shows "you can speak again".
                            if (e.on && !s.reduceMotion) level = max(level, LISTENING_AGAIN_JOLT)
                            _recording.update {
                                if (it is RecordingState.Active) it.copy(listening = e.on, micOpened = it.micOpened || e.on) else it
                            }
                        }
                        is SpeechEvent.Done -> when {
                            appendTo != null -> appendSpoken(appendTo, e.text)
                            continuing != null -> continueReview(e.text)
                            else -> onTranscript(e.text)
                        }
                        is SpeechEvent.Failed -> endRecording(e.error, e.code)
                        // The recording goes on; the note waits on the home screen until dismissed.
                        is SpeechEvent.OwnAudioRefused -> _ownAudioRefused.value = e.code
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

    /**
     * During the countdown "stop" cancels: nothing is kept and no "nothing heard" message - letting the
     * recognizer finish would report an empty recording (or, on phones without our own microphone, keep
     * words said during "3, 2, 1"). Afterwards it is a real stop that keeps what was said.
     */
    fun stopRecording() {
        val active = _recording.value as? RecordingState.Active
        if (active != null && active.countdown > 0) {
            session?.cancel()
            endRecording(null)
        } else {
            speech.stop()
        }
    }

    /**
     * Back to the review window when "record more" was listening; otherwise the error (or nothing) on the stack.
     * After adding by voice to a card of the review window, the window comes back (after the error is dismissed).
     */
    private fun endRecording(error: SpeechError?, code: Int? = null) {
        val review = continuing
        continuing = null
        _recording.value = when {
            review != null -> review.copy(moreFailed = error)
            error != null -> RecordingState.Failed(error, code)
            else -> held ?: RecordingState.Idle
        }
        if (error == null) held = null
    }

    /**
     * "Record more" in the review window: keeps listening for the same dictation, e.g. a shopping list
     * finished at the open fridge. The cards on screen are never lost - they come back even if nothing is heard.
     */
    fun recordMore(allowOnlineOnce: Boolean = false) {
        val review = _recording.value as? RecordingState.Review ?: return
        if (saving || session?.isActive == true) return
        keepCards()
        continuing = review.copy(moreFailed = null, refine = null)
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
            val (added, result) = spokenItems(spoken, review.cards[listIndex].type)
            val cards = review.cards.mapIndexed { i, card -> if (i == listIndex) card.copy(items = card.items + added) else card }
            _recording.value = persist(
                review.copy(
                    transcript = transcript,
                    cards = cards,
                    aiProblems = result?.rulesProblems() ?: review.aiProblems,
                    lowFunds = result?.lowFunds ?: review.lowFunds,
                    // Claude's cards with items added by someone else are worth asking Claude again.
                    by = review.by?.takeIf { it == result?.by },
                ),
            )
        }
    }

    private suspend fun hasClaudeKey(): Boolean = try {
        structurer.hasKey(AiProvider.ANTHROPIC)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private var refining: Job? = null

    /**
     * "More exact" (the author's decision 11.11.2026): the whole dictation once more to Claude Sonnet; in free mode
     * "Try AI again" to the free providers. The cards on screen stay saved until the user takes the new ones -
     * they may be worse, and the AI may not answer at all.
     */
    fun refine() {
        val review = _recording.value as? RecordingState.Review ?: return
        if (saving || refining?.isActive == true || review.refine is RecordingState.Refine.Offer) return
        _recording.value = review.copy(refine = RecordingState.Refine.Working)
        val s = settings.value ?: AppSettings()
        refining = viewModelScope.launch {
            val refined = askRefine(review.transcript, s, review.type)
            _recording.update { st ->
                // The window may have closed or moved on ("record more") meanwhile - then the answer is not offered.
                if (st !is RecordingState.Review || st.refine != RecordingState.Refine.Working) return@update st
                st.copy(refine = refined.asRefine(s), lowFunds = refined.lowFunds ?: st.lowFunds)
            }
        }
    }

    /** One "More exact" / "Try AI again" question; anything unexpected is a failure, never a crash. */
    private suspend fun askRefine(text: String, s: AppSettings, type: NoteType?): Refined = try {
        structurer.refine(text, s, type, todayEpochDay(), nowMillis())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val asked = if (s.aiMode == AiMode.FREE) AiProblem(AiProvider.FREE.first(), s.modelFor(AiProvider.FREE.first()), Problem.OTHER)
        else AiProblem(AiProvider.ANTHROPIC, NoteStructurer.REFINE_MODEL, Problem.OTHER)
        Refined.Failed(asked)
    }

    private fun Refined.asRefine(s: AppSettings): RecordingState.Refine = when (this) {
        is Refined.Cards -> if (notes.isEmpty()) RecordingState.Refine.Failed(
            AiProblem(by, if (by == AiProvider.ANTHROPIC) NoteStructurer.REFINE_MODEL else s.modelFor(by), Problem.ANSWER),
        ) else RecordingState.Refine.Offer(notes, by)
        is Refined.Failed -> RecordingState.Refine.Failed(problem)
    }

    private val Refined.lowFunds: LowFunds?
        get() = when (this) {
            is Refined.Cards -> lowFunds
            is Refined.Failed -> lowFunds
        }

    /** Which AI "More exact" asks: paid Claude with its key saved, or the free providers that are on; null = none. */
    private suspend fun refineMode(s: AppSettings): AiMode? = when {
        s.aiMode == AiMode.CLAUDE && hasClaudeKey() -> AiMode.CLAUDE
        s.autoProviders.isNotEmpty() -> AiMode.FREE
        else -> null
    }

    /** "Keep these" (or back): the AI's offer goes, the cards on screen stay; a running question is dropped. */
    fun keepCards() {
        refining?.cancel()
        _recording.update { if (it is RecordingState.Review && it.refine != null) it.copy(refine = null) else it }
    }

    /** "Use these": the AI's cards are written first, and only then the old ones go - nothing is lost on a failed save. */
    fun acceptRefined() {
        val review = _recording.value as? RecordingState.Review ?: return
        val offer = review.refine as? RecordingState.Refine.Offer ?: return
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                val saved = persist(
                    review.copy(cards = offer.cards, aiProblems = emptyList(), by = offer.by, refine = null, moreFailed = null),
                )
                if (saved.saveFailed) {
                    // The old cards are still saved: keep them, offer Claude's again; written ones go back out.
                    saved.cards.filter { it.id != 0L }.forEach { card -> write { notes.delete(card.id) } }
                    _recording.value = review.copy(saveFailed = true)
                } else {
                    _recording.value = saved
                    review.cards.filter { it.id != 0L }.forEach { old -> write { notes.delete(old.id) } }
                }
            } finally {
                saving = false
            }
        }
    }

    /** Items said for a list of [type], split and grouped like a new list (rules or the chosen AI); never empty. */
    private suspend fun spokenItems(spoken: String, type: NoteType): Pair<List<ChecklistItem>, StructureResult?> {
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
        val group = type.fallbackGroup()
        val items = result?.notes?.flatMap { it.items }.orEmpty().ifEmpty { listOf(ChecklistItem(spoken, group = group)) }
        return (if (s.pauseMillis == null) items.asOneItem(group) else items) to result
    }

    /** The user refused the microphone permission. */
    fun micDenied() {
        _recording.value = RecordingState.Failed(SpeechError.NO_PERMISSION)
    }

    /** Closes the error message; the stack (or the review window put aside for adding by voice) shows again. */
    fun dismissError() {
        if (_recording.value is RecordingState.Failed) {
            _recording.value = held ?: RecordingState.Idle
            held = null
        }
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
            // No "auto" on the home screen any more: an old "auto" choice opens the notes divider.
            val type = s.noteTypeChoice ?: NoteType.NOTE
            val result = try {
                structurer.structure(
                    transcript = transcript,
                    settings = s,
                    chosenType = type,
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
                ?.map { card -> if (s.pauseMillis == null) card.copy(items = card.items.asOneItem(card.type.fallbackGroup())) else card }
                ?: listOf(
                    Note(
                        title = firstWords(transcript),
                        body = transcript,
                        // Like any new card: the open divider's kind (a list without items stays a note), low priority.
                        type = if (type.hasItems) NoteType.NOTE else type,
                        priority = AppSettings.NEW_CARD_PRIORITY,
                        createdAtMillis = now,
                    ),
                )
            val review = persist(
                RecordingState.Review(
                    transcript, cards, result?.rulesProblems().orEmpty(),
                    lowFunds = result?.lowFunds,
                    by = result?.by,
                    type = type,
                    refineMode = refineMode(s),
                ),
            )
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
        autosave?.cancel()
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
     * Adds what was said in the card view to the card: to a list as new items (split and grouped like a
     * new list of that type - by the rules or the chosen AI), to any other card as a new line of text.
     * The words go onto the card as it is at the end - what the user typed meanwhile stays.
     */
    private fun appendSpoken(id: Long, text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) {
            _recording.value = RecordingState.Failed(SpeechError.NO_SPEECH)
            return
        }
        _recording.value = held ?: RecordingState.Idle
        held = null
        _appending.value = true
        viewModelScope.launch {
            try {
                fun current(): Note? = _editing.value?.note?.takeIf { it.id == id }
                val type = (current() ?: notes.get(id))?.type ?: return@launch
                val added = if (type.hasItems) spokenItems(spoken, type).first else emptyList()
                val open = current()
                val note = open ?: notes.get(id) ?: return@launch
                val updated = when {
                    added.isNotEmpty() && note.type.hasItems -> note.copy(items = note.items + added)
                    note.type.hasItems -> note.copy(items = note.items + ChecklistItem(spoken, group = note.type.fallbackGroup()))
                    else -> note.copy(body = listOf(note.body.trim(), spoken).filter { it.isNotEmpty() }.joinToString("\n"))
                }
                if (open != null) {
                    autosave?.cancel()
                    show(updated)
                }
                updateReviewCard(updated)
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

    // --- Review window ---

    /** "Done" (or back): the cards are already saved - closes the window; a failed save is tried again first. */
    fun saveReview() {
        val review = _recording.value as? RecordingState.Review ?: return
        if (saving) return
        // Closing the window drops Claude's offer (the cards on screen are the ones kept).
        refining?.cancel()
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
        refining?.cancel()
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

    // --- Card view ---

    /** A card of the review window, in the card view. */
    fun editDraft(index: Int) {
        val review = _recording.value as? RecordingState.Review ?: return
        review.cards.getOrNull(index)?.let { _editing.value = Editing.Draft(index, it) }
    }

    /** A card of the stack, in the card view. */
    fun openCard(note: Note) {
        openedDone = note.isDone()
        _editing.value = Editing.Saved(note)
        closeCardRefine()
        if (note.byAi) return
        cardRefining = viewModelScope.launch {
            val mode = refineMode(settings.value ?: AppSettings()) ?: return@launch
            if (_editing.value?.note?.id == note.id) _cardRefine.value = CardRefine(mode)
        }
    }

    /**
     * "More exact" in the card view: the card's words (title, text, items, date, hour, place) to the AI. Nothing
     * changes until the user takes the answer - the AI may make it worse (author 21.11.2026).
     */
    fun refineCard() {
        val open = _editing.value as? Editing.Saved ?: return
        val asking = _cardRefine.value ?: return
        if (cardRefining?.isActive == true || asking.state is RecordingState.Refine.Offer) return
        _cardRefine.value = asking.copy(state = RecordingState.Refine.Working)
        val s = settings.value ?: AppSettings()
        val id = open.note.id
        cardRefining = viewModelScope.launch {
            // No type: the AI may see a list in a note, or two things in one card.
            val refined = askRefine(open.note.tidy().asDictation(), s, null)
            if (_editing.value?.note?.id != id) return@launch
            _cardRefine.update { it?.copy(state = refined.asRefine(s), lowFunds = refined.lowFunds ?: it.lowFunds) }
        }
    }

    /** "Keep mine": the AI's offer goes, the card stays as it is. */
    fun keepCard() {
        cardRefining?.cancel()
        _cardRefine.update { it?.copy(state = null) }
    }

    /**
     * "Use this / these": one card takes the AI's version in place (same id, date of writing and priority);
     * several are written first, and only then the old card goes and the view closes onto the stack.
     */
    fun acceptCardRefine() {
        val open = _editing.value as? Editing.Saved ?: return
        val offer = _cardRefine.value?.state as? RecordingState.Refine.Offer ?: return
        if (saving) return
        saving = true
        autosave?.cancel()
        val old = open.note
        viewModelScope.launch {
            try {
                if (offer.cards.size == 1) {
                    val card = offer.cards.single().copy(id = old.id, createdAtMillis = old.createdAtMillis, priority = old.priority)
                    // A failed write keeps the old card and the offer - "Use this" can be tapped again.
                    if (!write { notes.save(card) }) return@launch
                    show(card)
                    // The AI wrote it now - no button any more.
                    closeCardRefine()
                    return@launch
                }
                val written = mutableListOf<Long>()
                val saved = write { for (card in offer.cards.reversed()) written += notes.save(card) }
                if (!saved) {
                    // Half a set is worse than none: what got written goes back out, the old card stays.
                    written.forEach { id -> write { notes.delete(id) } }
                    _actionFailed.value = true
                    return@launch
                }
                write { notes.delete(old.id) }
                _editing.value = null
                closeCardRefine()
            } finally {
                saving = false
            }
        }
    }

    private fun closeCardRefine() {
        cardRefining?.cancel()
        _cardRefine.value = null
    }

    /** Any change in the card view: on screen at once, in the database a moment later (and on closing). */
    fun changeEditing(note: Note) {
        if (_editing.value == null) return
        show(note)
        updateReviewCard(note)
        autosave?.cancel()
        if (note.id != 0L) {
            autosave = viewModelScope.launch {
                delay(AUTOSAVE_MS)
                write { notes.save(note) }
            }
        }
    }

    private fun show(note: Note) {
        _editing.update {
            when (it) {
                is Editing.Draft -> it.copy(note = note)
                is Editing.Saved -> it.copy(note = note)
                null -> null
            }
        }
    }

    /** The review window shows the card as it is now - "record more" and "more exact" work on it. */
    private fun updateReviewCard(note: Note) {
        val draft = (_editing.value as? Editing.Draft)?.index
        fun inReview(r: RecordingState.Review): RecordingState.Review {
            val i = draft?.takeIf { it in r.cards.indices && r.cards[it].id == note.id }
                ?: r.cards.indexOfFirst { it.id == note.id && it.id != 0L }.takeIf { it >= 0 }
                ?: return r
            return r.copy(cards = r.cards.mapIndexed { j, c -> if (j == i) note else c })
        }
        held = held?.let(::inReview)
        _recording.update { if (it is RecordingState.Review) inReview(it) else it }
    }

    /**
     * Back: closes the card view - its changes are already kept. Words still being said end and are still
     * added. A card from the stack stays open if the last write fails, so nothing typed is lost.
     */
    fun finishEditing() {
        val e = _editing.value ?: return
        autosave?.cancel()
        if (_recording.value is RecordingState.Active) speech.stop()
        val card = e.note.tidy()
        when (e) {
            is Editing.Draft -> {
                updateReviewCard(card)
                _editing.value = null
                closeCardRefine()
                dismissError()
                // Already in the database - the edit goes there too (a card not saved yet waits for "try again").
                if (card.id != 0L) viewModelScope.launch { write { notes.save(card) } }
            }
            is Editing.Saved -> viewModelScope.launch {
                if (!write { notes.save(card) }) return@launch
                _editing.value = null
                closeCardRefine()
                dismissError()
                if (card.isDone() != openedDone) showMoved(card)
            }
        }
    }

    /** "Delete this card": back to the stack (or the review window), where undo is offered as before. */
    fun deleteEditing() {
        val e = _editing.value ?: return
        autosave?.cancel()
        // A recording into this card is dropped - there is nothing left to add it to.
        if (_recording.value is RecordingState.Active) {
            session?.cancel()
            _recording.value = RecordingState.Idle
        }
        _editing.value = null
        closeCardRefine()
        dismissError()
        held?.let { _recording.value = it }
        held = null
        when (e) {
            is Editing.Draft -> removeCard(e.index)
            is Editing.Saved -> delete(e.note)
        }
    }

    // --- Stack ---

    fun retryStack() {
        reload.update { it + 1 }
    }

    fun toggleItem(note: Note, index: Int) {
        val items = note.items.mapIndexed { i, item -> if (i == index) item.copy(checked = !item.checked) else item }
        saveTick(note, note.copy(items = items))
    }

    fun toggleDone(note: Note) {
        saveTick(note, note.copy(done = !note.done))
    }

    /**
     * A tick that moves the card to "Done" or back to its own divider is said on a strip at the bottom
     * (author 10.11.2026: an unticked list vanished from "Done" without a word). A tick in the card view is
     * told when the view closes, so the strip is there when the card box is in sight again.
     */
    private fun saveTick(before: Note, after: Note) {
        viewModelScope.launch {
            if (!write { notes.save(after) } || before.isDone() == after.isDone()) return@launch
            showMoved(after)
        }
    }

    private fun showMoved(note: Note) {
        _moved.value = Moved(note, if (note.isDone()) StackTab.DONE else StackTab.of(note.type))
        movedTimer?.cancel()
        movedTimer = viewModelScope.launch {
            delay(stripMs)
            _moved.value = null
        }
    }

    fun dismissMoved() {
        movedTimer?.cancel()
        _moved.value = null
    }

    fun delete(note: Note) {
        viewModelScope.launch {
            if (write { notes.delete(note.id) }) {
                _deleted.value = note
                undoTimer?.cancel()
                undoTimer = launch {
                    delay(stripMs)
                    _deleted.value = null
                }
            }
        }
    }

    /** The strip's "x": the strip goes, the card stays deleted. */
    fun dismissDeleted() {
        undoTimer?.cancel()
        _deleted.value = null
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

    fun dismissOwnAudioRefused() {
        _ownAudioRefused.value = null
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

    /** Whole seconds of the countdown still to go: 3, 2, 1, then 0. */
    private fun countdownLeft(elapsedMs: Long): Int = ((countdownMillis - elapsedMs + 999) / 1000).coerceAtLeast(0).toInt()

    private companion object {
        const val COUNTDOWN_MS = 3_000L
        const val AUTOSAVE_MS = 600L
        const val TICK_MS = 50L
        const val DECAY = 0.75f
        const val LISTENING_AGAIN_JOLT = 0.5f
    }
}

/** Samples across the seismograph strip: 120 x 50 ms = the last 6 seconds. */
const val SEISMOGRAPH_SAMPLES = 120

/**
 * Pauses make no new items (Settings, 0 s): what one recording gave is one item - the rules or the AI may
 * still have cut it at "and" or a comma. Its shop section stays when all parts agree.
 */
internal fun List<ChecklistItem>.asOneItem(fallbackGroup: ShoppingGroup?): List<ChecklistItem> {
    if (size < 2) return this
    val group = map { it.group }.distinct().singleOrNull() ?: fallbackGroup
    return listOf(ChecklistItem(joinToString(", ") { it.text.trim() }, group = group))
}

private fun NoteType.fallbackGroup(): ShoppingGroup? = if (this == NoteType.SHOPPING) ShoppingGroup.OTHER else null

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
