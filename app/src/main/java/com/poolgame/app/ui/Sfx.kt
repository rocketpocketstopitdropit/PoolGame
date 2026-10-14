package com.poolgame.app.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Small synthesized sound effects, so the app ships with no audio assets. */
class Sfx(var enabled: Boolean) {
    private val rate = 22050

    private inner class Voice(pcm: ShortArray, copies: Int) {
        private val tracks = ArrayList<AudioTrack>()
        private var next = 0
        private var last = 0L
        init {
            repeat(copies) {
                try {
                    val t = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.size * 2)
                        .build()
                    t.write(pcm, 0, pcm.size)
                    tracks += t
                } catch (_: Throwable) { }
            }
        }
        fun play(vol: Float) {
            if (!enabled || tracks.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            if (now - last < 25) return
            last = now
            val t = tracks[next]; next = (next + 1) % tracks.size
            try {
                t.stop()
                t.reloadStaticData()
                t.setVolume(vol.coerceIn(0.03f, 1f))
                t.play()
            } catch (_: Throwable) { }
        }
        fun release() { tracks.forEach { try { it.release() } catch (_: Throwable) { } } }
    }

    private fun synth(ms: Int, f: (Double) -> Double): ShortArray {
        val n = rate * ms / 1000
        return ShortArray(n) { i -> (f(i.toDouble() / rate).coerceIn(-1.0, 1.0) * 30000).toInt().toShort() }
    }

    private val rnd = Random(7)
    private val click = Voice(synth(40) { t ->
        val e = exp(-t / 0.0045)
        e * (0.55 * sin(2 * PI * 3300 * t) + 0.3 * sin(2 * PI * 5200 * t) + 0.25 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.001))
    }, 4)
    private val cushion = Voice(synth(90) { t ->
        val e = exp(-t / 0.018)
        e * (0.7 * sin(2 * PI * 160 * t) + 0.25 * sin(2 * PI * 310 * t) + 0.15 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.004))
    }, 3)
    private val pocket = Voice(synth(260) { t ->
        val e1 = exp(-t / 0.05); val t2 = (t - 0.06).coerceAtLeast(0.0); val e2 = if (t > 0.06) exp(-t2 / 0.03) else 0.0
        e1 * 0.6 * sin(2 * PI * 120 * t) + e2 * 0.5 * sin(2 * PI * 210 * t2) + 0.2 * exp(-t / 0.006) * (rnd.nextDouble() * 2 - 1)
    }, 2)
    private val cue = Voice(synth(60) { t ->
        val e = exp(-t / 0.008)
        e * (0.5 * sin(2 * PI * 950 * t) + 0.35 * sin(2 * PI * 1720 * t) + 0.2 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.0015))
    }, 2)

    fun ball(speed: Double) = click.play((speed / 3.0).toFloat())
    fun rail(speed: Double) = cushion.play((speed / 3.5).toFloat())
    fun drop() = pocket.play(0.9f)
    fun strike(speed: Double) = cue.play((0.25 + speed / 7.0).toFloat())

    fun release() { click.release(); cushion.release(); pocket.release(); cue.release() }
}
