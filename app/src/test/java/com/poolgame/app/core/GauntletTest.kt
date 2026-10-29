package com.poolgame.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/** The Gauntlet: the square four-player bumper table with ramps, a dish and loops. */
class GauntletTest {
    private val i = Gauntlet.IN
    private val m = Gauntlet.MID

    @After fun back() { Table.useStandard(TableSize.NINE); BumperColors.each = 5 }

    private fun gauntlet(players: Int = 4, each: Int = 7, teams: Boolean = false, partner: Int = 1): Match {
        val names = arrayOf("You", "CPU 1", "CPU 2", "CPU 3").take(players).toTypedArray()
        val g = Match(GameMode.GAUNTLET, names)
        g.each = each; g.teams = teams; g.partner = partner
        g.rack(Random(1), 0)
        return g
    }

    /** One ball alone on the table at (x, y), shot along [angle] at [speed]: what happened. */
    private class Run(val w: World, val maxZ: Double, val looped: Boolean)
    private fun alone(x: Double, y: Double, angle: Double, speed: Double): Run {
        Table.useGauntlet()
        val w = World()
        for (b in w.balls) { b.onTable = false; b.stop() }
        val b = w.balls[1]; b.x = x; b.y = y; b.onTable = true
        w.cue = 1
        w.strike(angle, speed, 0.0, 0.0, 0.0)
        var t = 0.0; var maxZ = 0.0; var looped = false
        while (w.anyMoving() && t < 20) { t += w.step(0.004); maxZ = max(maxZ, b.z); if (w.inLoop(1)) looped = true }
        return Run(w, maxZ, looped)
    }

    @Test fun tableHasFourCupsAndSixtyEightBumpers() {
        Table.useGauntlet()
        assertEquals(4, Table.pockets.size); assertEquals(68, Table.posts.size)
        assertEquals(80 * i, P.L, 1e-9); assertEquals(80 * i, P.W, 1e-9)
        assertEquals(4, Table.segs.size)
        // The deflectors are among the bumpers.
        for (d in Gauntlet.deflectors()) assertTrue(Table.posts.any { hypot(it.x - d[0], it.y - d[1]) < 1e-6 })
    }

    @Test fun startingSpotsAreClearForSixSevenAndEightBalls() {
        for (n in 6..8) {
            val g = gauntlet(4, n)
            val ids = g.ballIds()
            assertEquals(4 * n, ids.size)
            for (id in ids) {
                val b = g.world.balls[id]
                assertTrue(b.onTable)
                assertTrue("ball $id ($n each) at ${b.x / i},${b.y / i}", g.world.isFree(b.x, b.y, id))
            }
            assertEquals(3, g.ownerOf(4 * n)); assertEquals(3 * n + 1, g.markedBall(3))
            assertEquals(3, BumperColors.ownerOf(4 * n)); assertTrue(BumperColors.isMarked(3 * n + 1))
        }
    }

    @Test fun rampLaunchesTheBallOverTheGap() {
        val r = alone(m - 26 * i, m, 0.0, 3.0)
        assertTrue("max height ${r.maxZ / i}in", r.maxZ > 4.5 * i)
        // Too soft: it never leaves the ground much and comes back toward you.
        val soft = alone(m - 26 * i, m, 0.0, 1.0)
        assertTrue(soft.maxZ < 2 * i)
    }

    @Test fun aBallCanLandInTheDishAndRestThere() {
        // Dropped in the dish from just above it, it settles in the middle.
        Table.useGauntlet()
        val w = World()
        for (b in w.balls) { b.onTable = false; b.stop() }
        val b = w.balls[1]; b.x = m + 2 * i; b.y = m - 1 * i; b.z = Gauntlet.dishHeight(b.x, b.y) + 0.01; b.onTable = true
        b.moving = true; b.vz = -0.01
        w.simulate()
        assertTrue(b.onTable)
        assertTrue(hypot(b.x - m, b.y - m) < 0.6 * i)
        assertEquals(Gauntlet.DISH_LOW, b.z, 0.003)
    }

    @Test fun hardEnoughShotGoesRoundTheLoopAndOutTheFarSide() {
        val lat = -0.9 / Math.sqrt(2.0) * i
        val x0 = m - 20 * i + lat; val y0 = m + 20 * i + lat
        val r = alone(x0, y0, -PI / 4, 4.0)
        assertTrue(r.looped); assertTrue(r.maxZ > 17 * i)
        val b = r.w.balls[1]
        assertTrue(!b.onTable || b.x > m)                  // came out heading for the top-right corner
        // Too soft: up the loop, off the top, and back out toward you.
        val weak = alone(x0, y0, -PI / 4, 2.4)
        assertTrue(weak.looped)
        val wb = weak.w.balls[1]
        assertTrue(wb.onTable && wb.x < m && wb.y > m)
    }

    @Test fun offTheLineTheLoopsTrackTurnsTheBallAway() {
        // Rolling across the track sideways, it bounces off.
        val r = alone(m + 4 * i, m - 12 * i, PI / 2, 1.5)
        assertFalse(r.looped)
    }

