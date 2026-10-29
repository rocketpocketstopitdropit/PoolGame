package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Gauntlet: a square bumper table, 80" cushion to cushion, for two to four players (or two teams).
 * A cup in the middle of each side, 68 bumpers in a pinwheel, and in the middle the Cage: two loop-de-loops
 * standing on end along the diagonals, a dish hanging inside them (a squared-off slice of a big ball, dip up),
 * and on each player's side a curved ramp aimed straight across at that player's cup, with a gap before the dish.
 *
 * Everything here is in table coordinates (metres, origin at the top-left corner of the cloth), except the
 * design constants, which are in inches from the middle of the table (x right, y down), as drawn.
 */
object Gauntlet {
    const val IN = 0.0254
    /** Half the cloth, cushion nose to cushion nose: 80" square. */
    const val HALF = 40 * IN
    /** The middle of the table. */
    const val MID = HALF

    /** A ball going faster than this (m/s) skims over a cup without dropping in. */
    const val CUP_MAX_SPEED = 1.5

    // ---- the ramps: one on each axis, rising toward the middle (a circular arc, so it curves up like a kicker)
    /** The ramp's curve: a circle of this radius, tangent to the cloth at the foot. */
    const val RAMP_RADIUS = 20 * IN
    /** Distance from the middle to the ramp's foot and to its lip. */
    const val RAMP_FOOT = 21 * IN
    const val RAMP_LIP = 9 * IN
    /** Half the ramp's width. */
    const val RAMP_HALF_W = 2 * IN

    // ---- the dish: a 10" square cut from a sphere of radius 11.8", dip up, hanging with its lowest point 3" up
    const val DISH_HALF = 5 * IN
    const val DISH_SPHERE = 11.8 * IN
    const val DISH_LOW = 3 * IN
    const val DISH_THICK = 0.35 * IN

    // ---- the loops: radius 9", standing on the diagonals, their lowest points at the middle of the table
    const val LOOP_R = 9 * IN
    /** Where a loop's track meets the cloth: from the middle out to here along the diagonal (both ways). */
    const val LOOP_MOUTH = 7 * IN
    /** Half the track's width: a ball must be this close to the diagonal (and heading along it) to go in. */
    const val LOOP_HALF_W = 1.5 * IN
    /** How high the loops' lower track stands off the cloth (balls rolling into it bounce off). */
    const val LOOP_TRACK_H = 0.06
    /** A ball has to be heading within this angle of the diagonal to roll into a loop's mouth. */
    val LOOP_ENTRY_ANGLE = 30.0 * PI / 180

    /** The design's bumpers for one quarter (inches from the middle; the right cup's quarter), turned four ways. */
    private val QUARTER = listOf(
        // a bumper either side of the cup, as on the classic table
        doubleArrayOf(CUP_D_IN, -3.74), doubleArrayOf(CUP_D_IN, 3.74),
        // the pinwheel (laid out on a 72" table and spread out to 80")
        *listOf(
            27.0 to -7.0, 31.0 to -12.0, 13.77 to 4.66, 30.89 to 21.12, 31.05 to -24.17, 15.94 to -6.16, 20.48 to 12.79,
            22.16 to -15.3, 22.05 to 2.16, 25.93 to 16.87, 21.31 to -9.49, 27.05 to -19.14, 19.19 to 7.33
        ).map { (x, y) -> doubleArrayOf(x * 40 / 36, y * 40 / 36) }.toTypedArray(),
        // the deflector past the loop (it turns a ball coming out of the loop toward this quarter's cup), and one more
        doubleArrayOf(12.82, -15.17), doubleArrayOf(29.0, 8.0)
    )
    private const val CUP_D_IN = 40 - Table.CUP_IN / IN

    /** Quarter turns: (x, y) -> (-y, x) on screen (right -> bottom -> left -> top). */
    private fun rot(x: Double, y: Double, k: Int): DoubleArray {
        var a = x; var b = y
        repeat(k and 3) { val t = a; a = -b; b = t }
        return doubleArrayOf(a, b)
    }

    /** All 68 bumpers in table coordinates (x, y). */
    fun posts(): List<DoubleArray> = (0 until 4).flatMap { k -> QUARTER.map { q -> rot(q[0], q[1], k) } }
        .map { doubleArrayOf(MID + it[0] * IN, MID + it[1] * IN) }

    /** The deflectors (one past each end of each loop), table coordinates. */
    fun deflectors(): List<DoubleArray> = (0 until 4).map { k -> rot(12.82, -15.17, k) }.map { doubleArrayOf(MID + it[0] * IN, MID + it[1] * IN) }

