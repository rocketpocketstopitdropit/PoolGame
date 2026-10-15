package com.poolgame.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

class Ball(val id: Int) {
    var x = 0.0; var y = 0.0
    var vx = 0.0; var vy = 0.0
    var wx = 0.0; var wy = 0.0; var wz = 0.0   // angular velocity (rad/s)
    var z = 0.0; var vz = 0.0                  // height above the cloth and vertical speed (jump shots), up > 0
    var onTable = false
    var moving = false
    // Orientation quaternion, only used for drawing the rolling ball.
    var qw = 1.0; var qx = 0.0; var qy = 0.0; var qz = 0.0

    fun stop() {
        vx = 0.0; vy = 0.0; wx = 0.0; wy = 0.0; wz = 0.0; z = 0.0; vz = 0.0; moving = false
    }

    fun copyFrom(o: Ball) {
        x = o.x; y = o.y; vx = o.vx; vy = o.vy; wx = o.wx; wy = o.wy; wz = o.wz
        z = o.z; vz = o.vz
        onTable = o.onTable; moving = o.moving
        qw = o.qw; qx = o.qx; qy = o.qy; qz = o.qz
    }
}

/** What happened during one shot; the rules engine reads this. */
class ShotLog {
    var firstHit = -1                 // first object ball the cue ball touched
    val pocketed = ArrayList<Int>()   // in the order they dropped
    var railAfterHit = false          // any ball touched a cushion after first contact
    var cueCushionsBeforeHit = 0
    var hitX = 0.0; var hitY = 0.0     // cue-ball centre at first contact
    val jumpedOff = ArrayList<Int>()   // balls that flew off the table (or fell into the gap)
    val fell = ArrayList<Int>()        // of those, the ones that fell into the gap (trick-shot table)
    val pocketIn = IntArray(16) { -1 } // which pocket (index into Table.pockets) each ball dropped in
    /** For each ball, a bit mask of the balls it touched this shot (bit n = ball n). */
    val touched = IntArray(16)
    /** The cue ball has come down on the far section's cloth (trick shots). */
    var cueLanded = false
    /** Balls the cue ball touched before it first came down on the far section. */
    var cueAirTouched = 0
    /** Cut angle of the cue ball's first contact, in degrees (0 = straight on), or NaN. */
    var cutDeg = Double.NaN
    /** Where the cue ball first came down after a jump, or NaN. */
    var cueLandX = Double.NaN
    var cueLandY = Double.NaN

    fun reset() {
        firstHit = -1; pocketed.clear(); railAfterHit = false; cueCushionsBeforeHit = 0; jumpedOff.clear()
        fell.clear()
        pocketIn.fill(-1)
        touched.fill(0); cueLanded = false; cueAirTouched = 0; cutDeg = Double.NaN
        cueLandX = Double.NaN; cueLandY = Double.NaN
    }

    fun copyFrom(o: ShotLog) {
        firstHit = o.firstHit; pocketed.clear(); pocketed.addAll(o.pocketed)
        railAfterHit = o.railAfterHit; cueCushionsBeforeHit = o.cueCushionsBeforeHit
        hitX = o.hitX; hitY = o.hitY
        jumpedOff.clear(); jumpedOff.addAll(o.jumpedOff)
        fell.clear(); fell.addAll(o.fell)
        o.pocketIn.copyInto(pocketIn)
        o.touched.copyInto(touched); cueLanded = o.cueLanded; cueAirTouched = o.cueAirTouched; cutDeg = o.cutDeg
        cueLandX = o.cueLandX; cueLandY = o.cueLandY
    }
}

interface WorldListener {
    fun onBallHit(speed: Double)
    fun onCushion(speed: Double)
    fun onPocket(id: Int)
    fun onOffTable(id: Int) {}
}

/**
 * Rigid-ball physics on cloth.
 *
 * Each ball carries linear velocity (vx,vy) and full 3D spin (wx,wy,wz). The cloth applies
 * sliding friction at the contact point until the ball reaches natural roll, then a much smaller
 * rolling resistance. This is what makes draw, follow and stun behave correctly. Ball-ball and
 * ball-cushion contacts include friction, so side spin throws object balls and changes the
 * rebound angle off cushions.
 */
