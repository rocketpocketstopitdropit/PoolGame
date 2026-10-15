package com.poolgame.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.poolgame.app.core.Cough
import com.poolgame.app.core.Crowd
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized sound effects (no audio assets). The sounds are rendered once to small WAV files
 * in the cache folder and played through SoundPool, which never blocks the game loop.
 */
class Sfx(context: Context, var enabled: Boolean) {
    private val rate = 44100
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val rnd = Random(7)
    private val last = LongArray(7)

    private val click: Int
    private val cushion: Int
    private val pocket: Int
    private val cue: Int
    private val tick: Int
    private val crowd: Int
    private val heckleId: Int

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        // Ball-on-ball: real collisions are a very short, broadband impulse (not a pitched tone),
        // so this is filtered noise centred in the upper mids, a tiny wooden "knock", a sharp
        // initial crack, and two faint early reflections for the room.
        click = loadPcm(dir, "click_v4", clack())
        cushion = load(dir, "cushion_v3", 100) { t ->
            val e = exp(-t / 0.02)
            e * (0.75 * sin(2 * PI * 140 * t) + 0.25 * sin(2 * PI * 270 * t)) +
                0.12 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.004)
        }
        pocket = load(dir, "pocket_v3", 280) { t ->
            val e1 = exp(-t / 0.05)
            val t2 = (t - 0.06).coerceAtLeast(0.0)
            val e2 = if (t > 0.06) exp(-t2 / 0.035) else 0.0
            e1 * 0.6 * sin(2 * PI * 110 * t) + e2 * 0.5 * sin(2 * PI * 190 * t2) +
                0.15 * exp(-t / 0.006) * (rnd.nextDouble() * 2 - 1)
        }
        cue = load(dir, "cue_v3", 70) { t ->
            val e = exp(-t / 0.01)
            e * (0.55 * sin(2 * PI * 700 * t) + 0.3 * sin(2 * PI * 1150 * t)) +
                0.15 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.0015)
        }
        tick = load(dir, "tick_v2", 90) { t ->
            val e = if (t < 0.005) t / 0.005 else exp(-(t - 0.005) / 0.03)
            e * 0.6 * sin(2 * PI * 880 * t)
        }
        // Crowd "awww" on a scratch: synthesized once (it takes a moment), then cached.
        crowd = if (File(dir, "aww_v1.wav").exists()) loadPcm(dir, "aww_v1", DoubleArray(0))
            else loadPcm(dir, "aww_v1", Crowd.aww(rate))
        // Table fouls: your recorded voice if you've made one in the menu, otherwise two coughs.
        val mine = voiceFile(context)
        heckleId = if (mine.exists()) try { pool.load(mine.path, 1) } catch (_: Throwable) { 0 }
            else loadPcm(dir, "coughs_v2", Cough.coughs(rate))
    }

    private fun load(dir: File, name: String, ms: Int, f: (Double) -> Double): Int {
        val file = File(dir, "$name.wav")
        if (file.exists()) return try { pool.load(file.path, 1) } catch (_: Throwable) { 0 }
        val n = rate * ms / 1000
        return loadPcm(dir, name, DoubleArray(n) { f(it.toDouble() / rate) })
    }

    private fun loadPcm(dir: File, name: String, data: DoubleArray): Int {
        val file = File(dir, "$name.wav")
        if (!file.exists()) {
            val n = data.size
            val buf = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray()); buf.putInt(36 + n * 2); buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
            buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
            buf.put("data".toByteArray()); buf.putInt(n * 2)
            for (v in data) buf.putShort((v.coerceIn(-1.0, 1.0) * 28000).toInt().toShort())
            try { FileOutputStream(file).use { it.write(buf.array()) } } catch (_: Throwable) { return 0 }
        }
        return try { pool.load(file.path, 1) } catch (_: Throwable) { 0 }
    }

    /** Band-pass filter (RBJ biquad, 0 dB peak). */
    private fun bandPass(x: DoubleArray, f: Double, q: Double): DoubleArray {
        val w0 = 2 * PI * f / rate
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        val b0 = alpha / a0; val b2 = -alpha / a0
        val a1 = -2 * cos(w0) / a0; val a2 = (1 - alpha) / a0
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val v = b0 * x[i] + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v
            y[i] = v
        }
        return y
    }

    private fun clack(): DoubleArray {
        val n = (rate * 0.07).toInt()
        val hiIn = DoubleArray(n); val midIn = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val w = rnd.nextDouble() * 2 - 1
            hiIn[i] = w * exp(-t / 0.0028)
            midIn[i] = w * exp(-t / 0.0050)
        }
        val hi = bandPass(hiIn, 2200.0, 1.4)
        val mid = bandPass(midIn, 1000.0, 1.8)
        val x = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            x[i] = 0.6 * hi[i] + 1.3 * mid[i] + 0.22 * sin(2 * PI * 800 * t) * exp(-t / 0.004)
        }
        x[0] += 0.25; if (n > 2) { x[1] -= 0.15; x[2] += 0.06 }   // the initial crack
        val r1 = (rate * 0.011).toInt(); val r2 = (rate * 0.019).toInt()
        val y = DoubleArray(n)
        for (i in 0 until n) {
            y[i] = x[i] + (if (i >= r1) 0.16 * x[i - r1] else 0.0) + (if (i >= r2) 0.07 * x[i - r2] else 0.0)
        }
        val peak = y.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        for (i in 0 until n) y[i] *= 0.92 / peak
        return y
    }

    private fun play(slot: Int, id: Int, vol: Float, gapMs: Long = 20) {
        if (!enabled || id == 0) return
        val now = SystemClock.uptimeMillis()
        if (now - last[slot] < gapMs) return
        last[slot] = now
        val v = vol.coerceIn(0.04f, 1f)
        pool.play(id, v, v, 1, 0, 1f)
    }

    fun ball(speed: Double) = play(0, click, (speed / 3.0).toFloat())
    fun rail(speed: Double) = play(1, cushion, (speed / 3.5).toFloat())
    fun drop() = play(2, pocket, 0.9f, 40)
    fun strike(speed: Double) = play(3, cue, (0.25 + speed / 7.0).toFloat())
    fun beep() = play(4, tick, 0.7f, 200)
    fun aww() = play(5, crowd, 1f, 2500)
    fun heckle() = play(6, heckleId, 1f, 2500)

    fun release() { try { pool.release() } catch (_: Throwable) { } }

    companion object {
        /** Where the menu's recorder keeps your foul voice. */
        fun voiceFile(ctx: Context) = File(ctx.filesDir, "foul_voice.wav")

        /** Writes mono samples (-1..1) as a 16-bit WAV file. */
        fun writeWav(file: File, data: DoubleArray, rate: Int) {
            val n = data.size
            val buf = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray()); buf.putInt(36 + n * 2); buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
            buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
            buf.put("data".toByteArray()); buf.putInt(n * 2)
            for (v in data) buf.putShort((v.coerceIn(-1.0, 1.0) * 32000).toInt().toShort())
            FileOutputStream(file).use { it.write(buf.array()) }
        }
    }
}
