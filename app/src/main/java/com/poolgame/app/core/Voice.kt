package com.poolgame.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Your recorded foul voice: cleaned up after recording (silence trimmed, level evened out,
 * short fades so it doesn't click). Shared by the Android app and the web version.
 */
object Voice {
    /** Longest recording kept, in seconds. */
    const val MAX_SECONDS = 3.0

    /** Trim the quiet before and after, normalize, and fade the ends. Returns an empty array if it's all silence. */
    fun clean(x: DoubleArray, rate: Int): DoubleArray {
        if (x.isEmpty()) return x
        // Remove any DC offset first.
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var peak = 1e-9
        for (v in x) if (abs(v - mean) > peak) peak = abs(v - mean)
        if (peak < 0.01) return DoubleArray(0)
        val th = peak * 0.06
        var a = 0
        while (a < x.size && abs(x[a] - mean) < th) a++
        var b = x.size - 1
        while (b > a && abs(x[b] - mean) < th) b--
        a = max(0, a - rate / 40); b = min(x.size - 1, b + rate / 15)
        val n = min(b - a + 1, (rate * MAX_SECONDS).toInt())
        val y = DoubleArray(n) { (x[a + it] - mean) * (0.92 / peak) }
        val fade = min(n / 4, rate / 100)
        for (i in 0 until fade) { val k = i.toDouble() / fade; y[i] *= k; y[n - 1 - i] *= k }
        return y
    }

    /** Linear resampling, e.g. to keep the web copy small. */
    fun resample(x: DoubleArray, from: Int, to: Int): DoubleArray {
        if (from == to || x.isEmpty()) return x
        val n = (x.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        return DoubleArray(n) {
            val p = it * step
            val k = p.toInt()
            val f = p - k
            val u = x[min(k, x.size - 1)]; val v = x[min(k + 1, x.size - 1)]
            u + (v - u) * f
        }
    }
}
