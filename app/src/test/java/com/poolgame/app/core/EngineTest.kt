package com.poolgame.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

class EngineTest {
    private fun World.put(id: Int, x: Double, y: Double) {
        val b = balls[id]; b.x = x; b.y = y; b.onTable = true; b.stop()
    }
    private fun World.clearAll() { for (b in balls) { b.onTable = false; b.stop() } }

    /** Trick-shot tests switch to the gap table; put the regulation table back for the others. */
    @After fun regulationTable() = Table.useStandard()

    @Test fun centreHitReachesNaturalRoll() {
        val w = World(); w.clearAll(); w.put(0, 0.3, 0.6)
        w.strike(0.0, 2.0, 0.0, 0.0)
        w.advance(0.5)
        val c = w.balls[0]
        assertEquals(0.0, c.vx + P.R * c.wy, 1e-6)          // no slip: rolling
        assertEquals(2.0 * 5 / 7, c.vx, 0.08)
    }

    @Test fun drawComesBackFollowGoesThrough() {
        fun cueEnd(vert: Double): Double {
            val w = World(); w.clearAll(); w.put(0, 1.0, 0.635); w.put(1, 1.3, 0.635)
            w.strike(0.0, 2.5, 0.0, vert); w.simulate(); return w.balls[0].x
        }
        assertTrue("draw", cueEnd(-0.45) < 1.2)
        assertTrue("follow", cueEnd(0.45) > 1.45)
    }

    @Test fun rightEnglishReboundsRight() {
        val w = World(); w.clearAll(); w.put(0, 1.0, 0.6)
        w.strike(-PI / 2, 2.0, 0.4, 0.0)                      // straight up the screen into the top rail
        while (w.balls[0].vy < 0) w.step(1.0)
        assertTrue(w.balls[0].vx > 0.1)                         // shooter's right
    }

    @Test fun straightShotIntoCornerDrops() {
        val w = World(); w.clearAll()
        val pk = Table.pockets[0]
        w.put(3, 0.6, 0.3); w.put(0, 1.0, 0.5)
        val d = hypot(pk.aimX - 0.6, pk.aimY - 0.3)
        val gx = 0.6 - (pk.aimX - 0.6) / d * 2 * P.R; val gy = 0.3 - (pk.aimY - 0.3) / d * 2 * P.R
        w.strike(atan2(gy - 0.5, gx - 1.0), 2.0, 0.0, 0.0); w.simulate()
        assertTrue(3 in w.log.pocketed)
    }

    @Test fun jumpShotClearsBlocker() {
        val w = World(); w.clearAll()
        w.put(0, 1.0, 0.635); w.put(5, 1.2, 0.635); w.put(1, 1.55, 0.635)
        w.strike(0.0, 5.0, 0.0, 0.0, Math.toRadians(50.0)); w.simulate()
        assertEquals(1, w.log.firstHit)
        // the same shot with a level cue hits the blocker
        val v = World(); v.clearAll()
        v.put(0, 1.0, 0.635); v.put(5, 1.2, 0.635); v.put(1, 1.55, 0.635)
        v.strike(0.0, 5.0, 0.0, 0.0); v.simulate()
        assertEquals(5, v.log.firstHit)
    }

    @Test fun cueBallJumpedOffTableIsFoul() {
        val m = Match(GameMode.NINE_BALL, arrayOf("You", "CPU")); m.rack(Random(1), 0)
        m.isBreak = false; m.ballInHand = false
        m.world.balls[0].x = 1.0; m.world.balls[0].y = 0.25
        m.shoot(-PI / 2, 6.0, 0.0, 0.0, Math.toRadians(50.0)); m.world.simulate()
        assertTrue(0 in m.world.log.jumpedOff)
        val out = m.resolve()
        assertTrue(out.foul); assertTrue(m.ballInHand); assertTrue(m.world.balls[0].onTable)
        assertEquals(1, m.current)
    }

    @Test fun saveAndRestoreGame() {
        val m = Match(GameMode.EIGHT_BALL, arrayOf("You", "CPU")); m.rack(Random(3), 0)
        m.shoot(0.0, 7.0, 0.0, 0.0); m.world.simulate(); m.resolve()
        val text = GameSave.write(m, GameSettings(GameMode.EIGHT_BALL, true, 7, 30, false, 0))
        val s = GameSave.readSettings(text)!!
        assertEquals(7, s.level); assertEquals(30, s.shotClock); assertTrue(s.vsCpu)
        val r = Match(GameMode.EIGHT_BALL, arrayOf("You", "CPU"))
        assertTrue(GameSave.restore(text, r))
        assertEquals(m.current, r.current); assertEquals(m.ballInHand, r.ballInHand); assertEquals(m.isBreak, r.isBreak)
        for (i in 0 until 16) {
            assertEquals(m.world.balls[i].onTable, r.world.balls[i].onTable)
            assertEquals(m.world.balls[i].x, r.world.balls[i].x, 1e-12)
        }
    }

