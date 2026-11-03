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
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.settings.RecordingEnd
import io.github.bakeron.tapedo.core.settings.ThemeMode
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
            AppSettings.SPEECH_LANGUAGES.map { d.copy(speechLanguage = it) } +
            OnlineSpeech.entries.map { d.copy(onlineSpeech = it) } +
            (NoteType.entries + null).map { d.copy(noteTypeChoice = it) } +
            Priority.entries.map { d.copy(defaultPriority = it) } +
            ThemeMode.entries.map { d.copy(theme = it) } +
            listOf(true, false).map { d.copy(reduceMotion = it) } +
            AiProvider.entries.map { d.copy(aiProvider = it) } +
            AppSettings.TEXT_SCALES.map { d.copy(textScale = it) } +
            AppSettings(
                recordingEnd = RecordingEnd.STOP_BUTTON, maxRecordingSeconds = 600, speechLanguage = "en-US",
                onlineSpeech = OnlineSpeech.NEVER, noteTypeChoice = NoteType.EVENT, defaultPriority = Priority.LOW,
                theme = ThemeMode.DARK, reduceMotion = true, aiProvider = AiProvider.GROQ, textScale = 1.3f,
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
                "ai_provider", "default_priority", "max_recording_seconds", "note_type_choice", "online_speech",
                "recording_end", "reduce_motion", "speech_language", "text_scale", "theme",
            ),
            names,
        )
    }

    @Test
    fun `values the app does not know fall back to defaults`() = runBlocking {
        val raw = dataStore()
        raw.edit { p ->
            for (key in listOf("recording_end", "online_speech", "note_type_choice", "default_priority", "theme", "ai_provider")) {
                p[stringPreferencesKey(key)] = "FOO"
            }
            p[intPreferencesKey("max_recording_seconds")] = 45
            p[stringPreferencesKey("speech_language")] = "de-DE"
            p[floatPreferencesKey("text_scale")] = 2f
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
}
