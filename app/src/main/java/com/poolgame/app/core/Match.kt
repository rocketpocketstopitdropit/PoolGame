package com.poolgame.app.core

import kotlin.math.sqrt
import kotlin.random.Random

/** TRICK: trick shots on the gap table (jump the gap, sink the 1), head to head. */
enum class GameMode { EIGHT_BALL, NINE_BALL, TRICK }
enum class Group { OPEN, SOLIDS, STRIPES }

/** Called-pocket rule: off, only for the money ball (the 8 in 8-ball, the 9 in 9-ball), or every shot. */
enum class CallMode { OFF, MONEY_BALL, EVERY_SHOT }

class ShotOutcome(
    val foul: Boolean,
    val foulReason: String,
    val keepTurn: Boolean,
    val winner: Int,
    val message: String,
    /** A table foul worth a cough-disguised "you suck": no ball hit, wrong ball first, or no rail and nothing potted. */
    val heckle: Boolean = false,
    /** Trick shots: the shooter made it. */
    val made: Boolean = false
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

    /** Trick-shot match state (only used in GameMode.TRICK). */
    val trick = TrickState()

    init {
        // The trick-shot game uses the gap table; everything else the regulation table.
        if (mode == GameMode.TRICK) Table.useGap() else Table.useStandard()
    }

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
        trick.copyFrom(o.trick)
    }

    // "You win" / "CPU wins" — the player named "You" takes the plural verb form.
    private fun you(p: Int) = names[p] == "You"
    fun winsText(p: Int) = if (you(p)) "You win!" else "${names[p]} wins!"
    private fun has(p: Int) = if (you(p)) "You have" else "${names[p]} has"
    private fun continues(p: Int) = if (you(p)) "You continue" else "${names[p]} continues"

    fun ballIds(): List<Int> = when (mode) {
        GameMode.EIGHT_BALL -> IDS_8
        GameMode.NINE_BALL -> IDS_9
        GameMode.TRICK -> Trick.LEVELS[trick.level].ids
    }

    // ------------------------------------------------------------------ setup

    fun rack(rng: Random, breaker: Int) {
        if (mode == GameMode.TRICK) { startTrick(breaker); return }
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

    /** Rock, paper, scissors decided who goes first. */
    fun setBreaker(p: Int) {
        current = p
        if (mode == GameMode.TRICK) {
            trick.starter = p
            message = "Level ${trick.levelNo + 1} · ${Trick.LEVELS[trick.level].name} · ${names[p]} to shoot first"
        } else message = "${names[p]} to break"
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
        if (mode == GameMode.TRICK) return (1..15).filter { world.balls[it].onTable }.toIntArray()
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
    fun callRequired(): Boolean = !isBreak && mode != GameMode.TRICK && when (callMode) {
        CallMode.OFF -> false
        CallMode.MONEY_BALL -> if (mode == GameMode.EIGHT_BALL) onEight(current) else lowestBall() == 9
        CallMode.EVERY_SHOT -> true
    }

    /** Calling is allowed whenever the rule is on (e.g. to call a 9-ball combo). */
    fun callAllowed(): Boolean = callMode != CallMode.OFF && !isBreak && mode != GameMode.TRICK

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
        if (mode == GameMode.TRICK) return trickAttempt(false, "the shot clock ran out", false)
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
        if (mode == GameMode.TRICK) return resolveTrick()
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

        // Not hitting anything, hitting the wrong ball first, or no rail with nothing potted:
        // someone in the crowd coughs out a "you suck".
        val heckle = foul && !cueIn && !cueOff && jumped.isEmpty() &&
            (log.firstHit < 0 || reason.startsWith("Must hit") || reason == "Wrong ball hit first" || reason == "No rail after contact")
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
        return ShotOutcome(foul, reason, keep, -1, message, heckle)
    }

    // ------------------------------------------------------------------ trick shots

    private fun startTrick(starter: Int) {
        trick.reset()
        trick.starter = starter
        current = starter
        winner = -1
        consecutiveFouls[0] = 0; consecutiveFouls[1] = 0
        setupTrickLevel()
        message = "Level 1 · ${Trick.LEVELS[trick.level].name} · ${names[starter]} to shoot first"
    }

    /** Puts the balls where the current level has them, with the cue ball in hand in the break area. */
    fun setupTrickLevel() {
        val lv = Trick.LEVELS[trick.level]
        for (b in world.balls) { b.onTable = false; b.stop() }
        for ((id, x, y) in lv.balls) {
            val b = world.balls[id]
            b.x = x; b.y = y; b.onTable = true
            b.qw = 1.0; b.qx = 0.0; b.qy = 0.0; b.qz = 0.0
        }
        val c = world.balls[0]
        c.x = lv.cueX; c.y = lv.cueY; c.onTable = true
        ballInHand = true; kitchenOnly = true; isBreak = false
        trick.resetPending = false
        world.log.reset()
    }

    /** Trick shots: after each attempt the balls go back to the level's layout at the start of the next turn. */
    fun prepareTurn() {
        if (mode == GameMode.TRICK && trick.resetPending && winner < 0) setupTrickLevel()
    }

    private fun resolveTrick(): ShotOutcome {
        val log = world.log
        val cueLost = 0 in log.pocketed || 0 in log.jumpedOff
        val made = Trick.TARGET in log.pocketed && !cueLost
        val why = when {
            made -> ""
            0 in log.fell -> "the cue ball fell into the gap"
            0 in log.jumpedOff -> "the cue ball flew off the table"
            0 in log.pocketed -> "scratch"
            Trick.TARGET in log.fell -> "the 1 fell into the gap"
            Trick.TARGET in log.jumpedOff -> "the 1 flew off the table"
            log.firstHit < 0 -> "didn't hit a ball"
            else -> "the 1 stayed up"
        }
        return trickAttempt(made, why, !made && !cueLost && log.firstHit < 0)
    }

    /**
     * One attempt at the level. Players alternate attempts; whoever makes it in fewer attempts wins
     * the level (same number = a tie). Most levels won takes the match; a tie goes to sudden death.
     */
    private fun trickAttempt(made: Boolean, why: String, heckle: Boolean): ShotOutcome {
        val t = trick
        val p = current
        val second = 1 - t.starter
        t.tries[p]++
        if (made && t.made[p] == 0) t.made[p] = t.tries[p]
        t.resetPending = true
        val notes = ArrayList<String>()
        notes += if (made) (if (you(p)) "You made it!" else "${names[p]} made it!") else "Missed: $why"
        var levelResult = -2   // -2: level goes on, -1: tie, 0/1: winner
        if (p == t.starter) {
            current = second
        } else {
            val a = t.made[t.starter] > 0
            val b = t.made[second] > 0
            levelResult = when {
                a && b -> -1
                a -> t.starter
                b -> second
                !t.suddenDeath && t.round >= Trick.ROUNDS -> -1
                else -> -2
            }
            if (levelResult == -2) { t.round++; current = t.starter }
        }
        var win = -1
        if (levelResult != -2) {
            t.results += levelResult
            if (levelResult >= 0) t.levelWins[levelResult]++
            notes += when {
                levelResult >= 0 -> if (you(levelResult)) "You win the level" else "${names[levelResult]} wins the level"
                t.made[0] > 0 -> "Level tied"
                else -> "Nobody made it · level tied"
            }
            t.levelNo++
            if (t.suddenDeath) {
                if (levelResult >= 0) win = levelResult
            } else if (t.levelNo >= Trick.LEVELS.size) {
                if (t.levelWins[0] != t.levelWins[1]) win = if (t.levelWins[0] > t.levelWins[1]) 0 else 1
                else { t.suddenDeath = true; notes += "Sudden death!" }
            }
            if (win < 0) {
                t.level = t.levelNo % Trick.LEVELS.size
                t.round = 1
                t.tries[0] = 0; t.tries[1] = 0; t.made[0] = 0; t.made[1] = 0
                t.starter = 1 - t.starter
                current = t.starter
                notes += "Level ${t.levelNo + 1} · ${Trick.LEVELS[t.level].name} · ${names[current]} first"
            }
        } else if (p == t.starter) {
            notes += if (you(second)) "Your turn" else "${names[second]}'s turn"
        } else {
            notes += "Round ${t.round} · " + if (you(current)) "your turn" else "${names[current]}'s turn"
        }
        winner = win
        ballInHand = true; kitchenOnly = true
        message = if (win >= 0) "${winsText(win)} (${t.levelWins[win]}–${t.levelWins[1 - win]})" else notes.joinToString(" · ")
        return ShotOutcome(!made, why, false, win, message, heckle, made)
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

    private companion object {
        val IDS_8 = (1..15).toList()
        val IDS_9 = (1..9).toList()
    }
}