    /** Shoots ball [id] straight into corner pocket 0 with [called] as the called pocket. */
    private fun potIntoCorner(m: Match, id: Int, called: Int): ShotOutcome {
        for (b in m.world.balls) { b.onTable = false; b.stop() }
        m.world.put(id, 0.6, 0.3); m.world.put(0, 1.0, 0.5)
        if (m.mode == GameMode.EIGHT_BALL) m.world.put(if (id == 8) 15 else 8, 2.2, 1.0)   // keep the 8 / a stripe on the table
        if (m.mode == GameMode.NINE_BALL && id != 9) m.world.put(9, 2.2, 1.0)
        val pk = Table.pockets[0]
        val d = hypot(pk.aimX - 0.6, pk.aimY - 0.3)
        val gx = 0.6 - (pk.aimX - 0.6) / d * 2 * P.R; val gy = 0.3 - (pk.aimY - 0.3) / d * 2 * P.R
        m.isBreak = false; m.ballInHand = false
        m.calledPocket = called
        m.shoot(atan2(gy - 0.5, gx - 1.0), 2.0, 0.0, 0.0); m.world.simulate()
        return m.resolve()
    }

    @Test fun calledPocketEveryShotSlop() {
        val m = Match(GameMode.EIGHT_BALL, arrayOf("You", "CPU")); m.callMode = CallMode.EVERY_SHOT
        m.groups[0] = Group.SOLIDS; m.groups[1] = Group.STRIPES
        val wrong = potIntoCorner(m, 3, 5)
        assertTrue(3 in m.world.log.pocketed)
        assertTrue(!wrong.keepTurn && !wrong.foul); assertEquals(1, m.current)
        val m2 = Match(GameMode.EIGHT_BALL, arrayOf("You", "CPU")); m2.callMode = CallMode.EVERY_SHOT
        m2.groups[0] = Group.SOLIDS; m2.groups[1] = Group.STRIPES
        assertTrue(potIntoCorner(m2, 3, 0).keepTurn)
    }

    @Test fun eightInWrongPocketLoses() {
        val m = Match(GameMode.EIGHT_BALL, arrayOf("You", "CPU")); m.callMode = CallMode.MONEY_BALL
        m.groups[0] = Group.SOLIDS; m.groups[1] = Group.STRIPES
        assertEquals(1, potIntoCorner(m, 8, 2).winner)
        val m2 = Match(GameMode.EIGHT_BALL, arrayOf("You", "CPU")); m2.callMode = CallMode.MONEY_BALL
        m2.groups[0] = Group.SOLIDS; m2.groups[1] = Group.STRIPES
        assertEquals(0, potIntoCorner(m2, 8, 0).winner)
    }

    @Test fun uncalledNineIsRespotted() {
        val m = Match(GameMode.NINE_BALL, arrayOf("You", "CPU")); m.callMode = CallMode.MONEY_BALL
        val out = potIntoCorner(m, 9, 3)
        assertEquals(-1, out.winner); assertTrue(m.world.balls[9].onTable)
    }

    @Test fun youWinGrammar() {
        val m = Match(GameMode.NINE_BALL, arrayOf("You", "CPU"))
        assertEquals("You win!", m.winsText(0))
        assertEquals("CPU wins!", m.winsText(1))
    }

    @Test fun predictionMatchesShot() {
        val w = World(); w.clearAll(); w.put(0, 1.0, 0.635); w.put(1, 1.5, 0.635 + P.R)
        val pr = Prediction()
        Predictor.run(w, World(), 0.0, 2.0, 0.0, 0.0, 0.0, pr)
        assertEquals(1, pr.hitId)
        assertTrue(pr.objN >= 2)
        w.strike(0.0, 2.0, 0.0, 0.0); w.simulate()
        assertEquals(w.log.hitX, pr.hitX, 1e-9)
    }