class World {
    val balls = Array(16) { Ball(it) }
    val log = ShotLog()
    var listener: WorldListener? = null
    var trackOrientation = false
    private val tmp = DoubleArray(2)
    private var lastDt = 0.0

    fun copyFrom(o: World) {
        for (i in 0 until 16) balls[i].copyFrom(o.balls[i])
        log.copyFrom(o.log)
    }

    fun anyMoving(): Boolean {
        for (b in balls) if (b.onTable && b.moving) return true
        return false
    }

    /**
     * Strike the cue ball.
     * @param side tip offset to the shooter's right as a fraction of R (right english > 0)
     * @param vert tip offset above centre as a fraction of R (follow > 0, draw < 0)
     * @param elevation cue elevation in radians. An elevated cue drives the ball into the slate,
     *   which bounces it up: that is a jump shot.
     */
    fun strike(angle: Double, speed: Double, side: Double, vert: Double, elevation: Double = 0.0) {
        val c = balls[0]
        var a = side; var b = vert
        val m = hypot(a, b)
        if (m > P.MAX_TIP) { a *= P.MAX_TIP / m; b *= P.MAX_TIP / m }
        val dx = cos(angle); val dy = sin(angle)
        val el = elevation.coerceIn(0.0, toRad(P.MAX_ELEVATION))
        val down = speed * sin(el)
        // Part of the horizontal speed is lost to slate friction during the bounce.
        val h = speed * cos(el) * (1.0 - 0.3 * sin(el))
        c.vx = h * dx; c.vy = h * dy
        // Angular impulse r x J with r = R(a*right + b*up), up = -z, right = (-dy, dx)
        val k = 5.0 * h / (2.0 * P.R)
        c.wx = k * b * dy
        c.wy = -k * b * dx
        c.wz = -k * a
        c.z = 0.0
        c.vz = if (el > 0.03) P.E_SLATE * down else 0.0
        c.moving = true
        log.reset()
    }

    /** Advance one adaptive sub-step no longer than maxDt. Returns the time advanced. */
    fun step(maxDt: Double): Double {
        var maxV = 0.0
        var any = false
        for (b in balls) {
            if (!b.onTable || !b.moving) continue
            any = true
            val s = abs(b.vx) + abs(b.vy) + abs(b.vz)
            if (s > maxV) maxV = s
        }
        if (!any) return maxDt
        var dt = if (maxV > 1e-9) 0.2 * P.R / maxV else maxDt
        if (dt > 0.004) dt = 0.004
        if (dt > maxDt) dt = maxDt
        lastDt = dt
        for (b in balls) {
            if (!b.onTable || !b.moving) continue
            if (b.z > 0.0 || b.vz > 0.0 || Table.overGap(b.x)) flight(b, dt) else cloth(b, dt)
            b.x += b.vx * dt
            b.y += b.vy * dt
            if (trackOrientation) rotate(b, dt)
        }
        if (Table.gap) gapEdges()
        collideBalls()
        collideCushions()
        checkPockets()
        return dt
    }

    /** Advance by `time` seconds (used for real-time animation). */
    fun advance(time: Double) {
        var t = 0.0
        while (t < time && anyMoving()) t += step(time - t)
    }

    /** Run until everything stops (used by the AI and tests). */
    fun simulate(maxTime: Double = 60.0) {
        var t = 0.0
        while (t < maxTime && anyMoving()) t += step(1.0)
        if (anyMoving()) for (b in balls) if (b.onTable) b.stop()
    }

    private fun cloth(b: Ball, dt: Double) {
        val r = P.R
        if (b.id == 0 && !log.cueLanded && Table.gap && b.x > Table.GAP_X1) log.cueLanded = true
        val ux = b.vx + r * b.wy
        val uy = b.vy - r * b.wx
        val us = hypot(ux, uy)
        if (us > 1e-4) {
            // Sliding: friction opposes contact-point slip; slip decays at 3.5*mu*g.
            val dv = P.MU_SLIDE * P.G * dt
            val f = if (3.5 * dv >= us) us / (3.5 * dv) else 1.0
            val ex = ux / us; val ey = uy / us
            b.vx -= dv * f * ex
            b.vy -= dv * f * ey
            val k = 2.5 * dv * f / r
            b.wx += k * ey
            b.wy -= k * ex
            if (f < 1.0) { b.wx = b.vy / r; b.wy = -b.vx / r }
        } else {
            val s = hypot(b.vx, b.vy)
            val d = P.MU_ROLL * P.G * dt
            if (s <= d) { b.vx = 0.0; b.vy = 0.0 } else { val sc = (s - d) / s; b.vx *= sc; b.vy *= sc }
            b.wx = b.vy / r; b.wy = -b.vx / r
        }
        val dz = P.SPIN_DECEL * dt
        b.wz = if (abs(b.wz) <= dz) 0.0 else b.wz - sign(b.wz) * dz
        if (abs(b.vx) + abs(b.vy) < 0.004 && r * (abs(b.wx) + abs(b.wy)) < 0.004) b.stop()
    }

