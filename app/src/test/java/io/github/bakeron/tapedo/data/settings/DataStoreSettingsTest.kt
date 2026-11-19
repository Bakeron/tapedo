package io.github.bakeron.tapedo.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.settings.AiMode
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.settings.RecordingEnd
import io.github.bakeron.tapedo.core.settings.ThemeMode
import io.github.bakeron.tapedo.core.settings.WhisperProvider
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The real DataStore on a temporary file - no Android needed. */
class DataStoreSettingsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private fun file() = File(folder.root, "settings.preferences_pb")

    // Okio storage: the default file storage cannot replace an existing file on a Windows test machine
    // (File.renameTo does not overwrite there); on Android and Linux both behave the same.
    private fun dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file().toOkioPath() },
        corruptionHandler = settingsCorruptionHandler,
        scope = scope,
    )

    @Test
    fun `every value of every setting is read back as written`() = runBlocking {
        val raw = dataStore()
        val settings = DataStoreSettings(raw)
        val d = AppSettings()
        val variants = RecordingEnd.entries.map { d.copy(recordingEnd = it) } +
            AppSettings.MAX_RECORDING_OPTIONS.map { d.copy(maxRecordingSeconds = it) } +
            AppSettings.SILENCE_OPTIONS.map { d.copy(silenceSeconds = it) } +
            AppSettings.PAUSE_OPTIONS.map { d.copy(pauseSeconds = it) } +
            AppSettings.SPEECH_LANGUAGES.map { d.copy(speechLanguage = it) } +
            OnlineSpeech.entries.map { d.copy(onlineSpeech = it) } +
            (NoteType.entries + null).map { d.copy(noteTypeChoice = it) } +
            ThemeMode.entries.map { d.copy(theme = it) } +
            listOf(true, false).map { d.copy(reduceMotion = it) } +
            AiProvider.entries.map { d.copy(aiProviders = setOf(it)) } +
            AiProvider.entries.map { d.copy(aiModels = mapOf(it to "model-${it.id}")) } +
            AppSettings.AI_DAILY_LIMITS.map { d.copy(aiDailyLimit = it) } +
            AppSettings.TEXT_SCALES.map { d.copy(textScale = it) } +
            (WhisperProvider.entries.filter { it.ai !in AiProvider.HIDDEN } + null).map { d.copy(whisper = it) } +
            AiMode.entries.map { d.copy(aiMode = it) } +
            listOf(1, 250, null).map { d.copy(claudeDayCents = it) } +
            listOf(true, false).map { d.copy(tiltedPaper = it) } +
            listOf(true, false).map { d.copy(firstStepsSeen = it) } +
            listOf(d.copy(claudeTopUpCents = 2050, claudeTopUpDay = 20_404L), d.copy(claudeTopUpCents = null, claudeTopUpDay = null)) +
            AppSettings(
                recordingEnd = RecordingEnd.STOP_BUTTON, maxRecordingSeconds = 600, silenceSeconds = 12,
                speechLanguage = "en-US",
                onlineSpeech = OnlineSpeech.NEVER, noteTypeChoice = NoteType.EVENT,
                theme = ThemeMode.DARK, reduceMotion = true, aiProviders = AiProvider.entries.toSet(), textScale = 1.3f,
            )
        for (wanted in variants) {
            settings.update { wanted }
            assertEquals(wanted, settings.settings.first())
        }
    }

    @Test
    fun `key names stay the same - a rename would silently reset the user's settings`() = runBlocking {
        val raw = dataStore()
        DataStoreSettings(raw).update { it }
        val names = raw.data.first().asMap().keys.map { it.name }.sorted()
        assertEquals(
            listOf(
                "ai_daily_limit", "ai_mode", "ai_providers", "claude_day_cents", "first_steps_seen", "max_recording_seconds", "note_type_choice", "online_speech",
                "pause_seconds", "recording_end", "reduce_motion", "silence_seconds", "speech_language", "text_scale", "theme",
                "tilted_paper", "whisper",
            ),
            names,
        )
    }

    @Test
    fun `before the free or paid choice, Claude alone turned on means paid Claude, and OpenAI's Whisper is off`() = runBlocking {
        val raw = dataStore()
        raw.edit { p ->
            p[stringPreferencesKey("ai_providers")] = "anthropic"
            p[stringPreferencesKey("whisper")] = "OPENAI"
        }
        val s = DataStoreSettings(raw).settings.first()
        assertEquals(AiMode.CLAUDE, s.aiMode)
        assertNull(s.whisper)
        assertEquals("the default daily limit is $1", 100, s.claudeDayCents)

        raw.edit { p -> p[stringPreferencesKey("ai_providers")] = "groq,anthropic" }
        assertEquals(AiMode.FREE, DataStoreSettings(raw).settings.first().aiMode)
    }

    @Test
    fun `values the app does not know fall back to defaults`() = runBlocking {
        val raw = dataStore()
        raw.edit { p ->
            for (key in listOf("recording_end", "online_speech", "note_type_choice", "default_priority", "theme", "ai_provider", "ai_providers")) {
                p[stringPreferencesKey(key)] = "FOO"
            }
            p[intPreferencesKey("max_recording_seconds")] = 45
            p[intPreferencesKey("silence_seconds")] = 31
            p[intPreferencesKey("pause_seconds")] = 11
            p[stringPreferencesKey("speech_language")] = "de-DE"
            p[floatPreferencesKey("text_scale")] = 2f
            p[intPreferencesKey("ai_daily_limit")] = 7
        }
        assertEquals(AppSettings(), DataStoreSettings(raw).settings.first())
    }

    @Test
    fun `auto type is stored as AUTO and read as no choice`() = runBlocking {
        val raw = dataStore()
        val settings = DataStoreSettings(raw)
        settings.update { it.copy(noteTypeChoice = NoteType.TASK) }
        settings.update { it.copy(noteTypeChoice = null) }
        assertEquals("AUTO", raw.data.first()[stringPreferencesKey("note_type_choice")])
        assertNull(settings.settings.first().noteTypeChoice)
    }

    @Test
    fun `a damaged settings file can still be read and written`() = runBlocking {
        file().writeBytes(Random(42).nextBytes(256))
        val settings = DataStoreSettings(dataStore())
        assertEquals(AppSettings(), settings.settings.first())

        settings.update { it.copy(theme = ThemeMode.DARK) }
        assertEquals(ThemeMode.DARK, settings.settings.first().theme)
    }

    @Test
    fun `the one provider chosen before 0_5 stays on, and the old key is removed`() = runBlocking {
        val raw = dataStore()
        raw.edit { it[stringPreferencesKey("ai_provider")] = "GROQ" }
        val settings = DataStoreSettings(raw)
        assertEquals(setOf(AiProvider.GROQ), settings.settings.first().aiProviders)

        settings.update { it }
        val prefs = raw.data.first()
        assertEquals("groq", prefs[stringPreferencesKey("ai_providers")])
        assertNull(prefs[stringPreferencesKey("ai_provider")])
        // "No AI" before 0.5 means nothing turned on.
        raw.edit { it.clear(); it[stringPreferencesKey("ai_provider")] = "NONE" }
        assertEquals(emptySet<AiProvider>(), settings.settings.first().aiProviders)
    }

    @Test
    fun `turning every provider off is remembered, not mistaken for the old setting`() = runBlocking {
        val raw = dataStore()
        raw.edit { it[stringPreferencesKey("ai_provider")] = "GEMINI" }
        val settings = DataStoreSettings(raw)
        settings.update { it.copy(aiProviders = emptySet()) }
        assertEquals(emptySet<AiProvider>(), settings.settings.first().aiProviders)
    }

    @Test
    fun `speech follows the phone's language until one is picked`() = runBlocking {
        val raw = dataStore()
        assertEquals("en-US", DataStoreSettings(raw, "de").settings.first().speechLanguage)
        assertEquals("pl-PL", DataStoreSettings(raw, "pl").settings.first().speechLanguage)
        // A picked (stored) language stays, whatever the phone says.
        DataStoreSettings(raw, "pl").update { it.copy(speechLanguage = "pl-PL") }
        assertEquals("pl-PL", DataStoreSettings(raw, "de").settings.first().speechLanguage)
    }

    @Test
    fun `a top-up out of range is not read`() = runBlocking {
        val raw = dataStore()
        raw.edit {
            it[intPreferencesKey("claude_top_up_cents")] = 0
            it[androidx.datastore.preferences.core.longPreferencesKey("claude_top_up_day")] = -3L
        }
        val s = DataStoreSettings(raw).settings.first()
        assertNull(s.claudeTopUpCents)
        assertNull(s.claudeTopUpDay)
    }
}
