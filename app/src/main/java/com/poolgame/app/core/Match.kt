package com.poolgame.app.core

import kotlin.math.sqrt
import kotlin.random.Random

enum class GameMode { EIGHT_BALL, NINE_BALL }
enum class Group { OPEN, SOLIDS, STRIPES }

/** Called-pocket rule: off, only for the money ball (the 8 in 8-ball, the 9 in 9-ball), or every shot. */
enum class CallMode { OFF, MONEY_BALL, EVERY_SHOT }

class ShotOutcome(
    val foul: Boolean,
    val foulReason: String,
    val keepTurn: Boolean,
    val winner: Int,
    val message: String
)

/**
 * Game state + rules for 8-ball and 9-ball (simplified WPA-style, no called pockets,
 * ball-in-hand anywhere after any foul, ball-in-hand behind the head string for the break).
 */
class Match(val mode: GameMode, val names: Array<String> = arrayOf("Player 1", "Player 2")) {
    val world = World()
    var current = 0
    val groups = arrayOf(Group.OPEN, Group.OPEN)
    var ballInHand = true
    var kitchenOnly = true
    var isBreak = true
    var winner = -1
    var message = ""
    val consecutiveFouls = intArrayOf(0, 0)

    // Snapshot taken at the start of each shot
    var callMode = CallMode.OFF
    /** Pocket the shooter has called for the next shot (index into Table.pockets), or -1. */
    var calledPocket = -1

    private var preTargets = IntArray(0)
    private var preOnEight = false
    private var preCalled = -1
    private var preCallActive = false

    fun copyFrom(o: Match) {
        world.copyFrom(o.world)
        current = o.current
        groups[0] = o.groups[0]; groups[1] = o.groups[1]
        ballInHand = o.ballInHand; kitchenOnly = o.kitchenOnly; isBreak = o.isBreak
        winner = o.winner; message = o.message
        consecutiveFouls[0] = o.consecutiveFouls[0]; consecutiveFouls[1] = o.consecutiveFouls[1]
        preTargets = o.preTargets; preOnEight = o.preOnEight
        callMode = o.callMode; calledPocket = o.calledPocket
        preCalled = o.preCalled; preCallActive = o.preCallActive
    }

    // "You win" / "CPU wins" — the player named "You" takes the plural verb form.
    private fun you(p: Int) = names[p] == "You"
    fun winsText(p: Int) = if (you(p)) "You win!" else "${names[p]} wins!"
    private fun has(p: Int) = if (you(p)) "You have" else "${names[p]} has"
    private fun continues(p: Int) = if (you(p)) "You continue" else "${names[p]} continues"

    fun ballIds(): IntRange = if (mode == GameMode.EIGHT_BALL) 1..15 else 1..9

    // ------------------------------------------------------------------ setup

    fun rack(rng: Random, breaker: Int) {
        for (b in world.balls) { b.onTable = false; b.stop() }
        val r = P.R + 0.0002
        val dx = sqrt(3.0) * r
        val positions = ArrayList<Pair<Double, Double>>()
        val order: List<Int>
        if (mode == GameMode.EIGHT_BALL) {
            for (row in 0..4) for (k in 0..row)
                positions += (P.FOOT_X + row * dx) to (P.FOOT_Y + (k - row / 2.0) * 2 * r)
            // index 4 = row 2 middle (8-ball); 10 and 14 = back corners
            val solids = (1..7).shuffled(rng).toMutableList()
            val stripes = (9..15).shuffled(rng).toMutableList()
            val slots = IntArray(15)
            slots[4] = 8
            val solidCorner = rng.nextBoolean()
            slots[10] = if (solidCorner) solids.removeAt(0) else stripes.removeAt(0)
            slots[14] = if (solidCorner) stripes.removeAt(0) else solids.removeAt(0)
            val rest = (solids + stripes).shuffled(rng).toMutableList()
            for (i in 0 until 15) if (slots[i] == 0) slots[i] = rest.removeAt(0)
            order = slots.toList()
        } else {
            val rows = intArrayOf(1, 2, 3, 2, 1)
            for (row in rows.indices) for (k in 0 until rows[row])
                positions += (P.FOOT_X + row * dx) to (P.FOOT_Y + (k - (rows[row] - 1) / 2.0) * 2 * r)
            val slots = IntArray(9)
            slots[0] = 1
            slots[4] = 9   // centre of the diamond
            val rest = (2..8).shuffled(rng).toMutableList()
            for (i in 0 until 9) if (slots[i] == 0) slots[i] = rest.removeAt(0)
            order = slots.toList()
        }
        for (i in order.indices) {
            val b = world.balls[order[i]]
            b.x = positions[i].first; b.y = positions[i].second
            b.onTable = true
            randomOrientation(b, rng)
        }
        val c = world.balls[0]
        c.x = P.HEAD_X; c.y = P.FOOT_Y; c.onTable = true
        current = breaker
        groups[0] = Group.OPEN; groups[1] = Group.OPEN
        ballInHand = true; kitchenOnly = true; isBreak = true
        winner = -1
        consecutiveFouls[0] = 0; consecutiveFouls[1] = 0
        message = "${names[breaker]} to break"
    }

