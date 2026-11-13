package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.WhisperProvider
import io.github.bakeron.tapedo.core.speech.RecordedAudio
import io.github.bakeron.tapedo.core.speech.TimedSegment
import io.github.bakeron.tapedo.core.speech.TimedWord
import io.github.bakeron.tapedo.core.speech.WhisperText
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Speech to text by a provider - Whisper over HTTP in the app, a fake in tests. Throws [AiException]. */
fun interface SpeechToText {
    /** @param pauseMillis a pause this long becomes a comma (a new list item); null = never. */
    suspend fun transcribe(provider: WhisperProvider, key: String, audio: RecordedAudio, languageTag: String, pauseMillis: Long?): String
}

/**
 * Whisper through the OpenAI-compatible `audio/transcriptions` endpoint (Groq and OpenAI take the same form).
 * The recording goes as a WAV from memory; the key only in a header. Word times come back too, so pauses
 * become commas ([WhisperText]).
 */
class HttpWhisper(private val http: HttpClient) : SpeechToText {

    override suspend fun transcribe(provider: WhisperProvider, key: String, audio: RecordedAudio, languageTag: String, pauseMillis: Long?): String {
        val root = providerJson(key) {
            http.submitFormWithBinaryData(
                url = url(provider),
                formData = formData {
                    append("model", provider.model)
                    // "pl-PL" -> "pl": told, not guessed - a short memo is easily taken for another language.
                    append("language", languageTag.substringBefore('-').lowercase())
                    append("response_format", "verbose_json")
                    append("timestamp_granularities[]", "word")
                    append("timestamp_granularities[]", "segment")
                    append(
                        "file",
                        audio.toWav(),
                        Headers.build {
                            append(HttpHeaders.ContentType, "audio/wav")
                            append(HttpHeaders.ContentDisposition, "filename=\"tapedo.wav\"")
                        },
                    )
                },
            ) {
                header(HttpHeaders.Authorization, "Bearer $key")
                // Sending minutes of audio over a phone connection takes longer than a chat answer.
                timeout { requestTimeoutMillis = UPLOAD_TIMEOUT_MS }
            }
        }
        return WhisperText.build(
            text = root.str("text").orEmpty(),
            segments = root.list("segments").mapNotNull { s ->
                TimedSegment(
                    s.str("text") ?: return@mapNotNull null,
                    s.num("start") ?: return@mapNotNull null,
                    s.num("end") ?: return@mapNotNull null,
                    s.num("no_speech_prob") ?: 0.0,
                )
            },
            words = root.list("words").mapNotNull { w ->
                TimedWord(
                    w.str("word") ?: return@mapNotNull null,
                    w.num("start") ?: return@mapNotNull null,
                    w.num("end") ?: return@mapNotNull null,
                )
            },
            pauseMillis = pauseMillis,
        ).trim()
    }

    private fun url(provider: WhisperProvider) = when (provider) {
        WhisperProvider.GROQ -> "https://api.groq.com/openai/v1/audio/transcriptions"
        WhisperProvider.OPENAI -> "https://api.openai.com/v1/audio/transcriptions"
    }

    private fun JsonObject.list(name: String): List<JsonObject> = (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.num(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        const val UPLOAD_TIMEOUT_MS = 180_000L
    }
}

/**
 * After "stop": Whisper types the recording again, when it is turned on. Whatever goes wrong, the phone's
 * own text stays - the caller gets the reason to show, never a lost note.
 */
class WhisperRetyper(private val secrets: SecretStore, private val stt: SpeechToText) {

    sealed interface Outcome {
        data class Text(val text: String) : Outcome
        data class Failed(val problem: AiProblem) : Outcome
    }

    /** Null when Whisper is off. */
    suspend fun retype(settings: AppSettings, audio: RecordedAudio?): Outcome? {
        val provider = settings.whisper ?: return null
        fun failed(problem: Problem, message: String = "") = Outcome.Failed(AiProblem(provider.ai, provider.model, problem, message))

        if (audio == null) return failed(Problem.NO_AUDIO)
        if (!audio.complete) return failed(Problem.TOO_LONG)
        val sent = audio.trimSilence()
        if (sent.pcm.isEmpty()) return failed(Problem.NO_AUDIO)
        if (RecordedAudio.WAV_HEADER + sent.pcm.size > MAX_UPLOAD_BYTES) return failed(Problem.TOO_LONG)

        val key = try {
            secrets.apiKey(provider.ai)?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(Problem.KEY_UNREADABLE)
        } ?: return failed(Problem.NO_KEY)

        return try {
            val text = stt.transcribe(provider, key, sent, settings.speechLanguage, settings.pauseMillis).trim()
            if (text.isEmpty()) failed(Problem.ANSWER, EMPTY_ANSWER) else Outcome.Text(text)
        } catch (e: AiException) {
            failed(e.problem, e.providerMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(Problem.OTHER)
        }
    }

    companion object {
        /** Groq's free plan and OpenAI both take files up to 25 MB - a little under it, to be safe. */
        const val MAX_UPLOAD_BYTES = 24_000_000
    }
}
