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
 * OCTAGON: bumper pool for two to four players (or two teams of two) on the octagon table: four cups, ramps for cushions.
 * GAUNTLET: the octagon's rules on the Gauntlet, a square table with ramps, a dish and loops in the middle; 6 to 8 balls each.
 */
enum class GameMode { EIGHT_BALL, NINE_BALL, TRICK, BUMPER, CIRCUIT, POCKET_WARS, CUTTHROAT, OCTAGON, GAUNTLET }
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

    /**
     * Bar rules: after a scratch (the cue ball pocketed or off the table) the next player has ball in hand
     * behind the head string ("in the kitchen") instead of anywhere, and has to shoot out of it — the cue
     * ball must cross the head string before it touches a ball, unless that ball is on or past the line.
     */
    var scratchKitchen = false

    /** Trick-shot match state (only used in GameMode.TRICK). */
    val trick = TrickState()

    /** Circuit Pool: each player's secret targets and what both players have seen (only used in GameMode.CIRCUIT). */
    val circuit = CircuitState()

    /** Pocket Wars: who owns each pocket, and the points (only used in GameMode.POCKET_WARS). */
    val pockets = PocketWarsState()

    /** Cutthroat: each player's group (only used in GameMode.CUTTHROAT, the one three-player game). */
    val cut = CutthroatState()

    /** How many players take turns: three in Cutthroat, two to four on the octagon table, two in everything else. */
    val players get() = when (mode) {
        GameMode.CUTTHROAT -> 3
        GameMode.OCTAGON, GameMode.GAUNTLET -> names.size.coerceIn(2, 4)
        else -> 2
    }

    /** The games on the regulation table with a cue ball and six pockets (not trick shots or bumper pool). */
    val poolGame get() = mode == GameMode.EIGHT_BALL || mode == GameMode.NINE_BALL || mode == GameMode.CIRCUIT ||
        mode == GameMode.POCKET_WARS || mode == GameMode.CUTTHROAT

    /** Bumper pool on either table: no cue ball, each player shoots their own five balls. */
    val bumperGame get() = mode == GameMode.BUMPER || mode == GameMode.OCTAGON || mode == GameMode.GAUNTLET

    /** The octagon's rules for two to four players: the octagon table and the Gauntlet. */
    val fourWay get() = mode == GameMode.OCTAGON || mode == GameMode.GAUNTLET

    /**
     * Balls each player has: five, except on the Gauntlet (6, 7 or 8). Set it before racking. It also tells the
     * shared colours which ball is whose.
     */
    var each = 5
        set(v) { field = v.coerceIn(5, 8); BumperColors.each = field }

    /**
     * Team play: player 0's partner (1, across, as on the octagon; on the Gauntlet 2 or 3, beside them, can be
     * picked too). The other two are the other team.
     */
    var partner = 1

    /**
     * Octagon table, four players: two teams of two, red and white against blue and yellow. Partners
     * sit across from each other, so turns go back and forth between the teams.
     */
    var teams = false

    /**
     * Bumper pool's opening: every player shoots their marked ball at the same moment. Each sets a
     * shot ([angle, speed, side, vert, elevation]) in turn; it waits here, unseen, until all are in.
     */
    val opening = arrayOfNulls<DoubleArray>(4)
    private val openIds = intArrayOf(-1, -1, -1, -1)

    init {
        // The trick-shot game uses the gap table, bumper pool its own table, everything else the regulation table.
        // (A trick match keeps whatever holes the current level has: the CPU's copies are made mid-game.)
        when (mode) {
            // (The classics are on the ordinary 9 ft table; a copy made mid-match keeps whichever table is in use.)
            GameMode.TRICK -> if (!Table.gap && !Table.trickClassic) Table.useGap()
            GameMode.BUMPER -> Table.useBumper()
            GameMode.OCTAGON -> if (!Table.octagon) Table.useOctagon()
            GameMode.GAUNTLET -> Table.useGauntlet()
            else -> Table.useStandard()
        }
        // (The other bumper tables have five balls each; the Gauntlet's count is set by whoever starts the game.)
        if (mode == GameMode.BUMPER || mode == GameMode.OCTAGON) BumperColors.each = 5
    }

    private var preTargets = IntArray(0)
    private var preOnEight = false
    private var preCalled = -1
    private var preCallActive = false
    private var preCue = 0
    /** This shot is from the kitchen after a scratch (or a foul on the break): it has to go out of it. */
    private var preShootOut = false
    /** Object balls behind the head string when the shot started (bit n = ball n). */
    private var preBehind = 0
    private val preMarked = BooleanArray(4)
    /** Octagon table: whose balls each player is shooting this turn (their own, or their partner's once theirs are all down). */
    private val prePlays = intArrayOf(0, 1, 2, 3)

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
        preCue = o.preCue
        preShootOut = o.preShootOut; preBehind = o.preBehind; scratchKitchen = o.scratchKitchen
        o.preMarked.copyInto(preMarked); o.prePlays.copyInto(prePlays)
        teams = o.teams
        if (each != o.each) each = o.each
        partner = o.partner
        for (p in 0..3) { opening[p] = o.opening[p]?.copyOf(); openIds[p] = o.openIds[p] }
        trick.copyFrom(o.trick)
        circuit.copyFrom(o.circuit)
        pockets.copyFrom(o.pockets)
        cut.copyFrom(o.cut)
    }

    // "You win" / "CPU wins" — the player named "You" takes the plural verb form.
    private fun you(p: Int) = names[p] == "You"
    fun winsText(p: Int) = when {
        teams -> "${teamName(p)} win!"
        you(p) -> "You win!"
        else -> "${names[p]} wins!"
    }
    private fun has(p: Int) = if (you(p)) "You have" else "${names[p]} has"
    private fun continues(p: Int) = if (you(p)) "You continue" else "${names[p]} continues"

    fun ballIds(): List<Int> = when (mode) {
        GameMode.EIGHT_BALL, GameMode.CIRCUIT, GameMode.POCKET_WARS, GameMode.CUTTHROAT -> IDS_8
        GameMode.NINE_BALL -> IDS_9
        GameMode.TRICK -> Trick.LEVELS[trick.level].ids
        GameMode.BUMPER -> IDS_BUMPER
        GameMode.OCTAGON, GameMode.GAUNTLET -> (1..each * players).toList()
    }

    // ------------------------------------------------------------------ setup

    fun rack(rng: Random, breaker: Int) {
        if (mode == GameMode.TRICK) { startTrick(breaker); return }
        if (mode == GameMode.BUMPER) { startBumper(rng); return }
        if (fourWay) { startOctagon(rng); return }
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
        if (bumperGame) return ballIds().filter { it != world.cue && world.balls[it].onTable }.toIntArray()
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

    /** 8-ball and 9-ball have a money ball; in the other pool games only "call every shot" means anything. */
    private val moneyBallGame get() = mode == GameMode.EIGHT_BALL || mode == GameMode.NINE_BALL

    /** Whether the shooter must call a pocket before this shot. */
    fun callRequired(): Boolean = !isBreak && poolGame && when (callMode) {
        CallMode.OFF -> false
        CallMode.MONEY_BALL -> moneyBallGame && (if (mode == GameMode.EIGHT_BALL) onEight(current) else lowestBall() == 9)
        CallMode.EVERY_SHOT -> true
    }

    /** Calling is allowed whenever the rule is on (e.g. to call a 9-ball combo). */
    fun callAllowed(): Boolean = !isBreak && poolGame &&
        (callMode == CallMode.EVERY_SHOT || (callMode == CallMode.MONEY_BALL && moneyBallGame))

    /** "Call every shot" is on for this shot: only a ball in the called pocket counts as a pot for the shooter. */
    private val everyShot get() = callMode == CallMode.EVERY_SHOT && preCallActive

    /** With "call every shot": did any of [ids] go in the called pocket? (Always true when it's off.) */
    private fun calledIn(ids: List<Int>, log: ShotLog) = !everyShot || ids.any { log.pocketIn[it] == preCalled }

    /**
     * Bar rules, shooting out of the kitchen: the first ball hit was behind the head string at the start of the
     * shot and the cue ball never crossed the line before touching it. A reason, or "" for none.
     */
    fun kitchenFoul(log: ShotLog): String {
        if (!preShootOut || log.firstHit <= 0) return ""
        val crossed = log.cueMaxX > P.HEAD_X || log.hitX > P.HEAD_X
        val behind = (preBehind and (1 shl log.firstHit)) != 0
        return if (!crossed && behind) "Didn't shoot out of the kitchen" else ""
    }

    /**
     * For the aim guide: may ball [id] be the first one hit? [crossedHead] says whether the cue ball crosses the
     * head string before reaching it (shooting out of the kitchen, a ball behind the line needs that).
     */
    fun legalFirstHit(id: Int, crossedHead: Boolean): Boolean {
        if (id !in legalTargets()) return false
        val fromKitchen = poolGame && kitchenOnly && !isBreak
        return !(fromKitchen && !crossedHead && world.balls[id].x < P.HEAD_X)
    }

    /** Ball in hand after a foul: behind the head string after a scratch under bar rules, otherwise anywhere. */
    private fun kitchenAfter(log: ShotLog) = scratchKitchen && (0 in log.pocketed || 0 in log.jumpedOff)

    fun beginShot() {
        preTargets = legalTargets(current)
        preOnEight = onEight(current)
        preCallActive = callAllowed()
        preCalled = if (preCallActive) calledPocket else -1
        calledPocket = -1
        preCue = world.cue
        preShootOut = poolGame && kitchenOnly && !isBreak
        preBehind = 0
        for (id in 1..15) if (world.balls[id].onTable && world.balls[id].x < P.HEAD_X) preBehind = preBehind or (1 shl id)
        if (bumperGame) for (p in 0 until players) { preMarked[p] = world.balls[markedBall(p)].onTable; prePlays[p] = playsFor(p) }
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
        if (fourWay) return octTimeFoul()
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
        if (fourWay) return resolveOctagon()
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
            kitchenFoul(log).isNotEmpty() -> { foul = true; reason = kitchenFoul(log) }
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
            kitchenOnly = kitchenAfter(log)
            if (cueIn || cueOff || !world.balls[0].onTable) restoreCue()
            notes.add(0, "Foul: $reason")
            notes += "${has(opp)} ball in hand" + if (kitchenOnly) " in the kitchen" else ""
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
    fun applyTrickLayout() {
        val lv = Trick.LEVELS[trick.level]
        if (lv.standard) { Table.useStandard(TableSize.NINE); Table.trickClassic = true }
        else Table.useGap(lv.holes, lv.farCorners)
    }

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
        if (bumperGame && winner < 0) world.cue = defaultBall(current)
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
            } else if (t.levelNo >= Trick.levelsOf(t.set).size) {
                if (t.levelWins[0] != t.levelWins[1]) win = if (t.levelWins[0] > t.levelWins[1]) 0 else 1
                else { t.suddenDeath = true; notes += "Sudden death!" }
            }
            if (win < 0) {
                val set = Trick.levelsOf(t.set)
                t.level = set[t.levelNo % set.size]
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

    /**
     * Bumper pool: player 0 has the red balls 1-5, player 1 the white balls 6-10 (and on the octagon
     * table player 2 the blue 11-15, player 3 the yellow 16-20). The first of each five is the marked ball.
     */
    fun ownerOf(id: Int) = if (id in 1..each * players && bumperGame) (id - 1) / each else -1
    fun markedBall(p: Int) = each * p + 1
    fun bumperBalls(p: Int): IntRange = (each * p + 1)..(each * p + each)
    /**
     * The cup each player shoots into (an index into Table.pockets): red the right-hand one, white the
     * left; on the octagon table blue the bottom one and yellow the top — always the cup across from them.
     */
    fun targetCup(p: Int) = if (fourWay) OCT_TARGET[p] else if (p == 0) 1 else 0
    /** The cup at a player's own end: sinking your own ball in it is a foul. */
    fun homeCup(p: Int) = if (fourWay) p else 1 - targetCup(p)
    fun ballsLeft(p: Int) = bumperBalls(p).count { world.balls[it].onTable }
    /** The Gauntlet: the ramp (see [Gauntlet.rampLocal]) on player [p]'s side, which faces the cup [p] shoots into. */
    fun homeCupRamp(p: Int) = when (homeCup(p)) { 0 -> 0; 1 -> 2; 2 -> 1; else -> 3 }
    fun colorName(p: Int) = COLOR_NAMES[p]

    /** Where a player's opening ball sits: just in front of their own cup, toward the middle. */
    private fun openingSpot(p: Int): DoubleArray {
        if (mode == GameMode.GAUNTLET) return Gauntlet.startSpots(p, each)[0]
        val home = Table.pockets[homeCup(p)]
        val ux = P.L / 2 - home.cx; val uy = P.W / 2 - home.cy
        val l = hypot(ux, uy)
        return doubleArrayOf(home.cx + ux / l * 0.13, home.cy + uy / l * 0.13)
    }

    /** The ball a player plays the opening with: the one on their opening spot (at first, the marked ball). */
    fun openingBall(p: Int): Int {
        val s = openingSpot(p)
        return bumperBalls(p).filter { world.balls[it].onTable }
            .minByOrNull { hypot(world.balls[it].x - s[0], world.balls[it].y - s[1]) } ?: -1
    }

    /** Balls player [p] may shoot: the marked ball until it's down, then any of theirs (in the opening, the opening ball). */
    fun shootable(p: Int = current): List<Int> {
        if (!bumperGame) return listOf(0)
        if (isBreak) { val b = openingBall(p); return if (b >= 0) listOf(b) else emptyList() }
        val o = playsFor(p)
        val m = markedBall(o)
        if (world.balls[m].onTable) return listOf(m)
        return bumperBalls(o).filter { world.balls[it].onTable }
    }

    fun canShoot(id: Int) = bumperGame && id in shootable()

    /** Bumper pool: shoot this ball (one of the current player's). False if they can't. */
    fun selectBall(id: Int): Boolean {
        if (!canShoot(id)) return false
        world.cue = id
        return true
    }

    /** The ball a player starts their turn with: of the ones they may shoot, the nearest their cup. */
    private fun defaultBall(p: Int): Int {
        val cup = Table.pockets[targetCup(playsFor(p))]
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
        if (fourWay) return octHint()
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
    fun openingPending() = bumperGame && isBreak && winner < 0

    /**
     * The current player's opening shot. It's held back until the other player has chosen theirs,
     * and then both go at once: returns true when both are in (call [fireOpening]); otherwise it's
     * the other player's turn to set theirs.
     */
    fun setOpening(angle: Double, speed: Double, side: Double, vert: Double, elevation: Double): Boolean {
        opening[current] = doubleArrayOf(angle, speed, side, vert, elevation)
        // The next player round the table who hasn't set theirs yet.
        var other = -1
        var p = current
        for (k in 1 until players) { p = nextPlayer(p); if (opening[p] == null && openingBall(p) >= 0) { other = p; break } }
        if (other < 0) return true
        val set = if (you(current)) "Your opening shot is set" else "${names[current]}'s opening shot is set"
        current = other
        world.cue = openingBall(other).coerceAtLeast(0)
        message = "$set · " + if (you(other)) "your turn" else "over to ${names[other]}"
        return false
    }

    /** Every opening shot at once. */
    fun fireOpening() {
        beginShot()
        ballInHand = false; kitchenOnly = false
        var first = true
        for (p in 0 until 4) openIds[p] = -1
        for (p in 0 until players) {
            val s = opening[p] ?: continue
            val id = openingBall(p)
            openIds[p] = id
            if (id < 0) continue
            world.strike(s[0], s[1], s[2], s[3], s[4], id, resetLog = first)
            first = false
        }
        val firstId = openIds.firstOrNull { it >= 0 } ?: -1
        if (firstId >= 0) world.cue = firstId
    }

    /** A loaded game: an opening that was being set up starts again from the first player. */
    fun afterRestore() {
        if (!bumperGame) return
        for (p in 0..3) opening[p] = null
        if (isBreak) { current = 0; world.cue = openingBall(0).coerceAtLeast(0) }
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
            if (shooting[p] && id >= 0 && (log.air and (1L shl id)) != 0L) foul(p, "no jump shots in bumper pool")
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

    // ------------------------------------------------------------------ octagon bumper table
    //
    // Bumper pool for two to four players on the octagon table (Table.useOctagon), or two teams of
    // two. Everyone shoots into the cup across from them. All the marked balls go at once to open, in
    // any direction (no jump shots); after that turns go round the table. A foul — your ball in a cup
    // that isn't yours to shoot at (it comes back out onto your spot), a ball off the table (it goes
    // back in the middle) or a jump shot — lets every other player (or each player on the other team)
    // drop one of their balls in by hand. First to get all five down wins; in teams, a player whose
    // five are down shoots their partner's, and the team wins when all ten are down.

    /** The order turns go round the table: red (left end), blue (top), white (right end), yellow (bottom). */
    fun turnOrder(): IntArray = when {
        !fourWay -> IntArray(players) { it }
        players == 2 -> intArrayOf(0, 1)
        players == 3 -> intArrayOf(0, 2, 1)
        // Teams always take turns: partners across (red, blue, white, yellow), or beside each other.
        teams && partner == 2 -> intArrayOf(0, 1, 2, 3)
        teams && partner == 3 -> intArrayOf(0, 2, 3, 1)
        else -> intArrayOf(0, 2, 1, 3)
    }

    /** Who shoots after player [p]. */
    fun nextPlayer(p: Int): Int {
        val o = turnOrder()
        val i = o.indexOf(p)
        return o[(i + 1) % o.size]
    }

    /** The two players on player 0's side (0 and [partner]); the other two are the other team. */
    private fun firstTeam(p: Int) = p == 0 || p == partner

    /** Team play: red and their [partner] against the other two. Each player's partner, or -1. */
    fun partnerOf(p: Int): Int {
        if (!teams || players != 4) return -1
        if (p == 0) return partner
        if (p == partner) return 0
        return (1..3).first { it != partner && it != p }
    }
    fun teamOf(p: Int) = if (teams && players == 4) (if (firstTeam(p)) 0 else 1) else p
    fun sameTeam(a: Int, b: Int) = teamOf(a) == teamOf(b)
    fun teamName(p: Int): String {
        val q = partnerOf(p)
        if (q < 0) return names[p]
        val (a, b) = if (p < q) p to q else q to p
        return "${names[a]} & ${names[b]}"
    }
    /** Whether player [p] is on the winning side (the winner themselves, or their partner). */
    fun won(p: Int) = winner >= 0 && sameTeam(winner, p)

    /** Whose balls player [p] shoots: their own, or (team play) their partner's once their own are all down. */
    fun playsFor(p: Int): Int {
        val q = partnerOf(p)
        return if (q >= 0 && fourWay && ballsLeft(p) == 0) q else p
    }

    /** Done: all theirs down (in team play, all the team's). */
    private fun finished(p: Int): Boolean {
        val q = partnerOf(p)
        return ballsLeft(p) == 0 && (q < 0 || ballsLeft(q) == 0)
    }

    private fun octHint(): String {
        val p = current
        val o = playsFor(p)
        val set = (0 until players).count { opening[it] != null }
        return when {
            isBreak && set > 0 -> "${names[p]}: your opening shot — $set set, all go together · any direction, no jumps"
            isBreak -> "${names[p]}: your opening shot · every marked ball goes at once, in any direction (no jumps)"
            o != p && world.balls[markedBall(o)].onTable -> "${names[p]}: yours are down — sink ${names[o]}'s marked ${colorName(o)} ball"
            o != p -> "${names[p]}: yours are down — shoot ${names[o]}'s ${colorName(o)} balls"
            world.balls[markedBall(p)].onTable -> "${names[p]}: sink your marked ball first (the spotted one)"
            else -> "${names[p]}: tap any of your ${colorName(p)} balls to shoot it"
        }
    }

    private fun startOctagon(rng: Random) {
        for (b in world.balls) { b.onTable = false; b.stop() }
        for (p in 0 until players) {
            val home = Table.pockets[homeCup(p)]
            val ids = bumperBalls(p).toList()
            val spot = openingSpot(p)
            fun put(id: Int, x: Double, y: Double) {
                val b = world.balls[id]
                b.x = x; b.y = y; b.onTable = true
                randomOrientation(b, rng)
            }
            if (mode == GameMode.GAUNTLET) {
                // Round their own cup: the marked ball in front, the rest in two little groups along the cushion.
                Gauntlet.startSpots(p, each).forEachIndexed { k, xy -> put(ids[k], xy[0], xy[1]) }
                continue
            }
            put(ids[0], spot[0], spot[1])
            // The other four in two pairs, one each side of the cup, as on the small table.
            val ux = P.L / 2 - home.cx; val uy = P.W / 2 - home.cy
            val l = hypot(ux, uy)
            val ix = ux / l; val iy = uy / l       // into the table
            val ax = -iy; val ay = ix               // along the wall
            var k = 1
            for (s in doubleArrayOf(-1.0, 1.0)) for (d in doubleArrayOf(-0.04, 0.04))
                put(ids[k++], home.cx + ax * s * 0.2 + ix * d, home.cy + ay * s * 0.2 + iy * d)
        }
        current = 0
        for (p in 0..3) { opening[p] = null; openIds[p] = -1 }
        groups[0] = Group.OPEN; groups[1] = Group.OPEN
        ballInHand = false; kitchenOnly = false; isBreak = true
        winner = -1
        consecutiveFouls[0] = 0; consecutiveFouls[1] = 0
        world.cue = markedBall(0)
        world.log.reset()
        message = "Opening: every marked ball goes at the same moment"
    }

    /** Puts ball [id] at (x, y), or as near as there's room. */
    private fun placeNear(id: Int, x: Double, y: Double) {
        val b = world.balls[id]
        b.stop()
        b.onTable = true
        if (world.isFree(x, y, id)) { b.x = x; b.y = y; return }
        var r = 0.02
        while (r < 0.4) {
            for (k in 0 until 16) {
                val a = 2 * PI * k / 16
                val px = x + r * cos(a); val py = y + r * sin(a)
                if (world.isFree(px, py, id)) { b.x = px; b.y = py; return }
            }
            r += 0.02
        }
        b.x = x; b.y = y
    }

    /** A penalty on the octagon table: player [p] drops one of their balls in by hand (the marked one first). */
    private fun dropOne(p: Int): Boolean {
        val cup = Table.pockets[targetCup(p)]
        val id = bumperBalls(p).filter { world.balls[it].onTable }.minWithOrNull(compareBy(
            { if (it == markedBall(p)) 0 else 1 },
            { -hypot(world.balls[it].x - cup.cx, world.balls[it].y - cup.cy) })) ?: return false
        val b = world.balls[id]; b.onTable = false; b.stop()
        return true
    }

    /** Player [fouler] fouled: every other player (in teams, each opponent — or their partner, if they're out) drops one in. */
    private fun octPenalty(fouler: Int): String {
        val got = ArrayList<Int>()
        for (q in turnOrder()) {
            if (q == fouler || sameTeam(q, fouler)) continue
            if (dropOne(q)) got += q
            else { val r = partnerOf(q); if (r >= 0 && dropOne(r)) got += r }
        }
        return when {
            got.isEmpty() -> ""
            got.size == 1 -> "${names[got[0]]} ${if (you(got[0])) "drop" else "drops"} one in"
            got.size == players - 1 && !teams -> "everyone else drops one in"
            else -> got.joinToString(" and ") { names[it] } + " drop one in each"
        }
    }

    /** Who wins now, if anyone: the first player found finished, going round the table from [from]. */
    private fun octWinner(from: Int): Int {
        var p = from
        for (k in 0 until players) {
            if (finished(p)) return p
            p = nextPlayer(p)
        }
        return -1
    }

    private fun octTimeFoul(): ShotOutcome {
        val p = current
        isBreak = false
        val drop = octPenalty(p)
        val win = octWinner(nextPlayer(p))
        if (win >= 0) {
            winner = win
            message = "${winsText(win)} (shot clock foul${if (drop.isNotEmpty()) ": $drop" else ""})"
            return ShotOutcome(true, "Shot clock", false, win, message)
        }
        current = nextPlayer(p)
        message = "Foul: shot clock expired" + (if (drop.isNotEmpty()) " · $drop" else "") + " · ${names[current]} to shoot"
        return ShotOutcome(true, "Shot clock", false, -1, message)
    }

    private fun resolveOctagon(): ShotOutcome {
        val log = world.log
        val n = players
        val isOpening = isBreak
        val shooting = BooleanArray(n) { if (isOpening) openIds[it] >= 0 else it == current }
        val struck = IntArray(n) { if (isOpening) openIds[it] else if (it == current) preCue else -1 }
        val plays = IntArray(n) { if (isOpening) it else prePlays[it] }
        val foulBy = BooleanArray(n)
        val reason = Array(n) { "" }
        fun foul(p: Int, why: String) { if (!foulBy[p]) { foulBy[p] = true; reason[p] = why } }
        val good = BooleanArray(n)          // sank a ball they were shooting for, in the right cup
        var firstGood = -1
        val markedUp = BooleanArray(n) { preMarked[it] }
        val notes = ArrayList<String>()
        // Whose shot each player's balls were in: the player shooting them (or -1, knocked in by someone else).
        fun shooterOf(o: Int): Int {
            for (q in 0 until n) if (shooting[q] && plays[q] == o) return q
            return -1
        }

        for (id in log.pocketed) {
            val o = ownerOf(id)
            if (o < 0) continue
            val q = shooterOf(o)
            if (q < 0) continue      // knocked in by someone else: it counts for its owner, wherever it went
            if (log.pocketIn[id] != targetCup(o)) {
                foul(q, "${colorName(o)} ball in the wrong cup")
                val sp = openingSpot(o)
                placeNear(id, sp[0], sp[1])
                continue
            }
            if (id != markedBall(o) && markedUp[o]) { foul(q, "a ball went in before the marked ball"); continue }
            if (id == markedBall(o)) markedUp[o] = false
            good[q] = true
            if (firstGood < 0) firstGood = q
        }
        for (id in log.jumpedOff) {
            val o = ownerOf(id)
            if (o < 0) continue
            val q = if (isOpening) (shooterOf(o).takeIf { it >= 0 } ?: o) else current
            foul(q, "a ball left the table")
            respotMiddle(id)
        }
        for (q in 0 until n) {
            val id = struck[q]
            if (shooting[q] && id >= 0 && ((log.cueJumps or log.scoops) and (1L shl id)) != 0L) foul(q, "no jump shots")
        }
        for (q in turnOrder()) if (foulBy[q]) {
            val drop = octPenalty(q)
            notes += (if (you(q) || n == 2) "Foul: " else "Foul by ${names[q]}: ") + reason[q] + if (drop.isNotEmpty()) " · $drop" else ""
        }

        val anyFoul = foulBy.any { it }
        val why = reason.firstOrNull { it.isNotEmpty() } ?: ""
        val win = octWinner(if (isOpening) 0 else current)
        if (win >= 0) {
            isBreak = false
            for (p in 0..3) opening[p] = null
            winner = win
            message = winsText(win) + if (notes.isNotEmpty()) " (${notes.joinToString(", ")})" else ""
            return ShotOutcome(anyFoul, why, false, win, message)
        }

        var keep = false
        if (isOpening) {
            isBreak = false
            for (p in 0..3) opening[p] = null
            // First in (without a foul) goes first; otherwise whoever's ball finished nearest their cup.
            val inFirst = log.pocketed.map { ownerOf(it) }.firstOrNull { it >= 0 && good[it] && !foulBy[it] && shooting[it] } ?: -1
            current = if (inFirst >= 0) inFirst else {
                val pool = (0 until n).filter { shooting[it] && !foulBy[it] }.ifEmpty { (0 until n).filter { shooting[it] } }
                pool.minByOrNull { p ->
                    val b = world.balls[struck[p]]
                    val cup = Table.pockets[targetCup(p)]
                    if (!b.onTable) Double.MAX_VALUE else hypot(b.x - cup.cx, b.y - cup.cy)
                } ?: 0
            }
            notes += if (inFirst >= 0) "${names[current]} ${if (you(current)) "go" else "goes"} first"
                else if (!anyFoul) "None went in · ${names[current]} ${if (you(current)) "were" else "was"} nearest and ${if (you(current)) "go" else "goes"} first"
                else "${names[current]} ${if (you(current)) "go" else "goes"} first"
        } else {
            keep = !foulBy[current] && good[current]
            if (!keep) current = nextPlayer(current)
            notes += if (keep) continues(current) else "${names[current]} to shoot"
        }
        message = notes.joinToString(" · ")
        return ShotOutcome(anyFoul, why, keep, -1, message)
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
        kitchenFoul(log).isNotEmpty() -> kitchenFoul(log)
        log.jumpedOff.isNotEmpty() -> "Ball jumped off the table"
        else -> ""
    }

    /** Numbered balls that touched a cushion this shot (a legal break needs four, if nothing went in). */
    fun railedObjectBalls(log: ShotLog): Int = (1..15).count { (log.railed and (1L shl it)) != 0L }

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
            kitchenOnly = brk || kitchenAfter(log)
            if (!world.balls[0].onTable) restoreCue()
            notes.add(0, "Foul: $reason")
            notes += "${has(opp)} ball in hand" + if (kitchenOnly) " behind the line" else ""
        } else if (theirs > 0 && !brk) {
            current = opp
            notes += "${names[opp]} to shoot"
        } else if (objects.isNotEmpty() && !calledIn(objects, log)) {
            current = opp
            notes += "Slop: not in the called pocket"
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
            kitchenFoul(log).isNotEmpty() -> kitchenFoul(log)
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
        var slop = false
        if (foul) {
            for (id in objects) respot(id)
            if (objects.isNotEmpty()) notes += if (objects.size == 1) "the ${objects[0]} is spotted" else "${objects.size} balls spotted"
        } else for (id in objects) {
            val pk = log.pocketIn[id]
            val o = if (pk in pw.owner.indices) pw.owner[pk] else -1
            when {
                brk -> scored += id
                // Call every shot: a ball you sink anywhere but the called pocket scores nothing and is spotted
                // (one in the other player's pocket still scores for them, as below).
                everyShot && pk != preCalled && !(o == opp && (log.banked and (1L shl id)) == 0L) -> {
                    respot(id); slop = true; notes += "the $id wasn't in the called pocket · spotted"
                }
                o == -1 -> { pw.owner[pk] = shooter; scored += id; notes += (if (you(shooter)) "you claim" else "${names[shooter]} claims") + " the ${pocketName(pk)}" }
                o == shooter -> scored += id
                (log.banked and (1L shl id)) != 0L -> {
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
            kitchenOnly = kitchenAfter(log)
            if (!world.balls[0].onTable) restoreCue()
            notes.add(0, "Foul: $reason")
            notes += "${has(opp)} ball in hand" + if (kitchenOnly) " in the kitchen" else ""
        } else if (gift || (slop && scored == 0)) {
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

    /**
     * Cutthroat: player [p] is out — they have a group and none of it is left (until a foul puts one back), or
     * they never got a group and every group nobody has is already off the table, so there's nothing left for
     * them to take.
     */
    fun cutOut(p: Int) = if (cut.group[p] >= 0) cutLeft(p) == 0 else cutPickable().isEmpty()

    /** Cutthroat: the groups that can still be picked — nobody has them and they have a ball on the table. */
    fun cutPickable(): List<Int> = cut.free().filter { g -> CutthroatState.ballsOf(g).any { world.balls[it].onTable } }

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
        kitchenFoul(log).isNotEmpty() -> kitchenFoul(log)
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
    fun cutSuggestedGroup(): Int = cutPickable().maxWithOrNull(compareBy<Int> { g ->
        CutthroatState.ballsOf(g).count { world.balls[it].onTable } }.thenByDescending { it }) ?: 0

    /**
     * Cutthroat: the player who just sank a ball on an open table ([CutthroatState.choosing]) takes group [g].
     * When that makes two players with a group, the third gets the one that's left. Then the turn carries on.
     */
    fun cutPick(g: Int): ShotOutcome {
        val p = cut.choosing
        require(p >= 0 && g in cutPickable())
        val inBefore = BooleanArray(3) { !cutOut(it) }
        cut.group[p] = g
        cut.choosing = -1
        val notes = ArrayList<String>()
        notes += (if (you(p)) "You take " else "${names[p]} takes ") + CutthroatState.groupName(g)
        if (cut.group.count { it < 0 } == 1) {
            val q = cut.group.indexOf(-1)
            val last = cutPickable().firstOrNull() ?: cut.free().first()
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
            if (g >= 0) cutLeft(p) + objects.count { CutthroatState.groupOf(it) == g } > 0
            else cut.free().any { f -> CutthroatState.ballsOf(f).any { world.balls[it].onTable || it in objects } }
        }
        val notes = ArrayList<String>()
        val own = objects.count { preGroup >= 0 && CutthroatState.groupOf(it) == preGroup }
        if (!foul && own > 0 && own == objects.size) notes += if (you(shooter)) "that was your own" else "${names[shooter]} sank their own"
        // Open table: the first legal pot lets the shooter pick a group.
        val called = calledIn(objects, log)
        if (!foul && !brk && objects.isNotEmpty() && !called) notes += "Slop: not in the called pocket"
        if (!foul && !brk && objects.isNotEmpty() && called && preGroup < 0 && cutPickable().isNotEmpty()) cut.choosing = shooter
        if (foul) notes += cutPenalty(shooter)
        isBreak = false
        // Any ball down on a legal shot keeps you at the table (even your own — that's cutting your own throat).
        val keep = !foul && objects.isNotEmpty() && called && (cut.choosing == shooter || !cutOut(shooter))
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
            kitchenOnly = kitchenAfter(log)
            if (!world.balls[0].onTable) restoreCue()
            notes.add(0, "Foul: $reason")
            notes += "${has(next)} ball in hand" + if (kitchenOnly) " in the kitchen" else ""
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

    companion object {
        /** Octagon table: the cup each player shoots into (the one across from them). */
        val OCT_TARGET = intArrayOf(1, 0, 3, 2)
        val COLOR_NAMES = arrayOf("red", "white", "blue", "yellow")
        val POCKET_NAMES = arrayOf("top-left corner", "top-right corner", "bottom-left corner", "bottom-right corner",
            "top side pocket", "bottom side pocket")
        val IDS_8 = (1..15).toList()
        val IDS_9 = (1..9).toList()
        val IDS_BUMPER = (1..10).toList()
    }
}