    /** Ballistic flight after a jump; bounces on the slate until it settles (over the gap there is no slate). */
    private fun flight(b: Ball, dt: Double) {
        val z0 = b.z; val vz0 = b.vz
        b.z += b.vz * dt
        b.vz -= P.G * dt
        if (b.z <= 0.0 && b.vz < 0.0 && !Table.overGap(b.x)) {
            // Find when in this step it actually touched down, and bounce from that moment, so the
            // landing spot moves smoothly with the stroke instead of in step-sized jumps.
            val frac = if (z0 > 0.0 && z0 - b.z > 1e-12) (z0 / (z0 - b.z)).coerceIn(0.0, 1.0) else 1.0
            val impact = (-(vz0 - P.G * dt * frac)).coerceAtLeast(0.0)
            if (b.id == 0) {
                val lx = b.x + b.vx * dt * frac; val ly = b.y + b.vy * dt * frac
                if (log.cueLandX.isNaN()) { log.cueLandX = lx; log.cueLandY = ly }
                if (Table.gap && lx > Table.GAP_X1) log.cueLanded = true
            }
            val up = if (impact > 0.25) impact * 0.45 else 0.0
            val rest = dt * (1 - frac)
            if (up > 0.0) { b.z = up * rest; b.vz = up - P.G * rest } else { b.z = 0.0; b.vz = 0.0 }
            if (b.z < 0.0) b.z = 0.0
            b.vx *= 0.96; b.vy *= 0.96
            listener?.onCushion(impact * 0.6)
        }
    }

    /**
     * The two cut edges of the gap. A ball dropping into the gap falls past the slate's edge: it can
     * catch the lip (the top corner of the cut) and bounce or roll off it, and it can't pass through
     * the cut face below the lip. Without this a ball that came down a hair short fell straight
     * through, even with most of it over the far table.
     */
    private fun gapEdges() {
        val r = P.R
        for (b in balls) {
            if (!b.onTable || !b.moving) continue
            if (b.x < Table.GAP_X0 - r || b.x > Table.GAP_X1 + r) continue
            for (k in 0..1) {
                val edge = if (k == 0) Table.GAP_X0 else Table.GAP_X1
                val slabLeft = k == 0                      // the near section is left of its edge
                val hc = b.z + r                           // centre height above the cloth
                if (hc < 0.0) {
                    // Below the cloth: the cut face of the slate.
                    if (slabLeft && b.x < edge + r) { b.x = edge + r; if (b.vx < 0) { b.vx = -0.3 * b.vx; b.vy *= 0.3 } }
                    if (!slabLeft && b.x > edge - r) { b.x = edge - r; if (b.vx > 0) { b.vx = -0.3 * b.vx; b.vy *= 0.3 } }
                    continue
                }
                val dx = b.x - edge
                if (if (slabLeft) dx <= 0.0 else dx >= 0.0) continue   // centre over the slate: the cloth holds it
                val d = hypot(dx, hc)
                if (d >= r || d < 1e-9) continue
                val nx = dx / d; val nz = hc / d
                b.x = edge + nx * r
                b.z = nz * r - r
                val vn = b.vx * nx + b.vz * nz
                if (vn < 0) {
                    val e = if (-vn < 0.6) 0.0 else 0.35   // slow contact rolls over the lip, a hard one bounces
                    val vx0 = b.vx
                    b.vx -= (1 + e) * vn * nx
                    b.vz -= (1 + e) * vn * nz
                    // Friction with the edge slows the ball's sideways roll as much as its forward speed,
                    // so it keeps its heading (seen from above) instead of veering along the edge.
                    if (abs(vx0) > 1e-6) b.vy *= (abs(b.vx) / abs(vx0)).coerceAtMost(1.0)
                    if (-vn > 0.6) listener?.onCushion(-vn * 0.5)
                }
            }
        }
    }

