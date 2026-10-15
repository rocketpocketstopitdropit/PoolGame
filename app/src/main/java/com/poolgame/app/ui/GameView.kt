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
import com.poolgame.app.core.Prediction
import com.poolgame.app.core.Predictor
import com.poolgame.app.core.Table
import com.poolgame.app.core.Trick
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
    private val practice: Boolean,       // free practice: no rules, move any ball
    aimSensitivity: Int,                 // fine-aim wheel speed, 1..10
    var showPrediction: Boolean,         // draw where the balls will go (otherwise just an aim line)
    resume: String?,                     // a saved game to continue, or null for a new game
    private val callMode: CallMode,      // called-pocket rule
    private val host: Host
) : View(context), Choreographer.FrameCallback {

    interface Host {
        fun onMenuRequested()
        fun onExitToMenu()
        /** Called once per finished game; returns a one-line record summary to show. */
        fun onGameFinished(winner: Int): String
        /** Rock, paper, scissors decided who breaks: [rounds] throws, ties included. */
        fun onRpsFinished(winner: Int, rounds: Int)
    }

    private enum class Phase { RPS, HUMAN, CPU_THINK, CPU_ANIM, RUNNING, PAUSE, OVER }
    private enum class Rps { PICK, SHAKE, REVEAL }
    private enum class Touch { NONE, AIM, POWER, SPIN, ELEV, WHEEL, MOVE_CUE, BUTTON, PENDING, MOVE_BALL }

    private val names = if (vsCpu) arrayOf("You", "CPU") else arrayOf("Player 1", "Player 2")
    private val rng = Random(System.nanoTime())
    private var match = Match(mode, names)
    private var breaker = 0
    private var phase = Phase.HUMAN
    private var generation = 0

    // Shot controls
    private var aimAngle = 0.0
    private var power = 0f            // 0..1
    private var lastPower = 0.35f     // used for the guide while the power bar is untouched
    private var spinX = 0f            // -1..1 (right english > 0)
    private var spinY = 0f            // -1..1 (screen down = draw)
    private var elevDeg = 0f          // cue elevation, 0..60 degrees (jump shots)
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

    // Layout
    private var scale = 1f
    private var ox = 0f; private var oy = 0f
    private var rpx = 10f
    private var dp = 1f
    private var hudH = 0f
    private val tableArea = RectF()
    private val powerRect = RectF()
    private var spinCx = 0f; private var spinCy = 0f; private var spinR = 0f
    private val elevRect = RectF()
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
        balls.plain = practice
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
        match = Match(mode, names)
        match.world.trackOrientation = true
        match.world.listener = makeListener()
        match.rack(rng, breaker)
        match.callMode = if (practice) CallMode.OFF else callMode
        if (practice) {
            match.ballInHand = false
            match.kitchenOnly = false
            match.message = "Practice · hold any ball to move it"
            practiceSnapshot = null
        }
        sinks.clear()
        potN = 0
        spinX = 0f; spinY = 0f; power = 0f; elevDeg = 0f
        gameOverTitle = ""
        recordLine = ""
        bannerLevel = -1; bannerT = 0.0
        rpsRounds = 0
        message = match.message
        if (practice) startTurn() else startRps(false)
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

    fun isPractice() = practice

    private fun settings() = GameSettings(mode, vsCpu, cpuLevel, shotClock, practice, breaker, match.callMode)

    private fun resumeGame(text: String): Boolean {
        val st = GameSave.readSettings(text) ?: return false
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
        message = if (practice) "Practice · hold any ball to move it" else "Game resumed · " + match.message
        bannerLevel = -1
        startTurn()
        return true
    }

    /**
     * The game to save when leaving it, or null when it's over. A shot that is still rolling is
     * undone (the saved game is from the start of that turn).
     */
    fun saveState(): String? {
        if (match.winner >= 0 || phase == Phase.OVER || phase == Phase.RPS) return null
        return when (phase) {
            Phase.HUMAN, Phase.PAUSE -> GameSave.write(match, settings())
            else -> turnSave ?: GameSave.write(match, settings())
        }
    }

    private fun startTurn() {
        match.prepareTurn()
        if (trick && match.winner < 0 && match.trick.levelNo != bannerLevel) {
            bannerLevel = match.trick.levelNo
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
        } else {
            phase = Phase.HUMAN
            spinX = 0f; spinY = 0f
            clockLeft = shotClock.toDouble()
            // With ball in hand the clock waits until you start aiming or touch the power bar.
            clockStarted = !match.ballInHand
            lastBeep = 0
            aimAtNearestTarget()
        }
    }

    private fun aimAtNearestTarget() {
        val c = match.world.balls[0]
        if (trick) {
            val b = match.world.balls[Trick.TARGET]
            if (b.onTable) { aimAngle = atan2(b.y - c.y, b.x - c.x); return }
        }
        val t = match.legalTargets().filter { match.world.balls[it].onTable }
            .minByOrNull { hypot(match.world.balls[it].x - c.x, match.world.balls[it].y - c.y) } ?: return
        val b = match.world.balls[t]
        aimAngle = atan2(b.y - c.y, b.x - c.x)
    }

    private fun beginCpuAnim(plan: Plan?) {
        val p = plan ?: Plan(aimAngle, 2.0, 0.0, 0.0, "fallback")
        cpuPlan = p
        animT = 0.0
        animFromAngle = aimAngle
        val c = match.world.balls[0]
        animFromX = c.x; animFromY = c.y
        if (p.hasPlacement && !match.placeCue(p.placeX, p.placeY)) {
            cpuPlan = Plan(p.angle, p.speed, p.side, p.vert, p.kind, elevation = p.elevation, pocket = p.pocket)
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
        val b = if (hit[0] == 1.0 && id > 0) match.world.balls[id] else match.world.balls[0]
        return match.world.nearestPocket(b.x, b.y)
    }

    private fun speedFor(pw: Float): Double = 0.25 + (P.MAX_CUE_SPEED - 0.25) * pw.toDouble().pow(1.6)
    private fun powerFor(speed: Double): Float = ((speed - 0.25) / (P.MAX_CUE_SPEED - 0.25)).coerceIn(0.0, 1.0).pow(1 / 1.6).toFloat()

    private fun shoot(angle: Double, speed: Double, side: Double, vert: Double, elev: Double) {
        if (practice) practiceSnapshot = World().also { it.copyFrom(match.world) }
        turnSave = GameSave.write(match, settings())
        sfx.strike(speed)
        match.shoot(angle, speed, side, vert, elev)
        phase = Phase.RUNNING
        clearTouches()
    }

    /** Practice: holding a finger still on a ball for a moment picks it up. Always returns true. */
    private fun checkLongPress(): Boolean {
        if (!practice) return true
        val now = System.nanoTime()
        for (i in ptrMode.indices) if (ptrMode[i] == Touch.PENDING && now - ptrDownAt[i] > 280_000_000L) {
            ptrMode[i] = Touch.MOVE_BALL
            movingBall = ptrBall[i]
            dragInvalid = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        return true
    }

    private fun moveBall(id: Int, x: Float, y: Float) {
        dragX = x; dragY = y
        val px = tx(x).coerceIn(P.R, P.L - P.R); val py = ty(y).coerceIn(P.R, P.W - P.R)
        val w = match.world
        if (w.isFree(px, py, id)) {
            val b = w.balls[id]
            b.x = px; b.y = py; b.stop()
            dragInvalid = false
        } else dragInvalid = true
    }

    private fun practiceRetry() {
        val snap = practiceSnapshot ?: return
        if (phase != Phase.HUMAN && phase != Phase.RUNNING) return
        match.world.copyFrom(snap)
        for (b in match.world.balls) b.moving = false
        sinks.clear()
        phase = Phase.HUMAN
        power = 0f
        message = "Shot reset"
    }

    private fun practiceClear() {
        for (id in 1..15) { val b = match.world.balls[id]; b.onTable = false; b.stop() }
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
        if (phase != Phase.RUNNING) for (id in match.ballIds()) if (!w.balls[id].onTable) addToTray(id)
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
            Phase.HUMAN -> if (checkLongPress() && !practice && shotClock > 0 && !menuOpen && clockStarted) {
                clockLeft -= dt
                val secs = ceil(clockLeft).toInt()
                if (secs in 1..5 && secs != lastBeep) { lastBeep = secs; sfx.beep() }
                if (clockLeft <= 0) {
                    clearTouches()
                    power = 0f
                    val out = match.timeFoul()
                    message = out.message
                    phase = Phase.PAUSE
                    pauseT = 1.0
                }
            }
            Phase.RUNNING -> {
                match.world.advance(dt)
                if (!match.world.anyMoving() && practice) {
                    if (!match.world.balls[0].onTable) {
                        match.respawnCue()
                        message = "Cue ball respotted · hold it to move it"
                    } else message = ""
                    phase = Phase.HUMAN
                    power = 0f
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
                    spinX = (p.side / P.MAX_TIP * e).toFloat()
                    spinY = (-p.vert / P.MAX_TIP * e).toFloat()
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
        // Left: power bar, then the ball tray. Right: the cue-angle slider beside the spin ball,
        // and the fine-aim wheel filling the space below them.
        val leftW = max(w * 0.08f, 58 * dp)
        val trayW = 28 * dp
        val stripW = 26 * dp
        val rightW = max(w * 0.15f, 96 * dp) + stripW
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
        val stripCx = colL + 4 * dp + stripW / 2
        val spinL = colL + stripW + 8 * dp; val spinRt = w - 8 * dp
        spinR = min((spinRt - spinL) * 0.42f, (h - hudH) * 0.15f)
        spinCx = (spinL + spinRt) / 2
        spinCy = hudH + 24 * dp + spinR
        elevRect.set(stripCx - 3 * dp, spinCy - spinR, stripCx + 3 * dp, spinCy + spinR + 16 * dp)
        wheelRect.set(colL + 8 * dp, elevRect.bottom + 38 * dp, w - 10 * dp, h - 14 * dp)
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
        for (pk in Table.pockets) {
            val r = (pk.capR * scale).toFloat()
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
        if (Table.gap) return
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

        if (humanTurn && match.ballInHand && match.kitchenOnly) {
            paint.style = Paint.Style.FILL; paint.shader = null
            paint.color = 0x22FFFFFF
            canvas.drawRect(sx(0.0), sy(0.0), sx(P.HEAD_X), sy(P.W), paint)
        }

        if (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) drawGuide(canvas)

        val cue = w.balls[0]
        var cueX = cue.x; var cueY = cue.y
        val plan = cpuPlan
        if (phase == Phase.CPU_ANIM && plan != null && plan.hasPlacement && animT < 0.45) {
            val k = (animT / 0.45).coerceIn(0.0, 1.0)
            cueX = animFromX + (plan.placeX - animFromX) * k
            cueY = animFromY + (plan.placeY - animFromY) * k
        }
        // Shadows stay on the cloth; airborne balls are drawn larger (closer to the viewer).
        for (b in w.balls) if (b.onTable) {
            val bx = if (b.id == 0) cueX else b.x; val by = if (b.id == 0) cueY else b.y
            val lift = (b.z * scale).toFloat()
            balls.drawShadow(canvas, sx(bx) + lift * 0.4f, sy(by) + lift * 0.6f)
        }
        for (s in sinks) {
            val k = 1f - s.age / 0.35f
            paint.alpha = (255 * k).toInt()
            balls.draw(canvas, w.balls[s.id], sx(s.x), sy(s.y), 0.55f + 0.45f * k, paint)
            paint.alpha = 255
        }
        for (b in w.balls) if (b.onTable && b.z <= 0.0) {
            val bx = if (b.id == 0) cueX else b.x; val by = if (b.id == 0) cueY else b.y
            balls.draw(canvas, b, sx(bx), sy(by))
        }
        for (b in w.balls) if (b.onTable && b.z > 0.0) {
            balls.draw(canvas, b, sx(b.x), sy(b.y), 1f + (b.z * 4.0).toFloat())
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

        val showCue = (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && cue.onTable) drawCueStick(canvas, sx(cueX), sy(cueY))

        if (bannerT > 0 && trick) drawBanner(canvas)
        drawHud(canvas)
        drawPowerBar(canvas)
        drawTray(canvas)
        drawSpin(canvas)
        drawElevation(canvas)
        drawWheel(canvas)
        if (phase == Phase.RPS) drawRps(canvas)
        if (phase == Phase.OVER) drawGameOver(canvas)
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
        val head = if (t.suddenDeath) "SUDDEN DEATH · ${lv.name.uppercase()}"
            else "LEVEL ${t.levelNo + 1} OF ${Trick.LEVELS.size} · ${lv.name.uppercase()}"
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
        val c = match.world.balls[0]
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
            (spinX * P.MAX_TIP).toDouble(), (-spinY * P.MAX_TIP).toDouble(), Math.toRadians(elevDeg.toDouble()),
            prediction
        )
    }

    private fun drawGuide(canvas: Canvas) {
        val c = match.world.balls[0]
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
            guide.color = 0xE0FF5050.toInt(); guide.strokeWidth = 2 * dp
            canvas.drawCircle(sx(pr.cueX[pr.cueN - 1]), sy(pr.cueY[pr.cueN - 1]), rpx * 1.3f, guide)
        }
    }

    private fun drawCueStick(canvas: Canvas, cx: Float, cy: Float) {
        val s = scale
        // An elevated cue is foreshortened when seen from above.
        val fore = cos(Math.toRadians(elevDeg.toDouble()))
        val gap = (P.R + 0.012 + power * 0.22) * s * fore
        val len = 1.45 * s * fore
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
            val a = -gap - stickSegs[i] * s * fore; val b = -gap - stickSegs[i + 1] * s * fore
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

        if (practice) { drawPracticeHud(canvas); return }
        drawPlayerCard(canvas, leftCard, 0)
        drawPlayerCard(canvas, rightCard, 1)

        val cardH = leftCard.height()
        val midL = leftCard.right + 10 * dp; val midR = rightCard.left - 10 * dp
        text.typeface = regular
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFE8ECEF.toInt()
        text.textSize = min(15 * dp, hudH * 0.26f)
        var msg = when (phase) {
            Phase.CPU_THINK, Phase.CPU_ANIM -> if (message.isNotEmpty()) message else "CPU is thinking…"
            Phase.HUMAN -> when {
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
        canvas.drawText("PRACTICE", leftCard.left + 12 * dp, leftCard.top + leftCard.height() * 0.42f, text)
        text.typeface = regular; text.textSize = min(11 * dp, leftCard.height() * 0.22f); text.color = 0xFF9AA7B1.toInt()
        val sub = if (message.isNotEmpty()) message else "Hold any ball to move it"
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
        canvas.drawCircle(spinCx, spinCy, spinR * 0.5f, guide)
        paint.color = 0xFFD62828.toInt()
        canvas.drawCircle(spinCx + spinX * spinR * 0.92f, spinCy + spinY * spinR * 0.92f, max(5 * dp, spinR * 0.13f), paint)
        text.typeface = regular
        text.textSize = 10.5f * dp; text.color = 0xFF9AA7B1.toInt()
        val v = if (spinY < -0.08f) 1 else if (spinY > 0.08f) 2 else 0
        val h = if (spinX > 0.08f) 1 else if (spinX < -0.08f) 2 else 0
        canvas.drawText(SPIN_LABELS[v * 3 + h], spinCx, spinCy + spinR + 14 * dp, text)
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

    /** Cue angle: a vertical slider beside the spin ball. Slide up to raise the cue for a jump. */
    private fun drawElevation(canvas: Canvas) {
        val r = elevRect
        val cx = r.centerX()
        val raised = elevDeg > 0.5f
        text.textAlign = Paint.Align.CENTER; text.typeface = bold
        text.textSize = 11 * dp
        text.color = if (raised) 0xFFF2C94C.toInt() else 0xFF9AA7B1.toInt()
        canvas.drawText("JUMP", cx, spinCy - spinR - 8 * dp, text)
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF1E252B.toInt()
        canvas.drawRoundRect(r, 3 * dp, 3 * dp, paint)
        // Tick marks every 15 degrees.
        guide.pathEffect = null; guide.strokeWidth = 1 * dp; guide.color = 0x55FFFFFF
        for (k in 1..3) {
            val ty = r.bottom - r.height() * k * 15f / P.MAX_ELEVATION.toFloat()
            canvas.drawLine(r.left - 5 * dp, ty, r.left - 2 * dp, ty, guide)
        }
        val f = elevDeg / P.MAX_ELEVATION.toFloat()
        val ky = r.bottom - r.height() * f
        paint.color = 0xFFF2C94C.toInt()
        canvas.drawRoundRect(r.left, ky, r.right, r.bottom, 3 * dp, 3 * dp, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, ky, 8 * dp, paint)
        text.typeface = regular; text.textSize = 10.5f * dp
        canvas.drawText(if (raised) DEG_LABELS[elevDeg.toInt().coerceIn(0, 60)] else "level", cx, r.bottom + 22 * dp, text)
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
        if (recordLine.isNotEmpty()) {
            text.color = 0xFFF2C94C.toInt(); text.textSize = 14 * dp
            canvas.drawText(ellipsize(recordLine, pw - 24 * dp), overPanel.centerX(), overPanel.top + ph * 0.52f, text)
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
        if (phase == Phase.RPS) {
            ptrMode[id] = Touch.BUTTON
            when (rps) {
                Rps.PICK -> for (pl in 0..1) if (!isCpu(pl)) for (c in 0..2) if (rpsBtn[pl][c].contains(x, y)) rpsChoose(pl, c)
                Rps.REVEAL -> if (rpsT > 0.6) rpsDone()
                else -> {}
            }
            return
        }
        if (practice && first) {
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
        if (phase != Phase.HUMAN) return
        val c = match.world.balls[0]
        val spinDist = hypot(x - spinCx, y - spinCy)
        val grabbed = if (practice && tableArea.contains(x, y)) ballUnder(x, y) else -1
        ptrMode[id] = when {
            powerRect.left - 20 * dp <= x && x <= powerRect.right + 20 * dp && y >= powerRect.top - 30 * dp && y <= powerRect.bottom + 20 * dp -> {
                if (anyTouch(Touch.POWER)) Touch.NONE else { ptrStartY[id] = y; power = 0f; Touch.POWER }
            }
            x >= elevRect.left - 14 * dp && x <= elevRect.right + 9 * dp && y >= elevRect.top - 14 * dp && y <= elevRect.bottom + 12 * dp -> {
                setCueAngle(y); Touch.ELEV
            }
            spinDist <= spinR * 1.2f -> {
                if (spinDist < spinR * 0.14f) { spinX = 0f; spinY = 0f } else setSpin(x, y)
                Touch.SPIN
            }
            x >= wheelRect.left - 6 * dp && x <= wheelRect.right + 8 * dp && y >= wheelRect.top - 6 * dp && y <= wheelRect.bottom + 8 * dp -> {
                ptrLastY[id] = y; ptrLastT[id] = t; ptrVel[id] = 0f; Touch.WHEEL
            }
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
            tableArea.contains(x, y) -> { aimAt(x, y); Touch.AIM }
            else -> Touch.NONE
        }
        // The shot clock (when waiting during ball in hand) starts once you aim or touch the power bar.
        val m = ptrMode[id]
        if (m == Touch.POWER || m == Touch.WHEEL || m == Touch.AIM) clockStarted = true
    }

    private fun pocketUnder(x: Float, y: Float): Int {
        for ((i, pk) in Table.pockets.withIndex()) {
            if (hypot(x - sx(pk.cx), y - sy(pk.cy)) < (pk.capR * scale).toFloat() + 14 * dp) return i
        }
        return -1
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
        if (phase != Phase.HUMAN || id !in ptrMode.indices) return
        when (ptrMode[id]) {
            Touch.POWER -> power = ((y - ptrStartY[id]) / (powerRect.height() * 0.9f)).coerceIn(0f, 1f)
            Touch.SPIN -> setSpin(x, y)
            Touch.ELEV -> setCueAngle(y)
            Touch.WHEEL -> {
                val d = y - ptrLastY[id]
                val secs = ((t - ptrLastT[id]) / 1000f).coerceIn(0.004f, 0.1f)
                ptrLastY[id] = y
                ptrLastT[id] = t
                ptrVel[id] = ptrVel[id] * 0.5f + (abs(d) / dp / secs) * 0.5f
                wheelOffset += d
                aimAngle += d / dp * wheelRadPerDp * wheelGain(ptrVel[id])
            }
            Touch.MOVE_CUE -> {
                dragX = x; dragY = y
                val px = tx(x); val py = ty(y)
                val maxX = when {
                    !match.kitchenOnly -> P.L - P.R
                    Table.gap -> Table.GAP_X0 - 0.61 * P.R     // stay on the near section
                    else -> P.HEAD_X
                }
                dragInvalid = !match.placeCue(px.coerceIn(P.R, maxX), py.coerceIn(P.R, P.W - P.R))
            }
            Touch.AIM -> aimAt(x, y)
            Touch.PENDING -> if (hypot(x - ptrDownX[id], y - ptrDownY[id]) > 10 * dp) {
                ptrMode[id] = Touch.AIM
                aimAt(x, y)
            }
            Touch.MOVE_BALL -> moveBall(ptrBall[id], x, y)
            else -> {}
        }
    }

    private fun onUp(id: Int) {
        if (id !in ptrMode.indices) return
        val mode = ptrMode[id]
        ptrMode[id] = Touch.NONE
        if (phase != Phase.HUMAN) return
        if (mode == Touch.POWER) {
            if (power > 0.02f && match.callRequired() && match.calledPocket < 0) {
                power = 0f
                callFlashUntil = System.nanoTime() + 1_500_000_000L
            } else if (power > 0.02f) {
                lastPower = power
                shoot(aimAngle, speedFor(power), (spinX * P.MAX_TIP).toDouble(), (-spinY * P.MAX_TIP).toDouble(),
                    Math.toRadians(elevDeg.toDouble()))
            } else power = 0f
        }
        if (mode == Touch.MOVE_CUE) dragInvalid = false
        if (mode == Touch.PENDING) aimAt(ptrDownX[id], ptrDownY[id])
        if (mode == Touch.MOVE_BALL) { movingBall = -1; dragInvalid = false }
    }

    private fun aimAt(x: Float, y: Float) {
        val c = match.world.balls[0]
        val dx = x - sx(c.x); val dy = y - sy(c.y)
        if (hypot(dx, dy) > rpx * 0.8f) aimAngle = atan2(dy.toDouble(), dx.toDouble())
    }

    private fun setSpin(x: Float, y: Float) {
        var u = (x - spinCx) / (spinR * 0.92f); var v = (y - spinCy) / (spinR * 0.92f)
        val m = hypot(u, v)
        if (m > 1f) { u /= m; v /= m }
        spinX = u; spinY = v
    }

    private fun setCueAngle(y: Float) {
        val f = ((elevRect.bottom - y) / elevRect.height()).coerceIn(0f, 1f)
        var d = f * P.MAX_ELEVATION.toFloat()
        if (d < 3f) d = 0f
        elevDeg = d
    }

    private companion object {
        val PCT = Array(101) { "$it%" }
        val DEG_LABELS = Array(61) { "$it°" }
        const val RPS_BEAT = 0.42
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
