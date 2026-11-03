package io.github.bakeron.tapedo.ui.home

import io.github.bakeron.tapedo.core.ai.NoteStructurer
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.data.NoteRepository
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.settings.RecordingEnd
import io.github.bakeron.tapedo.core.settings.SettingsStore
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
        override fun listen(languageTag: String, untilStopped: Boolean, allowOnline: Boolean): Flow<SpeechEvent> {
            this.allowOnline += allowOnline
            return events()
        }
        var stops = 0
        override fun stop() {
            stops++
        }
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

    private val noOfflinePack = { flowOf<SpeechEvent>(SpeechEvent.Failed(SpeechError.OFFLINE_UNAVAILABLE)) }

    /** The screen collects settings and the stack while visible - without that, the view model reads none. */
    private fun TestScope.viewModel(
        store: FakeStore,
        speech: FakeSpeech = FakeSpeech { flowOf() },
        notes: FakeNotes = FakeNotes(),
        notice: FakeNotice = FakeNotice(),
    ) = HomeViewModel(
        store, speech, notes,
        NoteStructurer(NoSecrets()) { _, _ -> null },
        notice,
        nowMillis = { 1_000L },
        todayEpochDay = { 20_000L },
        timeSource = testScheduler.timeSource,
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
    fun `speech goes to the review window and nothing is stored before the user decides`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val speech = FakeSpeech { flowOf(SpeechEvent.Done("kupić mleko i chleb")) }
        val vm = viewModel(FakeStore(AppSettings()), speech, notes)

        vm.startRecording()
        runCurrent()
        val review = vm.recording.value as RecordingState.Review
        assertEquals(NoteType.SHOPPING, review.cards.single().type)
        assertTrue(notes.rows.value.isEmpty())

        vm.saveReview()
        runCurrent()
        assertEquals(RecordingState.Idle, vm.recording.value)
        assertEquals(1, (vm.stack.value as StackState.Loaded).notes.size)
    }

    @Test
    fun `a failed save keeps the unsaved cards, and trying again does not duplicate the saved ones`() = runTest(dispatcher) {
        val notes = FakeNotes().apply { failOnSave = 2 }
        val vm = viewModel(FakeStore(AppSettings()), notes = notes)
        vm.onTranscript("Jutro oddać raport szefowi. Zakupy: jajka, pomidory oraz kawa.")
        runCurrent()
        assertEquals(listOf(NoteType.TASK, NoteType.SHOPPING), (vm.recording.value as RecordingState.Review).cards.map { it.type })

        // The last card is saved first (so the first lands on top); then the disk fills up.
        vm.saveReview()
        runCurrent()
        val after = vm.recording.value as RecordingState.Review
        assertTrue(after.saveFailed)
        assertEquals(listOf(NoteType.TASK), after.cards.map { it.type })

        vm.saveReview()
        runCurrent()
        assertEquals(RecordingState.Idle, vm.recording.value)
        assertEquals(listOf("Zakupy", "Jutro oddać raport szefowi"), notes.saved)
        // Newest first: the first card of the recording is on top of the stack.
        assertEquals("Jutro oddać raport szefowi", (vm.stack.value as StackState.Loaded).notes.first().title)
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
        val card = (vm.recording.value as RecordingState.Review).cards.single()
        assertEquals("Zadzwonić do hydraulika w sprawie kranu", card.title)
        assertEquals(null, vm.editing.value)
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
    fun `deleting from the preview closes it and offers undo`() = runTest(dispatcher) {
        val notes = FakeNotes()
        val vm = viewModel(FakeStore(AppSettings()), notes = notes)
        val id = notes.save(Note(title = "Zakupy", createdAtMillis = 1))
        runCurrent()
        val note = notes.rows.value.getValue(id)

        vm.openPreview(note)
        vm.deleteFromPreview(note)
        runCurrent()
        assertTrue(notes.rows.value.isEmpty())
        assertEquals("Zakupy", vm.deleted.value?.title)

        // Undo brings the card back to the stack, not onto a preview screen.
        vm.undoDelete()
        runCurrent()
        assertEquals(setOf(id), notes.rows.value.keys)
        val preview = backgroundScope.launch { vm.preview.collect {} }
        runCurrent()
        assertEquals(null, vm.preview.value)
        preview.cancel()
    }

    @Test
    fun `record more adds the new items to the list and keeps the edits`() = runTest(dispatcher) {
        val said = ArrayDeque(listOf("Lista zakupów, mleko", "marchewki, jajka"))
        val speech = FakeSpeech { flowOf(SpeechEvent.Done(said.removeFirst())) }
        val vm = viewModel(FakeStore(AppSettings()), speech)

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
        val vm = viewModel(FakeStore(AppSettings()), notes = notes)
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
}
