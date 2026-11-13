package io.github.bakeron.tapedo.core.speech

import kotlinx.coroutines.flow.Flow

/** Why listening failed - shown to the user in plain words. */
enum class SpeechError {
    NOT_AVAILABLE,
    NO_PERMISSION,
    NO_SPEECH,
    NETWORK,
    BUSY,

    /** No offline language pack, and going online was not allowed - nothing was sent anywhere. */
    OFFLINE_UNAVAILABLE,
    OTHER,
}

sealed interface SpeechEvent {
    /** Voice loudness 0..1 - drives the seismograph line. */
    data class Level(val value: Float) : SpeechEvent

    /**
     * The microphone opened (the first `true` comes when the recognizer is ready, often a second after
     * the start), closed for a moment (on phones without a continuous session the recognizer restarts
     * after a pause) or opened again. Words said while it is closed are lost.
     */
    data class Listening(val on: Boolean) : SpeechEvent

    /** Text so far: every finished phrase plus the one being spoken. */
    data class Partial(val text: String) : SpeechEvent

    /**
     * Listening ended normally; the whole transcript. Always the last event.
     * @param audio the recording itself, only when it was asked for (Whisper) and the phone let Tapedo record.
     */
    data class Done(val text: String, val audio: RecordedAudio? = null) : SpeechEvent

    /**
     * Listening ended with an error and nothing usable was heard. Always the last event.
     * @param code the platform recognizer's own error number, for an error report - null when there is none.
     */
    data class Failed(val error: SpeechError, val code: Int? = null) : SpeechEvent

    /**
     * The phone's recognizer refused the audio Tapedo records by itself, so this recording goes on with the
     * recognizer's own microphone - it works, but a word may get lost when it restarts after a pause.
     * Comes at most once, and only on the recording where it happened (later ones skip straight to it).
     * @param code the recognizer's error number, for an error report.
     */
    data class OwnAudioRefused(val code: Int) : SpeechEvent
}

/** How the phone's recognizer keeps a long recording going. */
enum class ListeningMode {
    /** Not known yet - found out during the first recording. */
    UNKNOWN,

    /** One session from "record" to "stop": pauses never close the microphone. */
    CONTINUOUS,

    /** The recognizer ends after each pause and is started again - a short gap without listening. */
    RESUMING,
}

/**
 * Speech-to-text of the platform (Android's recognizer; on iPhone it would be Apple's).
 * Kept behind this interface so `core` stays free of Android code. Audio is never stored - only text
 * comes out, plus the recording in memory when Whisper asks for it.
 */
interface SpeechInput {
    /**
     * Starts listening when collected and keeps listening across pauses, like a dictaphone, until [stop]
     * (the stop button, the time limit or Tapedo's own silence countdown call it).
     * Cancelling the collection aborts without a result.
     * @param allowOnline recognition always starts offline; only with true may it fall back to the online
     *   recognizer when the offline language pack is missing. Otherwise it ends with [SpeechError.OFFLINE_UNAVAILABLE].
     * @param skipFirstMs what is said in this first stretch (the 3-2-1 countdown) is not used - the author's
     *   decision 08.11.2026. The recognizer starts at once anyway, so it is ready by "go".
     * @param keepAudio keep the recording in memory and hand it over in [SpeechEvent.Done] (for Whisper).
     * @param pauseMillis a pause this long between words becomes a comma (a new list item); null = never.
     */
    fun listen(
        languageTag: String,
        allowOnline: Boolean,
        skipFirstMs: Long = 0,
        keepAudio: Boolean = false,
        pauseMillis: Long? = Transcript.PAUSE_MILLIS,
    ): Flow<SpeechEvent>

    /** Ends listening gracefully: what was said so far still arrives as [SpeechEvent.Done]. */
    fun stop()

    /** What the last recordings showed about this phone - for one line in Settings. */
    suspend fun listeningMode(): ListeningMode
}
