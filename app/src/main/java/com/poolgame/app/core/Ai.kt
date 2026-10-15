package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.TimeSource

class Plan(
    val angle: Double,
    val speed: Double,
    val side: Double,
    val vert: Double,
    val kind: String,
    val placeX: Double = Double.NaN,
    val placeY: Double = Double.NaN,
    val elevation: Double = 0.0,
    val pocket: Int = -1           // the pocket the CPU is calling (index into Table.pockets), or -1
) {
    val hasPlacement get() = !placeX.isNaN()
    fun withPlacement(x: Double, y: Double) = Plan(angle, speed, side, vert, kind, x, y, elevation, pocket)
}

class Cand(
    val target: Int, val pocket: Int, val angle: Double,
    val cosCut: Double, val d1: Double, val d2: Double, val p: Double
)

/**
 * Computer opponent. Skill 1..10 controls:
 *  - execution noise (aim, power, spin) — the dominant factor
 *  - how many shots it considers and whether it plays for position with speed and spin
 *  - whether it plays safeties when nothing is makeable
 * Every candidate is checked by running the real physics engine on a copy of the table.
 */
class Ai(val level: Int, seed: Long = Random.Default.nextLong()) {
    private val rng = Random(seed)
    val aimSigma = toRad(1.8 * 0.72.pow(level - 1))
    private val speedSigma = 0.14 * 0.8.pow(level - 1)
    private val spinSigma = 0.10 * 0.8.pow(level - 1)
    private val posWeight = if (level <= 2) 0.0 else 0.1 * level
    private var started = TimeSource.Monotonic.markNow()
    private var budget = 0L
    private var sim: Match? = null
    var simulations = 0
        private set

    fun plan(m: Match, budgetMs: Long = 2500): Plan {
        started = TimeSource.Monotonic.markNow()
        budget = budgetMs
        simulations = 0
        val ideal = if (m.mode == GameMode.TRICK) trickShot(m) else choose(m)
        return Plan(
            ideal.angle + gauss() * aimSigma,
            (ideal.speed * (1 + gauss() * speedSigma)).coerceIn(0.2, P.MAX_CUE_SPEED),
            ideal.side + gauss() * spinSigma,
            ideal.vert + gauss() * spinSigma,
            ideal.kind, ideal.placeX, ideal.placeY, ideal.elevation, ideal.pocket
        )
    }

    private fun timeUp() = started.elapsedNow().inWholeMilliseconds > budget
    private fun gauss(): Double {
        val u1 = rng.nextDouble(1e-12, 1.0); val u2 = rng.nextDouble()
        return sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
    }

    // ------------------------------------------------------------------ trick shots

