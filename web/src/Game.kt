package com.poolgame.web

import com.poolgame.app.core.Ai
import com.poolgame.app.core.Ball
import com.poolgame.app.core.BumperColors
import com.poolgame.app.core.NB
import com.poolgame.app.core.CallMode
import com.poolgame.app.core.CutthroatState
import com.poolgame.app.core.GameMode
import com.poolgame.app.core.GameSave
import com.poolgame.app.core.GameSettings
import com.poolgame.app.core.Group
import com.poolgame.app.core.Match
import com.poolgame.app.core.P
import com.poolgame.app.core.Plan
import com.poolgame.app.core.PovCam
import com.poolgame.app.core.PovControl
import com.poolgame.app.core.PovScene
import com.poolgame.app.core.Prediction
import com.poolgame.app.core.Predictor
import com.poolgame.app.core.Table
import com.poolgame.app.core.TableSize
import com.poolgame.app.core.Trick
import com.poolgame.app.core.TutTarget
import com.poolgame.app.core.Tutorial
import com.poolgame.app.core.World
import com.poolgame.app.core.WorldListener
import com.poolgame.app.core.toRad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

interface GameHost {
    fun onMenuRequested()
    fun onExitToMenu()
    fun onGameFinished(winner: Int): String
    /** Rock, paper, scissors decided who breaks: [rounds] throws, ties included. */
    fun onRpsFinished(winner: Int, rounds: Int)
}