    private fun randomOrientation(b: Ball, rng: Random) {
        val w = rng.nextDouble(-1.0, 1.0); val x = rng.nextDouble(-1.0, 1.0)
        val y = rng.nextDouble(-1.0, 1.0); val z = rng.nextDouble(-1.0, 1.0)
        val n = sqrt(w * w + x * x + y * y + z * z)
        b.qw = w / n; b.qx = x / n; b.qy = y / n; b.qz = z / n
    }

    // ------------------------------------------------------------------ queries

    fun groupOf(id: Int): Group = when (id) {
        in 1..7 -> Group.SOLIDS
        in 9..15 -> Group.STRIPES
        else -> Group.OPEN
    }

    fun remaining(g: Group): Int = world.balls.count { it.onTable && groupOf(it.id) == g }

    fun lowestBall(): Int {
        for (i in 1..9) if (world.balls[i].onTable) return i
        return 9
    }

    fun onEight(player: Int): Boolean =
        mode == GameMode.EIGHT_BALL && groups[player] != Group.OPEN && remaining(groups[player]) == 0

    /** Balls the given player may legally contact first. */
    fun legalTargets(player: Int = current): IntArray {
        if (mode == GameMode.NINE_BALL) return intArrayOf(lowestBall())
        if (isBreak) return (1..15).filter { world.balls[it].onTable }.toIntArray()
        val g = groups[player]
        if (g == Group.OPEN) return (1..15).filter { it != 8 && world.balls[it].onTable }.toIntArray()
        if (remaining(g) == 0) return intArrayOf(8)
        return (1..15).filter { groupOf(it) == g && world.balls[it].onTable }.toIntArray()
    }

    fun canPlaceCue(x: Double, y: Double): Boolean {
        if (kitchenOnly && x > P.HEAD_X) return false
        return world.isFree(x, y, 0)
    }

    fun placeCue(x: Double, y: Double): Boolean {
        if (!canPlaceCue(x, y)) return false
        val c = world.balls[0]
        c.x = x; c.y = y; c.onTable = true; c.stop()
        return true
    }

    // ------------------------------------------------------------------ shooting

    /** Whether the shooter must call a pocket before this shot. */
    fun callRequired(): Boolean = !isBreak && when (callMode) {
        CallMode.OFF -> false
        CallMode.MONEY_BALL -> if (mode == GameMode.EIGHT_BALL) onEight(current) else lowestBall() == 9
        CallMode.EVERY_SHOT -> true
    }

    /** Calling is allowed whenever the rule is on (e.g. to call a 9-ball combo). */
    fun callAllowed(): Boolean = callMode != CallMode.OFF && !isBreak

    fun beginShot() {
        preTargets = legalTargets(current)
        preOnEight = onEight(current)
        preCallActive = callAllowed()
        preCalled = if (preCallActive) calledPocket else -1
        calledPocket = -1
    }

    fun shoot(angle: Double, speed: Double, side: Double, vert: Double, elevation: Double = 0.0) {
        beginShot()
        ballInHand = false
        kitchenOnly = false
        world.strike(angle, speed, side, vert, elevation)
    }

    /** The shot clock ran out: a foul, opponent gets ball in hand. */
    fun timeFoul(): ShotOutcome {
        val shooter = current
        val opp = 1 - shooter
        isBreak = false
        consecutiveFouls[shooter]++
        if (mode == GameMode.NINE_BALL && consecutiveFouls[shooter] >= 3) {
            winner = opp
            message = "${winsText(opp)} (three fouls in a row)"
            return ShotOutcome(true, "Shot clock", false, opp, message)
        }
        current = opp
        ballInHand = true
        kitchenOnly = false
        if (!world.balls[0].onTable) restoreCue()
        message = "Foul: shot clock expired · ${has(opp)} ball in hand"
        return ShotOutcome(true, "Shot clock", false, -1, message)
    }

