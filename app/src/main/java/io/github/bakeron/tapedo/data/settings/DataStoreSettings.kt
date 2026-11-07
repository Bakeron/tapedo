package io.github.bakeron.tapedo.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.SettingsStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** A damaged settings file is replaced with defaults - otherwise every later save would fail too. */
internal val settingsCorruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }

private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = settingsCorruptionHandler,
)

/** Settings in Jetpack DataStore - small key-value file, read and written off the main thread. */
class DataStoreSettings(private val store: DataStore<Preferences>) : SettingsStore {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::read)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            recordingEnd = p[RECORDING_END].toEnum(d.recordingEnd),
            maxRecordingSeconds = p[MAX_SECONDS]?.takeIf { it in AppSettings.MAX_RECORDING_OPTIONS }
                ?: d.maxRecordingSeconds,
            silenceSeconds = p[SILENCE_SECONDS]?.takeIf { it in AppSettings.SILENCE_OPTIONS } ?: d.silenceSeconds,
            speechLanguage = p[SPEECH_LANGUAGE]?.takeIf { it in AppSettings.SPEECH_LANGUAGES } ?: d.speechLanguage,
            onlineSpeech = p[ONLINE_SPEECH].toEnum(d.onlineSpeech),
            // Anything that is not a type name (including "AUTO" or nothing) means "auto".
            noteTypeChoice = NoteType.entries.firstOrNull { it.name == p[NOTE_TYPE] },
            defaultPriority = p[PRIORITY].toEnum(d.defaultPriority),
            theme = p[THEME].toEnum(d.theme),
            reduceMotion = p[REDUCE_MOTION] ?: d.reduceMotion,
            aiProviders = p[AI_PROVIDERS]?.let { list -> list.split(',').mapNotNull(AiProvider::ofId).toSet() }
                // Before 0.5 there was one provider ("GEMINI" / "GROQ" / "NONE") - it stays turned on.
                ?: setOfNotNull(AiProvider.entries.firstOrNull { it.name == p[OLD_AI_PROVIDER] }),
            aiModels = AiProvider.entries.mapNotNull { pr ->
                p[modelKey(pr)]?.trim()?.take(AppSettings.MAX_MODEL_NAME)?.ifEmpty { null }?.let { pr to it }
            }.toMap(),
            aiDailyLimit = p[AI_DAILY_LIMIT]?.takeIf { it in AppSettings.AI_DAILY_LIMITS } ?: d.aiDailyLimit,
            textScale = p[TEXT_SCALE]?.takeIf { it in AppSettings.TEXT_SCALES } ?: d.textScale,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[RECORDING_END] = s.recordingEnd.name
        p[MAX_SECONDS] = s.maxRecordingSeconds
        p[SILENCE_SECONDS] = s.silenceSeconds
        p[SPEECH_LANGUAGE] = s.speechLanguage
        p[ONLINE_SPEECH] = s.onlineSpeech.name
        p[NOTE_TYPE] = s.noteTypeChoice?.name ?: AUTO
        p[PRIORITY] = s.defaultPriority.name
        p[THEME] = s.theme.name
        p[REDUCE_MOTION] = s.reduceMotion
        // Kept in the order they are asked, so the file reads the same as the screen.
        p[AI_PROVIDERS] = AiProvider.entries.filter { it in s.aiProviders }.joinToString(",") { it.id }
        p.remove(OLD_AI_PROVIDER)
        for (provider in AiProvider.entries) {
            val model = s.aiModels[provider]?.trim().orEmpty()
            if (model.isEmpty()) p.remove(modelKey(provider)) else p[modelKey(provider)] = model.take(AppSettings.MAX_MODEL_NAME)
        }
        p[AI_DAILY_LIMIT] = s.aiDailyLimit
        p[TEXT_SCALE] = s.textScale
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    private companion object {
        val RECORDING_END = stringPreferencesKey("recording_end")
        val MAX_SECONDS = intPreferencesKey("max_recording_seconds")
        val SILENCE_SECONDS = intPreferencesKey("silence_seconds")
        val SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
        val ONLINE_SPEECH = stringPreferencesKey("online_speech")
        // New key name: the old "default type" value must not turn into a manual choice.
        val NOTE_TYPE = stringPreferencesKey("note_type_choice")
        const val AUTO = "AUTO"
        val PRIORITY = stringPreferencesKey("default_priority")
        val THEME = stringPreferencesKey("theme")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        /** One provider before 0.5 - read once to keep the user's choice, then removed. */
        val OLD_AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_PROVIDERS = stringPreferencesKey("ai_providers")
        val AI_DAILY_LIMIT = intPreferencesKey("ai_daily_limit")
        fun modelKey(provider: AiProvider) = stringPreferencesKey("ai_model_${provider.id}")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
    }
}
