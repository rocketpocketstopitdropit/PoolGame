package com.poolgame.web

import com.poolgame.app.core.GameSave
import com.poolgame.app.core.GameSettings
import com.poolgame.app.core.Match
import com.poolgame.app.core.NB
import com.poolgame.app.core.P
import com.poolgame.app.core.World
import com.poolgame.app.core.WorldListener
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole game state as text, for online play: the saved-game format plus what a save leaves out
 * because it's only needed mid-game (the winner, whose turn, the ball being shot, a called pocket and
 * any opening shots already set).
 */
object NetState {
    fun write(m: Match, s: GameSettings): String {
        val sb = StringBuilder(GameSave.write(m, s))
        sb.append("win=").append(m.winner).append('\n')
        sb.append("cur=").append(m.current).append('\n')
        sb.append("cue=").append(m.world.cue).append('\n')
        sb.append("called=").append(m.calledPocket).append('\n')
        for (p in 0..3) sb.append("op$p=").append(m.opening[p]?.joinToString(",") ?: "-").append('\n')
        return sb.toString()
    }

    /**
     * Load [text] into [m]. With [keepSpin] the balls keep the orientation they have now (it's only for
     * drawing, and every phone works its own out as the balls roll, so numbers don't jump at the end of a shot).
     */
    fun apply(text: String, m: Match, keepSpin: Boolean): Boolean {
        val q = DoubleArray(NB * 4)
        for (b in m.world.balls) { q[b.id * 4] = b.qw; q[b.id * 4 + 1] = b.qx; q[b.id * 4 + 2] = b.qy; q[b.id * 4 + 3] = b.qz }
        if (!GameSave.restore(text, m)) { console.error("NetState: couldn't load the state"); return false }
        val p = HashMap<String, String>()
        for (line in text.split('\n')) { val i = line.indexOf('='); if (i > 0) p[line.substring(0, i)] = line.substring(i + 1) }
        p["win"]?.toIntOrNull()?.let { m.winner = it }
        p["cur"]?.toIntOrNull()?.let { m.current = it }
        p["cue"]?.toIntOrNull()?.let { m.world.cue = it }
        p["called"]?.toIntOrNull()?.let { m.calledPocket = it }
        for (k in 0..3) {
            val o = p["op$k"] ?: continue
            m.opening[k] = if (o == "-") null else o.split(',').map { it.toDouble() }.toDoubleArray()
        }
        if (keepSpin) for (b in m.world.balls) { b.qw = q[b.id * 4]; b.qx = q[b.id * 4 + 1]; b.qy = q[b.id * 4 + 2]; b.qz = q[b.id * 4 + 3] }
        return true
    }
}

/**
 * Runs a shot that's just been struck to the end, recording it 60 times a second so other phones can play
 * it back exactly. Positions are kept to 0.1 mm; after the first frame (every ball on the table) each frame
 * lists only the balls that moved, as a change from the last. Sounds and balls dropping are events.
 *
 * Frames: `|`-separated; in each, `id,x,y[,z];` (z only when off the cloth). Events: `tick,kind,value;` with
 * kind h (balls touched; value speed ×100), c (cushion), p (pocketed; value ball id), o (off the table).
 */
object ShotRecorder {
    const val FPS = 60

    class Recording(val frames: String, val events: String)

    fun record(w: World): Recording {
        val ev = StringBuilder()
        var t = 0
        val old = w.listener
        w.listener = object : WorldListener {
            override fun onBallHit(speed: Double) { ev.append(t).append(",h,").append(round(speed * 100).toInt()).append(';') }
            override fun onCushion(speed: Double) { ev.append(t).append(",c,").append(round(speed * 100).toInt()).append(';') }
            override fun onPocket(id: Int) { ev.append(t).append(",p,").append(id).append(';') }
            override fun onOffTable(id: Int) { ev.append(t).append(",o,").append(id).append(';') }
        }
        val lx = IntArray(NB); val ly = IntArray(NB); val lz = IntArray(NB)
        val fr = StringBuilder()
        for (b in w.balls) if (b.onTable) {
            lx[b.id] = qn(b.x); ly[b.id] = qn(b.y); lz[b.id] = qn(b.z)
            fr.append(b.id).append(',').append(lx[b.id]).append(',').append(ly[b.id])
            if (lz[b.id] != 0) fr.append(',').append(lz[b.id])
            fr.append(';')
        }
        try {
            while (w.anyMoving() && t < FPS * 60) {
                t++
                w.advance(1.0 / FPS)
                fr.append('|')
                for (b in w.balls) {
                    if (!b.onTable) continue
                    val x = qn(b.x); val y = qn(b.y); val z = qn(b.z)
                    if (x == lx[b.id] && y == ly[b.id] && z == lz[b.id]) continue
                    fr.append(b.id).append(',').append(x - lx[b.id]).append(',').append(y - ly[b.id])
                    if (z != 0) fr.append(',').append(z)
                    fr.append(';')
                    lx[b.id] = x; ly[b.id] = y; lz[b.id] = z
                }
            }
            if (w.anyMoving()) for (b in w.balls) if (b.onTable) b.stop()
        } finally { w.listener = old }
        return Recording(fr.toString(), ev.toString())
    }

