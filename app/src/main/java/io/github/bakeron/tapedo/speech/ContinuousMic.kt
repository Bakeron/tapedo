package io.github.bakeron.tapedo.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Tapedo's own microphone for the recognizer (Android 13+, RecognizerIntent.EXTRA_AUDIO_SOURCE): one
 * AudioRecord from "record" to "stop", so a recognizer that ends after every pause leaves no gap - each new
 * session is fed from where the last one ended (the author's phone lost words in those gaps, 08.11.2026).
 * The audio lives only in memory (the last [BUFFER_SECONDS] s) and is never saved.
 *
 * Positions are byte offsets from the first recorded sample. Threads: one recorder, one feeder per session;
 * the public methods are called on the main thread.
 */
class ContinuousMic(
    /** Audio of the countdown is not used - the recording starts at "go". */
    private val skipMs: Long,
    private val onLevel: (Float) -> Unit,
) {
    private val lock = Object()
    private val ring = ByteArray(BUFFER_BYTES)

    // Guarded by [lock].
    private var written = 0L
    private var endPos = Long.MAX_VALUE
    private var closed = false

    /** Where the recording really starts (after the countdown); known once the first audio arrives. */
    @Volatile var startPos = 0L
        private set

    private var record: AudioRecord? = null
    private var session: Session? = null

    private class Session(val read: ParcelFileDescriptor) {
        @Volatile var pos = 0L
        @Volatile var over = false
    }

    /** Opens the microphone. False when it cannot be opened - the recognizer then listens by itself. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before any recording starts
    fun start(): Boolean {
        val calledAt = SystemClock.elapsedRealtime()
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, BYTES_PER_SECOND / 2),
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return false
        }
        record = r
        Thread({ recordLoop(r, calledAt) }, "tapedo-mic").apply { isDaemon = true }.start()
        return true
    }

    private fun recordLoop(r: AudioRecord, calledAt: Long) {
        val chunk = ByteArray(CHUNK_BYTES)
        var first = true
        try {
            while (true) {
                val n = r.read(chunk, 0, chunk.size)
                if (n < 0) break
                if (n == 0) {
                    // A stopped AudioRecord can keep returning 0 - leave so the microphone gets released.
                    if (synchronized(lock) { closed }) break
                    continue
                }
                if (first) {
                    first = false
                    // The microphone opens a moment after the tap; the countdown is counted from the tap.
                    val late = SystemClock.elapsedRealtime() - calledAt
                    startPos = bytes((skipMs - late).coerceAtLeast(0))
                }
                onLevel(levelOf(chunk, n))
                synchronized(lock) {
                    if (closed || written >= endPos) return
                    var off = 0
                    while (off < n) {
                        val at = ((written + off) % BUFFER_BYTES).toInt()
                        val len = minOf(n - off, BUFFER_BYTES - at)
                        System.arraycopy(chunk, off, ring, at, len)
                        off += len
                    }
                    written += n
                    lock.notifyAll()
                    if (written >= endPos) return
                }
            }
        } finally {
            // Released here, not in [close]: releasing while read() runs on this thread is unsafe.
            runCatching { r.stop() }
            runCatching { r.release() }
        }
    }

    /**
     * Audio for one recognizer session, from [from] on: the read end of a pipe for EXTRA_AUDIO_SOURCE.
     * Ends (EOF) at the stop position; [endSession] cuts it off earlier.
     */
    fun openSession(from: Long): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val s = Session(read)
        s.pos = maxOf(from, startPos)
        session = s
        Thread({ feed(s, write) }, "tapedo-feed").apply { isDaemon = true }.start()
        return read
    }

    private fun feed(s: Session, write: ParcelFileDescriptor) {
        val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            while (true) {
                var n = 0
                synchronized(lock) {
                    while (!s.over && !closed && s.pos < endPos && s.pos >= written) lock.wait(WAIT_MS)
                    if (s.over || closed) return
                    // The countdown's audio is skipped.
                    if (s.pos < startPos) s.pos = startPos
                    if (s.pos >= endPos) return
                    // Fallen more than the whole buffer behind (a stuck recognizer): go on from the oldest audio kept.
                    if (written - s.pos > BUFFER_BYTES) s.pos = written - BUFFER_BYTES
                    val at = (s.pos % BUFFER_BYTES).toInt()
                    n = minOf(CHUNK_BYTES.toLong(), minOf(written, endPos) - s.pos, (BUFFER_BYTES - at).toLong())
                        .coerceAtLeast(0).toInt()
                    if (n > 0) System.arraycopy(ring, at, chunk, 0, n)
                }
                // Blocks while the recognizer is behind; the recorder keeps recording into the ring meanwhile.
                if (n > 0) {
                    out.write(chunk, 0, n)
                    s.pos += n
                }
            }
        } catch (e: IOException) {
            // The recognizer closed its end - the session is over.
        } finally {
            runCatching { out.close() }
        }
    }

    /** How far the current session was fed - where the recognizer is now, give or take the pipe. */
    fun fedPos(): Long = session?.pos ?: startPos

    /** Ends the current session's feeding; its pipe is closed. */
    fun endSession() {
        val s = session ?: return
        session = null
        s.over = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { s.read.close() }
    }

    /** "Stop": the microphone records [tailMs] more, then every session gets EOF there. */
    fun stopAfter(tailMs: Long) {
        synchronized(lock) {
            // Stopped during the countdown: nothing to record.
            endPos = if (written < startPos) startPos else written + bytes(tailMs)
            lock.notifyAll()
        }
    }

    /** Audio recorded after [pos] that no session has heard yet, once the microphone is off. */
    fun leftAfter(pos: Long): Long = synchronized(lock) { ms(minOf(written, endPos) - pos) }

    fun stopPos(): Long = synchronized(lock) { endPos }

    fun close() {
        endSession()
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        // Wakes the recorder thread's read(); that thread then releases the microphone.
        record?.let { r -> runCatching { r.stop() } }
        record = null
    }

    private fun levelOf(chunk: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val v = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort().toDouble()
            sum += v * v
            i += 2
        }
        val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
        val db = 20 * log10((rms / Short.MAX_VALUE).coerceAtLeast(1e-6))
        // Speech is roughly -50..-10 dBFS.
        return ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val RATE = 16_000
        private const val BYTES_PER_SECOND = RATE * 2
        private const val BUFFER_SECONDS = 30
        private const val BUFFER_BYTES = BYTES_PER_SECOND * BUFFER_SECONDS

        /** 40 ms - the seismograph gets a level 25 times a second. */
        private const val CHUNK_BYTES = BYTES_PER_SECOND / 25
        private const val WAIT_MS = 100L

        fun bytes(ms: Long): Long = ms * BYTES_PER_SECOND / 1000 / 2 * 2
        fun ms(bytes: Long): Long = bytes * 1000 / BYTES_PER_SECOND
    }
}