    /**
     * Trick shots: start from shots known to work on this level, then search for more with the
     * physics engine. Each candidate is scored by how often it still goes in with this level's
     * own aim and speed error, so stronger CPUs pick (and find) the more forgiving shots.
     */
    private fun trickShot(m: Match): Plan {
        val w = World()
        val base = m.world
        val lv = Trick.LEVELS[m.trick.level]
        fun run(s: DoubleArray): Boolean {
            if (s[0] > Table.GAP_X0 || !base.isFree(s[0], s[1], 0)) return false
            w.copyFrom(base)
            val c = w.balls[0]
            c.x = s[0]; c.y = s[1]; c.onTable = true; c.stop()
            w.strike(s[2], s[3].coerceIn(0.2, P.MAX_CUE_SPEED), s[4], s[5], s[6])
            w.simulate()
            simulations++
            return Trick.judge(lv, w.log).made
        }
        val samples = if (level >= 6) 10 else 6
        fun score(s: DoubleArray): Double {
            var ok = 0
            repeat(samples) {
                val t = s.copyOf()
                t[2] += gauss() * aimSigma
                t[3] *= 1 + gauss() * speedSigma
                t[4] += gauss() * spinSigma; t[5] += gauss() * spinSigma
                if (run(t)) ok++
            }
            return ok.toDouble() / samples
        }
        var best: DoubleArray? = null
        var bestScore = -1.0
        fun consider(s: DoubleArray) {
            if (!run(s)) return
            val sc = score(s)
            if (sc > bestScore) { best = s; bestScore = sc }
        }
        val hints = Trick.HINTS.getOrNull(m.trick.level) ?: emptyList()
        // Weaker players don't know every trick: they try a couple of the known shots at random.
        for (h in if (level <= 3) hints.shuffled(rng).take(2) else hints) consider(h)
        val tb = base.balls[Trick.TARGET]
        val searchUntil = if (level <= 3) 0.25 else 1.0
        while (started.elapsedNow().inWholeMilliseconds < budget * searchUntil) {
            val b0 = best
            val s = if (b0 != null && rng.nextDouble() < 0.5) {
                val t = b0.copyOf()
                t[0] = (t[0] + 0.01 * gauss()).coerceIn(P.R, Table.GAP_X0 - P.R)
                t[1] = (t[1] + 0.01 * gauss()).coerceIn(P.R, P.W - P.R)
                t[2] += toRad(0.3) * gauss()
                t[3] = (t[3] * (1 + 0.02 * gauss())).coerceIn(1.0, P.MAX_CUE_SPEED)
                t[6] = (t[6] + toRad(1.5) * gauss()).coerceIn(0.0, toRad(P.MAX_ELEVATION))
                t
            } else {
                val cx = rng.nextDouble(0.06, Table.GAP_X0 - 0.03)
                val cy = rng.nextDouble(0.06, P.W - 0.06)
                doubleArrayOf(
                    cx, cy,
                    atan2(tb.y - cy, tb.x - cx) + toRad(rng.nextDouble(-35.0, 35.0)),
                    rng.nextDouble(2.0, P.MAX_CUE_SPEED),
                    if (rng.nextDouble() < 0.7) 0.0 else rng.nextDouble(-0.4, 0.4),
                    if (rng.nextBoolean()) 0.0 else rng.nextDouble(-0.45, 0.45),
                    toRad(rng.nextDouble(15.0, 60.0))
                )
            }
            consider(s)
        }
        val s = best
        if (s != null) return Plan(s[2], s[3], s[4], s[5], "trick", s[0], s[1], s[6])
        // Nothing found: a hopeful jump straight at the 1.
        val c = base.balls[0]
        return Plan(atan2(tb.y - c.y, tb.x - c.x), 6.5, 0.0, 0.0, "trick", elevation = toRad(40.0))
    }

    // ------------------------------------------------------------------ top level

    private fun choose(m: Match): Plan {
        if (m.ballInHand && m.isBreak) return breakPlan(m)
        val work = Match(m.mode, m.names).also { it.copyFrom(m) }
        sim = Match(m.mode, m.names)
        if (m.ballInHand) {
            val (x, y) = choosePlacement(work)
            work.placeCue(x, y)
            return chooseShot(work).withPlacement(x, y)
        }
        return chooseShot(work)
    }

    private fun breakPlan(m: Match): Plan {
        val y = P.FOOT_Y + rng.nextDouble(-0.22, 0.22)
        val x = P.HEAD_X - 0.01
        val apex = (1..15).filter { m.world.balls[it].onTable }
            .minByOrNull { hypot(m.world.balls[it].x - P.FOOT_X, m.world.balls[it].y - P.FOOT_Y) } ?: 1
        val a = m.world.balls[apex]
        val angle = atan2(a.y - y, a.x - x)
        return Plan(angle, min(P.MAX_CUE_SPEED, 6.2 + 0.23 * level), 0.0, -0.12, "break", x, y)
    }

    // ------------------------------------------------------------------ shot geometry

    private fun erf(x: Double): Double {
        val s = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return s * y
    }

    /** Probability of potting given geometry and an aim error with std-dev sigma (radians). */
    private fun prob(cosCut: Double, d1: Double, d2: Double, tol: Double, sigma: Double): Double {
        val dm = tol * 2 * P.R * cosCut / (max(d1, 0.06) * max(d2, 0.06))
        return erf(dm / (sigma * 1.41421356)) * (0.75 + 0.25 * cosCut)
    }

