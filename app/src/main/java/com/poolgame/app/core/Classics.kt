package com.poolgame.app.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The classics: well-known trick shots on an ordinary 9 ft table (no gap). Most are about getting the 1 in
 * past other balls: going round them, over them, between them or off a cushion. Every level has a known
 * working shot (in [HINTS], found with the physics engine), and a unit test checks they still work.
 */
object Classics {
    private val L = TableSize.NINE.length
    private val W = TableSize.NINE.width
    private const val R = P.R
    private const val D = 2 * P.R + 0.0004      // frozen: touching, with a hair to spare

    /** A small rectangle round (x, y) where the cue ball may be put. */
    private fun zone(x: Double, y: Double, hw: Double = 0.07, hh: Double = 0.07) = doubleArrayOf(x - hw, y - hh, x + hw, y + hh)

    private fun ball(id: Int, x: Double, y: Double) = Triple(id, x, y)

    /** Dodgeball: a wall of three balls stands square across the line from the cue ball to the 1. */
    private fun dodgeball(): TrickLevel {
        val cx = 0.75; val cy = 0.80
        val ox = 2.05; val oy = 0.36
        val mx = (cx + ox) / 2; val my = (cy + oy) / 2
        val l = hypot(ox - cx, oy - cy)
        val px = -(oy - cy) / l; val py = (ox - cx) / l
        val wall = (-1..1).map { k -> ball(2 + k + 1, mx + px * k * 0.06, my + py * k * 0.06) }
        return TrickLevel(
            "Dodgeball", "Three balls stand between you and the 1 · the cue ball may touch only the 1",
            TrickRule.ONLY_THE_1,
            listOf(ball(1, ox, oy)) + wall,
            cueX = cx, cueY = cy, standard = true, zones = listOf(zone(cx, cy))
        )
    }

    /** Eye of the needle: the 1 has to pass between two balls with barely a ball's width between them. */
    private fun needle(): TrickLevel {
        val ox = 1.45; val oy = 0.85
        val ax = L - 0.03; val ay = W - 0.03          // toward the bottom-right corner
        val l = hypot(ax - ox, ay - oy)
        val ux = (ax - ox) / l; val uy = (ay - oy) / l
        val half = (2 * R + (2 * R + 0.014)) / 2      // centres: a ball's width plus 14 mm of room between their surfaces
        val t = 0.55 * l
        val gate = listOf(
            ball(2, ox + ux * t - uy * half, oy + uy * t + ux * half),
            ball(3, ox + ux * t + uy * half, oy + uy * t - ux * half))
        return TrickLevel(
            "Eye of the needle", "Send the 1 between the two balls without touching either",
            TrickRule.THREAD,
            listOf(ball(1, ox, oy)) + gate + listOf(ball(4, 1.95, 0.45)),
            cueX = 0.80, cueY = 0.50, standard = true, zones = listOf(zone(0.80, 0.50, 0.10, 0.10)), minCut = 0.0
        )
    }

    /** Snookered: the cue ball sits in a horseshoe of balls; kick off a cushion to get at the 1. */
    private fun snookered(): TrickLevel {
        val cx = 0.42; val cy = W / 2
        val arc = (0 until 5).map { k ->
            val a = toRad(-64.0 + 32.0 * k)
            ball(2 + k, cx + 0.115 * cos(a), cy + 0.115 * sin(a))
        }
        return TrickLevel(
            "Snookered", "Boxed in · the cue ball has to hit a cushion before the 1 · extra cushions take a shot off",
            TrickRule.CUSHION,
            listOf(ball(1, 1.98, 1.02)) + arc,
            cueX = cx, cueY = cy, standard = true, zones = listOf(zone(cx, cy, 0.02, 0.02))
        )
    }

    /** Over the wall: a solid row of seven balls across the table in front of the cue ball. */
    private fun overTheWall(): TrickLevel {
        val wall = (0 until 7).map { k -> ball(2 + k, 1.12, W / 2 + (k - 3) * D) }
        return TrickLevel(
            "Over the wall", "A wall of seven · the cue ball may touch only the 1: over it or round it",
            TrickRule.ONLY_THE_1,
            listOf(ball(1, 2.15, 0.33)) + wall,
            cueX = 0.86, cueY = W / 2, standard = true, zones = listOf(zone(0.86, W / 2, 0.06, 0.12))
        )
    }

