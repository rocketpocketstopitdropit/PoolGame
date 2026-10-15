package com.poolgame.app.core

/**
 * Trick shots on the gap table. Every level is a fixed layout: the cue ball starts in the break
 * area on the near section and has to jump the gap to sink the 1-ball (yellow) on the far section.
 * Other balls are obstacles; knocking them in doesn't matter.
 */
class TrickLevel(
    val name: String,
    val blurb: String,
    /** Object balls as (id, x, y). The 1-ball is always the target. */
    val balls: List<Triple<Int, Double, Double>>,
    val cueX: Double = 0.32,
    val cueY: Double = P.W / 2
) {
    val ids: List<Int> = balls.map { it.first }
}

/** Where a trick-shot match stands. */
class TrickState {
    /** How many levels have been played (the level being played now is levelNo + 1). */
    var levelNo = 0
    /** Index into Trick.LEVELS of the level being played. */
    var level = 0
    var round = 1
    /** Who shoots first on this level (it alternates level to level). */
    var starter = 0
    var suddenDeath = false
    /** Attempts each player has taken on this level. */
    val tries = intArrayOf(0, 0)
    /** The attempt each player made it on (0 = not yet). */
    val made = intArrayOf(0, 0)
    val levelWins = intArrayOf(0, 0)
    /** Finished levels: the winner (0/1) or -1 for a tie. */
    val results = ArrayList<Int>()
    /** The balls go back to the level's layout before the next attempt. */
    var resetPending = false

    fun reset() {
        levelNo = 0; level = 0; round = 1; starter = 0; suddenDeath = false
        tries.fill(0); made.fill(0); levelWins.fill(0); results.clear(); resetPending = false
    }

    fun copyFrom(o: TrickState) {
        levelNo = o.levelNo; level = o.level; round = o.round; starter = o.starter; suddenDeath = o.suddenDeath
        o.tries.copyInto(tries); o.made.copyInto(made); o.levelWins.copyInto(levelWins)
        results.clear(); results.addAll(o.results); resetPending = o.resetPending
    }
}

object Trick {
    const val TARGET = 1
    /** Rounds per level before it's called a tie (sudden death has no limit). */
    const val ROUNDS = 5

    private const val W = P.W
    private const val MID = P.W / 2

    val LEVELS: List<TrickLevel> = listOf(
        TrickLevel(
            "Leap of faith",
            "Jump the gap and sink the 1",
            listOf(Triple(1, 2.0, MID))
        ),
        TrickLevel(
            "Picket fence",
            "Get over the gap and the fence, then sink the 1",
            listOf(Triple(1, 2.22, MID)) + (0 until 7).map { Triple(2 + it, 1.335, MID - 0.27 + it * 0.09) }
        ),
        TrickLevel(
            "Bullseye",
            "Sink the 1 out of the middle of the ring",
            listOf(Triple(1, 1.95, MID)) + (1..5).map {
                val a = toRad(60.0 * it)
                Triple(1 + it, 1.95 + 0.12 * kotlin.math.cos(a), MID + 0.12 * kotlin.math.sin(a))
            }
        ),
        TrickLevel(
            "Traffic jam",
            "Find a lane through the traffic and sink the 1",
            listOf(Triple(1, 2.32, 1.07), Triple(2, 2.0, 0.78), Triple(3, 2.1, 1.16), Triple(4, 2.42, 0.86))
        ),
        TrickLevel(
            "Bank job",
            "The 1 is guarded. Find a way to sink it",
            listOf(Triple(1, 1.42, 0.22)) + (0 until 4).map { Triple(2 + it, 1.55, 0.08 + it * 0.09) }
        )
    )

