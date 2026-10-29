package com.poolgame.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How many balls a world holds: the cue ball and the fifteen numbered balls, or on the octagon bumper
 * table up to four players' five balls each (ids 1 to 20; 0 is unused there).
 */
const val NB = 21

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
    /** Furthest down the table (largest x) the cue ball's centre got before it first touched a ball. */
    var cueMaxX = Double.NEGATIVE_INFINITY
    val jumpedOff = ArrayList<Int>()   // balls that flew off the table (or fell into the gap)
    val fell = ArrayList<Int>()        // of those, the ones that fell into the gap (trick-shot table)
    val pocketIn = IntArray(NB) { -1 } // which pocket (index into Table.pockets) each ball dropped in
    /** For each ball, a bit mask of the balls it touched this shot (bit n = ball n). */
    val touched = IntArray(NB)
    /** The cue ball has come down on the far section's cloth (trick shots). */
    var cueLanded = false
    /** Balls the cue ball touched before it first came down on the far section. */
    var cueAirTouched = 0
    /** Cut angle of the cue ball's first contact, in degrees (0 = straight on), or NaN. */
    var cutDeg = Double.NaN
    /** Where the cue ball first came down after a jump, or NaN. */
    var cueLandX = Double.NaN
    var cueLandY = Double.NaN
    /** The tip was outside the friction limit and slipped off the ball (a miscue). Not a foul by itself. */
    var miscue = false
    /**
     * The cue ball was struck below centre and lifted straight off the cloth by the cue ("scooped"
     * or "dug under") instead of being driven down into the slate by an elevated cue. A foul.
     */
    var scooped = false
    /** Balls the cue drove down into the slate and bounced up off it (jump shots), bit n = ball n. */
    var cueJumps = 0
    /** Balls the tip dug under and scooped up (bit n = ball n): see [scooped]. */
    var scoops = 0
    /** Balls that left the cloth this shot (bit n = ball n), more than a few millimetres up. */
    var air = 0
    /** Balls that touched a cushion this shot (bit n = ball n). */
    var railed = 0
    /** Balls that touched a rail this shot, not counting the pockets' jaws (bit n = ball n). */
    var banked = 0

    fun reset() {
        firstHit = -1; pocketed.clear(); railAfterHit = false; cueCushionsBeforeHit = 0; jumpedOff.clear()
        fell.clear(); cueMaxX = Double.NEGATIVE_INFINITY
        pocketIn.fill(-1)
        touched.fill(0); cueLanded = false; cueAirTouched = 0; cutDeg = Double.NaN
        cueLandX = Double.NaN; cueLandY = Double.NaN
        miscue = false; scooped = false; cueJumps = 0; scoops = 0; air = 0; railed = 0; banked = 0
    }

    fun copyFrom(o: ShotLog) {
        firstHit = o.firstHit; pocketed.clear(); pocketed.addAll(o.pocketed)
        railAfterHit = o.railAfterHit; cueCushionsBeforeHit = o.cueCushionsBeforeHit
        hitX = o.hitX; hitY = o.hitY; cueMaxX = o.cueMaxX
        jumpedOff.clear(); jumpedOff.addAll(o.jumpedOff)
        fell.clear(); fell.addAll(o.fell)
        o.pocketIn.copyInto(pocketIn)
        o.touched.copyInto(touched); cueLanded = o.cueLanded; cueAirTouched = o.cueAirTouched; cutDeg = o.cutDeg
        cueLandX = o.cueLandX; cueLandY = o.cueLandY
        miscue = o.miscue; scooped = o.scooped; cueJumps = o.cueJumps; scoops = o.scoops; air = o.air; railed = o.railed; banked = o.banked
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
    val balls = Array(NB) { Ball(it) }
    /**
     * The ball the cue strikes. Always the cue ball (0) in 8-ball, 9-ball and trick shots; in bumper
     * pool there is no cue ball and you shoot one of your own balls, so it's whichever one you picked.
     */
    var cue = 0
    val cueBall get() = balls[cue]
    val log = ShotLog()
    var listener: WorldListener? = null
    var trackOrientation = false
    private val tmp = DoubleArray(2)
    private var lastDt = 0.0
    /** Octagon table: balls rolling up a ramp (to count the moment each turns back, like a cushion). */
    private val climbing = BooleanArray(NB)

    fun copyFrom(o: World) {
        for (i in 0 until NB) balls[i].copyFrom(o.balls[i])
        log.copyFrom(o.log)
        cue = o.cue
        o.climbing.copyInto(climbing)
    }

    private companion object {
        /** A ball higher than this off the cloth counts as having left it ([ShotLog.air]). */
        const val AIR_Z = 0.004
        /** Octagon table: a change of surface direction sharper than about 10 degrees in one step is a hard corner... */
        val HARD_CORNER = kotlin.math.cos(10.0 * kotlin.math.PI / 180)
        /** ...and a ball comes off it with this much of the speed it hit it with (felt over a hard board). */
        const val E_RAMP = 0.3
    }

    fun anyMoving(): Boolean {
        for (b in balls) if (b.onTable && b.moving) return true
        return false
    }

    /**
     * Strike the cue ball.
     *
     * The cue is a straight stick coming in along [angle], tilted up by [elevation]. [side] and
     * [vert] say where its tip meets the ball, as seen looking down the cue: offsets from the cue's
     * own line through the centre, as fractions of R (right english > 0, above the line > 0).
     *
     * Within the tip's friction limit ([P.MAX_TIP] off centre) the push is along the cue. Further out
     * the tip slips: only the push along the ball's surface normal, plus as much friction as the
     * tip can hold, gets through (a miscue). The ball's spin is r x J for the contact point r.
     *
     * An elevated cue drives the ball down into the slate, which bounces it up: a legal jump. Slate
     * friction during that bounce takes some speed off and turns it into roll. A tip that digs in
     * under the ball instead pushes it up off the cloth directly: a scoop, logged as [ShotLog.scooped].
     * Spin is kept in the air, so follow, draw and english all take effect when the ball lands.
     */
    fun strike(angle: Double, speed: Double, side: Double, vert: Double, elevation: Double = 0.0,
               id: Int = cue, resetLog: Boolean = true) {
        val c = balls[id]
        if (resetLog) log.reset()
        var a = side; var b = vert
        val m0 = hypot(a, b)
        if (m0 > P.TIP_LIMIT) { a *= P.TIP_LIMIT / m0; b *= P.TIP_LIMIT / m0 }
        val m = hypot(a, b)
        val fx = cos(angle); val fy = sin(angle)
        val el = elevation.coerceIn(0.0, toRad(P.MAX_ELEVATION))
        val ce = cos(el); val se = sin(el)
        // Engine frame: x, y on the table, z into the table (down). up = (0, 0, -1).
        // Cue direction d (forward and down), the cue's own "up" u and "right" rr (both across it).
        val dX = ce * fx; val dY = ce * fy; val dZ = se
        val uX = se * fx; val uY = se * fy; val uZ = -ce
        val rX = -fy; val rY = fx
        // Contact point on the ball (unit sphere): the tip line's offset, then back onto the surface.
        val cc = sqrt((1.0 - a * a - b * b).coerceAtLeast(0.0))
        val pX = a * rX + b * uX - cc * dX
        val pY = a * rY + b * uY - cc * dY
        val pZ = b * uZ - cc * dZ
        // Velocity the ball gets (impulse / mass). Normal part: speed * cc along -p. Tangential: the
        // rest of the cue's motion, as far as the tip's friction allows (exactly the whole of it inside
        // the friction limit, so a normal hit is pushed straight along the cue).
        var jX = -cc * pX; var jY = -cc * pY; var jZ = -cc * pZ
        if (m > 1e-9) {
            val tX = dX + cc * pX; val tY = dY + cc * pY; val tZ = dZ + cc * pZ   // |t| = m
            val k = minOf(P.MU_TIP * cc, m) / m
            jX += k * tX; jY += k * tY; jZ += k * tZ
        }
        jX *= speed; jY *= speed; jZ *= speed
        log.miscue = (!resetLog && log.miscue) || m > P.MAX_TIP + 1e-9
        c.vx = jX; c.vy = jY
        // Spin: w = (5 / 2R) p x J  (p in units of R)
        val kw = 5.0 / (2.0 * P.R)
        c.wx = kw * (pY * jZ - pZ * jY)
        c.wy = kw * (pZ * jX - pX * jZ)
        c.wz = kw * (pX * jY - pY * jX)
        c.z = 0.0; c.vz = 0.0
        if (jZ > 0.02) {
            // Driven into the slate: it bounces back up, and the slate's grip trades speed for roll.
            slateGrip(c, (1 + P.E_SLATE) * jZ)
            c.vz = P.E_SLATE * jZ
            if (c.vz < 0.05) c.vz = 0.0
            if (c.vz > 0.0) log.cueJumps = log.cueJumps or (1 shl id)
        } else if (jZ < -0.05) {
            // Lifted straight up by the tip: a scoop.
            c.vz = -jZ
            log.scooped = true
            log.scoops = log.scoops or (1 shl id)
        }
        c.moving = true
    }

    /**
     * A short, hard contact with the slate carrying [impulse] (per unit mass, along the normal):
     * friction at the contact point, as much as that impulse allows, cuts the slip between the ball's
     * bottom and the cloth (the same friction as rolling on the cloth, just all at once).
     */
    private fun slateGrip(b: Ball, impulse: Double) {
        val r = P.R
        val ux = b.vx + r * b.wy
        val uy = b.vy - r * b.wx
        val us = hypot(ux, uy)
        if (us < 1e-6) return
        val jt = minOf(P.MU_SLIDE * impulse, us / 3.5)
        val ex = ux / us; val ey = uy / us
        b.vx -= jt * ex; b.vy -= jt * ey
        val k = 2.5 * jt / r
        b.wx += k * ey
        b.wy -= k * ex
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
            if (Table.octagon) octMove(b, dt)
            else {
                if (b.z > 0.0 || b.vz > 0.0 || Table.overGap(b.x)) flight(b, dt) else cloth(b, dt)
                b.x += b.vx * dt
                b.y += b.vy * dt
            }
            if (trackOrientation) rotate(b, dt)
        }
        if (Table.gap) gapEdges()
        collideBalls()
        collideCushions()
        if (Table.posts.isNotEmpty()) collidePosts()
        checkPockets()
        if (log.firstHit < 0) {
            val c = balls[cue]
            if (c.onTable && c.x > log.cueMaxX) log.cueMaxX = c.x
        }
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
        if (b.id == cue && !log.cueLanded && Table.gap && b.x > Table.GAP_X1) log.cueLanded = true
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
        if (b.z > AIR_Z) log.air = log.air or (1 shl b.id)
        if (b.z <= 0.0 && b.vz < 0.0 && !Table.overGap(b.x)) {
            // Find when in this step it actually touched down, and bounce from that moment, so the
            // landing spot moves smoothly with the stroke instead of in step-sized jumps.
            val frac = if (z0 > 0.0 && z0 - b.z > 1e-12) (z0 / (z0 - b.z)).coerceIn(0.0, 1.0) else 1.0
            val impact = (-(vz0 - P.G * dt * frac)).coerceAtLeast(0.0)
            if (b.id == cue) {
                val lx = b.x + b.vx * dt * frac; val ly = b.y + b.vy * dt * frac
                if (log.cueLandX.isNaN()) { log.cueLandX = lx; log.cueLandY = ly }
                if (Table.gap && lx > Table.GAP_X1) log.cueLanded = true
            }
            val up = if (impact > 0.25) impact * 0.45 else 0.0
            val rest = dt * (1 - frac)
            if (up > 0.0) { b.z = up * rest; b.vz = up - P.G * rest } else { b.z = 0.0; b.vz = 0.0 }
            if (b.z < 0.0) b.z = 0.0
            // Landing: the cloth grips the ball, so the spin it carried through the air takes hold
            // (draw checks it up, follow drives it on, english starts to bite).
            slateGrip(b, impact + up)
            listener?.onCushion(impact * 0.6)
        }
    }

    /**
     * The octagon table. On the flat cloth a ball moves as on any table. On a ramp it rolls in the
     * slope's plane: gravity pulls it back down at 5/7 g sin θ (the rolling ball's value) and its spin
     * is the natural roll for that surface. The hard corners — where the cloth meets a ramp, and where two
     * ramps meet — are impacts: the ball loses the part of its speed going into the new face and hops
     * off it a little. Over a crest it's going too fast for (the top of the ramp, the ledge's outer edge)
     * it leaves the surface and flies, and it lands — bouncing if it comes down hard — wherever it meets
     * the surface again. Past the ledge there's nothing under it.
     */
    private fun octMove(b: Ball, dt: Double) {
        val r = P.R
        val d0 = Table.octDist(b.x, b.y)
        val h0 = Table.surfZ(d0)
        // The surface's normal where the ball starts this step (to tell a hard corner from a gentle curve).
        val e0 = Table.octEdge(b.x, b.y)
        val k0 = Table.surfSlope(d0)
        val i0 = 1.0 / sqrt(1 + k0 * k0)
        val n0x = -k0 * Table.octNx(e0) * i0; val n0y = -k0 * Table.octNy(e0) * i0; val n0z = i0
        val grounded = h0 != Double.NEGATIVE_INFINITY && b.z <= h0 + 1e-7
        if (grounded && d0 <= Table.FLAT_D && b.vz <= 1e-9) {
            // The flat cloth.
            b.z = 0.0; b.vz = 0.0
            climbing[b.id] = false
            cloth(b, dt)
            if (!b.moving) return
        } else if (grounded) {
            // On the curve or the slope (or the ledge): roll in the surface's plane.
            val e = Table.octEdge(b.x, b.y)
            val k = Table.surfSlope(d0)
            val inv = 1.0 / sqrt(1 + k * k)
            val nx = -k * Table.octNx(e) * inv; val ny = -k * Table.octNy(e) * inv; val nz = inv
            turnAlong(b, nx, ny, nz)
            val g = 5.0 / 7.0 * P.G
            b.vx += g * nz * nx * dt; b.vy += g * nz * ny * dt; b.vz += g * (nz * nz - 1) * dt
            val sp = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
            val dr = P.MU_ROLL * P.G * nz * dt
            if (sp <= dr) { b.vx = 0.0; b.vy = 0.0; b.vz = 0.0 } else { val sc = (sp - dr) / sp; b.vx *= sc; b.vy *= sc; b.vz *= sc }
            // Natural roll on this surface: w = n x v / R (in the up frame; the engine's z points down).
            val wux = (ny * b.vz - nz * b.vy) / r; val wuy = (nz * b.vx - nx * b.vz) / r; val wuz = (nx * b.vy - ny * b.vx) / r
            b.wx = -wux; b.wy = -wuy; b.wz = wuz
            // Turning back down the ramp counts as coming off a cushion (for the logs and the aim guide).
            if (h0 > 0.002) {
                if (b.vz > 1e-4) climbing[b.id] = true
                else if (climbing[b.id] && b.vz <= 0.0) { climbing[b.id] = false; rampTurn(b) }
            }
            if (sp < 0.004 && abs(k) < 0.02) { b.stop(); b.z = if (h0 < 1e-4) 0.0 else h0; return }
        } else {
            // In the air.
            b.vz -= P.G * dt
        }
        b.x += b.vx * dt; b.y += b.vy * dt; b.z += b.vz * dt
        // Meet the surface where the ball is now.
        val d1 = Table.octDist(b.x, b.y)
        val h1 = Table.surfZ(d1)
        if (h1 == Double.NEGATIVE_INFINITY) { log.air = log.air or (1 shl b.id); return }   // off the edge: falling
        if (b.z <= h1) {
            val e = Table.octEdge(b.x, b.y)
            val k = Table.surfSlope(d1)
            val inv = 1.0 / sqrt(1 + k * k)
            val nx = -k * Table.octNx(e) * inv; val ny = -k * Table.octNy(e) * inv; val nz = inv
            val vn = b.vx * nx + b.vy * ny + b.vz * nz
            if (!grounded && vn < -0.25) {
                // A hard landing bounces, like a jumped ball coming down on the slate.
                val up = -vn * 0.45
                b.vx -= (vn - up) * nx; b.vy -= (vn - up) * ny; b.vz -= (vn - up) * nz
                if (d1 <= Table.FLAT_D) slateGrip(b, -vn + up)
                listener?.onCushion(-vn * 0.6)
            } else if (grounded && n0x * nx + n0y * ny + n0z * nz < HARD_CORNER) {
                // A hard corner: the speed going into the new face is lost, and the ball hops off it a little.
                if (vn < 0) {
                    val up = -vn * E_RAMP
                    b.vx -= (vn - up) * nx; b.vy -= (vn - up) * ny; b.vz -= (vn - up) * nz
                    if (-vn > 0.15) listener?.onCushion(-vn)
                }
            } else if (grounded) turnAlong(b, nx, ny, nz)
            else if (vn < 0) { b.vx -= vn * nx; b.vy -= vn * ny; b.vz -= vn * nz }
            b.z = h1
            if (d1 <= Table.FLAT_D && b.vz < 1e-9) { b.z = 0.0; b.vz = 0.0 }
        } else if (grounded && b.z - h1 < 1e-6) b.z = h1
        if (b.z - h1 > AIR_Z) log.air = log.air or (1 shl b.id)
    }

    /**
     * A ball rolling over a curve in the surface (normal n): its velocity turns to follow the surface,
     * keeping its speed (a smooth curve doesn't take energy from a rolling ball).
     */
    private fun turnAlong(b: Ball, nx: Double, ny: Double, nz: Double) {
        val vn = b.vx * nx + b.vy * ny + b.vz * nz
        if (vn >= 0) return
        val s0 = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
        b.vx -= vn * nx; b.vy -= vn * ny; b.vz -= vn * nz
        val s1 = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
        if (s1 > 1e-9) { val k = s0 / s1; b.vx *= k; b.vy *= k; b.vz *= k }
    }

    /** A ball rolled up a ramp and turned back: logged like a cushion. */
    private fun rampTurn(b: Ball) {
        log.railed = log.railed or (1 shl b.id)
        log.banked = log.banked or (1 shl b.id)
        if (log.firstHit >= 0) log.railAfterHit = true
        else if (b.id == cue) log.cueCushionsBeforeHit++
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
        for (i in 0 until NB) {
            val a = balls[i]
            if (!a.onTable) continue
            for (j in i + 1 until NB) {
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
        if (i != cue && j != cue) return
        val other = if (i == cue) j else i
        if (!log.cueLanded) log.cueAirTouched = log.cueAirTouched or (1 shl other)
        if (log.firstHit < 0 && log.cutDeg.isNaN()) {
            val c = if (i == cue) a else b
            val o = if (i == cue) b else a
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
        if (i == cue) { log.firstHit = j; log.hitX = a.x; log.hitY = a.y }
        else if (j == cue) { log.firstHit = i; log.hitX = b.x; log.hitY = b.y }
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
                log.railed = log.railed or (1 shl b.id)
                if (!sg.jaw) log.banked = log.banked or (1 shl b.id)
                if (log.firstHit >= 0) log.railAfterHit = true
                else if (b.id == cue) log.cueCushionsBeforeHit++
                listener?.onCushion(-vn)
            }
        }
    }

    /**
     * Bumper posts (bumper pool): round, springy rubber rings. Same contact model as the cushions —
     * rewind to the moment of contact, bounce, friction at the contact point — but livelier.
     */
    private fun collidePosts() {
        val r = P.R
        for (b in balls) {
            if (!b.onTable || !b.moving || b.z > Table.POST_H) continue
            for (pt in Table.posts) {
                val rr = r + pt.r
                var dx = b.x - pt.x; var dy = b.y - pt.y
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr) continue
                var d = sqrt(d2).coerceAtLeast(1e-9)
                var nx = dx / d; var ny = dy / d
                var vn = b.vx * nx + b.vy * ny
                if (vn >= 0) { b.x = pt.x + nx * rr; b.y = pt.y + ny * rr; continue }
                // Rewind to the moment of contact (solve |p - v s - post| = rr for the latest s).
                val vv = b.vx * b.vx + b.vy * b.vy
                val dv = dx * b.vx + dy * b.vy
                val disc = dv * dv - vv * (d2 - rr * rr)
                var s = if (vv > 1e-12 && disc >= 0) ((dv + sqrt(disc)) / vv) else 0.0
                if (s < 0 || s > lastDt * 1.01) s = 0.0
                b.x -= b.vx * s; b.y -= b.vy * s
                dx = b.x - pt.x; dy = b.y - pt.y
                d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-9)
                nx = dx / d; ny = dy / d
                if (d < rr) { b.x = pt.x + nx * rr; b.y = pt.y + ny * rr }
                vn = b.vx * nx + b.vy * ny
                if (vn >= 0) { b.x += b.vx * s; b.y += b.vy * s; continue }
                val e = (0.9 - 0.03 * -vn).coerceIn(0.7, 0.9)
                val jn = -(1 + e) * vn
                b.vx += jn * nx; b.vy += jn * ny
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
                else if (b.id == cue) log.cueCushionsBeforeHit++   // (the aim guide stops a little after the first)
                listener?.onCushion(-vn)
            }
        }
    }

    /** Octagon table: how far a ball at (x, y) moving along unit (dx, dy) goes before it starts up a ramp. */
    private fun rampDistance(x: Double, y: Double, dx: Double, dy: Double): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until 8) {
            val v = Table.octNx(i) * dx + Table.octNy(i) * dy
            if (v <= 1e-9) continue
            // Where it reaches side i's rounded foot.
            val t = (Table.FLAT_D - Table.octSide(i, x, y)) / v
            if (t < best) best = t
        }
        return best.coerceAtLeast(0.0)
    }

    /** How far a ball (centre) at (x, y) moving along unit (dx, dy) travels before touching a bumper post. */
    private fun postDistance(x: Double, y: Double, dx: Double, dy: Double): Double {
        var best = Double.MAX_VALUE
        for (pt in Table.posts) {
            val rr = P.R + pt.r
            val ox = pt.x - x; val oy = pt.y - y
            val t = ox * dx + oy * dy
            if (t <= 0) continue
            val perp2 = ox * ox + oy * oy - t * t
            if (perp2 >= rr * rr) continue
            val tt = t - sqrt(rr * rr - perp2)
            if (tt < best) best = tt
        }
        return best
    }

    private fun checkPockets() {
        val r = P.R
        for (b in balls) {
            if (!b.onTable || !b.moving) continue
            if (Table.octagon) {
                // Past the outside of the ledge there's nothing to hold the ball up: it's off the table.
                if (Table.octDist(b.x, b.y) > Table.OCT_OUTER + r) {
                    b.onTable = false
                    log.jumpedOff += b.id
                    listener?.onOffTable(b.id)
                    b.stop()
                    continue
                }
                if (b.z < P.R * 0.6) for ((i, p) in Table.pockets.withIndex()) {
                    val dx = b.x - p.cx; val dy = b.y - p.cy
                    if (dx * dx + dy * dy < p.capR * p.capR) {
                        b.onTable = false
                        log.pocketed += b.id
                        log.pocketIn[b.id] = i
                        listener?.onPocket(b.id)
                        b.stop()
                        break
                    }
                }
                continue
            }
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
        if (Table.octagon) { if (Table.octDist(x, y) > Table.FLAT_D - 0.002) return false }
        else if (x < r || x > P.L - r || y < r || y > P.W - r) return false
        if (Table.gap && x > Table.GAP_X0 - 0.6 * r && x < Table.GAP_X1 + 0.6 * r) return false
        for (pt in Table.posts) if ((pt.x - x) * (pt.x - x) + (pt.y - y) * (pt.y - y) < (r + pt.r + 0.001) * (r + pt.r + 0.001)) return false
        if (Table.bumper || Table.octagon) for (pk in Table.pockets) if ((pk.cx - x) * (pk.cx - x) + (pk.cy - y) * (pk.cy - y) < (pk.capR + r) * (pk.capR + r)) return false
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
        for (pt in Table.posts) if (Table.pointSegDist(pt.x, pt.y, x1, y1, x2, y2) < P.R + pt.r) return false
        return true
    }

    /** Where the cue ball's centre would meet the cushions travelling along `angle` (ignores balls). */
    fun railPoint(angle: Double, out: DoubleArray) {
        val c = balls[cue]
        val dx = cos(angle); val dy = sin(angle)
        val r = P.R
        var tw = if (Table.octagon) rampDistance(c.x, c.y, dx, dy) else Double.MAX_VALUE
        if (!Table.octagon) {
            if (dx > 1e-9) tw = minOf(tw, (P.L - r - c.x) / dx)
            if (dx < -1e-9) tw = minOf(tw, (r - c.x) / dx)
            if (dy > 1e-9) tw = minOf(tw, (P.W - r - c.y) / dy)
            if (dy < -1e-9) tw = minOf(tw, (r - c.y) / dy)
        }
        if (tw == Double.MAX_VALUE || tw < 0) tw = 0.0
        if (Table.posts.isNotEmpty()) tw = minOf(tw, postDistance(c.x, c.y, dx, dy))
        out[0] = c.x + dx * tw; out[1] = c.y + dy * tw
    }

    /**
     * Cast the cue ball along `angle`. Returns the hit: [type, x, y, ballId]
     * where type 1 = ball (x,y = ghost-ball centre), type 2 = cushion (x,y = cue centre at contact), 0 = nothing.
     */
    fun castCue(angle: Double, out: DoubleArray) {
        val c = balls[cue]
        val dx = cos(angle); val dy = sin(angle)
        var best = Double.MAX_VALUE; var id = -1
        val d4 = 4 * P.R * P.R
        for (b in balls) {
            if (!b.onTable || b.id == cue) continue
            val ox = b.x - c.x; val oy = b.y - c.y
            val t = ox * dx + oy * dy
            if (t <= 0) continue
            val perp2 = ox * ox + oy * oy - t * t
            if (perp2 >= d4) continue
            val tt = t - sqrt(d4 - perp2)
            if (tt < best) { best = tt; id = b.id }
        }
        // Cushion: march against the rectangle shrunk by R (pockets ignored for the guide); on the
        // octagon table, the foot of the ramps.
        var tw = Double.MAX_VALUE
        val r = P.R
        if (Table.octagon) tw = rampDistance(c.x, c.y, dx, dy)
        else {
            if (dx > 1e-9) tw = minOf(tw, (P.L - r - c.x) / dx)
            if (dx < -1e-9) tw = minOf(tw, (r - c.x) / dx)
            if (dy > 1e-9) tw = minOf(tw, (P.W - r - c.y) / dy)
            if (dy < -1e-9) tw = minOf(tw, (r - c.y) / dy)
        }
        if (Table.posts.isNotEmpty()) tw = minOf(tw, postDistance(c.x, c.y, dx, dy))
        if (id >= 0 && best <= tw) {
            out[0] = 1.0; out[1] = c.x + dx * best; out[2] = c.y + dy * best; out[3] = id.toDouble()
        } else if (tw < Double.MAX_VALUE) {
            out[0] = 2.0; out[1] = c.x + dx * tw; out[2] = c.y + dy * tw; out[3] = -1.0
        } else {
            out[0] = 0.0
        }
    }
}