    fun qn(v: Double): Int = round(v * 1e4).toInt()
}

/** A recorded shot being played back (see [ShotRecorder]). */
class Replay(frames: String, events: String) {
    val n: Int
    private val px: DoubleArray
    private val py: DoubleArray
    private val pz: DoubleArray
    /** Each frame's events, as [kind, value] pairs. */
    private val ev = HashMap<Int, MutableList<IntArray>>()
    var t = 0.0
    private var done = -1
    private val lastX = DoubleArray(NB); private val lastY = DoubleArray(NB)

    init {
        val fs = frames.split('|')
        n = fs.size
        px = DoubleArray(n * NB); py = DoubleArray(n * NB); pz = DoubleArray(n * NB)
        val cx = IntArray(NB); val cy = IntArray(NB); val cz = IntArray(NB)
        for ((k, f) in fs.withIndex()) {
            for (tok in f.split(';')) {
                if (tok.isEmpty()) continue
                val v = tok.split(',')
                val id = v[0].toInt()
                if (id !in 0 until NB) continue
                if (k == 0) { cx[id] = v[1].toInt(); cy[id] = v[2].toInt() }
                else { cx[id] += v[1].toInt(); cy[id] += v[2].toInt() }
                cz[id] = if (v.size > 3) v[3].toInt() else 0
            }
            for (id in 0 until NB) { px[k * NB + id] = cx[id] / 1e4; py[k * NB + id] = cy[id] / 1e4; pz[k * NB + id] = cz[id] / 1e4 }
        }
        for (tok in events.split(';')) {
            if (tok.isEmpty()) continue
            val v = tok.split(',')
            if (v.size < 3) continue
            val kind = when (v[1]) { "h" -> 0; "c" -> 1; "p" -> 2; "o" -> 3; else -> continue }
            ev.getOrPut(v[0].toInt()) { ArrayList() } += intArrayOf(kind, v[2].toInt())
        }
    }

    val finished get() = done >= n - 1

    /**
     * Move on by [dt] seconds: put the balls where they are now in [w] (in between frames for smoothness),
     * roll them, and report each frame's events through [l] as it's passed. The balls that drop are taken off
     * the table here.
     */
    fun advance(dt: Double, w: World, l: WorldListener?) {
        if (done < 0) { done = 0; for (b in w.balls) { lastX[b.id] = b.x; lastY[b.id] = b.y }; place(w, 0, 0.0) }
        t += dt
        val f = t * ShotRecorder.FPS
        val k = floor(f).toInt().coerceAtMost(n - 1)
        while (done < k) {
            done++
            place(w, done, 0.0)
            val es = ev[done] ?: continue
            for (e in es) when (e[0]) {
                0 -> l?.onBallHit(e[1] / 100.0)
                1 -> l?.onCushion(e[1] / 100.0)
                2 -> { l?.onPocket(e[1]); w.balls[e[1]].let { it.onTable = false; it.stop() } }
                else -> { l?.onOffTable(e[1]); w.balls[e[1]].let { it.onTable = false; it.stop() } }
            }
        }
        if (k < n - 1) place(w, k, f - k)
        roll(w)
    }

    /** Jump to the end (another act came in, or the phone fell behind). */
    fun finish(w: World) {
        if (done < 0) done = 0
        while (done < n - 1) {
            done++
            for (e in ev[done] ?: emptyList<IntArray>()) if (e[0] >= 2) w.balls[e[1]].let { it.onTable = false; it.stop() }
        }
        place(w, n - 1, 0.0)
    }

    private fun place(w: World, k: Int, frac: Double) {
        val k2 = (k + 1).coerceAtMost(n - 1)
        for (b in w.balls) {
            if (!b.onTable) continue
            val i = k * NB + b.id; val j = k2 * NB + b.id
            b.x = px[i] + (px[j] - px[i]) * frac
            b.y = py[i] + (py[j] - py[i]) * frac
            b.z = pz[i] + (pz[j] - pz[i]) * frac
        }
    }

    /** Turn each ball as if it rolled from where it was drawn last to where it is now. */
    private fun roll(w: World) {
        for (b in w.balls) {
            val dx = b.x - lastX[b.id]; val dy = b.y - lastY[b.id]
            lastX[b.id] = b.x; lastY[b.id] = b.y
            if (!b.onTable) continue
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1e-7 || d > 0.3) continue
            // Rolling: spin axis (dy, -dx, 0), angle d / R; q' = (cos, axis·sin) ⊗ q.
            val h = d / P.R / 2
            val s = sin(h) / d
            val aw = cos(h); val ax = dy * s; val ay = -dx * s
            val qw = b.qw; val qx = b.qx; val qy = b.qy; val qz = b.qz
            val nw = aw * qw - ax * qx - ay * qy
            val nx = aw * qx + ax * qw + ay * qz
            val ny = aw * qy + ay * qw - ax * qz
            val nz = aw * qz + ax * qy - ay * qx
            val m = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
            b.qw = nw / m; b.qx = nx / m; b.qy = ny / m; b.qz = nz / m
        }
    }
}
