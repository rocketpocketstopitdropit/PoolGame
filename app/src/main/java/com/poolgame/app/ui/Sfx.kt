package com.poolgame.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized sound effects (no audio assets). The sounds are rendered once to small WAV files
 * in the cache folder and played through SoundPool, which never blocks the game loop.
 */
class Sfx(context: Context, var enabled: Boolean) {
    private val rate = 22050
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val rnd = Random(7)
    private val last = LongArray(5)

    private val click: Int
    private val cushion: Int
    private val pocket: Int
    private val cue: Int
    private val tick: Int

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        // Ball-on-ball: a lower, rounder "clack" than a pure high click.
        click = load(dir, "click_v2", 70) { t ->
            val e = exp(-t / 0.009)
            e * (0.5 * sin(2 * PI * 1250 * t) + 0.3 * sin(2 * PI * 2050 * t) + 0.25 * sin(2 * PI * 620 * t)) +
                0.12 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.0012)
        }
        cushion = load(dir, "cushion_v2", 100) { t ->
            val e = exp(-t / 0.02)
            e * (0.75 * sin(2 * PI * 140 * t) + 0.25 * sin(2 * PI * 270 * t)) +
                0.12 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.004)
        }
        pocket = load(dir, "pocket_v2", 280) { t ->
            val e1 = exp(-t / 0.05)
            val t2 = (t - 0.06).coerceAtLeast(0.0)
            val e2 = if (t > 0.06) exp(-t2 / 0.035) else 0.0
            e1 * 0.6 * sin(2 * PI * 110 * t) + e2 * 0.5 * sin(2 * PI * 190 * t2) +
                0.15 * exp(-t / 0.006) * (rnd.nextDouble() * 2 - 1)
        }
        cue = load(dir, "cue_v2", 70) { t ->
            val e = exp(-t / 0.01)
            e * (0.55 * sin(2 * PI * 700 * t) + 0.3 * sin(2 * PI * 1150 * t)) +
                0.15 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.0015)
        }
        tick = load(dir, "tick_v1", 90) { t ->
            val e = if (t < 0.005) t / 0.005 else exp(-(t - 0.005) / 0.03)
            e * 0.6 * sin(2 * PI * 880 * t)
        }
    }

    private fun load(dir: File, name: String, ms: Int, f: (Double) -> Double): Int {
        val file = File(dir, "$name.wav")
        if (!file.exists()) {
            val n = rate * ms / 1000
            val buf = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray()); buf.putInt(36 + n * 2); buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
            buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
            buf.put("data".toByteArray()); buf.putInt(n * 2)
            for (i in 0 until n) {
                val v = f(i.toDouble() / rate).coerceIn(-1.0, 1.0)
                buf.putShort((v * 28000).toInt().toShort())
            }
            try { FileOutputStream(file).use { it.write(buf.array()) } } catch (_: Throwable) { return 0 }
        }
        return try { pool.load(file.path, 1) } catch (_: Throwable) { 0 }
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

    fun release() { try { pool.release() } catch (_: Throwable) { } }
}
