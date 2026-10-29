package com.poolgame.app.core

/** What a level asks of the shot, on top of sinking the 1. */
enum class TrickRule {
    /** Just sink the 1. Extra balls in pockets are bonuses. */
    PLAIN,
    /** The cue ball must hit a cushion before the 1. Each extra cushion before it is a bonus. */
    CUSHION,
    /** The cue ball can't touch the fence until it has come down on the far section. */
    FENCE,
    /** The cue ball may touch only the 1. */
    ONLY_THE_1,
    /** Cut the 1 (no straight-on hit) and thread it through: the cue ball must hit the 1 first and the 1 may touch nothing else. */
    THREAD,
    /** The 1 has to hit a cushion before it drops. */
    BANK
}

/**
 * A trick-shot level: a fixed layout of balls and a rule. The gap jumps are on the gap table: the cue ball starts in
 * the break area on the near section and has to jump the gap to sink the 1-ball (yellow) on the far section. The
 * classics ([standard]) are well-known trick shots on an ordinary 9 ft table.
 */
class TrickLevel(
    val name: String,
    val blurb: String,
    val rule: TrickRule,
    /** Object balls as (id, x, y). The 1-ball is always the target. */
    val balls: List<Triple<Int, Double, Double>>,
    /** Holes cut into the cloth (x, y). */
    val holes: List<DoubleArray> = emptyList(),
    val cueX: Double = 0.32,
    val cueY: Double = TableSize.NINE.width / 2,
    /** The far end's two corner pockets (some levels fill them in). */
    val farCorners: Boolean = true,
    /**
     * Where the cue ball may be placed: rectangles (x0, y0, x1, y1) for its centre. Empty = anywhere
     * in the break area. With zones, [pens] gives the balls that surround each zone (the same ids
     * for every zone): they move to whichever zone the cue ball is put in.
     */
    val zones: List<DoubleArray> = emptyList(),
    val pens: List<List<Triple<Int, Double, Double>>> = emptyList(),
    /** On the ordinary 9 ft table (the classics) rather than the gap table. */
    val standard: Boolean = false,
    /** The ball the cue ball has to hit first, or -1 for no rule about it. */
    val firstBall: Int = -1,
    /** Every one of these has to go down (the 1, or the 1 and others). */
    val mustSink: List<Int> = listOf(Trick.TARGET),
    /** THREAD levels: how much the 1 has to be cut, in degrees (0 = any hit). */
    val minCut: Double = Trick.MIN_CUT_DEG
) {
    /** Which set of levels this is in: 0 the gap jumps, 1 the classics. */
    val set: Int get() = if (standard) 1 else 0
    val ids: List<Int> = balls.map { it.first }
    private val others = ids.filter { it != Trick.TARGET }
    /** Bit mask of the balls other than the 1. */
    val otherMask: Long = others.fold(0L) { m, id -> m or (1L shl id) }
    /** The most bonus a make here could realistically bring (used to decide when a player is out of it). */
    val maxBonus: Int = when (rule) {
        TrickRule.THREAD -> 0
        TrickRule.CUSHION -> 2
        TrickRule.BANK -> 1
        else -> minOf(2, others.size)
    }
}

/** How one attempt went. [bonus] is how many attempts it takes off (extra balls or cushions). */
class TrickJudgement(val made: Boolean, val why: String, val bonus: Int, val bonusText: String)

/** Where a trick-shot match stands. */
class TrickState {
    /** How many levels have been played (the level being played now is levelNo + 1). */
    var levelNo = 0
    /** Index into Trick.LEVELS of the level being played. */
    var level = 0
    /** The set of levels a match plays: 0 the gap jumps, 1 the classics (see [Trick.SETS]). */
    var set = 0
    /** Who shoots first on this level (it alternates level to level). */
    var starter = 0
    var suddenDeath = false
    /** Attempts each player has taken on this level. */
    val tries = intArrayOf(0, 0)
    /** The attempt each player made it on (0 = not yet). */
    val made = intArrayOf(0, 0)
    /** Attempts taken off by the making shot (extra balls, extra cushions). */
    val bonus = intArrayOf(0, 0)
    /** A player is done with the level: made it, ran out of attempts, or can't catch up any more. */
    val done = booleanArrayOf(false, false)
    val levelWins = intArrayOf(0, 0)
    /** Finished levels: the winner (0/1) or -1 for a tie. */
    val results = ArrayList<Int>()
    /** The balls go back to the level's layout before the next attempt. */
    var resetPending = false