    /**
     * Shots known to work for each level, found offline by searching with the physics engine:
     * (placeX, placeY, angle, speed, side, vert, elevation). The CPU starts its search from these.
     */
    val HINTS: List<List<DoubleArray>> = listOf(
        // Leap of faith
        listOf(
            doubleArrayOf(0.58631, 0.43742, 0.14513, 5.69305, 0.00000, 0.00000, 0.96655),
            doubleArrayOf(0.57182, 0.73711, -0.07282, 8.50000, 0.00000, 0.00000, 0.31805),
            doubleArrayOf(0.55905, 0.70491, -0.05019, 7.10677, 0.00000, 0.00000, 0.41521),
            doubleArrayOf(0.50545, 0.96197, -0.22552, 7.12045, -0.10363, 0.00000, 0.29686),
            doubleArrayOf(0.55613, 0.32681, 0.21763, 7.89249, 0.00000, 0.00000, 0.27441),
            doubleArrayOf(0.57521, 0.15726, 0.33628, 7.59632, 0.00000, 0.00000, 0.26561),
        ),
        // Picket fence
        listOf(
            doubleArrayOf(0.53771, 0.73138, -0.05858, 6.16512, 0.09012, 0.07256, 0.90961),
            doubleArrayOf(0.51649, 0.74456, -0.06676, 6.63561, 0.00000, 0.00000, 0.97876),
            doubleArrayOf(0.51481, 0.30463, 0.19646, 8.25804, 0.00000, -0.10324, 0.42300),
            doubleArrayOf(0.58204, 0.41758, 0.13631, 6.34392, 0.00000, 0.00000, 0.61537),
            doubleArrayOf(0.55571, 0.06973, 0.33743, 6.11900, 0.00000, -0.14202, 0.78598),
            doubleArrayOf(0.50115, 0.62164, 0.00657, 7.50723, 0.36858, 0.00000, 0.59302),
        ),
        // Bullseye
        listOf(
            doubleArrayOf(0.42731, 0.61134, 0.01756, 7.39652, 0.00000, 0.00000, 0.34149),
            doubleArrayOf(0.53187, 0.72028, -0.06954, 6.35270, -0.30235, 0.00000, 0.50372),
            doubleArrayOf(0.47415, 0.79190, -0.11993, 5.75373, 0.00000, 0.00000, 0.91063),
            doubleArrayOf(0.58822, 0.16418, 0.37703, 6.30241, 0.00000, 0.00000, 0.42225),
            doubleArrayOf(0.46350, 0.47411, 0.12122, 6.21981, -0.23795, 0.00000, 0.41147),
            doubleArrayOf(0.44481, 0.51305, 0.09105, 6.08276, 0.00000, 0.00000, 0.88786),
        ),
        // Traffic jam
        listOf(
            doubleArrayOf(0.59191, 0.46406, 0.32355, 5.90120, 0.00000, 0.04494, 0.78119),
            doubleArrayOf(0.55491, 0.19090, 0.45316, 6.96486, 0.23117, -0.06111, 1.03351),
            doubleArrayOf(0.57280, 0.28486, 0.41246, 5.80900, 0.24715, 0.00000, 0.96944),
            doubleArrayOf(0.58508, 0.17308, 0.46962, 7.89852, 0.00000, 0.00000, 0.22152),
            doubleArrayOf(0.33015, 0.27904, 0.36865, 6.55191, 0.00000, 0.00000, 0.87916),
            doubleArrayOf(0.46983, 0.37170, 0.35210, 7.13959, 0.00000, 0.00000, 1.02047),
        ),
        // Bank job
        listOf(
            doubleArrayOf(0.17741, 0.36746, 0.45285, 7.85404, -0.14203, 0.34462, 0.70376),
            doubleArrayOf(0.45433, 0.50923, -0.08235, 7.70457, 0.00000, 0.00000, 0.31772),
            doubleArrayOf(0.48068, 0.78252, -0.17360, 7.72326, 0.00000, 0.00000, 0.55645),
            doubleArrayOf(0.53845, 0.49895, -0.08104, 7.93505, 0.00000, 0.00000, 0.42720),
            doubleArrayOf(0.56469, 1.03018, -0.41305, 6.40839, 0.00000, 0.00000, 0.41675),
            doubleArrayOf(0.47342, 0.75952, -0.31790, 8.01466, 0.00000, 0.20215, 0.72902),
        )
    )
}
