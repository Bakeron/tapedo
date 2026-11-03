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

/**
 * Android's built-in speech recognizer. Always starts offline: on Android 12+ with the on-device
 * recognizer (the audio cannot leave the phone), on older phones with the "prefer offline" hint.
 * Only with `allowOnline` does it fall back to the online recognizer when the offline language
 * pack is missing. No audio is saved.
 *
 * The recognizer stops by itself after a pause. In "stop button" mode it is restarted after each
 * phrase and the phrases are joined, so one recording can last up to the user's limit.
 * SpeechRecognizer must be used on the main thread - hence [flowOn] Main and the [main] handler.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    private val main = Handler(Looper.getMainLooper())

    /** Set while a session runs; called on the main thread. */
    private var stopRequest: (() -> Unit)? = null

    override fun stop() {
        main.post { stopRequest?.invoke() }
    }

    private fun onDeviceAvailable() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun newRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    override fun listen(languageTag: String, untilStopped: Boolean, allowOnline: Boolean): Flow<SpeechEvent> = callbackFlow {
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

        fun soFar(current: String = "") = (phrases + current).filter { it.isNotBlank() }.joinToString(" ")

        fun finish() {
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
                },
            )
        }

        // A short gap before restarting - starting again inside a callback often gives "busy".
        fun restart() = main.postDelayed({ start() }, RESTART_DELAY_MS)

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // Typical range is about -2..10 dB.
                trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults.firstResult()
                if (!text.isNullOrBlank()) trySend(SpeechEvent.Partial(soFar(text)))
            }

            override fun onResults(results: Bundle?) {
                results.firstResult()?.takeIf { it.isNotBlank() }?.let { phrases += it }
                if (untilStopped && !stopping) restart() else finish()
            }

            override fun onError(error: Int) {
                val step = RecognizerErrorPolicy.decide(
                    error = kindOf(error),
                    offline = offline,
                    phrasesHeard = phrases.isNotEmpty(),
                    allowOnline = allowOnline,
                    untilStopped = untilStopped,
                    stopping = stopping,
                )
                when (step) {
                    RecognizerStep.GoOnline -> {
                        offline = false
                        // Swapped outside the callback - the on-device recognizer cannot go online.
                        main.postDelayed({
                            if (closed) return@postDelayed
                            recognizer.destroy()
                            recognizer = newRecognizer(onDevice = false).also { it.setRecognitionListener(this) }
                            start()
                        }, RESTART_DELAY_MS)
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
            stopping = true
            recognizer.stopListening()
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
        val SILENCE_ERRORS = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        val LANGUAGE_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
    }
}