    @Test fun scratchIsFoulWithBallInHand() {
        val m = Match(GameMode.NINE_BALL); m.rack(Random(1), 0)
        for (b in m.world.balls) if (b.id != 0 && b.id != 1) { b.onTable = false }
        m.world.put(1, 1.0, 0.6); m.isBreak = false; m.ballInHand = false
        m.world.put(0, 0.4, 0.4)
        val pk = Table.pockets[0]                                // send the cue ball straight into a pocket
        m.shoot(atan2(pk.aimY - 0.4, pk.aimX - 0.4), 2.0, 0.0, 0.0); m.world.simulate()
        val out = m.resolve()
        assertTrue(out.foul); assertEquals(1, m.current); assertTrue(m.ballInHand)
    }

    @Test fun cpuFinishesGames() {
        for (mode in listOf(GameMode.EIGHT_BALL, GameMode.NINE_BALL)) for (seed in 0 until 3) {
            val m = Match(mode); m.rack(Random(seed), 0)
            val ai = arrayOf(Ai(8, seed.toLong()), Ai(3, seed + 99L))
            var shots = 0
            while (m.winner < 0 && shots < 300) {
                val p = ai[m.current].plan(m)
                if (m.ballInHand && p.hasPlacement) assertTrue(m.placeCue(p.placeX, p.placeY))
                m.shoot(p.angle, p.speed, p.side, p.vert); m.world.simulate(); m.resolve(); shots++
            }
            assertTrue("$mode seed $seed unfinished", m.winner >= 0)
        }
    }

    // ------------------------------------------------------------------ heckle

    @Test fun missingEverythingIsHeckled() {
        val m = Match(GameMode.NINE_BALL); m.rack(Random(2), 0)
        m.isBreak = false; m.ballInHand = false
        m.world.put(0, 0.3, 0.635)
        m.shoot(PI, 1.5, 0.0, 0.0); m.world.simulate()            // straight back into the head rail
        val out = m.resolve()
        assertTrue(out.foul); assertTrue(out.heckle)
    }

    @Test fun scratchIsNotHeckled() {
        val m = Match(GameMode.NINE_BALL); m.rack(Random(1), 0)
        for (b in m.world.balls) if (b.id != 0 && b.id != 1) { b.onTable = false }
        m.world.put(1, 1.0, 0.6); m.isBreak = false; m.ballInHand = false
        m.world.put(0, 0.4, 0.4)
        val pk = Table.pockets[0]
        m.shoot(atan2(pk.aimY - 0.4, pk.aimX - 0.4), 2.0, 0.0, 0.0); m.world.simulate()
        val out = m.resolve()
        assertTrue(out.foul); assertFalse(out.heckle)
    }

    @Test fun coughSoundsAreClean() {
        val c = Cough.coughs(44100)
        assertTrue(c.size > 10000)
        assertTrue(c.all { !it.isNaN() && kotlin.math.abs(it) <= 1.0 })
    }

    @Test fun recordedVoiceIsTrimmedAndLevelled() {
        val rate = 22050
        // Half a second of quiet, a short "word", then more quiet.
        val raw = DoubleArray(rate * 2) { if (it in rate / 2 until rate) 0.2 * kotlin.math.sin(it * 0.07) else 0.001 }
        val v = Voice.clean(raw, rate)
        assertTrue(v.size in rate / 2..rate)                    // the silence is gone
        assertTrue(v.maxOf { kotlin.math.abs(it) } > 0.8)       // and it's brought up to full level
        assertEquals(0, Voice.clean(DoubleArray(rate) { 0.0005 }, rate).size)   // nothing heard
    }

    // ------------------------------------------------------------------ trick shots (gap table)

    @Test fun gapTableSwitchesBack() {
        Match(GameMode.TRICK)
        assertTrue(Table.gap); assertEquals(4, Table.pockets.size)      // only the corner pockets
        Match(GameMode.EIGHT_BALL)
        assertFalse(Table.gap); assertEquals(6, Table.pockets.size)
    }

    @Test fun ballRollingOffTheEdgeFallsIntoTheGap() {
        Table.useGap()
        val w = World(); w.clearAll(); w.put(0, 0.3, 0.6)
        w.strike(0.0, 2.0, 0.0, 0.0); w.simulate()
        assertFalse(w.balls[0].onTable)
        assertTrue(0 in w.log.fell)
        assertFalse(w.isFree(Table.GAP_X0 + 0.1, 0.6))
    }

    @Test fun jumpShotCrossesTheGap() {
        Table.useGap()
        val w = World(); w.clearAll(); w.put(0, 0.3, 0.4)
        w.strike(0.0, 6.5, 0.0, 0.0, Math.toRadians(40.0)); w.simulate()
        assertTrue(w.log.cueLanded)                 // came down on the far section (it may roll back off later)
        // The same stroke with a level cue drops into the gap.
        val v = World(); v.clearAll(); v.put(0, 0.3, 0.4)
        v.strike(0.0, 6.5, 0.0, 0.0); v.simulate()
        assertTrue(0 in v.log.fell)
    }

