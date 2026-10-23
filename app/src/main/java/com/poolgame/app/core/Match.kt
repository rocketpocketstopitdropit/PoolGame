package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * TRICK: trick shots on the gap table (jump the gap, sink the 1), head to head.
 * BUMPER: bumper pool on its own small table (two cups, twelve bumpers, five balls each, no cue ball).
 * CIRCUIT: Circuit Pool — each player is secretly dealt three of the fifteen balls; first to sink all three wins.
 * POCKET_WARS: claim pockets by sinking balls in them; balls score their number; bank into a rival's pocket to steal it.
 * CUTTHROAT: three players, groups 1–5, 6–10 and 11–15; sink the others' balls, last one with a ball left wins.
 */
enum class GameMode { EIGHT_BALL, NINE_BALL, TRICK, BUMPER, CIRCUIT, POCKET_WARS, CUTTHROAT }
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
class Match(val mode: GameMode, val names: Array<String> = arrayOf("Player 1", "Player 2", "Player 3")) {
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

    /** Circuit Pool: each player's secret targets and what both players have seen (only used in GameMode.CIRCUIT). */
    val circuit = CircuitState()

    /** Pocket Wars: who owns each pocket, and the points (only used in GameMode.POCKET_WARS). */
    val pockets = PocketWarsState()

    /** Cutthroat: each player's group (only used in GameMode.CUTTHROAT, the one three-player game). */
    val cut = CutthroatState()

    /** How many players take turns: three in Cutthroat, two in everything else. */
    val players get() = if (mode == GameMode.CUTTHROAT) 3 else 2

    /**
     * Bumper pool's opening: both players shoot their marked ball at the same moment. Each sets a
     * shot ([angle, speed, side, vert, elevation]) in turn; it waits here, unseen, until both are in.
     */
    val opening = arrayOfNulls<DoubleArray>(2)
    private val openIds = intArrayOf(-1, -1)

    init {
        // The trick-shot game uses the gap table, bumper pool its own table, everything else the regulation table.
        // (A trick match keeps whatever holes the current level has: the CPU's copies are made mid-game.)
        when (mode) {
            GameMode.TRICK -> if (!Table.gap) Table.useGap()
            GameMode.BUMPER -> Table.useBumper()
            else -> Table.useStandard()
        }
    }

