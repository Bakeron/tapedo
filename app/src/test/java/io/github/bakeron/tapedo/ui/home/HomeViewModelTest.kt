package io.github.bakeron.tapedo.ui.home

import io.github.bakeron.tapedo.core.ai.AiAnswer
import io.github.bakeron.tapedo.core.ai.AiChat
import io.github.bakeron.tapedo.core.ai.AiException
import io.github.bakeron.tapedo.core.ai.Problem
import io.github.bakeron.tapedo.core.ai.SpeechToText
import io.github.bakeron.tapedo.core.ai.WhisperRetyper
import io.github.bakeron.tapedo.core.ai.AiGateway
import io.github.bakeron.tapedo.core.ai.MemoryAiUsage
import io.github.bakeron.tapedo.core.ai.NoteStructurer
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.data.NoteRepository
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.settings.AiMode
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.settings.RecordingEnd
import io.github.bakeron.tapedo.core.settings.SettingsStore
import io.github.bakeron.tapedo.core.settings.WhisperProvider
import io.github.bakeron.tapedo.core.speech.RecordedAudio
import io.github.bakeron.tapedo.core.speech.ListeningMode
import io.github.bakeron.tapedo.core.speech.SpeechError
import io.github.bakeron.tapedo.core.speech.SpeechEvent
import io.github.bakeron.tapedo.core.speech.SpeechInput
import io.github.bakeron.tapedo.data.LockedNotesNotice
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeStore(initial: AppSettings) : SettingsStore {
        val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
    }

    /** Records what each recording was allowed to do and replays [events]. */
    private class FakeSpeech(private val events: () -> Flow<SpeechEvent>) : SpeechInput {
        val allowOnline = mutableListOf<Boolean>()
        val skipped = mutableListOf<Long>()
        val keptAudio = mutableListOf<Boolean>()
        override fun listen(languageTag: String, allowOnline: Boolean, skipFirstMs: Long, keepAudio: Boolean, pauseMillis: Long?): Flow<SpeechEvent> {
            this.allowOnline += allowOnline
            skipped += skipFirstMs
            keptAudio += keepAudio
            return events()
        }
        var stops = 0
        override fun stop() {
            stops++
        }
        override suspend fun listeningMode() = ListeningMode.UNKNOWN
    }

    /** In-memory notes. Save number [failOnSave] throws once, like a full disk; [failOpen] breaks reading. */
    private class FakeNotes : NoteRepository {
        val rows = MutableStateFlow<Map<Long, Note>>(emptyMap())
        private var nextId = 1L
        private var saves = 0
        var failOnSave = 0
        var failOpen = false
        val saved = mutableListOf<String>()

        override fun observeAll(): Flow<List<Note>> = flow {
            if (failOpen) throw IOException("database could not be opened")
            emitAll(rows.map { m -> m.values.sortedByDescending { it.id } })
        }

        override suspend fun get(id: Long) = rows.value[id]

        override suspend fun save(note: Note): Long {
            saves++
            if (saves == failOnSave) throw IOException("disk full")
            val id = if (note.id == 0L) nextId++ else note.id
            rows.update { it + (id to note.copy(id = id)) }
            saved += note.title
            return id
        }

        override suspend fun delete(id: Long) = rows.update { it - id }
    }

    private class NoSecrets : SecretStore {
        override suspend fun apiKey(provider: AiProvider): String? = null
        override suspend fun setApiKey(provider: AiProvider, key: String?) = Unit
    }

    private class FakeNotice(var pending: Boolean = false) : LockedNotesNotice {
        override suspend fun pending() = pending
        override suspend fun dismiss() {
            pending = false
        }
    }

    private object NoGateway : AiGateway {
        override fun chat(provider: AiProvider, model: String, key: String) = AiChat { _, _, _ -> error("no AI in these tests") }
        override suspend fun freeModels(provider: AiProvider, key: String?) = emptyList<String>()
    }

    private val noOfflinePack = { flowOf<SpeechEvent>(SpeechEvent.Failed(SpeechError.OFFLINE_UNAVAILABLE)) }

    /** The screen collects settings and the stack while visible - without that, the view model reads none. */
    private fun TestScope.viewModel(
        store: FakeStore,
        speech: FakeSpeech = FakeSpeech { flowOf() },
        notes: FakeNotes = FakeNotes(),
        notice: FakeNotice = FakeNotice(),
        // Most tests time the recording itself - the "3, 2, 1" has its own test.
        countdownMillis: Long = 0,
        retyper: WhisperRetyper? = null,
        structurer: NoteStructurer = NoteStructurer(NoSecrets(), MemoryAiUsage(), NoGateway),
    ) = HomeViewModel(
        store, speech, notes,
        structurer,
        notice,
        nowMillis = { 1_000L },
        todayEpochDay = { 20_000L },
        timeSource = testScheduler.timeSource,
        countdownMillis = countdownMillis,
        retyper = retyper,
    ).also { vm ->
        backgroundScope.launch { vm.settings.collect { } }
        backgroundScope.launch { vm.stack.collect { } }
        runCurrent()
    }

    @Test
    fun `the recording limit stops listening exactly once`() = runTest(dispatcher) {
        // The recognizer keeps listening until the test closes it - like "stop button" mode.
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val speech = FakeSpeech { events.receiveAsFlow() }
        val vm = viewModel(FakeStore(AppSettings(recordingEnd = RecordingEnd.STOP_BUTTON, maxRecordingSeconds = 10)), speech)

        vm.startRecording()
        runCurrent()
        advanceTimeBy(9_900)
        runCurrent()
        assertEquals("no stop before the limit", 0, speech.stops)

        advanceTimeBy(200) // 10.1 s
        runCurrent()
        assertEquals(1, speech.stops)
        val active = vm.recording.value as RecordingState.Active
        assertEquals(10, active.elapsedSeconds)
        assertEquals(10, active.limitSeconds)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("the limit is hit once, not on every tick", 1, speech.stops)
        assertEquals(15, (vm.recording.value as RecordingState.Active).elapsedSeconds)

        events.close()
        runCurrent()
        assertEquals(RecordingState.Idle, vm.recording.value)
    }

    @Test
    fun `on silence the recording ends only after the set seconds without new words`() = runTest(dispatcher) {
        // The bug from 07.11.2026: a 2 s pause before each item ended the recording (and lost the items).
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val speech = FakeSpeech { events.receiveAsFlow() }
        val vm = viewModel(FakeStore(AppSettings(recordingEnd = RecordingEnd.SILENCE, silenceSeconds = 7)), speech)

        vm.startRecording()
        runCurrent()
        events.send(SpeechEvent.Partial("Lista zakupów"))
        for (item in listOf("mleko", "chleb", "jajka")) {
            advanceTimeBy(2_000) // a pause to think
            runCurrent()
            events.send(SpeechEvent.Partial("Lista zakupów, $item"))
            runCurrent()
        }
        advanceTimeBy(6_900)
        runCurrent()
        assertEquals("6.9 s of quiet is not the end yet", 0, speech.stops)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, speech.stops)
        events.close()
    }

    @Test
    fun `stop button mode never ends on silence`() = runTest(dispatcher) {
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val speech = FakeSpeech { events.receiveAsFlow() }
        val vm = viewModel(FakeStore(AppSettings(recordingEnd = RecordingEnd.STOP_BUTTON, silenceSeconds = 3)), speech)

        vm.startRecording()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, speech.stops)
        events.close()
    }

    @Test
    fun `the screen shows when the microphone is closed for a restart`() = runTest(dispatcher) {
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val vm = viewModel(FakeStore(AppSettings(recordingEnd = RecordingEnd.STOP_BUTTON)), FakeSpeech { events.receiveAsFlow() })

        vm.startRecording()
        runCurrent()
        // The bug from 07.11.2026: "listening" was shown before the microphone opened - first words lost.
        val starting = vm.recording.value as RecordingState.Active
        assertEquals("not listening until the recognizer is ready", false, starting.listening)
        assertEquals(false, starting.micOpened)
        events.send(SpeechEvent.Listening(true))
        runCurrent()
        assertTrue((vm.recording.value as RecordingState.Active).listening)
        events.send(SpeechEvent.Listening(false))
        runCurrent()
        val restarting = vm.recording.value as RecordingState.Active
        assertEquals(false, restarting.listening)
        assertTrue("a later gap is a restart, not the start", restarting.micOpened)
        events.send(SpeechEvent.Listening(true))
        runCurrent()
        assertTrue((vm.recording.value as RecordingState.Active).listening)
        events.close()
    }

    @Test
    fun `three two one, and the counter and the limit start only after it`() = runTest(dispatcher) {
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val speech = FakeSpeech { events.receiveAsFlow() }
        val vm = viewModel(
            FakeStore(AppSettings(recordingEnd = RecordingEnd.STOP_BUTTON, maxRecordingSeconds = 10)),
            speech,
            countdownMillis = 3_000,
        )

        vm.startRecording()
        runCurrent()
        assertEquals(3, (vm.recording.value as RecordingState.Active).countdown)
        assertEquals("the recognizer warms up during the countdown", 1, speech.allowOnline.size)
        assertEquals("what is said during the countdown is not used", listOf(3_000L), speech.skipped)
        advanceTimeBy(1_010)
        runCurrent()
        assertEquals(2, (vm.recording.value as RecordingState.Active).countdown)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, (vm.recording.value as RecordingState.Active).countdown)
        advanceTimeBy(1_000) // 3.01 s
        runCurrent()
        val recording = vm.recording.value as RecordingState.Active
        assertEquals(0, recording.countdown)
        assertEquals(0, recording.elapsedSeconds)

        advanceTimeBy(9_900) // 9.9 s of recording, 12.9 s since the tap
        runCurrent()
        assertEquals("the limit counts recording time, not the countdown", 0, speech.stops)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, speech.stops)
        events.close()
    }

    @Test
    fun `stop during the countdown cancels quietly - no message, nothing saved`() = runTest(dispatcher) {
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val speech = FakeSpeech { events.receiveAsFlow() }
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings()), speech, notes = notes, countdownMillis = 3_000)

        vm.startRecording()
        advanceTimeBy(1_010)
        runCurrent()
        vm.stopRecording()
        // What the phone's recognizer says when stopped this early: "nothing heard", or words from "3, 2, 1".
        events.trySend(SpeechEvent.Failed(SpeechError.NO_SPEECH))
        events.trySend(SpeechEvent.Done("trzy dwa"))
        runCurrent()
        assertEquals(RecordingState.Idle, vm.recording.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(RecordingState.Idle, vm.recording.value)
        assertTrue(notes.rows.value.isEmpty())
    }

    @Test
    fun `ask mode never goes online by itself, and a yes covers one recording`() = runTest(dispatcher) {
        val store = FakeStore(AppSettings(onlineSpeech = OnlineSpeech.ASK))
        val speech = FakeSpeech(noOfflinePack)
        val vm = viewModel(store, speech)

        vm.startRecording()
        runCurrent()
        assertEquals(RecordingState.Failed(SpeechError.OFFLINE_UNAVAILABLE), vm.recording.value)

        vm.startRecording(allowOnlineOnce = true)
        runCurrent()
        vm.startRecording()
        runCurrent()
        assertEquals(listOf(false, true, false), speech.allowOnline)
        assertEquals(OnlineSpeech.ASK, store.state.value.onlineSpeech)
    }

    @Test
    fun `always online is remembered for the next recordings`() = runTest(dispatcher) {
        val store = FakeStore(AppSettings(onlineSpeech = OnlineSpeech.ASK))
        val speech = FakeSpeech(noOfflinePack)
        val vm = viewModel(store, speech)

        vm.allowOnlineAlways()
        runCurrent()
        vm.startRecording()
        runCurrent()
        assertEquals(OnlineSpeech.ALWAYS, store.state.value.onlineSpeech)
        assertEquals(listOf(true, true), speech.allowOnline)
    }

    @Test
    fun `never mode does not go online`() = runTest(dispatcher) {
        val speech = FakeSpeech(noOfflinePack)
        val vm = viewModel(FakeStore(AppSettings(onlineSpeech = OnlineSpeech.NEVER)), speech)

        vm.startRecording()
        runCurrent()
        assertEquals(listOf(false), speech.allowOnline)
    }

    @Test
    fun `a crash in the recognizer becomes a message, not a closed app`() = runTest(dispatcher) {
        val speech = FakeSpeech { flow { throw IllegalStateException("recognizer died") } }
        val vm = viewModel(FakeStore(AppSettings()), speech)

        vm.startRecording()
        runCurrent()
        assertEquals(RecordingState.Failed(SpeechError.OTHER), vm.recording.value)
    }

    @Test
    fun `a refused own recording is told once, the recording goes on, and the error number reaches the report`() =
        runTest(dispatcher) {
            val events = Channel<SpeechEvent>(Channel.UNLIMITED)
            val vm = viewModel(FakeStore(AppSettings()), FakeSpeech { events.receiveAsFlow() })

            vm.startRecording()
            events.send(SpeechEvent.OwnAudioRefused(code = 5))
            runCurrent()
            assertEquals(5, vm.ownAudioRefused.value)
            assertTrue("still recording", vm.recording.value is RecordingState.Active)

            events.send(SpeechEvent.Failed(SpeechError.OTHER, code = 11))
            events.close() // the real recognizer ends its flow after Failed; open, the sampler would tick forever
            runCurrent()
            assertEquals(RecordingState.Failed(SpeechError.OTHER, code = 11), vm.recording.value)

            vm.dismissOwnAudioRefused()
            assertEquals(null, vm.ownAudioRefused.value)
        }

    @Test
    fun `a recording is saved the moment it ends, and Done only closes the window`() = runTest(dispatcher) {
        // A list left in the review window was lost when the phone closed the app in the background.
        val notes = FakeNotes()
        val speech = FakeSpeech { flowOf(SpeechEvent.Done("kupić mleko i chleb")) }
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.SHOPPING)), speech, notes)

        vm.startRecording()
        runCurrent()
        val review = vm.recording.value as RecordingState.Review
        assertEquals(NoteType.SHOPPING, review.cards.single().type)
        assertEquals(listOf(NoteType.SHOPPING), notes.rows.value.values.map { it.type })

        vm.saveReview()
        runCurrent()
        assertEquals(RecordingState.Idle, vm.recording.value)
        assertEquals(1, (vm.stack.value as StackState.Loaded).notes.size)
    }

    @Test
    fun `delete in the review window takes the saved recording back out`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.SHOPPING)), notes = notes)
        vm.onTranscript("Zakupy: jajka, pomidory oraz kawa.")
        runCurrent()
        assertEquals(listOf(NoteType.SHOPPING), notes.rows.value.values.map { it.type })

        vm.discardReview()
        runCurrent()
        assertTrue(notes.rows.value.isEmpty())
        assertEquals(RecordingState.Idle, vm.recording.value)

        // "Delete" on the card itself does the same.
        vm.onTranscript("Kawa i cukier")
        runCurrent()
        assertEquals(1, notes.rows.value.size)
        vm.removeCard(0)
        runCurrent()
        assertTrue(notes.rows.value.isEmpty())
        assertEquals(RecordingState.Idle, vm.recording.value)
    }

    @Test
    fun `with pauses off one recording is one list item`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.SHOPPING, pauseSeconds = 0)), notes = notes)
        vm.onTranscript("Zakupy: jajka, pomidory oraz kawa.")
        runCurrent()
        assertEquals(1, (vm.recording.value as RecordingState.Review).cards.single().items.size)
    }

    @Test
    fun `the open divider's kind is the recording's kind, and an old auto choice means notes`() = runTest(dispatcher) {
        val store = FakeStore(AppSettings())
        val vm = viewModel(store)
        vm.onTranscript("kupić mleko o 10")
        runCurrent()
        assertEquals(NoteType.NOTE, (vm.recording.value as RecordingState.Review).cards.single().type)
        vm.saveReview()

        vm.chooseType(NoteType.EVENT)
        runCurrent()
        vm.onTranscript("kupić mleko o 10")
        runCurrent()
        assertEquals(NoteType.EVENT, (vm.recording.value as RecordingState.Review).cards.single().type)
    }

    @Test
    fun `going to the background writes the card being edited`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.SHOPPING)), notes = notes)
        vm.onTranscript("kupić mleko")
        runCurrent()

        vm.editDraft(0)
        val draft = vm.editing.value!!.note
        vm.changeEditing(draft.copy(items = draft.items + ChecklistItem("Marchewki")))
        vm.onBackground()
        runCurrent()
        assertEquals(listOf("Mleko", "Marchewki"), notes.rows.value.values.single().items.map { it.text })
        assertTrue("the editor stays open", vm.editing.value != null)
    }

    @Test
    fun `a failed save keeps the unsaved cards, and trying again does not duplicate the saved ones`() = runTest(dispatcher) {
        val notes = FakeNotes().apply { failOnSave = 2 }
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.TASK)), notes = notes)
        vm.onTranscript("Jutro oddać raport szefowi.")
        runCurrent()
        vm.saveReview()
        runCurrent()

        // The second recording hits a full disk: its card stays on screen, marked, and is not lost.
        vm.onTranscript("Zadzwonić do hydraulika.")
        runCurrent()
        val after = vm.recording.value as RecordingState.Review
        assertTrue(after.saveFailed)
        assertEquals(1, notes.rows.value.size)

        vm.saveReview()
        runCurrent()
        assertEquals(RecordingState.Idle, vm.recording.value)
        assertEquals(2, notes.rows.value.size)
        // Newest first: the last recording is on top of the stack.
        assertEquals("Zadzwonić do hydraulika", (vm.stack.value as StackState.Loaded).notes.first().title)
    }

    private fun whisper(answer: suspend () -> String, key: String? = "gsk_test") = WhisperRetyper(
        object : SecretStore {
            override suspend fun apiKey(provider: AiProvider) = key
            override suspend fun setApiKey(provider: AiProvider, key: String?) = Unit
        },
        SpeechToText { _, _, _, _, _ -> answer() },
    )

    private val loud = RecordedAudio(ByteArray(32_000) { if (it % 2 == 1) 0x30 else 0 }, 16_000)

    @Test
    fun `with Whisper on, its text replaces the phone's and the audio is asked for`() = runTest(dispatcher) {
        val speech = FakeSpeech { flowOf(SpeechEvent.Done("mleko marchew", loud)) }
        val store = FakeStore(AppSettings(noteTypeChoice = NoteType.SHOPPING, whisper = WhisperProvider.GROQ))
        val vm = viewModel(store, speech, retyper = whisper({ "Mleko, marchew" }))

        vm.startRecording()
        runCurrent()
        val review = vm.recording.value as RecordingState.Review
        assertEquals("Mleko, marchew", review.transcript)
        assertEquals(listOf("Mleko", "Marchew"), review.cards.single().items.map { it.text })
        assertEquals(null, review.whisperProblem)
        assertEquals(listOf(true), speech.keptAudio)
    }

    @Test
    fun `Whisper failing keeps the phone's text and says why`() = runTest(dispatcher) {
        val speech = FakeSpeech { flowOf(SpeechEvent.Done("mleko marchew", loud)) }
        val store = FakeStore(AppSettings(noteTypeChoice = NoteType.NOTE, whisper = WhisperProvider.GROQ))
        val vm = viewModel(store, speech, retyper = whisper({ throw AiException(Problem.LIMIT, "rate limit") }))

        vm.startRecording()
        runCurrent()
        val review = vm.recording.value as RecordingState.Review
        assertEquals("mleko marchew", review.transcript)
        assertEquals(Problem.LIMIT, review.whisperProblem?.problem)
        assertEquals(AiProvider.GROQ, review.whisperProblem?.provider)
    }

    @Test
    fun `with Whisper off nothing is kept and nothing is sent`() = runTest(dispatcher) {
        var asked = 0
        val speech = FakeSpeech { flowOf(SpeechEvent.Done("mleko")) }
        val vm = viewModel(FakeStore(AppSettings()), speech, retyper = whisper({ asked++; "x" }))

        vm.startRecording()
        runCurrent()
        assertEquals("mleko", (vm.recording.value as RecordingState.Review).transcript)
        assertEquals(0, asked)
        assertEquals(listOf(false), speech.keptAudio)
    }

    @Test
    fun `nothing heard is a message, not an empty card`() = runTest(dispatcher) {
        val vm = viewModel(FakeStore(AppSettings()))
        vm.onTranscript("   ")
        assertEquals(RecordingState.Failed(SpeechError.NO_SPEECH), vm.recording.value)
    }

    @Test
    fun `an edited draft replaces its card, and a cleared title is filled from the text`() = runTest(dispatcher) {
        val vm = viewModel(FakeStore(AppSettings()))
        vm.onTranscript("zadzwonić do hydraulika")
        runCurrent()

        vm.editDraft(0)
        val draft = vm.editing.value!!.note
        vm.changeEditing(draft.copy(title = "  ", body = "Zadzwonić do hydraulika w sprawie kranu"))
        vm.finishEditing()
        runCurrent()
        val card = (vm.recording.value as RecordingState.Review).cards.single()
        assertEquals("Zadzwonić do hydraulika w sprawie kranu", card.title)
        assertEquals(null, vm.editing.value)
        assertEquals(card, (vm.stack.value as StackState.Loaded).notes.single())
    }

    @Test
    fun `undo puts a deleted card back with the same id`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings()), notes = notes)
        val id = notes.save(Note(title = "Dentysta", createdAtMillis = 1))
        runCurrent()

        vm.delete(notes.rows.value.getValue(id))
        runCurrent()
        assertTrue(notes.rows.value.isEmpty())
        assertEquals("Dentysta", vm.deleted.value?.title)

        vm.undoDelete()
        runCurrent()
        assertEquals(setOf(id), notes.rows.value.keys)
        assertEquals(null, vm.deleted.value)
    }

    @Test
    fun `a tick that moves a card to another divider says where it went`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings()), notes = notes)
        val id = notes.save(
            Note(title = "Lista", type = NoteType.CHECKLIST, items = listOf(ChecklistItem("Mleko", checked = true)), createdAtMillis = 1),
        )
        runCurrent()

        // Unticking the only item takes the list off "Done", back to "Lists".
        vm.toggleItem(notes.rows.value.getValue(id), 0)
        runCurrent()
        assertEquals(StackTab.CHECKLIST, vm.moved.value?.to)

        // A tick that leaves the card where it is says nothing new.
        vm.dismissMoved()
        val two = notes.save(
            Note(title = "Wyjazd", type = NoteType.CHECKLIST, items = listOf(ChecklistItem("A"), ChecklistItem("B")), createdAtMillis = 2),
        )
        runCurrent()
        vm.toggleItem(notes.rows.value.getValue(two), 0)
        runCurrent()
        assertEquals(null, vm.moved.value)

        // The strip goes away by itself after a while.
        val task = notes.save(Note(title = "Zadzwonić", type = NoteType.TASK, createdAtMillis = 3))
        vm.toggleDone(notes.rows.value.getValue(task))
        runCurrent()
        assertEquals(StackTab.DONE, vm.moved.value?.to)
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(null, vm.moved.value)
    }

    @Test
    fun `deleting from the card view closes it and offers undo`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings()), notes = notes)
        val id = notes.save(Note(title = "Zakupy", createdAtMillis = 1))
        runCurrent()

        vm.openCard(notes.rows.value.getValue(id))
        vm.deleteEditing()
        runCurrent()
        assertTrue(notes.rows.value.isEmpty())
        assertEquals("Zakupy", vm.deleted.value?.title)

        // Undo brings the card back to the stack, not onto the card view.
        vm.undoDelete()
        runCurrent()
        assertEquals(setOf(id), notes.rows.value.keys)
        assertEquals(null, vm.editing.value)
    }

    @Test
    fun `changes in the card view are saved by themselves, and back just closes`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings()), notes = notes)
        val id = notes.save(Note(title = "Lista", type = NoteType.CHECKLIST, items = listOf(ChecklistItem("Mleko")), createdAtMillis = 1))
        runCurrent()

        vm.openCard(notes.rows.value.getValue(id))
        val open = vm.editing.value!!.note
        vm.changeEditing(open.copy(items = open.items + ChecklistItem("Chleb")))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("Mleko", "Chleb"), notes.rows.value.getValue(id).items.map { it.text })
        assertTrue("still open", vm.editing.value != null)

        // Ticking every item moves the list to "Done" - told on the strip once the view is closed.
        val now = vm.editing.value!!.note
        vm.changeEditing(now.copy(items = now.items.map { it.copy(checked = true) }))
        vm.finishEditing()
        runCurrent()
        assertEquals(null, vm.editing.value)
        assertTrue(notes.rows.value.getValue(id).items.all { it.checked })
        assertEquals(StackTab.DONE, vm.moved.value?.to)
    }

    @Test
    fun `adding by voice to a card of the review window brings the window back with the new item`() = runTest(dispatcher) {
        val said = ArrayDeque(listOf("Lista zakupów, mleko", "jajka"))
        val speech = FakeSpeech { flowOf(SpeechEvent.Done(said.removeFirst())) }
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.SHOPPING)), speech, notes)

        vm.startRecording()
        runCurrent()
        vm.editDraft(0)
        val card = vm.editing.value!!.note
        vm.startRecording(appendTo = card.id)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("Mleko", "Jajka"), vm.editing.value!!.note.items.map { it.text })
        val review = vm.recording.value as RecordingState.Review
        assertEquals(listOf("Mleko", "Jajka"), review.cards.single().items.map { it.text })
        assertEquals(review.cards.single(), notes.rows.value.values.single())

        vm.finishEditing()
        runCurrent()
        assertTrue(vm.recording.value is RecordingState.Review)
    }

    @Test
    fun `with pauses off a recording added to a list is one item`() = runTest(dispatcher) {
        val speech = FakeSpeech { flowOf(SpeechEvent.Done("masło i jajka")) }
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings(pauseSeconds = 0)), speech, notes)
        val id = notes.save(Note(title = "Zakupy", type = NoteType.SHOPPING, items = listOf(ChecklistItem("Mleko")), createdAtMillis = 1))
        runCurrent()

        vm.openCard(notes.rows.value.getValue(id))
        vm.startRecording(appendTo = id)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, notes.rows.value.getValue(id).items.size)
    }

    @Test
    fun `record more adds the new items to the list and keeps the edits`() = runTest(dispatcher) {
        val said = ArrayDeque(listOf("Lista zakupów, mleko", "marchewki, jajka"))
        val speech = FakeSpeech { flowOf(SpeechEvent.Done(said.removeFirst())) }
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.SHOPPING)), speech, notes)

        vm.startRecording()
        runCurrent()
        vm.editDraft(0)
        vm.changeEditing(vm.editing.value!!.note.copy(title = "Sklep"))
        vm.finishEditing()

        vm.recordMore()
        runCurrent()
        val review = vm.recording.value as RecordingState.Review
        val list = review.cards.single()
        assertEquals("Sklep", list.title)
        assertEquals(listOf("Mleko", "Marchewki", "Jajka"), list.items.map { it.text })
        assertEquals("Lista zakupów, mleko, marchewki, jajka", review.transcript)
        assertEquals(null, review.moreFailed)
        assertEquals(list, notes.rows.value.values.single())
    }

    @Test
    fun `record more with no list cuts the dictation again and replaces the old cards`() = runTest(dispatcher) {
        val said = ArrayDeque(listOf("Zadzwonić do mamy", "i do taty"))
        val speech = FakeSpeech { flowOf(SpeechEvent.Done(said.removeFirst())) }
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.TASK)), speech, notes)

        vm.startRecording()
        runCurrent()
        val first = notes.rows.value.values.single()

        vm.recordMore()
        runCurrent()
        val task = notes.rows.value.values.single()
        assertTrue("the old card is replaced, not kept beside", task.id != first.id)
        assertEquals(NoteType.TASK, task.type)
        assertEquals("Zadzwonić do mamy, i do taty", (vm.recording.value as RecordingState.Review).transcript)
    }

    @Test
    fun `record more that hears nothing brings the same cards back`() = runTest(dispatcher) {
        var calls = 0
        val speech = FakeSpeech {
            calls++
            if (calls == 1) flowOf(SpeechEvent.Done("Zadzwonić do mamy w niedzielę")) else flowOf(SpeechEvent.Failed(SpeechError.NO_SPEECH))
        }
        val vm = viewModel(FakeStore(AppSettings()), speech)

        vm.startRecording()
        runCurrent()
        val before = (vm.recording.value as RecordingState.Review).cards

        vm.recordMore()
        runCurrent()
        val review = vm.recording.value as RecordingState.Review
        assertEquals(before, review.cards)
        assertEquals(SpeechError.NO_SPEECH, review.moreFailed)
    }

    @Test
    fun `ticking an item saves the card with only that item changed`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings(noteTypeChoice = NoteType.SHOPPING)), notes = notes)
        vm.onTranscript("kupić mleko, chleb i masło")
        runCurrent()
        vm.saveReview()
        runCurrent()
        val card = notes.rows.value.values.single()

        vm.toggleItem(card, 1)
        runCurrent()
        assertEquals(listOf(false, true, false), notes.rows.value.values.single().items.map { it.checked })
    }

    @Test
    fun `a database that cannot be opened shows try-again, and the retry reads it`() = runTest(dispatcher) {
        val notes = FakeNotes().apply { failOpen = true }
        val vm = viewModel(FakeStore(AppSettings()), notes = notes)
        assertEquals(StackState.Failed, vm.stack.value)

        notes.failOpen = false
        vm.retryStack()
        runCurrent()
        assertEquals(StackState.Loaded(emptyList()), vm.stack.value)
    }

    @Test
    fun `notes set aside after a lost key are announced once`() = runTest(dispatcher) {
        val notice = FakeNotice(pending = true)
        val vm = viewModel(FakeStore(AppSettings()), notice = notice)
        assertTrue(vm.lockedNotes.value)

        vm.dismissLockedNotes()
        runCurrent()
        assertEquals(false, vm.lockedNotes.value)
        assertEquals(false, notice.pending)
    }

    // --- "More exact" ---

    private class ClaudeKey : SecretStore {
        override suspend fun apiKey(provider: AiProvider): String? = if (provider == AiProvider.ANTHROPIC) "sk-ant-test" else null
        override suspend fun setApiKey(provider: AiProvider, key: String?) = Unit
    }

    /** Claude answers with two cards. */
    private object ClaudeCards : AiGateway {
        override fun chat(provider: AiProvider, model: String, key: String) = AiChat { _, _, _ ->
            AiAnswer("""{"cards":[{"title":"Mleko","type":"shopping","items":[{"text":"Mleko"}]},{"title":"Dentysta","type":"task"}]}""", model)
        }
        override suspend fun freeModels(provider: AiProvider, key: String?) = emptyList<String>()
    }

    private fun TestScope.claudeViewModel(notes: FakeNotes, mode: AiMode = AiMode.CLAUDE) = viewModel(
        FakeStore(AppSettings(aiMode = mode)), notes = notes,
        structurer = NoteStructurer(ClaudeKey(), MemoryAiUsage(), ClaudeCards, testScheduler.timeSource),
    )

    @Test
    fun `free AI chosen - no more exact, even with a Claude key`() = runTest(dispatcher) {
        val vm = claudeViewModel(FakeNotes(), AiMode.FREE)
        vm.onTranscript("kupić mleko")
        runCurrent()
        assertFalse((vm.recording.value as RecordingState.Review).claudeKey)
    }

    @Test
    fun `more exact offers Claude's cards and swaps them in only when taken`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = claudeViewModel(notes)
        vm.onTranscript("kupić mleko i umówić dentystę")
        runCurrent()
        val review = vm.recording.value as RecordingState.Review
        assertTrue("a Claude key is saved and the rules wrote the cards", review.claudeKey)
        assertEquals(null, review.by)
        val old = review.cards.map { it.id }

        vm.refine()
        runCurrent()
        val offer = (vm.recording.value as RecordingState.Review).refine as RecordingState.Refine.Offer
        // A type was picked for the recording, so Claude writes one card of it, as the free models do.
        assertEquals(listOf("Mleko"), offer.cards.map { it.title })
        assertEquals("nothing changes before the user picks", old.toSet(), notes.rows.value.keys)

        vm.acceptRefined()
        runCurrent()
        val after = vm.recording.value as RecordingState.Review
        assertEquals(listOf("Mleko"), after.cards.map { it.title })
        assertTrue(after.cards.single().byAi)
        assertEquals(AiProvider.ANTHROPIC, after.by)
        assertEquals(null, after.refine)
        assertEquals(setOf(after.cards.single().id), notes.rows.value.keys)
    }

    @Test
    fun `keep mine drops Claude's offer and the cards stay`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = claudeViewModel(notes)
        vm.onTranscript("kupić mleko")
        runCurrent()
        val cards = (vm.recording.value as RecordingState.Review).cards
        vm.refine()
        runCurrent()
        vm.keepCards()
        val review = vm.recording.value as RecordingState.Review
        assertEquals(null, review.refine)
        assertEquals(cards, review.cards)
        assertEquals(cards.map { it.id }.toSet(), notes.rows.value.keys)
    }

    @Test
    fun `without a Claude key there is no more exact`() = runTest(dispatcher) {
        val vm = viewModel(FakeStore(AppSettings()))
        vm.onTranscript("kupić mleko")
        runCurrent()
        assertFalse((vm.recording.value as RecordingState.Review).claudeKey)
    }

    @Test
    fun `a failed save of Claude's cards keeps the old ones`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = claudeViewModel(notes)
        vm.onTranscript("kupić mleko")
        runCurrent()
        val old = (vm.recording.value as RecordingState.Review).cards
        vm.refine()
        runCurrent()
        notes.failOnSave = 2 // save 1 was the recording's card; Claude's card is save 2 and fails
        vm.acceptRefined()
        runCurrent()
        val review = vm.recording.value as RecordingState.Review
        assertTrue(review.saveFailed)
        assertEquals(old, review.cards)
        assertTrue("the offer is still there to try again", review.refine is RecordingState.Refine.Offer)
        assertEquals(old.map { it.id }.toSet(), notes.rows.value.keys)
    }
}
