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
    THREAD
}

/**
 * Trick shots on the gap table. Every level is a fixed layout: the cue ball starts in the break
 * area on the near section and has to jump the gap to sink the 1-ball (yellow) on the far section.
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
    val cueY: Double = P.W / 2,
    /** The far end's two corner pockets (some levels fill them in). */
    val farCorners: Boolean = true,
    /**
     * Where the cue ball may be placed: rectangles (x0, y0, x1, y1) for its centre. Empty = anywhere
     * in the break area. With zones, [pens] gives the balls that surround each zone (the same ids
     * for every zone): they move to whichever zone the cue ball is put in.
     */
    val zones: List<DoubleArray> = emptyList(),
    val pens: List<List<Triple<Int, Double, Double>>> = emptyList()
) {
    val ids: List<Int> = balls.map { it.first }
    private val others = ids.filter { it != Trick.TARGET }
    /** Bit mask of the balls other than the 1. */
    val otherMask: Int = others.fold(0) { m, id -> m or (1 shl id) }
    /** The most bonus a make here could realistically bring (used to decide when a player is out of it). */
    val maxBonus: Int = when (rule) {
        TrickRule.THREAD -> 0
        TrickRule.CUSHION -> 2
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
        levelNo = 0; level = 0; starter = 0; suddenDeath = false
        clearLevel(); levelWins.fill(0); results.clear(); resetPending = false
    }

    fun copyFrom(o: TrickState) {
        levelNo = o.levelNo; level = o.level; starter = o.starter; suddenDeath = o.suddenDeath
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

    private const val MID = P.W / 2

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
    )

    /**
     * Bank job: start from either pen of balls on the near section (drag the cue ball into the top
     * or bottom one; the pen moves with it) and jump out of it. On the far section a row of four
     * balls runs along each long cushion with a ball tucked behind it: the 1 at the top, the 10 at
     * the bottom. The far corner pockets are filled in; the only pocket is a hole in the middle of
     * the far section. Sinking the 10 as well is a bonus.
     */
    private fun bankJob(): TrickLevel {
        val w = P.W
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
            if (x > Table.GAP_X0 - 0.61 * r || !w.isFree(x, y, 0)) return false
        } else {
            val zi = zoneAt(lv, x, y)
            if (zi < 0) return false
            for ((id, px, py) in lv.pens[zi]) {
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
            0 in log.fell -> return miss("the cue ball fell into the gap")
            0 in log.jumpedOff -> return miss("the cue ball flew off the table")
            0 in log.pocketed -> return miss("scratch")
            TARGET in log.fell -> return miss("the 1 fell into the gap")
            TARGET in log.jumpedOff -> return miss("the 1 flew off the table")
            log.firstHit < 0 -> return miss("didn't hit a ball")
        }
        when (lv.rule) {
            TrickRule.CUSHION -> if (log.firstHit != TARGET || log.cueCushionsBeforeHit < 1)
                return miss("the cue ball has to hit a cushion before the 1")
            TrickRule.FENCE -> if (log.cueAirTouched and lv.otherMask != 0)
                return miss("the cue ball touched the fence before it landed")
            TrickRule.ONLY_THE_1 -> if (log.touched[0] and lv.otherMask != 0)
                return miss("the cue ball touched the ring")
            TrickRule.THREAD -> {
                if (log.firstHit != TARGET) return miss("the cue ball touched the traffic first")
                if (log.touched[TARGET] and lv.otherMask != 0) return miss("the 1 touched the traffic")
                if (log.firstHit == TARGET && !(log.cutDeg >= MIN_CUT_DEG)) return miss("hit straight on — the 1 has to be cut")
            }
            TrickRule.PLAIN -> {}
        }
        if (TARGET !in log.pocketed || cueLost) return miss("the 1 stayed up")
        return when (lv.rule) {
            TrickRule.CUSHION -> {
                val extra = log.cueCushionsBeforeHit - 1
                TrickJudgement(true, "", extra, if (extra > 0) "+$extra for ${if (extra == 1) "an extra cushion" else "$extra extra cushions"}" else "")
            }
            TrickRule.THREAD -> TrickJudgement(true, "", 0, "")
            else -> {
                val extra = log.pocketed.filter { it != 0 && it != TARGET }
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
            doubleArrayOf(0.59540, 1.20384, -0.58924, 6.85699, 0.00000, 0.00000, 0.40337),
            doubleArrayOf(0.58842, 0.44473, -0.13968, 5.64304, 0.00000, 0.00000, 0.95967),
            doubleArrayOf(0.47392, 1.13234, -0.51943, 6.83019, 0.00000, 0.09907, 0.35732),
            doubleArrayOf(0.42285, 0.75003, -0.30753, 6.33102, 0.00000, 0.00000, 0.61120),
            doubleArrayOf(0.43130, 1.00412, -0.44649, 6.20181, 0.00000, 0.00000, 0.72844),
            doubleArrayOf(0.59430, 1.19694, -0.58731, 7.39902, 0.00000, 0.00000, 0.41551),
        ),
        // Off the rail
        listOf(
            doubleArrayOf(0.56005, 0.38843, -0.38705, 5.87176, 0.18290, -0.18862, 0.97469),
            doubleArrayOf(0.51506, 0.50744, -0.45725, 8.07168, 0.00000, 0.24039, 0.31095),
            doubleArrayOf(0.09939, 0.08507, -0.15015, 8.18468, 0.00000, 0.00000, 0.31252),
            doubleArrayOf(0.13965, 0.53446, -0.37824, 6.97477, -0.29444, 0.00000, 0.77592),
            doubleArrayOf(0.60129, 0.44937, -0.44132, 6.36563, 0.00000, 0.00000, 0.49328),
            doubleArrayOf(0.58002, 0.53727, -0.48724, 6.00887, 0.00000, -0.22211, 0.53807),
        ),
        // Picket fence
        listOf(
            doubleArrayOf(0.59274, 0.84286, -0.30066, 6.42266, 0.00000, 0.17207, 1.01514),
            doubleArrayOf(0.52466, 0.65370, -0.18227, 6.35886, 0.13184, 0.40236, 0.78195),
            doubleArrayOf(0.55851, 0.62972, -0.17310, 6.32429, 0.39571, 0.00000, 0.97659),
            doubleArrayOf(0.37315, 1.11449, -0.39860, 7.45768, 0.00000, 0.28294, 1.03436),
            doubleArrayOf(0.58408, 0.95300, -0.36043, 6.13860, 0.37426, 0.00000, 0.90597),
            doubleArrayOf(0.54498, 1.19186, 0.57329, 7.62578, 0.00000, 0.00000, 0.52024),
        ),
        // Bullseye
        listOf(
            doubleArrayOf(0.59005, 0.69412, -0.27731, 7.46457, 0.00000, 0.00000, 1.01257),
            doubleArrayOf(0.59747, 0.81194, -0.35971, 6.95659, 0.00000, 0.00000, 0.71824),
            doubleArrayOf(0.56403, 0.52310, -0.14994, 6.15530, 0.31756, 0.00000, 1.03452),
            doubleArrayOf(0.55566, 0.64962, -0.23857, 6.90189, -0.36919, 0.00000, 0.86874),
            doubleArrayOf(0.51422, 0.68704, -0.25542, 7.29431, 0.00000, -0.16096, 0.96066),
            doubleArrayOf(0.29651, 0.48197, -0.09910, 7.38759, 0.00000, 0.20631, 0.76471),
        ),
        // Traffic jam
        listOf(
            doubleArrayOf(0.60122, 0.87080, 0.04669, 5.54219, 0.00000, -0.12357, 0.76600),
            doubleArrayOf(0.58939, 0.75960, 0.13792, 5.67135, 0.00000, 0.00000, 0.79861),
            doubleArrayOf(0.57917, 1.01431, -0.07221, 6.25788, 0.00000, 0.00000, 0.42186),
            doubleArrayOf(0.55696, 1.15817, -0.18442, 5.68135, 0.00000, 0.00000, 0.51649),
            doubleArrayOf(0.54833, 0.80866, 0.09458, 5.83898, 0.00000, 0.41349, 0.50776),
            doubleArrayOf(0.36261, 0.87207, 0.03982, 7.18471, 0.00000, 0.05749, 0.42955),
        ),
        // Bank job
        listOf(
            doubleArrayOf(0.36905, 0.28021, -0.23808, 8.48379, -0.14216, 0.00000, 0.63742),
            doubleArrayOf(0.45628, 0.14945, 0.43165, 8.34783, 0.00000, 0.00000, 0.81783),
            doubleArrayOf(0.36485, 1.02846, -0.81017, 8.40865, 0.00000, 0.00000, 0.65210),
            doubleArrayOf(0.43985, 0.27439, -0.32968, 7.20033, 0.00000, 0.09909, 0.90039),
            doubleArrayOf(0.44049, 0.14352, 0.39730, 6.97170, 0.31687, 0.00443, 0.72119),
            doubleArrayOf(0.43790, 0.18838, -0.05665, 6.10588, 0.10254, -0.09912, 0.98329),
        )
    )
}
