package com.poolgame.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.random.Random
import kotlin.math.hypot
import kotlin.math.sqrt

/** The octagon bumper table: its ramps, cups and bumpers. */
class OctagonTest {
    @After fun regulationTable() = Table.useStandard(TableSize.NINE)

    private fun World.clearAll() { for (b in balls) { b.onTable = false; b.stop() } }
    private fun World.put(id: Int, x: Double, y: Double) {
        val b = balls[id]; b.x = x; b.y = y; b.onTable = true; b.stop()
    }

    @Test fun profileIsFlatThenRampsUpAt45Degrees() {
        Table.useOctagon(0)
        assertEquals(0.0, Table.surfZ(-0.2), 0.0)
        assertEquals(0.0, Table.surfZ(Table.FLAT_D - 1e-4), 0.0)
        // On the straight slope the ball's bottom sits R(sqrt 2 - 1) above the surface under its centre.
        for (d in doubleArrayOf(0.05, 0.08, 0.11)) {
            assertEquals(d + P.R * (sqrt(2.0) - 1), Table.surfZ(d), 0.0008)
            assertEquals(1.0, Table.surfSlope(d), 0.02)
        }
        // It rises smoothly through the rounded foot: no step bigger than the grid would give.
        var last = 0.0
        var d = Table.FLAT_D
        while (d < 0.12) {
            val z = Table.surfZ(d)
            assertTrue(z >= last - 1e-9)
            assertTrue(z - last < 0.0006)
            last = z; d += 0.0005
        }
        // Past the ledge there's nothing.
        assertEquals(Double.NEGATIVE_INFINITY, Table.surfZ(Table.OCT_OUTER + P.R + 0.002), 0.0)
    }

    @Test fun tableIsTheRightSizeWithFourCups() {
        Table.useOctagon(0)
        assertEquals(2 * (0.889 + 0.254), P.L, 1e-9)
        assertEquals(2 * (0.8001 + 0.254), P.W, 1e-9)
        assertEquals(4, Table.pockets.size)
        assertTrue(Table.segs.isEmpty())
        // Every cup 5.1" (0.13 m) in from the foot of its ramp, like the small table's from its cushion.
        for (pk in Table.pockets) assertEquals(-Table.CUP_IN, Table.octDist(pk.cx, pk.cy), 1e-9)
        // Every cup has a bumper either side of it, 0.095 m away, like the small table.
        for (pk in Table.pockets) assertEquals(2, Table.posts.count { abs(hypot(it.x - pk.cx, it.y - pk.cy) - 0.095) < 1e-9 })
        // All bumpers are the small table's bumper.
        for (pt in Table.posts) assertEquals(Table.POST_R, pt.r, 0.0)
    }

    private fun abs(v: Double) = kotlin.math.abs(v)

    @Test fun layoutsHaveTheirBumperCounts() {
        val counts = intArrayOf(32, 28, 28)
        for (lay in 0..2) {
            Table.useOctagon(lay)
            assertEquals(lay, Table.octLayout)
            assertEquals(counts[lay], Table.posts.size)
            // Bumpers sit on the flat cloth, clear of the cups and of each other (except the small table's cross).
            for (pt in Table.posts) assertTrue(Table.octDist(pt.x, pt.y) < Table.FLAT_D - pt.r - P.R)
            for (pk in Table.pockets) for (pt in Table.posts) assertTrue(hypot(pt.x - pk.cx, pt.y - pk.cy) > pk.capR + pt.r)
        }
    }

    /** A ball rolled at [speed] from (L/2 + 0.6, W/2) straight toward the top of the table. */
    private fun rollToRamp(speed: Double): World {
        Table.useOctagon(0)
        val w = World(); w.clearAll()
        w.put(1, P.L / 2 + 0.6, P.W / 2); w.cue = 1
        w.strike(-PI / 2, speed, 0.0, 0.2)
        return w
    }

