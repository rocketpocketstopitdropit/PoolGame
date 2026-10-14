package com.poolgame.app.core

import org.junit.Assert.assertEquals
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
        for (mode in GameMode.values()) for (seed in 0 until 3) {
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
}
