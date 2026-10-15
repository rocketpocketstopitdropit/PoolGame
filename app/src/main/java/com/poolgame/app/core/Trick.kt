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
    val cueY: Double = P.W / 2
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
            "Bullseye", "The cue ball may touch only the 1 — not the ring",
            TrickRule.ONLY_THE_1,
            listOf(Triple(1, 1.95, MID)) + listOf(0.0, 90.0, 135.0, 180.0, 225.0, 270.0).mapIndexed { k, deg ->
                Triple(2 + k, 1.95 + 0.26 * kotlin.math.cos(toRad(deg)), MID + 0.26 * kotlin.math.sin(toRad(deg)))
            }
        ),
        TrickLevel(
            "Traffic jam", "Cut the 1 and thread it through the traffic without touching any",
            TrickRule.THREAD,
            trafficLayout()
        ),
        TrickLevel(
            "Bank job", "The 1 is guarded · knock it into the hole in the table",
            TrickRule.PLAIN,
            listOf(Triple(1, 1.835, 0.36)) + (0 until 4).map { Triple(2 + it, 1.70 + it * 0.09, 0.25) },
            holes = listOf(doubleArrayOf(1.835, 0.80))
        )
    )

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
            doubleArrayOf(0.58631, 0.43742, -0.13635, 5.60433, 0.00000, 0.00000, 0.98342),
            doubleArrayOf(0.52856, 0.71727, -0.30807, 6.52189, -0.14718, 0.30429, 0.57419),
            doubleArrayOf(0.46671, 0.58529, -0.22043, 6.88049, -0.09565, 0.42779, 0.47336),
            doubleArrayOf(0.56139, 1.11036, -0.53272, 7.12228, 0.00000, 0.00000, 0.47699),
            doubleArrayOf(0.59957, 0.95596, -0.46517, 6.93766, 0.00000, 0.00000, 1.01528),
            doubleArrayOf(0.46636, 0.47616, -0.15247, 6.09461, 0.00000, 0.20468, 0.89665),
        ),
        // Off the rail
        listOf(
            doubleArrayOf(0.56132, 0.39192, -0.39017, 5.69869, 0.18290, -0.18862, 0.96925),
            doubleArrayOf(0.09889, 0.08536, -0.14925, 8.16382, 0.00000, 0.00000, 0.33145),
            doubleArrayOf(0.57783, 0.54326, -0.48898, 6.09448, 0.00000, -0.22211, 0.54590),
            doubleArrayOf(0.51796, 0.56703, -0.48341, 5.81278, 0.00000, 0.00000, 0.65925),
            doubleArrayOf(0.60430, 0.45078, -0.44114, 6.40729, 0.00000, 0.00000, 0.49954),
            doubleArrayOf(0.13475, 0.55805, -0.38631, 7.29467, 0.00000, 0.00000, 0.93081),
        ),
        // Picket fence
        listOf(
            doubleArrayOf(0.59274, 0.84286, -0.30017, 6.43405, 0.00000, 0.17207, 0.99144),
            doubleArrayOf(0.58539, 0.94969, -0.35688, 6.14672, 0.37426, 0.00000, 0.89534),
            doubleArrayOf(0.37315, 1.11449, -0.39790, 7.44172, 0.00000, 0.28294, 1.00103),
            doubleArrayOf(0.43331, 1.13981, -0.42357, 6.86621, 0.00000, 0.00000, 0.87238),
            doubleArrayOf(0.53103, 1.20970, 0.56154, 7.62561, 0.00000, 0.00000, 0.57482),
            doubleArrayOf(0.51489, 0.65161, -0.17912, 6.48584, 0.13184, 0.40236, 0.77960),
        ),
        // Bullseye
        listOf(
            doubleArrayOf(0.56149, 0.85983, -0.13455, 5.41269, 0.21195, -0.21969, 0.88506),
            doubleArrayOf(0.49835, 1.16886, 0.54114, 6.41279, -0.38930, 0.00000, 0.39123),
            doubleArrayOf(0.53957, 1.18567, -0.38247, 6.71780, -0.12819, 0.00000, 0.78174),
            doubleArrayOf(0.42851, 0.09159, 0.32672, 6.15472, 0.15441, 0.00000, 0.63935),
            doubleArrayOf(0.49939, 0.08802, 0.34545, 6.30899, 0.00000, 0.19395, 0.83098),
            doubleArrayOf(0.59581, 0.90279, -0.19945, 7.28073, 0.00000, 0.43308, 0.97982),
        ),
        // Traffic jam
        listOf(
            doubleArrayOf(0.60140, 0.91124, 0.01238, 5.28886, 0.00000, 0.22297, 0.91142),
            doubleArrayOf(0.49875, 0.80589, 0.09141, 6.25632, 0.00000, -0.15932, 0.45636),
            doubleArrayOf(0.58720, 0.75868, 0.13743, 5.60229, 0.00000, 0.00000, 0.80712),
            doubleArrayOf(0.59997, 0.86974, 0.04916, 5.93227, 0.00000, -0.12357, 0.74977),
            doubleArrayOf(0.57141, 1.00248, -0.06284, 6.07656, 0.00000, 0.00000, 0.46993),
            doubleArrayOf(0.55929, 0.85434, 0.05814, 5.29741, 0.00000, 0.00000, 0.77072),
        ),
        // Bank job
        listOf(
            doubleArrayOf(0.45282, 0.39209, -0.09968, 7.52122, 0.00000, 0.00000, 0.29122),
            doubleArrayOf(0.55065, 0.39966, -0.11336, 6.68467, 0.00000, 0.00000, 0.45144),
            doubleArrayOf(0.53386, 0.96357, -0.55008, 6.39492, 0.00000, 0.00000, 0.48618),
            doubleArrayOf(0.44365, 0.39192, -0.09496, 6.96499, 0.00000, -0.03117, 0.35595),
            doubleArrayOf(0.50074, 1.09239, -0.61958, 8.50000, 0.33476, 0.00000, 0.28859),
            doubleArrayOf(0.24412, 0.84352, -0.38248, 7.24975, 0.00000, 0.00000, 0.59328),
        )
    )
}