    @Test fun aSlowBallRollsUpTheRampAndComesBack() {
        val w = rollToRamp(1.5)
        val b = w.balls[1]
        var top = 0.0
        var t = 0.0
        while (t < 20 && w.anyMoving()) { t += w.step(0.01); if (b.z > top) top = b.z }
        assertTrue(b.onTable)
        assertTrue("climbed ${top}", top > 0.03)
        assertTrue("climbed ${top}", top < Table.RAMP_H)
        assertEquals(0.0, b.z, 0.0)
        assertTrue(Table.octDist(b.x, b.y) <= Table.FLAT_D + 1e-9)
        assertTrue(w.log.cueCushionsBeforeHit >= 1)
        assertEquals(0, w.log.air)
    }

    @Test fun aHardBallFliesOffTheTable() {
        val w = rollToRamp(5.0)
        w.simulate()
        assertFalse(w.balls[1].onTable)
        assertEquals(listOf(1), w.log.jumpedOff.toList())
    }

    @Test fun climbSpeedMatchesRollingEnergy() {
        // Rolling up the ramp, the ball reaches about the height its rolling energy allows (v^2 = 10/7 g h),
        // less what rolling resistance takes on the way.
        Table.useOctagon(0)
        val w = World(); w.clearAll()
        val d0 = Table.FLAT_D - 0.002
        // Start just short of the foot of the right-hand end's ramp, rolling straight at it (off the cup's line).
        w.put(1, P.L / 2 + Table.OCT_HX + d0, P.W / 2 + 0.2); w.cue = 1
        val v = 1.3
        w.strike(0.0, v, 0.0, 0.4)   // struck 0.4 R above centre: natural roll at once
        var top = 0.0
        var t = 0.0
        while (t < 10 && w.anyMoving()) { t += w.step(0.01); if (w.balls[1].z > top) top = w.balls[1].z }
        val ideal = v * v * 7 / (10 * P.G)
        assertTrue("reached $top, ideal $ideal", top < ideal && top > ideal * 0.95)
        assertTrue(w.balls[1].onTable)
    }

    @Test fun aBallLeftOnTheRampRollsDownAndStops() {
        Table.useOctagon(0)
        val w = World(); w.clearAll()
        val b = w.balls[2]
        val x = P.L / 2 + Table.OCT_HX + 0.08
        b.x = x; b.y = P.W / 2 + 0.25; b.onTable = true
        b.z = Table.surfZ(Table.octDist(b.x, b.y)); b.moving = true
        w.simulate()
        assertTrue(b.onTable)
        assertEquals(0.0, b.z, 0.0)
        assertTrue(b.x < x - 0.08)
    }

    @Test fun ballsCanOnlyBePlacedOnTheFlat() {
        Table.useOctagon(0)
        val w = World(); w.clearAll()
        assertTrue(w.isFree(P.L / 2 + 0.6, P.W / 2 + 0.05))
        assertFalse(w.isFree(P.L / 2 + Table.OCT_HX + 0.05, P.W / 2 + 0.25))
        // Not in a cup either.
        val pk = Table.pockets[0]
        assertFalse(w.isFree(pk.cx, pk.cy))
    }

    // ------------------------------------------------------------------ rules

    private fun octMatch(players: Int, teams: Boolean = false, layout: Int = 0): Match {
        Table.useOctagon(layout)
        val m = Match(GameMode.OCTAGON, Array(players) { "P${it + 1}" })
        m.teams = teams
        m.rack(Random(1), 0)
        return m
    }

    @Test fun everyRackFitsOnEveryLayout() {
        for (lay in 0..2) for (n in 2..4) {
            val m = octMatch(n, layout = lay)
            val ids = m.ballIds()
            assertEquals(5 * n, ids.size)
            for (id in ids) {
                val b = m.world.balls[id]
                assertTrue(b.onTable)
                assertTrue("layout $lay ball $id", m.world.isFree(b.x, b.y, id))
            }
            for (id in 5 * n + 1 until NB) assertFalse(m.world.balls[id].onTable)
            assertTrue(m.isBreak)
            assertTrue(m.openingPending())
        }
    }

