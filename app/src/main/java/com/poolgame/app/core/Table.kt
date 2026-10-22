package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.sqrt

/** Degrees to radians (the engine is plain Kotlin so it also compiles for the web version). */
fun toRad(deg: Double) = deg * PI / 180.0

/**
 * The table sizes you can play on, by their nominal length. Playing surface (cushion nose to nose)
 * per WPA: 7 ft 78" x 39", 8 ft 88" x 44", 9 ft 100" x 50". Balls and pockets are the same on all.
 */
enum class TableSize(val feet: Int, val length: Double, val width: Double) {
    SEVEN(7, 1.9812, 0.9906),
    EIGHT(8, 2.2352, 1.1176),
    NINE(9, 2.54, 1.27);

    val label get() = "$feet ft"

    /** One line for the menu, under the size buttons (shared by the app and the web version). */
    val description: String get() {
        val inches = "${kotlin.math.round(length / 0.0254).toInt()}″ × ${kotlin.math.round(width / 0.0254).toInt()}″"
        return when (this) {
            SEVEN -> "7 ft bar box ($inches): shorter shots, bigger balls on screen."
            EIGHT -> "8 ft home table ($inches)."
            NINE -> "9 ft tournament table ($inches): longer shots, more room to play position."
        }
    }

    companion object {
        /** New players start on the smallest table. */
        val DEFAULT = SEVEN
        fun fromName(s: String?): TableSize? = values().firstOrNull { it.name == s }
    }
}

/**
 * Physical constants and table geometry. Units are SI (meters, seconds).
 *
 * Coordinates: x runs along the long side (0 = head rail, left on screen), y runs down the
 * screen (0 = top rail). z points INTO the table, so "up off the cloth" is -z. All physics is
 * written against this right-handed frame.
 */
object P {
    const val R = 0.028575           // ball radius (2.25" ball)
    /** Playing length and width (cushion nose to nose) of the table in use: set by Table.useStandard / useGap. */
    var L = 2.54
        private set
    var W = 1.27
        private set
    const val G = 9.81
    const val MU_SLIDE = 0.2         // ball-cloth sliding friction
    const val MU_ROLL = 0.014        // rolling resistance
    const val SPIN_DECEL = 20.0      // rad/s^2 decay of vertical-axis (side) spin
    const val E_BALL = 0.94          // ball-ball restitution
    const val MU_BALL = 0.06         // ball-ball friction (gives cut-induced and spin-induced throw)
    const val MU_CUSHION = 0.14      // ball-cushion friction (gives running/reverse english)
    const val MAX_CUE_SPEED = 8.5    // m/s, a hard break
    const val MAX_TIP = 0.5          // tip offset (fraction of R) where the tip's friction runs out: a miscue past it
    const val MU_TIP = 0.5773502691896258      // tip-ball friction (tan 30°): exactly the limit at MAX_TIP
    const val TIP_LIMIT = 0.85       // furthest the tip can be put from centre (the spin ball's edge)
    const val E_SLATE = 0.35         // bounce off the slate when the cue drives the ball down (jump shots)
    const val MAX_ELEVATION = 60.0   // degrees
    const val RAIL_CLEAR = 0.038     // a ball whose bottom is this high clears the cushions and leaves the table

    val HEAD_X get() = L * 0.25      // head string
    val FOOT_X get() = L * 0.75      // foot spot x
    val FOOT_Y get() = W * 0.5

    internal fun resize(s: TableSize) { L = s.length; W = s.width }
    internal fun resize(l: Double, w: Double) { L = l; W = w }
}

/** A round bumper post (bumper pool): a rubber ring on a post, standing up off the cloth. */
class Post(val x: Double, val y: Double, val r: Double)

class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double) {
    /** Part of a pocket's jaws rather than a rail (Pocket Wars: hitting a jaw isn't a bank). */
    var jaw = false
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

    /** The gap table (trick shots, always on a 9 ft table): the second quarter of the table is cut away. */
    val GAP_X0 = TableSize.NINE.length * 0.25
    val GAP_X1 = TableSize.NINE.length * 0.5

    /** Size of the regulation table (8-ball, 9-ball, practice). The trick-shot gap table is always 9 ft. */
    var size = TableSize.NINE
        private set

    var segs: Array<Seg> = emptyArray()
        private set
    var pockets: Array<Pocket> = emptyArray()
        private set
    /** Bumper posts standing on the cloth (only the bumper pool table has them). */
    var posts: Array<Post> = emptyArray()
        private set
    /** True while the bumper pool table is in use. */
    var bumper = false
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

    /** The regulation table: four corner pockets and two side pockets, in [s] (default: the size in use). */
    fun useStandard(s: TableSize = size) {
        if (segs.isNotEmpty() && !gap && !bumper && s == size) return
        size = s
        P.resize(s)
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
        // The first six are the rails; the rest are the pockets' jaws.
        for (i in 6 until s.size) s[i].jaw = true
        segs = s.toTypedArray()
        pockets = p.toTypedArray()
        posts = emptyArray()
        gap = false
        bumper = false
        holeKey = ""
        hasHoles = false
        version++
    }

    // Bumper pool table: 47" x 31.5" playing surface, a cup cut into the cloth at each end and
    // twelve rubber bumpers — two flanking each cup, and eight in a cross in the middle (one arm
    // of the cross runs between the cups) with a small open space at its centre.
    const val BUMPER_L = 1.2
    const val BUMPER_W = 0.8
    /** Bumper pool cups: centre this far in from each end cushion, and the drop radius for a ball's centre. */
    const val CUP_IN = 0.13
    const val CUP_R = 0.05
    /** Radius of a bumper (its rubber ring) and how high it stands (a ball higher than this passes over). */
    const val POST_R = 0.025
    const val POST_H = 0.055

    /** The bumper pool table. Pocket 0 is the cup at the left end (x = 0), pocket 1 the one at the right. */
    fun useBumper() {
        if (bumper) return
        P.resize(BUMPER_L, BUMPER_W)
        val L = P.L; val W = P.W
        val cy = W / 2
        segs = arrayOf(Seg(0.0, 0.0, L, 0.0), Seg(0.0, W, L, W), Seg(0.0, 0.0, 0.0, W), Seg(L, 0.0, L, W))
        pockets = arrayOf(CUP_IN, L - CUP_IN).map { x ->
            Pocket(x, cy, CUP_R, x, cy, false, doubleArrayOf(x - CUP_R, cy, x + CUP_R, cy), CUP_R - P.R * 0.5, hole = true)
        }.toTypedArray()
        val ps = ArrayList<Post>()
        for (x in doubleArrayOf(CUP_IN, L - CUP_IN)) for (s in doubleArrayOf(-1.0, 1.0)) ps += Post(x, cy + s * 0.095, POST_R)
        val mx = L / 2
        for (d in doubleArrayOf(0.11, 0.21)) for (s in doubleArrayOf(-1.0, 1.0)) {
            ps += Post(mx + s * d, cy, POST_R)    // the arm between the cups
            ps += Post(mx, cy + s * d, POST_R)    // the arm across the table
        }
        posts = ps.toTypedArray()
        gap = false
        bumper = true
        holeKey = ""
        hasHoles = true
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
        if (gap && !bumper && key == holeKey) return
        P.resize(TableSize.NINE)
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
        posts = emptyArray()
        bumper = false
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
