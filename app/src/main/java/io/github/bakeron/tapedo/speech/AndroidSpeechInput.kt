package io.github.bakeron.tapedo.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.bakeron.tapedo.core.speech.ListeningMode
import io.github.bakeron.tapedo.core.speech.Phrases
import io.github.bakeron.tapedo.core.speech.RecognizerErrorKind
import io.github.bakeron.tapedo.core.speech.RecognizerErrorPolicy
import io.github.bakeron.tapedo.core.speech.RecognizerStep
import io.github.bakeron.tapedo.core.speech.SpeechError
import io.github.bakeron.tapedo.core.speech.SpeechEvent
import io.github.bakeron.tapedo.core.speech.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Android's built-in speech recognizer. Always starts offline: on Android 12+ with the on-device
 * recognizer (the audio cannot leave the phone), on older phones with the "prefer offline" hint.
 * Only with `allowOnline` does it fall back to the online recognizer when the offline language
 * pack is missing. No audio is saved.
 *
 * Works like a dictaphone: from "record" to [stop] nothing said is lost. On Android 13+ it asks for a
 * segmented session - one open microphone, a result after every pause. A recognizer that ignores that
 * (or an older phone) ends after each pause; it is then restarted at once and the phrases are joined
 * (a pause becomes a comma, so items said one by one stay separate). During that restart the microphone
 * is closed for a moment - [SpeechEvent.Listening] tells the screen.
 * SpeechRecognizer must be used on the main thread - hence [flowOn] Main and the [main] handler.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    private val main = Handler(Looper.getMainLooper())

    /** Set while a session runs; called on the main thread. */
    private var stopRequest: (() -> Unit)? = null

    /** Only "which way this phone went" - no speech, nothing personal. */
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun stop() {
        main.post { stopRequest?.invoke() }
    }

    override suspend fun listeningMode(): ListeningMode = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext ListeningMode.RESUMING
        ListeningMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: ListeningMode.UNKNOWN
    }

    private fun remember(mode: ListeningMode) {
        // apply() writes off the main thread.
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun onDeviceAvailable() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun newRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    override fun listen(languageTag: String, allowOnline: Boolean): Flow<SpeechEvent> = callbackFlow {
        val onDevice = onDeviceAvailable()
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SpeechEvent.Failed(SpeechError.NOT_AVAILABLE))
            close()
            awaitClose { }
            return@callbackFlow
        }

        var recognizer = newRecognizer(onDevice)
        val phrases = mutableListOf<String>()
        var stopping = false
        var closed = false
        var offline = true
        var listening = true
        // Errors other than silence since the last new words - a recognizer that keeps failing ends the recording.
        var failuresInRow = 0
        // A segment arrived: this recognizer really keeps one session open.
        var segmentsSeen = false
        // The words on screen for the phrase being spoken. Some recognizers end a phrase with "no match"
        // (or an empty result) after showing it - what the user saw on screen is still kept.
        var shown = ""

        fun soFar(current: String = "") = Phrases.join(phrases + current)

        fun keepShown() {
            if (shown.isNotBlank()) phrases += shown
            shown = ""
        }

        fun addPhrase(text: String?) {
            if (text.isNullOrBlank()) keepShown() else phrases += text
            shown = ""
        }

        fun finish() {
            if (closed) return
            trySend(if (phrases.isEmpty()) SpeechEvent.Failed(SpeechError.NO_SPEECH) else SpeechEvent.Done(soFar()))
            close()
        }

        fun start() {
            if (closed) return
            // "Stop" pressed during the restart gap - end now instead of listening again.
            if (stopping) return finish()
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // One session for the whole recording; [stop] ends it. Recognizers that do not
                        // support this ignore it and end after a pause as before.
                        putExtra(
                            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                            RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        )
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SESSION_MS)
                    } else {
                        // Older phones: wait a little longer before ending a phrase, fewer restart gaps.
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PHRASE_SILENCE_MS)
                        putExtra(
                            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                            PHRASE_SILENCE_MS,
                        )
                    }
                },
            )
        }

        // A short gap before restarting - starting again inside a callback often gives "busy".
        // The microphone is closed until onReadyForSpeech.
        fun restart(block: () -> Unit = { start() }) {
            if (listening) {
                listening = false
                trySend(SpeechEvent.Listening(false))
            }
            main.postDelayed({ if (!closed) block() }, RESTART_DELAY_MS)
        }

        val listener = object : RecognitionListener {
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onReadyForSpeech(params: Bundle?) {
                if (!listening) {
                    listening = true
                    trySend(SpeechEvent.Listening(true))
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Typical range is about -2..10 dB.
                trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults.firstResult()
                if (!text.isNullOrBlank()) {
                    shown = text
                    failuresInRow = 0
                    trySend(SpeechEvent.Partial(soFar(text)))
                }
            }

            /** Continuous session: one phrase after a pause; the microphone stays open. */
            override fun onSegmentResults(segmentResults: Bundle) {
                if (!segmentsSeen) {
                    segmentsSeen = true
                    remember(ListeningMode.CONTINUOUS)
                }
                val text = segmentResults.firstResult()
                if (!text.isNullOrBlank()) failuresInRow = 0
                addPhrase(text)
                trySend(SpeechEvent.Partial(soFar()))
            }

            override fun onEndOfSegmentedSession() {
                keepShown()
                // Ended by itself (e.g. the recognizer's own time limit) - the recording goes on.
                if (stopping) finish() else restart()
            }

            /** The recognizer ended after a pause - a whole session in older phones. */
            override fun onResults(results: Bundle?) {
                val text = results.firstResult()
                if (!text.isNullOrBlank()) failuresInRow = 0
                addPhrase(text)
                if (stopping) return finish()
                if (!segmentsSeen) remember(ListeningMode.RESUMING)
                trySend(SpeechEvent.Partial(soFar()))
                restart()
            }

            override fun onError(error: Int) {
                keepShown()
                val kind = kindOf(error)
                if (kind != RecognizerErrorKind.SILENCE) failuresInRow++
                val step = RecognizerErrorPolicy.decide(
                    error = kind,
                    offline = offline,
                    phrasesHeard = phrases.isNotEmpty(),
                    allowOnline = allowOnline,
                    stopping = stopping,
                    failuresInRow = failuresInRow,
                )
                when (step) {
                    RecognizerStep.GoOnline -> {
                        offline = false
                        // Swapped outside the callback - the on-device recognizer cannot go online.
                        restart {
                            recognizer.destroy()
                            recognizer = newRecognizer(onDevice = false).also { it.setRecognitionListener(this) }
                            start()
                        }
                    }
                    RecognizerStep.Restart -> restart()
                    RecognizerStep.FinishWithText -> finish()
                    is RecognizerStep.Fail -> {
                        trySend(SpeechEvent.Failed(step.error))
                        close()
                    }
                }
            }
        }
        recognizer.setRecognitionListener(listener)

        stopRequest = {
            if (!stopping) {
                stopping = true
                recognizer.stopListening()
                // A recognizer that never answers the stop must not leave the recording hanging.
                main.postDelayed({
                    keepShown()
                    finish()
                }, STOP_GRACE_MS)
            }
        }
        start()

        awaitClose {
            closed = true
            stopRequest = null
            main.removeCallbacksAndMessages(null)
            recognizer.destroy()
        }
    }
        // trySend drops events when the default 64-slot buffer is full - the final Done must never be lost.
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.Main)

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun kindOf(code: Int): RecognizerErrorKind = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognizerErrorKind.NO_PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> RecognizerErrorKind.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognizerErrorKind.BUSY
        in LANGUAGE_ERRORS -> RecognizerErrorKind.LANGUAGE_MISSING
        in SILENCE_ERRORS -> RecognizerErrorKind.SILENCE
        else -> RecognizerErrorKind.OTHER
    }

    private companion object {
        const val RESTART_DELAY_MS = 150L
        const val STOP_GRACE_MS = 3_000L

        /** Longer than the longest recording (60 min) - the session ends with [stop], not by itself. */
        const val SESSION_MS = 61 * 60 * 1000

        /** Fallback path: quiet before a phrase ends (the platform default is often about 1 s). */
        const val PHRASE_SILENCE_MS = 2_500L
        const val PREFS = "speech"
        const val KEY_MODE = "listening_mode"
        val SILENCE_ERRORS = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        val LANGUAGE_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
    }
}
