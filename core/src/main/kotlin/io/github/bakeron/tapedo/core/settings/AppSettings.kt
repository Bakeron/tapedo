package io.github.bakeron.tapedo.core.settings

import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import kotlinx.coroutines.flow.Flow

enum class RecordingEnd { SILENCE, STOP_BUTTON }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** May speech go to the online recognizer (Google) when the phone has no offline language pack? */
enum class OnlineSpeech { ASK, ALWAYS, NEVER }

/** Everything the user can tune. Defaults follow docs/zalozenia.md. */
data class AppSettings(
    val recordingEnd: RecordingEnd = RecordingEnd.SILENCE,
    val maxRecordingSeconds: Int = 60,
    /** "On silence": seconds without new words before the recording ends - long enough to think of the next item. */
    val silenceSeconds: Int = 7,
    /** BCP 47 tag passed to the speech recognizer. */
    val speechLanguage: String = "pl-PL",
    /** Ask by default - the voice never leaves the phone without the user saying yes. */
    val onlineSpeech: OnlineSpeech = OnlineSpeech.ASK,
    /** Last type picked on the home screen's type bar; null = "auto" (rules / AI decide). */
    val noteTypeChoice: NoteType? = null,
    val defaultPriority: Priority = Priority.MEDIUM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    /** AI providers turned on, asked in the order of [AiProvider.entries]; empty = no AI, the rules write the cards. */
    val aiProviders: Set<AiProvider> = emptySet(),
    /** Model per provider; missing or blank = the provider's default model. */
    val aiModels: Map<AiProvider, String> = emptyMap(),
    /** After this many calls a day a provider waits until tomorrow - a free limit is never overrun. */
    val aiDailyLimit: Int = 200,
    /** Text size in Tapedo, on top of the phone's own text size: 1 = as the phone has it. */
    val textScale: Float = 1f,
) {
    companion object {
        val TEXT_SCALES = listOf(0.9f, 1f, 1.15f, 1.3f)
        /** Slider positions for the recording limit, in seconds: 10-50 s, then every minute up to 60 min. */
        val MAX_RECORDING_OPTIONS = listOf(10, 20, 30, 40, 50) + (1..60).map { it * 60 }
        val SILENCE_OPTIONS = (3..30).toList()
        val SPEECH_LANGUAGES = listOf("pl-PL", "en-US")
        val AI_DAILY_LIMITS = listOf(10, 25, 50, 100, 200, 300, 500, 1000)
        const val MAX_MODEL_NAME = 120
    }

    fun modelFor(provider: AiProvider): String = aiModels[provider]?.trim()?.ifEmpty { null } ?: provider.defaultModel
}

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}
