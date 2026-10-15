package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A synthesized crowd going "awww" (played on a scratch or a ball off the table). No audio
 * assets: about three dozen voices, each a glottal pulse train with a falling pitch, shaped into
 * an "aw" vowel by three formant resonators, plus a breathy murmur and a little room reverb.
 * Shared by the Android app and the web version.
 */
object Crowd {
    fun aww(rate: Int, seed: Int = 11): DoubleArray {
        val rnd = Random(seed)
        val n = (rate * 2.0).toInt()
        val out = DoubleArray(n)

        repeat(36) {
            val female = rnd.nextDouble() < 0.45
            val f0 = if (female) rnd.nextDouble(195.0, 275.0) else rnd.nextDouble(100.0, 150.0)
            var start = rnd.nextDouble(0.0, 0.18)
            if (rnd.nextDouble() < 0.25) start += rnd.nextDouble(0.08, 0.3)     // a few stragglers
            val len = rnd.nextDouble(1.0, 1.6)
            val rise = rnd.nextDouble(0.02, 0.08)       // "aw-" lifts a touch…
            val fall = rnd.nextDouble(0.2, 0.36)        // …then the long sympathetic fall
            val vibHz = rnd.nextDouble(4.5, 6.5)
            val vibDepth = rnd.nextDouble(0.004, 0.012)
            val k = if (female) rnd.nextDouble(1.1, 1.2) else rnd.nextDouble(0.95, 1.04)
            val f1 = 600.0 * k * rnd.nextDouble(0.93, 1.07)
            val f2 = 910.0 * k * rnd.nextDouble(0.93, 1.07)
            val f3 = 2500.0 * k * rnd.nextDouble(0.95, 1.05)
            val breath = rnd.nextDouble(0.06, 0.16)
            val attack = rnd.nextDouble(0.08, 0.18)
            val gain = rnd.nextDouble(0.55, 1.0) * (if (female) 0.8 else 1.0)

            val s0 = (start * rate).toInt()
            val s1 = minOf(n, s0 + (len * rate).toInt())
            var phase = rnd.nextDouble()
            var tilt = 0.0
            var drift = 0.0
            var prev = 0.0
            val r1 = Res(); val r2 = Res(); val r3 = Res()
            r3.set(f3, 170.0, rate)
            for (i in s0 until s1) {
                val t = (i - s0).toDouble() / rate
                val u = t / len
                if ((i - s0) % 64 == 0) {
                    drift = (drift + (rnd.nextDouble() - 0.5) * 0.004).coerceIn(-0.02, 0.02)
                    // The vowel rounds a little as it trails off (aw -> oh).
                    r1.set(f1 * (1 - 0.12 * u), 90.0, rate)
                    r2.set(f2 * (1 - 0.15 * u), 110.0, rate)
                }
                val contour = 1 + rise * smooth(0.0, 0.22, u) - (rise + fall) * smooth(0.18, 1.0, u)
                val f = f0 * contour * (1 + vibDepth * sin(2 * PI * vibHz * t) + drift)
                val dt = f / rate
                phase += dt
                if (phase >= 1) phase -= 1.0
                // Band-limited sawtooth (polyBLEP), then a gentle low-pass: roughly a glottal pulse.
                var saw = 2 * phase - 1
                saw -= blep(phase, dt)
                tilt += (saw - tilt) * 0.22
                val src = tilt + breath * (rnd.nextDouble() * 2 - 1)
                val v = r3.step(r2.step(r1.step(src)))
                val y = v - 0.96 * prev       // lip radiation (+6 dB/octave)
                prev = v
                val env = smooth(0.0, attack / len, u) * (1 - smooth(0.42, 1.0, u)).let { it * it }
                out[i] += gain * env * y
            }
        }

        // A soft murmur under the voices (breath and shuffling), swelling with the crowd.
        val m1 = Res(); m1.set(700.0, 600.0, rate)
        val m2 = Res(); m2.set(1500.0, 900.0, rate)
        for (i in 0 until n) {
            val u = i.toDouble() / n
            val e = smooth(0.0, 0.08, u) * (1 - smooth(0.25, 0.85, u))
            val w = rnd.nextDouble() * 2 - 1
            out[i] += e * (0.9 * m1.step(w) + 0.4 * m2.step(w)) * 0.25
        }

        normalize(out, 0.8)
        val wet = reverb(out, rate)
        for (i in 0 until n) out[i] = out[i] * 0.8 + wet[i] * 0.35
        // Fade the reverb tail out cleanly.
        val fade = (rate * 0.25).toInt()
        for (i in 0 until fade) out[n - 1 - i] *= i.toDouble() / fade
        normalize(out, 0.9)
        return out
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        if (x <= a) return 0.0
        if (x >= b) return 1.0
        val t = (x - a) / (b - a)
        return t * t * (3 - 2 * t)
    }

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1 }
        t > 1 - dt -> { val x = (t - 1) / dt; x * x + x + x + 1 }
        else -> 0.0
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

    /** Small Schroeder reverb: four damped combs into two all-passes. */
    private fun reverb(x: DoubleArray, rate: Int): DoubleArray {
        val n = x.size
        val wet = DoubleArray(n)
        for (ms in doubleArrayOf(29.7, 37.1, 41.1, 43.7)) {
            val d = (rate * ms / 1000).toInt()
            val buf = DoubleArray(d)
            var idx = 0
            var lp = 0.0
            for (i in 0 until n) {
                val o = buf[idx]
                lp = o * 0.6 + lp * 0.4
                buf[idx] = x[i] + lp * 0.8
                idx = (idx + 1) % d
                wet[i] += o * 0.25
            }
        }
        var y = wet
        for (ms in doubleArrayOf(5.0, 1.7)) {
            val d = (rate * ms / 1000).toInt()
            val buf = DoubleArray(d)
            var idx = 0
            val g = 0.7
            val z = DoubleArray(n)
            for (i in 0 until n) {
                val bo = buf[idx]
                val v = y[i] + g * bo
                buf[idx] = v
                z[i] = bo - g * v
                idx = (idx + 1) % d
            }
            y = z
        }
        return y
    }
}