    /** Frozen combo: the 2 and the 1 touching, lined up a little off the corner; throw it in. */
    private fun frozenCombo(): TrickLevel {
        val ox = 2.0; val oy = 0.55
        val pk = atan2(0.04 - oy, (L - 0.04) - ox)    // straight at the top-right corner
        val a = pk + toRad(7.0)                         // the pair points 7 degrees off it
        return TrickLevel(
            "Frozen combo", "The 2 and the 1 are frozen, pointing off the pocket · hit the 2 first and sink the 1",
            TrickRule.PLAIN,
            listOf(ball(1, ox, oy), ball(2, ox - D * cos(a), oy - D * sin(a))),
            cueX = 1.25, cueY = 0.95, standard = true, zones = listOf(zone(1.25, 0.95, 0.12, 0.10)), firstBall = 2
        )
    }

    /** Railroad: the 2 and the 1 frozen to the top cushion; sink both in the corner. */
    private fun railroad(): TrickLevel {
        val y = R + 0.0006
        return TrickLevel(
            "Railroad", "Two balls frozen to the cushion · sink both of them",
            TrickRule.PLAIN,
            listOf(ball(1, 2.05, y), ball(2, 2.05 - D, y)),
            cueX = 1.50, cueY = 0.40, standard = true, zones = listOf(zone(1.50, 0.40, 0.12, 0.10)), mustSink = listOf(1, 2)
        )
    }

    /** Line of four: four frozen balls in a row, the 1 at the front; hit the 4 at the back. */
    private fun lineOfFour(): TrickLevel {
        val ox = 0.52; val oy = 0.92
        val pk = atan2((W - 0.04) - oy, 0.04 - ox)        // straight at the bottom-left corner
        val a = pk + toRad(4.0)
        val bx = -cos(a); val by = -sin(a)                // back along the row
        val row = (1..3).map { k -> ball(1 + k, ox + bx * D * k, oy + by * D * k) }
        return TrickLevel(
            "Line of four", "Hit the 4 at the back of the row and sink the 1 at the front",
            TrickRule.PLAIN,
            listOf(ball(1, ox, oy)) + row,
            cueX = 1.45, cueY = 0.50, standard = true, zones = listOf(zone(1.45, 0.50, 0.12, 0.10)), firstBall = 4
        )
    }

    /** Bank it: the 1 has to come off a cushion before it drops. */
    private fun bankIt(): TrickLevel = TrickLevel(
        "Bank it", "Bank the 1 · it has to hit a cushion before it drops",
        TrickRule.BANK,
        listOf(ball(1, 1.27, 0.36), ball(2, 1.00, 0.62), ball(3, 1.54, 0.62)),
        cueX = 1.27, cueY = 0.95, standard = true, zones = listOf(zone(1.27, 0.95, 0.18, 0.08))
    )

    val LEVELS: List<TrickLevel> = listOf(
        // Roughly easiest first.
        lineOfFour(), frozenCombo(), railroad(), bankIt(), needle(), dodgeball(), overTheWall(), snookered()
    )

