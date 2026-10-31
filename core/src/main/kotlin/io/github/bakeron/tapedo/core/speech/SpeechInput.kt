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

    /** Text so far: every finished phrase plus the one being spoken. */
    data class Partial(val text: String) : SpeechEvent

    /** Listening ended normally; the whole transcript. Always the last event. */
    data class Done(val text: String) : SpeechEvent

    /** Listening ended with an error and nothing usable was heard. Always the last event. */
    data class Failed(val error: SpeechError) : SpeechEvent
}

/**
 * Speech-to-text of the platform (Android's recognizer; on iPhone it would be Apple's).
 * Kept behind this interface so `core` stays free of Android code. Audio is never stored -
 * only text comes out.
 */
interface SpeechInput {
    /**
     * Starts listening when collected; cancelling the collection aborts without a result.
     * @param untilStopped keep listening across pauses until [stop]; otherwise end at the first silence.
     * @param allowOnline recognition always starts offline; only with true may it fall back to the online
     *   recognizer when the offline language pack is missing. Otherwise it ends with [SpeechError.OFFLINE_UNAVAILABLE].
     */
    fun listen(languageTag: String, untilStopped: Boolean, allowOnline: Boolean): Flow<SpeechEvent>

    /** Ends listening gracefully: what was said so far still arrives as [SpeechEvent.Done]. */
    fun stop()
}
