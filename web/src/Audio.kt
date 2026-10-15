package com.poolgame.web

import com.poolgame.app.core.Crowd

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The same synthesized sounds as the Android version, played through Web Audio. */
class Sounds {
    var enabled = true
    private var ac: dynamic = null
    private val rate = 44100
    private val rnd = Random(7)
    private val last = DoubleArray(6)
    private var click: dynamic = null
    private var cushion: dynamic = null
    private var pocket: dynamic = null
    private var cue: dynamic = null
    private var tick: dynamic = null
    private var crowd: dynamic = null

    /** Browsers only allow audio after a user gesture, so this is called on the first touch. */
    fun unlock() {
        if (ac == null) {
            val ctor = window.AudioContext ?: window.webkitAudioContext ?: return
            ac = js("new ctor()")
            click = buffer(clack())
            cushion = buffer(gen(100) { t ->
                exp(-t / 0.02) * (0.75 * sin(2 * PI * 140 * t) + 0.25 * sin(2 * PI * 270 * t)) +
                    0.12 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.004)
            })
            pocket = buffer(gen(280) { t ->
                val t2 = (t - 0.06).coerceAtLeast(0.0)
                val e2 = if (t > 0.06) exp(-t2 / 0.035) else 0.0
                exp(-t / 0.05) * 0.6 * sin(2 * PI * 110 * t) + e2 * 0.5 * sin(2 * PI * 190 * t2) +
                    0.15 * exp(-t / 0.006) * (rnd.nextDouble() * 2 - 1)
            })
            cue = buffer(gen(70) { t ->
                exp(-t / 0.01) * (0.55 * sin(2 * PI * 700 * t) + 0.3 * sin(2 * PI * 1150 * t)) +
                    0.15 * (rnd.nextDouble() * 2 - 1) * exp(-t / 0.0015)
            })
            tick = buffer(gen(90) { t ->
                val e = if (t < 0.005) t / 0.005 else exp(-(t - 0.005) / 0.03)
                e * 0.6 * sin(2 * PI * 880 * t)
            })
            // The crowd "awww" takes a moment to synthesize, so it's made just after the first touch.
            window.setTimeout({ if (crowd == null) crowd = buffer(Crowd.aww(rate)) }, 400)
        }
        try { if (ac.state == "suspended") ac.resume() } catch (_: dynamic) { }
    }

    private fun gen(ms: Int, f: (Double) -> Double): DoubleArray {
        val n = rate * ms / 1000
        return DoubleArray(n) { f(it.toDouble() / rate) }
    }

    private fun buffer(d: DoubleArray): dynamic {
        val b = ac.createBuffer(1, d.size, rate)
        val ch = b.getChannelData(0)
        for (i in d.indices) ch[i] = d[i] * 0.85
        return b
    }

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
        x[0] += 0.25; x[1] -= 0.15; x[2] += 0.06
        val r1 = (rate * 0.011).toInt(); val r2 = (rate * 0.019).toInt()
        val y = DoubleArray(n)
        for (i in 0 until n) y[i] = x[i] + (if (i >= r1) 0.16 * x[i - r1] else 0.0) + (if (i >= r2) 0.07 * x[i - r2] else 0.0)
        var peak = 1e-9
        for (v in y) if (abs(v) > peak) peak = abs(v)
        for (i in 0 until n) y[i] *= 0.92 / peak
        return y
    }

    private fun play(slot: Int, buf: dynamic, vol: Double, gapMs: Double = 20.0) {
        if (!enabled || ac == null || buf == null) return
        val t = now()
        if (t - last[slot] < gapMs) return
        last[slot] = t
        try {
            val src = ac.createBufferSource()
            src.buffer = buf
            val g = ac.createGain()
            g.gain.value = vol.coerceIn(0.04, 1.0)
            src.connect(g); g.connect(ac.destination)
            src.start()
        } catch (_: dynamic) { }
    }

    fun ball(speed: Double) = play(0, click, speed / 3.0)
    fun rail(speed: Double) = play(1, cushion, speed / 3.5)
    fun drop() = play(2, pocket, 0.9, 40.0)
    fun strike(speed: Double) = play(3, cue, 0.25 + speed / 7.0)
    fun beep() = play(4, tick, 0.7, 200.0)
    fun aww() = play(5, crowd, 1.0, 2500.0)
}

/** Settings and win/loss record, kept in the browser's local storage. */
object Store {
    fun get(key: String, def: String): String {
        return try { (localStorage.getItem(key) as String?) ?: def } catch (_: dynamic) { def }
    }
    fun set(key: String, v: String) { try { localStorage.setItem(key, v) } catch (_: dynamic) { } }
    fun getInt(key: String, def: Int) = get(key, def.toString()).toIntOrNull() ?: def
    fun getBool(key: String, def: Boolean) = get(key, def.toString()) == "true"

    private fun key(mode: String, vsCpu: Boolean, level: Int, slot: String) =
        if (vsCpu) "rec_${mode}_cpu${level}_$slot" else "rec_${mode}_pp_$slot"

    fun record(mode: String, vsCpu: Boolean, level: Int, winner: Int) {
        val k = key(mode, vsCpu, level, if (winner == 0) "w" else "l")
        set(k, (getInt(k, 0) + 1).toString())
    }
    fun wins(mode: String, vsCpu: Boolean, level: Int) = getInt(key(mode, vsCpu, level, "w"), 0)
    fun losses(mode: String, vsCpu: Boolean, level: Int) = getInt(key(mode, vsCpu, level, "l"), 0)
    fun totalWins(mode: String) = (1..10).sumOf { wins(mode, true, it) }
    fun totalLosses(mode: String) = (1..10).sumOf { losses(mode, true, it) }
    fun resetRecord() {
        try {
            val keys = ArrayList<String>()
            val n = localStorage.length as Int
            for (i in 0 until n) { val k = localStorage.key(i) as String; if (k.startsWith("rec_")) keys += k }
            for (k in keys) localStorage.removeItem(k)
        } catch (_: dynamic) { }
    }
}
