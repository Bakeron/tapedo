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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.bakeron.tapedo.core.ai.TopUpInput
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.settings.AiMode
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

/**
 * Settings in Jetpack DataStore - small key-value file, read and written off the main thread.
 * @param phoneLanguage the phone's language: speech language until the user picks one.
 */
class DataStoreSettings(private val store: DataStore<Preferences>, private val phoneLanguage: String = "pl") : SettingsStore {

    constructor(context: Context, phoneLanguage: String) : this(context.applicationContext.settingsDataStore, phoneLanguage)

    override val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::read)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        val aiProviders = p[AI_PROVIDERS]?.let { list -> list.split(',').mapNotNull(AiProvider::ofId).toSet() }
            // Before 0.5 there was one provider ("GEMINI" / "GROQ" / "NONE") - it stays turned on.
            ?: setOfNotNull(AiProvider.entries.firstOrNull { it.name == p[OLD_AI_PROVIDER] })
        return AppSettings(
            recordingEnd = p[RECORDING_END].toEnum(d.recordingEnd),
            maxRecordingSeconds = p[MAX_SECONDS]?.takeIf { it in AppSettings.MAX_RECORDING_OPTIONS }
                ?: d.maxRecordingSeconds,
            silenceSeconds = p[SILENCE_SECONDS]?.takeIf { it in AppSettings.SILENCE_OPTIONS } ?: d.silenceSeconds,
            pauseSeconds = p[PAUSE_SECONDS]?.takeIf { it in AppSettings.PAUSE_OPTIONS } ?: d.pauseSeconds,
            speechLanguage = p[SPEECH_LANGUAGE]?.takeIf { it in AppSettings.SPEECH_LANGUAGES }
                ?: AppSettings.speechLanguageFor(phoneLanguage),
            onlineSpeech = p[ONLINE_SPEECH].toEnum(d.onlineSpeech),
            // Anything that is not a type name (including "AUTO" or nothing) means "auto".
            noteTypeChoice = NoteType.entries.firstOrNull { it.name == p[NOTE_TYPE] },
            theme = p[THEME].toEnum(d.theme),
            reduceMotion = p[REDUCE_MOTION] ?: d.reduceMotion,
            // Before 2.3 there was no choice: Claude alone turned on means paid Claude, anything else free.
            aiMode = p[AI_MODE].toEnum(
                if (AiProvider.ANTHROPIC in aiProviders && AiProvider.FREE.none { it in aiProviders }) AiMode.CLAUDE else d.aiMode,
            ),
            aiProviders = aiProviders,
            aiModels = AiProvider.entries.mapNotNull { pr ->
                p[modelKey(pr)]?.trim()?.take(AppSettings.MAX_MODEL_NAME)?.ifEmpty { null }?.let { pr to it }
            }.toMap(),
            aiDailyLimit = p[AI_DAILY_LIMIT]?.takeIf { it in AppSettings.AI_DAILY_LIMITS } ?: d.aiDailyLimit,
            textScale = p[TEXT_SCALE]?.takeIf { it in AppSettings.TEXT_SCALES } ?: d.textScale,
            tiltedPaper = p[TILTED_PAPER] ?: d.tiltedPaper,
            claudeTopUpCents = p[CLAUDE_TOP_UP]?.takeIf { it in 1..TopUpInput.MAX_CENTS },
            claudeTopUpDay = p[CLAUDE_TOP_UP_DAY]?.takeIf { it >= 0 },
            // 0 stored = "no limit" (a cleared field); nothing stored = the default.
            claudeDayCents = when (val cents = p[CLAUDE_DAY]) {
                null -> d.claudeDayCents
                in 1..TopUpInput.MAX_CENTS -> cents
                else -> null
            },
            firstStepsSeen = p[FIRST_STEPS_SEEN] ?: d.firstStepsSeen,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[RECORDING_END] = s.recordingEnd.name
        p[MAX_SECONDS] = s.maxRecordingSeconds
        p[SILENCE_SECONDS] = s.silenceSeconds
        p[PAUSE_SECONDS] = s.pauseSeconds
        p[SPEECH_LANGUAGE] = s.speechLanguage
        p[ONLINE_SPEECH] = s.onlineSpeech.name
        p[NOTE_TYPE] = s.noteTypeChoice?.name ?: AUTO
        p[THEME] = s.theme.name
        p[REDUCE_MOTION] = s.reduceMotion
        p[AI_MODE] = s.aiMode.name
        // Kept in the order they are asked, so the file reads the same as the screen.
        p[AI_PROVIDERS] = AiProvider.entries.filter { it in s.aiProviders }.joinToString(",") { it.id }
        p.remove(OLD_AI_PROVIDER)
        for (provider in AiProvider.entries) {
            val model = s.aiModels[provider]?.trim().orEmpty()
            if (model.isEmpty()) p.remove(modelKey(provider)) else p[modelKey(provider)] = model.take(AppSettings.MAX_MODEL_NAME)
        }
        p[AI_DAILY_LIMIT] = s.aiDailyLimit
        p[TEXT_SCALE] = s.textScale
        // Whisper was removed (author 21.11.2026) - its old choice is not kept around.
        p.remove(OLD_WHISPER)
        p[TILTED_PAPER] = s.tiltedPaper
        s.claudeTopUpCents?.let { p[CLAUDE_TOP_UP] = it } ?: p.remove(CLAUDE_TOP_UP)
        s.claudeTopUpDay?.let { p[CLAUDE_TOP_UP_DAY] = it } ?: p.remove(CLAUDE_TOP_UP_DAY)
        p[CLAUDE_DAY] = s.claudeDayCents ?: 0
        p[FIRST_STEPS_SEEN] = s.firstStepsSeen
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    private companion object {
        val RECORDING_END = stringPreferencesKey("recording_end")
        val MAX_SECONDS = intPreferencesKey("max_recording_seconds")
        val SILENCE_SECONDS = intPreferencesKey("silence_seconds")
        val PAUSE_SECONDS = intPreferencesKey("pause_seconds")
        val SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
        val ONLINE_SPEECH = stringPreferencesKey("online_speech")
        // New key name: the old "default type" value must not turn into a manual choice.
        val NOTE_TYPE = stringPreferencesKey("note_type_choice")
        const val AUTO = "AUTO"
        // "default_priority" (until 11.11.2026) may still be stored - it is no longer read.
        val THEME = stringPreferencesKey("theme")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        /** One provider before 0.5 - read once to keep the user's choice, then removed. */
        val OLD_AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_MODE = stringPreferencesKey("ai_mode")
        val AI_PROVIDERS = stringPreferencesKey("ai_providers")
        val AI_DAILY_LIMIT = intPreferencesKey("ai_daily_limit")
        fun modelKey(provider: AiProvider) = stringPreferencesKey("ai_model_${provider.id}")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val OLD_WHISPER = stringPreferencesKey("whisper")
        val TILTED_PAPER = booleanPreferencesKey("tilted_paper")
        val CLAUDE_TOP_UP = intPreferencesKey("claude_top_up_cents")
        val CLAUDE_TOP_UP_DAY = longPreferencesKey("claude_top_up_day")
        val CLAUDE_DAY = intPreferencesKey("claude_day_cents")
        val FIRST_STEPS_SEEN = booleanPreferencesKey("first_steps_seen")
    }
}
