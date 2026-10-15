package com.poolgame.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.poolgame.app.core.Voice
import kotlin.math.max

/**
 * Records your foul voice from the microphone (up to [Voice.MAX_SECONDS]), cleans it up and saves
 * it where [Sfx] picks it up. The caller must already hold the RECORD_AUDIO permission.
 */
class VoiceRecorder(private val ctx: Context) {
    private val rate = 44100
    @Volatile private var recording = false
    private var thread: Thread? = null

    val isRecording get() = recording

    /** Starts recording; [onDone] runs on the recording thread with the clip length in seconds (0 = nothing heard, -1 = failed). */
    @SuppressLint("MissingPermission")
    fun start(onDone: (Double) -> Unit) {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096) * 2)
        } catch (_: Throwable) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); onDone(-1.0); return }
        recording = true
        thread = Thread {
            val total = (rate * Voice.MAX_SECONDS).toInt()
            val pcm = ShortArray(total)
            var n = 0
            try {
                rec.startRecording()
                while (recording && n < total) {
                    val got = rec.read(pcm, n, minOf(2048, total - n))
                    if (got < 0) break
                    n += got
                }
            } catch (_: Throwable) {
            } finally {
                try { rec.stop() } catch (_: Throwable) { }
                rec.release()
                recording = false
            }
            val clip = Voice.clean(DoubleArray(n) { pcm[it] / 32768.0 }, rate)
            if (clip.isEmpty()) { onDone(0.0); return@Thread }
            try {
                Sfx.writeWav(Sfx.voiceFile(ctx), clip, rate)
                onDone(clip.size.toDouble() / rate)
            } catch (_: Throwable) { onDone(-1.0) }
        }.apply { isDaemon = true; start() }
    }

    /** Stops early (otherwise it stops by itself after a few seconds). */
    fun stop() { recording = false }
}
