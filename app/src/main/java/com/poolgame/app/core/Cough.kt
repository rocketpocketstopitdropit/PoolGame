package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A synthesized cough (an explosive burst, then breathy noise and a rough voiced "huh" through the
 * formants of an open vowel). Plays on a table foul until you record your own voice for it.
 * Shared by the Android app and the web version.
 */
object Cough {
    /** One cough. [f0] is the pitch of the voiced part, [len] its length in seconds. */
    fun cough(rate: Int, seed: Int, f0: Double = 130.0, len: Double = 0.32): DoubleArray {
        val rnd = Random(seed)
        val n = (rate * len).toInt()
        val out = DoubleArray(n)
        val r1 = Res(); val r2 = Res(); val r3 = Res()
        r3.set(2600.0, 420.0, rate)
        var phase = 0.0
        var prevNoise = 0.0
        var tilt = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val u = t / len
            if (i % 32 == 0) {
                // The vowel closes a little as the cough ends ("ah" toward "uh").
                r1.set(640.0 - 150.0 * u, 260.0, rate)
                r2.set(1250.0 - 120.0 * u, 320.0, rate)
            }
            // Explosive opening of the glottis: a very short broadband crack.
            val burst = (if (t < 0.003) t / 0.003 else exp(-(t - 0.003) / 0.012))
            // The rush of air: fast attack, long decay.
            val air = min(1.0, t / 0.005) * exp(-t / 0.085)
            // A rough, voiced "huh" that comes in just after the burst.
            val voiced = smooth(0.008, 0.035, t) * exp(-max(0.0, t - 0.035) / 0.07)
            val f = f0 * (1.0 - 0.18 * u) * (1 + 0.04 * (rnd.nextDouble() - 0.5))   // jittery, falling pitch
            phase += f / rate
            if (phase >= 1) phase -= 1.0
            val glottal = if (phase < 0.45) sin(PI * phase / 0.45).let { it * it } else 0.0
            tilt += (glottal - tilt) * 0.3
            val w = rnd.nextDouble() * 2 - 1
            val hiss = w - prevNoise * 0.6        // slightly brightened noise
            prevNoise = w
            val src = air * hiss * 0.9 + voiced * (tilt - 0.25) * 1.6 * (1 + 0.3 * w)
            out[i] = r1.step(src) * 1.0 + r2.step(src) * 0.55 + r3.step(src) * 0.25 + burst * hiss * 0.5
        }
        // Remove the resonators' DC offset and fade the very end.
        var mean = 0.0
        for (v in out) mean += v
        mean /= n
        for (i in 0 until n) out[i] -= mean
        val fade = min(n, (rate * 0.03).toInt())
        for (i in 0 until fade) out[n - 1 - i] *= i.toDouble() / fade
        normalize(out, 1.0)
        return out
    }

    /** Two coughs, a beat apart: what plays on a table foul until a voice has been recorded. */
    fun coughs(rate: Int): DoubleArray {
        val c1 = cough(rate, 3, 138.0, 0.30)
        val c2 = cough(rate, 9, 126.0, 0.36)
        val start2 = (rate * 0.24).toInt()
        val out = DoubleArray(start2 + c2.size)
        for (i in c1.indices) out[i] += c1[i] * 0.85
        for (i in c2.indices) out[start2 + i] += c2[i] * 0.9
        normalize(out, 0.9)
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun smooth(a: Double, b: Double, x: Double): Double {
        if (x <= a) return 0.0
        if (x >= b) return 1.0
        val t = (x - a) / (b - a)
        return t * t * (3 - 2 * t)
    }

    private fun normalize(x: DoubleArray, peak: Double) {
        var m = 1e-9
        for (v in x) if (abs(v) > m) m = abs(v)
        val s = peak / m
        for (i in x.indices) x[i] *= s
    }

    /** Two-pole resonator (Klatt), unity gain at DC. */
    private class Res {
        private var a = 0.0; private var b = 0.0; private var c = 0.0
        private var y1 = 0.0; private var y2 = 0.0
        fun set(f: Double, bw: Double, rate: Int) {
            val t = 1.0 / rate
            c = -exp(-2 * PI * bw * t)
            b = 2 * exp(-PI * bw * t) * cos(2 * PI * f * t)
            a = 1 - b - c
        }
        fun step(x: Double): Double {
            val y = a * x + b * y1 + c * y2
            y2 = y1; y1 = y
            return y
        }
    }
}