    /** All direct pots from the cue ball's current spot. */
    fun candidates(m: Match, player: Int, sigma: Double, blockedOnly: Boolean = false): ArrayList<Cand> {
        val out = ArrayList<Cand>()
        val w = m.world
        val c = w.balls[0]
        if (!c.onTable) return out
        val r = P.R
        val minCos = cos(toRad(80.0))
        for (t in m.legalTargets(player)) {
            val tb = w.balls[t]
            if (!tb.onTable) continue
            for ((pi, pk) in Table.pockets.withIndex()) {
                val ox = pk.aimX - tb.x; val oy = pk.aimY - tb.y
                val d2 = hypot(ox, oy)
                if (d2 < 1e-6) continue
                val ux = ox / d2; val uy = oy / d2
                if (pk.side) {
                    // Side pockets need a fairly square approach.
                    val inward = if (pk.cy < P.W / 2) -uy else uy
                    if (inward < 0.55) continue
                }
                val gx = tb.x - ux * 2 * r; val gy = tb.y - uy * 2 * r
                if (gx < r * 0.98 || gx > P.L - r * 0.98 || gy < r * 0.98 || gy > P.W - r * 0.98) continue
                val vx = gx - c.x; val vy = gy - c.y
                val d1 = hypot(vx, vy)
                if (d1 < 1e-6) continue
                val cosCut = (vx * ux + vy * uy) / d1
                if (cosCut < minCos) continue
                // Jaw clearance for the object ball
                val tips = pk.tips
                val margin = min(
                    Table.pointSegDist(tips[0], tips[1], tb.x, tb.y, pk.aimX, pk.aimY),
                    Table.pointSegDist(tips[2], tips[3], tb.x, tb.y, pk.aimX, pk.aimY)
                ) - r
                if (margin < 0.003) continue
                val cueClear = w.pathClear(c.x, c.y, gx, gy, 0, t, 2 * r - 0.0005)
                if (cueClear == blockedOnly) continue
                if (!w.pathClear(tb.x, tb.y, pk.aimX, pk.aimY, 0, t, 2 * r - 0.0005)) continue
                val tol = min(pk.tolerance, margin)
                val p = prob(cosCut, d1, d2, tol, sigma)
                out += Cand(t, pi, atan2(vy, vx), cosCut, d1, d2, p)
            }
        }
        out.sortByDescending { it.p }
        return out
    }

    private fun bestP(m: Match, player: Int, sigma: Double): Double {
        if (m.ballInHand) return 0.9
        return candidates(m, player, sigma).firstOrNull()?.p ?: 0.0
    }

    private fun baseSpeed(c: Cand): Double {
        val a = P.MU_ROLL * P.G
        val vo = 1.4 * sqrt(0.35 * 0.35 + 2 * a * c.d2)
        val vc = vo / (max(c.cosCut, 0.25) * 0.97)
        return (1.1 * sqrt(vc * vc + 2 * a * c.d1 * 1.5)).coerceIn(0.7, 6.0)
    }

    // ------------------------------------------------------------------ ball in hand