    @Test fun aFastBallSkimsOverACup() {
        Table.useGauntlet()
        val cup = Table.pockets[1]
        fun roll(speed: Double): Boolean {
            val w = World()
            for (b in w.balls) { b.onTable = false; b.stop() }
            val b = w.balls[1]; b.x = cup.cx - 0.3; b.y = cup.cy; b.onTable = true; w.cue = 1
            w.strike(0.0, speed, 0.0, 0.0, 0.0); w.simulate()
            return 1 in w.log.pocketed
        }
        assertTrue(roll(0.9))
        // Right over the cup at speed, nothing drops on that pass.
        val w = World()
        for (b in w.balls) { b.onTable = false; b.stop() }
        val b = w.balls[1]; b.x = cup.cx - 0.3; b.y = cup.cy; b.onTable = true; w.cue = 1
        w.strike(0.0, 3.5, 0.0, 0.0, 0.0)
        var t = 0.0
        while (t < 0.12) t += w.step(0.004)
        assertTrue(b.onTable)
    }

    @Test fun ballsCantBePutOnTheRampsOrTheLoops() {
        Table.useGauntlet()
        val w = World()
        for (b in w.balls) { b.onTable = false }
        assertFalse(w.isFree(m - 15 * i, m))                  // on a ramp
        assertFalse(w.isFree(m + 4 * i, m + 4 * i))          // on a loop's track
        assertTrue(w.isFree(m - 15 * i, m + 12 * i))
    }

    @Test fun partnersCanBeBesideEachOtherAndTurnsAlternateTeams() {
        for ((partner, order) in listOf(1 to listOf(0, 2, 1, 3), 2 to listOf(0, 1, 2, 3), 3 to listOf(0, 2, 3, 1))) {
            val g = gauntlet(4, 6, teams = true, partner = partner)
            assertEquals(order, g.turnOrder().toList())
            assertEquals(partner, g.partnerOf(0)); assertEquals(0, g.partnerOf(partner))
            val others = (1..3).filter { it != partner }
            assertEquals(others[1], g.partnerOf(others[0]))
            // Turns swap teams every time.
            val o = g.turnOrder()
            for (k in 0 until 4) assertFalse(g.sameTeam(o[k], o[(k + 1) % 4]))
        }
    }

    @Test fun teamWinsWhenAllItsBallsAreDown() {
        val g = gauntlet(4, 6, teams = true, partner = 3)
        g.isBreak = false
        for (id in g.bumperBalls(0) + g.bumperBalls(3)) g.world.balls[id].onTable = false
        // Put one of yellow's back and let yellow sink it into their cup.
        val last = g.markedBall(3)
        val b = g.world.balls[last]; b.onTable = true
        val cup = Table.pockets[g.targetCup(3)]
        b.x = cup.cx; b.y = cup.cy - 0.25
        g.current = 3
        g.world.cue = last
        g.shoot(PI / 2 * (if (cup.cy > b.y) 1 else -1), 0.8, 0.0, 0.0)
        g.world.simulate()
        val out = g.resolve()
        assertEquals(3, out.winner); assertTrue(g.won(0)); assertFalse(g.won(1))
        assertTrue(g.message.contains("You & CPU 3"))
    }

    @Test fun gauntletGameSurvivesSaving() {
        val g = gauntlet(4, 8, teams = true, partner = 2)
        val text = GameSave.write(g, GameSettings(GameMode.GAUNTLET, true, 6, 0, false, 0, octPlayers = 4, octTeams = true,
            gauntEach = 8, partner = 2))
        val s = GameSave.readSettings(text)!!
        assertEquals(8, s.gauntEach); assertEquals(2, s.partner)
        val r = Match(GameMode.GAUNTLET, arrayOf("You", "CPU 1", "CPU 2", "CPU 3"))
        assertTrue(GameSave.restore(text, r))
        assertEquals(8, r.each); assertEquals(2, r.partner); assertTrue(r.teams)
        for (id in g.ballIds()) assertEquals(g.world.balls[id].x, r.world.balls[id].x, 1e-12)
    }

    @Test fun cpuPlaysTheGauntlet() {
        val g = gauntlet(2, 6)
        val ai = arrayOf(Ai(8, 3L), Ai(4, 5L))
        var shots = 0
        while (g.winner < 0 && shots < 40) {
            g.prepareTurn()
            val plan = ai[g.current].plan(g, 250)
            assertTrue(plan.ball in g.shootable())
            if (g.openingPending()) {
                g.world.cue = plan.ball
                if (g.setOpening(plan.angle, plan.speed, plan.side, plan.vert, plan.elevation)) g.fireOpening() else continue
            } else {
                g.selectBall(plan.ball)
                g.shoot(plan.angle, plan.speed, plan.side, plan.vert, plan.elevation)
            }
            g.world.simulate(); g.resolve(); shots++
        }
        val down = 12 - g.ballsLeft(0) - g.ballsLeft(1)
        assertTrue("only $down down in $shots shots", g.winner >= 0 || down >= 1)
    }
}
