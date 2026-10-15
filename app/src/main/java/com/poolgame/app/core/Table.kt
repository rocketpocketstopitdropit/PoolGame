package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.sqrt

/** Degrees to radians (the engine is plain Kotlin so it also compiles for the web version). */
fun toRad(deg: Double) = deg * PI / 180.0

/**
 * Physical constants and table geometry. Units are SI (meters, seconds).
 *
 * Coordinates: x runs along the long side (0 = head rail, left on screen), y runs down the
 * screen (0 = top rail). z points INTO the table, so "up off the cloth" is -z. All physics is
 * written against this right-handed frame.
 */
object P {
    const val R = 0.028575           // ball radius (2.25" ball)
    const val L = 2.54               // playing length (9 ft table, cushion nose to nose)
    const val W = 1.27               // playing width
    const val G = 9.81
    const val MU_SLIDE = 0.2         // ball-cloth sliding friction
    const val MU_ROLL = 0.014        // rolling resistance
    const val SPIN_DECEL = 20.0      // rad/s^2 decay of vertical-axis (side) spin
    const val E_BALL = 0.94          // ball-ball restitution
    const val MU_BALL = 0.06         // ball-ball friction (gives cut-induced and spin-induced throw)
    const val MU_CUSHION = 0.14      // ball-cushion friction (gives running/reverse english)
    const val MAX_CUE_SPEED = 8.5    // m/s, a hard break
    const val MAX_TIP = 0.5          // max tip offset as a fraction of R before a miscue
    const val E_SLATE = 0.35         // bounce off the slate when the cue drives the ball down (jump shots)
    const val MAX_ELEVATION = 60.0   // degrees
    const val RAIL_CLEAR = 0.038     // a ball whose bottom is this high clears the cushions and leaves the table

    const val HEAD_X = L * 0.25      // head string
    const val FOOT_X = L * 0.75      // foot spot x
    const val FOOT_Y = W * 0.5
}

class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double) {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
}

class Pocket(
    val cx: Double, val cy: Double,      // capture circle centre
    val capR: Double,                    // ball centre inside this circle -> it drops
    val aimX: Double, val aimY: Double,  // where an object ball should be sent (mouth centre)
    val side: Boolean,
    val tips: DoubleArray,               // the two jaw tip points (x1,y1,x2,y2)
    val tolerance: Double,               // rough lateral error allowance at the mouth (for AI)
    val hole: Boolean = false            // a cup cut into the cloth (trick shots), not a pocket in the rail
)

object Table {
    // Regulation (WPA) mouths: corner 4.5" between cushion points, side 5".
    const val CORNER = 0.081   // distance from corner to where each cushion ends (mouth = CORNER * sqrt 2)
    const val SIDE = 0.064     // half-opening of side pockets

    /** The gap table (trick shots): the second quarter of the table is cut away. */
    const val GAP_X0 = P.L * 0.25
    const val GAP_X1 = P.L * 0.5

    var segs: Array<Seg> = emptyArray()
        private set
    var pockets: Array<Pocket> = emptyArray()
        private set
    /** True while the gap table is in use (balls over the gap fall to the floor). */
    var gap = false
        private set
    /** Bumped whenever the layout changes, so screens know to redraw the table. */
    var version = 0
        private set
    private var holeKey = ""
    /** True when the cloth has holes cut in it (balls anywhere on the table may drop). */
    var hasHoles = false
        private set

    /** Radius of a hole cut into the cloth: a ball whose centre gets inside it drops. */
    const val HOLE_R = 0.058

    init { useStandard() }

    /** The regulation table: four corner pockets and two side pockets. */
    fun useStandard() {
        if (segs.isNotEmpty() && !gap) return
        val L = P.L; val W = P.W
        val s = ArrayList<Seg>()
        val p = ArrayList<Pocket>()
        // Long rails (top y=0 and bottom y=W), split by side pockets
        for (y in doubleArrayOf(0.0, W)) {
            s += Seg(CORNER, y, L / 2 - SIDE, y)
            s += Seg(L / 2 + SIDE, y, L - CORNER, y)
        }
        // Short rails
        s += Seg(0.0, CORNER, 0.0, W - CORNER)
        s += Seg(L, CORNER, L, W - CORNER)
        for ((cx0, cy0) in listOf(0.0 to 0.0, L to 0.0, 0.0 to W, L to W)) corner(s, p, cx0, cy0)
        // Side pockets
        for (y0 in doubleArrayOf(0.0, W)) {
            val dy = if (y0 == 0.0) 1.0 else -1.0
            val x1 = L / 2 - SIDE; val x2 = L / 2 + SIDE
            s += Seg(x1, y0, x1 + 0.012, y0 - dy * 0.06)
            s += Seg(x2, y0, x2 - 0.012, y0 - dy * 0.06)
            p += Pocket(
                L / 2, y0 - dy * 0.03, 0.062,
                L / 2, y0 - dy * 0.012,
                true, doubleArrayOf(x1, y0, x2, y0), 0.022
            )
        }
        segs = s.toTypedArray()
        pockets = p.toTypedArray()
        gap = false
        holeKey = ""
        hasHoles = false
        version++
    }

