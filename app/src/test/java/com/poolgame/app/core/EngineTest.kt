package com.poolgame.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.random.Random

class EngineTest {
    private fun World.put(id: Int, x: Double, y: Double) {
        val b = balls[id]; b.x = x; b.y = y; b.onTable = true; b.stop()
    }
    private fun World.clearAll() { for (b in balls) { b.onTable = false; b.stop() } }

    /** Trick-shot and table-size tests switch tables; put the 9 ft regulation table back for the others. */
    @After fun regulationTable() = Table.useStandard(TableSize.NINE)

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

    @Test fun tipInsideTheLimitPushesAlongTheCue() {
        // A level cue: no lift for any tip spot inside the friction limit, and the ball goes where the cue points.
        for (v in doubleArrayOf(-0.5, -0.3, 0.0, 0.3, 0.5)) {
            val w = World(); w.clearAll(); w.put(0, 1.0, 0.6)
            w.strike(0.3, 3.0, 0.0, v)
            val c = w.balls[0]
            assertEquals(0.0, c.vz, 1e-9); assertFalse(w.log.scooped); assertFalse(w.log.miscue)
            assertEquals(0.3, atan2(c.vy, c.vx), 1e-9)
            assertEquals(3.0, hypot(c.vx, c.vy), 1e-9)
        }
    }

    @Test fun diggingUnderTheBallScoopsItAndFouls() {
        val m = Match(GameMode.NINE_BALL, arrayOf("You", "CPU")); m.rack(Random(1), 0)
        m.isBreak = false; m.ballInHand = false
        val c = m.world.balls[0]; c.x = 0.5; c.y = 0.3
        // Level cue, tip right at the bottom of the ball: it slips under and lifts the ball.
        m.shoot(0.0, 4.0, 0.0, -0.8, 0.0)
        assertTrue(m.world.log.scooped); assertTrue(m.world.log.miscue)
        assertTrue(c.vz > 0.5)
        m.world.simulate()
        val out = m.resolve()
        assertTrue(out.foul); assertTrue(out.foulReason.startsWith("Scoop"))
        assertTrue(m.ballInHand); assertEquals(1, m.current)
    }

    @Test fun elevatedCueJumpIsLegal() {
        val w = World(); w.clearAll(); w.put(0, 1.0, 0.635)
        w.strike(0.0, 5.0, 0.0, 0.0, Math.toRadians(45.0))
        assertTrue(w.balls[0].vz > 0.5); assertFalse(w.log.scooped)
        // ...even struck low on the ball (relative to the cue): the push is still down into the slate.
        val v = World(); v.clearAll(); v.put(0, 1.0, 0.635)
        v.strike(0.0, 5.0, 0.0, -0.45, Math.toRadians(45.0))
        assertTrue(v.balls[0].vz > 0.3); assertFalse(v.log.scooped)
    }

    @Test fun spinOnAJumpTakesHoldWhenItLands() {
        fun end(vert: Double): Double {
            val w = World(); w.clearAll(); w.put(0, 0.3, 0.635)
            w.strike(0.0, 3.0, 0.0, vert, Math.toRadians(35.0))
            assertTrue(w.balls[0].vz > 0.3)
            w.advance(0.8)
            return w.balls[0].x
        }
        val draw = end(-0.45); val stun = end(0.0); val follow = end(0.45)
        assertTrue("draw $draw < stun $stun", draw < stun - 0.1)
        assertTrue("follow $follow > stun $stun", follow > stun + 0.1)
    }

    @Test fun englishOnAnElevatedCueCurves() {
        // Side spin with the cue raised tilts the spin axis: the ball swerves toward the english side (masse).
        val w = World(); w.clearAll(); w.put(0, 0.4, 0.635)
        w.strike(0.0, 2.5, 0.45, 0.0, Math.toRadians(40.0)); w.simulate()
        assertTrue("right english curves right (down the screen): y=${w.balls[0].y}", w.balls[0].y > 0.635 + 0.03)
    }