    private fun rotate(b: Ball, dt: Double) {
        val h = 0.5 * dt
        val wx = b.wx; val wy = b.wy; val wz = b.wz
        val qw = b.qw; val qx = b.qx; val qy = b.qy; val qz = b.qz
        // dq = 0.5 * (0,w) * q
        val nw = qw - h * (wx * qx + wy * qy + wz * qz)
        val nx = qx + h * (qw * wx + (wy * qz - wz * qy))
        val ny = qy + h * (qw * wy + (wz * qx - wx * qz))
        val nz = qz + h * (qw * wz + (wx * qy - wy * qx))
        val n = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
        b.qw = nw / n; b.qx = nx / n; b.qy = ny / n; b.qz = nz / n
    }

    private fun collideBalls() {
        val r = P.R
        val minD2 = 4 * r * r
        for (i in 0 until 16) {
            val a = balls[i]
            if (!a.onTable) continue
            for (j in i + 1 until 16) {
                val b = balls[j]
                if (!b.onTable || (!a.moving && !b.moving)) continue
                val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 >= minD2) continue
                // Rewind both balls to the exact moment of contact so the line of centres
                // (and therefore the cut angle) is not distorted by step overshoot.
                val rvx = b.vx - a.vx; val rvy = b.vy - a.vy; val rvz = b.vz - a.vz
                val vv = rvx * rvx + rvy * rvy + rvz * rvz
                val dvr = dx * rvx + dy * rvy + dz * rvz
                var s = -1.0
                if (dvr < 0 && vv > 1e-12) {
                    val disc = dvr * dvr - vv * (d2 - minD2)
                    if (disc >= 0) s = (dvr + sqrt(disc)) / vv
                }
                if (s < 0 || s > lastDt * 1.01) {
                    // Resting contact or numerical oddity: just push apart (horizontally).
                    val hd = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-9)
                    val d = sqrt(d2).coerceAtLeast(1e-9)
                    val ov = (2 * r - d) * 0.5
                    val nx = dx / hd; val ny = dy / hd
                    a.x -= nx * ov; a.y -= ny * ov
                    b.x += nx * ov; b.y += ny * ov
                    s = 0.0
                    if ((a.vx - b.vx) * nx + (a.vy - b.vy) * ny <= 0) continue
                } else {
                    a.x -= a.vx * s; a.y -= a.vy * s; a.z -= a.vz * s
                    b.x -= b.vx * s; b.y -= b.vy * s; b.z -= b.vz * s
                }
                val ex0 = b.x - a.x; val ey0 = b.y - a.y; val ez0 = b.z - a.z
                val dd = sqrt(ex0 * ex0 + ey0 * ey0 + ez0 * ez0).coerceAtLeast(1e-9)
                val nz = ez0 / dd
                val hl = sqrt(ex0 * ex0 + ey0 * ey0).coerceAtLeast(1e-9)
                val nx = ex0 / hl; val ny = ey0 / hl          // horizontal unit normal
                val sh = hl / dd                              // horizontal share of the 3D normal
                val vn = ((a.vx - b.vx) * nx + (a.vy - b.vy) * ny) * sh + (a.vz - b.vz) * nz
                if (vn <= 0) {
                    a.x += a.vx * s; a.y += a.vy * s; a.z += a.vz * s
                    b.x += b.vx * s; b.y += b.vy * s; b.z += b.vz * s
                    continue
                }
                noteContact(i, j, a, b)
                if (nz != 0.0) {
                    // Airborne contact (jump shot clipping a ball): frictionless 3D impulse.
                    val ji = (1 + P.E_BALL) * 0.5 * vn
                    a.vx -= ji * nx * sh; a.vy -= ji * ny * sh; a.vz -= ji * nz
                    b.vx += ji * nx * sh; b.vy += ji * ny * sh; b.vz += ji * nz
                    for (q in arrayOf(a, b)) {
                        if (q.z <= 0.0) { q.z = 0.0; if (q.vz < 0) q.vz = 0.0 }
                        if (q.vz > 1.2) q.vz = 1.2   // keep glancing jumps on the table
                    }
                    a.x += a.vx * s; a.y += a.vy * s; a.z += a.vz * s
                    b.x += b.vx * s; b.y += b.vy * s; b.z += b.vz * s
                    for (q in arrayOf(a, b)) if (q.z < 0.0) q.z = 0.0
                    a.moving = true; b.moving = true
                    recordFirstHit(i, j, a, b)
                    listener?.onBallHit(vn)
                    continue
                }
                val jn = (1 + P.E_BALL) * 0.5 * vn
                // Relative surface velocity at the contact point (includes spin).
                val c1x = a.vx - r * a.wz * ny
                val c1y = a.vy + r * a.wz * nx
                val c1z = r * (a.wx * ny - a.wy * nx)
                val c2x = b.vx + r * b.wz * ny
                val c2y = b.vy - r * b.wz * nx
                val c2z = -r * (b.wx * ny - b.wy * nx)
                var tx = c1x - c2x; var ty = c1y - c2y; val tz = c1z - c2z
                val rn = tx * nx + ty * ny
                tx -= rn * nx; ty -= rn * ny
                val tm = sqrt(tx * tx + ty * ty + tz * tz)
                a.vx -= jn * nx; a.vy -= jn * ny
                b.vx += jn * nx; b.vy += jn * ny
                if (tm > 1e-9) {
                    val jt = minOf(P.MU_BALL * jn, tm / 7.0)
                    val ex = tx / tm; val ey = ty / tm; val ez = tz / tm
                    a.vx -= jt * ex; a.vy -= jt * ey
                    b.vx += jt * ex; b.vy += jt * ey
                    val k = 2.5 * jt / r
                    // n x e
                    val cx = ny * ez; val cy = -nx * ez; val cz = nx * ey - ny * ex
                    a.wx -= k * cx; a.wy -= k * cy; a.wz -= k * cz
                    b.wx -= k * cx; b.wy -= k * cy; b.wz -= k * cz
                }
                recordFirstHit(i, j, a, b)
                a.x += a.vx * s; a.y += a.vy * s
                b.x += b.vx * s; b.y += b.vy * s
                a.moving = true; b.moving = true
                listener?.onBallHit(vn)
            }
        }
    }

    /** Who touched whom, and (for the cue ball's first contact) how thin the cut was. Called before the impulse. */
    private fun noteContact(i: Int, j: Int, a: Ball, b: Ball) {
        log.touched[i] = log.touched[i] or (1 shl j)
        log.touched[j] = log.touched[j] or (1 shl i)
        if (i != 0 && j != 0) return
        val other = if (i == 0) j else i
        if (!log.cueLanded) log.cueAirTouched = log.cueAirTouched or (1 shl other)
        if (log.firstHit < 0 && log.cutDeg.isNaN()) {
            val c = if (i == 0) a else b
            val o = if (i == 0) b else a
            val nx = o.x - c.x; val ny = o.y - c.y
            val v = hypot(c.vx, c.vy); val n = hypot(nx, ny)
            if (v > 1e-9 && n > 1e-9) {
                val cosA = ((c.vx * nx + c.vy * ny) / (v * n)).coerceIn(-1.0, 1.0)
                log.cutDeg = kotlin.math.acos(cosA) * 180.0 / kotlin.math.PI
            }
        }
    }

    private fun recordFirstHit(i: Int, j: Int, a: Ball, b: Ball) {
        if (log.firstHit >= 0) return
        if (i == 0) { log.firstHit = j; log.hitX = a.x; log.hitY = a.y }
        else if (j == 0) { log.firstHit = i; log.hitX = b.x; log.hitY = b.y }
    }

    private fun collideCushions() {
        val r = P.R
        val r2 = r * r
        for (b in balls) {
            if (!b.onTable || !b.moving) continue
            // Quick reject: far from every rail.
            if (b.x > 2 * r && b.x < P.L - 2 * r && b.y > 2 * r && b.y < P.W - 2 * r) continue
            // A ball in the air higher than the cushions flies over them.
            if (b.z > P.RAIL_CLEAR) continue
            for (sg in Table.segs) {
                val d2 = Table.closest(sg, b.x, b.y, tmp)
                if (d2 >= r2) continue
                var d = sqrt(d2).coerceAtLeast(1e-9)
                var nx = (b.x - tmp[0]) / d; var ny = (b.y - tmp[1]) / d
                var vn = b.vx * nx + b.vy * ny
                if (vn >= 0) {
                    b.x = tmp[0] + nx * r; b.y = tmp[1] + ny * r
                    continue
                }
                // Rewind to the moment of contact, bounce, then replay the remaining time.
                var s = ((r - d) / -vn).coerceIn(0.0, lastDt)
                b.x -= b.vx * s; b.y -= b.vy * s
                val d2b = Table.closest(sg, b.x, b.y, tmp)
                d = sqrt(d2b).coerceAtLeast(1e-9)
                nx = (b.x - tmp[0]) / d; ny = (b.y - tmp[1]) / d
                vn = b.vx * nx + b.vy * ny
                if (d < r * 0.999 || vn >= 0) {
                    // fall back to projection
                    b.x += b.vx * s; b.y += b.vy * s; s = 0.0
                    Table.closest(sg, b.x, b.y, tmp)
                    val dd = sqrt((b.x - tmp[0]) * (b.x - tmp[0]) + (b.y - tmp[1]) * (b.y - tmp[1])).coerceAtLeast(1e-9)
                    nx = (b.x - tmp[0]) / dd; ny = (b.y - tmp[1]) / dd
                    b.x = tmp[0] + nx * r; b.y = tmp[1] + ny * r
                    vn = b.vx * nx + b.vy * ny
                    if (vn >= 0) continue
                }
                val e = (0.86 - 0.04 * -vn).coerceIn(0.6, 0.86)
                val jn = -(1 + e) * vn
                b.vx += jn * nx; b.vy += jn * ny
                // Friction at contact point r = -R n
                val cx = b.vx + r * b.wz * ny
                val cy = b.vy - r * b.wz * nx
                val cz = -r * (b.wx * ny - b.wy * nx)
                val cn = cx * nx + cy * ny
                val tx = cx - cn * nx; val ty = cy - cn * ny; val tz = cz
                val tm = sqrt(tx * tx + ty * ty + tz * tz)
                if (tm > 1e-9) {
                    val jt = minOf(P.MU_CUSHION * jn, tm / 3.5)
                    val ex = tx / tm; val ey = ty / tm; val ez = tz / tm
                    b.vx -= jt * ex; b.vy -= jt * ey
                    val k = 2.5 * jt / r
                    b.wx += k * (ny * ez); b.wy += k * (-nx * ez); b.wz += k * (nx * ey - ny * ex)
                }
                b.x += b.vx * s; b.y += b.vy * s
                if (log.firstHit >= 0) log.railAfterHit = true
                else if (b.id == 0) log.cueCushionsBeforeHit++
                listener?.onCushion(-vn)
            }
        }
    }

    private fun checkPockets() {
        val r = P.R
        for (b in balls) {
            if (!b.onTable || !b.moving) continue
            // Trick-shot table: a ball that has dropped below the table into the gap falls to the floor.
            val inGap = b.z < -2.0 * P.R && Table.overGap(b.x)
            if (!inGap && !Table.hasHoles && b.x > 0.1 && b.x < P.L - 0.1 && b.y > 0.1 && b.y < P.W - 0.1) continue
            var drop = b.x < -r || b.x > P.L + r || b.y < -r || b.y > P.W + r
            if (!drop && b.z < P.R * 0.6) for (p in Table.pockets) {
                val dx = b.x - p.cx; val dy = b.y - p.cy
                if (dx * dx + dy * dy < p.capR * p.capR) { drop = true; break }
            }
            if (!drop && inGap) {
                b.onTable = false
                log.jumpedOff += b.id
                log.fell += b.id
                listener?.onOffTable(b.id)
                b.stop()
                continue
            }
            if (drop) {
                val offTable = b.z > 0.0 && (b.x < -r || b.x > P.L + r || b.y < -r || b.y > P.W + r)
                b.onTable = false
                if (offTable) {
                    log.jumpedOff += b.id
                    listener?.onOffTable(b.id)
                } else {
                    log.pocketed += b.id
                    log.pocketIn[b.id] = nearestPocket(b.x, b.y)
                    listener?.onPocket(b.id)
                }
                b.stop()
            }
        }
    }

    // ---------- geometry helpers used by rules, AI and UI ----------

    fun nearestPocket(x: Double, y: Double): Int {
        var best = 0; var bd = Double.MAX_VALUE
        for ((i, p) in Table.pockets.withIndex()) {
            val d = (x - p.cx) * (x - p.cx) + (y - p.cy) * (y - p.cy)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    fun isFree(x: Double, y: Double, ignore: Int = 0): Boolean {
        val r = P.R
        if (x < r || x > P.L - r || y < r || y > P.W - r) return false
        if (Table.gap && x > Table.GAP_X0 - 0.6 * r && x < Table.GAP_X1 + 0.6 * r) return false
        val m = (2 * r + 0.0005)
        for (b in balls) {
            if (!b.onTable || b.id == ignore) continue
            val dx = b.x - x; val dy = b.y - y
            if (dx * dx + dy * dy < m * m) return false
        }
        return true
    }

    /** True if a ball of radius R can travel the straight line without touching any other ball. */
    fun pathClear(x1: Double, y1: Double, x2: Double, y2: Double, ex1: Int, ex2: Int, clear: Double = 2 * P.R): Boolean {
        for (b in balls) {
            if (!b.onTable || b.id == ex1 || b.id == ex2) continue
            if (Table.pointSegDist(b.x, b.y, x1, y1, x2, y2) < clear) return false
        }
        return true
    }

    /** Where the cue ball's centre would meet the cushions travelling along `angle` (ignores balls). */
    fun railPoint(angle: Double, out: DoubleArray) {
        val c = balls[0]
        val dx = cos(angle); val dy = sin(angle)
        val r = P.R
        var tw = Double.MAX_VALUE
        if (dx > 1e-9) tw = minOf(tw, (P.L - r - c.x) / dx)
        if (dx < -1e-9) tw = minOf(tw, (r - c.x) / dx)
        if (dy > 1e-9) tw = minOf(tw, (P.W - r - c.y) / dy)
        if (dy < -1e-9) tw = minOf(tw, (r - c.y) / dy)
        if (tw == Double.MAX_VALUE || tw < 0) tw = 0.0
        out[0] = c.x + dx * tw; out[1] = c.y + dy * tw
    }

    /**
     * Cast the cue ball along `angle`. Returns the hit: [type, x, y, ballId]
     * where type 1 = ball (x,y = ghost-ball centre), type 2 = cushion (x,y = cue centre at contact), 0 = nothing.
     */
    fun castCue(angle: Double, out: DoubleArray) {
        val c = balls[0]
        val dx = cos(angle); val dy = sin(angle)
        var best = Double.MAX_VALUE; var id = -1
        val d4 = 4 * P.R * P.R
        for (b in balls) {
            if (!b.onTable || b.id == 0) continue
            val ox = b.x - c.x; val oy = b.y - c.y
            val t = ox * dx + oy * dy
            if (t <= 0) continue
            val perp2 = ox * ox + oy * oy - t * t
            if (perp2 >= d4) continue
            val tt = t - sqrt(d4 - perp2)
            if (tt < best) { best = tt; id = b.id }
        }
        // Cushion: march against the rectangle shrunk by R (pockets ignored for the guide).
        var tw = Double.MAX_VALUE
        val r = P.R
        if (dx > 1e-9) tw = minOf(tw, (P.L - r - c.x) / dx)
        if (dx < -1e-9) tw = minOf(tw, (r - c.x) / dx)
        if (dy > 1e-9) tw = minOf(tw, (P.W - r - c.y) / dy)
        if (dy < -1e-9) tw = minOf(tw, (r - c.y) / dy)
        if (id >= 0 && best <= tw) {
            out[0] = 1.0; out[1] = c.x + dx * best; out[2] = c.y + dy * best; out[3] = id.toDouble()
        } else if (tw < Double.MAX_VALUE) {
            out[0] = 2.0; out[1] = c.x + dx * tw; out[2] = c.y + dy * tw; out[3] = -1.0
        } else {
            out[0] = 0.0
        }
    }
}