    private fun choosePlacement(work: Match): Pair<Double, Double> {
        val me = work.current
        val w = work.world
        val r = P.R
        var best = -1.0
        var bx = Double.NaN; var by = Double.NaN
        val dists = doubleArrayOf(0.18, 0.3, 0.5)
        val cuts = if (level >= 6) doubleArrayOf(12.0, -12.0, 0.0, 22.0, -22.0) else doubleArrayOf(0.0, 15.0, -15.0)
        for (t in work.legalTargets(me)) {
            val tb = w.balls[t]
            if (!tb.onTable) continue
            for (pk in Table.pockets) {
                val ox = pk.aimX - tb.x; val oy = pk.aimY - tb.y
                val d2 = hypot(ox, oy)
                val ux = ox / d2; val uy = oy / d2
                if (pk.side) {
                    val inward = if (pk.cy < P.W / 2) -uy else uy
                    if (inward < 0.55) continue
                }
                val tips = pk.tips
                val margin = min(
                    Table.pointSegDist(tips[0], tips[1], tb.x, tb.y, pk.aimX, pk.aimY),
                    Table.pointSegDist(tips[2], tips[3], tb.x, tb.y, pk.aimX, pk.aimY)
                ) - r
                if (margin < 0.003) continue
                if (!w.pathClear(tb.x, tb.y, pk.aimX, pk.aimY, 0, t)) continue
                val gx = tb.x - ux * 2 * r; val gy = tb.y - uy * 2 * r
                for (cutDeg in cuts) for (d in dists) {
                    val a = atan2(uy, ux) + toRad(cutDeg)
                    val px = gx - cos(a) * d; val py = gy - sin(a) * d
                    if (!work.canPlaceCue(px, py)) continue
                    if (!w.pathClear(px, py, gx, gy, 0, t)) continue
                    var s = prob(cos(toRad(cutDeg)), d, d2, min(pk.tolerance, margin), aimSigma)
                    if (level >= 6 && cutDeg != 0.0 && abs(cutDeg) < 15) s *= 1.04  // a little angle helps position
                    if (s > best) { best = s; bx = px; by = py }
                }
            }
        }
        if (bx.isNaN()) {
            // Nothing obvious: sample spots and keep the one with the best follow-up.
            val tmp = Match(work.mode, work.names).also { it.copyFrom(work) }
            repeat(150) {
                val px = rng.nextDouble(r, (if (work.kitchenOnly) P.HEAD_X else P.L - r))
                val py = rng.nextDouble(r, P.W - r)
                if (work.canPlaceCue(px, py)) {
                    tmp.placeCue(px, py)
                    tmp.ballInHand = false
                    val s = bestP(tmp, me, aimSigma)
                    if (s > best || bx.isNaN()) { best = s; bx = px; by = py }
                }
            }
        }
        if (bx.isNaN()) { bx = w.balls[0].x; by = w.balls[0].y }
        return bx to by
    }

    // ------------------------------------------------------------------ choosing the shot

    private class Eval(val value: Double, val potted: Boolean, val foul: Boolean)

    /** Run the shot noiselessly and score the result from `me`'s point of view. */
    private fun evaluate(work: Match, angle: Double, speed: Double, side: Double, vert: Double, elev: Double = 0.0, pocket: Int = -1): Eval {
        val s = sim!!
        s.copyFrom(work)
        s.calledPocket = pocket
        s.shoot(angle, speed, side, vert, elev)
        s.world.simulate()
        simulations++
        val me = work.current
        val out = s.resolve()
        return when {
            out.winner == me -> Eval(3.0, true, false)
            out.winner >= 0 -> Eval(-3.0, false, true)
            out.foul -> Eval(-0.9, false, true)
            out.keepTurn -> Eval(1.0 + posWeight * bestP(s, me, aimSigma), true, false)
            else -> Eval(-0.5 * bestP(s, 1 - me, aimSigma), false, false)
        }
    }