    @Test fun povViewLooksDownTheCue() {
        val m = Match(GameMode.EIGHT_BALL, arrayOf("a", "b")); m.rack(Random(1), 0)
        val c = m.world.balls[0]
        for (el in doubleArrayOf(0.0, 30.0, 60.0)) {
            val ctl = PovControl()
            val cam = PovCam(); cam.setup(c.x, c.y, 0.2, ctl.pitch, ctl.dist, 0.0, 0.0, 800.0, 400.0)
            val q = DoubleArray(3)
            // The cue ball is in view, centred left to right and below the middle.
            assertTrue("cue ball projects", cam.project(c.x, c.y, P.R, q))
            assertEquals(400.0, q[0], 1.0)
            assertTrue("cue ball low in the view: ${q[1]}", q[1] > 200 && q[1] < 400)
            // Tapping the screen where the cue ball is finds it on the table again.
            val back = DoubleArray(2)
            assertTrue(cam.unproject(q[0], q[1], P.R, back))
            assertEquals(c.x, back[0], 1e-6); assertEquals(c.y, back[1], 1e-6)
            // A point behind the eye doesn't project.
            assertFalse("behind the eye", cam.project(c.x - 2 * cos(0.2), c.y - 2 * sin(0.2), 0.0, q))
            val sc = PovScene(); sc.build(cam, m.world, c.x, c.y)
            assertTrue("items ${sc.n}", sc.n > 5)
            assertTrue("the cue ball is drawn", (0 until sc.n).any { sc.kind[it] == PovScene.BALL && sc.color[it] == 0 })
            // Layer 2 is sorted far to near, and the cue ball (nearest ball) comes after the rack.
            val ballsInOrder = (0 until sc.n).map { sc.order[it] }.filter { sc.kind[it] == PovScene.BALL }.map { sc.color[it] }
            assertEquals(0, ballsInOrder.last())
            if (el == 0.0) assertTrue("the rack is in view", ballsInOrder.size > 10)
            val out = DoubleArray(9 * 4)
            val k = sc.cue(cam, c.x, c.y, 0.2, Math.toRadians(el), 0.0, 0.0, 0.02,
                doubleArrayOf(0.0, 0.008, 0.03, 0.72, 0.74, 0.98, 1.22, 1.43, 1.45), 0.0065, 0.015, out)
            assertTrue("some of the cue is in front of the eye", k >= 2)
        }
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

    @Test fun ballComingDownJustShortCatchesTheLip() {
        Table.useGap()
        val w = World(); w.clearAll()
        val b = w.balls[0]
        // Centre a few millimetres short of the far edge, low and moving forward: it hits the lip and
        // carries onto the far table instead of dropping straight through.
        b.x = Table.GAP_X1 - 0.008; b.y = 0.6; b.z = 0.004; b.vx = 1.2; b.vz = -0.3; b.onTable = true; b.moving = true
        w.simulate()
        assertTrue("fell through", 0 !in w.log.fell)
        assertTrue(w.balls[0].x > Table.GAP_X1)
        // Well short, it drops into the gap (and can't pass through the cut face below the lip).
        val v = World(); v.clearAll()
        val c = v.balls[0]
        c.x = Table.GAP_X1 - 0.05; c.y = 0.6; c.z = 0.0; c.vx = 0.6; c.vz = -0.2; c.onTable = true; c.moving = true
        v.simulate()
        assertTrue(0 in v.log.fell)
    }

    @Test fun clippingTheLipDoesntSwerveTheBall() {
        Table.useGap()
        val w = World(); w.clearAll()
        val b = w.balls[0]
        b.x = Table.GAP_X1 - 0.008; b.y = 0.6; b.z = 0.004; b.vx = 1.2; b.vy = 0.6; b.vz = -0.3
        b.onTable = true; b.moving = true
        val before = kotlin.math.atan2(b.vy, b.vx)
        repeat(40) { w.step(0.002) }                         // just past the lip
        val after = kotlin.math.atan2(b.vy, b.vx)
        assertTrue("heading changed ${Math.toDegrees(after - before)}°", kotlin.math.abs(after - before) < Math.toRadians(5.0))
    }

    @Test fun bankJobPenFollowsTheCueBall() {
        val bank = Trick.LEVELS.first { it.name == "Bank job" }
        assertEquals(2, bank.zones.size)
        assertFalse(bank.farCorners)
        Table.useGap(bank.holes, bank.farCorners)
        assertEquals(3, Table.pockets.size)                  // two near corners and the hole
        val w = World(); w.clearAll()
        for ((id, x, y) in bank.balls) w.put(id, x, y)
        val pen = bank.pens[1]
        val z = bank.zones[1]
        assertTrue(Trick.place(bank, w, (z[0] + z[2]) / 2, (z[1] + z[3]) / 2))
        for ((id, x, y) in pen) { assertEquals(x, w.balls[id].x, 1e-9); assertEquals(y, w.balls[id].y, 1e-9) }
        assertFalse(Trick.place(bank, w, 0.2, P.W / 2))     // outside both pens
    }

    @Test fun bullseyeWallHasNoGapsToRollThrough() {
        val bull = Trick.LEVELS.first { it.name == "Bullseye" }
        val ring = bull.balls.filter { it.first != 1 }.sortedBy { it.third }
        for (k in 1 until ring.size) {
            val d = kotlin.math.hypot(ring[k].second - ring[k - 1].second, ring[k].third - ring[k - 1].third)
            assertTrue("gap between ${ring[k - 1].first} and ${ring[k].first}", d - 2 * P.R < 2 * P.R)
        }
    }

    @Test fun trickPracticeJudgesAndResets() {
        val m = Match(GameMode.TRICK, arrayOf("You", "You")); m.rack(Random(1), 0)
        m.trick.level = 0; m.setupTrickLevel()
        m.shoot(0.0, 1.5, 0.0, 0.0); m.world.simulate()
        val out = m.resolveTrickPractice()
        assertFalse(out.made); assertTrue(m.trick.resetPending)
        m.prepareTurn()
        assertTrue(m.world.balls[0].onTable && m.ballInHand)
        assertEquals(-1, m.winner)
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
                if (!Trick.place(Trick.LEVELS[li], w, h[0], h[1])) return@count false
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
                val w = World(); w.copyFrom(m.world)
                if (!Trick.place(lvl, w, h[0], h[1])) return@first false
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
        m.trick.level = 5; m.setupTrickLevel()                // bank job: guard rows, bonus possible
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

    // ------------------------------------------------------------------ tutorial and controls guide

    @Test fun tutorialShotGoesInWithoutScratching() {
        for (pw in listOf(0.3, 0.5, 0.75, 1.0)) {
            val m = Match(GameMode.EIGHT_BALL, arrayOf("a", "b"))
            m.rack(Random(1), 0)
            Tutorial.setup(m)
            val speed = 0.25 + (P.MAX_CUE_SPEED - 0.25) * Math.pow(pw, 1.6)
            m.shoot(Tutorial.lineUp(m.world), speed, 0.0, 0.0, 0.0)
            m.world.simulate()
            assertFalse("1 goes in at power $pw", m.world.balls[Tutorial.TARGET_BALL].onTable)
            assertTrue("no scratch at power $pw", m.world.balls[0].onTable)
        }
    }

    @Test fun tutorialStepsNeedTheRightControl() {
        val t = Tutorial()
        fun settle() { repeat(60) { t.update(0.02) } }
        assertEquals(Tutorial.Step.WELCOME, t.step)
        t.next(); assertEquals(Tutorial.Step.AIM, t.step)
        assertFalse(t.allows(TutTarget.POWER))
        t.onWheel(500.0); settle(); assertEquals("the wheel doesn't count for aiming", Tutorial.Step.AIM, t.step)
        t.onAim(0.3); t.onAim(-0.3); settle(); assertEquals(Tutorial.Step.FINE_AIM, t.step)
        t.onWheel(50.0); t.onWheel(-50.0); settle(); assertEquals(Tutorial.Step.SPIN, t.step)
        t.onSpin(0.0, 0.8); t.onRelease(TutTarget.SPIN, 0.8, 0.0); settle(); assertEquals(Tutorial.Step.POV, t.step)
        t.onElevation(40.0); settle()
        assertEquals("raising the cue only counts in the 3D view", Tutorial.Step.POV, t.step)
        t.onPov(true); t.onPov(false); settle()
        assertEquals("has to raise the cue first", Tutorial.Step.POV, t.step)
        t.onPov(true); t.onElevation(40.0); settle()
        assertEquals("still in the 3D view", Tutorial.Step.POV, t.step)
        t.onPov(false); settle(); assertEquals(Tutorial.Step.POWER, t.step)
        t.onShot(); assertTrue(t.rolling); assertFalse(t.allows(TutTarget.POWER))
        t.onShotDone(true); assertEquals(Tutorial.Step.TRAY, t.step); assertTrue(t.potted)
        t.next(); assertEquals(Tutorial.Step.BALL_IN_HAND, t.step)
        t.onCueMoved(0.2); t.onRelease(TutTarget.CUE, 0.0, 0.0); settle(); assertEquals(Tutorial.Step.MENU, t.step)
        t.onMenuOpened(); settle(); assertEquals(Tutorial.Step.DONE, t.step)
        assertFalse(t.finished)
        t.next(); assertTrue(t.finished)
    }

    @Test fun tutorialBallInHandSpotIsClear() {
        val m = Match(GameMode.EIGHT_BALL, arrayOf("a", "b"))
        m.rack(Random(1), 0)
        Tutorial.setup(m)
        Tutorial.placeForBallInHand(m)
        val c = m.world.balls[0]
        assertTrue(m.ballInHand)
        assertTrue(c.onTable)
        assertTrue("lower half, clear of the card", c.y > P.W * 0.5)
    }

    @Test fun controlsGuideMarksEveryControlOnTheMap() {
        val l = ControlsGuide.layout(ControlsGuide.MAP_W, ControlsGuide.MAP_H)
        val badges = ControlsGuide.badges(l)
        assertEquals(ControlsGuide.ITEMS.size, badges.size)
        for ((i, b) in badges.withIndex()) {
            assertTrue("badge ${i + 1} on the map", b.first in 0.0..ControlsGuide.MAP_W && b.second in 0.0..ControlsGuide.MAP_H)
            assertTrue(ControlsGuide.areas(l, i).isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ table sizes

    @Test fun eachTableSizeHasRegulationDimensions() {
        for (size in TableSize.values()) {
            Table.useStandard(size)
            assertEquals(size, Table.size)
            assertEquals(size.length, P.L, 1e-12); assertEquals(size.width, P.W, 1e-12)
            assertEquals(2.0, P.L / P.W, 1e-9)                              // always twice as long as wide
            assertEquals(6, Table.pockets.size)
            assertEquals(P.L / 2, Table.pockets.first { it.side }.cx, 1e-9)  // side pockets in the middle
            assertTrue(Table.pockets.any { !it.side && it.cx > P.L - 0.1 && it.cy > P.W - 0.1 })   // far corner moved
            assertEquals(P.L * 0.25, P.HEAD_X, 1e-12); assertEquals(P.L * 0.75, P.FOOT_X, 1e-12)
        }
        assertEquals(TableSize.SEVEN, TableSize.DEFAULT)
    }

    @Test fun rackFitsOnEveryTable() {
        for (size in TableSize.values()) for (mode in listOf(GameMode.EIGHT_BALL, GameMode.NINE_BALL)) {
            Table.useStandard(size)
            val m = Match(mode); m.rack(Random(5), 0)
            for (b in m.world.balls) if (b.onTable) {
                assertTrue("$size $mode ball ${b.id} on the cloth", b.x > P.R && b.x < P.L - P.R && b.y > P.R && b.y < P.W - P.R)
            }
            for (i in m.world.balls.indices) for (j in i + 1 until m.world.balls.size) {
                val a = m.world.balls[i]; val b = m.world.balls[j]
                if (a.onTable && b.onTable) assertTrue(hypot(a.x - b.x, a.y - b.y) >= 2 * P.R - 1e-9)
            }
        }
    }

    @Test fun matchesKeepTheirTableSize() {
        Table.useStandard(TableSize.SEVEN)
        Match(GameMode.EIGHT_BALL)                                  // a copy for the CPU keeps the size
        assertEquals(TableSize.SEVEN, Table.size); assertEquals(TableSize.SEVEN.length, P.L, 1e-12)
        Match(GameMode.TRICK)                                       // trick shots are always on the 9 ft gap table
        assertTrue(Table.gap); assertEquals(TableSize.NINE.length, P.L, 1e-12)
        Match(GameMode.NINE_BALL)                                   // and back to the chosen size after
        assertFalse(Table.gap); assertEquals(TableSize.SEVEN.length, P.L, 1e-12)
        Table.useStandard(TableSize.EIGHT)
        assertEquals(TableSize.EIGHT.length, P.L, 1e-12); assertEquals(6, Table.pockets.size)
    }

    @Test fun savedGameRemembersItsTable() {
        Table.useStandard(TableSize.EIGHT)
        val m = Match(GameMode.NINE_BALL, arrayOf("You", "CPU")); m.rack(Random(2), 0)
        m.shoot(0.0, 7.0, 0.0, 0.0); m.world.simulate(); m.resolve()
        val text = GameSave.write(m, GameSettings(GameMode.NINE_BALL, true, 4, 0, false, 0, tableSize = TableSize.EIGHT))
        assertEquals(TableSize.EIGHT, GameSave.readSettings(text)!!.tableSize)
        assertTrue(GameSave.restore(text, Match(GameMode.NINE_BALL, arrayOf("You", "CPU"))))
        Table.useStandard(TableSize.SEVEN)
        assertFalse(GameSave.restore(text, Match(GameMode.NINE_BALL, arrayOf("You", "CPU"))))   // wrong table: refuse
        // A save from before table sizes existed was played on the 9 ft table.
        val old = text.lines().filterNot { it.startsWith("table=") }.joinToString("\n")
        assertEquals(TableSize.NINE, GameSave.readSettings(old)!!.tableSize)
    }

    @Test fun straightShotDropsOnEveryTable() {
        for (size in TableSize.values()) {
            Table.useStandard(size)
            val m = Match(GameMode.EIGHT_BALL, arrayOf("You", "CPU"))
            val out = potIntoCorner(m, 3, -1)
            assertFalse("$size: 3 goes in", m.world.balls[3].onTable)
            assertFalse("$size: no foul", out.foul)
        }
    }

    @Test fun cpuFinishesGamesOnSmallerTables() {
        for (size in listOf(TableSize.SEVEN, TableSize.EIGHT)) {
            Table.useStandard(size)
            for (mode in listOf(GameMode.EIGHT_BALL, GameMode.NINE_BALL)) for (seed in 0 until 2) {
                val m = Match(mode); m.rack(Random(seed), 0)
                val ai = arrayOf(Ai(8, seed.toLong()), Ai(3, seed + 99L))
                var shots = 0
                while (m.winner < 0 && shots < 300) {
                    val p = ai[m.current].plan(m)
                    if (m.ballInHand && p.hasPlacement) assertTrue(m.placeCue(p.placeX, p.placeY))
                    m.shoot(p.angle, p.speed, p.side, p.vert); m.world.simulate(); m.resolve(); shots++
                }
                assertTrue("$size $mode seed $seed unfinished", m.winner >= 0)
                assertEquals(size, Table.size)
            }
        }
    }

    // ------------------------------------------------------------------ bumper pool

    /** A bumper pool game past its opening, with only the given balls on the table, red to shoot. */
    private fun bumper(vararg at: Triple<Int, Double, Double>): Match {
        val m = Match(GameMode.BUMPER, arrayOf("You", "CPU"))
        m.rack(Random(1), 0)
        m.isBreak = false
        m.world.clearAll()
        for ((id, x, y) in at) m.world.put(id, x, y)
        m.current = 0
        m.prepareTurn()
        return m
    }

    @Test fun bumperTableHasTwoCupsAndTwelveBumpers() {
        Match(GameMode.BUMPER)
        assertTrue(Table.bumper)
        assertEquals(2, Table.pockets.size)
        assertTrue(Table.pockets.all { it.hole })
        assertEquals(12, Table.posts.size)
        assertEquals(Table.BUMPER_L, P.L, 1e-9)
        // Every spot a ball starts on is clear of the bumpers, the cups and the other balls.
        val m = Match(GameMode.BUMPER); m.rack(Random(3), 0)
        for (id in 1..10) assertTrue("ball $id", m.world.isFree(m.world.balls[id].x, m.world.balls[id].y, id))
        assertEquals(1, m.world.cue)                 // red's marked ball is up first
        assertEquals(listOf(1), m.shootable(0))
        Table.useStandard(TableSize.SEVEN)
        assertFalse(Table.bumper); assertEquals(0, Table.posts.size)
    }

    @Test fun ballBouncesOffABumper() {
        val m = bumper(Triple(1, 0.25, 0.4), Triple(6, 1.0, 0.7))
        m.world.strike(0.0, 1.0, 0.0, 0.0)            // straight at the bumper at x = 0.39
        m.world.advance(0.25)
        val b = m.world.balls[1]
        assertTrue("came back off the bumper: x = ${b.x}", b.x < 0.39 - P.R - Table.POST_R)
        assertTrue("heading back: vx = ${b.vx}", b.vx < -0.2)
    }

    @Test fun sinkingYourBallInTheRightCupKeepsTheTurn() {
        val m = bumper(Triple(2, 0.92, 0.4), Triple(3, 0.3, 0.1), Triple(6, 0.3, 0.7))
        assertEquals(listOf(2, 3), m.shootable().sorted())   // marked ball's already down
        assertTrue(m.selectBall(2))
        m.shoot(0.0, 0.9, 0.0, 0.0); m.world.simulate()
        val out = m.resolve()
        assertFalse(m.world.balls[2].onTable)
        assertFalse(out.foul); assertTrue(out.keepTurn)
        assertEquals(0, m.current)
    }

    @Test fun ownCupIsAFoulAndTheOpponentDropsTwo() {
        val m = bumper(Triple(2, 0.28, 0.4), Triple(3, 0.9, 0.1),
            Triple(6, 1.0, 0.7), Triple(7, 0.7, 0.75), Triple(8, 0.3, 0.75), Triple(9, 0.95, 0.15))
        assertTrue(m.selectBall(2))
        m.shoot(Math.PI, 0.8, 0.0, 0.0); m.world.simulate()
        val out = m.resolve()
        assertFalse(m.world.balls[2].onTable)
        assertTrue(out.foul)
        assertEquals(2, m.ballsLeft(1))                // white had 4, dropped two (the marked one first)
        assertFalse(m.world.balls[6].onTable)
        assertEquals(1, m.current)
        assertEquals(-1, m.winner)
    }

    @Test fun lastBallInYourOwnCupLoses() {
        val m = bumper(Triple(2, 0.28, 0.4), Triple(6, 1.0, 0.7), Triple(7, 0.7, 0.75))
        m.shoot(Math.PI, 0.8, 0.0, 0.0); m.world.simulate()
        m.resolve()
        assertEquals(1, m.winner)
    }

    @Test fun lastBallInTheRightCupWins() {
        val m = bumper(Triple(2, 0.92, 0.4), Triple(6, 1.0, 0.7))
        m.shoot(0.0, 0.9, 0.0, 0.0); m.world.simulate()
        m.resolve()
        assertEquals(0, m.winner)
    }

    @Test fun markedBallMustGoFirst() {
        val m = bumper(Triple(1, 0.86, 0.4), Triple(2, 0.95, 0.4), Triple(3, 0.3, 0.1),
            Triple(6, 1.0, 0.7), Triple(7, 0.7, 0.75), Triple(8, 0.3, 0.75))
        assertEquals(listOf(1), m.shootable())
        assertFalse(m.selectBall(2))
        m.shoot(0.0, 0.9, 0.0, -0.3); m.world.simulate()   // the marked ball knocks the 2 in
        val out = m.resolve()
        assertFalse(m.world.balls[2].onTable)
        assertTrue(out.foul)
        assertEquals(1, m.current)
        assertEquals(1, m.ballsLeft(1))
    }

    @Test fun opponentsBallCountsForThem() {
        val m = bumper(Triple(2, 0.33, 0.4), Triple(3, 0.9, 0.1), Triple(7, 0.235, 0.4), Triple(8, 0.9, 0.7))
        assertTrue(m.selectBall(2))
        m.shoot(Math.PI, 0.9, 0.0, -0.45); m.world.simulate()   // draw, so the red ball comes back
        val out = m.resolve()
        assertFalse("white went in white's cup", m.world.balls[7].onTable)
        assertTrue("red stayed up", m.world.balls[2].onTable)
        assertFalse(out.foul); assertFalse(out.keepTurn)
        assertEquals(1, m.ballsLeft(1))
        assertEquals(1, m.current)
    }

    @Test fun ballOffTheTableGoesBackInTheMiddle() {
        val m = bumper(Triple(2, 0.92, 0.2), Triple(6, 1.0, 0.7), Triple(7, 0.3, 0.7), Triple(8, 0.3, 0.1), Triple(9, 0.6, 0.75))
        m.shoot(0.0, 1.0, 0.0, 0.0, toRad(50.0))
        m.world.simulate()
        val out = m.resolve()
        assertTrue(out.foul)   // a jump shot (and off the table, if it got that far)
        assertTrue(m.world.balls[2].onTable)
        assertEquals(1, m.current)
    }

    @Test fun openingShotsGoTogether() {
        val m = Match(GameMode.BUMPER, arrayOf("Player 1", "Player 2")); m.rack(Random(5), 0)
        assertTrue(m.openingPending())
        assertFalse(m.setOpening(toRad(52.0), 1.4, 0.0, 0.0, 0.0))
        assertEquals(1, m.current)
        assertEquals(6, m.world.cue)
        assertTrue(m.setOpening(toRad(-52.0) + Math.PI, 1.4, 0.0, 0.0, 0.0))
        m.fireOpening()
        assertTrue(m.world.balls[1].moving && m.world.balls[6].moving)
        m.world.simulate()
        m.resolve()
        assertFalse(m.isBreak && m.opening.any { it != null })
        assertTrue(m.winner < 0)
    }

    @Test fun bothSinkingTheOpeningGoesAgain() {
        // Red's bank off the bottom cushion; white's the mirror image off the top one.
        val m = Match(GameMode.BUMPER, arrayOf("A", "B")); m.rack(Random(5), 0)
        val ang = toRad(51.5)
        m.setOpening(ang, 1.4, 0.0, 0.0, 0.0); m.setOpening(Math.PI + ang, 1.4, 0.0, 0.0, 0.0)
        m.fireOpening(); m.world.simulate()
        m.resolve()
        assertFalse(m.world.balls[1].onTable); assertFalse(m.world.balls[6].onTable)
        assertTrue("another opening", m.openingPending())
        assertEquals(0, m.current)
        for (p in 0..1) assertTrue(m.openingBall(p) >= 0)
    }

    @Test fun bumperGameSurvivesSaving() {
        val m = Match(GameMode.BUMPER, arrayOf("You", "CPU")); m.rack(Random(2), 0)
        m.setOpening(toRad(40.0), 1.6, 0.0, 0.0, 0.0); m.setOpening(Math.PI, 1.6, 0.0, 0.0, 0.0)
        m.fireOpening(); m.world.simulate(); m.resolve()
        val text = GameSave.write(m, GameSettings(GameMode.BUMPER, true, 4, 0, false, 0))
        Table.useStandard(TableSize.SEVEN)
        val r = Match(GameMode.BUMPER, arrayOf("You", "CPU"))
        assertTrue(GameSave.restore(text, r))
        assertTrue(Table.bumper)
        assertEquals(m.current, r.current)
        for (id in 1..10) assertEquals(m.world.balls[id].onTable, r.world.balls[id].onTable)
        // A game saved while the opening was being set up starts the opening over.
        val o = Match(GameMode.BUMPER, arrayOf("You", "CPU")); o.rack(Random(2), 0)
        o.setOpening(0.5, 1.0, 0.0, 0.0, 0.0)
        val r2 = Match(GameMode.BUMPER, arrayOf("You", "CPU"))
        assertTrue(GameSave.restore(GameSave.write(o, GameSettings(GameMode.BUMPER, true, 4, 0, false, 0)), r2))
        assertTrue(r2.openingPending()); assertEquals(0, r2.current); assertTrue(r2.opening.all { it == null })
    }

    @Test fun cpuFinishesBumperGames() {
        for (seed in 0 until 3) {
            val m = Match(GameMode.BUMPER, arrayOf("A", "B")); m.rack(Random(seed), 0)
            val ai = arrayOf(Ai(8, seed.toLong()), Ai(3, seed + 99L))
            var shots = 0
            while (m.winner < 0 && shots < 300) {
                m.prepareTurn()
                val p = ai[m.current].plan(Match(m.mode, m.names).also { it.copyFrom(m) }, 400)
                assertTrue("seed $seed shot $shots: ball ${p.ball} is shootable", m.selectBall(p.ball))
                if (m.openingPending()) {
                    if (!m.setOpening(p.angle, p.speed, p.side, p.vert, p.elevation)) continue
                    m.fireOpening()
                } else m.shoot(p.angle, p.speed, p.side, p.vert, p.elevation)
                m.world.simulate(); m.resolve(); shots++
            }
            assertTrue("seed $seed unfinished", m.winner >= 0)
        }
    }

    // ------------------------------------------------------------------ circuit pool

    private fun circuit(): Match {
        val m = Match(GameMode.CIRCUIT, arrayOf("You", "CPU")); m.rack(Random(4), 0)
        m.circuit.targets[0] = intArrayOf(3, 4, 5); m.circuit.targets[1] = intArrayOf(6, 7, 8)
        return m
    }

    /** Circuit Pool: ball [id] straight into corner pocket 0; balls in [down] are already pocketed, the rest parked out of the way. */
    private fun circuitPot(m: Match, id: Int, down: List<Int> = emptyList()): ShotOutcome {
        var x = 1.3
        for (b in 1..15) {
            if (b == id) continue
            if (b in down) { m.world.balls[b].onTable = false; continue }
            m.world.put(b, x, P.W - 0.1); x += 0.07
        }
        m.world.put(id, 0.6, 0.3); m.world.put(0, 1.0, 0.5)
        val pk = Table.pockets[0]
        val d = hypot(pk.aimX - 0.6, pk.aimY - 0.3)
        val gx = 0.6 - (pk.aimX - 0.6) / d * 2 * P.R; val gy = 0.3 - (pk.aimY - 0.3) / d * 2 * P.R
        m.isBreak = false; m.ballInHand = false
        m.shoot(atan2(gy - 0.5, gx - 1.0), 2.0, 0.0, 0.0); m.world.simulate()
        assertEquals(listOf(id), m.world.log.pocketed)
        return m.resolve()
    }

    @Test fun circuitDealsThreeSecretBallsEach() {
        for (seed in 0 until 20) {
            val m = Match(GameMode.CIRCUIT); m.rack(Random(seed), 0)
            val all = (m.circuit.targets[0] + m.circuit.targets[1]).toList()
            assertEquals(6, all.toSet().size)
            assertTrue(all.all { it in 1..15 })
            assertTrue((1..15).all { m.world.balls[it].onTable })
            assertTrue(m.isBreak && m.ballInHand && m.kitchenOnly)
        }
    }

    @Test fun circuitOwnTargetKeepsTheTurn() {
        val m = circuit()
        val out = circuitPot(m, 3)
        assertTrue(out.keepTurn); assertFalse(out.foul)
        assertEquals(0, m.current); assertEquals(1, m.circuitSunk(0))
    }

    @Test fun circuitNeutralBallKeepsTheTurn() {
        val m = circuit()
        val out = circuitPot(m, 10)
        assertTrue(out.keepTurn); assertEquals(0, m.circuitSunk(0)); assertEquals(0, m.circuitSunk(1))
    }

    @Test fun circuitOpponentsTargetCountsForThemAndEndsTheTurn() {
        val m = circuit()
        val out = circuitPot(m, 7)
        assertFalse(out.keepTurn); assertFalse(out.foul)
        assertEquals(1, m.current); assertEquals(1, m.circuitSunk(1))
        assertFalse(m.ballInHand)
        assertEquals(listOf(1 shl 7, 0, 1), m.circuit.obs.last().toList())
    }

    @Test fun circuitThirdTargetWins() {
        val m = circuit()
        val out = circuitPot(m, 3, listOf(4, 5))
        assertEquals(0, out.winner); assertEquals(0, m.winner)
        // ...and sinking the opponent's last one makes them the winner.
        val m2 = circuit()
        circuitPot(m2, 8, listOf(6, 7))
        assertEquals(1, m2.winner)
    }

    @Test fun circuitDoubleDropGoesToTheShooterUnlessItsAFoul() {
        for (foul in listOf(false, true)) {
            val m = circuit()
            m.isBreak = false; m.ballInHand = false
            for (id in listOf(3, 4, 5, 6, 7, 8)) m.world.balls[id].onTable = false
            m.beginShot()
            val log = m.world.log
            log.reset(); log.firstHit = 3; log.pocketed += 3; log.pocketed += 6
            if (foul) { log.pocketed += 0; m.world.balls[0].onTable = false }
            m.resolve()
            assertEquals(if (foul) 1 else 0, m.winner)
        }
    }

    @Test fun circuitFoulGivesBallInHandAndSunkBallsStillCount() {
        val m = circuit()
        m.isBreak = false; m.ballInHand = false
        m.beginShot()
        val log = m.world.log
        log.reset(); log.firstHit = 3; log.pocketed += 3; log.pocketed += 0
        m.world.balls[3].onTable = false; m.world.balls[0].onTable = false
        val out = m.resolve()
        assertTrue(out.foul); assertEquals(1, m.current); assertTrue(m.ballInHand); assertFalse(m.kitchenOnly)
        assertEquals(1, m.circuitSunk(0)); assertTrue(m.world.balls[0].onTable)
    }

    @Test fun circuitSoftBreakIsReRackedForTheOtherPlayer() {
        val m = circuit()
        m.placeCue(P.HEAD_X - 0.05, P.FOOT_Y)
        m.shoot(0.0, 1.6, 0.0, 0.0); m.world.simulate()
        val out = m.resolve()
        assertTrue(m.world.log.pocketed.isEmpty())
        assertTrue(m.railedObjectBalls(m.world.log) < 4)
        assertFalse(out.foul)
        assertEquals(1, m.current); assertTrue(m.isBreak); assertTrue(m.ballInHand && m.kitchenOnly)
        assertTrue((1..15).all { m.world.balls[it].onTable })
        assertEquals(listOf(3, 4, 5), m.circuit.targets[0].toList())   // same targets, new rack
    }

    @Test fun circuitHardBreakIsLegal() {
        var legal = 0
        for (seed in 0 until 6) {
            val m = Match(GameMode.CIRCUIT); m.rack(Random(seed), 0)
            m.shoot(0.0, 7.0, 0.0, -0.1); m.world.simulate()
            m.resolve()
            if (!m.isBreak) legal++
        }
        assertTrue("legal breaks: $legal", legal >= 5)
    }

    @Test fun circuitBeliefOnlyUsesWhatsPublic() {
        val m = circuit()
        // CPU (player 1) watches: the 3 went down and the count for player 0 went up; the 10 went down and it didn't.
        m.world.balls[3].onTable = false; m.circuit.obs += intArrayOf(m.downMask(), 1, 0)
        m.world.balls[10].onTable = false; m.circuit.obs += intArrayOf(m.downMask(), 1, 0)
        val b = CircuitBelief(m.circuit, 1)
        assertEquals(1.0, b.pOpp[3], 1e-9)
        assertEquals(0.0, b.pOpp[10], 1e-9)
        assertEquals(0.0, b.pOpp[6], 1e-9)       // its own target
        assertTrue((1 shl 3) or (1 shl 4) or (1 shl 5) in b.sets.toList())
        assertEquals(1, b.oppSunk)
        // Changing player 0's real targets (consistent with what was seen) changes nothing the CPU knows.
        m.circuit.targets[0] = intArrayOf(3, 11, 12)
        val b2 = CircuitBelief(m.circuit, 1)
        assertEquals(b.sets.toList(), b2.sets.toList())
    }

    @Test fun circuitGameSurvivesSaving() {
        val m = circuit()
        circuitPot(m, 7)
        val text = GameSave.write(m, GameSettings(GameMode.CIRCUIT, false, 5, 0, false, 0))
        val r = Match(GameMode.CIRCUIT, arrayOf("You", "CPU"))
        assertTrue(GameSave.restore(text, r))
        assertEquals(listOf(3, 4, 5), r.circuit.targets[0].toList())
        assertEquals(listOf(6, 7, 8), r.circuit.targets[1].toList())
        assertEquals(1, r.circuit.obs.size); assertEquals(1, r.circuitSunk(1)); assertEquals(1, r.current)
    }

    @Test fun cpuFinishesCircuitGames() {
        for (seed in 0 until 4) {
            val m = Match(GameMode.CIRCUIT, arrayOf("A", "B")); m.rack(Random(seed), seed % 2)
            val ai = arrayOf(Ai(8, seed.toLong()), Ai(3, seed + 99L))
            var shots = 0
            while (m.winner < 0 && shots < 300) {
                val p = ai[m.current].plan(Match(m.mode, m.names).also { it.copyFrom(m) }, 500)
                if (m.ballInHand && p.hasPlacement) assertTrue(m.placeCue(p.placeX, p.placeY))
                m.shoot(p.angle, p.speed, p.side, p.vert, p.elevation); m.world.simulate(); m.resolve(); shots++
            }
            assertTrue("seed $seed unfinished", m.winner >= 0)
        }
    }

    // ------------------------------------------------------------------ pocket wars

    private fun wars(): Match {
        val m = Match(GameMode.POCKET_WARS, arrayOf("You", "CPU")); m.rack(Random(6), 0)
        m.isBreak = false; m.ballInHand = false
        return m
    }

    /** Pocket Wars: as if ball [id] went into pocket [pk] (a rail first if [bank], only the jaws if [jaw]); [cueIn] scratches into [cueIn]. */
    private fun warsFake(m: Match, id: Int, pk: Int, bank: Boolean = false, jaw: Boolean = false, cueIn: Int = -1): ShotOutcome {
        m.beginShot()
        val log = m.world.log
        log.reset(); log.firstHit = id; log.railAfterHit = true
        if (id > 0) { log.pocketed += id; log.pocketIn[id] = pk; m.world.balls[id].onTable = false }
        if (bank || jaw) log.railed = 1 shl id
        if (bank) log.banked = 1 shl id
        if (cueIn >= 0) { log.pocketed += 0; log.pocketIn[0] = cueIn; m.world.balls[0].onTable = false }
        return m.resolve()
    }

    @Test fun pocketWarsTableKnowsRailsFromJaws() {
        Table.useStandard(TableSize.SEVEN)
        assertEquals(6, Table.segs.count { !it.jaw })
        assertTrue(Table.segs.count { it.jaw } >= 8)
        assertEquals(6, Table.pockets.size)
    }

    @Test fun pocketWarsOpenPocketIsClaimed() {
        val m = wars()
        val out = circuitPot(m, 12)   // straight into pocket 0
        assertTrue(out.keepTurn); assertFalse(out.foul)
        assertEquals(0, m.pockets.owner[0]); assertEquals(12, m.pockets.score[0]); assertEquals(0, m.current)
    }

    @Test fun pocketWarsOwnPocketScoresAndOpponentsIsAGift() {
        val m = wars()
        m.pockets.owner[2] = 0; m.pockets.owner[3] = 1
        assertTrue(warsFake(m, 5, 2).keepTurn)
        assertEquals(5, m.pockets.score[0])
        val out = warsFake(m, 9, 3)
        assertFalse(out.keepTurn); assertFalse(out.foul)
        assertEquals(9, m.pockets.score[1]); assertEquals(1, m.pockets.owner[3]); assertEquals(1, m.current)
    }

    @Test fun pocketWarsBankStealsButTheJawsDont() {
        val m = wars()
        m.pockets.owner[4] = 1
        val jaw = warsFake(m, 7, 4, jaw = true)
        assertFalse(jaw.keepTurn); assertEquals(1, m.pockets.owner[4]); assertEquals(7, m.pockets.score[1])
        m.current = 0
        val bank = warsFake(m, 11, 4, bank = true)
        assertTrue(bank.keepTurn); assertEquals(0, m.pockets.owner[4]); assertEquals(11, m.pockets.score[0])
        assertTrue(m.message.contains("steal"))
    }

    @Test fun pocketWarsFoulSpotsTheBallsAndScratchingGivesUpYourPocket() {
        val m = wars()
        m.pockets.owner[1] = 0
        val out = warsFake(m, 10, 2, cueIn = 1)
        assertTrue(out.foul); assertEquals(1, m.current); assertTrue(m.ballInHand)
        assertTrue(m.world.balls[10].onTable); assertTrue(m.world.balls[0].onTable)
        assertEquals(0, m.pockets.score[0]); assertEquals(-1, m.pockets.owner[2]); assertEquals(-1, m.pockets.owner[1])
    }

    @Test fun pocketWarsBreakScoresButClaimsNothing() {
        val m = wars(); m.isBreak = true
        val out = warsFake(m, 3, 0)
        assertTrue(out.keepTurn); assertEquals(3, m.pockets.score[0]); assertEquals(-1, m.pockets.owner[0])
        assertFalse(m.isBreak)
    }

    @Test fun pocketWarsFirstTo61Wins() {
        val m = wars()
        m.pockets.score[0] = 50; m.pockets.owner[0] = 0
        warsFake(m, 11, 0)
        assertEquals(0, m.winner)
        // A gift can win it for the other player.
        val m2 = wars()
        m2.pockets.score[1] = 55; m2.pockets.owner[0] = 1
        warsFake(m2, 6, 0)
        assertEquals(1, m2.winner)
    }

    @Test fun pocketWarsTieGoesToMorePockets() {
        val m = wars()
        for (id in 1..15) if (id != 8) m.world.balls[id].onTable = false
        m.pockets.score[0] = 52; m.pockets.score[1] = 60
        m.pockets.owner[0] = 0; m.pockets.owner[1] = 0; m.pockets.owner[2] = 1
        warsFake(m, 8, 0)
        assertEquals(60, m.pockets.score[0]); assertEquals(0, m.winner)
    }

    @Test fun pocketWarsSurvivesSaving() {
        val m = wars()
        m.pockets.owner[3] = 1; m.pockets.owner[5] = 0; m.pockets.score[0] = 17; m.pockets.score[1] = 9
        val text = GameSave.write(m, GameSettings(GameMode.POCKET_WARS, true, 6, 0, false, 0))
        val r = Match(GameMode.POCKET_WARS, arrayOf("You", "CPU"))
        assertTrue(GameSave.restore(text, r))
        assertEquals(m.pockets.owner.toList(), r.pockets.owner.toList())
        assertEquals(m.pockets.score.toList(), r.pockets.score.toList())
    }

    @Test fun cpuFinishesPocketWarsGames() {
        for (seed in 0 until 3) {
            val m = Match(GameMode.POCKET_WARS, arrayOf("A", "B")); m.rack(Random(seed), seed % 2)
            val ai = arrayOf(Ai(8, seed.toLong()), Ai(3, seed + 99L))
            var shots = 0
            while (m.winner < 0 && shots < 300) {
                val p = ai[m.current].plan(Match(m.mode, m.names).also { it.copyFrom(m) }, 500)
                if (m.ballInHand && p.hasPlacement) assertTrue(m.placeCue(p.placeX, p.placeY))
                m.shoot(p.angle, p.speed, p.side, p.vert, p.elevation); m.world.simulate(); m.resolve(); shots++
            }
            assertTrue("seed $seed unfinished", m.winner >= 0)
            assertTrue(m.pockets.score[m.winner] >= m.pockets.score[1 - m.winner])
        }
    }

    @Test fun practiceRulesSurviveSaving() {
        val m = wars()
        val s = GameSave.readSettings(GameSave.write(m, GameSettings(GameMode.POCKET_WARS, false, 5, 0, true, 0, practiceRules = true)))!!
        assertTrue(s.practice && s.practiceRules)
        assertFalse(GameSave.readSettings(GameSave.write(m, GameSettings(GameMode.POCKET_WARS, false, 5, 0, true, 0)))!!.practiceRules)
    }

    // ------------------------------------------------------------------ cutthroat

    private fun cutthroat(): Match {
        val m = Match(GameMode.CUTTHROAT, arrayOf("You", "CPU 1", "CPU 2")); m.rack(Random(4), 0)
        m.isBreak = false; m.ballInHand = false
        return m
    }

    /** Cutthroat: as if [ids] went down (in that order) on a shot that hit [ids]'s first ball; [scratch] sinks the cue ball too. */
    private fun cutFake(m: Match, vararg ids: Int, scratch: Boolean = false): ShotOutcome {
        m.beginShot()
        val log = m.world.log
        log.reset(); log.firstHit = if (ids.isNotEmpty()) ids[0] else 1; log.railAfterHit = true
        for (id in ids) { log.pocketed += id; log.pocketIn[id] = 0; m.world.balls[id].onTable = false }
        if (scratch) { log.pocketed += 0; log.pocketIn[0] = 0; m.world.balls[0].onTable = false }
        return m.resolve()
    }

    private fun Match.sink(vararg ids: Int) { for (id in ids) world.balls[id].onTable = false }

    @Test fun cutthroatRacksFifteenWithTheOneOnTheSpot() {
        val m = cutthroat()
        assertEquals(15, (1..15).count { m.world.balls[it].onTable })
        assertEquals(P.FOOT_X, m.world.balls[1].x, 1e-9)
        assertEquals(3, m.players)
        assertEquals(15, m.legalTargets().size)
    }

    @Test fun cutthroatFirstPotPicksAnyGroupAndTheLastOneIsHandedOut() {
        val m = cutthroat()
        val a = cutFake(m, 7)
        assertTrue(a.keepTurn); assertEquals(0, m.cut.choosing); assertEquals(-1, m.cutGroup(0))
        assertEquals(listOf(0, 1, 2), m.cut.free())
        assertTrue(m.cutPick(2).keepTurn)         // any group, not just the 7's
        assertEquals(2, m.cutGroup(0)); assertEquals(-1, m.cut.choosing); assertEquals(0, m.current)
        cutFake(m, 3)                              // already has a group: no pick
        assertEquals(-1, m.cut.choosing)
        m.current = 1
        m.sink(4)
        cutFake(m, 13)
        assertEquals(1, m.cut.choosing); assertEquals(listOf(0, 1), m.cut.free())
        assertEquals(1, m.cutSuggestedGroup())     // the group with more balls left (1–5 is down to three)
        m.cutPick(0)
        assertEquals(0, m.cutGroup(1)); assertEquals(1, m.cutGroup(2))
        assertTrue(m.message.contains("6–10"))
    }

    @Test fun cutthroatPickingAnEmptyGroupPutsYouOut() {
        val m = cutthroat()
        m.cut.group[1] = 1
        m.sink(1, 2, 3, 4, 5)
        cutFake(m, 12)
        m.cutPick(0)
        assertTrue(m.cutOut(0)); assertEquals(2, m.cutGroup(2)); assertEquals(1, m.current)
    }

    @Test fun cutthroatBreakPicksNoGroups() {
        val m = cutthroat(); m.isBreak = true
        val out = cutFake(m, 4)
        assertTrue(out.keepTurn); assertEquals(-1, m.cutGroup(0)); assertFalse(m.isBreak)
    }

    @Test fun cutthroatAnyBallKeepsYouShootingAndTurnsGoRound() {
        val m = cutthroat()
        m.cut.group[0] = 0; m.cut.group[1] = 1; m.cut.group[2] = 2
        assertTrue(cutFake(m, 9).keepTurn); assertEquals(0, m.current)
        // Your own ball keeps you at the table too (cutting your own throat).
        val own = cutFake(m, 2)
        assertTrue(own.keepTurn); assertFalse(own.foul); assertEquals(0, m.current)
        // Turns go round all three.
        cutFake(m); assertEquals(1, m.current)
        cutFake(m); assertEquals(2, m.current)
        cutFake(m); assertEquals(0, m.current)
    }

    @Test fun cutthroatFoulGivesEachOpponentABallBack() {
        val m = cutthroat()
        m.cut.group[0] = 0; m.cut.group[1] = 1; m.cut.group[2] = 2
        m.sink(6, 8, 12, 3)
        val out = cutFake(m, 9, scratch = true)
        assertTrue(out.foul); assertEquals(1, m.current); assertTrue(m.ballInHand)
        assertTrue(m.world.balls[6].onTable)         // CPU 1's lowest down comes back
        assertFalse(m.world.balls[8].onTable); assertFalse(m.world.balls[9].onTable)
        assertTrue(m.world.balls[12].onTable)        // CPU 2's
        assertFalse(m.world.balls[3].onTable)        // not the fouler's own
        assertTrue(m.world.balls[0].onTable)
    }

    @Test fun cutthroatOutPlayersAreSkippedAndTheLastOneLeftWins() {
        val m = cutthroat()
        m.cut.group[0] = 0; m.cut.group[1] = 1; m.cut.group[2] = 2
        m.sink(6, 7, 8, 9, 11, 12, 13)
        val out = cutFake(m, 10)
        assertTrue(out.keepTurn); assertTrue(m.cutOut(1)); assertTrue(m.message.contains("CPU 1 is out"))
        cutFake(m)   // a miss: CPU 1 is out, so CPU 2 is next
        assertEquals(2, m.current)
        m.current = 0
        cutFake(m, 14, 15)
        assertEquals(0, m.winner)
    }

    @Test fun cutthroatAFoulCanBringAPlayerBack() {
        val m = cutthroat()
        m.cut.group[0] = 0; m.cut.group[1] = 1; m.cut.group[2] = 2
        m.sink(6, 7, 8, 9, 10)
        assertTrue(m.cutOut(1))
        m.current = 2
        cutFake(m, scratch = true)
        assertFalse(m.cutOut(1)); assertTrue(m.world.balls[6].onTable); assertEquals(0, m.current)
    }

    @Test fun cutthroatSurvivesSaving() {
        val m = cutthroat()
        m.cut.group[0] = 2; m.cut.group[1] = 0; m.cut.group[2] = 1; m.current = 2
        val text = GameSave.write(m, GameSettings(GameMode.CUTTHROAT, true, 6, 0, false, 1))
        val r = Match(GameMode.CUTTHROAT, arrayOf("You", "CPU 1", "CPU 2"))
        assertTrue(GameSave.restore(text, r))
        assertEquals(m.cut.group.toList(), r.cut.group.toList())
        assertEquals(2, r.current)
    }

    @Test fun cpuFinishesCutthroatGames() {
        for (seed in 0 until 3) {
            val m = Match(GameMode.CUTTHROAT, arrayOf("A", "B", "C")); m.rack(Random(seed), seed % 3)
            val ai = arrayOf(Ai(8, seed.toLong()), Ai(5, seed + 50L), Ai(3, seed + 99L))
            var shots = 0
            while (m.winner < 0 && shots < 400) {
                val p = ai[m.current].plan(Match(m.mode, m.names).also { it.copyFrom(m) }, 500)
                if (m.ballInHand && p.hasPlacement) assertTrue(m.placeCue(p.placeX, p.placeY))
                m.shoot(p.angle, p.speed, p.side, p.vert, p.elevation); m.world.simulate(); m.resolve(); shots++
                if (m.cut.choosing >= 0) m.cutPick(m.cutSuggestedGroup())
            }
            assertTrue("seed $seed unfinished", m.winner >= 0)
            assertFalse(m.cutOut(m.winner))
            assertEquals(1, (0..2).count { !m.cutOut(it) })
        }
    }
}