    /** Attempts minus bonuses, or a large number if the player never made it. */
    fun score(p: Int): Int = if (made[p] > 0) made[p] - bonus[p] else 1000

    fun clearLevel() {
        tries.fill(0); made.fill(0); bonus.fill(0); done.fill(false)
    }

    fun reset() {
        levelNo = 0; level = Trick.levelsOf(set).first(); starter = 0; suddenDeath = false
        clearLevel(); levelWins.fill(0); results.clear(); resetPending = false
    }

    fun copyFrom(o: TrickState) {
        levelNo = o.levelNo; level = o.level; set = o.set; starter = o.starter; suddenDeath = o.suddenDeath
        o.tries.copyInto(tries); o.made.copyInto(made); o.bonus.copyInto(bonus); o.done.copyInto(done)
        o.levelWins.copyInto(levelWins)
        results.clear(); results.addAll(o.results); resetPending = o.resetPending
    }
}

object Trick {
    const val TARGET = 1
    /** Attempts each player gets per level. */
    const val MAX_TRIES = 8
    /** Traffic jam: the 1 has to be cut at least this much. */
    const val MIN_CUT_DEG = 12.0

    private val MID = TableSize.NINE.width / 2   // trick shots are always on the 9 ft table

    /** The two sets of levels a match can play. */
    val SETS = listOf("Gap jumps", "Classics")

    /** The levels (indices into [LEVELS]) in set [set], in the order a match plays them. */
    fun levelsOf(set: Int): List<Int> = LEVELS.indices.filter { LEVELS[it].set == set }.ifEmpty { listOf(0) }

    val LEVELS: List<TrickLevel> = listOf(
        TrickLevel(
            "Leap of faith", "Jump the gap and sink the 1 in a far corner",
            TrickRule.PLAIN,
            listOf(Triple(1, 2.10, 0.21))
        ),
        TrickLevel(
            "Off the rail", "The cue ball has to hit a cushion before the 1 · extra cushions take a shot off",
            TrickRule.CUSHION,
            listOf(Triple(1, 2.0, 0.20))
        ),
        TrickLevel(
            "Picket fence", "Clear the fence: the cue ball can't touch it until it lands",
            TrickRule.FENCE,
            listOf(Triple(1, 2.25, 0.30)) + (0 until 7).map { Triple(2 + it, 1.335, MID - 0.27 + it * 0.09) }
        ),
        TrickLevel(
            "Bullseye", "Jump the wall and sink the 1 · touch the ring and it's a miss",
            TrickRule.ONLY_THE_1,
            // The side of the ring facing you is a solid wall of nine balls (no gaps to roll through);
            // the far side is open, toward the top-right corner.
            listOf(Triple(1, 1.95, 0.30)) + (0..8).map { k ->
                val a = toRad(90.0 + 22.5 * k)
                Triple(2 + k, 1.95 + 0.26 * kotlin.math.cos(a), 0.30 + 0.26 * kotlin.math.sin(a))
            }
        ),
        TrickLevel(
            "Traffic jam", "Cut the 1 and thread it through the traffic without touching any",
            TrickRule.THREAD,
            trafficLayout()
        ),
        bankJob()
    ) + Classics.LEVELS

