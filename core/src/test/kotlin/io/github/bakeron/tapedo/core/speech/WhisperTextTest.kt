package io.github.bakeron.tapedo.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WhisperTextTest {

    private fun words(vararg w: Pair<String, Double>): List<TimedWord> = w.map { (text, start) -> TimedWord(text, start, start + 0.4) }

    @Test
    fun `a pause of two seconds becomes a comma, Whisper's own punctuation stays`() {
        val text = "Lista zakupów mleko marchewki. Jajka"
        val w = words("Lista" to 0.0, "zakupów" to 0.5, "mleko" to 3.0, "marchewki" to 6.0, "Jajka" to 9.0)
        // "marchewki." already ends with a dot - no second mark after it.
        assertEquals("Lista zakupów, mleko, marchewki. Jajka", WhisperText.build(text, emptyList(), w))
    }

    @Test
    fun `with pauses off Whisper's text stays as it is`() {
        val text = "Lista zakupów mleko marchewki"
        val w = words("Lista" to 0.0, "zakupów" to 0.5, "mleko" to 3.0, "marchewki" to 6.0)
        assertEquals(text, WhisperText.build(text, emptyList(), w, pauseMillis = null))
    }

    @Test
    fun `a short breath is not a new item`() {
        val text = "kup mleko i chleb"
        val w = words("kup" to 0.0, "mleko" to 0.5, "i" to 1.5, "chleb" to 2.0)
        assertEquals("kup mleko i chleb", WhisperText.build(text, emptyList(), w))
    }

    @Test
    fun `stretches Whisper calls silence and invented credits are dropped`() {
        val segments = listOf(
            TimedSegment(" Kup mleko.", 0.0, 1.5),
            TimedSegment(" Dziękuję za uwagę.", 2.0, 4.0, noSpeechProb = 0.9),
            TimedSegment(" Napisy stworzone przez społeczność Amara.org", 4.0, 7.0, noSpeechProb = 0.1),
        )
        val w = words("Kup" to 0.0, "mleko" to 0.6, "Dziękuję" to 2.5, "za" to 3.0, "uwagę" to 3.3)
        assertEquals("Kup mleko.", WhisperText.build("Kup mleko. Dziękuję za uwagę.", segments, w))
    }

    @Test
    fun `words that cannot be found in the text are joined by themselves`() {
        val w = words("mleko" to 0.0, "chleb" to 3.0)
        assertEquals("mleko, chleb", WhisperText.build("zupełnie inny tekst", emptyList(), w))
    }

    @Test
    fun `silence around the speech is cut, quiet speech is kept whole`() {
        val rate = 16_000
        fun tone(seconds: Double, amp: Int) = ByteArray((rate * seconds).toInt() * 2) { i ->
            if (i % 2 == 1) (if ((i / 2) % 2 == 0) amp else -amp).toByte() else 0
        }
        val pcm = tone(2.0, 0) + tone(1.0, 40) + tone(5.0, 0)
        val trimmed = RecordedAudio(pcm, rate).trimSilence()
        // 1 s of speech plus 0.6 s on each side.
        assertEquals(2.2, trimmed.pcm.size / (rate * 2.0), 0.05)

        val allQuiet = RecordedAudio(tone(3.0, 0), rate)
        assertSame(allQuiet, allQuiet.trimSilence())
    }

    @Test
    fun `the WAV header describes 16-bit mono at the recording's rate`() {
        val wav = RecordedAudio(ByteArray(10), 16_000).toWav()
        assertEquals(54, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(16_000, (wav[24].toInt() and 0xff) or ((wav[25].toInt() and 0xff) shl 8))
        assertEquals(10, wav[40].toInt())
    }
}
