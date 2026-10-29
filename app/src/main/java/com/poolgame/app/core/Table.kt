package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.hypot
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
    /** True while the Gauntlet (the square four-player bumper table) is in use. */
    var gauntlet = false
        private set
    /** The ordinary table is in use for one of the classic trick shots (so a trick match's copies keep it). */
    var trickClassic = false
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
        trickClassic = false
        if (segs.isNotEmpty() && !gap && !bumper && !octagon && !gauntlet && s == size) return
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
        octagon = false
        gauntlet = false
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
        gauntlet = false
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
        octagon = false
        gauntlet = false
        holeKey = ""
        hasHoles = true
        version++
    }

    // ------------------------------------------------------------------ octagon bumper table
    //
    // A bigger bumper table, octagon-shaped, with four cups (one at each end and one on each side)
    // and no cushions: the cloth runs up a 45-degree ramp, 6" high, on every side, with a hard corner
    // at its foot (a ball thumps into it rather than gliding up). Above the ramp is a 4" wooden ledge tilted a little outward,
    // so a ball that makes it over the top rolls off the table. The bumpers are the same as on the
    // small table; three layouts to pick from.
    //
    // The ramps are described by one profile across them: the height of the surface at a distance d
    // outside the octagon's edge (d = 0 is the foot of the straight slope, extended). Distance from
    // the octagon is measured to its nearest side (the largest of the eight edge distances), which
    // makes the ramps meet in straight creases at the corners.

    /** True while the octagon bumper table is in use. */
    var octagon = false
        private set
    /** The octagon table's bumper layout: 0 Scatter, 1 Chevrons, 2 Goal gates ([OCT_LAYOUTS]). */
    var octLayout = 0
        private set

    val OCT_LAYOUTS = arrayOf("Scatter", "Chevrons", "Goal gates")

    /** Half the octagon's length and width (the cloth, ramp foot to ramp foot: 70" x 63"). */
    const val OCT_HX = 0.889
    const val OCT_HY = 0.8001
    /** Half-lengths of the end sides (across the long axis) and the long sides: 22" and 34". */
    const val OCT_END = 0.2794
    const val OCT_SIDE = 0.4318
    /** The ramps: 45 degrees, 6" high (so 6" deep), rising straight from the cloth with a hard corner at the foot. */
    const val RAMP_H = 0.1524
    /** The wooden ledge above the ramps: 4" wide, sloping down and outward by this angle. */
    const val LEDGE_W = 0.1016
    const val LEDGE_TILT_DEG = 5.0
    /** From the octagon's edge to the outside of the ledge. */
    const val OCT_OUTER = RAMP_H + LEDGE_W
    /**
     * Where a ball first touches the ramp: its centre is R(sqrt 2 - 1) inside the foot then. A ball whose centre
     * is no further out than this sits on the flat.
     */
    val FLAT_D = -P.R * (sqrt(2.0) - 1)

    // Outward unit normal (x, y) and offset of each of the eight sides: a point's distance outside
    // side i is octNx[i] * x + octNy[i] * y - octC[i] (table coordinates).
    private val octNx = DoubleArray(8)
    private val octNy = DoubleArray(8)
    private val octC = DoubleArray(8)
    fun octNx(i: Int) = octNx[i]
    fun octNy(i: Int) = octNy[i]
    /** How far (x, y) is outside side [i] of the octagon (negative inside it). */
    fun octSide(i: Int, x: Double, y: Double) = octNx[i] * x + octNy[i] * y - octC[i]

    /** How far (x, y) is outside the octagon's edge (negative inside). */
    fun octDist(x: Double, y: Double): Double {
        var best = -Double.MAX_VALUE
        for (i in 0 until 8) {
            val d = octNx[i] * x + octNy[i] * y - octC[i]
            if (d > best) best = d
        }
        return best
    }

    /** The side of the octagon (x, y) is nearest the outside of: the one [octDist] measures from. */
    fun octEdge(x: Double, y: Double): Int {
        var best = -Double.MAX_VALUE; var k = 0
        for (i in 0 until 8) {
            val d = octNx[i] * x + octNy[i] * y - octC[i]
            if (d > best) { best = d; k = i }
        }
        return k
    }

    /** A point on the octagon offset outward by [d] (its corner [i], 0..7, as x into out[0], y into out[1]). */
    fun octCorner(i: Int, d: Double, out: DoubleArray) {
        val a = (i + 7) % 8; val b = i
        // Corner where sides a and b, each moved out by d, meet.
        val n1x = octNx[a]; val n1y = octNy[a]; val c1 = octC[a] + d
        val n2x = octNx[b]; val n2y = octNy[b]; val c2 = octC[b] + d
        val det = n1x * n2y - n1y * n2x
        out[0] = (c1 * n2y - n1y * c2) / det
        out[1] = (n1x * c2 - c1 * n2x) / det
    }

    /** Height of the ramp profile's surface (not the ball) at distance [d] outside the edge, or -infinity past the ledge. */
    fun profileHeight(d: Double): Double {
        return when {
            d < 0.0 -> 0.0
            d <= RAMP_H -> d
            d <= OCT_OUTER -> RAMP_H - (d - RAMP_H) * kotlin.math.tan(toRad(LEDGE_TILT_DEG))
            else -> Double.NEGATIVE_INFINITY
        }
    }

    // The ball's resting height (its bottom above the cloth) against its centre's distance outside the
    // edge, worked out once: the highest point the ball would touch anywhere within its radius.
    private const val PROF_D0 = -0.04
    private const val PROF_STEP = 0.0005
    private val prof: DoubleArray by lazy {
        val r = P.R
        val n = ((OCT_OUTER + r - PROF_D0) / PROF_STEP).toInt() + 2
        val out = DoubleArray(n)
        val ds = 0.0002
        for (k in 0 until n) {
            val dc = PROF_D0 + k * PROF_STEP
            var best = Double.NEGATIVE_INFINITY
            fun touch(d: Double) {
                val s = profileHeight(d)
                if (s == Double.NEGATIVE_INFINITY) return
                val off = d - dc
                val h = s + sqrt((r * r - off * off).coerceAtLeast(0.0))
                if (h > best) best = h
            }
            var d = dc - r
            while (d <= dc + r) { touch(d); d += ds }
            touch(dc + r)
            if (0.0 > dc - r && 0.0 < dc + r) touch(0.0)
            if (RAMP_H > dc - r && RAMP_H < dc + r) touch(RAMP_H)
            if (OCT_OUTER > dc - r && OCT_OUTER < dc + r) touch(OCT_OUTER)
            out[k] = if (best == Double.NEGATIVE_INFINITY) best else (best - r).let { if (it < 1e-7) 0.0 else it }
        }
        out
    }

    /**
     * Where the bottom of a ball rests (height above the cloth) when its centre is [d] outside the
     * octagon's edge: 0 on the flat, rising up the ramp, or -infinity off the outside of the ledge.
     */
    fun surfZ(d: Double): Double {
        if (d <= FLAT_D) return 0.0
        val f = (d - PROF_D0) / PROF_STEP
        val i = f.toInt()
        val p = prof
        if (i + 1 >= p.size) return Double.NEGATIVE_INFINITY
        val a = p[i]; val b = p[i + 1]
        if (a == Double.NEGATIVE_INFINITY || b == Double.NEGATIVE_INFINITY) return Double.NEGATIVE_INFINITY
        return a + (b - a) * (f - i)
    }

    /** How steeply [surfZ] rises with [d] (0 on the flat, 1 on the 45-degree slope). */
    fun surfSlope(d: Double): Double {
        if (d <= FLAT_D) return 0.0
        val i = ((d - PROF_D0) / PROF_STEP).toInt()
        val p = prof
        if (i + 1 >= p.size || p[i + 1] == Double.NEGATIVE_INFINITY) return 0.0
        return (p[i + 1] - p[i]) / PROF_STEP
    }

    /** Height of a resting ball's bottom at (x, y): 0 everywhere but the octagon table's ramps and the Gauntlet's. */
    fun surfaceZ(x: Double, y: Double): Double {
        if (gauntlet) return Gauntlet.rampSurface(x, y)
        if (!octagon) return 0.0
        val z = surfZ(octDist(x, y))
        return if (z == Double.NEGATIVE_INFINITY) 0.0 else z
    }

    /** The octagon bumper table with bumper layout [layout] (0..2). */
    fun useOctagon(layout: Int = octLayout) {
        val lay = layout.coerceIn(0, OCT_LAYOUTS.size - 1)
        if (octagon && lay == octLayout) return
        val hx = OCT_HX + OCT_OUTER; val hy = OCT_HY + OCT_OUTER
        P.resize(2 * hx, 2 * hy)
        val mx = hx; val my = hy
        val vx = OCT_VX; val vy = OCT_VY
        for (i in 0 until 8) {
            val j = (i + 1) % 8
            val tx = vx[j] - vx[i]; val ty = vy[j] - vy[i]
            var nx = ty; var ny = -tx
            val l = sqrt(nx * nx + ny * ny); nx /= l; ny /= l
            if (nx * vx[i] + ny * vy[i] < 0) { nx = -nx; ny = -ny }
            octNx[i] = nx; octNy[i] = ny
            octC[i] = nx * vx[i] + ny * vy[i] + nx * mx + ny * my
        }
        pockets = octCups().map { c ->
            val x = mx + c[0]; val y = my + c[1]
            Pocket(x, y, CUP_R, x, y, false, doubleArrayOf(x - CUP_R, y, x + CUP_R, y), CUP_R - P.R * 0.5, hole = true)
        }.toTypedArray()
        posts = octPosts(lay).map { Post(mx + it[0], my + it[1], POST_R) }.toTypedArray()
        segs = emptyArray()
        gap = false
        bumper = false
        octagon = true
        gauntlet = false
        octLayout = lay
        holeKey = ""
        hasHoles = true
        version++
    }

    /** Corners of the octagon's cloth relative to its centre, going round; side i runs from corner i to i + 1. */
    val OCT_VX = doubleArrayOf(OCT_HX, OCT_HX, OCT_SIDE, -OCT_SIDE, -OCT_HX, -OCT_HX, -OCT_SIDE, OCT_SIDE)
    val OCT_VY = doubleArrayOf(-OCT_END, OCT_END, OCT_HY, OCT_HY, OCT_END, -OCT_END, -OCT_HY, -OCT_HY)

    /**
     * The octagon table's cups relative to its centre: 0 the left end, 1 the right end, 2 the top
     * side, 3 the bottom side; each the same distance in from the foot of its ramp as the small table's
     * cups are from their cushions.
     */
    fun octCups(): List<DoubleArray> {
        val ex = OCT_HX - CUP_IN; val sy = OCT_HY - CUP_IN
        return listOf(doubleArrayOf(-ex, 0.0), doubleArrayOf(ex, 0.0), doubleArrayOf(0.0, -sy), doubleArrayOf(0.0, sy))
    }

    /** Bumper layout [layout]'s bumpers relative to the octagon table's centre (x, y each). */
    fun octPosts(layout: Int): List<DoubleArray> {
        val ps = ArrayList<DoubleArray>()
        fun post(x: Double, y: Double) { ps += doubleArrayOf(x, y) }
        val ex = OCT_HX - CUP_IN; val sy = OCT_HY - CUP_IN
        // The small table's cross in the middle.
        for (d in doubleArrayOf(0.11, 0.21)) for (s in doubleArrayOf(-1.0, 1.0)) { post(s * d, 0.0); post(0.0, s * d) }
        // A bumper either side of every cup, as far from it as on the small table.
        for (s in doubleArrayOf(-1.0, 1.0)) {
            post(-ex, s * 0.095); post(ex, s * 0.095)
            post(s * 0.095, -sy); post(s * 0.095, sy)
        }
        val inch = 0.0254
        val dl = hypot(26.0, 21.25)
        val ux = 26.0 / dl; val uy = 21.25 / dl
        for (sx in doubleArrayOf(-1.0, 1.0)) for (syg in doubleArrayOf(-1.0, 1.0)) {
            val px = -uy * sx; val py = ux * syg
            when (layout) {
                0 -> for ((a, b) in listOf(7.3 to 7.3, 13.3 to 11.2, 18.5 to 16.7)) post(sx * a * inch, syg * b * inch)
                1 -> {
                    // Chevrons on the diagonals, pointing at the middle.
                    post(sx * ux * 15 * inch, syg * uy * 15 * inch)
                    for (k in doubleArrayOf(-1.0, 1.0)) post((sx * ux * 19 + k * px * 3.8) * inch, (syg * uy * 19 + k * py * 3.8) * inch)
                }
                else -> post(sx * ux * 17 * inch, syg * uy * 17 * inch)
            }
        }
        when (layout) {
            0 -> for (s in doubleArrayOf(-1.0, 1.0)) { post(s * 12.8 * inch, 0.0); post(0.0, s * 13.4 * inch) }
            2 -> for (s in doubleArrayOf(-1.0, 1.0)) for (k in doubleArrayOf(-1.0, 1.0)) {
                // A gate in front of every cup.
                post(s * 21.5 * inch, k * 3.3 * inch); post(k * 3.3 * inch, s * 18.0 * inch)
            }
        }
        return ps
    }

    /**
     * The octagon offset outward by [d] (0 = the cloth's edge, [OCT_OUTER] = the outside of the ledge),
     * relative to its centre, whatever table is in use: x0, y0, x1, y1, ... for its eight corners.
     */
    fun octOutline(d: Double): DoubleArray {
        val nx = DoubleArray(8); val ny = DoubleArray(8); val c = DoubleArray(8)
        for (i in 0 until 8) {
            val j = (i + 1) % 8
            val tx = OCT_VX[j] - OCT_VX[i]; val ty = OCT_VY[j] - OCT_VY[i]
            var ax = ty; var ay = -tx
            val l = sqrt(ax * ax + ay * ay); ax /= l; ay /= l
            if (ax * OCT_VX[i] + ay * OCT_VY[i] < 0) { ax = -ax; ay = -ay }
            nx[i] = ax; ny[i] = ay; c[i] = ax * OCT_VX[i] + ay * OCT_VY[i] + d
        }
        val out = DoubleArray(16)
        for (i in 0 until 8) {
            val a = (i + 7) % 8
            val det = nx[a] * ny[i] - ny[a] * nx[i]
            out[2 * i] = (c[a] * ny[i] - ny[a] * c[i]) / det
            out[2 * i + 1] = (nx[a] * c[i] - c[a] * nx[i]) / det
        }
        return out
    }

    /** One line about each bumper layout, for the menus. */
    val OCT_LAYOUT_HELP = arrayOf(
        "Scatter: the small table's cross, with bumpers scattered all round it.",
        "Chevrons: arrow shapes push balls out to the corner ramps — bank off the ramps to get across.",
        "Goal gates: a gate in front of every cup — thread the 4¾″ gap, or curl in off the ramp behind it."
    )

    /**
     * The Gauntlet ([Gauntlet]): 80" square, cushions all round, a cup in the middle of each side (0 left, 1 right,
     * 2 top, 3 bottom, as on the octagon table), and 68 bumpers. The ramps, the dish and the loops live in [Gauntlet].
     */
    fun useGauntlet() {
        if (gauntlet) return
        val l = 2 * Gauntlet.HALF
        P.resize(l, l)
        segs = arrayOf(Seg(0.0, 0.0, l, 0.0), Seg(0.0, l, l, l), Seg(0.0, 0.0, 0.0, l), Seg(l, 0.0, l, l))
        val m = Gauntlet.MID
        val cups = listOf(CUP_IN to m, l - CUP_IN to m, m to CUP_IN, m to l - CUP_IN)
        pockets = cups.map { (x, y) ->
            Pocket(x, y, CUP_R, x, y, false, doubleArrayOf(x - CUP_R, y, x + CUP_R, y), CUP_R - P.R * 0.5, hole = true)
        }.toTypedArray()
        posts = Gauntlet.posts().map { Post(it[0], it[1], POST_R) }.toTypedArray()
        gap = false
        bumper = false
        octagon = false
        gauntlet = true
        holeKey = ""
        hasHoles = true
        trickClassic = false
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
        octagon = false
        gauntlet = false
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