    @Test fun everyoneShootsAtTheCupAcrossFromThem() {
        val m = octMatch(4)
        for (p in 0..3) {
            val home = Table.pockets[m.homeCup(p)]; val target = Table.pockets[m.targetCup(p)]
            // Across the middle of the table from each other.
            assertEquals(P.L / 2, (home.cx + target.cx) / 2, 1e-9)
            assertEquals(P.W / 2, (home.cy + target.cy) / 2, 1e-9)
            assertTrue(hypot(home.cx - target.cx, home.cy - target.cy) > 1.0)
            // Each player's balls start at their own end.
            val b = m.world.balls[m.markedBall(p)]
            assertTrue(hypot(b.x - home.cx, b.y - home.cy) < hypot(b.x - target.cx, b.y - target.cy))
        }
    }

    @Test fun turnsGoRoundTheTable() {
        assertEquals(listOf(0, 1), octMatch(2).turnOrder().toList())
        assertEquals(listOf(0, 2, 1), octMatch(3).turnOrder().toList())
        val m = octMatch(4, teams = true)
        assertEquals(listOf(0, 2, 1, 3), m.turnOrder().toList())
        // Teams alternate: red & white against blue & yellow.
        val order = m.turnOrder()
        for (i in 0..3) assertFalse(m.sameTeam(order[i], order[(i + 1) % 4]))
        assertEquals(1, m.partnerOf(0)); assertEquals(3, m.partnerOf(2))
    }

    @Test fun theOpeningIsSetByEveryoneThenPlayedAtOnce() {
        val m = octMatch(4)
        val seen = ArrayList<Int>()
        for (k in 0 until 3) {
            seen += m.current
            assertEquals(m.markedBall(m.current), m.world.cue)
            assertFalse(m.setOpening(0.0, 0.3, 0.0, 0.0, 0.0))
        }
        seen += m.current
        assertEquals(listOf(0, 2, 1, 3), seen)
        // Everyone rolls their marked ball gently toward the middle: nothing goes in.
        val m2 = octMatch(4)
        for (k in 0 until 4) {
            val b = m2.world.balls[m2.markedBall(m2.current)]
            val done = m2.setOpening(atan2(P.W / 2 - b.y, P.L / 2 - b.x), 0.6, 0.0, 0.0, 0.0)
            assertEquals(k == 3, done)
        }
        m2.fireOpening()
        for (p in 0..3) assertTrue(m2.world.balls[m2.markedBall(p)].moving)
        m2.world.simulate()
        val out = m2.resolve()
        assertFalse(out.foul)
        assertFalse(m2.isBreak)
        assertEquals(-1, out.winner)
        assertTrue(m2.message, m2.message.contains("first"))
    }

    /** Puts [id] 0.12 m from cup [cup] (toward the middle) and makes it player [p]'s turn with that ball. */
    private fun lineUp(m: Match, p: Int, id: Int, cup: Int): Double {
        m.isBreak = false
        m.current = p
        val pk = Table.pockets[cup]
        val ux = P.L / 2 - pk.cx; val uy = P.W / 2 - pk.cy; val l = hypot(ux, uy)
        // Off to the side of the bumpers next to the cup: come in at an angle.
        val b = m.world.balls[id]
        val ax = -uy / l; val ay = ux / l
        b.x = pk.cx + ux / l * 0.12 + ax * 0.0; b.y = pk.cy + uy / l * 0.12 + ay * 0.0
        b.onTable = true; b.stop()
        m.world.cue = id
        return atan2(pk.cy - b.y, pk.cx - b.x)
    }