    /**
     * Where each player's balls start (table coordinates), marked ball first: round their own cup. Player 0 is red at
     * the left, 1 white at the right, 2 blue at the top, 3 yellow at the bottom (as on the octagon table).
     */
    fun startSpots(player: Int, n: Int): List<DoubleArray> {
        val wall = 40 - P.R / IN
        val out = ArrayList<DoubleArray>()
        out += doubleArrayOf(-40 + 8.6, -6.6)
        val others = n - 1
        val a = (others + 1) / 2; val b = others / 2
        fun row(cnt: Int, sgn: Double) {
            for (i in 0 until cnt) {
                val col = i / 2; val lane = i % 2
                out += doubleArrayOf(-wall + 0.15 + lane * 2.4, sgn * (8.2 + col * 2.4 + lane * 1.2))
            }
        }
        row(a, -1.0); row(b, 1.0)
        // Red's spots, turned to each seat: red left (k = 0), blue top (k = 1), white right (k = 2), yellow bottom (k = 3).
        val k = when (player) { 0 -> 0; 2 -> 1; 1 -> 2; else -> 3 }
        return out.map { val r = rot(it[0], it[1], k); doubleArrayOf(MID + r[0] * IN, MID + r[1] * IN) }
    }

    // ------------------------------------------------------------------ surfaces

    /**
     * Ramp [k] (0 the left one, rising to the right, toward the right cup; then 1 top, 2 right, 3 bottom — each faces
     * the cup across from it): the distance from its foot toward the middle and the sideways offset of (x, y), as
     * out[0], out[1]; and its direction toward the middle as out[2], out[3].
     */
    fun rampLocal(k: Int, x: Double, y: Double, out: DoubleArray) {
        val dx: Double; val dy: Double
        when (k) { 0 -> { dx = 1.0; dy = 0.0 }; 1 -> { dx = 0.0; dy = 1.0 }; 2 -> { dx = -1.0; dy = 0.0 }; else -> { dx = 0.0; dy = -1.0 } }
        val rx = x - MID; val ry = y - MID
        // The foot is RAMP_FOOT out from the middle, on the side the ramp faces away from.
        out[0] = (rx * dx + ry * dy) + RAMP_FOOT
        out[1] = -rx * dy + ry * dx
        out[2] = dx; out[3] = dy
    }

    /** Height of a ramp's surface [s] in from its foot (0 at the foot, 4" at the lip, where it leaves at about 37 degrees). */
    fun rampHeight(s: Double): Double {
        val c = s.coerceIn(0.0, RAMP_FOOT - RAMP_LIP)
        return RAMP_RADIUS - sqrt(RAMP_RADIUS * RAMP_RADIUS - c * c)
    }

    /** How steeply a ramp rises [s] in from its foot (dh/ds). */
    fun rampSlope(s: Double): Double {
        val c = s.coerceIn(0.0, RAMP_FOOT - RAMP_LIP)
        return c / sqrt(RAMP_RADIUS * RAMP_RADIUS - c * c)
    }

    /** Is (x, y) over the dish? */
    fun overDish(x: Double, y: Double) = abs(x - MID) <= DISH_HALF && abs(y - MID) <= DISH_HALF

    /** Height of the dish's top at (x, y) (only meaningful over it). */
    fun dishHeight(x: Double, y: Double): Double {
        val rx = x - MID; val ry = y - MID
        return DISH_LOW + DISH_SPHERE - sqrt((DISH_SPHERE * DISH_SPHERE - rx * rx - ry * ry).coerceAtLeast(1e-9))
    }

    /**
     * The surface a ball whose bottom is at height [z] would rest on at (x, y): its height into out[0] and its
     * upward unit normal into out[1..3] (x, y, up). The highest of the cloth, a ramp and the dish that isn't above
     * the ball (a little allowance, so a ball rolling onto a rising surface stays on it).
     */
    fun surface(x: Double, y: Double, z: Double, out: DoubleArray) {
        var h = 0.0; var nx = 0.0; var ny = 0.0; var nz = 1.0
        val tol = 0.012
        for (k in 0 until 4) {
            rampLocal(k, x, y, loc)
            val s = loc[0]
            if (s < 0 || s > RAMP_FOOT - RAMP_LIP || abs(loc[1]) > RAMP_HALF_W) continue
            val rh = rampHeight(s)
            if (rh > z + tol || rh < h) continue
            val sl = rampSlope(s)
            val inv = 1.0 / sqrt(1 + sl * sl)
            h = rh; nx = -sl * loc[2] * inv; ny = -sl * loc[3] * inv; nz = inv
        }
        if (overDish(x, y)) {
            val dh = dishHeight(x, y)
            if (dh <= z + tol && dh >= h) {
                val rx = x - MID; val ry = y - MID
                val cz = DISH_LOW + DISH_SPHERE           // the sphere's centre, above the dish
                val vx = -rx; val vy = -ry; val vz = cz - dh
                val l = sqrt(vx * vx + vy * vy + vz * vz)
                h = dh; nx = vx / l; ny = vy / l; nz = vz / l
            }
        }
        out[0] = h; out[1] = nx; out[2] = ny; out[3] = nz
    }
    private val loc = DoubleArray(4)

