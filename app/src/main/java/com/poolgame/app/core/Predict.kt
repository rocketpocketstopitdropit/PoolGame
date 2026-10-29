package com.poolgame.app.core

import kotlin.math.hypot

/** The predicted outcome of a shot, used to draw the aim guide. */
class Prediction {
    companion object { const val MAX = 400 }
    val cueX = FloatArray(MAX); val cueY = FloatArray(MAX)
    val cueAir = BooleanArray(MAX)
    var cueN = 0
    var contactIndex = -1          // cue points from here on are after first contact
    var hitId = -1
    var hitX = 0.0; var hitY = 0.0 // cue-ball centre at contact (ghost ball)

    /** Whether the cue ball's centre gets past [x] (down the table) before it first touches a ball. */
    fun crossedLine(x: Double): Boolean {
        if (hitId >= 0 && hitX > x) return true
        val n = if (contactIndex >= 0) contactIndex else cueN
        for (i in 0 until minOf(n, cueN)) if (cueX[i] > x) return true
        return false
    }
    val objX = FloatArray(MAX); val objY = FloatArray(MAX)
    var objN = 0
    var scratch = false
    /** Where the cue ball first comes down after a jump (before it hits anything), or NaN. */
    var landX = Double.NaN
    var landY = Double.NaN
    /** The tip would slip off the ball (miscue). */
    var miscue = false
    /** The tip would dig under the ball and lift it: an illegal scoop jump (a foul). */
    var scoop = false

    fun reset() {
        cueN = 0; contactIndex = -1; hitId = -1; objN = 0; scratch = false; landX = Double.NaN; landY = Double.NaN
        miscue = false; scoop = false
    }

    internal fun addCue(x: Double, y: Double, air: Boolean) {
        if (cueN >= MAX) return
        cueX[cueN] = x.toFloat(); cueY[cueN] = y.toFloat(); cueAir[cueN] = air; cueN++
    }

    internal fun addObj(x: Double, y: Double) {
        if (objN >= MAX) return
        objX[objN] = x.toFloat(); objY[objN] = y.toFloat(); objN++
    }
}

/**
 * Runs the real physics for the first part of a shot, so the guide shows where balls actually
 * go — including throw, spin, and jump shots — instead of idealised ghost-ball lines.
 */
object Predictor {
    /** How far the guide follows the cue ball after it hits a ball (enough to see draw, follow and curve). */
    const val CUE_AFTER = 0.15
    /** How far it follows the object ball: just its starting direction, so the aim still takes skill. */
    const val OBJ_LEN = 0.28
    /** After the cue ball's first cushion (with no ball hit yet), only a short stub of the rebound is shown. */
    const val AFTER_CUSHION = 0.12

    fun run(
        src: World, scratch: World,
        angle: Double, speed: Double, side: Double, vert: Double, elevation: Double,
        out: Prediction,
        cueAfter: Double = CUE_AFTER, objLen: Double = OBJ_LEN
    ) {
        out.reset()
        scratch.copyFrom(src)
        scratch.listener = null
        scratch.trackOrientation = false
        val c = scratch.cueBall
        if (!c.onTable) return
        scratch.strike(angle, speed, side, vert, elevation)
        out.miscue = scratch.log.miscue
        out.scoop = scratch.log.scooped
        out.addCue(c.x, c.y, false)
        var lx = c.x; var ly = c.y
        var travel = 0.0
        var after = 0.0
        var objTravel = 0.0
        var ox = 0.0; var oy = 0.0
        var t = 0.0
        var steps = 0
        var afterCushion = 0.0
        // Bounded so aiming never stalls a frame, even when the shot breaks up a cluster.
        while (t < 4.0 && steps < 2500 && scratch.anyMoving()) {
            t += scratch.step(0.02)
            steps++
            val log = scratch.log
            if (out.landX.isNaN() && !log.cueLandX.isNaN() && (out.hitId < 0 || log.firstHit < 0)) {
                out.landX = log.cueLandX; out.landY = log.cueLandY
            }
            if (out.hitId < 0 && log.firstHit >= 0) {
                out.hitId = log.firstHit
                out.hitX = log.hitX; out.hitY = log.hitY
                out.addCue(log.hitX, log.hitY, false)
                out.contactIndex = out.cueN - 1
                lx = log.hitX; ly = log.hitY
                val o = scratch.balls[out.hitId]
                ox = o.x; oy = o.y
                out.addObj(ox, oy)
            }
            if (!c.onTable) {
                // Only show a scratch (or a drop into the gap) if it happens within the part of the
                // path the guide shows; otherwise the guide would draw a long line to where it ends up.
                if (out.hitId < 0 || after < cueAfter) { out.scratch = true; out.addCue(lx, ly, false) }
                break
            }
            val d = hypot(c.x - lx, c.y - ly)
            // In the air: off the cloth (on the octagon table, off the ramp under it), or over the gap.
            val air = c.z - Table.surfaceZ(c.x, c.y) > 0.002 || Table.overGap(c.x)
            if (d >= 0.006 || air != out.cueAir[out.cueN - 1]) {
                val doneCue = out.hitId >= 0 && after >= cueAfter
                if (!doneCue) out.addCue(c.x, c.y, air)
                if (out.hitId >= 0) after += d else { travel += d; if (log.cueCushionsBeforeHit >= 1) afterCushion += d }
                lx = c.x; ly = c.y
            }
            if (out.hitId >= 0) {
                val o = scratch.balls[out.hitId]
                if (o.onTable) {
                    val od = hypot(o.x - ox, o.y - oy)
                    if (od >= 0.006 && objTravel < objLen) { out.addObj(o.x, o.y); objTravel += od; ox = o.x; oy = o.y }
                } else if (objTravel < objLen) {
                    objTravel = objLen   // dropped
                }
                if (after >= cueAfter && objTravel >= objLen) break
            } else if (afterCushion >= AFTER_CUSHION || log.cueCushionsBeforeHit >= 2 || travel > 3.0) {
                break
            }
        }
    }
}
