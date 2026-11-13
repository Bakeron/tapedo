package io.github.bakeron.tapedo.core.speech

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The audio of one recording (16-bit mono PCM), kept in memory only so Whisper can type it again - never
 * written to disk. [complete] is false when the recording outgrew what is kept; it is then not sent.
 */
class RecordedAudio(val pcm: ByteArray, val sampleRate: Int, val complete: Boolean = true) {

    /**
     * Without the silence before the first and after the last loud moment (with a margin): less to send,
     * and Whisper tends to "hear" phrases in long silence, e.g. a recording that ended on 7 s of quiet.
     * All quiet - kept as it is (quiet speech is still speech; Whisper decides).
     */
    fun trimSilence(): RecordedAudio {
        val frame = sampleRate / FRAMES_PER_SECOND * 2
        if (frame <= 0 || pcm.size < frame) return this
        val frames = pcm.size / frame
        var first = -1
        var last = -1
        for (f in 0 until frames) {
            if (dbfs(f * frame, frame) >= SPEECH_DBFS) {
                if (first < 0) first = f
                last = f
            }
        }
        if (first < 0) return this
        val margin = FRAMES_PER_SECOND * MARGIN_MS / 1000
        val from = (first - margin).coerceAtLeast(0) * frame
        val to = minOf((last + 1 + margin) * frame, pcm.size)
        if (from == 0 && to == pcm.size) return this
        return RecordedAudio(pcm.copyOfRange(from, to), sampleRate, complete)
    }

    private fun dbfs(offset: Int, length: Int): Double {
        var sum = 0.0
        var i = offset
        while (i + 1 < offset + length) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
            sum += v * v
            i += 2
        }
        val rms = sqrt(sum / (length / 2).coerceAtLeast(1))
        return 20 * log10((rms / Short.MAX_VALUE).coerceAtLeast(1e-6))
    }

    /** The same audio as a WAV file - what Whisper's upload takes, without any encoder. */
    fun toWav(): ByteArray {
        val out = ByteArray(WAV_HEADER + pcm.size)
        fun text(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun int(at: Int, v: Int) = (0..3).forEach { out[at + it] = (v ushr (8 * it)).toByte() }
        fun short(at: Int, v: Int) = (0..1).forEach { out[at + it] = (v ushr (8 * it)).toByte() }
        text(0, "RIFF"); int(4, 36 + pcm.size); text(8, "WAVE")
        text(12, "fmt "); int(16, 16); short(20, 1); short(22, 1)
        int(24, sampleRate); int(28, sampleRate * 2); short(32, 2); short(34, 16)
        text(36, "data"); int(40, pcm.size)
        pcm.copyInto(out, WAV_HEADER)
        return out
    }

    companion object {
        const val WAV_HEADER = 44
        private const val FRAMES_PER_SECOND = 25
        private const val MARGIN_MS = 600
        /** Room noise sits around -60..-50 dBFS, speech above -45 even from arm's length. */
        private const val SPEECH_DBFS = -48.0
    }
}

/** One word with its time in the recording (seconds), as Whisper gives it. */
data class TimedWord(val text: String, val start: Double, val end: Double)

/** One stretch of Whisper's text, with how sure Whisper is that it is not silence. */
data class TimedSegment(val text: String, val start: Double, val end: Double, val noSpeechProb: Double = 0.0)

/**
 * Whisper's answer as one transcript, under the same rule as the phone's recognizer ([Transcript]): a comma
 * (a new list item) where nothing was said for the pause set by the user (none when it is off). Whisper's own
 * punctuation stays.
 */
object WhisperText {

    fun build(
        text: String,
        segments: List<TimedSegment>,
        words: List<TimedWord>,
        pauseMillis: Long? = Transcript.PAUSE_MILLIS,
    ): String {
        // Stretches Whisper itself thinks are silence, and the subtitle credits it is known to invent there.
        val kept = segments.filter { it.noSpeechProb < NO_SPEECH && !invented(it.text) }
        val spoken = if (segments.isEmpty()) text.trim() else kept.joinToString(" ") { it.text.trim() }.trim()
        val heard = if (segments.isEmpty()) words else words.filter { w -> kept.any { w.start >= it.start - EDGE && w.end <= it.end + EDGE } }
        if (heard.size < 2 || pauseMillis == null) return spoken

        val pause = pauseMillis / 1000.0
        val breaks = (1 until heard.size).filter { heard[it].start - heard[it - 1].end >= pause }.toSet()
        if (breaks.isEmpty()) return spoken
        return withBreaks(spoken, heard, breaks) ?: byWords(heard, breaks)
    }

    /** Commas put into Whisper's text where the words after a pause begin; null when the words cannot be found in it. */
    private fun withBreaks(spoken: String, words: List<TimedWord>, breaks: Set<Int>): String? {
        val at = mutableListOf<Int>()
        var cursor = 0
        words.forEachIndexed { i, w ->
            val token = w.text.trim().trim { !it.isLetterOrDigit() }
            if (token.isEmpty()) return@forEachIndexed
            val idx = spoken.indexOf(token, cursor, ignoreCase = true)
            if (idx < 0) return null
            if (i in breaks) at += idx
            cursor = idx + token.length
        }
        val out = StringBuilder(spoken)
        for (pos in at.sortedDescending()) {
            var j = pos
            while (j > 0 && out[j - 1] == ' ') j--
            if (j > 0 && out[j - 1] !in PUNCTUATION) out.insert(j, ',')
        }
        return out.toString()
    }

    private fun byWords(words: List<TimedWord>, breaks: Set<Int>): String = buildString {
        words.forEachIndexed { i, w ->
            val word = w.text.trim()
            if (word.isEmpty()) return@forEachIndexed
            if (isNotEmpty()) {
                if (i in breaks && last() !in PUNCTUATION) append(',')
                append(' ')
            }
            append(word)
        }
    }

    private fun invented(text: String): Boolean = text.lowercase().let { t -> INVENTED.any { it in t } }

    private const val NO_SPEECH = 0.6
    /** Word and segment times are rounded a little differently. */
    private const val EDGE = 0.05
    private const val PUNCTUATION = ".,;:!?…"
    private val INVENTED = listOf("amara.org", "napisy stworzone", "thanks for watching", "subtitles by")
}