/** The game screen, drawn on a canvas. A port of the Android GameView. */
class Game(
    private val mode: GameMode,
    private val vsCpu: Boolean,
    private val cpuLevel: Int,
    private val shotClock: Int,
    private val practiceMode: Boolean,   // practice: the free table, or (with GameMode.TRICK) one trick shot
    private val trickLevel: Int,
    aimSensitivity: Int,
    var showPrediction: Boolean,
    resume: String?,
    private val callMode: CallMode,
    private val sfx: Sounds,
    private val host: GameHost,
    private val tutorialMode: Boolean = false,  // the hands-on tutorial (a practice table with a guide)
    private val tableSize: TableSize = TableSize.DEFAULT,  // table for 8-ball, 9-ball and practice
    private val practiceRules: Boolean = false,  // practice: play the game's own rules on your own (not the free table)
    private val octPlayers: Int = 4,             // octagon table: how many players (2 to 4)
    private val octTeams: Boolean = false,       // octagon table, four players: two teams of two
    private val octLayout: Int = 0,              // octagon table: the bumper layout
    private val scratchKitchen: Boolean = false, // bar rules: ball in hand in the kitchen after a scratch
    private val trickSet: Int = 0                // trick-shot match: 0 the gap jumps, 1 the classics
) {
    private enum class Phase { RPS, HANDOFF, PICK, HUMAN, CPU_THINK, CPU_ANIM, RUNNING, PAUSE, OVER }
    private enum class Rps { PICK, SHAKE, REVEAL }
    private enum class Touch { NONE, AIM, POWER, SPIN, WHEEL, MOVE_CUE, BUTTON, PENDING, MOVE_BALL, TAP_BALL, POV, CAM }

    /** Free-table practice: no rules, move any ball. (The tutorial runs on the practice table too.) */
    private val practice = practiceMode && mode != GameMode.TRICK
    /**
     * Practising a game on your own with its rules: every shot is judged as in a match (fouls, groups, targets,
     * pockets), but you take every turn. The practice tools (move balls, retry, re-rack) still work.
     */
    private val rulesPractice = practice && practiceRules && !tutorialMode && mode in RULES_PRACTICE
    private var rpShots = 0
    private var rpFouls = 0
    private var practiceMatch: Match? = null

    // The tutorial: one control at a time is highlighted, with a card saying what to do.
    private var tut: Tutorial? = null
    private var tutT = 0.0
    private var tutLeaving = false
    private var tutAimFrom = 0.0; private var tutAimTo = 0.0; private var tutAimK = 1.0
    private val tutCard = Rect(); private val tutBtn = Rect(); private val tutSkip = Rect(); private val tutShape = Rect()
    private var tutRound = false
    private val tutFinger = DoubleArray(4)
    /** Practising one trick shot on your own: unlimited attempts, the table resets after each. */
    private val trickPractice = practiceMode && mode == GameMode.TRICK
    private var tpTries = 0
    private var tpMade = 0

    /** Cutthroat: three players — you and two CPUs (both at the menu's level), or three people passing the phone. */
    private val cutthroat = mode == GameMode.CUTTHROAT
    /** The octagon bumper table: two to four players (or two teams of two), four cups, ramps for cushions. */
    private val octagon = mode == GameMode.OCTAGON
    private val names = when {
        // (Practice on the octagon table puts out all four colours, so it has four seats.)
        octagon && practiceMode -> arrayOf("Red", "White", "Blue", "Yellow")
        octagon && vsCpu && octPlayers == 2 -> arrayOf("You", "CPU")
        octagon && vsCpu -> Array(octPlayers) { if (it == 0) "You" else "CPU $it" }
        octagon -> Array(octPlayers) { "Player ${it + 1}" }
        cutthroat && vsCpu -> arrayOf("You", "CPU 1", "CPU 2")
        cutthroat && rulesPractice -> arrayOf("You", "Opponent 1", "Opponent 2")
        cutthroat -> arrayOf("Player 1", "Player 2", "Player 3")
        vsCpu -> arrayOf("You", "CPU")
        trickPractice -> arrayOf("You", "You")
        rulesPractice -> arrayOf("You", "Opponent")
        else -> arrayOf("Player 1", "Player 2")
    }
    private val rng = Random(now().toLong())
    // 8-ball, 9-ball and practice use the chosen table size (set before the layout reads its dimensions);
    // trick shots always use the 9 ft gap table.
    private var match = run { useTable(); Match(mode, names).also { it.teams = octTeams && names.size == 4 && !practiceMode } }
    private var breaker = 0
    private var phase = Phase.HUMAN
    private var generation = 0

    private var aimAngle = 0.0
    private var power = 0.0
    private var lastPower = 0.35
    private var spinX = 0.0
    private var spinY = 0.0
    private var elevDeg = 0.0
    private val ptrMode = Array(10) { Touch.NONE }
    private val ptrStartY = DoubleArray(10)
    private val ptrLastY = DoubleArray(10)
    private val ptrDownX = DoubleArray(10)
    private val ptrDownY = DoubleArray(10)
    private val ptrDownAt = DoubleArray(10)
    private val ptrBall = IntArray(10)
    private val ptrLastT = DoubleArray(10)   // fine-aim wheel: time of the last move (ms)
    private val ptrVel = DoubleArray(10)     // fine-aim wheel: smoothed finger speed (px/s)
    private val ptrLastX = DoubleArray(10)   // 3D view: last finger x
    private val ptrAxis = IntArray(10)       // 3D view drag: 0 not decided yet, 1 sideways (aim), 2 up/down (cue angle)

    // The 3D view: opened by tapping a ball or the 3D view button, closed by Top view. It stays on
    // through shots and turns; the camera orbits the cue ball wherever the player has put it.
    private var pov = false
    private val povCtl = PovControl()
    private val povCam = PovCam()
    // While balls roll (and while dragging a ball in 3D) the camera holds still instead of following the cue ball.
    private var povLocked = false
    private var povLockX = 0.0; private var povLockY = 0.0
    private val povXY = DoubleArray(2)
    private val resetViewBtn = Rect()
    private val view3dBtn = Rect()
    private var povHintUntil = 0.0
    // Two-finger camera gesture: the previous spread and midpoint of the two fingers.
    private var camDist = 0.0; private var camMidX = 0.0; private var camMidY = 0.0
    private val ptrX = DoubleArray(10); private val ptrY = DoubleArray(10)
    private val povScene = PovScene()
    private val povBalls = Balls()
    private val povView = DoubleArray(9)
    private val povSeg = DoubleArray(4)
    private val povPt = DoubleArray(3)
    private val povCue = DoubleArray(9 * 4)
    private val topViewBtn = Rect()
    private var wheelOffset = 0.0
    private var dragInvalid = false
    private var dragX = 0.0; private var dragY = 0.0

    private var practiceSnapshot: World? = null
    private val retryBtn = Rect(); private val rackBtn = Rect(); private val clearBtn = Rect()
    private var movingBall = -1

    // Ball tray left of the table: pocketed balls roll down it and line up from the bottom,
    // in the order they were made, like the ball return on a bar table.
    private val trayRect = Rect()
    private var potR = 0.0
    private val potIds = IntArray(NB)
    private val potY = DoubleArray(NB)
    private val potV = DoubleArray(NB)
    private var potN = 0
    private var trayGrad: Gradient? = null

    // Rock, paper, scissors decides who breaks.
    private var rps = Rps.PICK
    private val rpsPick = intArrayOf(-1, -1)      // 0 rock, 1 paper, 2 scissors
    private var rpsT = 0.0
    private var rpsBeat = 0
    private var rpsTie = false
    private var rpsRounds = 0                     // throws this game, ties included (for the stats)
    private val rpsBtn = Array(2) { Array(3) { Rect() } }
    private val rpsPanel = Rect()

    private val wheelRadPerPx = 0.0002 * 1.6.pow((aimSensitivity.coerceIn(1, 10) - 5).toDouble())
    private var turnSave: String? = null
    private val hit = DoubleArray(4)

    private var callFlashUntil = 0.0

    // Trick shots: a banner announcing each level as it starts.
    private val trick = mode == GameMode.TRICK
    /** Bumper pool: no cue ball (you shoot your own balls), its own table, and an opening shot both players take at once. */
    private val bumper = mode == GameMode.BUMPER || octagon
    /** Bumper pool practice: the bumper table on your own, any ball, no rules. */
    private val bumperPractice = practice && bumper
    /** Circuit Pool: three secret target balls each. */
    private val circuit = mode == GameMode.CIRCUIT
    /**
     * Circuit Pool: whose targets are on show (-1 for nobody). Against the CPU it's always you; in pass & play
     * they're hidden at each change of turn until the next player taps to see theirs.
     */
    private var revealTo = if (vsCpu || practiceMode) 0 else -1
    /** Pocket Wars: pockets ringed in their owner's colour. */
    private val pocketWars = mode == GameMode.POCKET_WARS
    private var bannerLevel = -1
    private var bannerT = 0.0
    private var clockStarted = false
    private var clockLeft = 0.0
    private var lastBeep = 0
    var menuOpen = false

    private var cpuPlan: Plan? = null
    private var animT = 0.0
    private var animFromAngle = 0.0
    private var animFromX = 0.0; private var animFromY = 0.0
    private var thinkingSince = 0.0

    private var pauseT = 0.0
    private var message = ""
    private var gameOverTitle = ""
    private var recordLine = ""

    private class Sink(val id: Int, val x: Double, val y: Double, var age: Double)
    private val sinks = ArrayList<Sink>()

    private val balls = Balls()
    private val prediction = Prediction()
    private val scratchWorld = World()
    private val predKey = DoubleArray(9) { Double.NaN }

    // Layout (CSS pixels, landscape)
    var width = 0.0; private set
    var height = 0.0; private set
    private var dpr = 1.0
    private var scale = 1.0
    private var ox = 0.0; private var oy = 0.0
    private var rpx = 10.0
    private val dp = 1.0
    private var hudH = 0.0
    private val tableArea = Rect(); private val powerRect = Rect()
    private var spinCx = 0.0; private var spinCy = 0.0; private var spinR = 0.0
    private val wheelRect = Rect()
    private val menuBtn = Rect(); private val rematchBtn = Rect(); private val menuExitBtn = Rect()
    private val overPanel = Rect(); private val leftCard = Rect(); private val rightCard = Rect()
    private val midCard = Rect()                 // Cutthroat's third player card (left of the right-hand one)
    private val card4 = Rect()                   // octagon table: the fourth player's card
    private var msgL = 0.0; private var msgR = 0.0   // the HUD's message, between the player cards
    private val pickBtn = Array(3) { Rect() }
    private var background: dynamic = null
    private var powerGrad: Gradient? = null
    private var spinGrad: Gradient? = null
    private var wheelGrad: Gradient? = null
    private val stickGrads = arrayOfNulls<Gradient>(8)
    private val stickSegs = doubleArrayOf(0.0, 0.008, 0.03, 0.72, 0.74, 0.98, 1.22, 1.43, 1.45)
    private val stickColors = intArrayOf(
        0xFF3D7BC9.toInt(), 0xFF24518A.toInt(), 0xFFF3EEDF.toInt(), 0xFFBFB8A4.toInt(),
        0xFFE9CF9E.toInt(), 0xFFB08B54.toInt(), 0xFF2B2B2B.toInt(), 0xFF101010.toInt(),
        0xFF5A2A14.toInt(), 0xFF2E1408.toInt(), 0xFF1C1C1C.toInt(), 0xFF080808.toInt(),
        0xFF5A2A14.toInt(), 0xFF2E1408.toInt(), 0xFF111111.toInt(), 0xFF111111.toInt()
    )
    private val bold = "'Arial Narrow', 'Roboto Condensed', 'Helvetica Neue', Arial, sans-serif"
    private val regular = "-apple-system, system-ui, 'Segoe UI', Roboto, Arial, sans-serif"
    private val dash = arrayOf(6.0, 5.0)
    private val noDash = arrayOf<Double>()

    init {
        balls.plain = practice && !tutorialMode && !bumper && !rulesPractice
        povBalls.plain = balls.plain
        balls.bumper = bumper
        povBalls.bumper = bumper
        if (bumper) povScene.cupRings = BumperColors.cupRings(octagon, names.size)
        // Cutthroat has no rock, paper, scissors (three players): the first breaker is drawn at random.
        if (resume == null || !resumeGame(resume)) newGame(if (cutthroat && !practiceMode) rng.nextInt(3) else 0)
    }

    private fun makeListener() = object : WorldListener {
        override fun onBallHit(speed: Double) = sfx.ball(speed)
        override fun onCushion(speed: Double) { if (speed > 0.15) sfx.rail(speed) }
        override fun onPocket(id: Int) {
            sfx.drop()
            val b = match.world.balls[id]
            sinks += Sink(id, b.x, b.y, 0.0)
            if (id == 0) crowdAww() else addToTray(id)
        }
        override fun onOffTable(id: Int) = crowdAww()
    }

    /** Scratch or a ball off the table: the crowd groans, a beat after it happens. */
    private fun crowdAww() { window.setTimeout({ sfx.aww() }, 180) }

    private fun isCpu(player: Int) = vsCpu && player != 0

    // ------------------------------------------------------------------ game flow

    fun newGame(breakerPlayer: Int) {
        generation++
        breaker = breakerPlayer
        useTable()
        match = Match(mode, names)
        match.trick.set = trickSet.coerceIn(0, Trick.SETS.size - 1)
        match.teams = octTeams && names.size == 4 && !practiceMode
        match.world.trackOrientation = true
        match.world.listener = makeListener()
        match.rack(rng, breaker)
        match.callMode = if (practice) CallMode.OFF else callMode
        match.scratchKitchen = scratchKitchen && !practice
        revealTo = if (vsCpu || practiceMode) 0 else -1
        rpShots = 0; rpFouls = 0; practiceMatch = null; practiceSnapshot = null
        if (rulesPractice) match.message = "${gameName()} practice · you take every shot · place the cue ball and break"
        else if (practice) {
            match.ballInHand = false
            match.kitchenOnly = false
            match.message = practiceHint()
            practiceSnapshot = null
            // Bumper practice: no opening shot; start with red's marked ball.
            if (bumperPractice) { match.isBreak = false; match.current = 0 }
        }
        if (tutorialMode) {
            Tutorial.setup(match)
            match.message = ""
            tut = Tutorial()
            tutT = 0.0
        }
        if (trickPractice) {
            match.trick.level = trickLevel.coerceIn(0, Trick.LEVELS.size - 1)
            match.setupTrickLevel()
            match.message = "Practice · ${Trick.LEVELS[match.trick.level].name}"
            tpTries = 0; tpMade = 0
        }
        sinks.clear()
        potN = 0
        spinX = 0.0; spinY = 0.0; power = 0.0; elevDeg = 0.0
        gameOverTitle = ""; recordLine = ""
        bannerLevel = -1; bannerT = 0.0
        rpsRounds = 0
        message = match.message
        // Trick shots don't need rock, paper, scissors (who goes first doesn't matter), nor does
        // bumper pool (both players open at the same moment). Cutthroat's break goes round the three players.
        if (cutthroat && !practice) {
            match.setBreaker(breaker)
            message = "${names[breaker]} ${if (breaker == 0 && vsCpu) "break" else "breaks"} · the break goes round, one rack each"
            match.message = message
            startTurn()
        } else if (practice || trick || bumper) startTurn() else startRps(false)
        if (tutorialMode) aimAngle = Tutorial.lineUp(match.world)
    }

    // ------------------------------------------------------------------ rock, paper, scissors

    private fun startRps(tie: Boolean) {
        phase = Phase.RPS
        rps = Rps.PICK
        rpsPick[0] = -1; rpsPick[1] = -1
        rpsT = 0.0; rpsBeat = 0
        rpsTie = tie
        clearTouches()
        message = if (tie) "Tie — go again!" else "Rock, paper, scissors — the winner ${if (trick) "goes first" else "breaks"}"
    }

    private fun rpsChoose(player: Int, choice: Int) {
        if (rps != Rps.PICK || rpsPick[player] >= 0) return
        rpsPick[player] = choice
        sfx.ball(1.2)
        if (vsCpu) rpsPick[1] = rng.nextInt(3)
        if (rpsPick[0] >= 0 && rpsPick[1] >= 0) { rps = Rps.SHAKE; rpsT = 0.0; rpsBeat = 0 }
    }

    /** 0 or 1 for the player who won, -1 for a tie. Rock beats scissors, scissors paper, paper rock. */
    private fun rpsWinner(): Int {
        val a = rpsPick[0]; val b = rpsPick[1]
        return when { a == b -> -1; (a - b + 3) % 3 == 1 -> 0; else -> 1 }
    }

    private fun rpsDone() {
        val w = rpsWinner()
        if (w < 0) { startRps(true); return }
        host.onRpsFinished(w, rpsRounds)
        breaker = w
        match.setBreaker(w)
        message = match.message
        startTurn()
    }

    fun restart() = newGame(breaker)

    /** The game just finished: whether you (player 1) are on the winning side. */
    fun youWon() = match.won(0)

    /** The winner's seat (0 to 3), or in team play the winning team (0 red & white, 1 blue & yellow). */
    fun winningSeat() = if (match.teams) match.teamOf(match.winner) else match.winner

    fun isPractice() = practiceMode

    private fun settings() = GameSettings(mode, vsCpu, cpuLevel, shotClock, practiceMode, breaker, match.callMode, tableSize, rulesPractice,
        octPlayers, octTeams, octLayout, match.scratchKitchen, match.trick.set)

    private fun gameName() = when (mode) {
        GameMode.EIGHT_BALL -> "8-Ball"; GameMode.NINE_BALL -> "9-Ball"; GameMode.CIRCUIT -> "Circuit Pool"
        GameMode.POCKET_WARS -> "Pocket Wars"; GameMode.BUMPER -> "Bumper pool"; GameMode.TRICK -> "Trick shots"
        GameMode.CUTTHROAT -> "Cutthroat"; GameMode.OCTAGON -> "Octagon bumper"
    }

    private fun useTable() {
        when (mode) {
            GameMode.TRICK -> {}
            GameMode.BUMPER -> Table.useBumper()
            GameMode.OCTAGON -> Table.useOctagon(octLayout)
            else -> Table.useStandard(tableSize)
        }
    }

    private fun resumeGame(text: String): Boolean {
        val st = GameSave.readSettings(text) ?: return false
        useTable()
        val m = Match(mode, names)
        if (!GameSave.restore(text, m)) return false
        generation++
        breaker = st.breaker
        match = m
        match.world.trackOrientation = true
        match.world.listener = makeListener()
        sinks.clear()
        potN = 0
        spinX = 0.0; spinY = 0.0; power = 0.0; elevDeg = 0.0
        gameOverTitle = ""; recordLine = ""
        revealTo = if (vsCpu || practiceMode) 0 else -1
        message = when {
            rulesPractice -> "Practice resumed · " + match.message
            practice -> practiceHint()
            trickPractice -> "Practice · ${Trick.LEVELS[match.trick.level].name}"
            else -> "Game resumed · " + match.message
        }
        bannerLevel = -1
        startTurn()
        return true
    }

    /** The game to save when leaving it, or null when it's over. A rolling shot is undone. */
    fun saveState(): String? {
        if (tutorialMode) return null
        if (match.winner >= 0 || phase == Phase.OVER || phase == Phase.RPS) return null
        return when (phase) {
            Phase.HUMAN, Phase.PAUSE, Phase.PICK -> GameSave.write(match, settings())
            else -> turnSave ?: GameSave.write(match, settings())
        }
    }

    /** Practice with the rules: the game's verdict, worded for someone playing on their own. */
    private fun practiceText(msg: String) = msg
        .replace(Regex("Opponent( \\d)? has ball in hand"), "you take ball in hand")
        .replace(Regex("Opponent( \\d)? to shoot"), "in a game your turn would end here")
        .replace("Opponent to break", "break again")

    private fun practiceHint() =
        if (bumperPractice) "${if (octagon) "Octagon" else "Bumper"} practice · tap a ball to shoot it, hold it to move it" else "Practice · hold any ball to move it"

    private fun startTurn() {
        match.prepareTurn()
        val bannerKey = if (trickPractice) match.trick.level else match.trick.levelNo
        if (trick && match.winner < 0 && bannerKey != bannerLevel) {
            bannerLevel = bannerKey
            bannerT = 3.2
        }
        if (match.winner >= 0) {
            phase = Phase.OVER
            val w = match.winner
            gameOverTitle = when {
                rulesPractice -> if (w == 0) "Rack won!" else "Rack lost"
                match.teams && vsCpu -> if (match.won(0)) "Your team wins!" else "${match.teamName(w)} win"
                match.teams -> "${match.teamName(w)} win!"
                vsCpu && w == 0 -> "You win!"
                vsCpu -> "${names[w]} wins"
                else -> "${names[w]} wins!"
            }
            message = match.message
            recordLine = host.onGameFinished(w)
            if (rulesPractice) recordLine = "$rpShots ${if (rpShots == 1) "shot" else "shots"} · $rpFouls ${if (rpFouls == 1) "foul" else "fouls"}"
            return
        }
        power = 0.0
        elevDeg = 0.0
        povLocked = false
        // Cutthroat: a ball down on an open table — the shooter picks a group first (the CPU straight away).
        val chooser = if (cutthroat) match.cut.choosing else -1
        if (chooser >= 0) {
            if (isCpu(chooser) || (rulesPractice && chooser != 0)) { cutPick(match.cutSuggestedGroup()); return }
            phase = Phase.PICK
            clearTouches()
            return
        }
        turnSave = GameSave.write(match, settings())
        if (isCpu(match.current)) {
            phase = Phase.CPU_THINK
            thinkingSince = now()
            val snapshot = Match(mode, names).also { it.copyFrom(match) }
            val gen = generation
            // Browsers run the CPU on the same thread as drawing, so keep its thinking time short.
            val budget = 250L + 60L * cpuLevel
            window.setTimeout({
                if (gen == generation) {
                    val plan = try { Ai(cpuLevel, rng.nextLong()).plan(snapshot, budget) } catch (e: dynamic) { null }
                    val wait = 600 - (now() - thinkingSince)
                    window.setTimeout({ if (gen == generation) beginCpuAnim(plan) }, max(0.0, wait))
                }
            }, 60)
        } else if (circuit && revealTo != match.current) {
            // Circuit Pool, pass & play: hide the targets while the phone changes hands.
            revealTo = -1
            phase = Phase.HANDOFF
            clearTouches()
        } else beginHumanTurn()
    }

    /** Cutthroat: the shooter takes group [g] (one nobody has), and play goes on. */
    private fun cutPick(g: Int) {
        if (match.cut.choosing < 0 || g !in match.cutPickable()) return
        val out = match.cutPick(g)
        // On your own, the two absent opponents get the other groups as soon as you've picked yours.
        if (rulesPractice) for (q in 1..2) if (match.cut.group[q] < 0) match.cut.group[q] = match.cut.free()[0]
        message = if (rulesPractice) practiceText(out.message) else out.message
        if (rulesPractice && match.winner < 0) match.current = 0
        sfx.ball(1.2)
        phase = Phase.PAUSE
        pauseT = if (out.winner >= 0) 0.6 else 0.9
    }

    private fun beginHumanTurn() {
        phase = Phase.HUMAN
        spinX = 0.0; spinY = 0.0
        clockLeft = shotClock.toDouble()
        clockStarted = !match.ballInHand
        lastBeep = 0
        aimAtNearestTarget()
    }

    private fun aimAtNearestTarget() {
        val c = match.world.cueBall
        if (bumper) {
            // Bumper pool: start out aimed at the cup you're shooting for.
            val cup = Table.pockets[match.targetCup(match.ownerOf(match.world.cue).coerceAtLeast(0))]
            aimAngle = atan2(cup.cy - c.y, cup.cx - c.x)
            return
        }
        if (trick) {
            val b = match.world.balls[Trick.TARGET]
            if (b.onTable) { aimAngle = atan2(b.y - c.y, b.x - c.x); return }
        }
        // Circuit Pool: your nearest target, if you can see them.
        val own = if (circuit && revealTo == match.current) match.circuit.targets[match.current].filter { match.world.balls[it].onTable } else emptyList()
        val t = own.ifEmpty { match.legalTargets().filter { match.world.balls[it].onTable } }
            .minByOrNull { hypot(match.world.balls[it].x - c.x, match.world.balls[it].y - c.y) } ?: return
        val b = match.world.balls[t]
        aimAngle = atan2(b.y - c.y, b.x - c.x)
    }

    private fun beginCpuAnim(plan: Plan?) {
        val p = plan ?: Plan(aimAngle, 2.0, 0.0, 0.0, "fallback")
        cpuPlan = p
        animT = 0.0
        animFromAngle = aimAngle
        if (p.ball >= 0) match.selectBall(p.ball)   // bumper pool: the ball it's going to shoot
        val c = match.world.cueBall
        animFromX = c.x; animFromY = c.y
        if (p.hasPlacement && !match.placeCue(p.placeX, p.placeY)) {
            cpuPlan = Plan(p.angle, p.speed, p.side, p.vert, p.kind, elevation = p.elevation, pocket = p.pocket, ball = p.ball)
        }
        if (match.callAllowed()) {
            match.calledPocket = when {
                p.pocket >= 0 -> p.pocket
                match.callRequired() -> nearestPocketToAim()
                else -> -1
            }
        }
        phase = Phase.CPU_ANIM
    }

    private fun nearestPocketToAim(): Int {
        match.world.castCue(aimAngle, hit)
        val id = hit[3].toInt()
        val b = if (hit[0] == 1.0 && id > 0) match.world.balls[id] else match.world.cueBall
        return match.world.nearestPocket(b.x, b.y)
    }

    private fun speedFor(pw: Double) = 0.25 + (P.MAX_CUE_SPEED - 0.25) * pw.pow(1.6)
    private fun powerFor(speed: Double) = ((speed - 0.25) / (P.MAX_CUE_SPEED - 0.25)).coerceIn(0.0, 1.0).pow(1 / 1.6)

    private fun shoot(angle: Double, speed: Double, side: Double, vert: Double, elev: Double) {
        if (match.openingPending()) { openingShot(angle, speed, side, vert, elev); return }
        if (practice) practiceSnapshot = World().also { it.copyFrom(match.world) }
        if (rulesPractice) practiceMatch = Match(mode, names).also { it.copyFrom(match) }
        turnSave = GameSave.write(match, settings())
        sfx.strike(speed)
        match.shoot(angle, speed, side, vert, elev)
        lockPov()
        phase = Phase.RUNNING
        clearTouches()
    }

    /**
     * Bumper pool's opening: each player's shot is held (unseen) until the other has set theirs,
     * then both marked balls go at the same moment.
     */
    private fun openingShot(angle: Double, speed: Double, side: Double, vert: Double, elev: Double) {
        clearTouches()
        power = 0.0
        if (!match.setOpening(angle, speed, side, vert, elev)) {
            message = match.message
            phase = Phase.PAUSE; pauseT = 0.9
            return
        }
        turnSave = GameSave.write(match, settings())
        sfx.strike(max(speed, 1.0))
        match.fireOpening()
        lockPov()
        phase = Phase.RUNNING
    }

    private fun clearTouches() { for (i in ptrMode.indices) ptrMode[i] = Touch.NONE }
    private fun anyTouch(t: Touch): Boolean { for (m in ptrMode) if (m == t) return true; return false }

    private fun checkLongPress() {
        if (!practice) return
        val t = now()
        for (i in ptrMode.indices) if (ptrMode[i] == Touch.PENDING && t - ptrDownAt[i] > 280) {
            ptrMode[i] = Touch.MOVE_BALL
            movingBall = ptrBall[i]
            if (pov) lockPov()
            dragInvalid = false
            try { if (navigator.vibrate != null) navigator.vibrate(15) } catch (e: dynamic) { }
        }
    }

    private fun moveBall(id: Int, x: Double, y: Double) {
        dragX = x; dragY = y
        if (!toTable(x, y)) { dragInvalid = true; return }
        val px = povXY[0].coerceIn(P.R, P.L - P.R); val py = povXY[1].coerceIn(P.R, P.W - P.R)
        val w = match.world
        if (w.isFree(px, py, id)) { val b = w.balls[id]; b.x = px; b.y = py; b.stop(); dragInvalid = false } else dragInvalid = true
    }

    /**
     * Bumper practice: shoot ball [id] next (any ball, either colour), or with -1 the one nearest the
     * middle of the table. The guide then shows the cup that ball's owner shoots for.
     */
    private fun bumperPracticePick(id: Int) {
        val w = match.world
        val pick = if (id >= 0) id else w.balls.filter { it.onTable }
            .minByOrNull { hypot(it.x - P.L / 2, it.y - P.W / 2) }?.id ?: return
        w.cue = pick
        match.current = match.ownerOf(pick).coerceAtLeast(0)
        aimAtNearestTarget()
    }

    private fun practiceRetry() {
        val snap = practiceSnapshot ?: return
        if (phase != Phase.HUMAN && phase != Phase.RUNNING) return
        val ms = practiceMatch
        if (rulesPractice && ms != null) match.copyFrom(ms) else match.world.copyFrom(snap)
        for (b in match.world.balls) b.moving = false
        if (bumperPractice) match.current = match.ownerOf(match.world.cue).coerceAtLeast(0)
        sinks.clear(); phase = Phase.HUMAN; power = 0.0
        message = "Shot reset"
    }

    /** Trick-shot practice: go to another level (or reset this one with [delta] = 0). */
    private fun trickPracticeGo(delta: Int) {
        if (phase == Phase.CPU_THINK || phase == Phase.CPU_ANIM) return
        val n = Trick.LEVELS.size
        generation++
        if (delta != 0) {
            match.trick.level = ((match.trick.level + delta) % n + n) % n
            tpTries = 0; tpMade = 0
        }
        match.setupTrickLevel()
        sinks.clear()
        potN = 0
        power = 0.0; elevDeg = 0.0; spinX = 0.0; spinY = 0.0
        message = "Practice · ${Trick.LEVELS[match.trick.level].name}"
        startTurn()
    }

    private fun practiceClear() {
        // (Bumper practice keeps the ball you're shooting.)
        for (id in 1 until NB) if (id != match.world.cue) { val b = match.world.balls[id]; b.onTable = false; b.stop() }
        message = "Table cleared · tap a ball in the tray to bring it back"
    }

    private fun returnBall(id: Int) {
        val w = match.world
        var r = 0.0
        while (r < 0.6) {
            val steps = if (r == 0.0) 1 else 12
            for (k in 0 until steps) {
                val a = 2 * PI * k / steps
                val x = P.L / 2 + 0.25 + r * cos(a); val y = P.W / 2 + r * sin(a)
                if (w.isFree(x, y, id)) {
                    val b = w.balls[id]; b.x = x; b.y = y; b.stop(); b.onTable = true
                    message = "Hold the ball to move it"
                    if (bumperPractice && !w.cueBall.onTable) bumperPracticePick(id)
                    return
                }
            }
            r += 0.06
        }
    }

    private fun inTray(id: Int): Boolean { for (i in 0 until potN) if (potIds[i] == id) return true; return false }

    private fun addToTray(id: Int) {
        if (id <= 0 || potN >= potIds.size || inTray(id)) return
        potIds[potN] = id
        potY[potN] = trayRect.t - potR * 2      // rolls in from the top of the tray
        potV[potN] = 0.0
        potN++
    }

    private fun traySlotY(i: Int) = trayRect.b - 3 - potR - i * potR * 2.06

    /** Keeps the tray in step with the table, then lets the balls roll down to their places. */
    private fun updateTray(dt: Double) {
        val w = match.world
        var j = 0
        for (i in 0 until potN) if (!w.balls[potIds[i]].onTable) {
            potIds[j] = potIds[i]; potY[j] = potY[i]; potV[j] = potV[i]; j++
        }
        potN = j
        // (Not in the tutorial: its table simply has fewer balls; only the ones you pot go in the tray.)
        if (phase != Phase.RUNNING && !tutorialMode) for (id in match.ballIds()) if (!w.balls[id].onTable) addToTray(id)
        val g = 2200.0
        for (i in 0 until potN) {
            val target = traySlotY(i)
            if (potY[i] < target || potV[i] < 0) {
                potV[i] += g * dt
                potY[i] += potV[i] * dt
                if (potY[i] >= target) {
                    potY[i] = target
                    potV[i] = if (potV[i] > 160) -potV[i] * 0.22 else 0.0
                }
            } else { potY[i] = target; potV[i] = 0.0 }
        }
    }

    fun update(dt: Double) {
        updateTutorial(dt)
        if (bannerT > 0) bannerT -= dt
        if (sinks.isNotEmpty()) {
            for (s in sinks) s.age += dt
            sinks.removeAll { it.age > 0.35 }
        }
        updateTray(dt)
        when (phase) {
            Phase.RPS -> when (rps) {
                Rps.SHAKE -> {
                    rpsT += dt
                    val beat = (rpsT / RPS_BEAT).toInt()
                    if (beat > rpsBeat) { rpsBeat = beat; if (beat < 3) sfx.rail(2.2) }
                    if (rpsT >= 3 * RPS_BEAT) { rps = Rps.REVEAL; rpsT = 0.0; rpsRounds++; sfx.ball(3.0) }
                }
                Rps.REVEAL -> { rpsT += dt; if (rpsT >= 2.4) rpsDone() }
                else -> {}
            }
            Phase.HUMAN -> {
                checkLongPress()
                if (!practiceMode && shotClock > 0 && !menuOpen && clockStarted) {
                    clockLeft -= dt
                    val secs = ceil(clockLeft).toInt()
                    if (secs in 1..5 && secs != lastBeep) { lastBeep = secs; sfx.beep() }
                    if (clockLeft <= 0) {
                        clearTouches(); power = 0.0
                        if (match.openingPending()) openingShot(aimAngle, 0.0, 0.0, 0.0, 0.0)   // time's up: no opening shot
                        else {
                            message = match.timeFoul().message
                            phase = Phase.PAUSE; pauseT = 1.0
                        }
                    }
                }
            }
            Phase.RUNNING -> {
                match.world.advance(dt)
                if (!match.world.anyMoving()) {
                    if (rulesPractice) {
                        // Judged as in a game, but the next shot is yours whatever happened.
                        val out = match.resolve()
                        rpShots++
                        if (out.foul) rpFouls++
                        // Cutthroat on your own: with your group gone, the rack's lost.
                        if (cutthroat && match.winner < 0 && match.cutOut(0)) match.winner = match.cutNext(0)
                        match.current = 0
                        message = practiceText(out.message)
                        phase = Phase.PAUSE
                        pauseT = if (out.foul || out.winner >= 0) 0.9 else 0.45
                        if (out.heckle) window.setTimeout({ sfx.heckle() }, 260)
                    } else if (bumperPractice) {
                        // (On the octagon table a ball flying off a ramp is just physics; only the cue's jump counts.)
                        val bit = 1 shl match.world.cue
                        val jumped = ((if (octagon) match.world.log.cueJumps or match.world.log.scoops else match.world.log.air) and bit) != 0
                        // The ball you shot went down: carry on with the nearest one left.
                        if (!match.world.cueBall.onTable) bumperPracticePick(-1)
                        message = when {
                            match.world.balls.none { it.onTable } -> "All in! Re-rack, or tap a ball in the tray"
                            jumped -> "That one left the cloth — in a game, a jump is a foul"
                            else -> ""
                        }
                        phase = Phase.HUMAN; power = 0.0; povLocked = false
                    } else if (practice) {
                        tut?.onShotDone(!match.world.balls[Tutorial.TARGET_BALL].onTable)
                        message = if (!match.world.cueBall.onTable) { match.respawnCue(); "Cue ball respotted · hold it to move it" }
                            else if (match.world.log.scooped) "Scoop shot — in a game that's a foul" else ""
                        phase = Phase.HUMAN; power = 0.0; povLocked = false
                    } else if (trickPractice) {
                        val out = match.resolveTrickPractice()
                        tpTries++
                        if (out.made) tpMade++
                        message = out.message
                        phase = Phase.PAUSE
                        pauseT = if (out.made) 1.6 else 1.3
                        if (out.heckle) window.setTimeout({ sfx.heckle() }, 260)
                    } else {
                        val out = match.resolve()
                        message = out.message
                        phase = Phase.PAUSE
                        pauseT = if (trick) (if (out.made) 1.6 else 1.3) else if (out.foul || out.winner >= 0) 0.9 else 0.45
                        if (out.heckle) window.setTimeout({ sfx.heckle() }, 260)
                    }
                }
            }
            Phase.PAUSE -> { pauseT -= dt; if (pauseT <= 0) startTurn() }
            Phase.CPU_ANIM -> {
                val p = cpuPlan ?: return
                animT += dt
                val tAim = if (p.hasPlacement) 0.45 else 0.0
                val aimDur = 0.75; val pullDur = 0.55
                if (animT >= tAim) {
                    val k = ((animT - tAim) / aimDur).coerceIn(0.0, 1.0)
                    val e = k * k * (3 - 2 * k)
                    var d = p.angle - animFromAngle
                    while (d > PI) d -= 2 * PI
                    while (d < -PI) d += 2 * PI
                    aimAngle = animFromAngle + d * e
                    spinX = p.side / P.TIP_LIMIT * e
                    spinY = -p.vert / P.TIP_LIMIT * e
                    elevDeg = p.elevation * 180 / PI * e
                }
                if (animT >= tAim + aimDur) power = powerFor(p.speed) * ((animT - tAim - aimDur) / pullDur).coerceIn(0.0, 1.0)
                if (animT >= tAim + aimDur + pullDur + 0.12) {
                    cpuPlan = null
                    shoot(p.angle, p.speed, p.side, p.vert, p.elevation)
                }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ layout

    // ------------------------------------------------------------------ tutorial

    private fun updateTutorial(dt: Double) {
        val t = tut ?: return
        t.update(dt)
        tutT += dt
        if (t.takeEntered()) {
            tutT = 0.0
            when (t.step) {
                Tutorial.Step.POWER -> {
                    // Line up the shot on the 1 (the cue swings round to it), with no spin or jump.
                    spinX = 0.0; spinY = 0.0; elevDeg = 0.0; power = 0.0
                    tutAimFrom = aimAngle; tutAimTo = Tutorial.lineUp(match.world); tutAimK = 0.0
                }
                Tutorial.Step.BALL_IN_HAND -> Tutorial.placeForBallInHand(match)
                Tutorial.Step.MENU -> match.ballInHand = false
                else -> {}
            }
        }
        if (tutAimK < 1.0) {
            tutAimK = (tutAimK + dt / 0.6).coerceAtMost(1.0)
            val e = tutAimK * tutAimK * (3 - 2 * tutAimK)
            var d = tutAimTo - tutAimFrom
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            aimAngle = tutAimFrom + d * e
        }
        if (t.finished) leaveTutorial()
    }

    private fun leaveTutorial() {
        if (tutLeaving) return
        tutLeaving = true
        clearTouches()
        host.onExitToMenu()
    }

    fun isTutorial() = tutorialMode

    /** The pause menu was opened (the tutorial's last step asks you to try it). */
    fun onMenuOpened() { tut?.onMenuOpened() }

    /** Which tutorial target a touch at (x, y) would use. */
    private fun regionAt(x: Double, y: Double): TutTarget {
        val c = match.world.cueBall
        return when {
            powerRect.l - 20 <= x && x <= powerRect.r + 20 && y >= powerRect.t - 30 && y <= powerRect.b + 20 -> TutTarget.POWER
            hypot(x - spinCx, y - spinCy) <= spinR * 1.2 -> TutTarget.SPIN
            x >= wheelRect.l - 6 && x <= wheelRect.r + 8 && y >= wheelRect.t - 6 && y <= wheelRect.b + 8 -> TutTarget.WHEEL
            !pov && match.ballInHand && hypot(x - sx(c.x), y - sy(c.y)) < max(rpx * 3, 28.0) -> TutTarget.CUE
            tableArea.contains(x, y) -> TutTarget.TABLE
            else -> TutTarget.NONE
        }
    }

    private fun targetOf(m: Touch) = when (m) {
        Touch.POWER -> TutTarget.POWER
        Touch.SPIN -> TutTarget.SPIN
        Touch.WHEEL -> TutTarget.WHEEL
        Touch.MOVE_CUE -> TutTarget.CUE
        Touch.AIM, Touch.TAP_BALL, Touch.POV -> TutTarget.TABLE
        else -> TutTarget.NONE
    }

    /** Sets [tutShape] to the highlighted control's box ([tutRound]: it's a circle). Returns false for none. */
    private fun tutorialShape(t: Tutorial): Boolean {
        val pad = 6.0
        tutRound = false
        when (t.step.target) {
            TutTarget.TABLE -> if (pov) tutShape.set(tableArea.l, tableArea.t, tableArea.r, tableArea.b)
                else tutShape.set(sx(-0.04), sy(-0.04), sx(P.L + 0.04), sy(P.W + 0.04))
            TutTarget.WHEEL -> tutShape.set(wheelRect.l - pad, wheelRect.t - 22, wheelRect.r + pad, wheelRect.b + pad)
            TutTarget.SPIN -> { val r = spinR + 5; tutShape.set(spinCx - r, spinCy - r, spinCx + r, spinCy + r); tutRound = true }
            TutTarget.POWER -> tutShape.set(powerRect.l - 10, powerRect.t - 24, powerRect.r + 10, powerRect.b + 18)
            TutTarget.TRAY -> tutShape.set(trayRect.l - pad, trayRect.t - pad, trayRect.r + pad, trayRect.b + pad)
            TutTarget.CUE -> {
                val c = match.world.cueBall
                val r = max(rpx * 2.6, 22.0)
                tutShape.set(sx(c.x) - r, sy(c.y) - r, sx(c.x) + r, sy(c.y) + r); tutRound = true
            }
            TutTarget.MENU -> tutShape.set(menuBtn.l - pad, menuBtn.t - pad, menuBtn.r + pad, menuBtn.b + pad)
            TutTarget.NONE -> return false
        }
        return true
    }

    private fun addTutShape(ctx: Ctx2D) {
        if (tutRound) { ctx.moveTo(tutShape.r, tutShape.cy); ctx.arc(tutShape.cx, tutShape.cy, tutShape.w / 2, 0.0, 2 * PI); ctx.closePath() }
        else ctx.addRoundRect(tutShape.l, tutShape.t, tutShape.w, tutShape.h, 12.0)
    }

    /** Splits text into lines no wider than [maxW] (with the current font). */
    private fun wrapAll(ctx: Ctx2D, s: String, maxW: Double): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in s.split(" ")) {
            val t = if (line.isEmpty()) word else "$line $word"
            if (ctx.measureText(t).width > maxW && line.isNotEmpty()) { out += line; line = word } else line = t
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** Dims everything but the highlighted control, then draws the demo finger, the card and Skip. */
    private fun drawTutorial(ctx: Ctx2D) {
        val t = tut ?: return
        val w = width; val h = height
        val pulse = 0.5 + 0.5 * sin(now() / 220.0)
        val hasShape = tutorialShape(t)
        ctx.setLineDash(noDash)
        if (!t.rolling) {
            ctx.fillStyle = if (hasShape) "rgba(0,0,0,0.55)" else "rgba(0,0,0,0.65)"
            ctx.beginPath()
            ctx.rect(0.0, 0.0, w, h)
            if (hasShape) addTutShape(ctx)
            ctx.fill("evenodd")
        }
        if (hasShape && !t.rolling) {
            ctx.lineWidth = 2.5 + 1.5 * pulse
            ctx.strokeStyle = "rgba(242,201,76,${(170 + 85 * pulse) / 255})"
            ctx.beginPath(); addTutShape(ctx); ctx.stroke()
            if (t.flash <= 0.0) {
                var tapX = Double.NaN; var tapY = Double.NaN
                if (t.step == Tutorial.Step.POV) {
                    val one = match.world.balls[Tutorial.TARGET_BALL]
                    if (!pov && one.onTable) { tapX = sx(one.x); tapY = sy(one.y) }
                    else if (pov && t.jumpRaised) { tapX = topViewBtn.cx; tapY = topViewBtn.cy }
                }
                t.finger(tutT, tutShape.l, tutShape.t, tutShape.r, tutShape.b, tutFinger, tapX, tapY)
                val fx = tutFinger[0]; val fy = tutFinger[1]; val a = tutFinger[2]; val pr = tutFinger[3]
                val r = 15 * (1.15 - 0.25 * pr)
                ctx.fillStyle = "rgba(255,255,255,${(90 * a + 60 * a * pr) / 255})"
                ctx.circle(fx, fy, r); ctx.fill()
                ctx.lineWidth = 2.0; ctx.strokeStyle = "rgba(255,255,255,${0.9 * a})"
                ctx.circle(fx, fy, r); ctx.stroke()
                if (pr > 0.05) { ctx.fillStyle = "rgba(242,201,76,${0.8 * a * pr})"; ctx.circle(fx, fy, 5.0); ctx.fill() }
            }
        }

        // The card: over the top of the table, or in the middle for the first and last cards.
        val clothL = sx(0.0); val clothT = sy(0.0); val clothR = sx(P.L)
        val cw = min((clothR - clothL) * 0.8, 470.0)
        val pad = 16.0
        ctx.font = font(13.5, false)
        val lines = wrapAll(ctx, t.body, cw - 2 * pad)
        val lineH = 18.5
        val btnH = if (t.step.button != null) 40.0 else 0.0
        val ch = pad + 14 + 24 + lines.size * lineH + (if (btnH > 0) 10 + btnH else 0.0) + pad * 0.8
        val top = if (t.step.target == TutTarget.NONE) (hudH + h) / 2 - ch / 2 else clothT + 6
        val ccx = (clothL + clothR) / 2
        tutCard.set(ccx - cw / 2, top, ccx + cw / 2, top + ch)
        ctx.fillStyle = "rgba(22,28,33,0.95)"; ctx.roundRect(tutCard, 16.0); ctx.fill()
        ctx.lineWidth = 1.5; ctx.strokeStyle = "#F2C94C"; ctx.roundRect(tutCard, 16.0); ctx.stroke()
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        var y = tutCard.t + pad + 8
        ctx.font = font(11.0, true); ctx.fillStyle = "#F2C94C"
        val head = if (t.step == Tutorial.Step.WELCOME || t.step == Tutorial.Step.DONE) "TUTORIAL" else "STEP ${t.number} OF ${t.total}"
        ctx.fillText(head, tutCard.l + pad, y)
        y += 24
        ctx.font = font(18.0, true); ctx.fillStyle = "#FFFFFF"
        ctx.fillText(t.title, tutCard.l + pad, y)
        if (t.flash > 0.0) {
            ctx.textAlign = "right"; ctx.fillStyle = "#3CCB7F"
            ctx.fillText("✓ Got it!", tutCard.r - pad, y)
            ctx.textAlign = "left"
        }
        ctx.font = font(13.5, false); ctx.fillStyle = "#DDE3E8"
        for (l in lines) { y += lineH; ctx.fillText(l, tutCard.l + pad, y) }
        val label = t.step.button
        if (label != null) {
            ctx.font = font(16.0, true)
            val bw = max(110.0, ctx.measureText(label).width + 40)
            tutBtn.set(tutCard.r - pad - bw, tutCard.b - pad * 0.8 - btnH, tutCard.r - pad, tutCard.b - pad * 0.8)
            ctx.fillStyle = "#F2C94C"; ctx.roundRect(tutBtn, 12.0); ctx.fill()
            ctx.fillStyle = "#1A1A1A"; ctx.textAlign = "center"
            ctx.fillText(label, tutBtn.cx, tutBtn.cy + 5.5)
        } else tutBtn.set(0.0, 0.0, 0.0, 0.0)

        // Skip: always there, top right.
        if (t.step != Tutorial.Step.DONE) {
            tutSkip.set(rightCard.r - 120, rightCard.t, rightCard.r, rightCard.b)
            ctx.fillStyle = "#39434C"; ctx.roundRect(tutSkip, 10.0); ctx.fill()
            val fs = min(15.0, tutSkip.h * 0.4)
            ctx.font = font(fs, true); ctx.fillStyle = "#FFFFFF"; ctx.textAlign = "center"
            ctx.fillText("Skip tutorial", tutSkip.cx, tutSkip.cy + fs * 0.35)
        } else tutSkip.set(0.0, 0.0, 0.0, 0.0)
    }

    /** Tutorial HUD: what this is and how far along you are (Skip is drawn on top by [drawTutorial]). */
    private fun drawTutorialHud(ctx: Ctx2D) {
        val t = tut ?: return
        ctx.fillStyle = "#1E252B"; ctx.roundRect(leftCard, 10.0); ctx.fill()
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(16.0, leftCard.h * 0.32), true); ctx.fillStyle = "#FFFFFF"
        ctx.fillText("TUTORIAL", leftCard.l + 12, leftCard.t + leftCard.h * 0.42)
        val r = min(4.0, leftCard.h * 0.08)
        for (i in 1..t.total) {
            ctx.fillStyle = if (i < t.number || t.step == Tutorial.Step.DONE) "#3CCB7F" else if (i == t.number) "#F2C94C" else "#39434C"
            ctx.circle(leftCard.l + 12 + r + (i - 1) * r * 3.2, leftCard.t + leftCard.h * 0.72, r); ctx.fill()
        }
    }

    private fun sx(x: Double) = ox + x * scale
    private fun sy(y: Double) = oy + y * scale
    private fun tx(px: Double) = (px - ox) / scale
    private fun ty(py: Double) = (py - oy) / scale

    fun resize(w: Double, h: Double, devicePixelRatio: Double) {
        width = w; height = h; dpr = devicePixelRatio
        hudH = max(h * 0.13, 44.0)
        // Left: power bar, then the ball tray. Right: the spin ball, and the fine-aim wheel filling
        // the space below it. (The same layout as ControlsGuide.layout, which draws the screen map.)
        val leftW = max(w * 0.08, 58.0)
        val trayW = 28.0
        val rightW = max(w * 0.15, 96.0) + 14
        trayRect.set(leftW, hudH + 34, leftW + trayW, h - 18)
        tableArea.set(trayRect.r + 6, hudH, w - rightW, h)
        // (The octagon table's width already includes its ledge.)
        val rail = if (octagon) 0.012 else 0.115
        val tw = P.L + 2 * rail; val th = P.W + 2 * rail
        scale = min(tableArea.w / tw, (tableArea.h - 6) / th) * 0.98
        ox = tableArea.l + (tableArea.w - tw * scale) / 2 + rail * scale
        oy = tableArea.t + (tableArea.h - th * scale) / 2 + rail * scale
        rpx = P.R * scale
        balls.setRadius(rpx, dpr)

        val pw = min(leftW * 0.45, 30.0)
        powerRect.set(leftW / 2 - pw / 2, hudH + 34, leftW / 2 + pw / 2, h - 18)
        val nBalls = if (trick) 8 else match.ballIds().count()
        potR = min((trayRect.h - 6) / (nBalls * 2.06), trayW / 2 - 3)
        for (i in 0 until potN) { potY[i] = traySlotY(i); potV[i] = 0.0 }

        val colL = w - rightW
        val spinL = colL + 8; val spinRt = w - 8
        spinR = min((spinRt - spinL) * 0.42, (h - hudH) * 0.15)
        spinCx = (spinL + spinRt) / 2
        spinCy = hudH + 24 + spinR
        wheelRect.set(colL + 8, spinCy + spinR + 58, w - 10, h - 14)
        topViewBtn.set(tableArea.l + 8, tableArea.t + 8, tableArea.l + 118, tableArea.t + 44)
        resetViewBtn.set(topViewBtn.r + 8, topViewBtn.t, topViewBtn.r + 52, topViewBtn.b)
        // In the top view the 3D button sits over the top rail, between the corner and side pockets.
        // (On the octagon table, in the empty corner at the top left.)
        if (octagon) view3dBtn.set(sx(0.0), sy(0.0), sx(0.0) + 104, sy(0.0) + 30)
        else view3dBtn.set(sx(P.L * 0.11), sy(-0.1) - 15, sx(P.L * 0.11) + 104, sy(-0.1) + 15)
        povBalls.setRadius(30.0, dpr)
        menuBtn.set(8.0, 8.0, 52.0, 44.0)
        val cardW = min(w * 0.27, 260.0)
        val cardH = hudH - 16
        leftCard.set(menuBtn.r + 10, 8.0, menuBtn.r + 10 + cardW, 8 + cardH)
        rightCard.set(w - 8 - cardW, 8.0, w - 8, 8 + cardH)
        if (cutthroat && !practice) {
            // Three players: three narrower cards — player 1 on the left, players 2 and 3 on the right.
            val cw = min(w * 0.19, 200.0)
            leftCard.set(menuBtn.r + 10, 8.0, menuBtn.r + 10 + cw, 8 + cardH)
            rightCard.set(w - 8 - cw, 8.0, w - 8, 8 + cardH)
            midCard.set(rightCard.l - 8 - cw, 8.0, rightCard.l - 8, 8 + cardH)
        }
        msgL = leftCard.r + 10
        msgR = (if (cutthroat && !practice) midCard.l else rightCard.l) - 10
        if (octagon && !practice && names.size > 2) {
            // Three or four players, in the order they shoot: three cards like Cutthroat's, or two each side.
            val n = names.size
            val cw = if (n == 4) min(w * 0.155, 170.0) else min(w * 0.19, 200.0)
            leftCard.set(menuBtn.r + 10, 8.0, menuBtn.r + 10 + cw, 8 + cardH)
            rightCard.set(w - 8 - cw, 8.0, w - 8, 8 + cardH)
            if (n == 4) {
                midCard.set(leftCard.r + 8, 8.0, leftCard.r + 8 + cw, 8 + cardH)
                card4.set(rightCard.l - 8 - cw, 8.0, rightCard.l - 8, 8 + cardH)
                msgL = midCard.r + 10; msgR = card4.l - 10
            } else {
                midCard.set(rightCard.l - 8 - cw, 8.0, rightCard.l - 8, 8 + cardH)
                msgL = leftCard.r + 10; msgR = midCard.l - 10
            }
        }
        run {
            val gap = 8.0
            val left = leftCard.r + 10; val right = rightCard.l - 10
            val bw = (right - left - 2 * gap) / 3
            val bh = cardH * 0.78
            val top = leftCard.cy - bh / 2
            retryBtn.set(left, top, left + bw, top + bh)
            rackBtn.set(left + bw + gap, top, left + 2 * bw + gap, top + bh)
            clearBtn.set(left + 2 * (bw + gap), top, right, top + bh)
        }

        val bg = offscreen((w * dpr).toInt(), (h * dpr).toInt())
        val bc = ctxOf(bg)
        rebuildBackground()

        powerGrad = bc.createLinearGradient(0.0, powerRect.t, 0.0, powerRect.b).also {
            it.addColorStop(0.0, "#3CCB7F"); it.addColorStop(0.5, "#F2C94C"); it.addColorStop(1.0, "#EB5757")
        }
        spinGrad = bc.createRadialGradient(spinCx - spinR * 0.3, spinCy - spinR * 0.35, 0.0, spinCx - spinR * 0.3, spinCy - spinR * 0.35, spinR * 1.3).also {
            it.addColorStop(0.0, "#FFFFFF"); it.addColorStop(0.55, "#E9E4D4"); it.addColorStop(1.0, "#AFA997")
        }
        // The wheel is a roller turning about a horizontal axis: lit in the middle, dark top and bottom.
        wheelGrad = bc.createLinearGradient(0.0, wheelRect.t, 0.0, wheelRect.b).also {
            it.addColorStop(0.0, "#0E1215"); it.addColorStop(0.5, "#3C4750"); it.addColorStop(1.0, "#0E1215")
        }
        trayGrad = bc.createLinearGradient(trayRect.l, 0.0, trayRect.r, 0.0).also {
            it.addColorStop(0.0, "#2A1408"); it.addColorStop(0.25, "#0B0604"); it.addColorStop(0.75, "#0B0604"); it.addColorStop(1.0, "#3A1E0E")
        }
        val buttW = 0.015 * scale
        for (i in 0 until 8) stickGrads[i] = bc.createLinearGradient(0.0, -buttW, 0.0, buttW).also {
            it.addColorStop(0.0, col(stickColors[i * 2 + 1])); it.addColorStop(0.5, col(stickColors[i * 2])); it.addColorStop(1.0, col(stickColors[i * 2 + 1]))
        }
        predKey[0] = Double.NaN
    }

    private var bgVersion = -1

    /** The table is drawn once into an offscreen canvas, and again when its layout changes (a level with a hole). */
    private fun rebuildBackground() {
        if (width <= 0) return
        val bg = offscreen((width * dpr).toInt(), (height * dpr).toInt())
        val bc = ctxOf(bg)
        bc.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
        drawBackground(bc, width, height, 0.115)
        background = bg
        bgVersion = Table.version
    }

    /** How high ball [b] is off the surface under it (on the octagon table's ramps, above the ramp). */
    private fun airOf(b: Ball): Double =
        if (octagon) (b.z - Table.surfaceZ(b.x, b.y)).coerceAtLeast(0.0).let { if (it < 0.002) 0.0 else it } else b.z

    private fun drawBackground(c: Ctx2D, w: Double, h: Double, rail: Double) {
        val g0 = c.createLinearGradient(0.0, 0.0, 0.0, h)
        g0.addColorStop(0.0, "#1A2026"); g0.addColorStop(1.0, "#0D1013")
        c.fillStyle = g0; c.fillRect(0.0, 0.0, w, h)
        if (Table.octagon) {
            OctagonPainter.draw(c, sx(P.L / 2), sy(P.W / 2), scale, Table.octLayout, BumperColors.cupRings(true, names.size))
            return
        }

        val l = sx(-rail); val t = sy(-rail); val r = sx(P.L + rail); val b = sy(P.W + rail)
        val corner = 0.06 * scale
        c.fillStyle = "rgba(0,0,0,0.4)"
        c.roundRect(l + 3, t + 6, r - l, b - t + 2, corner); c.fill()
        val wood = c.createLinearGradient(l, t, r, b)
        wood.addColorStop(0.0, "#6B3A1C"); wood.addColorStop(0.33, "#4A2610"); wood.addColorStop(0.66, "#5E3218"); wood.addColorStop(1.0, "#3C1E0C")
        c.fillStyle = wood; c.roundRect(l, t, r - l, b - t, corner); c.fill()
        val gr = Random(3)
        c.lineWidth = 1.0
        repeat(70) {
            c.strokeStyle = if (gr.nextBoolean()) "rgba(255,255,255,0.09)" else "rgba(0,0,0,0.13)"
            val inset = gr.nextDouble(0.01, rail - 0.045)
            c.roundRect(sx(-rail + inset), sy(-rail + inset), (P.L + 2 * (rail - inset)) * scale, (P.W + 2 * (rail - inset)) * scale, corner)
            c.stroke()
        }
        c.strokeStyle = "rgba(255,255,255,0.2)"; c.lineWidth = 1.5
        c.roundRect(l + 1, t + 1, r - l - 2, b - t - 2, corner); c.stroke()

        val cush = 0.045
        c.fillStyle = "#14583A"
        c.fillRect(sx(-cush), sy(-cush), (P.L + 2 * cush) * scale, (P.W + 2 * cush) * scale)
        val cloth = c.createRadialGradient(sx(P.L / 2), sy(P.W / 2), 0.0, sx(P.L / 2), sy(P.W / 2), P.L * 0.62 * scale)
        cloth.addColorStop(0.0, "#22935A"); cloth.addColorStop(0.6, "#1C7E4C"); cloth.addColorStop(1.0, "#145F38")
        c.fillStyle = cloth
        c.fillRect(sx(0.0), sy(0.0), P.L * scale, P.W * scale)
        val sh1 = c.createLinearGradient(0.0, sy(0.0), 0.0, sy(0.025))
        sh1.addColorStop(0.0, "rgba(0,0,0,0.33)"); sh1.addColorStop(1.0, "rgba(0,0,0,0)")
        c.fillStyle = sh1; c.fillRect(sx(0.0), sy(0.0), P.L * scale, 0.025 * scale)
        val sh2 = c.createLinearGradient(sx(0.0), 0.0, sx(0.025), 0.0)
        sh2.addColorStop(0.0, "rgba(0,0,0,0.27)"); sh2.addColorStop(1.0, "rgba(0,0,0,0)")
        c.fillStyle = sh2; c.fillRect(sx(0.0), sy(0.0), 0.025 * scale, P.W * scale)

        // Trick-shot table: the second quarter is cut out. The floor shows through the gap, the
        // cut edges of the slate and rails face each other across it, and each side throws a shadow.
        val gl = sx(Table.GAP_X0); val gr2 = sx(Table.GAP_X1)
        if (Table.gap) {
            c.fillStyle = g0
            c.fillRect(gl, t - 12, gr2 - gl, b - t + 24)
            val s1 = c.createLinearGradient(gl, 0.0, gl + 34, 0.0)
            s1.addColorStop(0.0, "rgba(0,0,0,0.67)"); s1.addColorStop(1.0, "rgba(0,0,0,0)")
            c.fillStyle = s1; c.fillRect(gl, t + 6, 34.0, b - t + 2)
            val s2 = c.createLinearGradient(gr2, 0.0, gr2 - 18, 0.0)
            s2.addColorStop(0.0, "rgba(0,0,0,0.47)"); s2.addColorStop(1.0, "rgba(0,0,0,0)")
            c.fillStyle = s2; c.fillRect(gr2 - 18, t + 6, 18.0, b - t + 2)
            val edge = 5.0
            for (side in 0..1) {
                val x0 = if (side == 0) gl else gr2 - edge
                c.fillStyle = "#3C1E0C"; c.fillRect(x0, t, edge, b - t)
                c.fillStyle = "#2C3034"; c.fillRect(x0, sy(-0.045), edge, (P.W + 0.09) * scale)
                c.fillStyle = "#145F38"
                c.fillRect(if (side == 0) x0 else x0 + edge - 1.5, sy(-0.045), 1.5, (P.W + 0.09) * scale)
            }
            c.strokeStyle = "rgba(255,255,255,0.19)"; c.lineWidth = 1.0
            c.line(gl - 1.5, sy(0.0), gl - 1.5, sy(P.W))
        }

        // Pockets: the hole is exactly the drop zone, outlined in a bright ring.
        c.save()
        if (Table.gap) {
            // Keep the pocket holes off the floor in the gap.
            c.beginPath(); c.rect(0.0, 0.0, gl, h); c.rect(gr2, 0.0, w - gr2, h); c.clip()
        }
        for ((pi, pk) in Table.pockets.withIndex()) {
            val pr = pk.capR * scale
            if (Table.bumper) {
                // Bumper pool: each cup is ringed in the colour of the player who shoots into it.
                c.strokeStyle = if (pi == 1) "#C62828" else "#EDE6D2"; c.lineWidth = 4.0
                c.circle(sx(pk.cx), sy(pk.cy), pr + 5); c.stroke()
            }
            if (pk.hole) {
                // A cup cut into the cloth, like a golf hole.
                val cup = c.createRadialGradient(sx(pk.cx) - pr * 0.2, sy(pk.cy) - pr * 0.25, 0.0, sx(pk.cx), sy(pk.cy), pr)
                cup.addColorStop(0.0, "#050505"); cup.addColorStop(0.7, "#101010"); cup.addColorStop(1.0, "#2A2A2A")
                c.fillStyle = cup; c.circle(sx(pk.cx), sy(pk.cy), pr); c.fill()
                c.strokeStyle = "#F2E3B3"; c.lineWidth = 2.0; c.circle(sx(pk.cx), sy(pk.cy), pr); c.stroke()
                c.strokeStyle = "rgba(0,0,0,0.35)"; c.lineWidth = 3.0; c.circle(sx(pk.cx), sy(pk.cy), pr + 3); c.stroke()
                continue
            }
            val hole = c.createRadialGradient(sx(pk.cx), sy(pk.cy), 0.0, sx(pk.cx), sy(pk.cy), pr)
            hole.addColorStop(0.0, "#000"); hole.addColorStop(0.8, "#000"); hole.addColorStop(1.0, "#141414")
            c.fillStyle = hole; c.circle(sx(pk.cx), sy(pk.cy), pr); c.fill()
            c.strokeStyle = "#F2E3B3"; c.lineWidth = 2.0
            c.circle(sx(pk.cx), sy(pk.cy), pr); c.stroke()
        }
        c.restore()
        c.strokeStyle = "#0E4A2D"; c.lineWidth = 2.0; c.lineCap = "round"
        for (sg in Table.segs) c.line(sx(sg.ax), sy(sg.ay), sx(sg.bx), sy(sg.by))
        // Bumpers: a black rubber ring with a red band and a metal cap, seen from above.
        for (pt in Table.posts) {
            val px = sx(pt.x); val py = sy(pt.y); val pr = pt.r * scale
            c.fillStyle = "rgba(0,0,0,0.35)"; c.circle(px + pr * 0.2, py + pr * 0.32, pr * 1.08); c.fill()
            c.fillStyle = "#1C1C1C"; c.circle(px, py, pr); c.fill()
            c.fillStyle = "#8E1A1A"; c.circle(px, py, pr * 0.8); c.fill()
            c.fillStyle = "#1C1C1C"; c.circle(px, py, pr * 0.62); c.fill()
            val cap = c.createRadialGradient(px - pr * 0.2, py - pr * 0.25, 0.0, px, py, pr * 0.5)
            cap.addColorStop(0.0, "#F4F6F8"); cap.addColorStop(1.0, "#8D949B")
            c.fillStyle = cap; c.circle(px, py, pr * 0.5); c.fill()
        }

        c.fillStyle = "#EDE3C8"
        val dOff = rail * 0.55
        val dr = 0.012 * scale
        fun diamond(x: Double, y: Double) {
            c.beginPath(); c.moveTo(x, y - dr); c.lineTo(x + dr * 0.6, y); c.lineTo(x, y + dr); c.lineTo(x - dr * 0.6, y); c.closePath(); c.fill()
        }
        for (i in 1..7) if (i != 4 && !(Table.gap && i in 2..3)) { val x = P.L * i / 8; diamond(sx(x), sy(-dOff)); diamond(sx(x), sy(P.W + dOff)) }
        for (i in 1..3) {
            val y = P.W * i / 4
            diamond(sx(-dOff), sy(y))
            if (!(Table.gap && i == 2)) diamond(sx(P.L + dOff), sy(y))
        }
        if (Table.gap || Table.bumper) return
        c.strokeStyle = "rgba(255,255,255,0.19)"; c.lineWidth = 1.0
        c.setLineDash(arrayOf(4.0, 6.0)); c.line(sx(P.HEAD_X), sy(0.0), sx(P.HEAD_X), sy(P.W)); c.setLineDash(noDash)
        c.fillStyle = "rgba(255,255,255,0.33)"
        c.circle(sx(P.FOOT_X), sy(P.FOOT_Y), 2.5); c.fill()
        c.circle(sx(P.HEAD_X), sy(P.FOOT_Y), 2.5); c.fill()
    }

    // ------------------------------------------------------------------ drawing

    fun draw(ctx: Ctx2D) {
        if (bgVersion != Table.version) rebuildBackground()
        ctx.drawImage(background, 0.0, 0.0, width, height)
        val w = match.world
        val humanTurn = phase == Phase.HUMAN
        if (pov) {
            drawPov(ctx)
            drawControls(ctx)
            return
        }

        if (humanTurn && match.ballInHand && match.kitchenOnly) {
            val zones = if (trick) Trick.LEVELS[match.trick.level].zones else emptyList()
            if (zones.isEmpty()) {
                ctx.fillStyle = "rgba(255,255,255,0.13)"
                ctx.fillRect(sx(0.0), sy(0.0), P.HEAD_X * scale, P.W * scale)
            } else {
                // Start zones: where the cue ball's centre may go (drawn a ball's width bigger).
                ctx.fillStyle = "rgba(255,255,255,0.2)"
                for (z in zones) { ctx.roundRect(sx(z[0] - P.R), sy(z[1] - P.R), (z[2] - z[0] + 2 * P.R) * scale, (z[3] - z[1] + 2 * P.R) * scale, rpx); ctx.fill() }
            }
        }
        if (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) drawGuide(ctx)

        val cue = w.cueBall
        var cueX = cue.x; var cueY = cue.y
        val plan = cpuPlan
        if (phase == Phase.CPU_ANIM && plan != null && plan.hasPlacement && animT < 0.45) {
            val k = (animT / 0.45).coerceIn(0.0, 1.0)
            cueX = animFromX + (plan.placeX - animFromX) * k
            cueY = animFromY + (plan.placeY - animFromY) * k
        }
        // Shadows stay on the cloth, right under the ball. A ball in the air casts a smaller, fainter one;
        // a ball dropping into the gap has no cloth under it, so no shadow.
        for (b in w.balls) if (b.onTable) {
            val bx = if (b.id == w.cue) cueX else b.x; val by = if (b.id == w.cue) cueY else b.y
            if (b.z < 0.0) continue
            val h = (airOf(b) / 0.12).coerceIn(0.0, 1.0)
            balls.drawShadow(ctx, sx(bx), sy(by), 1 - 0.35 * h, 1 - 0.55 * h)
        }
        for (s in sinks) {
            val k = 1 - s.age / 0.35
            balls.draw(ctx, w.balls[s.id], sx(s.x), sy(s.y), 0.55 + 0.45 * k, k.coerceIn(0.0, 1.0))
        }
        for (b in w.balls) if (b.onTable && b.z < 0.0) {
            // Falling into the gap: shrinks as it drops away.
            balls.draw(ctx, b, sx(b.x), sy(b.y), (1 + b.z * 6).coerceAtLeast(0.45))
        }
        for (b in w.balls) if (b.onTable && b.z >= 0.0 && airOf(b) == 0.0) {
            val bx = if (b.id == w.cue) cueX else b.x; val by = if (b.id == w.cue) cueY else b.y
            // (Up an octagon table's ramp a ball is a little nearer the viewer: a little bigger.)
            if (b.z > 0.0) balls.draw(ctx, b, sx(bx), sy(by), 1 + b.z * RAMP_GROW)
            else balls.draw(ctx, b, sx(bx), sy(by))
        }
        // In the air: the height is exaggerated on screen (the distance is real) so a jump is easy to
        // see. The ball is drawn lifted toward the top of the screen and bigger, away from its shadow.
        for (b in w.balls) if (b.onTable && b.z >= 0.0 && airOf(b) > 0.0) {
            val air = airOf(b)
            val lift = air * JUMP_LIFT * scale
            balls.draw(ctx, b, sx(b.x) - lift * 0.25, sy(b.y) - lift, 1 + air * JUMP_GROW + (b.z - air) * RAMP_GROW)
        }

        ctx.setLineDash(noDash)
        if (humanTurn && match.ballInHand) {
            val pulse = 0.5 + 0.5 * sin(now() / 250.0)
            ctx.lineWidth = 2.0
            ctx.strokeStyle = "rgba(255,214,64,${(120 + 120 * pulse) / 255})"
            ctx.circle(sx(cue.x), sy(cue.y), rpx * (1.5 + 0.25 * pulse)); ctx.stroke()
            if (anyTouch(Touch.MOVE_CUE) && dragInvalid) {
                ctx.strokeStyle = "rgba(255,64,64,0.8)"; ctx.circle(dragX, dragY, rpx); ctx.stroke()
            }
        }
        if (humanTurn && movingBall >= 0) {
            val mb = w.balls[movingBall]
            ctx.lineWidth = 2.0; ctx.strokeStyle = "rgba(255,213,74,0.88)"
            ctx.circle(sx(mb.x), sy(mb.y), rpx * 1.5); ctx.stroke()
            if (dragInvalid) { ctx.strokeStyle = "rgba(255,64,64,0.8)"; ctx.circle(dragX, dragY, rpx); ctx.stroke() }
        }
        drawPocketCall(ctx)
        if (pocketWars) drawPocketOwners(ctx)
        if (circuit && humanTurn && revealTo == match.current) {
            // Your secret targets still on the table.
            ctx.lineWidth = 1.6; ctx.strokeStyle = "rgba(255,214,64,0.7)"; ctx.setLineDash(arrayOf(3.0, 4.0))
            for (id in match.circuit.targets[revealTo]) { val b = w.balls[id]; if (b.onTable) { ctx.circle(sx(b.x), sy(b.y), rpx * 1.5); ctx.stroke() } }
            ctx.setLineDash(noDash)
        }
        if (bumper && humanTurn && !practice) {
            // The other balls you could shoot instead: tap one to pick it.
            ctx.lineWidth = 1.5; ctx.strokeStyle = "rgba(255,214,64,0.6)"; ctx.setLineDash(arrayOf(3.0, 4.0))
            for (id in match.shootable()) if (id != w.cue) { val b = w.balls[id]; ctx.circle(sx(b.x), sy(b.y), rpx * 1.45); ctx.stroke() }
            ctx.setLineDash(noDash)
        }

        val showCue = (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && cue.onTable) drawCueStick(ctx, sx(cueX), sy(cueY))

        if (bannerT > 0 && trick) drawBanner(ctx)
        if (phase != Phase.RPS && phase != Phase.OVER && phase != Phase.HANDOFF && povAllowed()) {
            ctx.fillStyle = "rgba(30,37,43,0.9)"; ctx.roundRect(view3dBtn, 10.0); ctx.fill()
            ctx.lineWidth = 1.5; ctx.strokeStyle = "#F2C94C"; ctx.roundRect(view3dBtn, 10.0); ctx.stroke()
            ctx.textAlign = "center"; ctx.textBaseline = "middle"
            ctx.font = font(13.0, true); ctx.fillStyle = "#FFF"
            ctx.fillText("3D view ▶", view3dBtn.cx, view3dBtn.cy)
            ctx.textBaseline = "alphabetic"
        }
        drawControls(ctx)
    }

    /** Everything around the table: the HUD, power bar, tray, spin ball and fine-aim wheel, and overlays. */
    private fun drawControls(ctx: Ctx2D) {
        drawHud(ctx)
        drawPowerBar(ctx)
        drawTray(ctx)
        drawSpin(ctx)
        drawWheel(ctx)
        if (phase == Phase.RPS) drawRps(ctx)
        if (phase == Phase.HANDOFF) drawHandoff(ctx)
        if (phase == Phase.PICK) drawPick(ctx)
        if (phase == Phase.OVER) drawGameOver(ctx)
        drawTutorial(ctx)
    }

    /** Trick shots: the level's name and goal over the table as it starts, fading out. */
    private fun drawBanner(ctx: Ctx2D) {
        val t = match.trick
        val lv = Trick.LEVELS[t.level]
        val a = (bannerT / 0.5).coerceIn(0.0, 1.0)
        val cx = sx(P.L / 2)
        val bw = min(P.L * scale * 0.62, 420.0)
        ctx.font = font(13.0, false)
        val lines = wrap(ctx, lv.blurb, bw - 24)
        val bh = 50.0 + 18.0 * lines.size
        val top = sy(P.W * 0.16)
        ctx.save()
        ctx.globalAlpha = a
        ctx.fillStyle = "rgba(20,26,31,0.88)"
        ctx.roundRect(cx - bw / 2, top, bw, bh, 14.0); ctx.fill()
        ctx.strokeStyle = "#F2C94C"; ctx.lineWidth = 2.0; ctx.setLineDash(noDash)
        ctx.roundRect(cx - bw / 2, top, bw, bh, 14.0); ctx.stroke()
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(19.0, true); ctx.fillStyle = "#FFF"
        val head = when {
            trickPractice -> "PRACTICE · ${lv.name.uppercase()}"
            t.suddenDeath -> "SUDDEN DEATH · ${lv.name.uppercase()}"
            else -> "LEVEL ${t.levelNo + 1} OF ${Trick.levelsOf(t.set).size} · ${lv.name.uppercase()}"
        }
        ctx.fillText(ellipsize(ctx, head, bw - 20), cx, top + 27)
        ctx.font = font(13.0, false); ctx.fillStyle = "#CED6DD"
        lines.forEachIndexed { i, l -> ctx.fillText(l, cx, top + 49 + 18.0 * i) }
        ctx.restore()
    }

    /** Splits text into at most two lines that fit [maxW] (the second is shortened if it must be). */
    private fun wrap(ctx: Ctx2D, s: String, maxW: Double): List<String> {
        if (ctx.measureText(s).width <= maxW) return listOf(s)
        val words = s.split(" ")
        var first = ""
        var k = 0
        while (k < words.size) {
            val t = if (first.isEmpty()) words[k] else "$first ${words[k]}"
            if (ctx.measureText(t).width > maxW && first.isNotEmpty()) break
            first = t; k++
        }
        val rest = words.drop(k).joinToString(" ").trimStart('·', ' ')
        return if (rest.isEmpty()) listOf(first) else listOf(first.trimEnd('·', ' '), ellipsize(ctx, rest, maxW))
    }

    private fun drawPocketCall(ctx: Ctx2D) {
        if (phase != Phase.HUMAN && phase != Phase.CPU_ANIM) return
        val called = match.calledPocket
        val pulse = 0.5 + 0.5 * sin(now() / 200.0)
        ctx.setLineDash(noDash)
        if (called >= 0) {
            val pk = Table.pockets[called]
            val r = pk.capR * scale
            ctx.lineWidth = 3.0; ctx.strokeStyle = "#FFD640"
            ctx.circle(sx(pk.cx), sy(pk.cy), r + 3 + 3 * pulse); ctx.stroke()
            ctx.font = font(11.0, true); ctx.fillStyle = "#FFD640"; ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
            val below = pk.cy < P.W / 2
            ctx.fillText("CALLED", sx(pk.cx), sy(pk.cy) + (if (below) r + 20 else -r - 10))
        } else if (phase == Phase.HUMAN && match.callRequired()) {
            val flash = now() < callFlashUntil
            ctx.lineWidth = if (flash) 3.0 else 1.5
            ctx.strokeStyle = if (flash) "rgba(255,90,90,${(150 + 100 * pulse) / 255})" else "rgba(255,214,64,${(60 + 80 * pulse) / 255})"
            for (pk in Table.pockets) { ctx.circle(sx(pk.cx), sy(pk.cy), pk.capR * scale + 5); ctx.stroke() }
        }
    }

    /** Pocket Wars: each claimed pocket ringed in its owner's colour. */
    private fun drawPocketOwners(ctx: Ctx2D) {
        ctx.setLineDash(noDash); ctx.lineWidth = 4.0
        for ((i, pk) in Table.pockets.withIndex()) {
            val o = match.pockets.owner.getOrElse(i) { -1 }
            if (o < 0) continue
            ctx.strokeStyle = PW_COLORS[o]
            ctx.circle(sx(pk.cx), sy(pk.cy), pk.capR * scale + 3); ctx.stroke()
        }
    }

    private fun guideSpeed() = speedFor(if (power > 0.02) power else lastPower)

    private fun updatePrediction() {
        val c = match.world.cueBall
        val sp = guideSpeed()
        val k = doubleArrayOf(aimAngle, sp, spinX, spinY, elevDeg, c.x, c.y, match.current.toDouble(),
            generation + (if (match.ballInHand) 0.5 else 0.0))
        var same = true
        for (i in 0 until 9) if (predKey[i] != k[i]) { same = false; predKey[i] = k[i] }
        if (same) return
        Predictor.run(match.world, scratchWorld, aimAngle, sp, spinX * P.TIP_LIMIT, -spinY * P.TIP_LIMIT, toRad(elevDeg), prediction)
    }

    private fun drawGuide(ctx: Ctx2D) {
        val c = match.world.cueBall
        if (!c.onTable) return
        if (!showPrediction) {
            match.world.railPoint(aimAngle, hit)
            ctx.lineWidth = 1.7; ctx.lineCap = "round"
            ctx.setLineDash(dash); ctx.strokeStyle = "rgba(255,255,255,0.56)"
            ctx.line(sx(c.x), sy(c.y), sx(hit[0]), sy(hit[1]))
            ctx.setLineDash(noDash)
            return
        }
        updatePrediction()
        val pr = prediction
        if (pr.cueN < 2) return
        val pre = if (pr.contactIndex >= 0) pr.contactIndex else pr.cueN - 1
        ctx.lineWidth = 1.7; ctx.lineCap = "round"; ctx.lineJoin = "round"
        for (i in 0 until pre) {
            val air = pr.cueAir[i] || pr.cueAir[i + 1]
            ctx.setLineDash(if (air) noDash else dash)
            ctx.strokeStyle = if (air) "rgba(255,213,74,0.88)" else "rgba(255,255,255,0.69)"
            ctx.line(sx(pr.cueX[i].toDouble()), sy(pr.cueY[i].toDouble()), sx(pr.cueX[i + 1].toDouble()), sy(pr.cueY[i + 1].toDouble()))
        }
        ctx.setLineDash(noDash)
        if (pr.hitId >= 0) {
            val legal = practice || match.legalFirstHit(pr.hitId, pr.crossedLine(P.HEAD_X))
            val gx = sx(pr.hitX); val gy = sy(pr.hitY)
            ctx.strokeStyle = if (legal) "rgba(255,255,255,0.88)" else "rgba(255,80,80,0.88)"
            ctx.circle(gx, gy, rpx); ctx.stroke()
            if (pr.objN >= 2) {
                ctx.lineWidth = 2.4
                ctx.beginPath(); ctx.moveTo(sx(pr.objX[0].toDouble()), sy(pr.objY[0].toDouble()))
                for (i in 1 until pr.objN) ctx.lineTo(sx(pr.objX[i].toDouble()), sy(pr.objY[i].toDouble()))
                ctx.stroke()
            }
            if (pr.cueN - 1 > pre) {
                ctx.strokeStyle = "rgba(255,255,255,0.5)"; ctx.lineWidth = 1.4
                ctx.beginPath(); ctx.moveTo(sx(pr.cueX[pre].toDouble()), sy(pr.cueY[pre].toDouble()))
                for (i in pre + 1 until pr.cueN) ctx.lineTo(sx(pr.cueX[i].toDouble()), sy(pr.cueY[i].toDouble()))
                ctx.stroke()
            }
            if (!legal) {
                ctx.strokeStyle = "rgba(255,80,80,0.88)"; ctx.lineWidth = 2.4
                val r = rpx * 0.55
                ctx.line(gx - r, gy - r, gx + r, gy + r); ctx.line(gx - r, gy + r, gx + r, gy - r)
            }
        } else {
            ctx.strokeStyle = "rgba(255,255,255,0.69)"; ctx.lineWidth = 1.7
            ctx.circle(sx(pr.cueX[pr.cueN - 1].toDouble()), sy(pr.cueY[pr.cueN - 1].toDouble()), rpx); ctx.stroke()
        }
        if (pr.scratch) {
            val lx = pr.cueX[pr.cueN - 1].toDouble(); val ly = pr.cueY[pr.cueN - 1].toDouble()
            // Bumper pool: dropping into the cup you're shooting for is the point (gold), your own cup is a foul (red).
            val good = bumper && intoTargetCup(lx, ly)
            ctx.strokeStyle = if (good) "rgba(255,214,64,0.95)" else "rgba(255,80,80,0.88)"; ctx.lineWidth = 2.0
            ctx.circle(sx(lx), sy(ly), rpx * 1.3); ctx.stroke()
        }
        // Jump shots: where the cue ball first comes down. It slides smoothly as you add power.
        if (!pr.landX.isNaN()) {
            ctx.setLineDash(noDash)
            ctx.strokeStyle = "rgba(255,213,74,0.94)"; ctx.lineWidth = 2.0
            ctx.circle(sx(pr.landX), sy(pr.landY), rpx); ctx.stroke()
            ctx.fillStyle = "rgba(255,213,74,0.94)"; ctx.circle(sx(pr.landX), sy(pr.landY), 2.2); ctx.fill()
        }
    }

    /** Bumper pool: the guide's ball drops at (x, y) into the cup the current player is shooting for. */
    private fun intoTargetCup(x: Double, y: Double): Boolean {
        val pk = Table.pockets[match.targetCup(match.ownerOf(match.world.cue).coerceAtLeast(0))]
        return hypot(x - pk.cx, y - pk.cy) < pk.capR + 0.06
    }

    private fun drawCueStick(ctx: Ctx2D, cx: Double, cy: Double) {
        val s = scale
        val fore = cos(toRad(elevDeg))
        val gap = (P.R + 0.012 + power * 0.22) * s * fore
        // Bumper pool is played with a short cue (and on a small table a full-length one hangs off the screen).
        val k = if (bumper) SHORT_CUE else 1.0
        val len = 1.45 * s * fore * k
        val tipW = 0.0065 * s; val buttW = 0.015 * s
        fun halfW(x: Double) = tipW + (buttW - tipW) * ((-x - gap) / len)
        ctx.save()
        ctx.translate(cx, cy)
        ctx.rotate(aimAngle)
        ctx.save(); ctx.translate(3.0, 5 * (1 + elevDeg / 15))
        ctx.fillStyle = "rgba(0,0,0,0.33)"
        ctx.beginPath(); ctx.moveTo(-gap, -tipW); ctx.lineTo(-gap - len, -buttW); ctx.lineTo(-gap - len, buttW); ctx.lineTo(-gap, tipW); ctx.closePath(); ctx.fill()
        ctx.restore()
        for (i in 0 until 8) {
            val a = -gap - stickSegs[i] * s * fore * k; val b = -gap - stickSegs[i + 1] * s * fore * k
            ctx.fillStyle = stickGrads[i]
            ctx.beginPath(); ctx.moveTo(a, -halfW(a)); ctx.lineTo(b, -halfW(b)); ctx.lineTo(b, halfW(b)); ctx.lineTo(a, halfW(a)); ctx.closePath(); ctx.fill()
        }
        ctx.restore()
    }

    private fun font(px: Double, b: Boolean) = "${if (b) "bold " else ""}${px.toInt().coerceAtLeast(6)}px ${if (b) bold else regular}"

    private fun ellipsize(ctx: Ctx2D, s: String, maxW: Double): String {
        if (ctx.measureText(s).width <= maxW) return s
        var t = s
        while (t.length > 4 && ctx.measureText("$t…").width > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun drawHud(ctx: Ctx2D) {
        ctx.fillStyle = "#232B32"; ctx.roundRect(menuBtn, 8.0); ctx.fill()
        ctx.fillStyle = "#CED6DD"
        for (i in -1..1) { ctx.roundRect(menuBtn.cx - 9, menuBtn.cy + i * 6 - 1.2, 18.0, 2.4, 1.0); ctx.fill() }
        if (tutorialMode) { drawTutorialHud(ctx); return }
        if (practice) { drawPracticeHud(ctx); return }
        if (trickPractice) { drawTrickPracticeHud(ctx); return }
        for (p in 0 until match.players) drawPlayerCard(ctx, cardOf(p), p)
        val cardH = leftCard.h
        val midL = msgL; val midR = msgR
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.fillStyle = "#E8ECEF"; ctx.font = font(min(15.0, hudH * 0.26), false)
        val msg = when (phase) {
            Phase.HANDOFF -> "${names[match.current]}'s turn — pass it over"
            Phase.PICK -> "${names[match.cut.choosing.coerceAtLeast(0)]}: pick your group"
            Phase.CPU_THINK, Phase.CPU_ANIM -> if (message.isNotEmpty()) message else "${names[match.current]} is thinking…"
            Phase.HUMAN -> when {
                pov && match.ballInHand -> "${names[match.current]}: ball in hand — tap Top view to move the cue ball"
                trick && match.ballInHand -> "${names[match.current]}: jump the gap and sink the 1 · drag the cue ball to move it"
                match.ballInHand && match.isBreak -> "${names[match.current]}: place the cue ball behind the line, then break"
                match.ballInHand -> "${names[match.current]}: ball in hand — drag the cue ball" +
                    if (shotClock > 0 && !clockStarted) " · clock starts when you aim" else ""
                match.callRequired() && match.calledPocket < 0 -> "${names[match.current]}: call your pocket — tap the pocket you're shooting at"
                match.calledPocket >= 0 -> "Pocket called · tap it again to cancel"
                else -> message
            }
            else -> message
        }
        ctx.fillText(ellipsize(ctx, msg, midR - midL), (midL + midR) / 2, 8 + cardH * 0.42)
        val subY = 8 + cardH * 0.82
        if (phase == Phase.CPU_THINK) {
            ctx.font = font(min(12.0, hudH * 0.2), false); ctx.fillStyle = "#F2C94C"
            ctx.fillText(if (names.size > 2) "${names[match.current]} (level $cpuLevel) is thinking…" else "CPU (level $cpuLevel) is thinking…",
                (midL + midR) / 2, subY)
        } else if (trick) {
            val t = match.trick
            ctx.font = font(min(12.0, hudH * 0.2), false); ctx.fillStyle = "#F2C94C"
            val lvl = if (t.suddenDeath) "Sudden death" else "Level ${t.levelNo + 1} of ${Trick.levelsOf(t.set).size}"
            ctx.fillText(ellipsize(ctx, "$lvl · ${Trick.LEVELS[t.level].name} · fewest shots wins", midR - midL), (midL + midR) / 2, subY)
        } else if (bumper && phase == Phase.HUMAN) {
            ctx.font = font(min(12.0, hudH * 0.2), false); ctx.fillStyle = "#F2C94C"
            ctx.fillText(ellipsize(ctx, match.bumperHint(), midR - midL), (midL + midR) / 2, subY)
        } else if (pocketWars && phase == Phase.HUMAN) {
            ctx.font = font(min(12.0, hudH * 0.2), false); ctx.fillStyle = "#F2C94C"
            ctx.fillText(ellipsize(ctx, if (match.isBreak) "The break scores but claims no pockets · first to 61 wins"
                else "Open pockets are yours to claim · bank into theirs to steal", midR - midL), (midL + midR) / 2, subY)
        } else if (cutthroat && phase == Phase.HUMAN) {
            ctx.font = font(min(12.0, hudH * 0.2), false); ctx.fillStyle = "#F2C94C"
            val hint = when {
                match.isBreak -> "Break · any ball in keeps you shooting · groups are picked after the break"
                match.cutGroup(match.current) < 0 -> "Open table · sink any ball, then pick your group"
                else -> "Sink the other players' balls · last one with a ball left wins"
            }
            ctx.fillText(ellipsize(ctx, hint, midR - midL), (midL + midR) / 2, subY)
        } else if (circuit && phase == Phase.HUMAN) {
            ctx.font = font(min(12.0, hudH * 0.2), false); ctx.fillStyle = "#F2C94C"
            ctx.fillText(ellipsize(ctx, "Sink your 3 secret balls · any ball first · theirs ends your turn", midR - midL), (midL + midR) / 2, subY)
        } else if (mode == GameMode.NINE_BALL) {
            val r = min(hudH * 0.13, 9.0)
            var x = (midL + midR) / 2 - 9 * r * 2.4 / 2 + r
            for (id in 1..9) { balls.icon(ctx, id, x, subY - r * 0.4, r, !match.world.balls[id].onTable); x += r * 2.4 }
        }
    }

    /** Trick-shot practice: the level and how it's going, with buttons to change level or reset. */
    private fun drawTrickPracticeHud(ctx: Ctx2D) {
        ctx.fillStyle = "#1E252B"
        ctx.roundRect(leftCard, 10.0); ctx.fill()
        ctx.roundRect(rightCard, 10.0); ctx.fill()
        val lv = Trick.LEVELS[match.trick.level]
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(16.0, leftCard.h * 0.32), true); ctx.fillStyle = "#FFF"
        ctx.fillText(ellipsize(ctx, "PRACTICE · ${lv.name.uppercase()}", leftCard.w - 20), leftCard.l + 12, leftCard.t + leftCard.h * 0.42)
        ctx.font = font(min(11.0, leftCard.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
        val sub = if (phase == Phase.HUMAN && match.ballInHand) "Drag the cue ball to place it, then shoot" else message
        ctx.fillText(ellipsize(ctx, sub, leftCard.w - 20), leftCard.l + 12, leftCard.t + leftCard.h * 0.78)
        fun button(r: Rect, label: String) {
            ctx.fillStyle = "#2F8F5B"
            ctx.roundRect(r, 10.0); ctx.fill()
            ctx.textAlign = "center"; ctx.textBaseline = "middle"
            ctx.font = font(min(14.0, r.h * 0.38), true); ctx.fillStyle = "#FFF"
            ctx.fillText(label, r.cx, r.cy)
        }
        button(retryBtn, "◀ Level")
        button(rackBtn, "Reset")
        button(clearBtn, "Level ▶")
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(15.0, rightCard.h * 0.3), true); ctx.fillStyle = "#FFF"
        ctx.fillText("Level ${match.trick.level + 1} of ${Trick.LEVELS.size}", rightCard.l + 12, rightCard.t + rightCard.h * 0.42)
        ctx.font = font(min(11.0, rightCard.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
        val pct = if (tpTries > 0) " (${tpMade * 100 / tpTries}%)" else ""
        ctx.fillText("Made $tpMade of $tpTries$pct", rightCard.l + 12, rightCard.t + rightCard.h * 0.78)
    }

    /** Practice with the rules: one line on where the rack stands. */
    private fun practiceStatus(): String = when (mode) {
        GameMode.EIGHT_BALL -> when {
            match.isBreak -> "Break the rack"
            match.groups[0] == Group.OPEN -> "Open table · sink any ball but the 8"
            match.onEight(0) -> "Your group's down · now the 8"
            else -> "${if (match.groups[0] == Group.SOLIDS) "Solids" else "Stripes"} · ${match.remaining(match.groups[0])} left, then the 8"
        }
        GameMode.NINE_BALL -> if (match.isBreak) "Break the rack" else "Shoot the ${match.lowestBall()}" +
            if (match.consecutiveFouls[0] > 0) " · ${match.consecutiveFouls[0]} foul${if (match.consecutiveFouls[0] > 1) "s" else ""} in a row" else ""
        GameMode.CIRCUIT -> "Yours: ${match.circuit.targets[0].joinToString(" · ")} (${match.circuitSunk(0)}/3) · 3 hidden: ${match.circuitSunk(1)}/3"
        GameMode.CUTTHROAT -> when {
            match.isBreak -> "Break the rack"
            match.cutGroup(0) < 0 -> "Open table · sink a ball, then pick your group"
            else -> "Yours: ${CutthroatState.groupName(match.cutGroup(0))} (${match.cutLeft(0)} left) · " +
                (1..2).joinToString(" · ") { "${CutthroatState.groupName(match.cutGroup(it))}: ${match.cutLeft(it)}" }
        }
        GameMode.POCKET_WARS -> "${match.pockets.score[0]} points · ${match.pockets.pocketsOf(0)} " +
            "pocket${if (match.pockets.pocketsOf(0) == 1) "" else "s"} · ${maxOf(0, 61 - match.pockets.score[0])} to go"
        else -> ""
    }

    private fun drawPracticeHud(ctx: Ctx2D) {
        ctx.fillStyle = "#1E252B"
        ctx.roundRect(leftCard, 10.0); ctx.fill()
        ctx.roundRect(rightCard, 10.0); ctx.fill()
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(16.0, leftCard.h * 0.32), true); ctx.fillStyle = "#FFF"
        val title = when {
            rulesPractice -> "${gameName().uppercase()} PRACTICE"
            bumperPractice -> if (octagon) "OCTAGON PRACTICE" else "BUMPER PRACTICE"
            else -> "PRACTICE"
        }
        ctx.fillText(ellipsize(ctx, title, leftCard.w - 20), leftCard.l + 12, leftCard.t + leftCard.h * 0.42)
        ctx.font = font(min(11.0, leftCard.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
        val sub = when {
            rulesPractice && phase == Phase.HUMAN && match.ballInHand && match.isBreak -> "Place the cue ball behind the line, then break"
            rulesPractice && phase == Phase.HUMAN && match.ballInHand -> "Ball in hand — drag the cue ball" + if (message.isNotEmpty()) " · $message" else ""
            message.isNotEmpty() -> message
            bumperPractice -> "Tap a ball to shoot it · hold it to move it"
            else -> "Hold any ball to move it"
        }
        ctx.fillText(ellipsize(ctx, sub, leftCard.w - 20), leftCard.l + 12, leftCard.t + leftCard.h * 0.78)
        fun button(r: Rect, label: String, enabled: Boolean) {
            ctx.fillStyle = if (enabled) "#2F8F5B" else "#2A3238"
            ctx.roundRect(r, 10.0); ctx.fill()
            ctx.textAlign = "center"; ctx.textBaseline = "middle"
            ctx.font = font(min(14.0, r.h * 0.38), true)
            ctx.fillStyle = if (enabled) "#FFF" else "#6B7780"
            ctx.fillText(label, r.cx, r.cy)
        }
        button(retryBtn, "Retry shot", practiceSnapshot != null)
        button(rackBtn, "Re-rack", true)
        button(clearBtn, "Clear table", !rulesPractice)
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        if (rulesPractice) {
            // How the rack is going, and how many shots it's taken.
            ctx.font = font(min(13.0, rightCard.h * 0.26), true); ctx.fillStyle = "#FFF"
            ctx.fillText(ellipsize(ctx, practiceStatus(), rightCard.w - 20), rightCard.l + 12, rightCard.t + rightCard.h * 0.42)
            ctx.font = font(min(11.0, rightCard.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
            ctx.fillText("$rpShots ${if (rpShots == 1) "shot" else "shots"} · $rpFouls ${if (rpFouls == 1) "foul" else "fouls"}",
                rightCard.l + 12, rightCard.t + rightCard.h * 0.78)
            return
        }
        // Balls off the table sit in the tray on the left: tap one there to bring it back.
        ctx.font = font(min(11.0, rightCard.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
        ctx.fillText(ellipsize(ctx, if (potN == 0) "All balls on the table" else "Tap a ball in the tray (left) to put it back", rightCard.w - 20),
            rightCard.l + 12, rightCard.t + rightCard.h * 0.42)
    }

    /** Player [p]'s card at the top of the screen: the cards go in the order the players shoot. */
    private fun cardOf(p: Int): Rect = when {
        octagon && names.size == 4 -> when (p) { 0 -> leftCard; 2 -> midCard; 1 -> card4; else -> rightCard }
        octagon && names.size == 3 -> when (p) { 0 -> leftCard; 2 -> midCard; else -> rightCard }
        cutthroat -> when (p) { 0 -> leftCard; 1 -> midCard; else -> rightCard }
        else -> if (p == 0) leftCard else rightCard
    }

    private fun drawPlayerCard(ctx: Ctx2D, r: Rect, player: Int) {
        val active = match.current == player && phase != Phase.OVER && phase != Phase.RPS
        ctx.fillStyle = if (active) "#2B3A33" else "#1E252B"
        ctx.roundRect(r, 10.0); ctx.fill()
        if (active) { ctx.strokeStyle = "#F2C94C"; ctx.lineWidth = 2.0; ctx.roundRect(r, 10.0); ctx.stroke() }
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(16.0, r.h * 0.32), true); ctx.fillStyle = "#FFF"
        ctx.fillText(ellipsize(ctx, if (isCpu(player)) "${names[player]} · Lv $cpuLevel" else names[player],
            r.w - (if (active && shotClock > 0) 64 else 20)), r.l + 12, r.t + r.h * 0.4)
        if (active && phase == Phase.HUMAN && shotClock > 0) {
            val secs = max(0, ceil(clockLeft).toInt())
            val low = clockStarted && clockLeft <= 5.0
            ctx.textAlign = "right"
            ctx.fillStyle = if (!clockStarted) "#9AA7B1" else if (low) "#FF6B6B" else "#F2C94C"
            ctx.fillText(if (clockStarted) "${secs}s" else "${secs}s ⏸", r.r - 12, r.t + r.h * 0.4)
            val frac = (clockLeft / shotClock).coerceIn(0.0, 1.0)
            ctx.fillStyle = "rgba(255,255,255,0.2)"; ctx.roundRect(r.l + 10, r.b - 6, r.w - 20, 3.0, 2.0); ctx.fill()
            ctx.fillStyle = if (low) "#FF6B6B" else "#F2C94C"; ctx.roundRect(r.l + 10, r.b - 6, (r.w - 20) * frac, 3.0, 2.0); ctx.fill()
        }
        ctx.textAlign = "left"
        ctx.font = font(min(11.0, r.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
        val iconR = min(r.h * 0.16, 9.0)
        val iy = r.t + r.h * 0.70
        if (trick) {
            // Levels won, then this level's attempts: a tick for the one that went in.
            val t = match.trick
            val sb = StringBuilder("Levels won: ${t.levelWins[player]}")
            if (t.tries[player] > 0) {
                sb.append("   ")
                for (i in 1..t.tries[player]) sb.append(if (t.made[player] == i) "✓" else "✗")
                if (t.made[player] > 0 && t.bonus[player] > 0) sb.append(" +${t.bonus[player]}")
                if (t.made[player] > 0) sb.append(" = ${t.score(player)}")
            }
            ctx.fillText(sb.toString(), r.l + 12, iy + iconR * 0.4)
        } else if (pocketWars) {
            // This player's colour, points and pockets.
            ctx.fillStyle = PW_COLORS[player]; ctx.circle(r.l + 12 + iconR, iy, iconR); ctx.fill()
            ctx.fillStyle = "#9AA7B1"
            val pts = match.pockets.score[player]; val n = match.pockets.pocketsOf(player)
            ctx.fillText(ellipsize(ctx, "$pts pts · $n pocket${if (n == 1) "" else "s"} · ${maxOf(0, 61 - pts)} to go", r.w - 30 - 2 * iconR),
                r.l + 18 + 2 * iconR, iy + iconR * 0.4)
        } else if (circuit) {
            // Your own targets (dim once they're down) when they're on show; otherwise just how many are down.
            val sunk = match.circuitSunk(player)
            var x = r.l + 12 + iconR
            if (player == revealTo || phase == Phase.OVER) {
                for (id in match.circuit.targets[player]) { balls.icon(ctx, id, x, iy, iconR, !match.world.balls[id].onTable); x += iconR * 2.3 }
                // (Drawing the numbered icons changes the text settings.)
                ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
                ctx.font = font(min(11.0, r.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
                ctx.fillText(ellipsize(ctx, "$sunk/3 sunk", r.r - x - 4), x, iy + iconR * 0.4)
            } else {
                // Three hidden balls: filled in as they go down.
                for (i in 0 until 3) {
                    ctx.fillStyle = if (i < sunk) "#F2C94C" else "#3A444C"
                    ctx.circle(x, iy, iconR); ctx.fill()
                    if (i >= sunk) {
                        ctx.textAlign = "center"; ctx.fillStyle = "#9AA7B1"
                        ctx.fillText("?", x, iy + iconR * 0.45)
                        ctx.textAlign = "left"
                    }
                    x += iconR * 2.3
                }
                ctx.fillStyle = "#9AA7B1"
                val hidden = if (phase == Phase.HANDOFF && player == match.current) "tap to see your targets" else "$sunk/3 sunk · hidden"
                ctx.fillText(ellipsize(ctx, hidden, r.r - x - 4), x, iy + iconR * 0.4)
            }
        } else if (cutthroat) {
            // This player's five balls (dim once they're down), or how the table stands before they have a group.
            val g = match.cutGroup(player)
            if (g < 0) {
                val t = when {
                    match.isBreak -> ""
                    match.cut.choosing == player -> "picking a group…"
                    else -> "Open table · ${match.cut.free().size} groups left"
                }
                ctx.fillText(ellipsize(ctx, t, r.w - 24), r.l + 12, iy + iconR * 0.4)
            } else {
                var x = r.l + 12 + iconR
                for (id in CutthroatState.ballsOf(g)) { balls.icon(ctx, id, x, iy, iconR, !match.world.balls[id].onTable); x += iconR * 2.3 }
                // (Drawing the numbered icons changes the text settings.)
                ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
                ctx.font = font(min(11.0, r.h * 0.22), false)
                val out = match.cutOut(player)
                ctx.fillStyle = if (out) "#FF6B6B" else "#9AA7B1"
                ctx.fillText(ellipsize(ctx, if (out) "OUT" else "${match.cutLeft(player)} left", r.r - x - 4), x, iy + iconR * 0.4)
            }
        } else if (bumper) {
            // Five balls each (dim once they're down), and which way this player shoots.
            var x = r.l + 12 + iconR
            for (id in match.bumperBalls(player)) { balls.icon(ctx, id, x, iy, iconR, !match.world.balls[id].onTable); x += iconR * 2.3 }
            val arrow = when (match.targetCup(player)) { 0 -> "◀"; 1 -> "▶"; 2 -> "▲"; else -> "▼" }
            val q = match.partnerOf(player)
            val dir = when {
                octagon && names.size == 4 && match.ballsLeft(player) == 0 && q >= 0 -> "done · shoots ${names[q]}'s"
                names.size > 2 -> arrow
                else -> "${match.colorName(player)} · shoots $arrow"
            }
            ctx.fillText(ellipsize(ctx, dir, r.r - x - 4), x, iy + iconR * 0.4)
        } else if (mode == GameMode.EIGHT_BALL) {
            val g = match.groups[player]
            if (g == Group.OPEN) {
                if (!match.isBreak) ctx.fillText("Open table", r.l + 12, iy + iconR * 0.4)
            } else {
                var x = r.l + 12 + iconR
                val from = if (g == Group.SOLIDS) 1 else 9
                for (id in from until from + 7) { balls.icon(ctx, id, x, iy, iconR, !match.world.balls[id].onTable); x += iconR * 2.3 }
                if (match.onEight(player)) balls.icon(ctx, 8, x + iconR * 0.4, iy, iconR, false)
            }
        } else if (match.current == player && !match.isBreak) {
            ctx.fillText("Shoot the ${match.lowestBall()}", r.l + 12, iy + iconR * 0.4)
        }
    }

    private fun drawPowerBar(ctx: Ctx2D) {
        val r = powerRect
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(11.0, true); ctx.fillStyle = "#9AA7B1"
        ctx.fillText("POWER", r.cx, r.t - 10)
        ctx.fillStyle = "#1E252B"; ctx.roundRect(r, r.w / 2); ctx.fill()
        if (power > 0) {
            val fillB = r.t + r.h * power
            ctx.save()
            ctx.beginPath(); ctx.rect(r.l, r.t, r.w, fillB - r.t); ctx.clip()
            ctx.fillStyle = powerGrad; ctx.roundRect(r, r.w / 2); ctx.fill()
            ctx.restore()
            ctx.fillStyle = "#FFF"; ctx.roundRect(r.l - 4, fillB - 3, r.w + 8, 6.0, 3.0); ctx.fill()
            ctx.font = font(12.0, false)
            ctx.fillText("${(power * 100).toInt()}%", r.cx, r.b + 14)
        } else if (phase == Phase.HUMAN) {
            ctx.strokeStyle = "rgba(255,255,255,0.4)"; ctx.lineWidth = 2.0
            for (i in 0..2) {
                val y = r.t + 20 + i * 12
                ctx.beginPath(); ctx.moveTo(r.cx - 7, y); ctx.lineTo(r.cx, y + 6); ctx.lineTo(r.cx + 7, y); ctx.stroke()
            }
        }
    }

    private fun drawSpin(ctx: Ctx2D) {
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(11.0, true); ctx.fillStyle = "#9AA7B1"
        ctx.fillText("SPIN", spinCx, spinCy - spinR - 8)
        ctx.fillStyle = spinGrad; ctx.circle(spinCx, spinCy, spinR); ctx.fill()
        ctx.strokeStyle = "rgba(0,0,0,0.25)"; ctx.lineWidth = 1.0
        ctx.line(spinCx - spinR, spinCy, spinCx + spinR, spinCy)
        ctx.line(spinCx, spinCy - spinR, spinCx, spinCy + spinR)
        // The tip's grip: inside this ring it pushes along the cue; outside it slips (a miscue).
        val pad = spinR * 0.92
        ctx.setLineDash(dash); ctx.lineWidth = 1.3; ctx.strokeStyle = "rgba(176,48,48,0.55)"
        ctx.circle(spinCx, spinCy, pad * (P.MAX_TIP / P.TIP_LIMIT)); ctx.stroke()
        ctx.setLineDash(noDash)
        val st = tipState()
        ctx.fillStyle = when (st) { 2 -> "#FF3B3B"; 1 -> "#F2994A"; else -> "#D62828" }
        ctx.circle(spinCx + spinX * pad, spinCy + spinY * pad, max(5.0, spinR * 0.13)); ctx.fill()
        ctx.font = font(10.5, false); ctx.fillStyle = "#9AA7B1"
        val v = if (spinY < -0.08) "follow " else if (spinY > 0.08) "draw " else ""
        val h = if (spinX > 0.08) "right" else if (spinX < -0.08) "left" else ""
        ctx.fillText((v + h).trim().ifEmpty { "center" }, spinCx, spinCy + spinR + 14)
        // Second line: the cue's angle, and a warning if the tip would slip or scoop.
        val raised = elevDeg >= 0.5
        val line = when (st) {
            2 -> "SCOOP · FOUL"
            1 -> if (raised) "MISCUE · ${elevDeg.toInt()}° cue" else "MISCUE"
            else -> if (raised) "cue ${elevDeg.toInt()}° up" else ""
        }
        if (line.isNotEmpty()) {
            ctx.font = font(10.5, true)
            ctx.fillStyle = when (st) { 2 -> "#FF6B6B"; 1 -> "#F2994A"; else -> "#F2C94C" }
            ctx.fillText(ellipsize(ctx, line, spinR * 2.4), spinCx, spinCy + spinR + 29)
        }
    }

    // A one-ball world for checking where the tip is: 0 fine, 1 miscue (slips), 2 scoop (a foul).
    private val tipWorld = World()
    private val tipKey = DoubleArray(4) { Double.NaN }
    private var tipLast = 0

    private fun tipState(): Int {
        if (phase != Phase.HUMAN && phase != Phase.CPU_ANIM) return 0
        val pw = if (power > 0.02) power else lastPower
        if (tipKey[0] == spinX && tipKey[1] == spinY && tipKey[2] == elevDeg && tipKey[3] == pw) return tipLast
        tipKey[0] = spinX; tipKey[1] = spinY; tipKey[2] = elevDeg; tipKey[3] = pw
        val c = tipWorld.balls[0]
        c.x = 1.0; c.y = 0.6; c.onTable = true
        tipWorld.strike(0.0, speedFor(pw), spinX * P.TIP_LIMIT, -spinY * P.TIP_LIMIT, toRad(elevDeg))
        tipLast = if (tipWorld.log.scooped) 2 else if (tipWorld.log.miscue) 1 else 0
        c.stop()
        return tipLast
    }

    /** Ball tray: a dark wooden channel; pocketed balls stack up from the bottom in the order made. */
    private fun drawTray(ctx: Ctx2D) {
        val r = trayRect
        if (r.h < 40 || potR <= 0) return
        ctx.fillStyle = trayGrad; ctx.roundRect(r, r.w / 2); ctx.fill()
        ctx.strokeStyle = "rgba(255,255,255,0.19)"; ctx.lineWidth = 1.5; ctx.roundRect(r, r.w / 2); ctx.stroke()
        ctx.save()
        ctx.beginPath(); ctx.rect(r.l, r.t + 2, r.w, r.h - 2); ctx.clip()
        for (i in 0 until potN) balls.trayBall(ctx, potIds[i], r.cx, potY[i], potR)
        ctx.restore()
    }

    /**
     * Fine aim: a wide roller turning about a horizontal axis. Drag up or down; the faster you
     * drag, the further each movement turns the cue (slow drags are extra fine).
     */
    private fun drawWheel(ctx: Ctx2D) {
        val r = wheelRect
        if (r.h < 40) return
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(11.0, true); ctx.fillStyle = "#9AA7B1"
        ctx.fillText("FINE AIM", r.cx, r.t - 8)
        ctx.fillStyle = wheelGrad; ctx.roundRect(r, 12.0); ctx.fill()
        ctx.save()
        ctx.beginPath(); ctx.rect(r.l, r.t, r.w, r.h); ctx.clip()
        val rad = r.h / 2
        val step = 11.0 / rad
        val half = PI / 2
        var a = (wheelOffset / rad) % step
        if (a < 0) a += step
        a -= ceil(half / step) * step
        while (a < half) {
            if (a > -half) {
                val c = cos(a)
                val y = r.cy + rad * sin(a)
                ctx.lineWidth = 0.6 + 1.8 * c
                ctx.strokeStyle = "rgba(160,176,190,${(40 + 175 * c) / 255})"
                ctx.line(r.l + 10, y, r.r - 10, y)
            }
            a += step
        }
        ctx.fillStyle = "rgba(0,0,0,0.4)"
        ctx.fillRect(r.l, r.t, 6.0, r.h); ctx.fillRect(r.r - 6, r.t, 6.0, r.h)
        ctx.fillStyle = "rgba(242,201,76,0.67)"
        ctx.beginPath(); ctx.moveTo(r.l, r.cy - 5); ctx.lineTo(r.l + 6, r.cy); ctx.lineTo(r.l, r.cy + 5); ctx.closePath(); ctx.fill()
        ctx.beginPath(); ctx.moveTo(r.r, r.cy - 5); ctx.lineTo(r.r - 6, r.cy); ctx.lineTo(r.r, r.cy + 5); ctx.closePath(); ctx.fill()
        ctx.restore()
    }

    // ------------------------------------------------------------------ rock, paper, scissors (drawing)

    /** Draws a hand gesture centred at (cx, cy), rotated so it points across at the other player. */
    /**
     * Draws a hand gesture centred at (cx, cy), rotated so it points across at the other player.
     * [mirror] flips it left to right first, so the left player's hand is the mirror image of the right's.
     */
    private fun drawGesture(ctx: Ctx2D, g: Int, cx: Double, cy: Double, size: Double, rotDeg: Double, alpha: Double = 1.0, mirror: Boolean = false) {
        ctx.save()
        ctx.globalAlpha = alpha
        ctx.translate(cx, cy)
        if (mirror) ctx.scale(-1.0, 1.0)
        ctx.rotate(rotDeg * PI / 180)
        ctx.font = "${size.toInt().coerceAtLeast(8)}px 'Apple Color Emoji', 'Segoe UI Emoji', 'Noto Color Emoji', sans-serif"
        ctx.textAlign = "center"; ctx.textBaseline = "middle"
        ctx.fillStyle = "#FFF"
        ctx.fillText(GESTURES[g], 0.0, 0.0)
        ctx.restore()
    }

    private fun drawRps(ctx: Ctx2D) {
        val w = width; val h = height
        ctx.fillStyle = "rgba(0,0,0,0.69)"; ctx.fillRect(0.0, hudH, w, h - hudH)
        val pw = min(w * 0.8, 660.0)
        val ph = min(h - hudH - 16, 300.0)
        val top = hudH + (h - hudH - ph) / 2
        rpsPanel.set(w / 2 - pw / 2, top, w / 2 + pw / 2, top + ph)
        val p = rpsPanel
        ctx.fillStyle = "rgba(30,37,43,0.95)"; ctx.roundRect(p, 18.0); ctx.fill()
        ctx.strokeStyle = "#F2C94C"; ctx.lineWidth = 2.0; ctx.roundRect(p, 18.0); ctx.stroke()
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(20.0, ph * 0.08), true); ctx.fillStyle = "#FFF"
        ctx.fillText("ROCK · PAPER · SCISSORS", p.cx, p.t + ph * 0.11)
        val win = rpsWinner()
        val sub = when (rps) {
            Rps.PICK -> when {
                rpsTie -> "Tie! Go again · round ${rpsRounds + 1}"
                vsCpu -> if (trick) "Pick your hand · the winner shoots first" else "Pick your hand · the winner breaks"
                else -> if (trick) "Both pick on your own side · the winner shoots first" else "Both pick on your own side · the winner breaks"
            }
            Rps.SHAKE -> SHAKE_WORDS[(rpsT / RPS_BEAT).toInt().coerceIn(0, 2)]
            Rps.REVEAL -> when {
                win < 0 -> "Shoot! · Tie — go again"
                vsCpu && win == 0 -> if (trick) "Shoot! · You win — you go first!" else "Shoot! · You win — you break!"
                vsCpu -> if (trick) "Shoot! · CPU wins — CPU goes first" else "Shoot! · CPU wins — CPU breaks"
                else -> "Shoot! · ${names[win]} wins — ${names[win]} ${if (trick) "goes first" else "breaks"}!"
            }
        }
        val picking = rps == Rps.PICK
        ctx.font = font(min(if (picking) 13.0 else 17.0, ph * 0.07), !picking)
        ctx.fillStyle = if (picking) "#B8C2CA" else "#F2C94C"
        ctx.fillText(sub, p.cx, p.t + ph * 0.2)

        val hand = min(ph * 0.3, pw * 0.15)
        val handY = p.t + ph * 0.5
        val sideX = doubleArrayOf(p.l + pw * 0.25, p.l + pw * 0.75)
        ctx.font = font(15.0, true); ctx.fillStyle = "#F2C94C"
        ctx.fillText("VS", p.cx, handY + 5)
        val bob = if (rps == Rps.SHAKE) abs(sin(PI * rpsT / RPS_BEAT)) * hand * 0.35 else 0.0
        for (pl in 0..1) {
            val cx = sideX[pl]
            // Both hands point across at each other: the right hand is turned to face left, and the
            // left hand is its mirror image (flipped across the vertical), not the same hand upside down.
            val rot = -90.0
            val mirror = pl == 0
            ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
            ctx.font = font(13.0, true); ctx.fillStyle = "#FFF"
            ctx.fillText(if (isCpu(pl)) "CPU" else names[pl].uppercase(), cx, p.t + ph * 0.3)
            when (rps) {
                Rps.PICK, Rps.SHAKE -> drawGesture(ctx, 0, cx, handY - bob, hand, rot, 1.0, mirror)
                Rps.REVEAL -> {
                    val pop = 1 + 0.18 * (1 - (rpsT / 0.15).coerceIn(0.0, 1.0))
                    if (win == pl && rpsT > 0.2) {
                        ctx.strokeStyle = "#F2C94C"; ctx.lineWidth = 3.0
                        ctx.circle(cx, handY, hand * 0.56); ctx.stroke()
                    }
                    drawGesture(ctx, rpsPick[pl], cx, handY, hand * pop, rot, if (win >= 0 && win != pl && rpsT > 0.2) 0.47 else 1.0, mirror)
                }
            }
            val bs = min(pw * 0.5 / 3.5, ph * 0.26)
            val by = p.b - 12 - bs
            if (picking && !isCpu(pl) && rpsPick[pl] < 0) {
                val gap = bs * 0.18
                var bx = cx - (3 * bs + 2 * gap) / 2
                for (c in 0..2) {
                    val br = rpsBtn[pl][c]
                    br.set(bx, by, bx + bs, by + bs)
                    ctx.fillStyle = "#2A333B"; ctx.roundRect(br, 12.0); ctx.fill()
                    ctx.strokeStyle = "rgba(242,201,76,0.33)"; ctx.lineWidth = 1.5; ctx.roundRect(br, 12.0); ctx.stroke()
                    // Scissors is the peace sign turned sideways.
                    drawGesture(ctx, c, br.cx, br.t + bs * 0.42, bs * 0.46, if (c == 2) 90.0 else 0.0)
                    ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
                    ctx.font = font(min(11.0, bs * 0.16), false); ctx.fillStyle = "#CED6DD"
                    ctx.fillText(GESTURE_NAMES[c], br.cx, br.b - bs * 0.1)
                    bx += bs + gap
                }
            } else if (picking) {
                for (c in 0..2) rpsBtn[pl][c].set(0.0, 0.0, 0.0, 0.0)
                ctx.font = font(14.0, true); ctx.fillStyle = "#3CCB7F"
                ctx.fillText(if (isCpu(pl)) "Ready" else "Ready ✓", cx, by + bs / 2 + 5)
            }
        }
        if (rps == Rps.REVEAL && rpsT > 0.6) {
            ctx.font = font(11.0, false); ctx.fillStyle = "#9AA7B1"
            ctx.fillText("tap to continue", p.cx, p.b - 10)
        }
    }

    /** Circuit Pool, pass & play: the next player's targets stay hidden until they tap. */
    private fun drawHandoff(ctx: Ctx2D) {
        val w = width; val h = height
        ctx.fillStyle = "rgba(0,0,0,0.7)"; ctx.fillRect(0.0, 0.0, w, h)
        val pw = min(w * 0.5, 420.0); val ph = min(h * 0.5, 190.0)
        overPanel.set(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        ctx.fillStyle = "#1E252B"; ctx.roundRect(overPanel, 16.0); ctx.fill()
        ctx.strokeStyle = "#F2C94C"; ctx.lineWidth = 2.0; ctx.roundRect(overPanel, 16.0); ctx.stroke()
        val p = match.current
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(24.0, true); ctx.fillStyle = "#FFF"
        ctx.fillText("${names[p]}'s turn", overPanel.cx, overPanel.t + ph * 0.3)
        ctx.font = font(13.0, false); ctx.fillStyle = "#B8C2CA"
        ctx.fillText(ellipsize(ctx, "Hand it to ${names[p]} — ${names[1 - p]}, no peeking", pw - 24), overPanel.cx, overPanel.t + ph * 0.5)
        if (message.isNotEmpty()) ctx.fillText(ellipsize(ctx, message, pw - 24), overPanel.cx, overPanel.t + ph * 0.64)
        ctx.font = font(15.0, true); ctx.fillStyle = "#F2C94C"
        ctx.fillText("Tap to see your targets", overPanel.cx, overPanel.b - 18)
    }

    /** Cutthroat: the shooter sank a ball on an open table — pick the group the others will be trying to sink. */
    private fun drawPick(ctx: Ctx2D) {
        val w = width; val h = height
        ctx.fillStyle = "rgba(0,0,0,0.6)"; ctx.fillRect(0.0, hudH, w, h - hudH)
        val pw = min(w * 0.7, 600.0); val ph = min(h - hudH - 16, 250.0)
        val top = hudH + (h - hudH - ph) / 2
        overPanel.set(w / 2 - pw / 2, top, w / 2 + pw / 2, top + ph)
        ctx.fillStyle = "rgba(30,37,43,0.95)"; ctx.roundRect(overPanel, 16.0); ctx.fill()
        ctx.strokeStyle = "#F2C94C"; ctx.lineWidth = 2.0; ctx.roundRect(overPanel, 16.0); ctx.stroke()
        val p = match.cut.choosing.coerceAtLeast(0)
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(22.0, ph * 0.1), true); ctx.fillStyle = "#FFF"
        ctx.fillText(if (p == 0 && (vsCpu || rulesPractice)) "Pick your group" else "${names[p]}: pick your group", overPanel.cx, overPanel.t + ph * 0.15)
        ctx.font = font(min(13.0, ph * 0.06), false); ctx.fillStyle = "#B8C2CA"
        ctx.fillText(ellipsize(ctx, "These are the balls you keep on the table — the others try to sink them", pw - 24),
            overPanel.cx, overPanel.t + ph * 0.26)
        val gap = 10.0
        val bw = (pw - 32 - 2 * gap) / 3
        val bh = ph * 0.56
        val by = overPanel.t + ph * 0.34
        val free = match.cutPickable()
        for (g in 0..2) {
            val r = pickBtn[g]
            r.set(overPanel.l + 16 + g * (bw + gap), by, overPanel.l + 16 + g * (bw + gap) + bw, by + bh)
            val open = g in free
            ctx.fillStyle = if (open) "#2A333B" else "#161B1F"; ctx.roundRect(r, 12.0); ctx.fill()
            if (open) { ctx.strokeStyle = "rgba(242,201,76,0.55)"; ctx.lineWidth = 1.5; ctx.roundRect(r, 12.0); ctx.stroke() }
            ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
            ctx.font = font(min(18.0, bh * 0.16), true); ctx.fillStyle = if (open) "#FFF" else "#6B7780"
            ctx.fillText(CutthroatState.groupName(g), r.cx, r.t + bh * 0.24)
            val ir = min(bw / 13, bh * 0.1)
            var x = r.cx - 4 * ir * 1.25
            for (id in CutthroatState.ballsOf(g)) { balls.icon(ctx, id, x, r.t + bh * 0.5, ir, !match.world.balls[id].onTable); x += ir * 2.5 }
            val left = CutthroatState.ballsOf(g).count { match.world.balls[it].onTable }
            ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
            ctx.font = font(min(12.0, bh * 0.11), false); ctx.fillStyle = if (open) "#CED6DD" else "#6B7780"
            val owner = match.cut.ownerOfGroup(g)
            ctx.fillText(if (open) "$left on the table" else if (owner >= 0) "${names[owner]}'s" else "all down", r.cx, r.t + bh * 0.82)
        }
    }

    private fun drawGameOver(ctx: Ctx2D) {
        val w = width; val h = height
        ctx.fillStyle = "rgba(0,0,0,0.6)"; ctx.fillRect(0.0, 0.0, w, h)
        val pw = min(w * 0.5, 420.0); val ph = min(h * 0.62, 240.0)
        overPanel.set(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        ctx.fillStyle = "#1E252B"; ctx.roundRect(overPanel, 16.0); ctx.fill()
        ctx.strokeStyle = "#F2C94C"; ctx.lineWidth = 2.0; ctx.roundRect(overPanel, 16.0); ctx.stroke()
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(30.0, true); ctx.fillStyle = "#FFF"
        ctx.fillText(gameOverTitle, overPanel.cx, overPanel.t + ph * 0.25)
        ctx.font = font(13.0, false); ctx.fillStyle = "#B8C2CA"
        val detail = match.message.substringAfter("(", "").removeSuffix(")")
        if (detail.isNotEmpty()) ctx.fillText(ellipsize(ctx, detail, pw - 24), overPanel.cx, overPanel.t + ph * 0.38)
        var recY = 0.52
        if (circuit) {
            // Everyone's targets, now it's over.
            ctx.fillStyle = "#DDE3E8"; ctx.font = font(13.0, false)
            val t = (0..1).joinToString("  ·  ") { p -> "${names[p]} had ${match.circuit.targets[p].joinToString(", ")}" }
            ctx.fillText(ellipsize(ctx, t, pw - 24), overPanel.cx, overPanel.t + ph * 0.49)
            recY = 0.6
        }
        if (recordLine.isNotEmpty()) {
            ctx.fillStyle = "#F2C94C"; ctx.font = font(14.0, false)
            ctx.fillText(ellipsize(ctx, recordLine, pw - 24), overPanel.cx, overPanel.t + ph * recY)
        }
        val bw = pw * 0.38; val bh = 46.0
        val by = overPanel.b - bh - 20
        rematchBtn.set(overPanel.cx - bw - 8, by, overPanel.cx - 8, by + bh)
        menuExitBtn.set(overPanel.cx + 8, by, overPanel.cx + bw + 8, by + bh)
        ctx.fillStyle = "#F2C94C"; ctx.roundRect(rematchBtn, 12.0); ctx.fill()
        ctx.fillStyle = "#39434C"; ctx.roundRect(menuExitBtn, 12.0); ctx.fill()
        ctx.font = font(16.0, true); ctx.textBaseline = "middle"
        ctx.fillStyle = "#1A1A1A"; ctx.fillText(if (practiceMode) "Re-rack" else "Rematch", rematchBtn.cx, rematchBtn.cy)
        ctx.fillStyle = "#FFF"; ctx.fillText("Menu", menuExitBtn.cx, menuExitBtn.cy)
    }

    // ------------------------------------------------------------------ input (slot = finger 0..9)

    fun pointerDown(id: Int, x: Double, y: Double, first: Boolean) {
        sfx.unlock()
        if (id !in ptrMode.indices) return
        ptrMode[id] = Touch.NONE
        if (first && menuBtn.contains(x, y) && phase != Phase.OVER) { ptrMode[id] = Touch.BUTTON; host.onMenuRequested(); return }
        if (phase == Phase.OVER) {
            if (first) {
                if (rematchBtn.contains(x, y)) newGame(if (practiceMode || octagon) 0 else if (cutthroat) (breaker + 1) % 3 else 1 - breaker)
                else if (menuExitBtn.contains(x, y)) host.onExitToMenu()
            }
            ptrMode[id] = Touch.BUTTON
            return
        }
        if (phase == Phase.HANDOFF) {
            ptrMode[id] = Touch.BUTTON
            if (first) { revealTo = match.current; beginHumanTurn() }
            return
        }
        if (phase == Phase.PICK) {
            ptrMode[id] = Touch.BUTTON
            if (first) for (g in 0..2) if (pickBtn[g].contains(x, y)) cutPick(g)
            return
        }
        if (phase == Phase.RPS) {
            ptrMode[id] = Touch.BUTTON
            when (rps) {
                Rps.PICK -> for (pl in 0..1) if (!isCpu(pl)) for (c in 0..2) if (rpsBtn[pl][c].contains(x, y)) rpsChoose(pl, c)
                Rps.REVEAL -> if (rpsT > 0.6) rpsDone()
                else -> {}
            }
            return
        }
        val tu = tut
        if (tu != null) {
            ptrMode[id] = Touch.BUTTON
            if (first && tutSkip.contains(x, y)) { leaveTutorial(); return }
            if (first && tutBtn.contains(x, y)) { tu.next(); return }
            if (tutCard.contains(x, y)) return
            // Only the highlighted control works.
            if (phase != Phase.HUMAN || !tu.allows(regionAt(x, y))) return
            ptrMode[id] = Touch.NONE
        }
        if (trickPractice && first) {
            when {
                retryBtn.contains(x, y) -> { trickPracticeGo(-1); ptrMode[id] = Touch.BUTTON; return }
                rackBtn.contains(x, y) -> { trickPracticeGo(0); ptrMode[id] = Touch.BUTTON; return }
                clearBtn.contains(x, y) -> { trickPracticeGo(1); ptrMode[id] = Touch.BUTTON; return }
            }
        }
        if (practice && !tutorialMode && first) {
            when {
                retryBtn.contains(x, y) -> { practiceRetry(); ptrMode[id] = Touch.BUTTON; return }
                rackBtn.contains(x, y) -> { newGame(0); ptrMode[id] = Touch.BUTTON; return }
                clearBtn.contains(x, y) && phase == Phase.HUMAN && !rulesPractice -> { practiceClear(); ptrMode[id] = Touch.BUTTON; return }
                clearBtn.contains(x, y) -> { ptrMode[id] = Touch.BUTTON; return }
                phase == Phase.HUMAN && !rulesPractice && x >= trayRect.l - 8 && x <= trayRect.r + 8 && y >= trayRect.t && y <= trayRect.b -> {
                    // Tap a ball in the tray to put it back on the table.
                    for (i in 0 until potN) if (abs(y - potY[i]) < max(potR * 1.1, 10.0)) { returnBall(potIds[i]); break }
                    ptrMode[id] = Touch.BUTTON
                    return
                }
            }
        }
        val inView = tableArea.contains(x, y)
        if (!pov && inView && view3dBtn.contains(x, y) && povAllowed()) { setPov(true); ptrMode[id] = Touch.BUTTON; return }
        if (pov && inView) {
            if (topViewBtn.contains(x, y)) { setPov(false); ptrMode[id] = Touch.BUTTON; return }
            if (resetViewBtn.contains(x, y)) { povCtl.reset(); ptrMode[id] = Touch.BUTTON; return }
            ptrX[id] = x; ptrY[id] = y
            if (anyTouch(Touch.CAM)) { ptrMode[id] = Touch.BUTTON; return }
            // A second finger on the 3D view: both fingers now move the camera.
            for (o in ptrMode.indices) if (o != id && (ptrMode[o] == Touch.POV || ptrMode[o] == Touch.PENDING ||
                    ptrMode[o] == Touch.MOVE_CUE || ptrMode[o] == Touch.MOVE_BALL)) {
                if (ptrMode[o] == Touch.MOVE_CUE || ptrMode[o] == Touch.MOVE_BALL) {
                    movingBall = -1; dragInvalid = false
                    if (phase == Phase.HUMAN) povLocked = false
                }
                ptrMode[o] = Touch.CAM; ptrMode[id] = Touch.CAM
                camDist = hypot(x - ptrX[o], y - ptrY[o]); camMidX = (x + ptrX[o]) / 2; camMidY = (y + ptrY[o]) / 2
                return
            }
            if (phase != Phase.HUMAN) {
                // Between shots one finger does nothing, but a second one turns it into a camera gesture.
                ptrMode[id] = Touch.POV; ptrAxis[id] = -1
                return
            }
        }
        if (phase != Phase.HUMAN) return
        val c = match.world.cueBall
        val spinDist = hypot(x - spinCx, y - spinCy)
        val inTable = tableArea.contains(x, y)
        val grabbed = if (!pov && practice && !tutorialMode && inTable) ballUnder(x, y) else -1
        val tapBall = if (!pov && grabbed < 0 && inTable && povAllowed()) objectBallUnder(x, y) else -1
        ptrDownX[id] = x; ptrDownY[id] = y
        ptrMode[id] = when {
            powerRect.l - 20 <= x && x <= powerRect.r + 20 && y >= powerRect.t - 30 && y <= powerRect.b + 20 ->
                if (anyTouch(Touch.POWER)) Touch.NONE else { ptrStartY[id] = y; power = 0.0; Touch.POWER }
            spinDist <= spinR * 1.2 -> {
                if (spinDist < spinR * 0.14) { spinX = 0.0; spinY = 0.0 } else setSpin(x, y)
                Touch.SPIN
            }
            x >= wheelRect.l - 6 && x <= wheelRect.r + 8 && y >= wheelRect.t - 6 && y <= wheelRect.b + 8 -> {
                ptrLastY[id] = y; ptrLastT[id] = now(); ptrVel[id] = 0.0; Touch.WHEEL
            }
            pov && inTable -> povDown(id, x, y)
            match.ballInHand && !anyTouch(Touch.POWER) && !anyTouch(Touch.MOVE_CUE) &&
                hypot(x - sx(c.x), y - sy(c.y)) < max(rpx * 3, 28.0) -> { dragInvalid = false; Touch.MOVE_CUE }
            match.callAllowed() && pocketUnder(x, y) >= 0 -> {
                val pk = pocketUnder(x, y)
                match.calledPocket = if (match.calledPocket == pk) -1 else pk
                Touch.BUTTON
            }
            grabbed >= 0 && !anyTouch(Touch.MOVE_BALL) && !anyTouch(Touch.PENDING) -> {
                ptrBall[id] = grabbed; ptrDownX[id] = x; ptrDownY[id] = y; ptrDownAt[id] = now()
                Touch.PENDING
            }
            tapBall >= 0 -> { ptrBall[id] = tapBall; Touch.TAP_BALL }
            inTable -> { aimAt(x, y); Touch.AIM }
            else -> Touch.NONE
        }
        val m = ptrMode[id]
        if (m == Touch.POWER || m == Touch.WHEEL || m == Touch.AIM || m == Touch.POV || m == Touch.TAP_BALL) clockStarted = true
    }

    private fun pocketUnder(x: Double, y: Double): Int {
        for ((i, pk) in Table.pockets.withIndex()) if (hypot(x - sx(pk.cx), y - sy(pk.cy)) < pk.capR * scale + 14) return i
        return -1
    }

    /** A ball other than the cue ball under (x, y) in the top view, for opening the 3D view on it. */
    private fun objectBallUnder(x: Double, y: Double): Int {
        val reach = max(rpx * 1.5, 18.0)
        var best = -1; var bestD = Double.MAX_VALUE
        for (b in match.world.balls) {
            if (!b.onTable || b.id == match.world.cue) continue
            val d = hypot(x - sx(b.x), y - sy(b.y))
            if (d < reach && d < bestD) { best = b.id; bestD = d }
        }
        return best
    }

    private fun ballUnder(x: Double, y: Double): Int {
        val reach = max(rpx * 1.6, 20.0)
        var best = -1; var bestD = Double.MAX_VALUE
        for (b in match.world.balls) {
            if (!b.onTable) continue
            val d = hypot(x - sx(b.x), y - sy(b.y))
            if (d < reach && d < bestD) { best = b.id; bestD = d }
        }
        return best
    }

    /**
     * Fine-aim acceleration: how much the cue turns per pixel of finger travel, from the finger's
     * speed. Slow drags are about 3x finer than the base setting; ~150 px/s is the base speed;
     * fast swipes turn up to 25x as far, so a flick swings the cue a long way.
     */
    private fun wheelGain(v: Double): Double = (0.3 + 0.7 * (v / 150.0).pow(1.7)).coerceAtMost(25.0)

    fun pointerMove(id: Int, x: Double, y: Double) {
        if (id !in ptrMode.indices) return
        ptrX[id] = x; ptrY[id] = y
        if (ptrMode[id] == Touch.CAM) { camMove(); return }
        if (phase != Phase.HUMAN) return
        when (ptrMode[id]) {
            Touch.POWER -> power = ((y - ptrStartY[id]) / (powerRect.h * 0.9)).coerceIn(0.0, 1.0)
            Touch.SPIN -> setSpin(x, y)
            Touch.POV -> povDrag(id, x, y, now())
            Touch.TAP_BALL -> if (hypot(x - ptrDownX[id], y - ptrDownY[id]) > 10) { ptrMode[id] = Touch.AIM; aimAt(x, y) }
            Touch.WHEEL -> {
                val d = y - ptrLastY[id]
                val t = now()
                val secs = ((t - ptrLastT[id]) / 1000).coerceIn(0.004, 0.1)
                ptrLastY[id] = y
                ptrLastT[id] = t
                ptrVel[id] = ptrVel[id] * 0.5 + (abs(d) / secs) * 0.5
                wheelOffset += d
                aimAngle += d * wheelRadPerPx * wheelGain(ptrVel[id])
                tut?.onWheel(d)
            }
            Touch.MOVE_CUE -> {
                dragX = x; dragY = y
                if (!toTable(x, y)) { dragInvalid = true; return }
                val px = povXY[0]; val py = povXY[1]
                if (trick) {
                    // Stay in the break area (or the nearest of the level's start zones).
                    val q = Trick.clampPlacement(Trick.LEVELS[match.trick.level], px, py)
                    dragInvalid = !match.placeCue(q[0], q[1])
                } else {
                    val maxX = if (match.kitchenOnly) P.HEAD_X else P.L - P.R
                    val c0 = match.world.cueBall
                    val bx = c0.x; val by = c0.y
                    dragInvalid = !match.placeCue(px.coerceIn(P.R, maxX), py.coerceIn(P.R, P.W - P.R))
                    tut?.onCueMoved(hypot(c0.x - bx, c0.y - by))
                }
            }
            Touch.AIM -> aimAt(x, y)
            Touch.PENDING -> if (hypot(x - ptrDownX[id], y - ptrDownY[id]) > 10) {
                if (pov) { ptrMode[id] = Touch.POV; povDrag(id, x, y, now()) } else { ptrMode[id] = Touch.AIM; aimAt(x, y) }
            }
            Touch.MOVE_BALL -> moveBall(ptrBall[id], x, y)
            else -> {}
        }
    }

    fun pointerUp(id: Int) {
        if (id !in ptrMode.indices) return
        val mode = ptrMode[id]
        ptrMode[id] = Touch.NONE
        // One finger of the camera gesture lifted: the other is ignored until it's lifted too.
        if (mode == Touch.CAM) { for (o in ptrMode.indices) if (ptrMode[o] == Touch.CAM) ptrMode[o] = Touch.BUTTON; return }
        if (phase != Phase.HUMAN) return
        if (pov && (mode == Touch.MOVE_CUE || mode == Touch.MOVE_BALL)) povLocked = false
        tut?.onRelease(targetOf(mode), hypot(spinX, spinY), elevDeg)
        if (mode == Touch.POWER) {
            if (power > 0.02 && match.callRequired() && match.calledPocket < 0) {
                power = 0.0
                callFlashUntil = now() + 1500
            } else if (power > 0.02) {
                lastPower = power
                tut?.onShot()
                shoot(aimAngle, speedFor(power), spinX * P.TIP_LIMIT, -spinY * P.TIP_LIMIT, toRad(elevDeg))
            } else power = 0.0
        }
        if (mode == Touch.MOVE_CUE) dragInvalid = false
        if (mode == Touch.TAP_BALL && !pickBumperBall(ptrBall[id])) { aimAtBall(ptrBall[id]); setPov(true) }
        if (mode == Touch.POV && ptrAxis[id] == 0) povTap(ptrDownX[id], ptrDownY[id])
        if (mode == Touch.PENDING) {
            if (bumperPractice) {
                // Bumper practice: a tap on another ball shoots that one; on the one you're shooting, opens the 3D view.
                if (ptrBall[id] != match.world.cue) { bumperPracticePick(ptrBall[id]); sfx.ball(0.6) } else setPov(true)
            } else if (ptrBall[id] > 0 && povAllowed()) { aimAtBall(ptrBall[id]); setPov(true) }
            else if (!pov) aimAt(ptrDownX[id], ptrDownY[id])
        }
        if (mode == Touch.MOVE_BALL) { movingBall = -1; dragInvalid = false }
    }

    fun cancelTouches() { if (anyTouch(Touch.POWER)) power = 0.0; clearTouches(); movingBall = -1 }

    private fun aimAt(x: Double, y: Double) {
        val c = match.world.cueBall
        val dx = x - sx(c.x); val dy = y - sy(c.y)
        if (hypot(dx, dy) > rpx * 0.8) {
            val a = atan2(dy, dx)
            var d = a - aimAngle
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            tut?.onAim(d)
            aimAngle = a
        }
    }

    // ------------------------------------------------------------------ 3D view (input)

    /** Tapping a ball opens the 3D view (in the tutorial, only on its 3D-view step). */
    private fun povAllowed(): Boolean { val t = tut ?: return true; return t.step == Tutorial.Step.POV }

    private fun setPov(on: Boolean) {
        if (pov == on) return
        pov = on
        for (i in ptrMode.indices) if (ptrMode[i] == Touch.POV || ptrMode[i] == Touch.AIM || ptrMode[i] == Touch.TAP_BALL ||
            ptrMode[i] == Touch.CAM || ptrMode[i] == Touch.PENDING) ptrMode[i] = Touch.NONE
        if (on) povHintUntil = now() + 6000
        tut?.onPov(on)
    }

    /** Hold the camera where it is (it otherwise follows the cue ball). */
    private fun lockPov() {
        val c = match.world.cueBall
        povLocked = true; povLockX = c.x; povLockY = c.y
    }

    /** Screen point to table coordinates (on the plane of the balls' centres in 3D) into [povXY]. */
    private fun toTable(x: Double, y: Double): Boolean {
        if (!pov) { povXY[0] = tx(x); povXY[1] = ty(y); return true }
        return povCam.unproject(x, y, P.R, povXY)
    }

    /** One finger down on the 3D view: the cue ball (ball in hand), a ball to move (practice), or aim / cue angle. */
    private fun povDown(id: Int, x: Double, y: Double): Touch {
        ptrLastX[id] = x; ptrLastY[id] = y; ptrLastT[id] = now(); ptrVel[id] = 0.0; ptrAxis[id] = 0
        val c = match.world.cueBall
        if (match.ballInHand && c.onTable && !anyTouch(Touch.MOVE_CUE) && !anyTouch(Touch.POWER) &&
            povCam.project(c.x, c.y, P.R, povPt)) {
            val r = povCam.ballRadius(povPt[2])
            if (hypot(x - povPt[0], y - povPt[1]) < max(r * 1.8, 28.0)) {
                lockPov(); dragInvalid = false; dragX = x; dragY = y
                return Touch.MOVE_CUE
            }
        }
        if (practice && !tutorialMode && !anyTouch(Touch.MOVE_BALL) && !anyTouch(Touch.PENDING)) {
            val b = povBallUnder(x, y, true)
            if (b >= 0) { ptrBall[id] = b; ptrDownAt[id] = now(); return Touch.PENDING }
        }
        return Touch.POV
    }

    /** The nearest ball under (x, y) in the 3D view (the cue ball too if [cue]), or -1. */
    private fun povBallUnder(x: Double, y: Double, cue: Boolean): Int {
        var best = -1; var bestD = Double.MAX_VALUE
        for (b in match.world.balls) {
            if (!b.onTable || (b.id == match.world.cue && !cue)) continue
            if (!povCam.project(b.x, b.y, P.R + b.z, povPt)) continue
            val r = povCam.ballRadius(povPt[2])
            if (hypot(x - povPt[0], y - povPt[1]) < max(r * 1.3, 16.0) && povPt[2] < bestD) { best = b.id; bestD = povPt[2] }
        }
        return best
    }

    /** Two fingers on the 3D view: spread or pinch to zoom, move together up/down to tilt and sideways to swing round. */
    private fun camMove() {
        var a = -1; var b = -1
        for (i in ptrMode.indices) if (ptrMode[i] == Touch.CAM) { if (a < 0) a = i else if (b < 0) b = i }
        if (a < 0 || b < 0) return
        val d = hypot(ptrX[a] - ptrX[b], ptrY[a] - ptrY[b])
        val mx = (ptrX[a] + ptrX[b]) / 2; val my = (ptrY[a] + ptrY[b]) / 2
        if (camDist > 10 && d > 10) povCtl.pinch(d / camDist)
        povCtl.tilt(my - camMidY, tableArea.h)
        povCtl.orbit(mx - camMidX, tableArea.w)
        camDist = d; camMidX = mx; camMidY = my
    }

    /**
     * Bumper pool: a tap on one of your own balls picks it to shoot (aimed at your cup). Returns true
     * if the tap was taken that way: your ball, picked or not (the marked ball has to go first).
     */
    private fun pickBumperBall(id: Int): Boolean {
        if (!bumper || match.ownerOf(id) != match.playsFor(match.current)) return false
        if (match.selectBall(id)) {
            aimAtNearestTarget()
            sfx.ball(0.6)
        } else message = match.bumperHint()
        return true
    }

    /** Aim straight at the centre of ball [id]. */
    private fun aimAtBall(id: Int) {
        val c = match.world.cueBall; val b = match.world.balls[id]
        if (!b.onTable || id == match.world.cue) return
        val a = atan2(b.y - c.y, b.x - c.x)
        var d = a - aimAngle
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        tut?.onAim(d)
        aimAngle = a
    }

    /**
     * Dragging in the 3D view. The first ~8 px decide the direction: sideways turns the cue (faster the
     * quicker you drag), up and down raise and lower the cue.
     */
    private fun povDrag(id: Int, x: Double, y: Double, t: Double) {
        if (ptrAxis[id] < 0) return
        if (ptrAxis[id] == 0) {
            val mx = x - ptrDownX[id]; val my = y - ptrDownY[id]
            if (hypot(mx, my) < 8) return
            ptrAxis[id] = if (abs(mx) >= abs(my)) 1 else 2
            ptrLastX[id] = ptrDownX[id]; ptrLastY[id] = ptrDownY[id]
        }
        val ddx = x - ptrLastX[id]; val ddy = y - ptrLastY[id]
        val secs = ((t - ptrLastT[id]) / 1000).coerceIn(0.004, 0.1)
        ptrLastX[id] = x; ptrLastY[id] = y; ptrLastT[id] = t
        if (ptrAxis[id] == 1) {
            ptrVel[id] = ptrVel[id] * 0.5 + (abs(ddx) / secs) * 0.5
            val gain = (0.35 + 0.65 * (ptrVel[id] / 150.0).pow(1.5)).coerceAtMost(8.0)
            aimAngle += ddx * POV_AIM_PER_PX * gain
        } else {
            elevDeg = (elevDeg - ddy * POV_ELEV_PER_PX).coerceIn(0.0, P.MAX_ELEVATION)
            tut?.onElevation(elevDeg)
        }
    }

    /** A tap in the 3D view: Top view, a pocket (to call it) or a ball (to line up on it). */
    private fun povTap(x: Double, y: Double) {
        if (match.callAllowed()) {
            for ((i, pk) in Table.pockets.withIndex()) {
                if (!povCam.project(pk.cx, pk.cy, 0.0, povPt)) continue
                val r = povCam.focal * pk.capR / povPt[2] + 14
                if (hypot(x - povPt[0], y - povPt[1]) < r) {
                    match.calledPocket = if (match.calledPocket == i) -1 else i
                    return
                }
            }
        }
        var best = -1; var bestD = Double.MAX_VALUE
        for (b in match.world.balls) {
            if (!b.onTable || b.id == match.world.cue) continue
            if (!povCam.project(b.x, b.y, P.R + b.z, povPt)) continue
            val r = povCam.ballRadius(povPt[2])
            if (hypot(x - povPt[0], y - povPt[1]) < max(r * 1.3, 16.0) && povPt[2] < bestD) { best = b.id; bestD = povPt[2] }
        }
        if (best > 0 && !pickBumperBall(best)) aimAtBall(best)
    }

    // ------------------------------------------------------------------ 3D view (drawing)

    /** The view down the cue: the table in 3D, the balls, the aim guide and the cue, plus Top view. */
    private fun drawPov(ctx: Ctx2D) {
        val w = match.world
        val c = w.cueBall
        val a = tableArea
        // The camera orbits the cue ball (where it's being placed, during the CPU's ball in hand),
        // or holds still while balls roll and while a ball is being dragged.
        var cueX = c.x; var cueY = c.y
        val plan = cpuPlan
        if (phase == Phase.CPU_ANIM && plan != null && plan.hasPlacement && animT < 0.45) {
            val k = (animT / 0.45).coerceIn(0.0, 1.0)
            cueX = animFromX + (plan.placeX - animFromX) * k
            cueY = animFromY + (plan.placeY - animFromY) * k
        }
        val pivX = if (povLocked) povLockX else cueX
        val pivY = if (povLocked) povLockY else cueY
        povCam.setup(pivX, pivY, aimAngle + povCtl.yaw, povCtl.pitch, povCtl.dist, a.l, a.t, a.r, a.b)
        val sc = povScene
        sc.build(povCam, w, cueX, cueY)
        povCam.viewBasis(povView)
        povBalls.setView(povView)
        val humanTurn = phase == Phase.HUMAN

        ctx.save()
        ctx.beginPath(); ctx.rect(a.l, a.t, a.w, a.h); ctx.clip()
        val bg = ctx.createLinearGradient(0.0, a.t, 0.0, a.b)
        bg.addColorStop(0.0, "#0B0E11"); bg.addColorStop(1.0, "#20262C")
        ctx.fillStyle = bg; ctx.fillRect(a.l, a.t, a.w, a.h)
        val cloth = ctx.createRadialGradient(sc.clothCx, sc.clothCy, 0.0, sc.clothCx, sc.clothCy, max(sc.clothR, 1.0))
        cloth.addColorStop(0.0, "#24985D"); cloth.addColorStop(0.55, "#1C7E4C"); cloth.addColorStop(1.0, "#135A35")
        val showGuide = humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0
        for (o in 0 until sc.n) {
            if (o == sc.guideAt) { if (showGuide) drawPovGuide(ctx); drawPovMarks(ctx, cueX, cueY) }
            val i = sc.order[o]
            if (sc.kind[i] == PovScene.BALL) {
                povBalls.draw(ctx, w.balls[sc.color[i]], sc.bx[i], sc.by[i], sc.br[i] / povBalls.radius)
                continue
            }
            val st = sc.start[i]
            ctx.beginPath()
            ctx.moveTo(sc.px[st], sc.py[st])
            for (k in 1 until sc.count[i]) ctx.lineTo(sc.px[st + k], sc.py[st + k])
            ctx.closePath()
            ctx.fillStyle = if (sc.color[i] == PovScene.CLOTH) cloth else col(sc.color[i])
            ctx.fill()
        }
        if (sc.guideAt >= sc.n) { if (showGuide) drawPovGuide(ctx); drawPovMarks(ctx, cueX, cueY) }
        if (humanTurn || phase == Phase.CPU_ANIM) drawPovCalled(ctx)
        val showCue = (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && c.onTable) drawPovCue(ctx, cueX, cueY)
        ctx.restore()

        // Top view and reset-camera buttons, and (for a few seconds, or when there's a warning) how to drive this view.
        ctx.setLineDash(noDash)
        ctx.fillStyle = "rgba(30,37,43,0.9)"; ctx.roundRect(topViewBtn, 10.0); ctx.fill()
        ctx.roundRect(resetViewBtn, 10.0); ctx.fill()
        ctx.lineWidth = 1.5; ctx.strokeStyle = "#F2C94C"; ctx.roundRect(topViewBtn, 10.0); ctx.stroke()
        ctx.strokeStyle = "rgba(242,201,76,0.6)"; ctx.roundRect(resetViewBtn, 10.0); ctx.stroke()
        ctx.textAlign = "center"; ctx.textBaseline = "middle"
        ctx.font = font(14.0, true); ctx.fillStyle = "#FFF"
        ctx.fillText("◀ Top view", topViewBtn.cx, topViewBtn.cy)
        ctx.font = font(18.0, true)
        ctx.fillText("⟲", resetViewBtn.cx, resetViewBtn.cy)
        ctx.textBaseline = "alphabetic"
        if (!humanTurn) return
        val st = tipState()
        val hint = when {
            st == 2 -> "Scoop! A level cue this low digs under the ball — that's a foul"
            st == 1 -> "Miscue: the tip will slip off the ball"
            now() < povHintUntil -> "1 finger: ↔ aim, ↕ cue angle · 2 fingers (or mouse wheel): zoom, turn and tilt"
            elevDeg >= 0.5 -> "cue ${elevDeg.toInt()}°"
            else -> ""
        }
        if (hint.isEmpty()) return
        ctx.font = font(12.5, false)
        val tw = ctx.measureText(hint).width + 24
        ctx.fillStyle = when (st) { 2 -> "rgba(163,32,32,0.9)"; 1 -> "rgba(163,90,16,0.9)"; else -> "rgba(16,20,24,0.7)" }
        ctx.roundRect(a.cx - tw / 2, a.b - 34, tw, 26.0, 13.0); ctx.fill()
        ctx.fillStyle = "#FFF"; ctx.textBaseline = "middle"
        ctx.fillText(hint, a.cx, a.b - 21)
        ctx.textBaseline = "alphabetic"
    }

    /** Ball in hand (a pulsing ring round the cue ball) and a ball being moved, drawn on the cloth. */
    private fun drawPovMarks(ctx: Ctx2D, cueX: Double, cueY: Double) {
        if (phase != Phase.HUMAN) return
        ctx.setLineDash(noDash)
        if (circuit && revealTo == match.current) {
            ctx.lineWidth = 2.0; ctx.strokeStyle = "rgba(255,214,64,0.7)"
            for (id in match.circuit.targets[revealTo]) { val b = match.world.balls[id]; if (b.onTable) povRing(ctx, b.x, b.y, P.R * 1.45) }
        }
        if (match.ballInHand) {
            val pulse = 0.5 + 0.5 * sin(now() / 250.0)
            ctx.lineWidth = 2.5
            ctx.strokeStyle = "rgba(255,214,64,${(120 + 120 * pulse) / 255})"
            povRing(ctx, cueX, cueY, P.R * (1.5 + 0.25 * pulse))
            if (anyTouch(Touch.MOVE_CUE) && dragInvalid && toTable(dragX, dragY)) {
                ctx.strokeStyle = "rgba(255,64,64,0.8)"; povRing(ctx, povXY[0], povXY[1], P.R)
            }
        }
        if (movingBall >= 0) {
            val mb = match.world.balls[movingBall]
            ctx.lineWidth = 2.5; ctx.strokeStyle = "rgba(255,213,74,0.88)"
            povRing(ctx, mb.x, mb.y, P.R * 1.5)
            if (dragInvalid && toTable(dragX, dragY)) { ctx.strokeStyle = "rgba(255,64,64,0.8)"; povRing(ctx, povXY[0], povXY[1], P.R) }
        }
    }

    /** Mouse wheel over the 3D view: zoom (for a computer, which has no two-finger pinch). */
    fun wheel(x: Double, y: Double, deltaY: Double) {
        if (!pov || !tableArea.contains(x, y)) return
        povCtl.pinch(kotlin.math.exp(-deltaY * 0.0015))
    }

    private fun povLine(ctx: Ctx2D, x1: Double, y1: Double, x2: Double, y2: Double, h: Double) {
        // (On the octagon table's ramps the line runs up the ramp, not through it.)
        if (povCam.segment(x1, y1, h + Table.surfaceZ(x1, y1), x2, y2, h + Table.surfaceZ(x2, y2), povSeg)) ctx.line(povSeg[0], povSeg[1], povSeg[2], povSeg[3])
    }

    /** An outline of a circle of radius [r] lying on the cloth at (x, y). */
    private fun povRing(ctx: Ctx2D, x: Double, y: Double, r: Double) {
        ctx.beginPath()
        for (k in 0..24) {
            val ang = 2 * PI * k / 24
            if (!povCam.project(x + r * cos(ang), y + r * sin(ang), 0.002, povPt)) return
            if (k == 0) ctx.moveTo(povPt[0], povPt[1]) else ctx.lineTo(povPt[0], povPt[1])
        }
        ctx.stroke()
    }

    /** The aim guide, drawn on the cloth in 3D (before the cushions and balls, which can hide it). */
    private fun drawPovGuide(ctx: Ctx2D) {
        val c = match.world.cueBall
        if (!c.onTable) return
        val h = 0.002
        ctx.lineCap = "round"; ctx.lineJoin = "round"
        if (!showPrediction) {
            match.world.railPoint(aimAngle, hit)
            ctx.lineWidth = 2.0; ctx.setLineDash(dash); ctx.strokeStyle = "rgba(255,255,255,0.56)"
            povLine(ctx, c.x, c.y, hit[0], hit[1], h)
            ctx.setLineDash(noDash)
            return
        }
        updatePrediction()
        val pr = prediction
        if (pr.cueN < 2) return
        val pre = if (pr.contactIndex >= 0) pr.contactIndex else pr.cueN - 1
        ctx.lineWidth = 2.2
        for (i in 0 until pre) {
            val air = pr.cueAir[i] || pr.cueAir[i + 1]
            ctx.setLineDash(if (air) noDash else dash)
            ctx.strokeStyle = if (air) "rgba(255,213,74,0.88)" else "rgba(255,255,255,0.69)"
            povLine(ctx, pr.cueX[i].toDouble(), pr.cueY[i].toDouble(), pr.cueX[i + 1].toDouble(), pr.cueY[i + 1].toDouble(), h)
        }
        ctx.setLineDash(noDash)
        if (pr.hitId >= 0) {
            val legal = practice || match.legalFirstHit(pr.hitId, pr.crossedLine(P.HEAD_X))
            ctx.strokeStyle = if (legal) "rgba(255,255,255,0.88)" else "rgba(255,80,80,0.88)"
            ctx.lineWidth = 2.0
            if (povCam.project(pr.hitX, pr.hitY, P.R, povPt)) {
                val r = povCam.ballRadius(povPt[2])
                val gx = povPt[0]; val gy = povPt[1]
                ctx.circle(gx, gy, r); ctx.stroke()
                if (!legal) { val k = r * 0.55; ctx.line(gx - k, gy - k, gx + k, gy + k); ctx.line(gx - k, gy + k, gx + k, gy - k) }
            }
            ctx.lineWidth = 3.0
            for (i in 0 until pr.objN - 1) povLine(ctx, pr.objX[i].toDouble(), pr.objY[i].toDouble(), pr.objX[i + 1].toDouble(), pr.objY[i + 1].toDouble(), h)
            ctx.strokeStyle = "rgba(255,255,255,0.5)"; ctx.lineWidth = 1.8
            for (i in pre until pr.cueN - 1) povLine(ctx, pr.cueX[i].toDouble(), pr.cueY[i].toDouble(), pr.cueX[i + 1].toDouble(), pr.cueY[i + 1].toDouble(), h)
        }
        if (pr.scratch) {
            ctx.strokeStyle = "rgba(255,80,80,0.88)"; ctx.lineWidth = 2.0
            povRing(ctx, pr.cueX[pr.cueN - 1].toDouble(), pr.cueY[pr.cueN - 1].toDouble(), P.R * 1.3)
        }
        if (!pr.landX.isNaN()) {
            ctx.strokeStyle = "rgba(255,213,74,0.94)"; ctx.lineWidth = 2.0
            povRing(ctx, pr.landX, pr.landY, P.R)
        }
    }

    /** The called pocket (or, when a call is needed, every pocket) ringed in gold. */
    private fun drawPovCalled(ctx: Ctx2D) {
        if (pocketWars) {
            ctx.setLineDash(noDash); ctx.lineWidth = 4.0
            for ((i, pk) in Table.pockets.withIndex()) {
                val o = match.pockets.owner.getOrElse(i) { -1 }
                if (o >= 0) { ctx.strokeStyle = PW_COLORS[o]; povRing(ctx, pk.cx, pk.cy, pk.capR + 0.012) }
            }
        }
        val called = match.calledPocket
        val pulse = 0.5 + 0.5 * sin(now() / 200.0)
        ctx.setLineDash(noDash)
        if (called >= 0) {
            val pk = Table.pockets[called]
            ctx.lineWidth = 3.0; ctx.strokeStyle = "#FFD640"
            povRing(ctx, pk.cx, pk.cy, pk.capR + 0.02)
        } else if (match.callRequired()) {
            val flash = now() < callFlashUntil
            ctx.lineWidth = if (flash) 3.0 else 1.5
            ctx.strokeStyle = if (flash) "rgba(255,90,90,${(150 + 100 * pulse) / 255})" else "rgba(255,214,64,${(60 + 80 * pulse) / 255})"
            for (pk in Table.pockets) povRing(ctx, pk.cx, pk.cy, pk.capR + 0.02)
        }
    }

    /** The cue, seen from just above it: from the tip at the ball back past the bottom of the screen. */
    private fun drawPovCue(ctx: Ctx2D, cx: Double, cy: Double) {
        val k = povScene.cue(povCam, cx, cy, aimAngle, toRad(elevDeg), spinX * P.TIP_LIMIT, -spinY * P.TIP_LIMIT,
            0.012 + power * 0.07, stickSegs, 0.0065, 0.015, povCue)
        val q = povCue
        for (i in 0 until k - 1) {
            val o = i * 4
            val lx = (q[o] + q[o + 4]) / 2; val ly = (q[o + 1] + q[o + 5]) / 2
            val rx = (q[o + 2] + q[o + 6]) / 2; val ry = (q[o + 3] + q[o + 7]) / 2
            if (hypot(rx - lx, ry - ly) < 0.5) continue
            val g = ctx.createLinearGradient(lx, ly, rx, ry)
            g.addColorStop(0.0, col(stickColors[i * 2 + 1])); g.addColorStop(0.45, col(stickColors[i * 2])); g.addColorStop(1.0, col(stickColors[i * 2 + 1]))
            ctx.beginPath()
            ctx.moveTo(q[o], q[o + 1]); ctx.lineTo(q[o + 2], q[o + 3]); ctx.lineTo(q[o + 6], q[o + 7]); ctx.lineTo(q[o + 4], q[o + 5])
            ctx.closePath()
            ctx.fillStyle = g; ctx.fill()
        }
    }

    private fun setSpin(x: Double, y: Double) {
        var u = (x - spinCx) / (spinR * 0.92); var v = (y - spinCy) / (spinR * 0.92)
        val m = hypot(u, v)
        if (m > 1) { u /= m; v /= m }
        spinX = u; spinY = v
        tut?.onSpin(u, v)
    }

    private companion object {
        /** Games that can be practised with their own rules. */
        val RULES_PRACTICE = setOf(GameMode.EIGHT_BALL, GameMode.NINE_BALL, GameMode.CIRCUIT, GameMode.POCKET_WARS, GameMode.CUTTHROAT)
        /** Pocket Wars colours: player 1 / you blue, player 2 / the CPU orange. */
        val PW_COLORS = arrayOf("#4DA3FF", "#FF8A3D")
        const val RPS_BEAT = 0.42
        /** Bumper pool cue length, as a fraction of a full-length one (shorter than a real bumper cue, so it stays over the table). */
        const val SHORT_CUE = 0.55
        /** 3D view: radians the cue turns per px of sideways drag at moderate speed. */
        const val POV_AIM_PER_PX = 0.0022
        /** 3D view: degrees the cue rises per px of upward drag. */
        const val POV_ELEV_PER_PX = 0.3
        /** On screen, a jumping ball is drawn this many times higher than it really is. */
        const val JUMP_LIFT = 1.6
        /** ...and this much bigger per metre of height (closer to the viewer). */
        const val JUMP_GROW = 8.0
        /** ...and a ball up an octagon table's ramp this much bigger per metre (it's only a little nearer). */
        const val RAMP_GROW = 1.6
        val GESTURES = arrayOf("\u270A", "\u270B", "\u270C\uFE0F")   // fist, flat hand, peace sign
        val GESTURE_NAMES = arrayOf("Rock", "Paper", "Scissors")
        val SHAKE_WORDS = arrayOf("Rock…", "Paper…", "Scissors…")
    }
}