    /** Known working shots per level, as in [Trick.HINTS]: (placeX, placeY, angle, speed, side, vert, elevation). */
    val HINTS: List<List<DoubleArray>> = listOf(
        // Line of four
        listOf(
            doubleArrayOf(1.43705, 0.45305, 2.65928, 6.31243, 0.00000, 0.05907, 0.00000),
            doubleArrayOf(1.42171, 0.54661, 2.76007, 6.10643, 0.07124, 0.00000, 0.00000),
            doubleArrayOf(1.54212, 0.42557, 2.68699, 7.24859, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(1.42226, 0.40969, 2.59570, 7.86162, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(1.55859, 0.48088, 2.73442, 7.72399, 0.00000, -0.29120, 0.00000),
            doubleArrayOf(1.52220, 0.59555, 2.84713, 7.18419, -0.05577, 0.03154, 0.00000),
        ),
        // Frozen combo
        listOf(
            doubleArrayOf(1.31933, 0.99325, -0.61445, 6.36855, -0.13269, 0.36550, 0.00000),
            doubleArrayOf(1.30935, 0.98400, -0.57673, 4.90172, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(1.22310, 0.89189, -0.40623, 4.67648, 0.11542, 0.04382, 0.00000),
            doubleArrayOf(1.35907, 1.04512, -0.68816, 4.85623, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(1.26060, 0.99007, -0.54996, 4.65461, 0.06674, 0.00000, 0.00000),
            doubleArrayOf(1.22472, 0.94742, -0.47193, 5.27266, 0.45403, 0.00000, 0.00000),
        ),
        // Railroad
        listOf(
            doubleArrayOf(1.53911, 0.31083, -0.55909, 3.20017, 0.00000, 0.43680, 0.00000),
            doubleArrayOf(1.57170, 0.35166, -0.67088, 4.53440, 0.00000, 0.47320, 0.00000),
            doubleArrayOf(1.38060, 0.43147, 5.69466, 3.61759, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(1.48491, 0.48582, -0.74438, 3.13173, -0.13414, 0.14091, 0.00000),
            doubleArrayOf(1.44505, 0.40977, 5.65650, 5.03910, 0.10308, 0.00000, 0.00000),
            doubleArrayOf(1.57621, 0.32852, 5.63393, 5.17289, 0.43334, 0.15935, 0.00000),
        ),
        // Bank it
        listOf(
            doubleArrayOf(1.36484, 0.92330, -1.69464, 8.20477, 0.26982, 0.08668, 0.00000),
            doubleArrayOf(1.40560, 0.91907, -1.80450, 3.41613, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(1.30923, 0.91691, 4.68271, 7.90934, 0.00000, -0.19970, 0.00000),
            doubleArrayOf(1.15500, 0.97607, -1.43014, 6.83774, -0.24949, 0.20566, 0.00000),
            doubleArrayOf(1.37595, 0.95294, -1.75924, 4.98141, 0.10368, 0.00000, 0.00000),
            doubleArrayOf(1.19770, 0.90272, -1.50023, 7.83525, 0.00000, 0.00000, 0.00000),
        ),
        // Eye of the needle
        listOf(
            doubleArrayOf(0.71617, 0.47895, 0.42379, 7.34912, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(0.85557, 0.50278, 0.48104, 7.47051, 0.08837, -0.28847, 0.00000),
            doubleArrayOf(0.83855, 0.41206, 0.58334, 8.21049, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(0.77029, 0.59299, 0.30846, 6.68362, -0.08815, 0.47002, 0.00000),
            doubleArrayOf(0.72706, 0.46450, 0.44908, 6.82336, 0.00000, 0.40339, 0.00000),
            doubleArrayOf(0.83864, 0.44974, 0.84033, 7.23197, 0.32110, 0.00000, 0.00000),
        ),
        // Dodgeball
        listOf(
            doubleArrayOf(0.78387, 0.82891, -0.74548, 8.48626, -0.10812, -0.01755, 0.00000),
            doubleArrayOf(0.72889, 0.85024, 5.54662, 8.13582, 0.00000, 0.28128, 0.00000),
            doubleArrayOf(0.72424, 0.75830, 0.84511, 6.08594, -0.02461, 0.00000, 0.00000),
            doubleArrayOf(0.68015, 0.85090, -0.71095, 6.82551, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(0.71806, 0.80185, -0.74656, 8.14662, 0.00000, 0.00000, 0.33406),
            doubleArrayOf(0.74784, 0.75547, -0.46876, 6.40150, 0.00000, 0.00000, 0.60070),
        ),
        // Over the wall
        listOf(
            doubleArrayOf(0.84569, 0.74605, -0.29151, 4.82701, 0.00000, 0.25192, 0.95327),
            doubleArrayOf(0.83958, 0.58900, 0.88097, 5.37817, 0.31182, 0.00000, 0.18769),
            doubleArrayOf(0.80233, 0.52793, -0.40736, 6.54467, 0.00000, 0.00000, 0.62051),
            doubleArrayOf(0.83585, 0.54199, -0.60315, 5.72054, 0.12169, 0.29029, 0.00000),
            doubleArrayOf(0.81087, 0.59637, 5.63590, 2.45302, 0.00000, 0.00000, 0.00000),
            doubleArrayOf(0.82954, 0.52465, -0.62358, 2.49881, 0.00000, 0.00000, 0.00000),
        ),
        // Snookered
        listOf(
            doubleArrayOf(0.40166, 0.63692, 3.52975, 7.40532, 0.49030, 0.00000, 0.22094),
            doubleArrayOf(0.41650, 0.62557, 3.71526, 3.96361, 0.00845, 0.47613, 0.00000),
            doubleArrayOf(0.41174, 0.63063, 2.65426, 5.54598, 0.33101, -0.08302, 0.00000),
            doubleArrayOf(0.41024, 0.63165, 3.80130, 6.92837, 0.00000, -0.04271, 0.00000),
            doubleArrayOf(0.40625, 0.62730, 3.89958, 5.38709, 0.00000, 0.32211, 0.00000),
            doubleArrayOf(0.40451, 0.62471, 0.20791, 6.47306, 0.38304, -0.10266, 1.04405),
        )
    )
}