    @Test fun yourBallInYourCupScoresAndKeepsYourTurn() {
        val m = octMatch(4)
        val a = lineUp(m, 0, 1, m.targetCup(0))
        m.shoot(a, 0.45, 0.0, 0.0)
        m.world.simulate()
        val out = m.resolve()
        assertFalse(out.foul)
        assertTrue(out.keepTurn)
        assertEquals(0, m.current)
        assertEquals(4, m.ballsLeft(0))
        for (p in 1..3) assertEquals(5, m.ballsLeft(p))
    }

    @Test fun theWrongCupIsAFoulAndEveryoneElseDropsOne() {
        val m = octMatch(4)
        val a = lineUp(m, 0, 1, m.homeCup(0))
        m.shoot(a, 0.45, 0.0, 0.0)
        m.world.simulate()
        val out = m.resolve()
        assertTrue(out.foul)
        assertTrue(out.foulReason, out.foulReason.contains("wrong cup"))
        // The ball comes back out, and every other player drops one (their marked ball first).
        assertEquals(5, m.ballsLeft(0))
        assertTrue(m.world.balls[1].onTable)
        for (p in 1..3) { assertEquals(4, m.ballsLeft(p)); assertFalse(m.world.balls[m.markedBall(p)].onTable) }
        assertEquals(2, m.current)   // blue is next round the table
    }

    @Test fun inTeamsTheFoulGoesToTheOtherTeam() {
        val m = octMatch(4, teams = true)
        val a = lineUp(m, 0, 1, m.homeCup(0))
        m.shoot(a, 0.45, 0.0, 0.0)
        m.world.simulate()
        val out = m.resolve()
        assertTrue(out.foul)
        assertEquals(5, m.ballsLeft(1))      // partner: nothing
        assertEquals(4, m.ballsLeft(2)); assertEquals(4, m.ballsLeft(3))
    }

    @Test fun knockingSomeoneElsesBallInCountsForThem() {
        val m = octMatch(4)
        // Blue's marked ball sits in front of red's own cup; red knocks it in.
        val home = Table.pockets[m.homeCup(0)]
        val blue = m.world.balls[m.markedBall(2)]
        blue.x = home.cx + 0.07; blue.y = home.cy; blue.stop()
        val red = m.world.balls[1]
        red.x = home.cx + 0.2; red.y = home.cy; red.stop()
        m.isBreak = false; m.current = 0; m.world.cue = 1
        m.shoot(PI, 0.7, 0.0, 0.0)
        m.world.simulate()
        val out = m.resolve()
        assertFalse(m.world.balls[m.markedBall(2)].onTable)
        assertEquals(4, m.ballsLeft(2))
        if (m.world.balls[1].onTable) { assertFalse(out.foul); assertFalse(out.keepTurn); assertEquals(2, m.current) }
    }

    @Test fun firstToSinkAllFiveWins() {
        val m = octMatch(3)
        // Red has one ball left, by the cup.
        for (id in 2..5) m.world.balls[id].onTable = false
        val a = lineUp(m, 0, 1, m.targetCup(0))
        m.shoot(a, 0.45, 0.0, 0.0)
        m.world.simulate()
        val out = m.resolve()
        assertEquals(0, out.winner)
        assertTrue(m.won(0))
        assertFalse(m.won(1))
    }

    @Test fun aTeamPlayerWhoseBallsAreDownShootsTheirPartners() {
        val m = octMatch(4, teams = true)
        for (id in 1..5) m.world.balls[id].onTable = false   // red's all down
        m.isBreak = false; m.current = 0
        assertEquals(1, m.playsFor(0))
        assertEquals(listOf(m.markedBall(1)), m.shootable(0))
        m.prepareTurn()
        assertEquals(m.markedBall(1), m.world.cue)
        assertEquals(-1, m.winner)
        // White's five in white's cup (by red) win it for the team.
        for (id in 7..10) m.world.balls[id].onTable = false
        val a = lineUp(m, 0, 6, m.targetCup(1))
        m.shoot(a, 0.45, 0.0, 0.0)
        m.world.simulate()
        val out = m.resolve()
        assertTrue(out.winner == 0 || out.winner == 1)
        assertTrue(m.won(0) && m.won(1) && !m.won(2) && !m.won(3))
        assertTrue(m.message, m.message.startsWith("P1 & P2 win"))
    }