    /** Apply the rules once all balls have stopped. */
    fun resolve(): ShotOutcome {
        val log = world.log
        val shooter = current
        val opp = 1 - shooter
        val pocketed = log.pocketed
        val jumped = log.jumpedOff
        val cueIn = 0 in pocketed
        val cueOff = 0 in jumped
        var foul = false
        var reason = ""
        when {
            cueIn -> { foul = true; reason = "Scratch" }
            cueOff -> { foul = true; reason = "Cue ball jumped off the table" }
            log.firstHit < 0 -> { foul = true; reason = "No ball hit" }
            !(mode == GameMode.EIGHT_BALL && isBreak) && log.firstHit !in preTargets -> {
                foul = true
                reason = if (mode == GameMode.NINE_BALL) "Must hit the ${preTargets[0]} first" else "Wrong ball hit first"
            }
            jumped.isNotEmpty() -> { foul = true; reason = "Ball jumped off the table" }
            pocketed.isEmpty() && !log.railAfterHit -> { foul = true; reason = "No rail after contact" }
        }

        var win = -1
        val notes = ArrayList<String>()
        val objects = pocketed.filter { it != 0 }

        // Jumped object balls go back on the foot spot (the 8 and 9 have their own rules below).
        for (id in jumped) if (id != 0 && id != 8 && id != 9) respot(id)
        if (mode == GameMode.EIGHT_BALL) {
            if (9 in jumped) respot(9)
            if (8 in jumped) {
                if (isBreak) { respot(8); notes += "8 respotted" }
                else { win = opp; notes += "${names[shooter]} jumped the 8 off the table" }
            }
            if (8 in pocketed) {
                if (isBreak) {
                    respot(8); notes += "8 on the break is respotted"
                } else if (preOnEight && !foul && preCallActive && log.pocketIn[8] != preCalled) {
                    win = opp
                    notes += "the 8 went in the wrong pocket"
                } else if (preOnEight && !foul) {
                    win = shooter
                } else {
                    win = opp
                    notes += if (foul) "${names[shooter]} fouled on the 8" else "${names[shooter]} sank the 8 early"
                }
            }
            if (win < 0 && !isBreak && !foul && groups[shooter] == Group.OPEN) {
                val first = if (callMode == CallMode.EVERY_SHOT)
                    objects.firstOrNull { it != 8 && log.pocketIn[it] == preCalled }
                else objects.firstOrNull { it != 8 }
                if (first != null) {
                    val g = groupOf(first)
                    groups[shooter] = g
                    groups[opp] = if (g == Group.SOLIDS) Group.STRIPES else Group.SOLIDS
                    notes += "${names[shooter]}: ${if (g == Group.SOLIDS) "solids" else "stripes"}"
                }
            }
        } else {
            if (9 in jumped) { respot(9); notes += "9 respotted" }
            if (9 in pocketed) {
                val calledOk = !preCallActive || log.pocketIn[9] == preCalled
                when {
                    !foul && calledOk -> win = shooter
                    !foul -> { respot(9); notes += "9 not in the called pocket · respotted" }
                    else -> { respot(9); notes += "9 respotted" }
                }
            }
        }

        if (win < 0) {
            if (foul) {
                consecutiveFouls[shooter]++
                if (mode == GameMode.NINE_BALL && consecutiveFouls[shooter] >= 3) {
                    win = opp
                    notes += "three fouls in a row"
                }
            } else consecutiveFouls[shooter] = 0
        }

        val own: (Int) -> Boolean = when {
            mode == GameMode.NINE_BALL -> { _ -> true }
            groups[shooter] == Group.OPEN -> { id -> id != 8 }
            else -> { id -> groupOf(id) == groups[shooter] }
        }
        // With "call every shot", only your ball in the called pocket keeps the turn; anything else is slop.
        val everyShot = callMode == CallMode.EVERY_SHOT && preCallActive
        val keep = win < 0 && !foul && objects.any { own(it) && (!everyShot || log.pocketIn[it] == preCalled) }
        if (win < 0 && !foul && !keep && everyShot && objects.isNotEmpty()) notes += "Slop: not in the called pocket"


        isBreak = false
        winner = win
        if (win >= 0) {
            message = winsText(win) + if (notes.isNotEmpty()) " (${notes.joinToString(", ")})" else ""
            return ShotOutcome(foul, reason, false, win, message)
        }

        if (foul) {
            current = opp
            ballInHand = true
            kitchenOnly = false
            if (cueIn || cueOff || !world.balls[0].onTable) restoreCue()
            notes.add(0, "Foul: $reason")
            notes += "${has(opp)} ball in hand"
        } else if (!keep) {
            current = opp
            notes += "${names[opp]} to shoot"
        } else {
            notes += continues(shooter)
        }
        message = notes.joinToString(" · ")
        return ShotOutcome(foul, reason, keep, -1, message)
    }

    /** Put the cue ball back on the table near the head spot (practice mode). */
    fun respawnCue() = restoreCue()

    private fun restoreCue() {
        val c = world.balls[0]
        c.stop()
        var x = P.HEAD_X; var y = P.FOOT_Y
        var tries = 0
        while (!world.isFree(x, y, 0) && tries < 200) {
            x = P.HEAD_X - 0.05 * (tries % 10); y = P.FOOT_Y + 0.06 * ((tries / 10) - 10)
            tries++
        }
        c.x = x; c.y = y; c.onTable = true
    }

    private fun respot(id: Int) {
        val b = world.balls[id]
        b.stop()
        var x = P.FOOT_X
        while (x < P.L - P.R && !world.isFree(x, P.FOOT_Y, id)) x += 0.005
        if (x >= P.L - P.R) {
            x = P.FOOT_X
            while (x > P.R && !world.isFree(x, P.FOOT_Y, id)) x -= 0.005
        }
        b.x = x; b.y = P.FOOT_Y; b.onTable = true
    }
}