    @Test fun ballDropsIntoAHoleInTheCloth() {
        Table.useGap(listOf(doubleArrayOf(2.0, 0.6)))
        val w = World(); w.clearAll(); w.put(3, 1.6, 0.6)
        w.balls[3].vx = 1.0; w.balls[3].moving = true
        w.simulate()
        assertTrue(3 in w.log.pocketed)
        assertEquals(Table.pockets.size - 1, w.log.pocketIn[3])
    }

    private fun lv(i: Int) = Trick.LEVELS[i]
    private fun log(first: Int, potted: List<Int>, init: ShotLog.() -> Unit = {}) = ShotLog().apply {
        firstHit = first; pocketed.addAll(potted); init()
    }

    @Test fun cushionLevelNeedsARailFirstAndRewardsMore() {
        val rail = lv(1); assertEquals(TrickRule.CUSHION, rail.rule)
        assertFalse(Trick.judge(rail, log(1, listOf(1))).made)
        val one = Trick.judge(rail, log(1, listOf(1)) { cueCushionsBeforeHit = 1 })
        assertTrue(one.made); assertEquals(0, one.bonus)
        assertEquals(2, Trick.judge(rail, log(1, listOf(1)) { cueCushionsBeforeHit = 3 }).bonus)
    }

    @Test fun fenceCantBeTouchedBeforeLanding() {
        val fence = lv(2); assertEquals(TrickRule.FENCE, fence.rule)
        assertFalse(Trick.judge(fence, log(1, listOf(1)) { cueAirTouched = 1 shl 4 }).made)
        val ok = Trick.judge(fence, log(1, listOf(1, 4)) { cueLanded = true; touched[0] = 1 shl 4 })
        assertTrue(ok.made); assertEquals(1, ok.bonus)        // the fence ball went in too
    }

    @Test fun bullseyeCueMayTouchOnlyTheOne() {
        val bull = lv(3); assertEquals(TrickRule.ONLY_THE_1, bull.rule)
        assertFalse(Trick.judge(bull, log(1, listOf(1)) { touched[0] = (1 shl 1) or (1 shl 3) }).made)
        assertTrue(Trick.judge(bull, log(1, listOf(1)) { touched[0] = 1 shl 1 }).made)
    }

    @Test fun trafficJamMustBeCutAndThreaded() {
        val jam = lv(4); assertEquals(TrickRule.THREAD, jam.rule)
        assertFalse(Trick.judge(jam, log(1, listOf(1)) { cutDeg = 4.0 }).made)
        assertFalse(Trick.judge(jam, log(1, listOf(1)) { cutDeg = 25.0; touched[1] = 1 shl 5 }).made)
        val ok = Trick.judge(jam, log(1, listOf(1, 5)) { cutDeg = 25.0 })
        assertTrue(ok.made); assertEquals(0, ok.bonus)         // no bonus balls on this level
    }

    @Test fun everyLevelHasAWorkingShot() {
        val m = Match(GameMode.TRICK); m.rack(Random(1), 0)
        for (li in Trick.LEVELS.indices) {
            m.trick.level = li; m.setupTrickLevel()
            val ok = Trick.HINTS[li].count { h ->
                val w = World(); w.copyFrom(m.world)
                w.balls[0].x = h[0]; w.balls[0].y = h[1]
                w.strike(h[2], h[3], h[4], h[5], h[6]); w.simulate()
                Trick.judge(Trick.LEVELS[li], w.log).made
            }
            assertTrue("level $li ${Trick.LEVELS[li].name}: no hint works", ok > 0)
        }
    }

    /** Takes one trick attempt: a known good shot when [make], otherwise a soft roll into the gap. */
    private fun attempt(m: Match, make: Boolean): ShotOutcome {
        m.prepareTurn()
        if (make) {
            val lvl = Trick.LEVELS[m.trick.level]
            val h = Trick.HINTS[m.trick.level].first { h ->
                val w = World(); w.copyFrom(m.world); w.balls[0].x = h[0]; w.balls[0].y = h[1]
                w.strike(h[2], h[3], h[4], h[5], h[6]); w.simulate()
                Trick.judge(lvl, w.log).made
            }
            assertTrue(m.placeCue(h[0], h[1]))
            m.shoot(h[2], h[3], h[4], h[5], h[6])
        } else m.shoot(0.0, 1.5, 0.0, 0.0)
        m.world.simulate()
        return m.resolve()
    }