    private var preTargets = IntArray(0)
    private var preOnEight = false
    private var preCalled = -1
    private var preCallActive = false
    private var preCue = 0
    private val preMarked = booleanArrayOf(false, false)

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
        preCue = o.preCue; preMarked[0] = o.preMarked[0]; preMarked[1] = o.preMarked[1]
        for (p in 0..1) { opening[p] = o.opening[p]?.copyOf(); openIds[p] = o.openIds[p] }
        trick.copyFrom(o.trick)
        circuit.copyFrom(o.circuit)
        pockets.copyFrom(o.pockets)
        cut.copyFrom(o.cut)
    }

    // "You win" / "CPU wins" — the player named "You" takes the plural verb form.
    private fun you(p: Int) = names[p] == "You"
    fun winsText(p: Int) = if (you(p)) "You win!" else "${names[p]} wins!"
    private fun has(p: Int) = if (you(p)) "You have" else "${names[p]} has"
    private fun continues(p: Int) = if (you(p)) "You continue" else "${names[p]} continues"

    fun ballIds(): List<Int> = when (mode) {
        GameMode.EIGHT_BALL, GameMode.CIRCUIT, GameMode.POCKET_WARS, GameMode.CUTTHROAT -> IDS_8
        GameMode.NINE_BALL -> IDS_9
        GameMode.TRICK -> Trick.LEVELS[trick.level].ids
        GameMode.BUMPER -> IDS_BUMPER
    }

    // ------------------------------------------------------------------ setup

    fun rack(rng: Random, breaker: Int) {
        if (mode == GameMode.TRICK) { startTrick(breaker); return }
        if (mode == GameMode.BUMPER) { startBumper(rng); return }
        if (mode == GameMode.CIRCUIT) circuit.deal(rng)
        pockets.reset()
        cut.reset()
        rackBalls(rng)
        val c = world.balls[0]
        c.x = P.HEAD_X; c.y = P.FOOT_Y; c.onTable = true
        current = breaker
        groups[0] = Group.OPEN; groups[1] = Group.OPEN
        ballInHand = true; kitchenOnly = true; isBreak = true
        winner = -1
        consecutiveFouls[0] = 0; consecutiveFouls[1] = 0
        message = "${names[breaker]} to break"
    }

    /** Racks the object balls on the foot spot (8-ball's and Circuit Pool's triangle, 9-ball's diamond) and takes the cue ball off. */
    private fun rackBalls(rng: Random) {
        for (b in world.balls) { b.onTable = false; b.stop() }
        val r = P.R + 0.0002
        val dx = sqrt(3.0) * r
        val positions = ArrayList<Pair<Double, Double>>()
        val order: List<Int>
        if (mode == GameMode.CIRCUIT || mode == GameMode.POCKET_WARS) {
            // Circuit Pool and Pocket Wars: the full triangle, every ball anywhere.
            for (row in 0..4) for (k in 0..row)
                positions += (P.FOOT_X + row * dx) to (P.FOOT_Y + (k - row / 2.0) * 2 * r)
            order = (1..15).shuffled(rng)
        } else if (mode == GameMode.CUTTHROAT) {
            // Cutthroat: the 1 on the foot spot, the 6 and 11 on the back corners, the rest anywhere.
            for (row in 0..4) for (k in 0..row)
                positions += (P.FOOT_X + row * dx) to (P.FOOT_Y + (k - row / 2.0) * 2 * r)
            val slots = IntArray(15)
            slots[0] = 1
            val six = rng.nextBoolean()
            slots[10] = if (six) 6 else 11
            slots[14] = if (six) 11 else 6
            val rest = (2..15).filter { it != 6 && it != 11 }.shuffled(rng).toMutableList()
            for (i in 0 until 15) if (slots[i] == 0) slots[i] = rest.removeAt(0)
            order = slots.toList()
        } else if (mode == GameMode.EIGHT_BALL) {
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
        // Bumper pool: your ball may hit anything first.
        if (mode == GameMode.BUMPER) return IDS_BUMPER.filter { it != world.cue && world.balls[it].onTable }.toIntArray()
        // Circuit Pool: any ball may be hit first.
        if (mode == GameMode.CIRCUIT || mode == GameMode.POCKET_WARS || mode == GameMode.CUTTHROAT)
            return (1..15).filter { world.balls[it].onTable }.toIntArray()
        if (mode == GameMode.NINE_BALL) return intArrayOf(lowestBall())
        if (isBreak) return (1..15).filter { world.balls[it].onTable }.toIntArray()
        val g = groups[player]
        if (g == Group.OPEN) return (1..15).filter { it != 8 && world.balls[it].onTable }.toIntArray()
        if (remaining(g) == 0) return intArrayOf(8)
        return (1..15).filter { groupOf(it) == g && world.balls[it].onTable }.toIntArray()
    }

    fun canPlaceCue(x: Double, y: Double): Boolean {
        if (mode == GameMode.TRICK) {
            val lv = Trick.LEVELS[trick.level]
            return if (lv.zones.isEmpty()) x <= Table.GAP_X0 - 0.61 * P.R && world.isFree(x, y, 0) else Trick.zoneAt(lv, x, y) >= 0
        }
        if (kitchenOnly && x > P.HEAD_X) return false
        return world.isFree(x, y, 0)
    }

    fun placeCue(x: Double, y: Double): Boolean {
        if (mode == GameMode.TRICK) return Trick.place(Trick.LEVELS[trick.level], world, x, y)
        if (!canPlaceCue(x, y)) return false
        val c = world.balls[0]
        c.x = x; c.y = y; c.onTable = true; c.stop()
        return true
    }

    // ------------------------------------------------------------------ shooting

    /** Whether the shooter must call a pocket before this shot. */
    fun callRequired(): Boolean = !isBreak && mode != GameMode.TRICK && mode != GameMode.BUMPER && mode != GameMode.CIRCUIT &&
        mode != GameMode.POCKET_WARS && mode != GameMode.CUTTHROAT && when (callMode) {
        CallMode.OFF -> false
        CallMode.MONEY_BALL -> if (mode == GameMode.EIGHT_BALL) onEight(current) else lowestBall() == 9
        CallMode.EVERY_SHOT -> true
    }

    /** Calling is allowed whenever the rule is on (e.g. to call a 9-ball combo). */
    fun callAllowed(): Boolean = callMode != CallMode.OFF && !isBreak && mode != GameMode.TRICK && mode != GameMode.BUMPER &&
        mode != GameMode.CIRCUIT && mode != GameMode.POCKET_WARS && mode != GameMode.CUTTHROAT

    fun beginShot() {
        preTargets = legalTargets(current)
        preOnEight = onEight(current)
        preCallActive = callAllowed()
        preCalled = if (preCallActive) calledPocket else -1
        calledPocket = -1
        preCue = world.cue
        if (mode == GameMode.BUMPER) for (p in 0..1) preMarked[p] = world.balls[markedBall(p)].onTable
    }

    fun shoot(angle: Double, speed: Double, side: Double, vert: Double, elevation: Double = 0.0) {
        beginShot()
        ballInHand = false
        kitchenOnly = false
        world.strike(angle, speed, side, vert, elevation)
    }

    /** The shot clock ran out: a foul, opponent gets ball in hand. */
    fun timeFoul(): ShotOutcome {
        if (mode == GameMode.TRICK) return trickAttempt(false, "the shot clock ran out")
        if (mode == GameMode.BUMPER) return bumperTimeFoul()
        if (mode == GameMode.CIRCUIT) return circuitTimeFoul()
        if (mode == GameMode.CUTTHROAT) return cutthroatTimeFoul()
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
        if (mode == GameMode.BUMPER) return resolveBumper()
        if (mode == GameMode.CIRCUIT) return resolveCircuit()
        if (mode == GameMode.POCKET_WARS) return resolvePocketWars()
        if (mode == GameMode.CUTTHROAT) return resolveCutthroat()
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
            // WPA: digging under the cue ball to lift it is not a legal jump (an elevated cue that
            // bounces it off the slate is).
            log.scooped -> { foul = true; reason = "Scoop shot — the cue ball was dug under and lifted" }
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

    /** The table for the current level (some levels have a hole cut in the cloth). */
    fun applyTrickLayout() { val lv = Trick.LEVELS[trick.level]; Table.useGap(lv.holes, lv.farCorners) }

    /** Puts the balls where the current level has them, with the cue ball in hand in the break area. */
    fun setupTrickLevel() {
        val lv = Trick.LEVELS[trick.level]
        applyTrickLayout()
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

    /**
     * Trick shots: after each attempt the balls go back to the level's layout at the start of the next turn.
     * Bumper pool: the player starts with a ball picked for them (their marked ball while it's still up).
     */
    fun prepareTurn() {
        if (mode == GameMode.TRICK && trick.resetPending && winner < 0) setupTrickLevel()
        if (mode == GameMode.BUMPER && winner < 0) world.cue = defaultBall(current)
    }

    /** Trick-shot practice: judge the attempt, nothing else (the layout resets for the next one). */
    fun resolveTrickPractice(): ShotOutcome {
        val log = world.log
        val j = Trick.judge(Trick.LEVELS[trick.level], log)
        val cueLost = 0 in log.pocketed || 0 in log.jumpedOff
        trick.resetPending = true
        ballInHand = true; kitchenOnly = true
        message = when {
            !j.made -> "Missed: ${j.why}"
            j.bonusText.isNotEmpty() -> "Made it! ${j.bonusText}"
            else -> "Made it!"
        }
        return ShotOutcome(!j.made, j.why, false, -1, message, !j.made && !cueLost && log.firstHit < 0, j.made)
    }

    private fun resolveTrick(): ShotOutcome {
        val log = world.log
        val j = Trick.judge(Trick.LEVELS[trick.level], log)
        val cueLost = 0 in log.pocketed || 0 in log.jumpedOff
        return trickAttempt(j.made, j.why, j.bonus, j.bonusText, !j.made && !cueLost && log.firstHit < 0)
    }

    /**
     * One attempt at the level. Players take turns until each has made it (or used up their
     * attempts, or can't catch up any more). Score = attempts minus bonuses; the lower score wins
     * the level, and a tied score goes to whoever earned more bonus. Most levels won takes the
     * match; a tie goes to sudden death.
     */
    private fun trickAttempt(made: Boolean, why: String, bonus: Int = 0, bonusText: String = "", heckle: Boolean = false): ShotOutcome {
        val t = trick
        val lv = Trick.LEVELS[t.level]
        val p = current
        val q = 1 - p
        t.tries[p]++
        if (made && t.made[p] == 0) { t.made[p] = t.tries[p]; t.bonus[p] = bonus }
        t.resetPending = true
        val notes = ArrayList<String>()
        notes += when {
            !made -> "Missed: $why"
            bonusText.isNotEmpty() -> (if (you(p)) "You made it! " else "${names[p]} made it! ") + bonusText
            else -> if (you(p)) "You made it!" else "${names[p]} made it!"
        }
        // Who is finished with this level?
        for (k in 0..1) {
            val o = 1 - k
            t.done[k] = t.made[k] > 0 || t.tries[k] >= Trick.MAX_TRIES ||
                (t.made[o] > 0 && t.tries[k] + 1 - lv.maxBonus > t.score(o))
        }
        var win = -1
        if (t.done[0] && t.done[1]) {
            val a = t.score(0); val b = t.score(1)
            val levelResult = when {
                t.made[0] == 0 && t.made[1] == 0 -> -1
                a != b -> if (a < b) 0 else 1
                t.bonus[0] != t.bonus[1] -> if (t.bonus[0] > t.bonus[1]) 0 else 1   // tie goes to the runner
                else -> -1
            }
            t.results += levelResult
            if (levelResult >= 0) t.levelWins[levelResult]++
            notes += when {
                levelResult >= 0 && a == b -> (if (you(levelResult)) "You win the level" else "${names[levelResult]} wins the level") + " on the bonus"
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
                t.clearLevel()
                t.starter = 1 - t.starter
                current = t.starter
                notes += "Level ${t.levelNo + 1} · ${Trick.LEVELS[t.level].name} · ${names[current]} first"
            }
        } else {
            current = if (!t.done[q]) q else p
            notes += if (you(current)) "your turn" else "${names[current]}'s turn"
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

    // ------------------------------------------------------------------ bumper pool

    /** Bumper pool: player 0 has the red balls 1-5, player 1 the white balls 6-10. 1 and 6 are the marked balls. */
    fun ownerOf(id: Int) = when (id) { in 1..5 -> 0; in 6..10 -> 1; else -> -1 }
    fun markedBall(p: Int) = if (p == 0) 1 else 6
    fun bumperBalls(p: Int): IntRange = if (p == 0) 1..5 else 6..10
    /** The cup each player shoots into (an index into Table.pockets): red the right-hand one, white the left. */
    fun targetCup(p: Int) = if (p == 0) 1 else 0
    /** The cup at a player's own end: sinking your own ball in it is a foul. */
    fun homeCup(p: Int) = 1 - targetCup(p)
    fun ballsLeft(p: Int) = bumperBalls(p).count { world.balls[it].onTable }
    fun colorName(p: Int) = if (p == 0) "red" else "white"

    /** Where a player's opening ball sits: just in front of their own cup, toward the middle. */
    private fun openingSpot(p: Int): DoubleArray {
        val home = Table.pockets[homeCup(p)]
        val dir = if (home.cx < P.L / 2) 1.0 else -1.0
        return doubleArrayOf(home.cx + dir * 0.13, home.cy)
    }

    /** The ball a player plays the opening with: the one on their opening spot (at first, the marked ball). */
    fun openingBall(p: Int): Int {
        val s = openingSpot(p)
        return bumperBalls(p).filter { world.balls[it].onTable }
            .minByOrNull { hypot(world.balls[it].x - s[0], world.balls[it].y - s[1]) } ?: -1
    }

    /** Balls player [p] may shoot: the marked ball until it's down, then any of theirs (in the opening, the opening ball). */
    fun shootable(p: Int = current): List<Int> {
        if (mode != GameMode.BUMPER) return listOf(0)
        if (isBreak) { val b = openingBall(p); return if (b >= 0) listOf(b) else emptyList() }
        val m = markedBall(p)
        if (world.balls[m].onTable) return listOf(m)
        return bumperBalls(p).filter { world.balls[it].onTable }
    }

    fun canShoot(id: Int) = mode == GameMode.BUMPER && id in shootable()

    /** Bumper pool: shoot this ball (one of the current player's). False if they can't. */
    fun selectBall(id: Int): Boolean {
        if (!canShoot(id)) return false
        world.cue = id
        return true
    }

    /** The ball a player starts their turn with: of the ones they may shoot, the nearest their cup. */
    private fun defaultBall(p: Int): Int {
        val cup = Table.pockets[targetCup(p)]
        return shootable(p).minByOrNull { hypot(world.balls[it].x - cup.cx, world.balls[it].y - cup.cy) } ?: world.cue
    }

    /**
     * The cushion on a player's right as they face the far cup, which the opening is banked off:
     * 0 the top cushion (y = 0), 1 the bottom one. Red (at the left end) has the bottom cushion.
     */
    fun openingRight(p: Int) = if (Table.pockets[homeCup(p)].cx < P.L / 2) 1 else 0

    /** One line telling the current bumper pool player (whoever's turn it is, so "your") what to do. */
    fun bumperHint(): String {
        val p = current
        return when {
            isBreak && opening[1 - p] != null -> "${names[p]}: your opening shot — ${names[1 - p]}'s is set, both go together"
            isBreak -> "${names[p]}: bank your ${if (world.balls[markedBall(p)].onTable) "marked ball" else "ball"} off the " +
                "${if (openingRight(p) == 1) "bottom" else "top"} cushion into the far cup"
            world.balls[markedBall(p)].onTable -> "${names[p]}: sink your marked ball first (the spotted one)"
            else -> "${names[p]}: tap any of your ${colorName(p)} balls to shoot it"
        }
    }

    private fun startBumper(rng: Random) {
        for (b in world.balls) { b.onTable = false; b.stop() }
        for (p in 0..1) {
            val home = Table.pockets[homeCup(p)]
            val ids = bumperBalls(p).toList()
            val spot = openingSpot(p)
            fun put(id: Int, x: Double, y: Double) {
                val b = world.balls[id]
                b.x = x; b.y = y; b.onTable = true
                randomOrientation(b, rng)
            }
            put(ids[0], spot[0], spot[1])
            // The other four in two pairs, one each side of the cup.
            var k = 1
            for (sy in doubleArrayOf(-1.0, 1.0)) for (dx in doubleArrayOf(-0.04, 0.04)) put(ids[k++], home.cx + dx, home.cy + sy * 0.2)
        }
        current = 0
        opening[0] = null; opening[1] = null
        openIds[0] = -1; openIds[1] = -1
        groups[0] = Group.OPEN; groups[1] = Group.OPEN
        ballInHand = false; kitchenOnly = false; isBreak = true
        winner = -1
        consecutiveFouls[0] = 0; consecutiveFouls[1] = 0
        world.cue = markedBall(0)
        world.log.reset()
        message = "Opening: both marked balls go at the same moment"
    }

    /** True while the bumper pool opening is still to be played. */
    fun openingPending() = mode == GameMode.BUMPER && isBreak && winner < 0

    /**
     * The current player's opening shot. It's held back until the other player has chosen theirs,
     * and then both go at once: returns true when both are in (call [fireOpening]); otherwise it's
     * the other player's turn to set theirs.
     */
    fun setOpening(angle: Double, speed: Double, side: Double, vert: Double, elevation: Double): Boolean {
        opening[current] = doubleArrayOf(angle, speed, side, vert, elevation)
        val other = 1 - current
        if (opening[other] != null) return true
        val set = if (you(current)) "Your opening shot is set" else "${names[current]}'s opening shot is set"
        current = other
        world.cue = openingBall(other).coerceAtLeast(0)
        message = "$set · " + if (you(other)) "your turn" else "over to ${names[other]}"
        return false
    }

    /** Both opening shots at once. */
    fun fireOpening() {
        beginShot()
        ballInHand = false; kitchenOnly = false
        var first = true
        for (p in 0..1) {
            val id = openingBall(p)
            openIds[p] = id
            if (id < 0) continue
            val s = opening[p] ?: DoubleArray(5)
            world.strike(s[0], s[1], s[2], s[3], s[4], id, resetLog = first)
            first = false
        }
        if (openIds[0] >= 0) world.cue = openIds[0]
    }

    /** A loaded game: an opening that was being set up starts again from the first player. */
    fun afterRestore() {
        if (mode != GameMode.BUMPER) return
        opening[0] = null; opening[1] = null
        if (isBreak) current = 0
    }

    /** A penalty: player [p] drops two of their balls into their cup by hand (the marked one first). Returns how many. */
    private fun dropTwo(p: Int): Int {
        val cup = Table.pockets[targetCup(p)]
        val ids = bumperBalls(p).filter { world.balls[it].onTable }.sortedWith(compareBy(
            { if (it == markedBall(p)) 0 else 1 },
            { -hypot(world.balls[it].x - cup.cx, world.balls[it].y - cup.cy) }))
        val n = minOf(2, ids.size)
        for (i in 0 until n) { val b = world.balls[ids[i]]; b.onTable = false; b.stop() }
        return n
    }

    /** A ball that left the table goes back in the open space in the middle of the bumpers (or as near as it can). */
    private fun respotMiddle(id: Int) {
        val b = world.balls[id]
        b.stop()
        var r = 0.0
        while (r < 0.5) {
            val n = if (r == 0.0) 1 else 16
            for (k in 0 until n) {
                val a = 2 * PI * k / n
                val x = P.L / 2 + r * cos(a); val y = P.W / 2 + r * sin(a)
                if (world.isFree(x, y, id)) { b.x = x; b.y = y; b.onTable = true; return }
            }
            r += 0.02
        }
        b.x = P.L / 2; b.y = P.W / 2; b.onTable = true
    }

    private fun bumperTimeFoul(): ShotOutcome {
        val p = current; val q = 1 - p
        val n = dropTwo(q)
        current = q
        if (ballsLeft(q) == 0) {
            winner = q
            message = "${winsText(q)} (shot clock foul: ${names[q]} dropped the last ball${if (n > 1) "s" else ""} in)"
            return ShotOutcome(true, "Shot clock", false, q, message)
        }
        message = "Foul: shot clock expired · ${names[q]} ${if (you(q)) "drop" else "drops"} $n in · ${names[q]} to shoot"
        return ShotOutcome(true, "Shot clock", false, -1, message)
    }

    /**
     * Bumper pool rules (as commonly played):
     *  - Shoot your marked ball first; one of your others going in before it is a foul.
     *  - Sinking your own ball in your own cup is a foul (and sinking your last ball there loses).
     *  - A ball of your opponent's that goes in, in either cup, counts for them, no penalty.
     *  - Knocking a ball off the table is a foul (it goes back in the middle); so is a jump shot.
     *  - After a foul your opponent drops two of their balls in by hand, and it's their turn.
     *  - Sinking one of yours in the right cup keeps your turn. First to get all five down wins.
     * The opening: both marked balls at once. Both in: go again with another ball each. One in: that
     * player shoots first. Neither: whoever finished nearer their cup.
     */
    private fun resolveBumper(): ShotOutcome {
        val log = world.log
        val isOpening = isBreak
        val shooting = BooleanArray(2) { isOpening || it == current }
        val struck = IntArray(2) { if (isOpening) openIds[it] else if (it == current) preCue else -1 }
        val foulBy = BooleanArray(2)
        val reason = arrayOf("", "")
        fun foul(p: Int, why: String) { if (!foulBy[p]) { foulBy[p] = true; reason[p] = why } }
        val good = BooleanArray(2)       // sank one of their own in the right cup
        val lastWrong = BooleanArray(2)  // their latest ball down (by their own shot) went in their own cup
        val markedUp = booleanArrayOf(preMarked[0], preMarked[1])
        val notes = ArrayList<String>()

        for (id in log.pocketed) {
            val o = ownerOf(id)
            if (o < 0) continue
            if (!shooting[o]) continue   // knocked in by the opponent: it counts for its owner
            if (log.pocketIn[id] != targetCup(o)) { foul(o, "own ball in own cup"); lastWrong[o] = true; continue }
            lastWrong[o] = false
            if (id != markedBall(o) && markedUp[o]) { foul(o, "a ball went in before the marked ball"); continue }
            if (id == markedBall(o)) markedUp[o] = false
            good[o] = true
        }
        for (id in log.jumpedOff) {
            val o = ownerOf(id)
            if (o < 0) continue
            foul(if (isOpening) o else current, "a ball left the table")
            respotMiddle(id)
        }
        for (p in 0..1) {
            val id = struck[p]
            if (shooting[p] && id >= 0 && (log.air and (1 shl id)) != 0) foul(p, "no jump shots in bumper pool")
        }
        for (p in 0..1) if (foulBy[p]) {
            val q = 1 - p
            val n = dropTwo(q)
            notes += "Foul: ${reason[p]} · ${names[q]} ${if (you(q)) "drop" else "drops"} ${if (n == 1) "one" else "two"} in"
        }

        // Out of balls: your last one in your own cup loses; otherwise getting all five down wins.
        var win = -1
        for (p in 0..1) if (shooting[p] && ballsLeft(p) == 0 && lastWrong[p]) {
            win = 1 - p
            notes += "${names[p]} sank the last ball in the wrong cup"
        }
        if (win < 0) {
            val a = ballsLeft(0) == 0; val b = ballsLeft(1) == 0
            if (a && b) {
                // Both down to nothing at once (only really possible off the opening): play it again.
                val rng = Random(log.pocketed.size * 31 + 7)
                startBumper(rng)
                message = "Both players got all five down — it's a draw · re-racked"
                return ShotOutcome(false, "", false, -1, message)
            }
            if (a) win = 0 else if (b) win = 1
        }
        val anyFoul = foulBy[0] || foulBy[1]
        val why = reason.firstOrNull { it.isNotEmpty() } ?: ""
        if (win >= 0) {
            isBreak = false
            winner = win
            message = winsText(win) + if (notes.isNotEmpty()) " (${notes.joinToString(", ")})" else ""
            return ShotOutcome(anyFoul, why, false, win, message)
        }

        var keep = false
        if (isOpening) {
            isBreak = false
            opening[0] = null; opening[1] = null
            var again = false
            current = when {
                foulBy[0] != foulBy[1] -> if (foulBy[0]) 1 else 0
                good[0] && good[1] && !anyFoul -> { again = true; 0 }
                good[0] != good[1] -> if (good[0]) 0 else 1
                else -> {
                    // Neither (or both fouled): whoever's opening ball finished nearer their cup.
                    val d = DoubleArray(2) { p ->
                        val id = struck[p]
                        val b = if (id >= 0) world.balls[id] else null
                        val cup = Table.pockets[targetCup(p)]
                        if (b == null || !b.onTable) Double.MAX_VALUE else hypot(b.x - cup.cx, b.y - cup.cy)
                    }
                    if (d[1] < d[0]) 1 else 0
                }
            }
            if (again) {
                // Both made it: each puts another ball on their opening spot and they go again.
                isBreak = true
                for (p in 0..1) placeOpeningBall(p)
                notes += "Both made it! Another opening shot each"
            } else if (good[0] || good[1]) {
                notes += "${names[current]} ${if (you(current)) "go" else "goes"} first"
            } else if (!anyFoul) {
                notes += "Neither went in · ${names[current]} ${if (you(current)) "were" else "was"} nearer and ${if (you(current)) "go" else "goes"} first"
            } else notes += "${names[current]} to shoot"
        } else {
            keep = !foulBy[current] && good[current]
            if (!keep) current = 1 - current
            notes += if (keep) continues(current) else "${names[current]} to shoot"
        }
        message = notes.joinToString(" · ")
        return ShotOutcome(anyFoul, why, keep, -1, message)
    }

    /** Puts one of the player's balls on their opening spot for another opening shot. */
    private fun placeOpeningBall(p: Int) {
        val id = bumperBalls(p).firstOrNull { world.balls[it].onTable } ?: return
        val s = openingSpot(p)
        val b = world.balls[id]
        b.stop()
        if (world.isFree(s[0], s[1], id)) { b.x = s[0]; b.y = s[1]; return }
        // Something's on the spot: as near to it as there's room.
        var r = 0.02
        while (r < 0.3) {
            for (k in 0 until 12) {
                val a = 2 * PI * k / 12
                val x = s[0] + r * cos(a); val y = s[1] + r * sin(a)
                if (world.isFree(x, y, id)) { b.x = x; b.y = y; return }
            }
            r += 0.02
        }
    }

    // ------------------------------------------------------------------ circuit pool

    /** The player whose secret target ball [id] is, or -1 (a neutral ball, dealt to nobody). */
    fun targetOwner(id: Int): Int = when (id) {
        in circuit.targets[0] -> 0
        in circuit.targets[1] -> 1
        else -> -1
    }

    /** How many of player [p]'s targets are down. (Public: both players see each other's count.) */
    fun circuitSunk(p: Int): Int = circuit.targets[p].count { it in 1..15 && !world.balls[it].onTable }

    /** The object balls in the pockets, as a bit mask (bit n = ball n). */
    fun downMask(): Int {
        var m = 0
        for (id in 1..15) if (!world.balls[id].onTable) m = m or (1 shl id)
        return m
    }

    /** Circuit Pool's fouls (the same for the real game and for the computer looking ahead): a reason, or "" for none. */
    fun circuitFoul(log: ShotLog): String = when {
        0 in log.pocketed -> "Scratch"
        0 in log.jumpedOff -> "Cue ball jumped off the table"
        log.scooped -> "Scoop shot — the cue ball was dug under and lifted"
        log.firstHit < 0 -> "No ball hit"
        log.jumpedOff.isNotEmpty() -> "Ball jumped off the table"
        else -> ""
    }

    /** Numbered balls that touched a cushion this shot (a legal break needs four, if nothing went in). */
    fun railedObjectBalls(log: ShotLog): Int = (1..15).count { (log.railed and (1 shl it)) != 0 }

    private fun circuitTimeFoul(): ShotOutcome {
        val opp = 1 - current
        current = opp
        ballInHand = true
        if (!world.balls[0].onTable) restoreCue()
        message = if (isBreak) {
            // Nothing has been hit yet: the opponent breaks instead.
            kitchenOnly = true
            "Foul: shot clock expired · ${names[opp]} to break"
        } else {
            kitchenOnly = false
            "Foul: shot clock expired · ${has(opp)} ball in hand"
        }
        return ShotOutcome(true, "Shot clock", false, -1, message)
    }

    /**
     * Circuit Pool (from the rules sheet):
     *  - Each player has three secret target balls; the other nine are neutral. Any ball may be hit first, any order.
     *  - Pocketed balls stay down. Your target counts for you; the opponent's counts for them and ends your turn;
     *    a neutral ball keeps your turn going (so does your own target).
     *  - Fouls (scratch, cue ball off the table, scoop, no ball hit, an object ball off the table — it's spotted on
     *    the foot spot) give the opponent ball in hand anywhere. Balls sunk on a foul still count for their owner.
     *  - Break: legal if a ball goes in or four numbered balls reach a cushion. On a legal break any ball that goes
     *    in keeps the breaker shooting. Nothing in: the opponent shoots from where the cue ball stopped. A foul on
     *    the break: ball in hand behind the head string. An illegal break is re-racked and the opponent breaks.
     *  - Three down wins at once. If one shot finishes both players, the shooter wins — unless it was a foul.
     */
    private fun resolveCircuit(): ShotOutcome {
        val log = world.log
        val shooter = current
        val opp = 1 - shooter
        val brk = isBreak
        val objects = log.pocketed.filter { it != 0 }
        val reason = circuitFoul(log)
        val foul = reason.isNotEmpty()
        val railed = railedObjectBalls(log)
        for (id in log.jumpedOff) if (id != 0) respot(id)

        // Illegal break (no foul, nothing in, fewer than four balls to a cushion): rack again, the other player breaks.
        if (brk && !foul && objects.isEmpty() && railed < 4) {
            rackBalls(Random(log.railed + 17 * circuit.targets[0][0]))
            val c = world.balls[0]
            c.x = P.HEAD_X; c.y = P.FOOT_Y; c.onTable = true
            current = opp
            ballInHand = true; kitchenOnly = true; isBreak = true
            message = "Illegal break: only $railed ${if (railed == 1) "ball" else "balls"} reached a cushion (4 needed) · " +
                "re-racked · ${names[opp]} to break"
            return ShotOutcome(false, "Illegal break", false, -1, message)
        }

        val mine = objects.count { targetOwner(it) == shooter }
        val theirs = objects.count { targetOwner(it) == opp }
        val sDone = circuitSunk(shooter) >= 3
        val oDone = circuitSunk(opp) >= 3
        val win = when {
            sDone && oDone -> if (foul) opp else shooter   // a double drop: the shooter, unless it was a foul
            sDone -> shooter
            oDone -> opp
            else -> -1
        }
        circuit.obs += intArrayOf(downMask(), circuitSunk(0), circuitSunk(1))
        isBreak = false

        val notes = ArrayList<String>()
        if (mine > 0) notes += (if (you(shooter)) "You sank " else "${names[shooter]} sank ") +
            (if (mine == 1) "a target" else "$mine targets") + " (${circuitSunk(shooter)}/3)"
        if (theirs > 0) notes += (if (you(opp)) "that was " + (if (theirs == 1) "one of your targets" else "$theirs of your targets")
            else (if (theirs == 1) "one of ${names[opp]}'s targets" else "$theirs of ${names[opp]}'s targets") + " went down") +
            " (${circuitSunk(opp)}/3)"
        val neutral = objects.size - mine - theirs
        if (neutral > 0 && mine == 0 && theirs == 0 && !foul) notes += if (neutral == 1) "a neutral ball" else "$neutral neutral balls"

        winner = win
        if (win >= 0) {
            if (sDone && oDone) notes += if (foul) "both finished on a foul" else "both finished on the same shot"
            message = winsText(win) + if (notes.isNotEmpty()) " (${notes.joinToString(", ")})" else ""
            return ShotOutcome(foul, reason, false, win, message)
        }

        var keep = false
        if (foul) {
            current = opp
            ballInHand = true
            kitchenOnly = brk
            if (!world.balls[0].onTable) restoreCue()
            notes.add(0, "Foul: $reason")
            notes += "${has(opp)} ball in hand" + if (brk) " behind the line" else ""
        } else if (theirs > 0 && !brk) {
            current = opp
            notes += "${names[opp]} to shoot"
        } else if (objects.isNotEmpty()) {
            keep = true
            notes += continues(shooter)
        } else {
            current = opp
            notes += "${names[opp]} to shoot"
        }
        message = notes.joinToString(" · ")
        return ShotOutcome(foul, reason, keep, -1, message, heckle = foul && log.firstHit < 0 && reason == "No ball hit")
    }

    // ------------------------------------------------------------------ pocket wars

    /** A pocket's name as players would say it (top = the rail at the top of the screen). */
    fun pocketName(i: Int) = POCKET_NAMES.getOrElse(i) { "pocket" }

    private fun poss(p: Int) = if (you(p)) "your" else "${names[p]}'s"

    /** Pocket Wars' fouls (shared with the computer's look-ahead): a reason, or "" for none. */
    fun pocketWarsFoul(log: ShotLog, brk: Boolean): String {
        val objects = log.pocketed.any { it != 0 }
        return when {
            0 in log.pocketed -> "Scratch"
            0 in log.jumpedOff -> "Cue ball jumped off the table"
            log.scooped -> "Scoop shot — the cue ball was dug under and lifted"
            log.firstHit < 0 -> "No ball hit"
            log.jumpedOff.isNotEmpty() -> "Ball jumped off the table"
            !brk && !objects && !log.railAfterHit -> "No rail after contact"
            else -> ""
        }
    }

    /**
     * Pocket Wars:
     *  - Every ball is worth its number. All six pockets start open; any ball may be hit first.
     *  - A ball in an open pocket claims it for you and scores; in your own pocket it scores. Either way you keep shooting.
     *  - A ball in your opponent's pocket scores for them and ends your turn — unless it hit a rail (not just the
     *    jaws) before dropping: then you steal the pocket, score the ball and keep shooting.
     *  - The break scores for the breaker but claims nothing; anything in keeps the breaker shooting.
     *  - Fouls (scratch, cue ball off, scoop, no ball hit, an object ball off the table, no rail after contact):
     *    balls sunk on that shot are spotted back, and the opponent gets ball in hand anywhere. Scratching into one
     *    of your own pockets also gives it up (it's open again).
     *  - First to 61 wins. If the table empties at 60–60, more pockets wins; still level, whoever sank the last ball.
     */
    private fun resolvePocketWars(): ShotOutcome {
        val log = world.log
        val pw = pockets
        val shooter = current
        val opp = 1 - shooter
        val brk = isBreak
        val objects = log.pocketed.filter { it != 0 }
        val reason = pocketWarsFoul(log, brk)
        val foul = reason.isNotEmpty()
        for (id in log.jumpedOff) if (id != 0) respot(id)
        val notes = ArrayList<String>()

        // Scratching into one of your own pockets gives it up.
        if (0 in log.pocketed) {
            val pk = log.pocketIn[0]
            if (pk in pw.owner.indices && pw.owner[pk] == shooter) {
                pw.owner[pk] = -1
                notes += "the ${pocketName(pk)} is open again"
            }
        }
        var gift = false
        var scored = 0
        if (foul) {
            for (id in objects) respot(id)
            if (objects.isNotEmpty()) notes += if (objects.size == 1) "the ${objects[0]} is spotted" else "${objects.size} balls spotted"
        } else for (id in objects) {
            val pk = log.pocketIn[id]
            val o = if (pk in pw.owner.indices) pw.owner[pk] else -1
            when {
                brk -> scored += id
                o == -1 -> { pw.owner[pk] = shooter; scored += id; notes += (if (you(shooter)) "you claim" else "${names[shooter]} claims") + " the ${pocketName(pk)}" }
                o == shooter -> scored += id
                (log.banked and (1 shl id)) != 0 -> {
                    pw.owner[pk] = shooter; scored += id
                    notes += (if (you(shooter)) "you steal" else "${names[shooter]} steals") + " the ${pocketName(pk)}!"
                }
                else -> { pw.score[opp] += id; gift = true; notes += "the $id went in ${poss(opp)} pocket (+$id to ${if (you(opp)) "you" else names[opp]})" }
            }
        }
        pw.score[shooter] += scored
        if (scored > 0) notes.add(0, "+$scored")
        isBreak = false

        val left = (1..15).count { world.balls[it].onTable }
        val win = when {
            pw.score[shooter] >= PocketWarsState.WIN -> shooter
            pw.score[opp] >= PocketWarsState.WIN -> opp
            left == 0 -> when {
                pw.score[0] != pw.score[1] -> if (pw.score[0] > pw.score[1]) 0 else 1
                pw.pocketsOf(0) != pw.pocketsOf(1) -> if (pw.pocketsOf(0) > pw.pocketsOf(1)) 0 else 1
                else -> shooter
            }
            else -> -1
        }
        winner = win
        val tally = "${pw.score[0]}–${pw.score[1]}"
        if (win >= 0) {
            message = "${winsText(win)} ($tally" + (if (notes.isNotEmpty()) ", ${notes.joinToString(", ")}" else "") + ")"
            return ShotOutcome(foul, reason, false, win, message)
        }
        var keep = false
        if (foul) {
            current = opp
            ballInHand = true
            kitchenOnly = false
            if (!world.balls[0].onTable) restoreCue()
            notes.add(0, "Foul: $reason")
            notes += "${has(opp)} ball in hand"
        } else if (gift) {
            current = opp
            notes += "${names[opp]} to shoot"
        } else if (objects.isNotEmpty()) {
            keep = true
            notes += continues(shooter)
        } else {
            current = opp
            notes += "${names[opp]} to shoot"
        }
        message = notes.joinToString(" · ")
        val heckle = foul && (reason == "No ball hit" || reason == "No rail after contact")
        return ShotOutcome(foul, reason, keep, -1, message, heckle)
    }

    // ------------------------------------------------------------------ cutthroat

    /** Cutthroat: player [p]'s group (0 = 1–5, 1 = 6–10, 2 = 11–15), or -1 while the table is open for them. */
    fun cutGroup(p: Int) = cut.group[p]

    /** Cutthroat: how many of player [p]'s balls are still on the table (5 while they haven't got a group). */
    fun cutLeft(p: Int): Int {
        val g = cut.group[p]
        return if (g < 0) 5 else CutthroatState.ballsOf(g).count { world.balls[it].onTable }
    }

    /** Cutthroat: player [p] has a group and none of it is left — they're out (until a foul puts one back). */
    fun cutOut(p: Int) = cut.group[p] >= 0 && cutLeft(p) == 0

    /** Cutthroat: the player whose group ball [id] is in, or -1 (the cue ball, or a group nobody has yet). */
    fun cutOwner(id: Int) = cut.ownerOfGroup(CutthroatState.groupOf(id))

    /** Cutthroat: the next player after [p] (in turn order) who is still in. */
    fun cutNext(p: Int): Int {
        for (k in 1..3) { val q = (p + k) % 3; if (!cutOut(q)) return q }
        return p
    }

    /** Cutthroat's fouls (shared with the computer's look-ahead): a reason, or "" for none. */
    fun cutthroatFoul(log: ShotLog): String = when {
        0 in log.pocketed -> "Scratch"
        0 in log.jumpedOff -> "Cue ball jumped off the table"
        log.scooped -> "Scoop shot — the cue ball was dug under and lifted"
        log.firstHit < 0 -> "No ball hit"
        log.jumpedOff.isNotEmpty() -> "Ball jumped off the table"
        log.pocketed.none { it != 0 } && !log.railAfterHit -> "No rail after contact"
        else -> ""
    }

    /**
     * The penalty for a foul: each of the shooter's opponents who has a group gets one of their pocketed
     * balls back (the lowest), spotted on the foot spot. A player who was out is back in.
     */
    private fun cutPenalty(shooter: Int): List<String> {
        val notes = ArrayList<String>()
        for (k in 1..2) {
            val q = (shooter + k) % 3
            val g = cut.group[q]
            if (g < 0) continue
            val id = CutthroatState.ballsOf(g).firstOrNull { !world.balls[it].onTable } ?: continue
            respot(id)
            notes += if (you(q)) "your $id comes back" else "${names[q]}'s $id comes back"
        }
        return notes
    }

    /** Cutthroat, open table: the group the computer picks — of those nobody has, the one with most balls left. */
    fun cutSuggestedGroup(): Int = cut.free().maxWithOrNull(compareBy<Int> { g ->
        CutthroatState.ballsOf(g).count { world.balls[it].onTable } }.thenByDescending { it }) ?: 0

    /**
     * Cutthroat: the player who just sank a ball on an open table ([CutthroatState.choosing]) takes group [g].
     * When that makes two players with a group, the third gets the one that's left. Then the turn carries on.
     */
    fun cutPick(g: Int): ShotOutcome {
        val p = cut.choosing
        require(p >= 0 && g in cut.free())
        val inBefore = BooleanArray(3) { !cutOut(it) }
        cut.group[p] = g
        cut.choosing = -1
        val notes = ArrayList<String>()
        notes += (if (you(p)) "You take " else "${names[p]} takes ") + CutthroatState.groupName(g)
        if (cut.group.count { it < 0 } == 1) {
            val q = cut.group.indexOf(-1)
            val last = cut.free().first()
            cut.group[q] = last
            notes += (if (you(q)) "you get " else "${names[q]} gets ") + CutthroatState.groupName(last)
        }
        return cutFinish(p, false, "", !cutOut(p), inBefore, notes)
    }

    private fun cutthroatTimeFoul(): ShotOutcome {
        val shooter = current
        isBreak = false
        val notes = arrayListOf("Foul: shot clock expired")
        notes += cutPenalty(shooter)
        val next = cutNext(shooter)
        current = next
        ballInHand = true
        kitchenOnly = false
        if (!world.balls[0].onTable) restoreCue()
        notes += "${has(next)} ball in hand"
        message = notes.joinToString(" · ")
        return ShotOutcome(true, "Shot clock", false, -1, message)
    }

    /**
     * Cutthroat (three players):
     *  - Groups: 1–5, 6–10, 11–15. The table is open after the break; the first player to sink a ball on a legal
     *    shot picks any group ([cutPick]), the next picks one of the other two, and the third gets the last.
     *  - Any ball may be hit first. Any ball down on a legal shot keeps you shooting — your own too, which is
     *    "cutting your own throat". Balls stay down.
     *  - Fouls (scratch, cue ball off the table, scoop, no ball hit, an object ball off the table — it's
     *    spotted — or no rail after contact): the next player has ball in hand anywhere, and each of the
     *    fouler's opponents gets one of their pocketed balls back on the table.
     *  - A player with none of their group left is out (a foul can bring them back). The last player with a
     *    ball on the table wins. If one shot clears everyone, the player whose last ball dropped last wins.
     *  - Turns go in order, Player 1 → 2 → 3, skipping anyone who's out.
     */
    private fun resolveCutthroat(): ShotOutcome {
        val log = world.log
        val shooter = current
        val brk = isBreak
        val objects = log.pocketed.filter { it != 0 }
        val reason = cutthroatFoul(log)
        val foul = reason.isNotEmpty()
        for (id in log.jumpedOff) if (id != 0) respot(id)
        val preGroup = cut.group[shooter]
        // Who was still in before this shot (their balls sunk now were on the table then).
        val inBefore = BooleanArray(3) { p ->
            val g = cut.group[p]
            g < 0 || cutLeft(p) + objects.count { CutthroatState.groupOf(it) == g } > 0
        }
        val notes = ArrayList<String>()
        val own = objects.count { preGroup >= 0 && CutthroatState.groupOf(it) == preGroup }
        if (!foul && own > 0 && own == objects.size) notes += if (you(shooter)) "that was your own" else "${names[shooter]} sank their own"
        // Open table: the first legal pot lets the shooter pick a group.
        if (!foul && !brk && objects.isNotEmpty() && preGroup < 0) cut.choosing = shooter
        if (foul) notes += cutPenalty(shooter)
        isBreak = false
        // Any ball down on a legal shot keeps you at the table (even your own — that's cutting your own throat).
        val keep = !foul && objects.isNotEmpty() && (cut.choosing == shooter || !cutOut(shooter))
        return cutFinish(shooter, foul, reason, keep, inBefore, notes)
    }

    /** Cutthroat, after a shot (or a group pick): who's out, whether that's the game, and whose turn it is. */
    private fun cutFinish(shooter: Int, foul: Boolean, reason: String, keep: Boolean, inBefore: BooleanArray,
                          notes: MutableList<String>): ShotOutcome {
        val log = world.log
        for (p in 0..2) if (inBefore[p] && cutOut(p)) notes += if (you(p)) "you're out" else "${names[p]} is out"
        val alive = (0..2).filter { !cutOut(it) }
        val win = when {
            alive.size == 1 -> alive[0]
            // Everyone cleared at once: whoever's last ball dropped last.
            alive.isEmpty() -> (0..2).filter { inBefore[it] }.maxByOrNull { p ->
                log.pocketed.indexOfLast { CutthroatState.groupOf(it) == cut.group[p] }
            } ?: shooter
            else -> -1
        }
        winner = win
        if (win >= 0) {
            cut.choosing = -1
            message = winsText(win) + if (notes.isNotEmpty()) " (${notes.joinToString(", ")})" else ""
            return ShotOutcome(foul, reason, false, win, message)
        }
        if (cut.choosing == shooter) {
            notes += if (you(shooter)) "pick your group" else "${names[shooter]} picks a group"
        } else if (foul) {
            val next = cutNext(shooter)
            current = next
            ballInHand = true
            kitchenOnly = false
            if (!world.balls[0].onTable) restoreCue()
            notes.add(0, "Foul: $reason")
            notes += "${has(next)} ball in hand"
        } else if (!keep) {
            val next = cutNext(shooter)
            current = next
            notes += "${names[next]} to shoot"
        } else {
            notes += continues(shooter)
        }
        message = notes.joinToString(" · ").replaceFirstChar { it.uppercase() }
        val heckle = foul && (reason == "No ball hit" || reason == "No rail after contact")
        return ShotOutcome(foul, reason, keep, -1, message, heckle)
    }

    private companion object {
        val POCKET_NAMES = arrayOf("top-left corner", "top-right corner", "bottom-left corner", "bottom-right corner",
            "top side pocket", "bottom side pocket")
        val IDS_8 = (1..15).toList()
        val IDS_9 = (1..9).toList()
        val IDS_BUMPER = (1..10).toList()
    }
}
