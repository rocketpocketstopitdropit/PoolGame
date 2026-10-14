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
    val objX = FloatArray(MAX); val objY = FloatArray(MAX)
    var objN = 0
    var scratch = false

    fun reset() { cueN = 0; contactIndex = -1; hitId = -1; objN = 0; scratch = false }

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
    fun run(
        src: World, scratch: World,
        angle: Double, speed: Double, side: Double, vert: Double, elevation: Double,
        out: Prediction,
        cueAfter: Double = 0.35, objLen: Double = 0.7
    ) {
        out.reset()
        scratch.copyFrom(src)
        scratch.listener = null
        scratch.trackOrientation = false
        val c = scratch.balls[0]
        if (!c.onTable) return
        scratch.strike(angle, speed, side, vert, elevation)
        out.addCue(c.x, c.y, false)
        var lx = c.x; var ly = c.y
        var travel = 0.0
        var after = 0.0
        var objTravel = 0.0
        var ox = 0.0; var oy = 0.0
        var t = 0.0
        var steps = 0
        // Bounded so aiming never stalls a frame, even when the shot breaks up a cluster.
        while (t < 4.0 && steps < 900 && scratch.anyMoving()) {
            t += scratch.step(0.02)
            steps++
            val log = scratch.log
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
            if (!c.onTable) { out.scratch = true; out.addCue(lx, ly, false); break }
            val d = hypot(c.x - lx, c.y - ly)
            if (d >= 0.006 || (c.z > 0) != out.cueAir[out.cueN - 1]) {
                val doneCue = out.hitId >= 0 && after >= cueAfter
                if (!doneCue) out.addCue(c.x, c.y, c.z > 0.002)
                if (out.hitId >= 0) after += d else travel += d
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
            } else if (log.cueCushionsBeforeHit >= 2 || travel > 3.0) {
                break
            }
        }
    }
}