    @Test fun aJumpShotIsAFoul() {
        val m = octMatch(2)
        val b = m.world.balls[1]
        b.x = P.L / 2 - 0.45; b.y = P.W / 2 + 0.3; b.stop()
        m.isBreak = false; m.current = 0; m.world.cue = 1
        m.shoot(0.0, 3.0, 0.0, -0.2, toRad(40.0))
        m.world.simulate()
        val out = m.resolve()
        assertTrue(out.foul)
        assertEquals("no jump shots", out.foulReason)
        assertEquals(1, m.current)
    }

    @Test fun aBallOffTheTableIsAFoulAndGoesBackInTheMiddle() {
        val m = octMatch(2)
        val b = m.world.balls[2]
        b.x = P.L / 2 + 0.5; b.y = P.W / 2 + 0.45; b.stop()
        m.isBreak = false; m.current = 0; m.world.cue = 2
        m.shoot(PI / 2, 6.0, 0.0, 0.0)
        m.world.simulate()
        assertTrue(2 in m.world.log.jumpedOff)
        val out = m.resolve()
        assertTrue(out.foul)
        assertTrue(m.world.balls[2].onTable)
        assertTrue(hypot(m.world.balls[2].x - P.L / 2, m.world.balls[2].y - P.W / 2) < 0.12)
        assertEquals(4, m.ballsLeft(1))
    }

    @Test fun saveAndRestoreKeepsTheSetup() {
        val m = octMatch(4, teams = true, layout = 2)
        m.world.balls[17].onTable = false
        val text = GameSave.write(m, GameSettings(GameMode.OCTAGON, true, 5, 0, false, 0, octPlayers = 4, octTeams = true, octLayout = 2))
        val st = GameSave.readSettings(text)!!
        assertEquals(4, st.octPlayers); assertTrue(st.octTeams); assertEquals(2, st.octLayout)
        val m2 = Match(GameMode.OCTAGON, Array(4) { "P${it + 1}" })
        assertTrue(GameSave.restore(text, m2))
        assertTrue(m2.teams)
        assertFalse(m2.world.balls[17].onTable)
        assertTrue(m2.world.balls[16].onTable)
        // A save from the small bumper table (16 balls) still loads.
        Table.useBumper()
        val b = Match(GameMode.BUMPER, arrayOf("You", "CPU")); b.rack(Random(2), 0)
        val old = GameSave.write(b, GameSettings(GameMode.BUMPER, true, 5, 0, false, 0)).lines().filter { !it.startsWith("b16") && !it.startsWith("b17") &&
            !it.startsWith("b18") && !it.startsWith("b19") && !it.startsWith("b20") }.joinToString("\n")
        assertTrue(GameSave.restore(old, Match(GameMode.BUMPER, arrayOf("You", "CPU"))))
    }

    // ------------------------------------------------------------------ the computer

    @Test fun theCpuPlaysItsOwnBallsAndAGameMovesOn() {
        val m = octMatch(4)
        val ai = Array(4) { Ai(6, 10L + it) }
        var shots = 0
        var down = 0
        while (m.winner < 0 && shots < 24) {
            m.prepareTurn()
            val plan = ai[m.current].plan(m, 300)
            assertTrue(plan.ball in m.shootable())
            if (m.openingPending()) {
                m.world.cue = plan.ball
                if (m.setOpening(plan.angle, plan.speed, plan.side, plan.vert, plan.elevation)) m.fireOpening() else continue
            } else {
                m.selectBall(plan.ball)
                m.shoot(plan.angle, plan.speed, plan.side, plan.vert, plan.elevation)
            }
            m.world.simulate()
            m.resolve()
            shots++
        }
        for (p in 0..3) down += 5 - m.ballsLeft(p)
        assertTrue("only $down down in $shots shots", down >= 3)
    }
}