    /**
     * Bank job: start from either pen of balls on the near section (drag the cue ball into the top
     * or bottom one; the pen moves with it) and jump out of it. On the far section a row of four
     * balls runs along each long cushion with a ball tucked behind it: the 1 at the top, the 10 at
     * the bottom. The far corner pockets are filled in; the only pocket is a hole in the middle of
     * the far section. Sinking the 10 as well is a bonus.
     */
    private fun bankJob(): TrickLevel {
        val w = TableSize.NINE.width
        fun mirror(t: List<Triple<Int, Double, Double>>) = t.map { Triple(it.first, it.second, w - it.third) }
        val top = (0 until 4).map { Triple(2 + it, 1.62 + it * 0.075, 0.30) }
        val bottom = (0 until 4).map { Triple(6 + it, 1.62 + it * 0.075, w - 0.30) }
        val targets = listOf(Triple(1, 1.80, 0.13), Triple(10, 1.80, w - 0.13))
        // Three balls in front (toward the gap) and two on the inside; the cushion closes the pen.
        val penTop = listOf(Triple(11, 0.53, 0.12), Triple(12, 0.53, 0.215), Triple(13, 0.53, 0.31),
            Triple(14, 0.36, 0.37), Triple(15, 0.45, 0.37))
        val zoneTop = doubleArrayOf(0.30, 0.13, 0.46, 0.30)
        return TrickLevel(
            "Bank job", "Start in either pen · jump out and get the 1 into the hole",
            TrickRule.PLAIN,
            top + bottom + targets + penTop,
            holes = listOf(doubleArrayOf(1.905, MID)),
            cueX = 0.38, cueY = 0.215,
            farCorners = false,
            zones = listOf(zoneTop, doubleArrayOf(zoneTop[0], w - zoneTop[3], zoneTop[2], w - zoneTop[1])),
            pens = listOf(penTop, mirror(penTop))
        )
    }

    /**
     * Traffic jam: the 1 sits off the line to the bottom-right corner, with four balls staggered
     * either side of its path, each leaving only a couple of centimetres to spare, plus a few strays.
     */
    private fun trafficLayout(): List<Triple<Int, Double, Double>> {
        val ox = 1.85; val oy = 0.95
        val ax = 2.506; val ay = 1.236                 // the bottom-right pocket's mouth
        val len = kotlin.math.hypot(ax - ox, ay - oy)
        val ux = (ax - ox) / len; val uy = (ay - oy) / len
        val off = 2 * P.R + 0.02
        val out = ArrayList<Triple<Int, Double, Double>>()
        out += Triple(1, ox, oy)
        listOf(0.20 to off, 0.38 to -off, 0.56 to off, 0.74 to -off).forEachIndexed { k, (t, o) ->
            out += Triple(2 + k, ox + ux * len * t - uy * o, oy + uy * len * t + ux * o)
        }
        out += Triple(6, 1.62, 1.10); out += Triple(7, 2.15, 0.55); out += Triple(8, 2.40, 0.85)
        return out
    }