    @Test fun fewestAttemptsWinsTheLevel() {
        val m = Match(GameMode.TRICK, arrayOf("You", "CPU")); m.rack(Random(1), 0)
        assertEquals(0, m.current); assertTrue(m.ballInHand)
        assertFalse(attempt(m, false).made); assertEquals(1, m.current)
        assertTrue(0 in m.world.log.fell)
        attempt(m, false); assertEquals(0, m.current)
        attempt(m, false)
        assertTrue(attempt(m, true).made)                 // the CPU makes it on its 2nd attempt
        // You've had 2 and can't do better than 3 on this level (no bonus balls), so it's over.
        assertEquals(listOf(1), m.trick.results)
        assertEquals(1, m.trick.levelWins[1]); assertEquals(1, m.trick.level); assertEquals(1, m.current)
        m.prepareTurn()
        assertTrue(m.ballInHand)
    }

    @Test fun bonusBallTakesAnAttemptOffAndTiesGoToTheRunner() {
        val m = Match(GameMode.TRICK, arrayOf("You", "CPU")); m.rack(Random(1), 0)
        m.trick.level = 5; m.setupTrickLevel()                // bank job: four guard balls, bonus possible
        fun fake(potted: List<Int>): ShotOutcome {
            m.prepareTurn()
            m.world.log.reset()
            if (potted.isNotEmpty()) { m.world.log.firstHit = 1; m.world.log.pocketed.addAll(potted) }
            return m.resolve()
        }
        fake(emptyList()); fake(emptyList())                  // round 1: both miss
        fake(emptyList()); assertTrue(fake(listOf(1)).made)    // you miss again, the CPU makes it on its 2nd
        assertEquals(0, m.current)                             // you're still alive: a bonus could tie it
        val out = fake(listOf(1, 3))                           // you make it on your 3rd with the 3 as well
        assertTrue(out.made)
        assertEquals(listOf(0), m.trick.results)              // 3 - 1 = 2, same as the CPU: tie goes to the runner
    }

    @Test fun trickMatchIsWonOnLevels() {
        val m = Match(GameMode.TRICK, arrayOf("You", "CPU")); m.rack(Random(1), 0)
        var guard = 0
        while (m.winner < 0 && guard++ < 80) attempt(m, m.current == 0)
        assertEquals(0, m.winner)
        assertEquals(Trick.LEVELS.size, m.trick.levelWins[0])
    }

    @Test fun tiedTrickMatchGoesToSuddenDeath() {
        val m = Match(GameMode.TRICK, arrayOf("You", "CPU")); m.rack(Random(1), 0)
        repeat(Trick.LEVELS.size) { attempt(m, true); attempt(m, true) }   // the same shot both ways: every level tied
        assertTrue(m.trick.suddenDeath); assertEquals(-1, m.winner)
        attempt(m, m.current == 1); attempt(m, m.current == 1)
        assertEquals(1, m.winner)
    }

    @Test fun trickProgressSurvivesSaving() {
        val m = Match(GameMode.TRICK, arrayOf("You", "CPU")); m.rack(Random(1), 1)
        attempt(m, false); attempt(m, true)
        m.prepareTurn()
        val text = GameSave.write(m, GameSettings(GameMode.TRICK, true, 5, 0, false, 1))
        val r = Match(GameMode.TRICK, arrayOf("You", "CPU"))
        assertTrue(GameSave.restore(text, r))
        assertEquals(m.trick.level, r.trick.level); assertEquals(m.trick.levelWins.toList(), r.trick.levelWins.toList())
        assertEquals(m.trick.results, r.trick.results); assertEquals(m.current, r.current)
        assertEquals(m.trick.tries.toList(), r.trick.tries.toList()); assertEquals(m.trick.made.toList(), r.trick.made.toList())
    }

    @Test fun cpuPlaysTrickShots() {
        val m = Match(GameMode.TRICK); m.rack(Random(4), 0)
        var made = 0
        repeat(6) {
            m.prepareTurn()
            val p = Ai(10, it.toLong()).plan(m, 400)
            if (p.hasPlacement) assertTrue(m.placeCue(p.placeX, p.placeY))
            m.shoot(p.angle, p.speed, p.side, p.vert, p.elevation); m.world.simulate()
            if (m.resolve().made) made++
        }
        assertTrue("level-10 CPU made only $made of 6", made >= 3)
    }
}