    /**
     * The trick-shot table: the second quarter (from the head string to the side pockets) is gone.
     * The near section keeps its two corner pockets; the far section has just its two corner pockets
     * at the far end. The edges facing the gap have no cushion: a ball that rolls off falls.
     * [holes] are cups cut into the far section's cloth (x, y), for levels that have one.
     */
    fun useGap(holes: List<DoubleArray> = emptyList(), farCorners: Boolean = true) {
        val key = holes.joinToString(";") { "${it[0]},${it[1]}" } + "|$farCorners"
        if (gap && key == holeKey) return
        val L = P.L; val W = P.W
        val s = ArrayList<Seg>()
        val p = ArrayList<Pocket>()
        for (y in doubleArrayOf(0.0, W)) {
            s += Seg(CORNER, y, GAP_X0, y)
            s += Seg(GAP_X1, y, L - CORNER, y)
        }
        s += Seg(0.0, CORNER, 0.0, W - CORNER)
        // Corner pockets: near section 0..1, far end 2..3 (unless the level fills them in); then any holes.
        for ((cx0, cy0) in listOf(0.0 to 0.0, 0.0 to W)) corner(s, p, cx0, cy0)
        if (farCorners) {
            s += Seg(L, CORNER, L, W - CORNER)
            for ((cx0, cy0) in listOf(L to 0.0, L to W)) corner(s, p, cx0, cy0)
        } else {
            // No far pockets: the cushions run right into the corners.
            for (y in doubleArrayOf(0.0, W)) s += Seg(L - CORNER, y, L, y)
            s += Seg(L, 0.0, L, W)
        }
        for (h in holes) {
            val r = HOLE_R
            p += Pocket(h[0], h[1], r, h[0], h[1], false, doubleArrayOf(h[0] - r, h[1], h[0] + r, h[1]), r - P.R, hole = true)
        }
        segs = s.toTypedArray()
        pockets = p.toTypedArray()
        gap = true
        holeKey = key
        hasHoles = holes.isNotEmpty()
        version++
    }

    private fun corner(s: ArrayList<Seg>, p: ArrayList<Pocket>, cx0: Double, cy0: Double) {
        val dx = if (cx0 < P.L - 0.01) 1.0 else -1.0   // into the table
        val dy = if (cy0 == 0.0) 1.0 else -1.0
        val hTipX = cx0 + dx * CORNER; val hTipY = cy0
        val vTipX = cx0; val vTipY = cy0 + dy * CORNER
        s += Seg(hTipX, hTipY, cx0 + dx * (CORNER - 0.03), cy0 - dy * 0.055)
        s += Seg(vTipX, vTipY, cx0 - dx * 0.055, cy0 + dy * (CORNER - 0.03))
        p += Pocket(
            cx0 - dx * 0.02, cy0 - dy * 0.02, 0.068,
            cx0 + dx * CORNER * 0.42, cy0 + dy * CORNER * 0.42,
            false, doubleArrayOf(hTipX, hTipY, vTipX, vTipY), 0.028
        )
    }

    /** True if (x, y) is over the gap, where there is no table to hold a ball up. */
    fun overGap(x: Double): Boolean = gap && x > GAP_X0 && x < GAP_X1

    /** Squared distance from point to segment, and writes the closest point into out[0..1]. */
    fun closest(sg: Seg, px: Double, py: Double, out: DoubleArray): Double {
        var t = ((px - sg.ax) * sg.dx + (py - sg.ay) * sg.dy) / sg.len2
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val cx = sg.ax + t * sg.dx
        val cy = sg.ay + t * sg.dy
        out[0] = cx; out[1] = cy
        val ex = px - cx; val ey = py - cy
        return ex * ex + ey * ey
    }

    /** Distance from point (px,py) to segment (ax,ay)-(bx,by). */
    fun pointSegDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        var t = if (l2 > 0) ((px - ax) * dx + (py - ay) * dy) / l2 else 0.0
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val ex = px - (ax + t * dx); val ey = py - (ay + t * dy)
        return sqrt(ex * ex + ey * ey)
    }
}