    /** The zone (index) containing (x, y), or -1. */
    fun zoneAt(lv: TrickLevel, x: Double, y: Double): Int =
        lv.zones.indexOfFirst { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] }

    /** The nearest allowed spot for the cue ball's centre to (x, y), for dragging it around. */
    fun clampPlacement(lv: TrickLevel, x: Double, y: Double): DoubleArray {
        val r = P.R
        if (lv.zones.isEmpty() && lv.standard) return doubleArrayOf(x.coerceIn(r, P.L - r), y.coerceIn(r, P.W - r))
        if (lv.zones.isEmpty()) return doubleArrayOf(x.coerceIn(r, Table.GAP_X0 - 0.61 * r), y.coerceIn(r, P.W - r))
        var best = doubleArrayOf(x, y); var bd = Double.MAX_VALUE
        for (z in lv.zones) {
            val cx = x.coerceIn(z[0], z[2]); val cy = y.coerceIn(z[1], z[3])
            val d = (cx - x) * (cx - x) + (cy - y) * (cy - y)
            if (d < bd) { bd = d; best = doubleArrayOf(cx, cy) }
        }
        return best
    }

    /**
     * Put the cue ball at (x, y) for this level: it must be in the break area (or one of the level's
     * zones, in which case that zone's pen of balls moves there with it). Returns false if it can't go there.
     */
    fun place(lv: TrickLevel, w: World, x: Double, y: Double): Boolean {
        val r = P.R
        if (lv.zones.isEmpty()) {
            if ((!lv.standard && x > Table.GAP_X0 - 0.61 * r) || !w.isFree(x, y, 0)) return false
        } else {
            val zi = zoneAt(lv, x, y)
            if (zi < 0) return false
            for ((id, px, py) in lv.pens.getOrNull(zi) ?: emptyList()) {
                val b = w.balls[id]
                b.x = px; b.y = py; b.onTable = true; b.stop()
            }
            if (!w.isFree(x, y, 0)) return false
        }
        val c = w.balls[0]
        c.x = x; c.y = y; c.onTable = true; c.stop()
        return true
    }

    /** Was this attempt a make under the level's rules, and how many attempts does it take off? */
    fun judge(lv: TrickLevel, log: ShotLog): TrickJudgement {
        val cueLost = 0 in log.pocketed || 0 in log.jumpedOff
        fun miss(why: String) = TrickJudgement(false, why, 0, "")
        when {
            log.scooped -> return miss("scoop shot — dig under the ball and it's a foul; raise the cue to jump")
            0 in log.fell -> return miss("the cue ball fell into the gap")
            0 in log.jumpedOff -> return miss("the cue ball flew off the table")
            0 in log.pocketed -> return miss("scratch")
            TARGET in log.fell -> return miss("the 1 fell into the gap")
            TARGET in log.jumpedOff -> return miss("the 1 flew off the table")
            log.firstHit < 0 -> return miss("didn't hit a ball")
            lv.firstBall >= 0 && log.firstHit != lv.firstBall -> return miss("the cue ball has to hit the ${lv.firstBall} first")
        }
        when (lv.rule) {
            TrickRule.CUSHION -> if (log.firstHit != TARGET || log.cueCushionsBeforeHit < 1)
                return miss("the cue ball has to hit a cushion before the 1")
            TrickRule.FENCE -> if (log.cueAirTouched and lv.otherMask != 0L)
                return miss("the cue ball touched the fence before it landed")
            TrickRule.ONLY_THE_1 -> if (log.touched[0] and lv.otherMask != 0L)
                return miss("the cue ball touched the ring")
            TrickRule.THREAD -> {
                if (log.firstHit != TARGET) return miss("the cue ball touched the traffic first")
                if (log.touched[TARGET] and lv.otherMask != 0L) return miss("the 1 touched the traffic")
                if (lv.minCut > 0 && log.firstHit == TARGET && !(log.cutDeg >= lv.minCut)) return miss("hit straight on — the 1 has to be cut")
            }
            TrickRule.BANK -> if (TARGET in log.pocketed && (log.banked and (1L shl TARGET)) == 0L)
                return miss("the 1 has to hit a cushion before it drops")
            TrickRule.PLAIN -> {}
        }
        if (TARGET !in log.pocketed || cueLost) return miss("the 1 stayed up")
        for (id in lv.mustSink) if (id !in log.pocketed) return miss("the $id stayed up")
        return when (lv.rule) {
            TrickRule.CUSHION -> {
                val extra = log.cueCushionsBeforeHit - 1
                TrickJudgement(true, "", extra, if (extra > 0) "+$extra for ${if (extra == 1) "an extra cushion" else "$extra extra cushions"}" else "")
            }
            TrickRule.THREAD -> TrickJudgement(true, "", 0, "")
            else -> {
                val extra = log.pocketed.filter { it != 0 && it !in lv.mustSink }
                TrickJudgement(true, "", extra.size,
                    if (extra.isEmpty()) "" else "+${extra.size} for the ${extra.joinToString(" and ")}")
            }
        }
    }

    /**
     * Shots known to work for each level, found offline by searching with the physics engine:
     * (placeX, placeY, angle, speed, side, vert, elevation). The CPU starts its search from these.
     */
    val HINTS: List<List<DoubleArray>> = listOf(
        // Leap of faith
        listOf(
            doubleArrayOf(0.53714, 1.07201, -0.50730, 7.83816, 0.00000, -0.00233, 0.24668),
            doubleArrayOf(0.60565, 1.17326, -0.57891, 7.58624, 0.00000, 0.00000, 0.25871),
            doubleArrayOf(0.51689, 0.88283, -0.40087, 6.80887, 0.00000, -0.23014, 0.33828),
            doubleArrayOf(0.48954, 1.10067, -0.50859, 8.05854, 0.00000, 0.00966, 0.26819),
            doubleArrayOf(0.49122, 1.14933, -0.53321, 7.42344, 0.00000, -0.01762, 0.28358),
            doubleArrayOf(0.58778, 1.21031, -0.59182, 6.78303, 0.00000, 0.00000, 0.30642),
        ),
        // Off the rail
        listOf(
            doubleArrayOf(0.60354, 0.47131, -0.46320, 7.82276, 0.00000, 0.00000, 0.32552),
            doubleArrayOf(0.46341, 0.59064, -0.49328, 8.50000, 0.03696, 0.00000, 0.21933),
            doubleArrayOf(0.51506, 0.50744, -0.47398, 8.94841, 0.00000, 0.24039, 0.33571),
            doubleArrayOf(0.57057, 0.51454, -0.47139, 6.87654, 0.00000, -0.15330, 0.45714),
            doubleArrayOf(0.61212, 0.53776, -0.49550, 6.19216, 0.00000, -0.38284, 0.37761),
        ),
        // Picket fence
        listOf(
            doubleArrayOf(0.47362, 0.55435, -0.33348, 8.14471, 0.00000, 0.29301, 0.21922),
            doubleArrayOf(0.32211, 0.08689, -0.23096, 8.24729, 0.11561, 0.00000, 0.27354),
            doubleArrayOf(0.55845, 1.14006, -0.40763, 7.56776, -0.25682, 0.00000, 0.42755),
            doubleArrayOf(0.42824, 1.15871, -0.42858, 7.04875, 0.00000, -0.27814, 0.62930),
            doubleArrayOf(0.54788, 1.23957, 0.57614, 7.75283, 0.00000, 0.00000, 0.50273),
            doubleArrayOf(0.52909, 1.18288, 0.59655, 7.64635, 0.00000, 0.00000, 0.52301),
        ),
        // Bullseye
        listOf(
            doubleArrayOf(0.47951, 0.56191, -0.16743, 6.87943, 0.00000, 0.31234, 0.58074),
            doubleArrayOf(0.53838, 0.65854, -0.24262, 7.16277, 0.00000, -0.13986, 0.67987),
            doubleArrayOf(0.46255, 0.97940, -0.24314, 7.37784, -0.36563, 0.00000, 0.72500),
            doubleArrayOf(0.28443, 0.48659, -0.10062, 7.03058, 0.00000, 0.24687, 0.76275),
            doubleArrayOf(0.56143, 0.48410, -0.11848, 6.68468, 0.00000, -0.16205, 0.97438),
            doubleArrayOf(0.53986, 0.71726, -0.28075, 7.64283, 0.00000, -0.28609, 0.89343),
        ),
        // Traffic jam
        listOf(
            doubleArrayOf(0.56935, 1.00535, -0.06334, 8.16220, 0.00000, 0.00000, 0.21976),
            doubleArrayOf(0.45964, 0.88010, 0.05029, 7.80557, -0.31722, 0.00000, 0.24970),
            doubleArrayOf(0.55352, 1.11199, -0.14853, 6.45105, 0.00000, 0.00000, 0.39736),
            doubleArrayOf(0.56048, 1.15323, -0.18158, 6.24367, 0.00000, 0.00000, 0.36605),
            doubleArrayOf(0.58891, 1.06633, -0.11409, 6.31937, 0.00000, -0.07708, 0.44141),
            doubleArrayOf(0.35224, 0.88832, 0.02677, 8.48256, 0.00000, 0.12693, 0.31387),
        ),
        // Bank job
        listOf(
            doubleArrayOf(0.37364, 1.02279, -0.82151, 8.50000, 0.00000, 0.00000, 0.69327),
            doubleArrayOf(0.37243, 0.27829, -0.16542, 8.50000, -0.16432, 0.00000, 0.64526),
            doubleArrayOf(0.44956, 0.14907, 0.39302, 8.50000, 0.00000, 0.00000, 0.73471),
            doubleArrayOf(0.38261, 0.29520, -0.27361, 8.46140, -0.15993, 0.00000, 0.62353),
        )
    ) + Classics.HINTS
}