    /** The highest ramp surface at (x, y) (0 off the ramps): for drawing things on the cloth. */
    fun rampSurface(x: Double, y: Double): Double {
        var h = 0.0
        val l = DoubleArray(4)
        for (k in 0 until 4) {
            rampLocal(k, x, y, l)
            if (l[0] < 0 || l[0] > RAMP_FOOT - RAMP_LIP || abs(l[1]) > RAMP_HALF_W) continue
            h = maxOf(h, rampHeight(l[0]))
        }
        return h
    }

    /** Somewhere a ball can be put down by hand: on the cloth, clear of the ramps and the loops' tracks. */
    fun placeable(x: Double, y: Double): Boolean {
        val r = P.R
        val l = DoubleArray(4)
        for (k in 0 until 4) {
            rampLocal(k, x, y, l)
            if (l[0] > -r && l[0] < RAMP_FOOT - RAMP_LIP + r && abs(l[1]) < RAMP_HALF_W + r) return false
        }
        val rx = x - MID; val ry = y - MID
        val s = 1 / sqrt(2.0)
        for ((ux, uy) in listOf(s to s, s to -s)) {
            val along = rx * ux + ry * uy; val lat = -rx * uy + ry * ux
            if (abs(along) < LOOP_MOUTH + r && abs(lat) < LOOP_HALF_W + r) return false
        }
        return true
    }

    // ------------------------------------------------------------------ the loops

    /** Loop [i]'s direction along its diagonal: loop 0 runs top-left to bottom-right, loop 1 bottom-left to top-right. */
    fun loopDir(i: Int, out: DoubleArray) {
        val s = 1 / sqrt(2.0)
        if (i == 0) { out[0] = s; out[1] = s } else { out[0] = s; out[1] = -s }
    }

    /** Length of a trip through a loop: in along the cloth, once round, out along the cloth. */
    val LOOP_TRIP = 2 * LOOP_MOUTH + 2 * PI * LOOP_R

    /**
     * Where a ball is [s] along a trip through loop [i] travelling in direction [dir] (+1 or -1 along the loop's
     * diagonal), [lat] off the diagonal: x, y, height of the ball's bottom, and the unit direction of travel (x, y, up)
     * into out[0..5].
     */
    fun loopPoint(i: Int, dir: Int, lat: Double, s: Double, out: DoubleArray) {
        loopDir(i, dd)
        val ux = dd[0] * dir; val uy = dd[1] * dir
        val wx = -uy; val wy = ux
        val along: Double; val h: Double; val tx: Double; val th: Double
        when {
            s < LOOP_MOUTH -> { along = s - LOOP_MOUTH; h = 0.0; tx = 1.0; th = 0.0 }
            s < LOOP_MOUTH + 2 * PI * LOOP_R -> {
                val a = (s - LOOP_MOUTH) / LOOP_R
                along = LOOP_R * sin(a); h = LOOP_R * (1 - cos(a)); tx = cos(a); th = sin(a)
            }
            else -> { along = s - LOOP_MOUTH - 2 * PI * LOOP_R; h = 0.0; tx = 1.0; th = 0.0 }
        }
        out[0] = MID + ux * along + wx * lat; out[1] = MID + uy * along + wy * lat; out[2] = h
        out[3] = ux * tx; out[4] = uy * tx; out[5] = th
    }
    private val dd = DoubleArray(2)

    /** The angle round the loop at trip distance [s] (0 at the bottom on the way in), or -1 off the circle. */
    fun loopAngle(s: Double): Double =
        if (s < LOOP_MOUTH || s >= LOOP_MOUTH + 2 * PI * LOOP_R) -1.0 else (s - LOOP_MOUTH) / LOOP_R
}
