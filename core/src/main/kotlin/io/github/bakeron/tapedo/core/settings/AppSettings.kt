package io.github.bakeron.tapedo.core.settings

import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import kotlinx.coroutines.flow.Flow

enum class RecordingEnd { SILENCE, STOP_BUTTON }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** May speech go to the online recognizer (Google) when the phone has no offline language pack? */
enum class OnlineSpeech { ASK, ALWAYS, NEVER }

/**
 * Whisper types the recording again after "stop" - more exactly than the phone's recognizer, which still
 * shows the words live. Uses the AI key saved for [ai]. Models checked with the providers on 10.11.2026;
 * both give word times, so a pause can become a new list item, as with the phone's recognizer.
 */
enum class WhisperProvider(val ai: AiProvider, val model: String) {
    GROQ(AiProvider.GROQ, "whisper-large-v3-turbo"),
    OPENAI(AiProvider.OPENAI, "whisper-1"),
}

/**
 * Free or paid AI - never both at once (author's shared AI rules, 13.11.2026, as in the job-offers board).
 * Free: Groq and OpenRouter write the cards after each recording on their own. Claude: paid, so it is asked
 * only on a tap ("More exact" after a recording) - the rules write the cards first.
 */
enum class AiMode { FREE, CLAUDE }

/** Everything the user can tune. Defaults follow docs/zalozenia.md. */
data class AppSettings(
    val recordingEnd: RecordingEnd = RecordingEnd.SILENCE,
    val maxRecordingSeconds: Int = 60,
    /** "On silence": seconds without new words before the recording ends - long enough to think of the next item. */
    val silenceSeconds: Int = 7,
    /**
     * Seconds without a new word that start a new list item (a comma). 0 = a pause never does: one recording
     * (and each recording added to a card) is one item (author 13.11.2026).
     */
    val pauseSeconds: Int = 2,
    /** BCP 47 tag passed to the speech recognizer. */
    val speechLanguage: String = "pl-PL",
    /** Ask by default - the voice never leaves the phone without the user saying yes. */
    val onlineSpeech: OnlineSpeech = OnlineSpeech.ASK,
    /** The divider open on the home screen - a recording there becomes a card of this type; null = "auto". */
    val noteTypeChoice: NoteType? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val aiMode: AiMode = AiMode.FREE,
    /** Free AI providers turned on, asked in the order of [AiProvider.entries]; empty = no AI, the rules write the cards. */
    val aiProviders: Set<AiProvider> = emptySet(),
    /** Model per provider; missing or blank = the provider's default model. */
    val aiModels: Map<AiProvider, String> = emptyMap(),
    /** After this many calls a day a provider waits until tomorrow - a free limit is never overrun. */
    val aiDailyLimit: Int = 200,
    /** Text size in Tapedo, on top of the phone's own text size: 1 = as the phone has it. */
    val textScale: Float = 1f,
    /** Whisper after each recording; null = off (the default - the audio then never leaves the phone). */
    val whisper: WhisperProvider? = null,
    /** "Crooked paper": cards, tapes and stamps lie at a slight angle. Off by default (author 10.11.2026). */
    val tiltedPaper: Boolean = false,
    /** The last top-up of the Claude account in US cents (as Anthropic counts); null = not given. */
    val claudeTopUpCents: Int? = null,
    /** Epoch day the top-up was typed in - Claude's spending is counted from it. */
    val claudeTopUpDay: Long? = null,
    /** Daily limit of Claude's spending in US cents, checked before each call; null = no limit. */
    val claudeDayCents: Int? = 100,
    /** "First steps" (the tips on the home screen) were gone through or skipped; Settings can show them again. */
    val firstStepsSeen: Boolean = false,
) {
    /** The top-up in dollars, only together with its day - a sum alone has nothing to count from. */
    val claudeTopUpUsd: Double? get() = claudeTopUpCents?.takeIf { claudeTopUpDay != null }?.let { it / 100.0 }

    companion object {
        /**
         * A new card's priority when the recording says nothing about it: low (green tape). Not a setting
         * any more (author 11.11.2026: unclear - "urgent" / "important" said aloud already sets it, and the
         * tape is easy to change on the card after recording).
         */
        val NEW_CARD_PRIORITY = Priority.LOW
        val TEXT_SCALES = listOf(0.9f, 1f, 1.15f, 1.3f)
        /** Slider positions for the recording limit, in seconds: 10-50 s, then every minute up to 60 min. */
        val MAX_RECORDING_OPTIONS = listOf(10, 20, 30, 40, 50) + (1..60).map { it * 60 }
        val SILENCE_OPTIONS = (3..30).toList()
        val PAUSE_OPTIONS = (0..10).toList()
        val SPEECH_LANGUAGES = listOf("pl-PL", "en-US")
        val AI_DAILY_LIMITS = listOf(10, 25, 50, 100, 200, 300, 500, 1000)
        const val MAX_MODEL_NAME = 120

        /** Speech language after installing: Polish on a phone in Polish, English on any other (author 11.11.2026). */
        fun speechLanguageFor(phoneLanguage: String): String = if (phoneLanguage == "pl") "pl-PL" else "en-US"
    }

    /** The pause that makes a new list item, or null when pauses never do. */
    val pauseMillis: Long? get() = pauseSeconds.takeIf { it > 0 }?.let { it * 1000L }

    fun modelFor(provider: AiProvider): String = aiModels[provider]?.trim()?.ifEmpty { null } ?: provider.defaultModel

    /**
     * Whisper as really used: only in free mode - never free and paid AI at once (author 15.11.2026). In Claude
     * mode the Groq frame is hidden, so its key could not even be typed. The choice itself is kept for going back.
     */
    val activeWhisper: WhisperProvider? get() = whisper.takeIf { aiMode == AiMode.FREE }

    /** Providers that write the cards after a recording on their own: the free ones turned on, and only in free mode. */
    val autoProviders: List<AiProvider>
        get() = if (aiMode == AiMode.FREE) AiProvider.FREE.filter { it in aiProviders } else emptyList()
}

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}
