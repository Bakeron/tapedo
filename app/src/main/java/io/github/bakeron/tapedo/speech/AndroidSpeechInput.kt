package io.github.bakeron.tapedo.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.pm.PackageInfoCompat
import io.github.bakeron.tapedo.core.speech.ListeningMode
import io.github.bakeron.tapedo.core.speech.RecognizerErrorKind
import io.github.bakeron.tapedo.core.speech.RecognizerErrorPolicy
import io.github.bakeron.tapedo.core.speech.RecognizerStep
import io.github.bakeron.tapedo.core.speech.SpeechError
import io.github.bakeron.tapedo.core.speech.SpeechEvent
import io.github.bakeron.tapedo.core.speech.SpeechInput
import io.github.bakeron.tapedo.core.speech.Transcript
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
 * ([Transcript]: a pause of 2 s or more becomes a comma, so items said one by one stay separate).
 * The microphone is closed until the recognizer is ready, and for a moment during that restart -
 * [SpeechEvent.Listening] tells the screen. On Android 13+ Tapedo records by itself ([ContinuousMic]) and
 * feeds the recognizer, so those restarts lose nothing.
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

    /** Version of this install - a refusal of the own audio is retried after an update (it may fix it). */
    private val versionCode by lazy {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }

    private fun ownAudioAllowed() = prefs.getLong(KEY_OWN_AUDIO_REFUSED_IN, -1L) != versionCode

    private fun rememberOwnAudioRefused() {
        prefs.edit().putLong(KEY_OWN_AUDIO_REFUSED_IN, versionCode).apply()
    }

    private fun onDeviceAvailable() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun newRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    override fun listen(languageTag: String, allowOnline: Boolean, skipFirstMs: Long, pauseMillis: Long?): Flow<SpeechEvent> = callbackFlow {
        val onDevice = onDeviceAvailable()
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SpeechEvent.Failed(SpeechError.NOT_AVAILABLE))
            close()
            awaitClose { }
            return@callbackFlow
        }

        // Android 13+: Tapedo's own microphone feeds the recognizer, so its restarts leave no gap. Without it
        // (older phones, a microphone that would not open) the recognizer listens by itself, as before.
        // A phone whose recognizer refused it before (in this version) goes straight to the old way.
        var mic: ContinuousMic? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ownAudioAllowed()) {
            ContinuousMic(
                skipMs = skipFirstMs,
                onLevel = { trySend(SpeechEvent.Level(it)) },
            ).takeIf { it.start() }
        } else {
            null
        }

        var recognizer = newRecognizer(onDevice)
        // Pauses become commas by the clock, not by where the recognizer ends its phrases. Some
        // recognizers end a phrase with "no match" (or an empty result) after showing it - what the
        // user saw on screen is still kept.
        val transcript = Transcript(pauseMillis)
        var stopping = false
        // Speech began and its phrase has no result yet - stopListening now would throw the phrase away.
        var speaking = false
        var closed = false
        var offline = true
        // The microphone opens only at onReadyForSpeech, often a second after the tap - until then the
        // screen must not say "listening", or the first words are lost.
        var listening = false
        // Errors other than silence since the last new words - a recognizer that keeps failing ends the recording.
        var failuresInRow = 0
        // A segment arrived: this recognizer really keeps one session open.
        var segmentsSeen = false

        // Own microphone: where the next recognizer session starts in the recorded audio.
        var nextFrom = 0L
        var sessionFrom = 0L
        var sessionReady = false
        // Where the recognizer said the phrase ended in this session (-1: not yet).
        var phraseEndPos = -1L
        // Some words came back - the recognizer does take Tapedo's audio.
        var heardAnything = false
        // Errors that look like "this recognizer refuses Tapedo's audio", before anything was heard.
        var ownAudioErrors = 0

        fun now() = SystemClock.elapsedRealtime()

        fun finish() {
            if (closed) return
            trySend(
                if (transcript.isEmpty()) SpeechEvent.Failed(SpeechError.NO_SPEECH) else SpeechEvent.Done(transcript.text()),
            )
            close()
        }

        /**
         * Own microphone: a session is over - the next one starts a little before where this one stopped
         * hearing, so nothing said in between is lost. A session that heard nothing because it failed is
         * replayed whole. Returns true when, after "stop", recorded audio is still left for another session.
         */
        fun sessionEnded(failed: Boolean): Boolean {
            val m = mic ?: return false
            val boundary = when {
                phraseEndPos >= 0 -> phraseEndPos
                failed || !sessionReady -> sessionFrom
                else -> m.fedPos()
            }
            m.endSession()
            nextFrom = maxOf(boundary - ContinuousMic.bytes(REPLAY_MS), sessionFrom, m.startPos)
            phraseEndPos = -1
            val left = m.leftAfter(boundary)
            return stopping && left > LEFT_MIN_MS
        }

        fun start() {
            if (closed) return
            // "Stop" pressed during the restart gap - end now instead of listening again. With the own
            // microphone the audio recorded meanwhile still gets a last session (it ends at the stop point).
            if (stopping && mic == null) return finish()
            val m = mic
            speaking = false
            sessionReady = false
            phraseEndPos = -1
            sessionFrom = nextFrom
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    // Every restart closes the microphone for a moment, and the first word after it often gets
                    // lost (seen on the author's Android 16 phone, 07.11.2026). So: wait long before ending a
                    // phrase - on every Android version, also when the segmented session below is ignored.
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PHRASE_SILENCE_MS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PHRASE_SILENCE_MS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SESSION_MS)
                    if (m != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, m.openSession(nextFrom))
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, ContinuousMic.RATE)
                        // One session for the whole audio: it ends when the audio does (at "stop").
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // One session with a result after every pause; it ends after [PHRASE_SILENCE_MS] of
                        // silence or with [stop]. The "minimum length" kind was ignored by the author's phone,
                        // so the "silence" kind is asked for now.
                        putExtra(
                            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        )
                    }
                },
            )
        }

        // A short gap before restarting - starting again inside a callback often gives "busy".
        // The recognizer's own microphone is closed until onReadyForSpeech; the own one never closes.
        fun restart(block: () -> Unit = { start() }) {
            if (listening && mic == null) {
                listening = false
                trySend(SpeechEvent.Listening(false))
            }
            main.postDelayed({ if (!closed) block() }, RESTART_DELAY_MS)
        }

        val listener = object : RecognitionListener {
            override fun onBeginningOfSpeech() {
                speaking = true
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                val m = mic
                if (m != null && phraseEndPos < 0) phraseEndPos = m.fedPos()
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onReadyForSpeech(params: Bundle?) {
                sessionReady = true
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
                    heardAnything = true
                    transcript.partial(text, now())
                    failuresInRow = 0
                    trySend(SpeechEvent.Partial(transcript.text()))
                }
            }

            /** Continuous session: one phrase after a pause; the microphone stays open. */
            override fun onSegmentResults(segmentResults: Bundle) {
                if (!segmentsSeen) {
                    segmentsSeen = true
                    remember(ListeningMode.CONTINUOUS)
                }
                val text = segmentResults.firstResult()
                speaking = false
                // A segment is not a session end: the next phrase is in the same audio.
                phraseEndPos = -1
                if (!text.isNullOrBlank()) {
                    failuresInRow = 0
                    heardAnything = true
                }
                transcript.phraseEnded(text, now())
                trySend(SpeechEvent.Partial(transcript.text()))
            }

            override fun onEndOfSegmentedSession() {
                transcript.phraseEnded(null, now())
                val more = sessionEnded(failed = false)
                // Ended by itself (e.g. the recognizer's own time limit) - the recording goes on.
                if (stopping && !more) finish() else restart()
            }

            /** The recognizer ended after a pause - a whole session in older phones. */
            override fun onResults(results: Bundle?) {
                val text = results.firstResult()
                speaking = false
                if (!text.isNullOrBlank()) {
                    failuresInRow = 0
                    heardAnything = true
                }
                transcript.phraseEnded(text, now())
                val more = sessionEnded(failed = false)
                if (stopping && !more) return finish()
                if (!segmentsSeen) remember(ListeningMode.RESUMING)
                trySend(SpeechEvent.Partial(transcript.text()))
                restart()
            }

            override fun onError(error: Int) {
                speaking = false
                transcript.phraseEnded(null, now())
                val kind = kindOf(error)
                if (kind != RecognizerErrorKind.SILENCE) failuresInRow++
                val m = mic
                if (m != null && RecognizerErrorPolicy.ownAudioRefused(kind, offline, heardAnything)) {
                    // Once may be a glitch - the same audio is tried again. Twice: this recognizer refuses
                    // audio from an app, so the recording goes on with its own microphone (old way).
                    sessionEnded(failed = true)
                    if (++ownAudioErrors >= OWN_AUDIO_TRIES) {
                        m.close()
                        mic = null
                        rememberOwnAudioRefused()
                        failuresInRow = 0
                        trySend(SpeechEvent.OwnAudioRefused(error))
                    }
                    return restart()
                }
                // After "stop" the recorded rest still gets heard (the grace timer ends a recognizer that keeps failing).
                if (sessionEnded(failed = kind != RecognizerErrorKind.SILENCE)) return restart()
                val step = RecognizerErrorPolicy.decide(
                    error = kind,
                    offline = offline,
                    phrasesHeard = !transcript.isEmpty(),
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
                        trySend(SpeechEvent.Failed(step.error, code = error))
                        close()
                    }
                }
            }
        }
        recognizer.setRecognitionListener(listener)

        stopRequest = {
            if (!stopping) {
                stopping = true
                val m = mic
                if (m != null) {
                    // The microphone goes off at once; the recognizer gets the audio up to here and then its
                    // end, which ends the session with the last phrase in it (see onResults / onEndOfSegmentedSession).
                    m.stopAfter(STOP_AUDIO_TAIL_MS)
                } else {
                    // stopListening in the middle of a phrase gave "no match" and lost the whole last phrase
                    // (author's phone, 08.11.2026) - a phrase still being spoken gets [STOP_TAIL_MS] to end by
                    // itself, which finishes the recording at once (see onResults / start). Nothing being
                    // spoken - nothing to wait for.
                    if (speaking) {
                        main.postDelayed({ recognizer.stopListening() }, STOP_TAIL_MS)
                    } else {
                        recognizer.stopListening()
                    }
                }
                // A recognizer that never answers the stop must not leave the recording hanging.
                main.postDelayed({
                    transcript.phraseEnded(null, now())
                    finish()
                }, if (m != null) STOP_OWN_MIC_GRACE_MS else STOP_TAIL_MS + STOP_GRACE_MS)
            }
        }
        start()

        awaitClose {
            closed = true
            stopRequest = null
            main.removeCallbacksAndMessages(null)
            recognizer.destroy()
            mic?.close()
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
        const val SESSION_MS = 61 * 60 * 1000L // a Long: recognizers read these extras with getLong (an Int reads as 0)

        /**
         * Quiet before a phrase (or a segmented session) ends - the platform default is often under 1 s.
         * Pauses still become commas: [Transcript] reads them from the clock, not from phrase ends.
         * How long a silence ends the whole recording is decided by Tapedo, not here.
         */
        const val PHRASE_SILENCE_MS = 10_000L

        /**
         * After "stop", the most the microphone keeps listening - the author's limit (08.11.2026): listening
         * longer after "stop" is a bug. A phrase on the author's phone ended ~0.9 s after "stop".
         */
        const val STOP_TAIL_MS = 1_000L

        /** Own microphone: it records this much after "stop" - the last syllable is often said with the tap. */
        const val STOP_AUDIO_TAIL_MS = 200L

        /**
         * Own microphone: the most "stop" waits for the recognizer to turn the rest of the audio into text
         * (it usually takes well under 1 s); then the recording ends with what is on screen.
         */
        const val STOP_OWN_MIC_GRACE_MS = 2_000L

        /** A new session starts this much before where the last one heard the phrase end - still its silence. */
        const val REPLAY_MS = 300L

        /** After "stop": less unheard audio than this is not worth another session. */
        const val LEFT_MIN_MS = 250L
        const val PREFS = "speech"
        const val KEY_MODE = "listening_mode"

        /** Version code in which the recognizer refused Tapedo's own audio - see [ownAudioAllowed]. */
        const val KEY_OWN_AUDIO_REFUSED_IN = "own_audio_refused_in"
        const val OWN_AUDIO_TRIES = 2
        val SILENCE_ERRORS = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        val LANGUAGE_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
    }
}
