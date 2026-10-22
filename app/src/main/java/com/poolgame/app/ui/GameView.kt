package com.poolgame.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.poolgame.app.core.Ai
import com.poolgame.app.core.GameSave
import com.poolgame.app.core.GameSettings
import com.poolgame.app.core.CallMode
import com.poolgame.app.core.GameMode
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

@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val mode: GameMode,
    private val vsCpu: Boolean,
    private val cpuLevel: Int,
    soundOn: Boolean,
    private val shotClock: Int,          // seconds per shot, 0 = off
    private val practiceMode: Boolean,   // practice: the free table, or (with GameMode.TRICK) one trick shot
    private val trickLevel: Int,         // trick-shot practice: which level
    aimSensitivity: Int,                 // fine-aim wheel speed, 1..10
    var showPrediction: Boolean,         // draw where the balls will go (otherwise just an aim line)
    resume: String?,                     // a saved game to continue, or null for a new game
    private val callMode: CallMode,      // called-pocket rule
    private val host: Host,
    private val tutorialMode: Boolean = false,  // the hands-on tutorial (a practice table with a guide)
    private val tableSize: TableSize = TableSize.DEFAULT   // table for 8-ball, 9-ball and practice
) : View(context), Choreographer.FrameCallback {

    interface Host {
        fun onMenuRequested()
        fun onExitToMenu()
        /** Called once per finished game; returns a one-line record summary to show. */
        fun onGameFinished(winner: Int): String
        /** Rock, paper, scissors decided who breaks: [rounds] throws, ties included. */
        fun onRpsFinished(winner: Int, rounds: Int)
    }

    private enum class Phase { RPS, HANDOFF, HUMAN, CPU_THINK, CPU_ANIM, RUNNING, PAUSE, OVER }
    private enum class Rps { PICK, SHAKE, REVEAL }
    private enum class Touch { NONE, AIM, POWER, SPIN, WHEEL, MOVE_CUE, BUTTON, PENDING, MOVE_BALL, TAP_BALL, POV, CAM }

    /** Free-table practice: no rules, move any ball. (The tutorial runs on the practice table too.) */
    private val practice = practiceMode && mode != GameMode.TRICK

    // The tutorial: one control at a time is highlighted, with a card saying what to do.
    private var tut: Tutorial? = null
    private var tutT = 0.0                       // seconds into the current step (for the demo finger)
    private var tutLeaving = false
    private var tutAimFrom = 0.0; private var tutAimTo = 0.0; private var tutAimK = 1.0
    private val tutCard = RectF()
    private val tutBtn = RectF()
    private val tutSkip = RectF()
    private val tutShape = RectF()
    private val tutPath = Path()
    private val tutFinger = DoubleArray(4)
    private val tutCloth = RectF()
    private var tutLines: List<String> = emptyList()   // the card's text, wrapped (kept until it changes)
    private var tutLinesFor = ""
    private var tutLinesW = 0f
    /** Practising one trick shot on your own: unlimited attempts, the table resets after each. */
    private val trickPractice = practiceMode && mode == GameMode.TRICK
    private var tpTries = 0
    private var tpMade = 0

    private val names = when {
        vsCpu -> arrayOf("You", "CPU")
        trickPractice -> arrayOf("You", "You")
        else -> arrayOf("Player 1", "Player 2")
    }
    private val rng = Random(System.nanoTime())
    // 8-ball, 9-ball and practice use the chosen table size (set before the layout reads its dimensions);
    // trick shots always use the 9 ft gap table.
    private var match = run { useTable(); Match(mode, names) }
    private var breaker = 0
    private var phase = Phase.HUMAN
    private var generation = 0

    // Shot controls
    private var aimAngle = 0.0
    private var power = 0f            // 0..1
    private var lastPower = 0.35f     // used for the guide while the power bar is untouched
    private var spinX = 0f            // -1..1 (right english > 0)
    private var spinY = 0f            // -1..1 (screen down = draw)
    private var elevDeg = 0f          // cue elevation, 0..60 degrees (raised in the 3D view; jump shots)
    // Multi-touch: each finger gets its own role, so you can hold the power bar
    // with one finger and fine-tune the aim with another.
    private val ptrMode = Array(10) { Touch.NONE }
    private val ptrStartY = FloatArray(10)
    private val ptrLastY = FloatArray(10)
    private val ptrDownX = FloatArray(10)
    private val ptrDownY = FloatArray(10)
    private val ptrDownAt = LongArray(10)
    private val ptrBall = IntArray(10)
    private val ptrLastT = LongArray(10)     // fine-aim wheel: time of the last move (ms)
    private val ptrVel = FloatArray(10)      // fine-aim wheel: smoothed finger speed (dp/s)
    private val ptrLastX = FloatArray(10)    // 3D view: last finger x
    private val ptrAxis = IntArray(10)       // 3D view drag: 0 not decided yet, 1 sideways (aim), 2 up/down (cue angle)
    private var wheelOffset = 0f
    private var dragInvalid = false
    private var dragX = 0f; private var dragY = 0f

    // Practice mode
    private var practiceSnapshot: World? = null
    private val retryBtn = RectF()
    private val rackBtn = RectF()
    private val clearBtn = RectF()
    private var movingBall = -1

    // Ball tray left of the table: pocketed balls roll down it and line up from the bottom,
    // in the order they were made, like the ball return on a bar table.
    private val trayRect = RectF()
    private var potR = 0f
    private val potIds = IntArray(16)
    private val potY = FloatArray(16)
    private val potV = FloatArray(16)
    private var potN = 0
    private var trayShader: Shader? = null

    // Rock, paper, scissors decides who breaks.
    private var rps = Rps.PICK
    private val rpsPick = intArrayOf(-1, -1)      // 0 rock, 1 paper, 2 scissors
    private var rpsT = 0.0
    private var rpsBeat = 0
    private var rpsTie = false
    private var rpsRounds = 0                     // throws this game, ties included (for the stats)
    private val rpsBtn = Array(2) { Array(3) { RectF() } }
    private val rpsPanel = RectF()
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val emojiMetrics = Paint.FontMetrics()

    // Fine-aim wheel: level 5 is ~0.01 degrees per dp of finger travel; each step is 1.6x.
    private val wheelRadPerDp = 0.0002 * Math.pow(1.6, (aimSensitivity.coerceIn(1, 10) - 5).toDouble())

    // Saving: the game as it stood at the start of the current turn.
    private var turnSave: String? = null

    private var callFlashUntil = 0L

    // Trick shots: a banner announcing each level as it starts.
    private val trick = mode == GameMode.TRICK
    /** Bumper pool: no cue ball (you shoot your own balls), its own table, and an opening shot both players take at once. */
    private val bumper = mode == GameMode.BUMPER
    /** Bumper pool practice: the bumper table on your own, any ball, no rules. */
    private val bumperPractice = practice && bumper
    /** Circuit Pool: three secret target balls each. */
    private val circuit = mode == GameMode.CIRCUIT
    /**
     * Circuit Pool: whose targets are on show (-1 for nobody). Against the CPU it's always you; in pass & play
     * they're hidden at each change of turn until the next player taps to see theirs.
     */
    private var revealTo = if (vsCpu) 0 else -1
    private var bannerLevel = -1
    private var bannerT = 0.0

    // Shot clock
    private var clockStarted = false
    private var clockLeft = 0.0
    private var lastBeep = 0
    var menuOpen = false

    // CPU animation
    private var cpuPlan: Plan? = null
    private var animT = 0.0
    private var animFromAngle = 0.0
    private var animFromX = 0.0; private var animFromY = 0.0
    private var thinkingSince = 0L

    private var pauseT = 0.0
    private var lastFrame = 0L
    private var running = false
    private var message = ""
    private var gameOverTitle = ""
    private var recordLine = ""

    private class Sink(val id: Int, val x: Double, val y: Double, var age: Float)
    private val sinks = ArrayList<Sink>()

    private val balls = BallRenderer()
    private val sfx = Sfx(context, soundOn)

    // Aim prediction (runs the real physics for the first part of the shot)
    private val prediction = Prediction()
    private val hit = DoubleArray(4)
    private val scratchWorld = World()
    private val predKey = DoubleArray(9) { Double.NaN }

    // The 3D view: opened by tapping a ball or the 3D view button, closed by Top view. It stays on
    // through shots and turns; the camera orbits the cue ball wherever the player has put it.
    private var pov = false
    private val povCtl = PovControl()
    private val povCam = PovCam()
    // While balls roll (and while dragging a ball in 3D) the camera holds still instead of following the cue ball.
    private var povLocked = false
    private var povLockX = 0.0; private var povLockY = 0.0
    private val povXY = DoubleArray(2)
    private val resetViewBtn = RectF()
    private val view3dBtn = RectF()
    private var povHintUntil = 0L
    // Two-finger camera gesture: the previous spread and midpoint of the two fingers.
    private var camDist = 0f; private var camMidX = 0f; private var camMidY = 0f
    private val ptrX = FloatArray(10); private val ptrY = FloatArray(10)
    private val povScene = PovScene()
    private val povBalls = BallRenderer()
    private val povView = DoubleArray(9)
    private val povSeg = DoubleArray(4)
    private val povPt = DoubleArray(3)
    private val povCue = DoubleArray(9 * 4)
    private val topViewBtn = RectF()

    // Layout
    private var scale = 1f
    private var ox = 0f; private var oy = 0f
    private var rpx = 10f
    private var dp = 1f
    private var hudH = 0f
    private val tableArea = RectF()
    private val powerRect = RectF()
    private var spinCx = 0f; private var spinCy = 0f; private var spinR = 0f
    private val wheelRect = RectF()
    private val menuBtn = RectF()
    private val rematchBtn = RectF()
    private val menuExitBtn = RectF()
    private val overPanel = RectF()
    private val leftCard = RectF()
    private val rightCard = RectF()
    private var background: Bitmap? = null

    // Cached drawing objects (avoid per-frame allocations, which cause GC hitches)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var dash: DashPathEffect? = null
    private val path = Path()
    private var powerShader: Shader? = null
    private var spinShader: Shader? = null
    private var wheelShader: Shader? = null
    private val stickShaders = arrayOfNulls<Shader>(8)
    private val stickSegs = doubleArrayOf(0.0, 0.008, 0.03, 0.72, 0.74, 0.98, 1.22, 1.43, 1.45)
    private val stickColors = intArrayOf(
        0xFF3D7BC9.toInt(), 0xFF24518A.toInt(),   // chalked tip
        0xFFF3EEDF.toInt(), 0xFFBFB8A4.toInt(),   // ferrule
        0xFFE9CF9E.toInt(), 0xFFB08B54.toInt(),   // maple shaft
        0xFF2B2B2B.toInt(), 0xFF101010.toInt(),   // joint
        0xFF5A2A14.toInt(), 0xFF2E1408.toInt(),   // forearm
        0xFF1C1C1C.toInt(), 0xFF080808.toInt(),   // wrap
        0xFF5A2A14.toInt(), 0xFF2E1408.toInt(),   // butt
        0xFF111111.toInt(), 0xFF111111.toInt()    // bumper
    )

    init {
        isFocusable = true
        balls.plain = practice && !tutorialMode && !bumper
        povBalls.plain = balls.plain
        balls.bumper = bumper
        povBalls.bumper = bumper
        if (resume == null || !resumeGame(resume)) newGame(0)
    }

    private fun makeListener() = object : WorldListener {
        override fun onBallHit(speed: Double) = sfx.ball(speed)
        override fun onCushion(speed: Double) { if (speed > 0.15) sfx.rail(speed) }
        override fun onPocket(id: Int) {
            sfx.drop()
            val b = match.world.balls[id]
            sinks += Sink(id, b.x, b.y, 0f)
            if (id == 0) crowdAww() else addToTray(id)
        }
        override fun onOffTable(id: Int) = crowdAww()
    }

    /** Scratch or a ball off the table: the crowd groans, a beat after it happens. */
    private fun crowdAww() { postDelayed({ sfx.aww() }, 180) }

    private fun isCpu(player: Int) = vsCpu && player == 1

    // ------------------------------------------------------------------ game flow

    fun newGame(breakerPlayer: Int) {
        generation++
        breaker = breakerPlayer
        useTable()
        match = Match(mode, names)
        match.world.trackOrientation = true
        match.world.listener = makeListener()
        match.rack(rng, breaker)
        match.callMode = if (practice) CallMode.OFF else callMode
        revealTo = if (vsCpu) 0 else -1
        if (practice) {
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
        spinX = 0f; spinY = 0f; power = 0f; elevDeg = 0f
        gameOverTitle = ""
        recordLine = ""
        bannerLevel = -1; bannerT = 0.0
        rpsRounds = 0
        message = match.message
        // Trick shots don't need rock, paper, scissors (who goes first doesn't matter), nor does
        // bumper pool (both players open at the same moment).
        if (practice || trick || bumper) startTurn() else startRps(false)
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

    fun isPractice() = practiceMode

    private fun settings() = GameSettings(mode, vsCpu, cpuLevel, shotClock, practiceMode, breaker, match.callMode, tableSize)

    private fun useTable() {
        when (mode) {
            GameMode.TRICK -> {}
            GameMode.BUMPER -> Table.useBumper()
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
        spinX = 0f; spinY = 0f; power = 0f; elevDeg = 0f
        gameOverTitle = ""; recordLine = ""
        revealTo = if (vsCpu) 0 else -1
        message = when {
            practice -> practiceHint()
            trickPractice -> "Practice · ${Trick.LEVELS[match.trick.level].name}"
            else -> "Game resumed · " + match.message
        }
        bannerLevel = -1
        startTurn()
        return true
    }

    /**
     * The game to save when leaving it, or null when it's over. A shot that is still rolling is
     * undone (the saved game is from the start of that turn).
     */
    fun saveState(): String? {
        if (tutorialMode) return null
        if (match.winner >= 0 || phase == Phase.OVER || phase == Phase.RPS) return null
        return when (phase) {
            Phase.HUMAN, Phase.PAUSE -> GameSave.write(match, settings())
            else -> turnSave ?: GameSave.write(match, settings())
        }
    }

    private fun practiceHint() =
        if (bumperPractice) "Bumper practice · tap a ball to shoot it, hold it to move it" else "Practice · hold any ball to move it"

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
                vsCpu && w == 0 -> "You win!"
                vsCpu -> "CPU wins"
                else -> "${names[w]} wins!"
            }
            message = match.message
            recordLine = host.onGameFinished(w)
            return
        }
        power = 0f
        elevDeg = 0f
        povLocked = false
        turnSave = GameSave.write(match, settings())
        if (isCpu(match.current)) {
            phase = Phase.CPU_THINK
            thinkingSince = System.nanoTime()
            val snapshot = Match(mode, names).also { it.copyFrom(match) }
            val gen = generation
            val budget = 700L + 160L * cpuLevel
            Thread {
                val plan = try { Ai(cpuLevel, rng.nextLong()).plan(snapshot, budget) } catch (t: Throwable) { null }
                post {
                    if (gen != generation) return@post
                    val wait = 600 - (System.nanoTime() - thinkingSince) / 1_000_000
                    if (wait > 0) postDelayed({ if (gen == generation) beginCpuAnim(plan) }, wait) else beginCpuAnim(plan)
                }
            }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        } else if (circuit && revealTo != match.current) {
            // Circuit Pool, pass & play: hide the targets while the phone changes hands.
            revealTo = -1
            phase = Phase.HANDOFF
            clearTouches()
        } else beginHumanTurn()
    }

    private fun beginHumanTurn() {
        phase = Phase.HUMAN
        spinX = 0f; spinY = 0f
        clockLeft = shotClock.toDouble()
        // With ball in hand the clock waits until you start aiming or touch the power bar.
        clockStarted = !match.ballInHand
        lastBeep = 0
        aimAtNearestTarget()
    }

    private fun aimAtNearestTarget() {
        val c = match.world.cueBall
        if (bumper) {
            // Bumper pool: start out aimed at the cup you're shooting for.
            val cup = Table.pockets[match.targetCup(match.current)]
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

    /** For a CPU safety when a call is required: the pocket nearest the ball it is aiming at. */
    private fun nearestPocketToAim(): Int {
        match.world.castCue(aimAngle, hit)
        val id = hit[3].toInt()
        val b = if (hit[0] == 1.0 && id > 0) match.world.balls[id] else match.world.cueBall
        return match.world.nearestPocket(b.x, b.y)
    }

    private fun speedFor(pw: Float): Double = 0.25 + (P.MAX_CUE_SPEED - 0.25) * pw.toDouble().pow(1.6)
    private fun powerFor(speed: Double): Float = ((speed - 0.25) / (P.MAX_CUE_SPEED - 0.25)).coerceIn(0.0, 1.0).pow(1 / 1.6).toFloat()

    private fun shoot(angle: Double, speed: Double, side: Double, vert: Double, elev: Double) {
        if (match.openingPending()) { openingShot(angle, speed, side, vert, elev); return }
        if (practice) practiceSnapshot = World().also { it.copyFrom(match.world) }
        turnSave = GameSave.write(match, settings())
        sfx.strike(speed)
        match.shoot(angle, speed, side, vert, elev)
        phase = Phase.RUNNING
        clearTouches()
        lockPov()
    }

    /**
     * Bumper pool's opening: each player's shot is held (unseen) until the other has set theirs,
     * then both marked balls go at the same moment.
     */
    private fun openingShot(angle: Double, speed: Double, side: Double, vert: Double, elev: Double) {
        clearTouches()
        power = 0f
        if (!match.setOpening(angle, speed, side, vert, elev)) {
            message = match.message
            phase = Phase.PAUSE; pauseT = 0.9
            return
        }
        turnSave = GameSave.write(match, settings())
        sfx.strike(max(speed, 1.0))
        match.fireOpening()
        phase = Phase.RUNNING
        lockPov()
    }

    /** Practice: holding a finger still on a ball for a moment picks it up. Always returns true. */
    private fun checkLongPress(): Boolean {
        if (!practice) return true
        val now = System.nanoTime()
        for (i in ptrMode.indices) if (ptrMode[i] == Touch.PENDING && now - ptrDownAt[i] > 280_000_000L) {
            ptrMode[i] = Touch.MOVE_BALL
            movingBall = ptrBall[i]
            if (pov) lockPov()
            dragInvalid = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        return true
    }

    private fun moveBall(id: Int, x: Float, y: Float) {
        dragX = x; dragY = y
        if (!toTable(x, y)) { dragInvalid = true; return }
        val px = povXY[0].coerceIn(P.R, P.L - P.R); val py = povXY[1].coerceIn(P.R, P.W - P.R)
        val w = match.world
        if (w.isFree(px, py, id)) {
            val b = w.balls[id]
            b.x = px; b.y = py; b.stop()
            dragInvalid = false
        } else dragInvalid = true
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
        match.world.copyFrom(snap)
        for (b in match.world.balls) b.moving = false
        if (bumperPractice) match.current = match.ownerOf(match.world.cue).coerceAtLeast(0)
        sinks.clear()
        phase = Phase.HUMAN
        power = 0f
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
        power = 0f; elevDeg = 0f; spinX = 0f; spinY = 0f
        message = "Practice · ${Trick.LEVELS[match.trick.level].name}"
        startTurn()
    }

    private fun practiceClear() {
        // (Bumper practice keeps the ball you're shooting.)
        for (id in 1..15) if (id != match.world.cue) { val b = match.world.balls[id]; b.onTable = false; b.stop() }
        message = "Table cleared · tap a ball in the tray to bring it back"
    }

    private fun returnBall(id: Int) {
        val w = match.world
        var r = 0.0
        while (r < 0.6) {
            val steps = if (r == 0.0) 1 else 12
            for (k in 0 until steps) {
                val a = 2 * Math.PI * k / steps
                val x = P.L / 2 + 0.25 + r * cos(a); val y = P.W / 2 + r * sin(a)
                if (w.isFree(x, y, id)) {
                    val b = w.balls[id]
                    b.x = x; b.y = y; b.stop(); b.onTable = true
                    message = "Hold the ball to move it"
                    if (bumperPractice && !w.cueBall.onTable) bumperPracticePick(id)
                    return
                }
            }
            r += 0.06
        }
    }

    private fun clearTouches() { for (i in ptrMode.indices) ptrMode[i] = Touch.NONE }
    private fun anyTouch(t: Touch): Boolean { for (m in ptrMode) if (m == t) return true; return false }

    private fun inTray(id: Int): Boolean { for (i in 0 until potN) if (potIds[i] == id) return true; return false }

    private fun addToTray(id: Int) {
        if (id <= 0 || potN >= potIds.size || inTray(id)) return
        potIds[potN] = id
        potY[potN] = trayRect.top - potR * 2      // rolls in from the top of the tray
        potV[potN] = 0f
        potN++
    }

    private fun traySlotY(i: Int) = trayRect.bottom - 3 * dp - potR - i * potR * 2.06f

    /**
     * Keeps the tray in step with the table: balls put back on the table (respotted, or returned in
     * practice) leave it, and anything off the table that isn't listed yet is added in number order
     * (a resumed game, or practice's Clear table). Then the balls roll down to their places.
     */
    private fun updateTray(dt: Float) {
        val w = match.world
        var j = 0
        for (i in 0 until potN) if (!w.balls[potIds[i]].onTable) {
            potIds[j] = potIds[i]; potY[j] = potY[i]; potV[j] = potV[i]; j++
        }
        potN = j
        // (Not in the tutorial: its table simply has fewer balls; only the ones you pot go in the tray.)
        if (phase != Phase.RUNNING && !tutorialMode) for (id in match.ballIds()) if (!w.balls[id].onTable) addToTray(id)
        val g = 2200f * dp
        for (i in 0 until potN) {
            val target = traySlotY(i)
            if (potY[i] < target || potV[i] < 0f) {
                potV[i] += g * dt
                potY[i] += potV[i] * dt
                if (potY[i] >= target) {
                    potY[i] = target
                    potV[i] = if (potV[i] > 160 * dp) -potV[i] * 0.22f else 0f
                }
            } else { potY[i] = target; potV[i] = 0f }
        }
    }

    private fun update(dt: Double) {
        updateTutorial(dt)
        if (bannerT > 0) bannerT -= dt
        if (sinks.isNotEmpty()) {
            for (s in sinks) s.age += dt.toFloat()
            sinks.removeAll { it.age > 0.35f }
        }
        updateTray(dt.toFloat())
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
            Phase.HUMAN -> if (checkLongPress() && !practiceMode && shotClock > 0 && !menuOpen && clockStarted) {
                clockLeft -= dt
                val secs = ceil(clockLeft).toInt()
                if (secs in 1..5 && secs != lastBeep) { lastBeep = secs; sfx.beep() }
                if (clockLeft <= 0) {
                    clearTouches()
                    power = 0f
                    if (match.openingPending()) openingShot(aimAngle, 0.0, 0.0, 0.0, 0.0)   // time's up: no opening shot
                    else {
                        val out = match.timeFoul()
                        message = out.message
                        phase = Phase.PAUSE
                        pauseT = 1.0
                    }
                }
            }
            Phase.RUNNING -> {
                match.world.advance(dt)
                if (!match.world.anyMoving() && practice) {
                    tut?.onShotDone(!match.world.balls[Tutorial.TARGET_BALL].onTable)
                    if (bumperPractice) {
                        val jumped = (match.world.log.air and (1 shl match.world.cue)) != 0
                        // The ball you shot went down: carry on with the nearest one left.
                        if (!match.world.cueBall.onTable) bumperPracticePick(-1)
                        message = when {
                            match.world.balls.none { it.onTable } -> "All in! Re-rack, or tap a ball in the tray"
                            jumped -> "That one left the cloth — in a game, a jump is a foul"
                            else -> ""
                        }
                    } else if (!match.world.cueBall.onTable) {
                        match.respawnCue()
                        message = "Cue ball respotted · hold it to move it"
                    } else message = if (match.world.log.scooped) "Scoop shot — in a game that's a foul" else ""
                    phase = Phase.HUMAN
                    power = 0f
                    povLocked = false
                } else if (!match.world.anyMoving() && trickPractice) {
                    val out = match.resolveTrickPractice()
                    tpTries++
                    if (out.made) tpMade++
                    message = out.message
                    phase = Phase.PAUSE
                    pauseT = if (out.made) 1.6 else 1.3
                    if (out.heckle) postDelayed({ sfx.heckle() }, 260)
                } else if (!match.world.anyMoving()) {
                    val out = match.resolve()
                    message = out.message
                    phase = Phase.PAUSE
                    pauseT = if (trick) (if (out.made) 1.6 else 1.3) else if (out.foul || out.winner >= 0) 0.9 else 0.45
                    if (out.heckle) postDelayed({ sfx.heckle() }, 260)
                }
            }
            Phase.PAUSE -> {
                pauseT -= dt
                if (pauseT <= 0) startTurn()
            }
            Phase.CPU_ANIM -> {
                val p = cpuPlan ?: return
                animT += dt
                val tAim = if (p.hasPlacement) 0.45 else 0.0
                val aimDur = 0.75
                val pullDur = 0.55
                if (animT >= tAim) {
                    val k = ((animT - tAim) / aimDur).coerceIn(0.0, 1.0)
                    val e = k * k * (3 - 2 * k)
                    var d = p.angle - animFromAngle
                    while (d > Math.PI) d -= 2 * Math.PI
                    while (d < -Math.PI) d += 2 * Math.PI
                    aimAngle = animFromAngle + d * e
                    spinX = (p.side / P.TIP_LIMIT * e).toFloat()
                    spinY = (-p.vert / P.TIP_LIMIT * e).toFloat()
                    elevDeg = (Math.toDegrees(p.elevation) * e).toFloat()
                }
                if (animT >= tAim + aimDur) {
                    val k = ((animT - tAim - aimDur) / pullDur).coerceIn(0.0, 1.0)
                    power = powerFor(p.speed) * k.toFloat()
                }
                if (animT >= tAim + aimDur + pullDur + 0.12) {
                    cpuPlan = null
                    shoot(p.angle, p.speed, p.side, p.vert, p.elevation)
                }
            }
            else -> {}
        }
    }

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
                    spinX = 0f; spinY = 0f; elevDeg = 0f; power = 0f
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
            while (d > Math.PI) d -= 2 * Math.PI
            while (d < -Math.PI) d += 2 * Math.PI
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
    private fun regionAt(x: Float, y: Float): TutTarget {
        val c = match.world.cueBall
        return when {
            powerRect.left - 20 * dp <= x && x <= powerRect.right + 20 * dp && y >= powerRect.top - 30 * dp && y <= powerRect.bottom + 20 * dp -> TutTarget.POWER
            hypot(x - spinCx, y - spinCy) <= spinR * 1.2f -> TutTarget.SPIN
            x >= wheelRect.left - 6 * dp && x <= wheelRect.right + 8 * dp && y >= wheelRect.top - 6 * dp && y <= wheelRect.bottom + 8 * dp -> TutTarget.WHEEL
            !pov && match.ballInHand && hypot(x - sx(c.x), y - sy(c.y)) < max(rpx * 3f, 28 * dp) -> TutTarget.CUE
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

    /** The highlighted control's outline: [tutShape] is its box, and [tutPath] its exact shape. Returns false for none. */
    private fun tutorialShape(t: Tutorial): Boolean {
        tutPath.reset()
        val pad = 6 * dp
        when (t.step.target) {
            TutTarget.TABLE -> if (pov) tutShape.set(tableArea) else tutShape.set(sx(-0.04), sy(-0.04), sx(P.L + 0.04), sy(P.W + 0.04))
            TutTarget.WHEEL -> tutShape.set(wheelRect.left - pad, wheelRect.top - 22 * dp, wheelRect.right + pad, wheelRect.bottom + pad)
            TutTarget.SPIN -> {
                val r = spinR + 5 * dp
                tutShape.set(spinCx - r, spinCy - r, spinCx + r, spinCy + r)
                tutPath.addCircle(spinCx, spinCy, r, Path.Direction.CW)
                return true
            }
            TutTarget.POWER -> tutShape.set(powerRect.left - 10 * dp, powerRect.top - 24 * dp, powerRect.right + 10 * dp, powerRect.bottom + 18 * dp)
            TutTarget.TRAY -> tutShape.set(trayRect.left - pad, trayRect.top - pad, trayRect.right + pad, trayRect.bottom + pad)
            TutTarget.CUE -> {
                val c = match.world.cueBall
                val r = max(rpx * 2.6f, 22 * dp)
                tutShape.set(sx(c.x) - r, sy(c.y) - r, sx(c.x) + r, sy(c.y) + r)
                tutPath.addCircle(sx(c.x), sy(c.y), r, Path.Direction.CW)
                return true
            }
            TutTarget.MENU -> tutShape.set(menuBtn.left - pad, menuBtn.top - pad, menuBtn.right + pad, menuBtn.bottom + pad)
            TutTarget.NONE -> return false
        }
        tutPath.addRoundRect(tutShape, 12 * dp, 12 * dp, Path.Direction.CW)
        return true
    }

    /** Splits text into lines no wider than [maxW]. */
    private fun wrapAll(s: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in s.split(" ")) {
            val t = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(t) > maxW && line.isNotEmpty()) { out += line; line = word } else line = t
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** Dims everything but the highlighted control, then draws the demo finger, the card and Skip. */
    private fun drawTutorial(canvas: Canvas) {
        val t = tut ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val pulse = (0.5 + 0.5 * sin(System.nanoTime() / 2.2e8)).toFloat()
        val hasShape = tutorialShape(t)
        paint.style = Paint.Style.FILL; paint.shader = null
        if (!t.rolling) {
            paint.color = if (hasShape) 0x8C000000.toInt() else 0xA6000000.toInt()
            canvas.save()
            if (hasShape) canvas.clipOutPath(tutPath)
            canvas.drawRect(0f, 0f, w, h, paint)
            canvas.restore()
        }
        if (hasShape && !t.rolling) {
            guide.pathEffect = null
            guide.strokeWidth = (2.5f + 1.5f * pulse) * dp
            guide.color = Color.argb((170 + 85 * pulse).toInt(), 242, 201, 76)
            canvas.drawPath(tutPath, guide)
            // The demo finger, looping through the gesture this step wants.
            if (t.flash <= 0.0) {
                var tapX = Double.NaN; var tapY = Double.NaN
                if (t.step == Tutorial.Step.POV) {
                    val one = match.world.balls[Tutorial.TARGET_BALL]
                    if (!pov && one.onTable) { tapX = sx(one.x).toDouble(); tapY = sy(one.y).toDouble() }
                    else if (pov && t.jumpRaised) { tapX = topViewBtn.centerX().toDouble(); tapY = topViewBtn.centerY().toDouble() }
                }
                t.finger(tutT, tutShape.left.toDouble(), tutShape.top.toDouble(), tutShape.right.toDouble(), tutShape.bottom.toDouble(), tutFinger, tapX, tapY)
                val fx = tutFinger[0].toFloat(); val fy = tutFinger[1].toFloat()
                val a = tutFinger[2].toFloat(); val pr = tutFinger[3].toFloat()
                val r = 15 * dp * (1.15f - 0.25f * pr)
                paint.color = Color.argb((90 * a + 60 * a * pr).toInt(), 255, 255, 255)
                canvas.drawCircle(fx, fy, r, paint)
                guide.strokeWidth = 2 * dp
                guide.color = Color.argb((230 * a).toInt(), 255, 255, 255)
                canvas.drawCircle(fx, fy, r, guide)
                if (pr > 0.05f) {
                    paint.color = Color.argb((200 * a * pr).toInt(), 242, 201, 76)
                    canvas.drawCircle(fx, fy, 5 * dp, paint)
                }
            }
        }

        // The card: over the top of the table, or in the middle for the first and last cards.
        val cloth = tutCloth.apply { set(sx(0.0), sy(0.0), sx(P.L), sy(P.W)) }
        val cw = min(cloth.width() * 0.8f, 470 * dp)
        val pad = 16 * dp
        text.typeface = regular; text.textSize = 13.5f * dp
        val body = t.body
        if (body != tutLinesFor || cw != tutLinesW) { tutLines = wrapAll(body, cw - 2 * pad); tutLinesFor = body; tutLinesW = cw }
        val lines = tutLines
        val lineH = 18.5f * dp
        val btnH = if (t.step.button != null) 40 * dp else 0f
        val ch = pad + 14 * dp + 24 * dp + lines.size * lineH + (if (btnH > 0) 10 * dp + btnH else 0f) + pad * 0.8f
        val centred = t.step.target == TutTarget.NONE
        val top = if (centred) (hudH + h) / 2 - ch / 2 else cloth.top + 6 * dp
        tutCard.set(cloth.centerX() - cw / 2, top, cloth.centerX() + cw / 2, top + ch)
        paint.color = 0xF2161C21.toInt()
        canvas.drawRoundRect(tutCard, 16 * dp, 16 * dp, paint)
        guide.strokeWidth = 1.5f * dp; guide.color = 0xFFF2C94C.toInt()
        canvas.drawRoundRect(tutCard, 16 * dp, 16 * dp, guide)
        text.textAlign = Paint.Align.LEFT
        var y = tutCard.top + pad + 8 * dp
        text.typeface = bold; text.textSize = 11 * dp; text.color = 0xFFF2C94C.toInt()
        val head = if (t.step == Tutorial.Step.WELCOME || t.step == Tutorial.Step.DONE) "TUTORIAL" else "STEP ${t.number} OF ${t.total}"
        canvas.drawText(head, tutCard.left + pad, y, text)
        y += 24 * dp
        text.textSize = 18 * dp; text.color = Color.WHITE
        canvas.drawText(t.title, tutCard.left + pad, y, text)
        if (t.flash > 0.0) {
            text.textAlign = Paint.Align.RIGHT; text.color = 0xFF3CCB7F.toInt()
            canvas.drawText("✓ Got it!", tutCard.right - pad, y, text)
            text.textAlign = Paint.Align.LEFT
        }
        text.typeface = regular; text.textSize = 13.5f * dp; text.color = 0xFFDDE3E8.toInt()
        for (l in lines) { y += lineH; canvas.drawText(l, tutCard.left + pad, y, text) }
        val label = t.step.button
        if (label != null) {
            val bw = max(110 * dp, text.measureText(label) + 40 * dp)
            tutBtn.set(tutCard.right - pad - bw, tutCard.bottom - pad * 0.8f - btnH, tutCard.right - pad, tutCard.bottom - pad * 0.8f)
            paint.color = 0xFFF2C94C.toInt()
            canvas.drawRoundRect(tutBtn, 12 * dp, 12 * dp, paint)
            text.typeface = bold; text.textSize = 16 * dp; text.color = 0xFF1A1A1A.toInt(); text.textAlign = Paint.Align.CENTER
            canvas.drawText(label, tutBtn.centerX(), tutBtn.centerY() + 5.5f * dp, text)
        } else tutBtn.setEmpty()

        // Skip: always there, top right.
        if (t.step != Tutorial.Step.DONE) {
            tutSkip.set(rightCard.right - 120 * dp, rightCard.top, rightCard.right, rightCard.bottom)
            paint.color = 0xFF39434C.toInt()
            canvas.drawRoundRect(tutSkip, 10 * dp, 10 * dp, paint)
            text.typeface = bold; text.textSize = min(15 * dp, tutSkip.height() * 0.4f); text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("Skip tutorial", tutSkip.centerX(), tutSkip.centerY() + text.textSize * 0.35f, text)
        } else tutSkip.setEmpty()
    }

    /** Tutorial HUD: what this is and how far along you are (Skip is drawn on top by [drawTutorial]). */
    private fun drawTutorialHud(canvas: Canvas) {
        val t = tut ?: return
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF1E252B.toInt()
        canvas.drawRoundRect(leftCard, 10 * dp, 10 * dp, paint)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold
        text.textSize = min(16 * dp, leftCard.height() * 0.32f); text.color = Color.WHITE
        canvas.drawText("TUTORIAL", leftCard.left + 12 * dp, leftCard.top + leftCard.height() * 0.42f, text)
        // Progress dots, one per step.
        val n = t.total
        val r = min(4 * dp, leftCard.height() * 0.08f)
        for (i in 1..n) {
            paint.color = if (i < t.number || t.step == Tutorial.Step.DONE) 0xFF3CCB7F.toInt() else if (i == t.number) 0xFFF2C94C.toInt() else 0xFF39434C.toInt()
            canvas.drawCircle(leftCard.left + 12 * dp + r + (i - 1) * r * 3.2f, leftCard.top + leftCard.height() * 0.72f, r, paint)
        }
    }

    // ------------------------------------------------------------------ frame loop

    fun resume() {
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun release() {
        pause()
        generation++
        sfx.release()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); resume() }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 0.0 else ((frameTimeNanos - lastFrame) / 1e9).coerceIn(0.0, 0.05)
        lastFrame = frameTimeNanos
        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ------------------------------------------------------------------ layout

    private fun sx(x: Double) = ox + (x * scale).toFloat()
    private fun sy(y: Double) = oy + (y * scale).toFloat()
    private fun sx(x: Float) = ox + x * scale
    private fun sy(y: Float) = oy + y * scale
    private fun tx(px: Float) = ((px - ox) / scale).toDouble()
    private fun ty(py: Float) = ((py - oy) / scale).toDouble()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dp = resources.displayMetrics.density
        hudH = max(h * 0.13f, 44 * dp)
        // Left: power bar, then the ball tray. Right: the spin ball, and the fine-aim wheel filling
        // the space below it. (The same layout as ControlsGuide.layout, which draws the screen map.)
        val leftW = max(w * 0.08f, 58 * dp)
        val trayW = 28 * dp
        val rightW = max(w * 0.15f, 96 * dp) + 14 * dp
        trayRect.set(leftW, hudH + 34 * dp, leftW + trayW, h - 18 * dp)
        tableArea.set(trayRect.right + 6 * dp, hudH, w - rightW, h.toFloat())
        val rail = 0.115
        val tw = P.L + 2 * rail; val th = P.W + 2 * rail
        scale = (min(tableArea.width() / tw, (tableArea.height() - 6 * dp) / th) * 0.98).toFloat()
        ox = tableArea.left + (tableArea.width() - (tw * scale).toFloat()) / 2 + (rail * scale).toFloat()
        oy = tableArea.top + (tableArea.height() - (th * scale).toFloat()) / 2 + (rail * scale).toFloat()
        rpx = (P.R * scale).toFloat()
        balls.setRadius(rpx)

        val pw = min(leftW * 0.45f, 30 * dp)
        powerRect.set(leftW / 2 - pw / 2, hudH + 34 * dp, leftW / 2 + pw / 2, h - 18 * dp)
        val nBalls = if (trick) 8 else match.ballIds().count()
        potR = min((trayRect.height() - 6 * dp) / (nBalls * 2.06f), trayW / 2 - 3 * dp)
        for (i in 0 until potN) { potY[i] = traySlotY(i); potV[i] = 0f }

        val colL = w - rightW
        val spinL = colL + 8 * dp; val spinRt = w - 8 * dp
        spinR = min((spinRt - spinL) * 0.42f, (h - hudH) * 0.15f)
        spinCx = (spinL + spinRt) / 2
        spinCy = hudH + 24 * dp + spinR
        wheelRect.set(colL + 8 * dp, spinCy + spinR + 58 * dp, w - 10 * dp, h - 14 * dp)
        topViewBtn.set(tableArea.left + 8 * dp, tableArea.top + 8 * dp, tableArea.left + 118 * dp, tableArea.top + 44 * dp)
        resetViewBtn.set(topViewBtn.right + 8 * dp, topViewBtn.top, topViewBtn.right + 52 * dp, topViewBtn.bottom)
        // In the top view the 3D button sits over the top rail, between the corner and side pockets.
        view3dBtn.set(sx(P.L * 0.11), sy(-0.1) - 15 * dp, sx(P.L * 0.11) + 104 * dp, sy(-0.1) + 15 * dp)
        povBalls.setRadius(30 * dp)
        menuBtn.set(8 * dp, 8 * dp, 8 * dp + 44 * dp, 8 * dp + 36 * dp)

        val cardW = min(w * 0.27f, 260 * dp)
        val cardH = hudH - 16 * dp
        leftCard.set(menuBtn.right + 10 * dp, 8 * dp, menuBtn.right + 10 * dp + cardW, 8 * dp + cardH)
        rightCard.set(w - 8 * dp - cardW, 8 * dp, w - 8 * dp, 8 * dp + cardH)
        run {
            val gap = 8 * dp
            val left = leftCard.right + 10 * dp; val right = rightCard.left - 10 * dp
            val bw = (right - left - 2 * gap) / 3
            val bh = cardH * 0.78f
            val top = leftCard.centerY() - bh / 2
            retryBtn.set(left, top, left + bw, top + bh)
            rackBtn.set(left + bw + gap, top, left + 2 * bw + gap, top + bh)
            clearBtn.set(left + 2 * (bw + gap), top, right, top + bh)
        }

        powerShader = LinearGradient(0f, powerRect.top, 0f, powerRect.bottom,
            intArrayOf(0xFF3CCB7F.toInt(), 0xFFF2C94C.toInt(), 0xFFEB5757.toInt()), null, Shader.TileMode.CLAMP)
        spinShader = RadialGradient(spinCx - spinR * 0.3f, spinCy - spinR * 0.35f, spinR * 1.3f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFE9E4D4.toInt(), 0xFFAFA997.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        // The wheel is a roller turning about a horizontal axis: lit in the middle, dark top and bottom.
        wheelShader = LinearGradient(0f, wheelRect.top, 0f, wheelRect.bottom,
            intArrayOf(0xFF0E1215.toInt(), 0xFF3C4750.toInt(), 0xFF0E1215.toInt()), null, Shader.TileMode.CLAMP)
        trayShader = LinearGradient(trayRect.left, 0f, trayRect.right, 0f,
            intArrayOf(0xFF2A1408.toInt(), 0xFF0B0604.toInt(), 0xFF0B0604.toInt(), 0xFF3A1E0E.toInt()),
            floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP)
        val buttW = (0.015 * scale).toFloat()
        for (i in 0 until 8) stickShaders[i] = LinearGradient(0f, -buttW, 0f, buttW,
            intArrayOf(stickColors[i * 2 + 1], stickColors[i * 2], stickColors[i * 2 + 1]), null, Shader.TileMode.CLAMP)
        dash = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)

        rebuildBackground()
        predKey[0] = Double.NaN
    }

    private var bgVersion = -1

    /** The table is drawn once into a bitmap, and again when its layout changes (a level with a hole). */
    private fun rebuildBackground() {
        if (width <= 0 || height <= 0) return
        background?.recycle()
        background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { drawBackground(Canvas(it), width, height, 0.115) }
        bgVersion = Table.version
    }

    private fun drawBackground(c: Canvas, w: Int, h: Int, rail: Double) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1A2026.toInt(), 0xFF0D1013.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        val outer = RectF(sx(-rail), sy(-rail), sx(P.L + rail), sy(P.W + rail))
        val corner = (0.06 * scale).toFloat()
        p.color = 0x66000000
        c.drawRoundRect(RectF(outer.left + 3 * dp, outer.top + 6 * dp, outer.right + 3 * dp, outer.bottom + 8 * dp), corner, corner, p)
        p.shader = LinearGradient(outer.left, outer.top, outer.right, outer.bottom,
            intArrayOf(0xFF6B3A1C.toInt(), 0xFF4A2610.toInt(), 0xFF5E3218.toInt(), 0xFF3C1E0C.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(outer, corner, corner, p)
        p.shader = null
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
        val gr = Random(3)
        repeat(70) {
            grain.color = if (gr.nextBoolean()) 0x18FFFFFF else 0x22000000
            val inset = gr.nextDouble(0.01, rail - 0.045)
            val r = RectF(sx(-rail + inset), sy(-rail + inset), sx(P.L + rail - inset), sy(P.W + rail - inset))
            c.drawRoundRect(r, corner, corner, grain)
        }
        grain.color = 0x33FFFFFF; grain.strokeWidth = 1.5f * dp
        c.drawRoundRect(RectF(outer.left + dp, outer.top + dp, outer.right - dp, outer.bottom - dp), corner, corner, grain)

        val cush = 0.045
        p.color = 0xFF14583A.toInt()
        c.drawRect(sx(-cush), sy(-cush), sx(P.L + cush), sy(P.W + cush), p)
        p.shader = RadialGradient(sx(P.L / 2), sy(P.W / 2), (P.L * 0.62 * scale).toFloat(),
            intArrayOf(0xFF22935A.toInt(), 0xFF1C7E4C.toInt(), 0xFF145F38.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(sx(0.0), sy(0.0), sx(P.L), sy(P.W), p)
        p.shader = null
        p.shader = LinearGradient(0f, sy(0.0), 0f, sy(0.025), 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(sx(0.0), sy(0.0), sx(P.L), sy(0.025), p)
        p.shader = LinearGradient(sx(0.0), 0f, sx(0.025), 0f, 0x44000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(sx(0.0), sy(0.0), sx(0.025), sy(P.W), p)
        p.shader = null

        // Trick-shot table: the second quarter is cut out. The floor shows through the gap, the
        // cut edges of the slate and rails face each other across it, and each side throws a shadow.
        val gapRect = RectF(sx(Table.GAP_X0), sy(-rail) - 12 * dp, sx(Table.GAP_X1), sy(P.W + rail) + 12 * dp)
        if (Table.gap) {
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1A2026.toInt(), 0xFF0D1013.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(gapRect, p)
            p.shader = null
            val edge = 5 * dp
            val top = sy(-rail); val bot = sy(P.W + rail)
            // Shadows the two sections cast onto the floor.
            p.shader = LinearGradient(gapRect.left, 0f, gapRect.left + 34 * dp, 0f, 0xAA000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(gapRect.left, top + 6 * dp, gapRect.left + 34 * dp, bot + 8 * dp, p)
            p.shader = LinearGradient(gapRect.right, 0f, gapRect.right - 18 * dp, 0f, 0x77000000, 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(gapRect.right - 18 * dp, top + 6 * dp, gapRect.right, bot + 8 * dp, p)
            p.shader = null
            // Cut faces: wood rails at the ends, the slate (dark grey) with the cloth's edge on top.
            for (side in 0..1) {
                val x0 = if (side == 0) gapRect.left else gapRect.right - edge
                p.color = 0xFF3C1E0C.toInt()
                c.drawRect(x0, top, x0 + edge, bot, p)
                p.color = 0xFF2C3034.toInt()
                c.drawRect(x0, sy(-0.045), x0 + edge, sy(P.W + 0.045), p)
                p.color = 0xFF145F38.toInt()
                val clothX = if (side == 0) x0 else x0 + edge - 1.5f * dp
                c.drawRect(clothX, sy(-0.045), clothX + 1.5f * dp, sy(P.W + 0.045), p)
            }
            // A dashed line marks the edge of the break area.
            val hs = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = 0x30FFFFFF }
            c.drawLine(sx(Table.GAP_X0) - 1.5f * dp, sy(0.0), sx(Table.GAP_X0) - 1.5f * dp, sy(P.W), hs)
        }

        // Pockets: the hole is drawn exactly the size of the drop zone, outlined in a bright ring —
        // once a ball's centre crosses that ring, it falls in.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = 0xFFF2E3B3.toInt() }
        c.save()
        if (Table.gap) c.clipOutRect(gapRect)
        for ((pi, pk) in Table.pockets.withIndex()) {
            val r = (pk.capR * scale).toFloat()
            if (Table.bumper) {
                // Bumper pool: each cup is ringed in the colour of the player who shoots into it.
                val own = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 4f * dp; color = if (pi == 1) 0xFFC62828.toInt() else 0xFFEDE6D2.toInt()
                }
                c.drawCircle(sx(pk.cx), sy(pk.cy), r + 5 * dp, own)
            }
            if (pk.hole) {
                // A cup cut into the cloth, like a golf hole.
                p.shader = RadialGradient(sx(pk.cx) - r * 0.2f, sy(pk.cy) - r * 0.25f, r,
                    intArrayOf(0xFF050505.toInt(), 0xFF101010.toInt(), 0xFF2A2A2A.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(sx(pk.cx), sy(pk.cy), r, p)
                p.shader = null
                c.drawCircle(sx(pk.cx), sy(pk.cy), r, ring)
                val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * dp; color = 0x59000000 }
                c.drawCircle(sx(pk.cx), sy(pk.cy), r + 3 * dp, shade)
                continue
            }
            p.shader = RadialGradient(sx(pk.cx), sy(pk.cy), r, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF141414.toInt()),
                floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(sx(pk.cx), sy(pk.cy), r, p)
            p.shader = null
            c.drawCircle(sx(pk.cx), sy(pk.cy), r, ring)
        }
        c.restore()
        val nose = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = 0xFF0E4A2D.toInt(); strokeCap = Paint.Cap.ROUND }
        for (sg in Table.segs) c.drawLine(sx(sg.ax), sy(sg.ay), sx(sg.bx), sy(sg.by), nose)
        // Bumpers: a black rubber ring with a red band and a metal cap, seen from above.
        for (pt in Table.posts) {
            val px = sx(pt.x); val py = sy(pt.y); val pr = (pt.r * scale).toFloat()
            p.shader = null
            p.color = 0x59000000; c.drawCircle(px + pr * 0.2f, py + pr * 0.32f, pr * 1.08f, p)
            p.color = 0xFF1C1C1C.toInt(); c.drawCircle(px, py, pr, p)
            p.color = 0xFF8E1A1A.toInt(); c.drawCircle(px, py, pr * 0.8f, p)
            p.color = 0xFF1C1C1C.toInt(); c.drawCircle(px, py, pr * 0.62f, p)
            p.shader = RadialGradient(px - pr * 0.2f, py - pr * 0.25f, pr * 0.5f, 0xFFF4F6F8.toInt(), 0xFF8D949B.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(px, py, pr * 0.5f, p)
            p.shader = null
        }

        p.color = 0xFFEDE3C8.toInt()
        val dOff = rail * 0.55
        val dr = (0.012 * scale).toFloat()
        for (i in 1..7) if (i != 4 && !(Table.gap && i in 2..3)) {
            val x = P.L * i / 8
            diamond(c, sx(x), sy(-dOff), dr, p)
            diamond(c, sx(x), sy(P.W + dOff), dr, p)
        }
        for (i in 1..3) {
            val y = P.W * i / 4
            diamond(c, sx(-dOff), sy(y), dr, p)
            if (!(Table.gap && i == 2)) diamond(c, sx(P.L + dOff), sy(y), dr, p)
        }
        if (Table.gap || Table.bumper) return
        val hs = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = 0x30FFFFFF; pathEffect = DashPathEffect(floatArrayOf(4 * dp, 6 * dp), 0f) }
        c.drawLine(sx(P.HEAD_X), sy(0.0), sx(P.HEAD_X), sy(P.W), hs)
        p.color = 0x55FFFFFF
        c.drawCircle(sx(P.FOOT_X), sy(P.FOOT_Y), 2.5f * dp, p)
        c.drawCircle(sx(P.HEAD_X), sy(P.FOOT_Y), 2.5f * dp, p)
    }

    private fun diamond(c: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        path.reset()
        path.moveTo(x, y - r); path.lineTo(x + r * 0.6f, y); path.lineTo(x, y + r); path.lineTo(x - r * 0.6f, y); path.close()
        c.drawPath(path, p)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        if (bgVersion != Table.version) rebuildBackground()
        background?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val w = match.world
        val humanTurn = phase == Phase.HUMAN
        if (pov) {
            drawPov(canvas)
            drawControls(canvas)
            return
        }

        if (humanTurn && match.ballInHand && match.kitchenOnly) {
            paint.style = Paint.Style.FILL; paint.shader = null
            paint.color = 0x22FFFFFF
            val zones = if (trick) Trick.LEVELS[match.trick.level].zones else emptyList()
            if (zones.isEmpty()) canvas.drawRect(sx(0.0), sy(0.0), sx(P.HEAD_X), sy(P.W), paint)
            else {
                // Start zones: where the cue ball's centre may go (drawn a ball's width bigger).
                paint.color = 0x33FFFFFF
                for (z in zones) canvas.drawRoundRect(sx(z[0] - P.R), sy(z[1] - P.R), sx(z[2] + P.R), sy(z[3] + P.R), rpx, rpx, paint)
            }
        }

        if (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) drawGuide(canvas)

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
            val h = (b.z / 0.12).toFloat().coerceIn(0f, 1f)
            balls.drawShadow(canvas, sx(bx), sy(by), 1f - 0.35f * h, (255 * (1f - 0.55f * h)).toInt())
        }
        for (s in sinks) {
            val k = 1f - s.age / 0.35f
            paint.alpha = (255 * k).toInt()
            balls.draw(canvas, w.balls[s.id], sx(s.x), sy(s.y), 0.55f + 0.45f * k, paint)
            paint.alpha = 255
        }
        for (b in w.balls) if (b.onTable && b.z < 0.0) {
            // Falling into the gap: shrinks as it drops away.
            balls.draw(canvas, b, sx(b.x), sy(b.y), (1f + (b.z * 6.0).toFloat()).coerceAtLeast(0.45f))
        }
        for (b in w.balls) if (b.onTable && b.z == 0.0) {
            val bx = if (b.id == w.cue) cueX else b.x; val by = if (b.id == w.cue) cueY else b.y
            balls.draw(canvas, b, sx(bx), sy(by))
        }
        // In the air: the height is exaggerated on screen (the distance is real) so a jump is easy to
        // see. The ball is drawn lifted toward the top of the screen and bigger, away from its shadow.
        for (b in w.balls) if (b.onTable && b.z > 0.0) {
            val lift = (b.z * JUMP_LIFT * scale).toFloat()
            balls.draw(canvas, b, sx(b.x) - lift * 0.25f, sy(b.y) - lift, 1f + (b.z * JUMP_GROW).toFloat())
        }

        if (humanTurn && match.ballInHand) {
            val pulse = (0.5 + 0.5 * sin(System.nanoTime() / 2.5e8)).toFloat()
            guide.pathEffect = null
            guide.strokeWidth = 2 * dp
            guide.color = Color.argb((120 + 120 * pulse).toInt(), 255, 214, 64)
            canvas.drawCircle(sx(cue.x), sy(cue.y), rpx * (1.5f + 0.25f * pulse), guide)
            if (anyTouch(Touch.MOVE_CUE) && dragInvalid) {
                guide.color = 0xCCFF4040.toInt()
                canvas.drawCircle(dragX, dragY, rpx, guide)
            }
        }

        if (humanTurn && movingBall >= 0) {
            val mb = w.balls[movingBall]
            guide.pathEffect = null; guide.strokeWidth = 2 * dp
            guide.color = 0xE0FFD54A.toInt()
            canvas.drawCircle(sx(mb.x), sy(mb.y), rpx * 1.5f, guide)
            if (dragInvalid) {
                guide.color = 0xCCFF4040.toInt()
                canvas.drawCircle(dragX, dragY, rpx, guide)
            }
        }

        drawPocketCall(canvas)
        if (circuit && humanTurn && revealTo == match.current) {
            // Your secret targets still on the table.
            guide.pathEffect = dash; guide.strokeWidth = 1.6f * dp; guide.color = 0xB3FFD640.toInt()
            for (id in match.circuit.targets[revealTo]) {
                val b = w.balls[id]
                if (b.onTable) canvas.drawCircle(sx(b.x), sy(b.y), rpx * 1.5f, guide)
            }
            guide.pathEffect = null
        }
        if (bumper && humanTurn && !practice) {
            // The other balls you could shoot instead: tap one to pick it.
            guide.pathEffect = dash; guide.strokeWidth = 1.5f * dp; guide.color = 0x99FFD640.toInt()
            for (id in match.shootable()) if (id != w.cue) {
                val b = w.balls[id]
                canvas.drawCircle(sx(b.x), sy(b.y), rpx * 1.45f, guide)
            }
            guide.pathEffect = null
        }

        val showCue = (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && cue.onTable) drawCueStick(canvas, sx(cueX), sy(cueY))

        if (bannerT > 0 && trick) drawBanner(canvas)
        if (phase != Phase.RPS && phase != Phase.OVER && phase != Phase.HANDOFF && povAllowed()) {
            paint.style = Paint.Style.FILL; paint.shader = null; paint.color = 0xE61E252B.toInt()
            canvas.drawRoundRect(view3dBtn, 10 * dp, 10 * dp, paint)
            guide.pathEffect = null; guide.strokeWidth = 1.5f * dp; guide.color = 0xFFF2C94C.toInt()
            canvas.drawRoundRect(view3dBtn, 10 * dp, 10 * dp, guide)
            text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = 13 * dp; text.color = Color.WHITE
            canvas.drawText("3D view ▶", view3dBtn.centerX(), view3dBtn.centerY() + 4.5f * dp, text)
        }
        drawControls(canvas)
    }

    /** Everything around the table: the HUD, power bar, tray, spin ball and fine-aim wheel, and overlays. */
    private fun drawControls(canvas: Canvas) {
        drawHud(canvas)
        drawPowerBar(canvas)
        drawTray(canvas)
        drawSpin(canvas)
        drawWheel(canvas)
        if (phase == Phase.RPS) drawRps(canvas)
        if (phase == Phase.HANDOFF) drawHandoff(canvas)
        if (phase == Phase.OVER) drawGameOver(canvas)
        drawTutorial(canvas)
    }

    // ------------------------------------------------------------------ 3D view (drawing)

    private val povPoly = Path()
    private val povPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The view down the cue: the table in 3D, the balls, the aim guide and the cue, plus Top view. */
    private fun drawPov(canvas: Canvas) {
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
        povCam.setup(pivX, pivY, aimAngle + povCtl.yaw, povCtl.pitch, povCtl.dist,
            a.left.toDouble(), a.top.toDouble(), a.right.toDouble(), a.bottom.toDouble())
        val sc = povScene
        sc.build(povCam, w, cueX, cueY)
        povCam.viewBasis(povView)
        povBalls.setView(povView)
        val humanTurn = phase == Phase.HUMAN

        canvas.save()
        canvas.clipRect(a)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK   // opaque: a shader is drawn with the paint's alpha, and the top view must not show through
        paint.shader = LinearGradient(0f, a.top, 0f, a.bottom, 0xFF0B0E11.toInt(), 0xFF20262C.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(a, paint)
        paint.shader = null
        val clothShader = RadialGradient(sc.clothCx.toFloat(), sc.clothCy.toFloat(), sc.clothR.toFloat().coerceAtLeast(1f),
            intArrayOf(0xFF24985D.toInt(), 0xFF1C7E4C.toInt(), 0xFF135A35.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val showGuide = humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0
        povPaint.style = Paint.Style.FILL
        for (o in 0 until sc.n) {
            if (o == sc.guideAt) { if (showGuide) drawPovGuide(canvas); drawPovMarks(canvas, cueX, cueY) }
            val i = sc.order[o]
            if (sc.kind[i] == PovScene.BALL) {
                val b = w.balls[sc.color[i]]
                povBalls.draw(canvas, b, sc.bx[i].toFloat(), sc.by[i].toFloat(), (sc.br[i] / povBalls.radius).toFloat())
                continue
            }
            povPoly.reset()
            val st = sc.start[i]
            povPoly.moveTo(sc.px[st].toFloat(), sc.py[st].toFloat())
            for (k in 1 until sc.count[i]) povPoly.lineTo(sc.px[st + k].toFloat(), sc.py[st + k].toFloat())
            povPoly.close()
            if (sc.color[i] == PovScene.CLOTH) { povPaint.shader = clothShader; povPaint.color = Color.BLACK }
            else { povPaint.shader = null; povPaint.color = sc.color[i] }
            canvas.drawPath(povPoly, povPaint)
        }
        povPaint.shader = null
        if (sc.guideAt >= sc.n) { if (showGuide) drawPovGuide(canvas); drawPovMarks(canvas, cueX, cueY) }
        if (humanTurn || phase == Phase.CPU_ANIM) drawPovCalled(canvas)
        val showCue = (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && c.onTable) drawPovCue(canvas, cueX, cueY)
        canvas.restore()

        // Top view and reset-camera buttons, and (for a few seconds, or when there's a warning) how to drive this view.
        paint.style = Paint.Style.FILL; paint.color = 0xE61E252B.toInt()
        canvas.drawRoundRect(topViewBtn, 10 * dp, 10 * dp, paint)
        canvas.drawRoundRect(resetViewBtn, 10 * dp, 10 * dp, paint)
        guide.pathEffect = null; guide.strokeWidth = 1.5f * dp; guide.color = 0xFFF2C94C.toInt()
        canvas.drawRoundRect(topViewBtn, 10 * dp, 10 * dp, guide)
        guide.color = 0x99F2C94C.toInt()
        canvas.drawRoundRect(resetViewBtn, 10 * dp, 10 * dp, guide)
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = 14 * dp; text.color = Color.WHITE
        canvas.drawText("◀ Top view", topViewBtn.centerX(), topViewBtn.centerY() + 5 * dp, text)
        text.textSize = 18 * dp
        canvas.drawText("⟲", resetViewBtn.centerX(), resetViewBtn.centerY() + 6 * dp, text)
        if (!humanTurn) return
        val st = tipState()
        val hint = when {
            st == 2 -> "Scoop! A level cue this low digs under the ball — that's a foul"
            st == 1 -> "Miscue: the tip will slip off the ball"
            System.nanoTime() < povHintUntil -> "1 finger: ↔ aim, ↕ cue angle · 2 fingers: pinch to zoom, drag to turn and tilt"
            elevDeg >= 0.5f -> "cue ${elevDeg.toInt()}°"
            else -> ""
        }
        if (hint.isEmpty()) return
        text.typeface = regular; text.textSize = 12.5f * dp
        val tw = text.measureText(hint) + 24 * dp
        val pill = RectF(a.centerX() - tw / 2, a.bottom - 34 * dp, a.centerX() + tw / 2, a.bottom - 8 * dp)
        paint.color = when (st) { 2 -> 0xE6A32020.toInt(); 1 -> 0xE6A35A10.toInt(); else -> 0xB3101418.toInt() }
        canvas.drawRoundRect(pill, 13 * dp, 13 * dp, paint)
        text.color = Color.WHITE
        canvas.drawText(hint, pill.centerX(), pill.centerY() + 4.5f * dp, text)
    }

    /** Ball in hand (a pulsing ring round the cue ball) and a ball being moved, drawn on the cloth. */
    private fun drawPovMarks(canvas: Canvas, cueX: Double, cueY: Double) {
        if (phase != Phase.HUMAN) return
        guide.pathEffect = null
        if (circuit && revealTo == match.current) {
            guide.strokeWidth = 2f * dp; guide.color = 0xB3FFD640.toInt()
            for (id in match.circuit.targets[revealTo]) {
                val b = match.world.balls[id]
                if (b.onTable) povRing(canvas, b.x, b.y, P.R * 1.45)
            }
        }
        if (match.ballInHand) {
            val pulse = (0.5 + 0.5 * sin(System.nanoTime() / 2.5e8)).toFloat()
            guide.strokeWidth = 2.5f * dp
            guide.color = Color.argb((120 + 120 * pulse).toInt(), 255, 214, 64)
            povRing(canvas, cueX, cueY, P.R * (1.5 + 0.25 * pulse))
            if (anyTouch(Touch.MOVE_CUE) && dragInvalid && toTable(dragX, dragY)) {
                guide.color = 0xCCFF4040.toInt(); povRing(canvas, povXY[0], povXY[1], P.R)
            }
        }
        if (movingBall >= 0) {
            val mb = match.world.balls[movingBall]
            guide.strokeWidth = 2.5f * dp; guide.color = 0xE0FFD54A.toInt()
            povRing(canvas, mb.x, mb.y, P.R * 1.5)
            if (dragInvalid && toTable(dragX, dragY)) { guide.color = 0xCCFF4040.toInt(); povRing(canvas, povXY[0], povXY[1], P.R) }
        }
    }

    private fun povLine(canvas: Canvas, x1: Double, y1: Double, x2: Double, y2: Double, h: Double) {
        if (povCam.segment(x1, y1, h, x2, y2, h, povSeg))
            canvas.drawLine(povSeg[0].toFloat(), povSeg[1].toFloat(), povSeg[2].toFloat(), povSeg[3].toFloat(), guide)
    }

    /** An outline of a ball-sized circle lying on the cloth at (x, y). */
    private fun povRing(canvas: Canvas, x: Double, y: Double, r: Double) {
        povPoly.reset()
        var first = true
        for (k in 0..24) {
            val ang = 2 * Math.PI * k / 24
            if (!povCam.project(x + r * cos(ang), y + r * sin(ang), 0.002, povPt)) return
            if (first) povPoly.moveTo(povPt[0].toFloat(), povPt[1].toFloat()) else povPoly.lineTo(povPt[0].toFloat(), povPt[1].toFloat())
            first = false
        }
        canvas.drawPath(povPoly, guide)
    }

    /** The aim guide, drawn on the cloth in 3D (before the cushions and balls, which can hide it). */
    private fun drawPovGuide(canvas: Canvas) {
        val c = match.world.cueBall
        if (!c.onTable) return
        val h = 0.002
        guide.strokeCap = Paint.Cap.ROUND
        if (!showPrediction) {
            match.world.railPoint(aimAngle, hit)
            guide.strokeWidth = 2f * dp; guide.pathEffect = dash; guide.color = 0x90FFFFFF.toInt()
            povLine(canvas, c.x, c.y, hit[0], hit[1], h)
            guide.pathEffect = null
            return
        }
        updatePrediction()
        val pr = prediction
        if (pr.cueN < 2) return
        val pre = if (pr.contactIndex >= 0) pr.contactIndex else pr.cueN - 1
        guide.strokeWidth = 2.2f * dp
        for (i in 0 until pre) {
            val air = pr.cueAir[i] || pr.cueAir[i + 1]
            guide.pathEffect = if (air) null else dash
            guide.color = if (air) 0xE0FFD54A.toInt() else 0xB0FFFFFF.toInt()
            povLine(canvas, pr.cueX[i].toDouble(), pr.cueY[i].toDouble(), pr.cueX[i + 1].toDouble(), pr.cueY[i + 1].toDouble(), h)
        }
        guide.pathEffect = null
        if (pr.hitId >= 0) {
            val legal = practice || pr.hitId in match.legalTargets()
            guide.color = if (legal) 0xE0FFFFFF.toInt() else 0xE0FF5050.toInt()
            guide.strokeWidth = 2f * dp
            // Ghost ball: where the cue ball will be at contact.
            if (povCam.project(pr.hitX, pr.hitY, P.R, povPt)) {
                val r = povCam.ballRadius(povPt[2]).toFloat()
                canvas.drawCircle(povPt[0].toFloat(), povPt[1].toFloat(), r, guide)
                if (!legal) {
                    val gx = povPt[0].toFloat(); val gy = povPt[1].toFloat(); val k = r * 0.55f
                    canvas.drawLine(gx - k, gy - k, gx + k, gy + k, guide)
                    canvas.drawLine(gx - k, gy + k, gx + k, gy - k, guide)
                }
            }
            guide.strokeWidth = 3f * dp
            for (i in 0 until pr.objN - 1) povLine(canvas, pr.objX[i].toDouble(), pr.objY[i].toDouble(), pr.objX[i + 1].toDouble(), pr.objY[i + 1].toDouble(), h)
            guide.color = 0x80FFFFFF.toInt(); guide.strokeWidth = 1.8f * dp
            for (i in pre until pr.cueN - 1) povLine(canvas, pr.cueX[i].toDouble(), pr.cueY[i].toDouble(), pr.cueX[i + 1].toDouble(), pr.cueY[i + 1].toDouble(), h)
        }
        if (pr.scratch) {
            guide.color = 0xE0FF5050.toInt(); guide.strokeWidth = 2 * dp
            povRing(canvas, pr.cueX[pr.cueN - 1].toDouble(), pr.cueY[pr.cueN - 1].toDouble(), P.R * 1.3)
        }
        if (!pr.landX.isNaN()) {
            guide.color = 0xF0FFD54A.toInt(); guide.strokeWidth = 2 * dp
            povRing(canvas, pr.landX, pr.landY, P.R)
        }
    }

    /** The called pocket (or, when a call is needed, every pocket) ringed in gold. */
    private fun drawPovCalled(canvas: Canvas) {
        val called = match.calledPocket
        val pulse = (0.5 + 0.5 * sin(System.nanoTime() / 2.0e8)).toFloat()
        guide.pathEffect = null
        if (called >= 0) {
            val pk = Table.pockets[called]
            guide.strokeWidth = 3 * dp; guide.color = 0xFFFFD640.toInt()
            povRing(canvas, pk.cx, pk.cy, pk.capR + 0.02)
        } else if (match.callRequired()) {
            val flash = System.nanoTime() < callFlashUntil
            guide.strokeWidth = (if (flash) 3f else 1.5f) * dp
            guide.color = if (flash) Color.argb((150 + 100 * pulse).toInt(), 255, 90, 90) else Color.argb((60 + 80 * pulse).toInt(), 255, 214, 64)
            for (pk in Table.pockets) povRing(canvas, pk.cx, pk.cy, pk.capR + 0.02)
        }
    }

    /** The cue, seen from just above it: from the tip at the ball back past the bottom of the screen. */
    private fun drawPovCue(canvas: Canvas, cx: Double, cy: Double) {
        val k = povScene.cue(povCam, cx, cy, aimAngle, Math.toRadians(elevDeg.toDouble()),
            (spinX * P.TIP_LIMIT).toDouble(), (-spinY * P.TIP_LIMIT).toDouble(),
            0.012 + power * 0.07, stickSegs, 0.0065, 0.015, povCue)
        val q = povCue
        for (i in 0 until k - 1) {
            val o = i * 4
            povPoly.reset()
            povPoly.moveTo(q[o].toFloat(), q[o + 1].toFloat()); povPoly.lineTo(q[o + 2].toFloat(), q[o + 3].toFloat())
            povPoly.lineTo(q[o + 6].toFloat(), q[o + 7].toFloat()); povPoly.lineTo(q[o + 4].toFloat(), q[o + 5].toFloat())
            povPoly.close()
            // Rounded shading across the stick: dark edges, light down the middle.
            val lx = ((q[o] + q[o + 4]) / 2).toFloat(); val ly = ((q[o + 1] + q[o + 5]) / 2).toFloat()
            val rx = ((q[o + 2] + q[o + 6]) / 2).toFloat(); val ry = ((q[o + 3] + q[o + 7]) / 2).toFloat()
            if (hypot(rx - lx, ry - ly) < 0.5f) continue
            povPaint.shader = LinearGradient(lx, ly, rx, ry, intArrayOf(stickColors[i * 2 + 1], stickColors[i * 2], stickColors[i * 2 + 1]),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(povPoly, povPaint)
        }
        povPaint.shader = null
    }

    /** Trick shots: the level's name and goal over the table as it starts, fading out. */
    private fun drawBanner(canvas: Canvas) {
        val t = match.trick
        val lv = Trick.LEVELS[t.level]
        val a = (bannerT / 0.5).coerceIn(0.0, 1.0).toFloat()
        val cx = sx(P.L / 2)
        val bw = min((P.L * scale).toFloat() * 0.62f, 420 * dp)
        text.typeface = regular; text.textSize = 13 * dp
        val lines = wrap(lv.blurb, bw - 24 * dp)
        val bh = (50 + 18 * lines.size) * dp
        val top = sy(P.W * 0.16)
        val r = RectF(cx - bw / 2, top, cx + bw / 2, top + bh)
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = Color.argb((225 * a).toInt(), 20, 26, 31)
        canvas.drawRoundRect(r, 14 * dp, 14 * dp, paint)
        guide.pathEffect = null; guide.strokeWidth = 2 * dp
        guide.color = Color.argb((255 * a).toInt(), 242, 201, 76)
        canvas.drawRoundRect(r, 14 * dp, 14 * dp, guide)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold; text.textSize = 19 * dp
        text.color = Color.argb((255 * a).toInt(), 255, 255, 255)
        val head = when {
            trickPractice -> "PRACTICE · ${lv.name.uppercase()}"
            t.suddenDeath -> "SUDDEN DEATH · ${lv.name.uppercase()}"
            else -> "LEVEL ${t.levelNo + 1} OF ${Trick.LEVELS.size} · ${lv.name.uppercase()}"
        }
        canvas.drawText(ellipsize(head, bw - 20 * dp), cx, top + 27 * dp, text)
        text.typeface = regular; text.textSize = 13 * dp
        text.color = Color.argb((255 * a).toInt(), 206, 214, 221)
        for ((i, l) in lines.withIndex()) canvas.drawText(l, cx, top + (49 + 18 * i) * dp, text)
    }

    /** Splits text into at most two lines that fit [maxW] (the second is shortened if it must be). */
    private fun wrap(s: String, maxW: Float): List<String> {
        if (text.measureText(s) <= maxW) return listOf(s)
        val words = s.split(" ")
        var first = ""
        var k = 0
        while (k < words.size) {
            val t = if (first.isEmpty()) words[k] else "$first ${words[k]}"
            if (text.measureText(t) > maxW && first.isNotEmpty()) break
            first = t; k++
        }
        val rest = words.drop(k).joinToString(" ").trimStart('·', ' ')
        return if (rest.isEmpty()) listOf(first) else listOf(first.trimEnd('·', ' '), ellipsize(rest, maxW))
    }

    private fun drawPocketCall(canvas: Canvas) {
        if (phase != Phase.HUMAN && phase != Phase.CPU_ANIM) return
        val called = match.calledPocket
        val pulse = (0.5 + 0.5 * sin(System.nanoTime() / 2.0e8)).toFloat()
        guide.pathEffect = null
        if (called >= 0) {
            val pk = Table.pockets[called]
            val r = (pk.capR * scale).toFloat()
            guide.strokeWidth = 3 * dp
            guide.color = Color.argb(255, 255, 214, 64)
            canvas.drawCircle(sx(pk.cx), sy(pk.cy), r + (3 + 3 * pulse) * dp, guide)
            text.typeface = bold; text.textAlign = Paint.Align.CENTER
            text.textSize = 11 * dp; text.color = 0xFFFFD640.toInt()
            val below = pk.cy < P.W / 2
            canvas.drawText("CALLED", sx(pk.cx), sy(pk.cy) + (if (below) r + 20 * dp else -r - 10 * dp), text)
        } else if (phase == Phase.HUMAN && match.callRequired()) {
            val flash = System.nanoTime() < callFlashUntil
            guide.strokeWidth = (if (flash) 3f else 1.5f) * dp
            guide.color = if (flash) Color.argb((150 + 100 * pulse).toInt(), 255, 90, 90) else Color.argb((60 + 80 * pulse).toInt(), 255, 214, 64)
            for (pk in Table.pockets) canvas.drawCircle(sx(pk.cx), sy(pk.cy), (pk.capR * scale).toFloat() + 5 * dp, guide)
        }
    }

    private fun guideSpeed() = speedFor(if (power > 0.02f) power else lastPower)

    private fun updatePrediction() {
        val c = match.world.cueBall
        val sp = guideSpeed()
        val k0 = aimAngle; val k1 = sp; val k2 = spinX.toDouble(); val k3 = spinY.toDouble()
        val k4 = elevDeg.toDouble(); val k5 = c.x; val k6 = c.y; val k7 = match.current.toDouble()
        val k8 = generation.toDouble() + (if (match.ballInHand) 0.5 else 0.0)
        if (predKey[0] == k0 && predKey[1] == k1 && predKey[2] == k2 && predKey[3] == k3 && predKey[4] == k4 &&
            predKey[5] == k5 && predKey[6] == k6 && predKey[7] == k7 && predKey[8] == k8) return
        predKey[0] = k0; predKey[1] = k1; predKey[2] = k2; predKey[3] = k3; predKey[4] = k4
        predKey[5] = k5; predKey[6] = k6; predKey[7] = k7; predKey[8] = k8
        Predictor.run(
            match.world, scratchWorld, aimAngle, sp,
            (spinX * P.TIP_LIMIT).toDouble(), (-spinY * P.TIP_LIMIT).toDouble(), Math.toRadians(elevDeg.toDouble()),
            prediction
        )
    }

    private fun drawGuide(canvas: Canvas) {
        val c = match.world.cueBall
        if (!c.onTable) return
        if (!showPrediction) {
            // Aim line only: straight from the cue ball to the cushion, no contact or paths.
            match.world.railPoint(aimAngle, hit)
            guide.strokeWidth = 1.7f * dp
            guide.pathEffect = dash
            guide.color = 0x90FFFFFF.toInt()
            canvas.drawLine(sx(c.x), sy(c.y), sx(hit[0]), sy(hit[1]), guide)
            guide.pathEffect = null
            return
        }
        updatePrediction()
        val pr = prediction
        if (pr.cueN < 2) return
        val pre = if (pr.contactIndex >= 0) pr.contactIndex else pr.cueN - 1

        // Cue ball path up to contact: dashed on the cloth, solid yellow while airborne.
        guide.strokeWidth = 1.7f * dp
        for (i in 0 until pre) {
            val air = pr.cueAir[i] || pr.cueAir[i + 1]
            guide.pathEffect = if (air) null else dash
            guide.color = if (air) 0xE0FFD54A.toInt() else 0xB0FFFFFF.toInt()
            canvas.drawLine(sx(pr.cueX[i]), sy(pr.cueY[i]), sx(pr.cueX[i + 1]), sy(pr.cueY[i + 1]), guide)
        }
        guide.pathEffect = null

        if (pr.hitId >= 0) {
            val legal = practice || pr.hitId in match.legalTargets()
            val gx = sx(pr.hitX); val gy = sy(pr.hitY)
            guide.strokeWidth = 1.7f * dp
            guide.color = if (legal) 0xE0FFFFFF.toInt() else 0xE0FF5050.toInt()
            canvas.drawCircle(gx, gy, rpx, guide)
            // Object ball's real path (includes throw)
            if (pr.objN >= 2) {
                guide.strokeWidth = 2.4f * dp
                path.reset()
                path.moveTo(sx(pr.objX[0]), sy(pr.objY[0]))
                for (i in 1 until pr.objN) path.lineTo(sx(pr.objX[i]), sy(pr.objY[i]))
                canvas.drawPath(path, guide)
            }
            // Cue ball after contact (shows follow / draw / stun)
            if (pr.cueN - 1 > pre) {
                guide.color = 0x80FFFFFF.toInt()
                guide.strokeWidth = 1.4f * dp
                path.reset()
                path.moveTo(sx(pr.cueX[pre]), sy(pr.cueY[pre]))
                for (i in pre + 1 until pr.cueN) path.lineTo(sx(pr.cueX[i]), sy(pr.cueY[i]))
                canvas.drawPath(path, guide)
            }
            if (!legal) {
                guide.color = 0xE0FF5050.toInt(); guide.strokeWidth = 2.4f * dp
                val r = rpx * 0.55f
                canvas.drawLine(gx - r, gy - r, gx + r, gy + r, guide)
                canvas.drawLine(gx - r, gy + r, gx + r, gy - r, guide)
            }
        } else {
            guide.color = 0xB0FFFFFF.toInt(); guide.strokeWidth = 1.7f * dp
            canvas.drawCircle(sx(pr.cueX[pr.cueN - 1]), sy(pr.cueY[pr.cueN - 1]), rpx, guide)
        }
        if (pr.scratch) {
            val lx = pr.cueX[pr.cueN - 1].toDouble(); val ly = pr.cueY[pr.cueN - 1].toDouble()
            // Bumper pool: dropping into the cup you're shooting for is the point (gold), your own cup is a foul (red).
            val good = bumper && intoTargetCup(lx, ly)
            guide.color = if (good) 0xF2FFD640.toInt() else 0xE0FF5050.toInt(); guide.strokeWidth = 2 * dp
            canvas.drawCircle(sx(lx), sy(ly), rpx * 1.3f, guide)
        }
        // Jump shots: where the cue ball first comes down. It slides smoothly as you add power.
        if (!pr.landX.isNaN()) {
            val lx = sx(pr.landX); val ly = sy(pr.landY)
            guide.color = 0xF0FFD54A.toInt(); guide.strokeWidth = 2 * dp
            canvas.drawCircle(lx, ly, rpx, guide)
            paint.style = Paint.Style.FILL; paint.shader = null; paint.color = 0xF0FFD54A.toInt()
            canvas.drawCircle(lx, ly, 2.2f * dp, paint)
        }
    }

    /** Bumper pool: the guide's ball drops at (x, y) into the cup the current player is shooting for. */
    private fun intoTargetCup(x: Double, y: Double): Boolean {
        val pk = Table.pockets[match.targetCup(match.current)]
        return hypot(x - pk.cx, y - pk.cy) < pk.capR + 0.06
    }

    private fun drawCueStick(canvas: Canvas, cx: Float, cy: Float) {
        val s = scale
        // An elevated cue is foreshortened when seen from above. Bumper pool is played with a short
        // cue (and on its small table a full-length one would hang off the screen).
        val fore = cos(Math.toRadians(elevDeg.toDouble()))
        val k = if (bumper) SHORT_CUE else 1.0
        val gap = (P.R + 0.012 + power * 0.22) * s * fore
        val len = 1.45 * s * fore * k
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(Math.toDegrees(aimAngle).toFloat())
        val tipW = (0.0065 * s).toFloat()
        val buttW = (0.015 * s).toFloat()
        fun halfW(x: Double) = tipW + (buttW - tipW) * ((-x - gap) / len).toFloat()
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0x55000000
        val sh = (5 * dp * (1 + elevDeg / 15f))
        canvas.save(); canvas.translate(3 * dp, sh)
        path.reset()
        path.moveTo((-gap).toFloat(), -tipW); path.lineTo((-gap - len).toFloat(), -buttW)
        path.lineTo((-gap - len).toFloat(), buttW); path.lineTo((-gap).toFloat(), tipW); path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
        for (i in 0 until 8) {
            val a = -gap - stickSegs[i] * s * fore * k; val b = -gap - stickSegs[i + 1] * s * fore * k
            path.reset()
            path.moveTo(a.toFloat(), -halfW(a)); path.lineTo(b.toFloat(), -halfW(b))
            path.lineTo(b.toFloat(), halfW(b)); path.lineTo(a.toFloat(), halfW(a)); path.close()
            paint.shader = stickShaders[i]
            canvas.drawPath(path, paint)
        }
        paint.shader = null
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas) {
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF232B32.toInt()
        canvas.drawRoundRect(menuBtn, 8 * dp, 8 * dp, paint)
        paint.color = 0xFFCED6DD.toInt()
        val mx = menuBtn.centerX(); val my = menuBtn.centerY()
        for (i in -1..1) canvas.drawRoundRect(mx - 9 * dp, my + i * 6 * dp - 1.2f * dp, mx + 9 * dp, my + i * 6 * dp + 1.2f * dp, dp, dp, paint)

        if (tutorialMode) { drawTutorialHud(canvas); return }
        if (practice) { drawPracticeHud(canvas); return }
        if (trickPractice) { drawTrickPracticeHud(canvas); return }
        drawPlayerCard(canvas, leftCard, 0)
        drawPlayerCard(canvas, rightCard, 1)

        val cardH = leftCard.height()
        val midL = leftCard.right + 10 * dp; val midR = rightCard.left - 10 * dp
        text.typeface = regular
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFE8ECEF.toInt()
        text.textSize = min(15 * dp, hudH * 0.26f)
        var msg = when (phase) {
            Phase.HANDOFF -> "${names[match.current]}'s turn — pass it over"
            Phase.CPU_THINK, Phase.CPU_ANIM -> if (message.isNotEmpty()) message else "CPU is thinking…"
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
        msg = ellipsize(msg, midR - midL)
        canvas.drawText(msg, (midL + midR) / 2, 8 * dp + cardH * 0.42f, text)
        val subY = 8 * dp + cardH * 0.82f
        if (phase == Phase.CPU_THINK) {
            text.textSize = min(12 * dp, hudH * 0.2f); text.color = 0xFFF2C94C.toInt()
            canvas.drawText("CPU (level $cpuLevel) is thinking…", (midL + midR) / 2, subY, text)
        } else if (trick) {
            val t = match.trick
            text.textSize = min(12 * dp, hudH * 0.2f); text.color = 0xFFF2C94C.toInt()
            val lvl = if (t.suddenDeath) "Sudden death" else "Level ${t.levelNo + 1} of ${Trick.LEVELS.size}"
            canvas.drawText(ellipsize("$lvl · ${Trick.LEVELS[t.level].name} · fewest shots wins", midR - midL), (midL + midR) / 2, subY, text)
        } else if (bumper && phase == Phase.HUMAN) {
            text.textSize = min(12 * dp, hudH * 0.2f); text.color = 0xFFF2C94C.toInt()
            canvas.drawText(ellipsize(match.bumperHint(), midR - midL), (midL + midR) / 2, subY, text)
        } else if (circuit && phase == Phase.HUMAN) {
            text.textSize = min(12 * dp, hudH * 0.2f); text.color = 0xFFF2C94C.toInt()
            canvas.drawText(ellipsize("Sink your 3 secret balls · any ball first · theirs ends your turn", midR - midL), (midL + midR) / 2, subY, text)
        } else if (mode == GameMode.NINE_BALL) {
            val r = min(hudH * 0.13f, 9 * dp)
            val total = 9 * r * 2.4f
            var x = (midL + midR) / 2 - total / 2 + r
            for (id in 1..9) {
                balls.drawIcon(canvas, id, x, subY - r * 0.4f, r, !match.world.balls[id].onTable, paint)
                x += r * 2.4f
            }
        }
    }

    private fun drawPracticeHud(canvas: Canvas) {
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF1E252B.toInt()
        canvas.drawRoundRect(leftCard, 10 * dp, 10 * dp, paint)
        canvas.drawRoundRect(rightCard, 10 * dp, 10 * dp, paint)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold
        text.textSize = min(16 * dp, leftCard.height() * 0.32f); text.color = Color.WHITE
        canvas.drawText(if (bumperPractice) "BUMPER PRACTICE" else "PRACTICE", leftCard.left + 12 * dp, leftCard.top + leftCard.height() * 0.42f, text)
        text.typeface = regular; text.textSize = min(11 * dp, leftCard.height() * 0.22f); text.color = 0xFF9AA7B1.toInt()
        val sub = if (message.isNotEmpty()) message else if (bumperPractice) "Tap a ball to shoot it · hold it to move it" else "Hold any ball to move it"
        canvas.drawText(ellipsize(sub, leftCard.width() - 20 * dp), leftCard.left + 12 * dp, leftCard.top + leftCard.height() * 0.78f, text)

        fun button(r: RectF, label: String, enabled: Boolean) {
            paint.color = if (enabled) 0xFF2F8F5B.toInt() else 0xFF2A3238.toInt()
            canvas.drawRoundRect(r, 10 * dp, 10 * dp, paint)
            text.textAlign = Paint.Align.CENTER; text.typeface = bold
            text.textSize = min(14 * dp, r.height() * 0.38f)
            text.color = if (enabled) Color.WHITE else 0xFF6B7780.toInt()
            canvas.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
        }
        button(retryBtn, "Retry shot", practiceSnapshot != null)
        button(rackBtn, "Re-rack", true)
        button(clearBtn, "Clear table", true)

        // Balls off the table sit in the tray on the left: tap one there to bring it back.
        text.textAlign = Paint.Align.LEFT; text.typeface = regular
        text.textSize = min(11 * dp, rightCard.height() * 0.22f); text.color = 0xFF9AA7B1.toInt()
        canvas.drawText(if (potN == 0) "All balls on the table" else "Tap a ball in the tray (left) to put it back",
            rightCard.left + 12 * dp, rightCard.top + rightCard.height() * 0.42f, text)
    }

    /** Trick-shot practice: the level and how it's going, with buttons to change level or reset. */
    private fun drawTrickPracticeHud(canvas: Canvas) {
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF1E252B.toInt()
        canvas.drawRoundRect(leftCard, 10 * dp, 10 * dp, paint)
        canvas.drawRoundRect(rightCard, 10 * dp, 10 * dp, paint)
        val lv = Trick.LEVELS[match.trick.level]
        text.textAlign = Paint.Align.LEFT; text.typeface = bold
        text.textSize = min(16 * dp, leftCard.height() * 0.32f); text.color = Color.WHITE
        canvas.drawText(ellipsize("PRACTICE · ${lv.name.uppercase()}", leftCard.width() - 20 * dp), leftCard.left + 12 * dp, leftCard.top + leftCard.height() * 0.42f, text)
        text.typeface = regular; text.textSize = min(11 * dp, leftCard.height() * 0.22f); text.color = 0xFF9AA7B1.toInt()
        val sub = if (phase == Phase.HUMAN && match.ballInHand) "Drag the cue ball to place it, then shoot" else message
        canvas.drawText(ellipsize(sub, leftCard.width() - 20 * dp), leftCard.left + 12 * dp, leftCard.top + leftCard.height() * 0.78f, text)

        fun button(r: RectF, label: String) {
            paint.color = 0xFF2F8F5B.toInt()
            canvas.drawRoundRect(r, 10 * dp, 10 * dp, paint)
            text.textAlign = Paint.Align.CENTER; text.typeface = bold
            text.textSize = min(14 * dp, r.height() * 0.38f); text.color = Color.WHITE
            canvas.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
        }
        button(retryBtn, "◀ Level")
        button(rackBtn, "Reset")
        button(clearBtn, "Level ▶")

        text.textAlign = Paint.Align.LEFT; text.typeface = bold
        text.textSize = min(15 * dp, rightCard.height() * 0.3f); text.color = Color.WHITE
        canvas.drawText("Level ${match.trick.level + 1} of ${Trick.LEVELS.size}", rightCard.left + 12 * dp, rightCard.top + rightCard.height() * 0.42f, text)
        text.typeface = regular; text.textSize = min(11 * dp, rightCard.height() * 0.22f); text.color = 0xFF9AA7B1.toInt()
        val pct = if (tpTries > 0) " (${tpMade * 100 / tpTries}%)" else ""
        canvas.drawText("Made $tpMade of $tpTries$pct", rightCard.left + 12 * dp, rightCard.top + rightCard.height() * 0.78f, text)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 4 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun drawPlayerCard(canvas: Canvas, r: RectF, player: Int) {
        val active = match.current == player && phase != Phase.OVER && phase != Phase.RPS
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = if (active) 0xFF2B3A33.toInt() else 0xFF1E252B.toInt()
        canvas.drawRoundRect(r, 10 * dp, 10 * dp, paint)
        if (active) {
            guide.pathEffect = null; guide.strokeWidth = 2 * dp; guide.color = 0xFFF2C94C.toInt()
            canvas.drawRoundRect(r, 10 * dp, 10 * dp, guide)
        }
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = min(16 * dp, r.height() * 0.32f)
        text.color = Color.WHITE
        val label = if (isCpu(player)) "CPU · Lv $cpuLevel" else names[player]
        canvas.drawText(label, r.left + 12 * dp, r.top + r.height() * 0.4f, text)

        // Shot clock on the active human's card
        if (active && phase == Phase.HUMAN && shotClock > 0) {
            val secs = max(0, ceil(clockLeft).toInt())
            val low = clockStarted && clockLeft <= 5.0
            text.textAlign = Paint.Align.RIGHT
            text.color = if (!clockStarted) 0xFF9AA7B1.toInt() else if (low) 0xFFFF6B6B.toInt() else 0xFFF2C94C.toInt()
            canvas.drawText(if (clockStarted) "${secs}s" else "${secs}s ⏸", r.right - 12 * dp, r.top + r.height() * 0.4f, text)
            val frac = (clockLeft / shotClock).toFloat().coerceIn(0f, 1f)
            paint.color = 0x33FFFFFF
            canvas.drawRoundRect(r.left + 10 * dp, r.bottom - 6 * dp, r.right - 10 * dp, r.bottom - 3 * dp, 2 * dp, 2 * dp, paint)
            paint.color = if (low) 0xFFFF6B6B.toInt() else 0xFFF2C94C.toInt()
            canvas.drawRoundRect(r.left + 10 * dp, r.bottom - 6 * dp, r.left + 10 * dp + (r.width() - 20 * dp) * frac, r.bottom - 3 * dp, 2 * dp, 2 * dp, paint)
        }

        text.textAlign = Paint.Align.LEFT
        text.typeface = regular
        text.textSize = min(11 * dp, r.height() * 0.22f)
        text.color = 0xFF9AA7B1.toInt()
        val iconR = min(r.height() * 0.16f, 9 * dp)
        val iy = r.top + r.height() * 0.70f
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
            canvas.drawText(sb.toString(), r.left + 12 * dp, iy + iconR * 0.4f, text)
        } else if (circuit) {
            // Your own targets (dim once they're down) when they're on show; otherwise just how many are down.
            val sunk = match.circuitSunk(player)
            var x = r.left + 12 * dp + iconR
            if (player == revealTo || phase == Phase.OVER) {
                for (id in match.circuit.targets[player]) {
                    balls.drawIcon(canvas, id, x, iy, iconR, !match.world.balls[id].onTable, paint)
                    x += iconR * 2.3f
                }
                canvas.drawText(ellipsize("$sunk/3 sunk", r.right - x - 4 * dp), x, iy + iconR * 0.4f, text)
            } else {
                // Three hidden balls: filled in as they go down.
                for (i in 0 until 3) {
                    paint.style = Paint.Style.FILL
                    paint.color = if (i < sunk) 0xFFF2C94C.toInt() else 0xFF3A444C.toInt()
                    canvas.drawCircle(x, iy, iconR, paint)
                    if (i >= sunk) {
                        text.textAlign = Paint.Align.CENTER; text.color = 0xFF9AA7B1.toInt()
                        canvas.drawText("?", x, iy + iconR * 0.45f, text)
                        text.textAlign = Paint.Align.LEFT
                    }
                    x += iconR * 2.3f
                }
                text.color = 0xFF9AA7B1.toInt()
                val hidden = if (phase == Phase.HANDOFF && player == match.current) "tap to see your targets" else "$sunk/3 sunk · hidden"
                canvas.drawText(ellipsize(hidden, r.right - x - 4 * dp), x, iy + iconR * 0.4f, text)
            }
        } else if (bumper) {
            // Five balls each (dim once they're down), and which way this player shoots.
            var x = r.left + 12 * dp + iconR
            for (id in match.bumperBalls(player)) {
                balls.drawIcon(canvas, id, x, iy, iconR, !match.world.balls[id].onTable, paint)
                x += iconR * 2.3f
            }
            val dir = if (match.targetCup(player) == 1) "red · shoots ▶" else "white · shoots ◀"
            canvas.drawText(ellipsize(dir, r.right - x - 4 * dp), x, iy + iconR * 0.4f, text)
        } else if (mode == GameMode.EIGHT_BALL) {
            val g = match.groups[player]
            if (g == Group.OPEN) {
                if (!match.isBreak) canvas.drawText("Open table", r.left + 12 * dp, iy + iconR * 0.4f, text)
            } else {
                var x = r.left + 12 * dp + iconR
                val from = if (g == Group.SOLIDS) 1 else 9
                for (id in from until from + 7) {
                    balls.drawIcon(canvas, id, x, iy, iconR, !match.world.balls[id].onTable, paint)
                    x += iconR * 2.3f
                }
                if (match.onEight(player)) balls.drawIcon(canvas, 8, x + iconR * 0.4f, iy, iconR, false, paint)
            }
        } else if (match.current == player && !match.isBreak) {
            canvas.drawText("Shoot the ${match.lowestBall()}", r.left + 12 * dp, iy + iconR * 0.4f, text)
        }
    }

    private fun drawPowerBar(canvas: Canvas) {
        val r = powerRect
        text.textAlign = Paint.Align.CENTER; text.typeface = bold
        text.textSize = 11 * dp; text.color = 0xFF9AA7B1.toInt()
        canvas.drawText("POWER", r.centerX(), r.top - 10 * dp, text)
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF1E252B.toInt()
        canvas.drawRoundRect(r, r.width() / 2, r.width() / 2, paint)
        if (power > 0) {
            val fillB = r.top + r.height() * power
            canvas.save()
            canvas.clipRect(r.left, r.top, r.right, fillB)
            paint.shader = powerShader
            canvas.drawRoundRect(r, r.width() / 2, r.width() / 2, paint)
            paint.shader = null
            canvas.restore()
            paint.color = Color.WHITE
            canvas.drawRoundRect(r.left - 4 * dp, fillB - 3 * dp, r.right + 4 * dp, fillB + 3 * dp, 3 * dp, 3 * dp, paint)
            text.color = Color.WHITE; text.textSize = 12 * dp
            canvas.drawText(PCT[(power * 100).toInt().coerceIn(0, 100)], r.centerX(), r.bottom + 14 * dp, text)
        } else if (phase == Phase.HUMAN) {
            guide.pathEffect = null; guide.strokeWidth = 2 * dp; guide.color = 0x66FFFFFF
            for (i in 0..2) {
                val y = r.top + 20 * dp + i * 12 * dp
                path.reset()
                path.moveTo(r.centerX() - 7 * dp, y); path.lineTo(r.centerX(), y + 6 * dp); path.lineTo(r.centerX() + 7 * dp, y)
                canvas.drawPath(path, guide)
            }
        }
    }

    private fun drawSpin(canvas: Canvas) {
        text.textAlign = Paint.Align.CENTER; text.typeface = bold
        text.textSize = 11 * dp; text.color = 0xFF9AA7B1.toInt()
        canvas.drawText("SPIN", spinCx, spinCy - spinR - 8 * dp, text)
        paint.style = Paint.Style.FILL
        paint.shader = spinShader
        canvas.drawCircle(spinCx, spinCy, spinR, paint)
        paint.shader = null
        guide.pathEffect = null; guide.strokeWidth = 1 * dp; guide.color = 0x40000000
        canvas.drawLine(spinCx - spinR, spinCy, spinCx + spinR, spinCy, guide)
        canvas.drawLine(spinCx, spinCy - spinR, spinCx, spinCy + spinR, guide)
        // The tip's grip: inside this ring it pushes along the cue; outside it slips (a miscue).
        val pad = spinR * 0.92f
        guide.pathEffect = dash; guide.strokeWidth = 1.3f * dp; guide.color = 0x88B03030.toInt()
        canvas.drawCircle(spinCx, spinCy, pad * (P.MAX_TIP / P.TIP_LIMIT).toFloat(), guide)
        guide.pathEffect = null
        val st = tipState()
        paint.color = when (st) { 2 -> 0xFFFF3B3B.toInt(); 1 -> 0xFFF2994A.toInt(); else -> 0xFFD62828.toInt() }
        canvas.drawCircle(spinCx + spinX * pad, spinCy + spinY * pad, max(5 * dp, spinR * 0.13f), paint)
        text.typeface = regular
        text.textSize = 10.5f * dp; text.color = 0xFF9AA7B1.toInt()
        val v = if (spinY < -0.08f) 1 else if (spinY > 0.08f) 2 else 0
        val h = if (spinX > 0.08f) 1 else if (spinX < -0.08f) 2 else 0
        canvas.drawText(SPIN_LABELS[v * 3 + h], spinCx, spinCy + spinR + 14 * dp, text)
        // Second line: the cue's angle, and a warning if the tip would slip or scoop.
        val raised = elevDeg >= 0.5f
        val line = when (st) {
            2 -> "SCOOP · FOUL"
            1 -> if (raised) "MISCUE · ${elevDeg.toInt()}° cue" else "MISCUE"
            else -> if (raised) "cue ${DEG_LABELS[elevDeg.toInt().coerceIn(0, 60)]} up" else ""
        }
        if (line.isNotEmpty()) {
            text.typeface = bold
            text.color = when (st) { 2 -> 0xFFFF6B6B.toInt(); 1 -> 0xFFF2994A.toInt(); else -> 0xFFF2C94C.toInt() }
            canvas.drawText(ellipsize(line, spinR * 2.4f), spinCx, spinCy + spinR + 29 * dp, text)
        }
    }

    // A one-ball world for checking where the tip is: 0 fine, 1 miscue (slips), 2 scoop (a foul).
    private val tipWorld = World()
    private val tipKey = FloatArray(4) { Float.NaN }
    private var tipLast = 0

    private fun tipState(): Int {
        if (phase != Phase.HUMAN && phase != Phase.CPU_ANIM) return 0
        val pw = if (power > 0.02f) power else lastPower
        if (tipKey[0] == spinX && tipKey[1] == spinY && tipKey[2] == elevDeg && tipKey[3] == pw) return tipLast
        tipKey[0] = spinX; tipKey[1] = spinY; tipKey[2] = elevDeg; tipKey[3] = pw
        val c = tipWorld.balls[0]
        c.x = 1.0; c.y = 0.6; c.onTable = true
        tipWorld.strike(0.0, speedFor(pw), (spinX * P.TIP_LIMIT).toDouble(), (-spinY * P.TIP_LIMIT).toDouble(),
            Math.toRadians(elevDeg.toDouble()))
        tipLast = if (tipWorld.log.scooped) 2 else if (tipWorld.log.miscue) 1 else 0
        c.stop()
        return tipLast
    }

    /** Ball tray: a dark wooden channel; pocketed balls stack up from the bottom in the order made. */
    private fun drawTray(canvas: Canvas) {
        val r = trayRect
        if (r.height() < 40 * dp || potR <= 0f) return
        val rad = r.width() / 2
        paint.style = Paint.Style.FILL
        paint.shader = trayShader
        canvas.drawRoundRect(r, rad, rad, paint)
        paint.shader = null
        guide.pathEffect = null; guide.strokeWidth = 1.5f * dp; guide.color = 0x30FFFFFF
        canvas.drawRoundRect(r, rad, rad, guide)
        canvas.save()
        canvas.clipRect(r.left, r.top + 2 * dp, r.right, r.bottom)
        for (i in 0 until potN) balls.drawTrayBall(canvas, potIds[i], r.centerX(), potY[i], potR, paint)
        canvas.restore()
    }

    /**
     * Fine aim: a wide roller turning about a horizontal axis. Drag up or down; the faster you
     * drag, the further each movement turns the cue (slow drags are extra fine).
     */
    private fun drawWheel(canvas: Canvas) {
        val r = wheelRect
        if (r.height() < 40 * dp) return
        text.textAlign = Paint.Align.CENTER; text.typeface = bold
        text.textSize = 11 * dp; text.color = 0xFF9AA7B1.toInt()
        canvas.drawText("FINE AIM", r.centerX(), r.top - 8 * dp, text)
        paint.style = Paint.Style.FILL
        paint.shader = wheelShader
        canvas.drawRoundRect(r, 12 * dp, 12 * dp, paint)
        paint.shader = null
        canvas.save()
        canvas.clipRect(r)
        val rad = r.height() / 2
        val cy = r.centerY()
        val step = 11 * dp / rad
        val half = (Math.PI / 2).toFloat()
        var a = (wheelOffset / rad) % step
        if (a < 0) a += step
        a -= ceil(half / step) * step
        guide.pathEffect = null
        while (a < half) {
            if (a > -half) {
                val c = cos(a)
                val y = cy + rad * sin(a)
                guide.strokeWidth = (0.6f + 1.8f * c) * dp
                guide.color = Color.argb((40 + 175 * c).toInt(), 160, 176, 190)
                canvas.drawLine(r.left + 10 * dp, y, r.right - 10 * dp, y, guide)
            }
            a += step
        }
        // Darker end caps, and a small marker on each side at the centre line.
        paint.color = 0x66000000
        canvas.drawRect(r.left, r.top, r.left + 6 * dp, r.bottom, paint)
        canvas.drawRect(r.right - 6 * dp, r.top, r.right, r.bottom, paint)
        paint.color = 0xAAF2C94C.toInt()
        path.reset()
        path.moveTo(r.left, cy - 5 * dp); path.lineTo(r.left + 6 * dp, cy); path.lineTo(r.left, cy + 5 * dp); path.close()
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(r.right, cy - 5 * dp); path.lineTo(r.right - 6 * dp, cy); path.lineTo(r.right, cy + 5 * dp); path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    // ------------------------------------------------------------------ rock, paper, scissors (drawing)

    /**
     * Draws a hand gesture centred at (cx, cy), rotated so it points across at the other player.
     * [mirror] flips it left to right first, so the left player's hand is the mirror image of the right's.
     */
    private fun drawGesture(canvas: Canvas, g: Int, cx: Float, cy: Float, size: Float, rot: Float, alpha: Int = 255, mirror: Boolean = false) {
        emoji.textSize = size
        emoji.alpha = alpha
        emoji.getFontMetrics(emojiMetrics)
        canvas.save()
        canvas.translate(cx, cy)
        if (mirror) canvas.scale(-1f, 1f)
        canvas.rotate(rot)
        canvas.drawText(GESTURES[g], 0f, -(emojiMetrics.ascent + emojiMetrics.descent) / 2, emoji)
        canvas.restore()
    }

    private fun drawRps(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xB0000000.toInt()
        canvas.drawRect(0f, hudH, w, h, paint)
        val pw = min(w * 0.8f, 660 * dp)
        val ph = min(h - hudH - 16 * dp, 300 * dp)
        val top = hudH + (h - hudH - ph) / 2
        rpsPanel.set(w / 2 - pw / 2, top, w / 2 + pw / 2, top + ph)
        paint.color = 0xF21E252B.toInt()
        canvas.drawRoundRect(rpsPanel, 18 * dp, 18 * dp, paint)
        guide.pathEffect = null; guide.strokeWidth = 2 * dp; guide.color = 0xFFF2C94C.toInt()
        canvas.drawRoundRect(rpsPanel, 18 * dp, 18 * dp, guide)

        val p = rpsPanel
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold; text.textSize = min(20 * dp, ph * 0.08f); text.color = Color.WHITE
        canvas.drawText("ROCK · PAPER · SCISSORS", p.centerX(), p.top + ph * 0.11f, text)
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
        text.typeface = if (rps == Rps.PICK) regular else bold
        text.textSize = min(if (rps == Rps.PICK) 13 * dp else 17 * dp, ph * 0.07f)
        text.color = if (rps == Rps.PICK) 0xFFB8C2CA.toInt() else 0xFFF2C94C.toInt()
        canvas.drawText(sub, p.centerX(), p.top + ph * 0.2f, text)

        val hand = min(ph * 0.3f, pw * 0.15f)
        val handY = p.top + ph * 0.5f
        val sideX = floatArrayOf(p.left + pw * 0.25f, p.left + pw * 0.75f)
        text.typeface = bold; text.textSize = 15 * dp; text.color = 0xFFF2C94C.toInt()
        canvas.drawText("VS", p.centerX(), handY + 5 * dp, text)
        val bob = if (rps == Rps.SHAKE) abs(sin(Math.PI * rpsT / RPS_BEAT)).toFloat() * hand * 0.35f else 0f
        for (pl in 0..1) {
            val cx = sideX[pl]
            // Both hands point across at each other: the right hand is turned to face left, and the
            // left hand is its mirror image (flipped across the vertical), not the same hand upside down.
            val rot = -90f
            val mirror = pl == 0
            text.typeface = bold; text.textSize = 13 * dp; text.color = Color.WHITE
            canvas.drawText(if (isCpu(pl)) "CPU" else names[pl].uppercase(), cx, p.top + ph * 0.3f, text)
            when (rps) {
                Rps.PICK, Rps.SHAKE -> drawGesture(canvas, 0, cx, handY - bob, hand, rot, 255, mirror)
                Rps.REVEAL -> {
                    val pop = 1f + 0.18f * (1f - (rpsT / 0.15).toFloat().coerceIn(0f, 1f))
                    if (win == pl && rpsT > 0.2) {
                        guide.strokeWidth = 3 * dp; guide.color = 0xFFF2C94C.toInt()
                        canvas.drawCircle(cx, handY, hand * 0.56f, guide)
                    }
                    drawGesture(canvas, rpsPick[pl], cx, handY, hand * pop, rot, if (win >= 0 && win != pl && rpsT > 0.2) 120 else 255, mirror)
                }
            }
            // Choices (or "ready") under each hand.
            val bs = min(pw * 0.5f / 3.5f, ph * 0.26f)
            val by = p.bottom - 12 * dp - bs
            if (rps == Rps.PICK && !isCpu(pl) && rpsPick[pl] < 0) {
                val gap = bs * 0.18f
                var bx = cx - (3 * bs + 2 * gap) / 2
                for (c in 0..2) {
                    val br = rpsBtn[pl][c]
                    br.set(bx, by, bx + bs, by + bs)
                    paint.color = 0xFF2A333B.toInt()
                    canvas.drawRoundRect(br, 12 * dp, 12 * dp, paint)
                    guide.strokeWidth = 1.5f * dp; guide.color = 0x55F2C94C
                    canvas.drawRoundRect(br, 12 * dp, 12 * dp, guide)
                    // Scissors is the peace sign turned sideways.
                    drawGesture(canvas, c, br.centerX(), br.top + bs * 0.42f, bs * 0.46f, if (c == 2) 90f else 0f)
                    text.typeface = regular; text.textSize = min(11 * dp, bs * 0.16f); text.color = 0xFFCED6DD.toInt()
                    canvas.drawText(GESTURE_NAMES[c], br.centerX(), br.bottom - bs * 0.1f, text)
                    bx += bs + gap
                }
            } else if (rps == Rps.PICK) {
                for (c in 0..2) rpsBtn[pl][c].setEmpty()
                text.typeface = bold; text.textSize = 14 * dp; text.color = 0xFF3CCB7F.toInt()
                canvas.drawText(if (isCpu(pl)) "Ready" else "Ready ✓", cx, by + bs / 2 + 5 * dp, text)
            }
        }
        if (rps == Rps.REVEAL && rpsT > 0.6) {
            text.typeface = regular; text.textSize = 11 * dp; text.color = 0xFF9AA7B1.toInt()
            canvas.drawText("tap to continue", p.centerX(), p.bottom - 10 * dp, text)
        }
    }

    /** Circuit Pool, pass & play: the next player's targets stay hidden until they tap. */
    private fun drawHandoff(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xB3000000.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        val pw = min(w * 0.5f, 420 * dp); val ph = min(h * 0.5f, 190 * dp)
        overPanel.set(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        paint.color = 0xFF1E252B.toInt()
        canvas.drawRoundRect(overPanel, 16 * dp, 16 * dp, paint)
        guide.color = 0xFFF2C94C.toInt(); guide.strokeWidth = 2 * dp; guide.pathEffect = null
        canvas.drawRoundRect(overPanel, 16 * dp, 16 * dp, guide)
        val p = match.current
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold; text.textSize = 24 * dp; text.color = Color.WHITE
        canvas.drawText("${names[p]}'s turn", overPanel.centerX(), overPanel.top + ph * 0.3f, text)
        text.typeface = regular; text.textSize = 13 * dp; text.color = 0xFFB8C2CA.toInt()
        canvas.drawText(ellipsize("Hand it to ${names[p]} — ${names[1 - p]}, no peeking", pw - 24 * dp), overPanel.centerX(), overPanel.top + ph * 0.5f, text)
        if (message.isNotEmpty()) canvas.drawText(ellipsize(message, pw - 24 * dp), overPanel.centerX(), overPanel.top + ph * 0.64f, text)
        text.typeface = bold; text.textSize = 15 * dp; text.color = 0xFFF2C94C.toInt()
        canvas.drawText("Tap to see your targets", overPanel.centerX(), overPanel.bottom - 18 * dp, text)
    }

    private fun drawGameOver(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0x99000000.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        val pw = min(w * 0.5f, 420 * dp); val ph = min(h * 0.62f, 240 * dp)
        overPanel.set(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        paint.color = 0xFF1E252B.toInt()
        canvas.drawRoundRect(overPanel, 16 * dp, 16 * dp, paint)
        guide.color = 0xFFF2C94C.toInt(); guide.strokeWidth = 2 * dp; guide.pathEffect = null
        canvas.drawRoundRect(overPanel, 16 * dp, 16 * dp, guide)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold; text.textSize = 30 * dp; text.color = Color.WHITE
        canvas.drawText(gameOverTitle, overPanel.centerX(), overPanel.top + ph * 0.25f, text)
        text.typeface = regular; text.textSize = 13 * dp; text.color = 0xFFB8C2CA.toInt()
        val detail = match.message.substringAfter("(", "").removeSuffix(")")
        if (detail.isNotEmpty()) canvas.drawText(ellipsize(detail, pw - 24 * dp), overPanel.centerX(), overPanel.top + ph * 0.38f, text)
        var recY = 0.52f
        if (circuit) {
            // Everyone's targets, now it's over.
            text.color = 0xFFDDE3E8.toInt(); text.textSize = 13 * dp
            val t = (0..1).joinToString("  ·  ") { p ->
                "${names[p]} had ${match.circuit.targets[p].joinToString(", ")}"
            }
            canvas.drawText(ellipsize(t, pw - 24 * dp), overPanel.centerX(), overPanel.top + ph * 0.49f, text)
            recY = 0.6f
        }
        if (recordLine.isNotEmpty()) {
            text.color = 0xFFF2C94C.toInt(); text.textSize = 14 * dp
            canvas.drawText(ellipsize(recordLine, pw - 24 * dp), overPanel.centerX(), overPanel.top + ph * recY, text)
        }
        val bw = pw * 0.38f; val bh = 46 * dp
        val by = overPanel.bottom - bh - 20 * dp
        rematchBtn.set(overPanel.centerX() - bw - 8 * dp, by, overPanel.centerX() - 8 * dp, by + bh)
        menuExitBtn.set(overPanel.centerX() + 8 * dp, by, overPanel.centerX() + bw + 8 * dp, by + bh)
        paint.color = 0xFFF2C94C.toInt()
        canvas.drawRoundRect(rematchBtn, 12 * dp, 12 * dp, paint)
        paint.color = 0xFF39434C.toInt()
        canvas.drawRoundRect(menuExitBtn, 12 * dp, 12 * dp, paint)
        text.typeface = bold; text.textSize = 16 * dp
        text.color = 0xFF1A1A1A.toInt()
        canvas.drawText("Rematch", rematchBtn.centerX(), rematchBtn.centerY() + 6 * dp, text)
        text.color = Color.WHITE
        canvas.drawText("Menu", menuExitBtn.centerX(), menuExitBtn.centerY() + 6 * dp, text)
    }

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                onDown(e.getPointerId(i), e.getX(i), e.getY(i), e.actionMasked == MotionEvent.ACTION_DOWN, e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) onMove(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> { if (anyTouch(Touch.POWER)) power = 0f; clearTouches() }
        }
        return true
    }

    private fun onDown(id: Int, x: Float, y: Float, first: Boolean, t: Long) {
        if (id !in ptrMode.indices) return
        ptrMode[id] = Touch.NONE
        if (first && menuBtn.contains(x, y) && phase != Phase.OVER) { ptrMode[id] = Touch.BUTTON; host.onMenuRequested(); return }
        if (phase == Phase.OVER) {
            if (first) {
                if (rematchBtn.contains(x, y)) newGame(1 - breaker)
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
                clearBtn.contains(x, y) && phase == Phase.HUMAN -> { practiceClear(); ptrMode[id] = Touch.BUTTON; return }
                phase == Phase.HUMAN && x >= trayRect.left - 8 * dp && x <= trayRect.right + 8 * dp &&
                    y >= trayRect.top && y <= trayRect.bottom -> {
                    // Tap a ball in the tray to put it back on the table.
                    for (i in 0 until potN) if (abs(y - potY[i]) < max(potR * 1.1f, 10 * dp)) { returnBall(potIds[i]); break }
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
            powerRect.left - 20 * dp <= x && x <= powerRect.right + 20 * dp && y >= powerRect.top - 30 * dp && y <= powerRect.bottom + 20 * dp -> {
                if (anyTouch(Touch.POWER)) Touch.NONE else { ptrStartY[id] = y; power = 0f; Touch.POWER }
            }
            spinDist <= spinR * 1.2f -> {
                if (spinDist < spinR * 0.14f) { spinX = 0f; spinY = 0f } else setSpin(x, y)
                Touch.SPIN
            }
            x >= wheelRect.left - 6 * dp && x <= wheelRect.right + 8 * dp && y >= wheelRect.top - 6 * dp && y <= wheelRect.bottom + 8 * dp -> {
                ptrLastY[id] = y; ptrLastT[id] = t; ptrVel[id] = 0f; Touch.WHEEL
            }
            pov && inTable -> povDown(id, x, y, t)
            match.ballInHand && !anyTouch(Touch.POWER) && !anyTouch(Touch.MOVE_CUE) &&
                hypot(x - sx(c.x), y - sy(c.y)) < max(rpx * 3f, 28 * dp) -> {
                dragInvalid = false; Touch.MOVE_CUE
            }
            match.callAllowed() && pocketUnder(x, y) >= 0 -> {
                val pk = pocketUnder(x, y)
                match.calledPocket = if (match.calledPocket == pk) -1 else pk
                Touch.BUTTON
            }
            grabbed >= 0 && !anyTouch(Touch.MOVE_BALL) && !anyTouch(Touch.PENDING) -> {
                // Practice: a short tap aims at the ball; holding still picks it up.
                ptrBall[id] = grabbed; ptrDownX[id] = x; ptrDownY[id] = y; ptrDownAt[id] = System.nanoTime()
                Touch.PENDING
            }
            tapBall >= 0 -> { ptrBall[id] = tapBall; Touch.TAP_BALL }
            inTable -> { aimAt(x, y); Touch.AIM }
            else -> Touch.NONE
        }
        // The shot clock (when waiting during ball in hand) starts once you aim or touch the power bar.
        val m = ptrMode[id]
        if (m == Touch.POWER || m == Touch.WHEEL || m == Touch.AIM || m == Touch.POV || m == Touch.TAP_BALL) clockStarted = true
    }

    private fun pocketUnder(x: Float, y: Float): Int {
        for ((i, pk) in Table.pockets.withIndex()) {
            if (hypot(x - sx(pk.cx), y - sy(pk.cy)) < (pk.capR * scale).toFloat() + 14 * dp) return i
        }
        return -1
    }

    /** A ball other than the cue ball under (x, y) in the top view, for opening the 3D view on it. */
    private fun objectBallUnder(x: Float, y: Float): Int {
        val reach = max(rpx * 1.5f, 18 * dp)
        var best = -1; var bestD = Float.MAX_VALUE
        for (b in match.world.balls) {
            if (!b.onTable || b.id == match.world.cue) continue
            val d = hypot(x - sx(b.x), y - sy(b.y))
            if (d < reach && d < bestD) { best = b.id; bestD = d }
        }
        return best
    }

    private fun ballUnder(x: Float, y: Float): Int {
        val reach = max(rpx * 1.6f, 20 * dp)
        var best = -1; var bestD = Float.MAX_VALUE
        for (b in match.world.balls) {
            if (!b.onTable) continue
            val d = hypot(x - sx(b.x), y - sy(b.y))
            if (d < reach && d < bestD) { best = b.id; bestD = d }
        }
        return best
    }

    /**
     * Fine-aim acceleration: how much the cue turns per dp of finger travel, from the finger's
     * speed. Slow drags (under ~50 dp/s) are about 3x finer than the base setting; ~150 dp/s is
     * the base speed; fast swipes turn up to 25x as far, so a flick swings the cue a long way.
     */
    private fun wheelGain(v: Float): Double = (0.3 + 0.7 * (v / 150.0).pow(1.7)).coerceAtMost(25.0)

    private fun onMove(id: Int, x: Float, y: Float, t: Long) {
        if (id !in ptrMode.indices) return
        ptrX[id] = x; ptrY[id] = y
        if (ptrMode[id] == Touch.CAM) { camMove(); return }
        if (phase != Phase.HUMAN) return
        when (ptrMode[id]) {
            Touch.POWER -> power = ((y - ptrStartY[id]) / (powerRect.height() * 0.9f)).coerceIn(0f, 1f)
            Touch.SPIN -> setSpin(x, y)
            Touch.POV -> povDrag(id, x, y, t)
            Touch.TAP_BALL -> if (hypot(x - ptrDownX[id], y - ptrDownY[id]) > 10 * dp) {
                ptrMode[id] = Touch.AIM
                aimAt(x, y)
            }
            Touch.WHEEL -> {
                val d = y - ptrLastY[id]
                val secs = ((t - ptrLastT[id]) / 1000f).coerceIn(0.004f, 0.1f)
                ptrLastY[id] = y
                ptrLastT[id] = t
                ptrVel[id] = ptrVel[id] * 0.5f + (abs(d) / dp / secs) * 0.5f
                wheelOffset += d
                aimAngle += d / dp * wheelRadPerDp * wheelGain(ptrVel[id])
                tut?.onWheel((d / dp).toDouble())
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
            Touch.PENDING -> if (hypot(x - ptrDownX[id], y - ptrDownY[id]) > 10 * dp) {
                if (pov) { ptrMode[id] = Touch.POV; povDrag(id, x, y, t) }
                else { ptrMode[id] = Touch.AIM; aimAt(x, y) }
            }
            Touch.MOVE_BALL -> moveBall(ptrBall[id], x, y)
            else -> {}
        }
    }

    private fun onUp(id: Int) {
        if (id !in ptrMode.indices) return
        val mode = ptrMode[id]
        ptrMode[id] = Touch.NONE
        // One finger of the camera gesture lifted: the other is ignored until it's lifted too.
        if (mode == Touch.CAM) { for (o in ptrMode.indices) if (ptrMode[o] == Touch.CAM) ptrMode[o] = Touch.BUTTON; return }
        if (phase != Phase.HUMAN) return
        if (pov && (mode == Touch.MOVE_CUE || mode == Touch.MOVE_BALL)) povLocked = false
        tut?.onRelease(targetOf(mode), hypot(spinX, spinY).toDouble(), elevDeg.toDouble())
        if (mode == Touch.POWER) {
            if (power > 0.02f && match.callRequired() && match.calledPocket < 0) {
                power = 0f
                callFlashUntil = System.nanoTime() + 1_500_000_000L
            } else if (power > 0.02f) {
                lastPower = power
                tut?.onShot()
                shoot(aimAngle, speedFor(power), (spinX * P.TIP_LIMIT).toDouble(), (-spinY * P.TIP_LIMIT).toDouble(),
                    Math.toRadians(elevDeg.toDouble()))
            } else power = 0f
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

    private fun aimAt(x: Float, y: Float) {
        val c = match.world.cueBall
        val dx = x - sx(c.x); val dy = y - sy(c.y)
        if (hypot(dx, dy) > rpx * 0.8f) {
            val a = atan2(dy.toDouble(), dx.toDouble())
            var d = a - aimAngle
            while (d > Math.PI) d -= 2 * Math.PI
            while (d < -Math.PI) d += 2 * Math.PI
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
        if (on) povHintUntil = System.nanoTime() + 6_000_000_000L
        tut?.onPov(on)
    }

    /** Hold the camera where it is (it otherwise follows the cue ball). */
    private fun lockPov() {
        val c = match.world.cueBall
        povLocked = true; povLockX = c.x; povLockY = c.y
    }

    /** Screen point to table coordinates (on the plane of the balls' centres in 3D) into [povXY]. */
    private fun toTable(x: Float, y: Float): Boolean {
        if (!pov) { povXY[0] = tx(x); povXY[1] = ty(y); return true }
        return povCam.unproject(x.toDouble(), y.toDouble(), P.R, povXY)
    }

    /** One finger down on the 3D view: the cue ball (ball in hand), a ball to move (practice), or aim / cue angle. */
    private fun povDown(id: Int, x: Float, y: Float, t: Long): Touch {
        ptrLastX[id] = x; ptrLastY[id] = y; ptrLastT[id] = t; ptrVel[id] = 0f; ptrAxis[id] = 0
        val c = match.world.cueBall
        if (match.ballInHand && c.onTable && !anyTouch(Touch.MOVE_CUE) && !anyTouch(Touch.POWER) &&
            povCam.project(c.x, c.y, P.R, povPt)) {
            val r = povCam.ballRadius(povPt[2])
            if (hypot(x - povPt[0], y - povPt[1]) < max(r * 1.8, 28.0 * dp)) {
                lockPov(); dragInvalid = false; dragX = x; dragY = y
                return Touch.MOVE_CUE
            }
        }
        if (practice && !tutorialMode && !anyTouch(Touch.MOVE_BALL) && !anyTouch(Touch.PENDING)) {
            val b = povBallUnder(x, y, true)
            if (b >= 0) { ptrBall[id] = b; ptrDownAt[id] = System.nanoTime(); return Touch.PENDING }
        }
        return Touch.POV
    }

    /** The nearest ball under (x, y) in the 3D view (the cue ball too if [cue]), or -1. */
    private fun povBallUnder(x: Float, y: Float, cue: Boolean): Int {
        var best = -1; var bestD = Double.MAX_VALUE
        for (b in match.world.balls) {
            if (!b.onTable || (b.id == match.world.cue && !cue)) continue
            if (!povCam.project(b.x, b.y, P.R + b.z, povPt)) continue
            val r = povCam.ballRadius(povPt[2])
            if (hypot(x - povPt[0], y - povPt[1]) < max(r * 1.3, 16.0 * dp) && povPt[2] < bestD) { best = b.id; bestD = povPt[2] }
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
        if (camDist > 10 * dp && d > 10 * dp) povCtl.pinch((d / camDist).toDouble())
        povCtl.tilt((my - camMidY).toDouble(), tableArea.height().toDouble())
        povCtl.orbit((mx - camMidX).toDouble(), tableArea.width().toDouble())
        camDist = d; camMidX = mx; camMidY = my
    }

    /**
     * Bumper pool: a tap on one of your own balls picks it to shoot (aimed at your cup). Returns true
     * if the tap was taken that way: your ball, picked or not (the marked ball has to go first).
     */
    private fun pickBumperBall(id: Int): Boolean {
        if (!bumper || match.ownerOf(id) != match.current) return false
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
        while (d > Math.PI) d -= 2 * Math.PI
        while (d < -Math.PI) d += 2 * Math.PI
        tut?.onAim(d)
        aimAngle = a
    }

    /**
     * Dragging in the 3D view. The first ~8 dp decide the direction: sideways turns the cue (faster the
     * quicker you drag, like the fine-aim roller but coarser), up and down raise and lower the cue.
     */
    private fun povDrag(id: Int, x: Float, y: Float, t: Long) {
        if (ptrAxis[id] < 0) return
        if (ptrAxis[id] == 0) {
            val mx = x - ptrDownX[id]; val my = y - ptrDownY[id]
            if (hypot(mx, my) < 8 * dp) return
            ptrAxis[id] = if (abs(mx) >= abs(my)) 1 else 2
            ptrLastX[id] = ptrDownX[id]; ptrLastY[id] = ptrDownY[id]
        }
        val ddx = x - ptrLastX[id]; val ddy = y - ptrLastY[id]
        val secs = ((t - ptrLastT[id]) / 1000f).coerceIn(0.004f, 0.1f)
        ptrLastX[id] = x; ptrLastY[id] = y; ptrLastT[id] = t
        if (ptrAxis[id] == 1) {
            ptrVel[id] = ptrVel[id] * 0.5f + (abs(ddx) / dp / secs) * 0.5f
            val gain = (0.35 + 0.65 * (ptrVel[id] / 150.0).pow(1.5)).coerceAtMost(8.0)
            aimAngle += ddx / dp * POV_AIM_PER_DP * gain
        } else {
            var e = elevDeg - ddy / dp * POV_ELEV_PER_DP
            e = e.coerceIn(0f, P.MAX_ELEVATION.toFloat())
            elevDeg = e
            tut?.onElevation(e.toDouble())
        }
    }

    /** A tap in the 3D view: Top view, a pocket (to call it) or a ball (to line up on it). */
    private fun povTap(x: Float, y: Float) {
        if (match.callAllowed()) {
            for ((i, pk) in Table.pockets.withIndex()) {
                if (!povCam.project(pk.cx, pk.cy, 0.0, povPt)) continue
                val r = povCam.focal * pk.capR / povPt[2] + 14 * dp
                if (hypot(x - povPt[0].toFloat(), y - povPt[1].toFloat()) < r) {
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
            val d = hypot(x - povPt[0], y - povPt[1])
            if (d < max(r * 1.3, 16.0 * dp) && povPt[2] < bestD) { best = b.id; bestD = povPt[2] }
        }
        if (best > 0 && !pickBumperBall(best)) aimAtBall(best)
    }

    private fun setSpin(x: Float, y: Float) {
        var u = (x - spinCx) / (spinR * 0.92f); var v = (y - spinCy) / (spinR * 0.92f)
        val m = hypot(u, v)
        if (m > 1f) { u /= m; v /= m }
        spinX = u; spinY = v
        tut?.onSpin(u.toDouble(), v.toDouble())
    }

    private companion object {
        val PCT = Array(101) { "$it%" }
        /** 3D view: radians the cue turns per dp of sideways drag at moderate speed. */
        const val POV_AIM_PER_DP = 0.0022
        /** 3D view: degrees the cue rises per dp of upward drag. */
        const val POV_ELEV_PER_DP = 0.3f
        val DEG_LABELS = Array(61) { "$it°" }
        /** On screen, a jumping ball is drawn this many times higher than it really is. */
        const val JUMP_LIFT = 1.6
        /** ...and this much bigger per metre of height (closer to the viewer). */
        const val JUMP_GROW = 8.0
        const val RPS_BEAT = 0.42
        /** Bumper pool cue length, as a fraction of a full-length one: shorter than a real bumper cue, so it stays over the table. */
        const val SHORT_CUE = 0.55
        val GESTURES = arrayOf("\u270A", "\u270B", "\u270C\uFE0F")   // fist, flat hand, peace sign
        val GESTURE_NAMES = arrayOf("Rock", "Paper", "Scissors")
        val SHAKE_WORDS = arrayOf("Rock…", "Paper…", "Scissors…")
        val SPIN_LABELS = arrayOf(
            "center", "right", "left",
            "follow", "follow right", "follow left",
            "draw", "draw right", "draw left"
        )
    }
}