    private fun chooseShot(work: Match): Plan {
        val me = work.current
        val cands = candidates(work, me, aimSigma)
        val k = if (level <= 3) 2 else min(2 + level, 10)
        val speedF = when {
            level <= 3 -> doubleArrayOf(1.25)
            level <= 6 -> doubleArrayOf(1.1, 1.6)
            else -> doubleArrayOf(1.0, 1.35, 1.9)
        }
        val spins: List<Pair<Double, Double>> = when {
            level <= 4 -> listOf(0.0 to 0.0)
            level <= 7 -> listOf(0.0 to 0.0, 0.0 to 0.35, 0.0 to -0.35)
            else -> listOf(0.0 to 0.0, 0.0 to 0.35, 0.0 to -0.35, 0.3 to 0.2, -0.3 to 0.2, 0.3 to -0.3, -0.3 to -0.3)
        }
        var best: Plan? = null
        var bestScore = -10.0
        // Weaker players sometimes go for a shot that isn't their best look.
        val pool = if (level <= 3 && cands.size > 1 && rng.nextDouble() < 0.4) cands.shuffled(rng) else cands
        loop@ for (c in pool.take(k)) {
            val base = baseSpeed(c)
            for (f in speedF) for ((sd, vt) in spins) {
                if (timeUp() && best != null) break@loop
                val speed = (base * f * (if (vt < 0) 1.15 else 1.0)).coerceAtMost(7.0)
                val e = evaluate(work, c.angle, speed, sd, vt, pocket = c.pocket)
                val score = if (e.potted) c.p * e.value - (1 - c.p) * 0.3 else e.value - 0.6
                if (score > bestScore) {
                    bestScore = score
                    best = Plan(c.angle, speed, sd, vt, "pot ${c.target}", pocket = c.pocket)
                }
            }
        }
        // Strong players will jump over a blocker to reach a makeable ball.
        if (level >= 7 && (best == null || bestScore < 0.2) && !timeUp()) {
            for (c in candidates(work, me, aimSigma, blockedOnly = true).take(4)) {
                for (el in doubleArrayOf(0.6, 0.8)) for (sp in doubleArrayOf(3.5, 4.5, 5.5)) {
                    if (timeUp() && best != null) break
                    val e = evaluate(work, c.angle, sp, 0.0, 0.0, el, c.pocket)
                    if (!e.potted) continue
                    val p = c.p * 0.6     // jumps are harder to control
                    val score = p * e.value - (1 - p) * 0.3
                    if (score > bestScore) {
                        bestScore = score
                        best = Plan(c.angle, sp, 0.0, 0.0, "jump ${c.target}", elevation = el, pocket = c.pocket)
                    }
                }
            }
        }
        val needSafety = best == null || (level >= 5 && bestScore < 0.15)
        if (needSafety && !timeUp()) {
            val (safe, safeScore) = safetySearch(work, if (level >= 5) 7 else 3)
            if (safe != null && (best == null || safeScore > bestScore)) {
                best = safe; bestScore = safeScore
            }
        }
        if (best == null || bestScore <= -0.9) {
            val (kick, kickScore) = kickSearch(work)
            if (kick != null && (best == null || kickScore > bestScore)) best = kick
        }
        return best ?: Plan(rng.nextDouble(0.0, 2 * PI), 2.0, 0.0, 0.0, "desperation")
    }

    /** Hit a legal ball at varying thickness and speed; prefer leaving the opponent nothing. */
    private fun safetySearch(work: Match, n: Int): Pair<Plan?, Double> {
        val w = work.world
        val c = w.balls[0]
        var best: Plan? = null
        var bestScore = -10.0
        val speeds = if (level >= 6) doubleArrayOf(0.9, 1.5, 2.4) else doubleArrayOf(1.2, 2.2)
        outer@ for (t in work.legalTargets(work.current)) {
            val tb = w.balls[t]
            if (!tb.onTable) continue
            val dx = tb.x - c.x; val dy = tb.y - c.y
            val d = hypot(dx, dy)
            val px = -dy / d; val py = dx / d
            for (i in 0 until n) {
                val f = if (n == 1) 0.0 else -0.85 + 1.7 * i / (n - 1)
                val ax = tb.x + px * f * 2 * P.R; val ay = tb.y + py * f * 2 * P.R
                if (!w.pathClear(c.x, c.y, ax, ay, 0, t, 2 * P.R - 0.0005)) continue
                val angle = atan2(ay - c.y, ax - c.x)
                for (s in speeds) {
                    if (timeUp() && best != null) break@outer
                    val e = evaluate(work, angle, s, 0.0, 0.0)
                    if (e.value > bestScore) { bestScore = e.value; best = Plan(angle, s, 0.0, 0.0, "safety") }
                }
            }
        }
        return best to bestScore
    }

    /** Snookered: sweep around the clock looking for any legal contact off the rails. */
    private fun kickSearch(work: Match): Pair<Plan?, Double> {
        var best: Plan? = null
        var bestScore = -10.0
        val steps = if (level >= 6) 180 else 90
        for (i in 0 until steps) {
            if (timeUp() && best != null) break
            val angle = 2 * PI * i / steps
            val e = evaluate(work, angle, 3.0, 0.0, 0.0)
            if (e.value > bestScore) { bestScore = e.value; best = Plan(angle, 3.0, 0.0, 0.0, "kick") }
        }
        return best to bestScore
    }
}
