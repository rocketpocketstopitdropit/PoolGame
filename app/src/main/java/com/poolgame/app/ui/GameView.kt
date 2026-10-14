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
import com.poolgame.app.core.GameMode
import com.poolgame.app.core.Group
import com.poolgame.app.core.Match
import com.poolgame.app.core.P
import com.poolgame.app.core.Plan
import com.poolgame.app.core.Prediction
import com.poolgame.app.core.Predictor
import com.poolgame.app.core.Table
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
    private val host: Host
) : View(context), Choreographer.FrameCallback {

    interface Host {
        fun onMenuRequested()
        fun onExitToMenu()
        /** Called once per finished game; returns a one-line record summary to show. */
        fun onGameFinished(winner: Int): String
    }

    private enum class Phase { HUMAN, CPU_THINK, CPU_ANIM, RUNNING, PAUSE, OVER }
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
    private var wheelOffset = 0f
    private var dragInvalid = false
    private var dragX = 0f; private var dragY = 0f

    // Practice mode
    private var practiceSnapshot: World? = null
    private val retryBtn = RectF()
    private val rackBtn = RectF()
    private val clearBtn = RectF()
    private val trayIds = IntArray(15)
    private val trayX = FloatArray(15)
    private var trayN = 0
    private var trayY = 0f
    private var trayR = 0f
    private var movingBall = -1

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
        newGame(0)
    }

    private fun makeListener() = object : WorldListener {
        override fun onBallHit(speed: Double) = sfx.ball(speed)
        override fun onCushion(speed: Double) { if (speed > 0.15) sfx.rail(speed) }
        override fun onPocket(id: Int) {
            sfx.drop()
            val b = match.world.balls[id]
            sinks += Sink(id, b.x, b.y, 0f)
        }
    }

    private fun isCpu(player: Int) = vsCpu && player == 1

    // ------------------------------------------------------------------ game flow

    fun newGame(breakerPlayer: Int) {
        generation++
        breaker = breakerPlayer
        match = Match(mode, names)
        match.world.trackOrientation = true
        match.world.listener = makeListener()
        match.rack(rng, breaker)
        if (practice) {
            match.ballInHand = false
            match.kitchenOnly = false
            match.message = "Practice · hold any ball to move it"
            practiceSnapshot = null
        }
        sinks.clear()
        spinX = 0f; spinY = 0f; power = 0f; elevDeg = 0f
        gameOverTitle = ""
        recordLine = ""
        message = match.message
        startTurn()
    }

    fun restart() = newGame(breaker)

    private fun startTurn() {
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
            cpuPlan = Plan(p.angle, p.speed, p.side, p.vert, p.kind, elevation = p.elevation)
        }
        phase = Phase.CPU_ANIM
    }

    private fun speedFor(pw: Float): Double = 0.25 + (P.MAX_CUE_SPEED - 0.25) * pw.toDouble().pow(1.6)
    private fun powerFor(speed: Double): Float = ((speed - 0.25) / (P.MAX_CUE_SPEED - 0.25)).coerceIn(0.0, 1.0).pow(1 / 1.6).toFloat()

    private fun shoot(angle: Double, speed: Double, side: Double, vert: Double, elev: Double) {
        if (practice) practiceSnapshot = World().also { it.copyFrom(match.world) }
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
        message = "Table cleared · tap balls on the right to bring them back"
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

    private fun update(dt: Double) {
        if (sinks.isNotEmpty()) {
            for (s in sinks) s.age += dt.toFloat()
            sinks.removeAll { it.age > 0.35f }
        }
        when (phase) {
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
                    pauseT = if (out.foul || out.winner >= 0) 0.9 else 0.45
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
        val leftW = max(w * 0.1f, 64 * dp)
        val rightW = max(w * 0.15f, 96 * dp)
        tableArea.set(leftW, hudH, w - rightW, h.toFloat())
        val rail = 0.115
        val tw = P.L + 2 * rail; val th = P.W + 2 * rail
        scale = (min(tableArea.width() / tw, (tableArea.height() - 6 * dp) / th) * 0.98).toFloat()
        ox = tableArea.left + (tableArea.width() - (tw * scale).toFloat()) / 2 + (rail * scale).toFloat()
        oy = tableArea.top + (tableArea.height() - (th * scale).toFloat()) / 2 + (rail * scale).toFloat()
        rpx = (P.R * scale).toFloat()
        balls.setRadius(rpx)

        val pw = leftW * 0.36f
        powerRect.set(leftW / 2 - pw / 2, hudH + 34 * dp, leftW / 2 + pw / 2, h - 18 * dp)
        spinR = min(rightW * 0.32f, (h - hudH) * 0.15f)
        spinCx = w - rightW / 2
        spinCy = hudH + 24 * dp + spinR
        val elevTop = spinCy + spinR + 44 * dp
        elevRect.set(w - rightW + 12 * dp, elevTop, w - 12 * dp, elevTop + 22 * dp)
        val ww = min(rightW * 0.42f, 52 * dp)
        wheelRect.set(spinCx - ww / 2, elevRect.bottom + 30 * dp, spinCx + ww / 2, h - 16 * dp)
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
        wheelShader = LinearGradient(wheelRect.left, 0f, wheelRect.right, 0f,
            intArrayOf(0xFF15191D.toInt(), 0xFF39434C.toInt(), 0xFF15191D.toInt()), null, Shader.TileMode.CLAMP)
        val buttW = (0.015 * scale).toFloat()
        for (i in 0 until 8) stickShaders[i] = LinearGradient(0f, -buttW, 0f, buttW,
            intArrayOf(stickColors[i * 2 + 1], stickColors[i * 2], stickColors[i * 2 + 1]), null, Shader.TileMode.CLAMP)
        dash = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)

        background?.recycle()
        background = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawBackground(Canvas(it), w, h, rail) }
        predKey[0] = Double.NaN
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

        for (pk in Table.pockets) {
            val r = ((pk.capR + 0.004) * scale).toFloat()
            p.color = 0xFF2A1A10.toInt()
            c.drawCircle(sx(pk.cx), sy(pk.cy), r + 3 * dp, p)
            p.shader = RadialGradient(sx(pk.cx), sy(pk.cy), r, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF1B1B1B.toInt()),
                floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(sx(pk.cx), sy(pk.cy), r, p)
            p.shader = null
        }
        val nose = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = 0xFF0E4A2D.toInt(); strokeCap = Paint.Cap.ROUND }
        for (sg in Table.segs) c.drawLine(sx(sg.ax), sy(sg.ay), sx(sg.bx), sy(sg.by), nose)

        p.color = 0xFFEDE3C8.toInt()
        val dOff = rail * 0.55
        val dr = (0.012 * scale).toFloat()
        for (i in 1..7) if (i != 4) {
            val x = P.L * i / 8
            diamond(c, sx(x), sy(-dOff), dr, p)
            diamond(c, sx(x), sy(P.W + dOff), dr, p)
        }
        for (i in 1..3) {
            val y = P.W * i / 4
            diamond(c, sx(-dOff), sy(y), dr, p)
            diamond(c, sx(P.L + dOff), sy(y), dr, p)
        }
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

        val showCue = (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && cue.onTable) drawCueStick(canvas, sx(cueX), sy(cueY))

        drawHud(canvas)
        drawPowerBar(canvas)
        drawSpin(canvas)
        drawElevation(canvas)
        drawWheel(canvas)
        if (phase == Phase.OVER) drawGameOver(canvas)
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
                match.ballInHand && match.isBreak -> "${names[match.current]}: place the cue ball behind the line, then break"
                match.ballInHand -> "${names[match.current]}: ball in hand — drag the cue ball" +
                    if (shotClock > 0 && !clockStarted) " · clock starts when you aim" else ""
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

        // Tray of balls that are off the table: tap one to bring it back.
        trayN = 0
        for (id in 1..15) if (!match.world.balls[id].onTable) trayIds[trayN++] = id
        text.textAlign = Paint.Align.LEFT; text.typeface = regular
        text.textSize = min(11 * dp, rightCard.height() * 0.22f); text.color = 0xFF9AA7B1.toInt()
        canvas.drawText(if (trayN == 0) "All balls on the table" else "Tap a ball to put it back",
            rightCard.left + 12 * dp, rightCard.top + rightCard.height() * 0.32f, text)
        trayR = min(rightCard.height() * 0.17f, (rightCard.width() - 24 * dp) / (15 * 2.2f))
        trayY = rightCard.top + rightCard.height() * 0.68f
        var x = rightCard.left + 12 * dp + trayR
        for (i in 0 until trayN) {
            trayX[i] = x
            balls.drawIcon(canvas, trayIds[i], x, trayY, trayR, false, paint)
            x += trayR * 2.2f
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 4 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun drawPlayerCard(canvas: Canvas, r: RectF, player: Int) {
        val active = match.current == player && phase != Phase.OVER
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
        if (mode == GameMode.EIGHT_BALL) {
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

    private fun drawElevation(canvas: Canvas) {
        val r = elevRect
        text.textAlign = Paint.Align.CENTER; text.typeface = bold
        text.textSize = 11 * dp
        text.color = if (elevDeg > 0.5f) 0xFFF2C94C.toInt() else 0xFF9AA7B1.toInt()
        canvas.drawText(if (elevDeg > 0.5f) JUMP_LABELS[elevDeg.toInt().coerceIn(0, 60)] else "CUE ANGLE · level", r.centerX(), r.top - 8 * dp, text)
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF1E252B.toInt()
        val cy = r.centerY()
        canvas.drawRoundRect(r.left, cy - 3 * dp, r.right, cy + 3 * dp, 3 * dp, 3 * dp, paint)
        val f = elevDeg / P.MAX_ELEVATION.toFloat()
        val kx = r.left + (r.width()) * f
        paint.color = 0xFFF2C94C.toInt()
        canvas.drawRoundRect(r.left, cy - 3 * dp, kx, cy + 3 * dp, 3 * dp, 3 * dp, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(kx, cy, 8 * dp, paint)
    }

    private fun drawWheel(canvas: Canvas) {
        val r = wheelRect
        if (r.height() < 40 * dp) return
        text.textAlign = Paint.Align.CENTER; text.typeface = bold
        text.textSize = 11 * dp; text.color = 0xFF9AA7B1.toInt()
        canvas.drawText("FINE AIM", r.centerX(), r.top - 8 * dp, text)
        paint.style = Paint.Style.FILL
        paint.shader = wheelShader
        canvas.drawRoundRect(r, 10 * dp, 10 * dp, paint)
        paint.shader = null
        canvas.save()
        canvas.clipRect(r)
        val spacing = 9 * dp
        var y = r.top + ((wheelOffset % spacing) + spacing) % spacing
        guide.pathEffect = null; guide.strokeWidth = 2 * dp
        while (y < r.bottom) {
            val rel = ((y - r.centerY()) / (r.height() / 2)).coerceIn(-1f, 1f)
            guide.color = Color.argb((200 * (1 - rel * rel)).toInt(), 150, 165, 178)
            canvas.drawLine(r.left + 6 * dp, y, r.right - 6 * dp, y, guide)
            y += spacing
        }
        canvas.restore()
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
                onDown(e.getPointerId(i), e.getX(i), e.getY(i), e.actionMasked == MotionEvent.ACTION_DOWN)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) onMove(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> { if (anyTouch(Touch.POWER)) power = 0f; clearTouches() }
        }
        return true
    }

    private fun onDown(id: Int, x: Float, y: Float, first: Boolean) {
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
        if (practice && first) {
            when {
                retryBtn.contains(x, y) -> { practiceRetry(); ptrMode[id] = Touch.BUTTON; return }
                rackBtn.contains(x, y) -> { newGame(0); ptrMode[id] = Touch.BUTTON; return }
                clearBtn.contains(x, y) && phase == Phase.HUMAN -> { practiceClear(); ptrMode[id] = Touch.BUTTON; return }
                phase == Phase.HUMAN && rightCard.contains(x, y) -> {
                    for (i in 0 until trayN) if (hypot(x - trayX[i], y - trayY) < max(trayR * 1.3f, 14 * dp)) {
                        returnBall(trayIds[i]); break
                    }
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
            spinDist <= spinR * 1.2f -> {
                if (spinDist < spinR * 0.14f) { spinX = 0f; spinY = 0f } else setSpin(x, y)
                Touch.SPIN
            }
            x >= elevRect.left - 10 * dp && x <= elevRect.right + 10 * dp && y >= elevRect.top - 22 * dp && y <= elevRect.bottom + 10 * dp -> {
                setCueAngle(x); Touch.ELEV
            }
            x >= wheelRect.left - 16 * dp && x <= wheelRect.right + 16 * dp && y >= wheelRect.top && y <= wheelRect.bottom -> {
                ptrLastY[id] = y; Touch.WHEEL
            }
            match.ballInHand && !anyTouch(Touch.POWER) && !anyTouch(Touch.MOVE_CUE) &&
                hypot(x - sx(c.x), y - sy(c.y)) < max(rpx * 3f, 28 * dp) -> {
                dragInvalid = false; Touch.MOVE_CUE
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

    private fun onMove(id: Int, x: Float, y: Float) {
        if (phase != Phase.HUMAN || id !in ptrMode.indices) return
        when (ptrMode[id]) {
            Touch.POWER -> power = ((y - ptrStartY[id]) / (powerRect.height() * 0.9f)).coerceIn(0f, 1f)
            Touch.SPIN -> setSpin(x, y)
            Touch.ELEV -> setCueAngle(x)
            Touch.WHEEL -> {
                val d = y - ptrLastY[id]
                ptrLastY[id] = y
                wheelOffset += d
                aimAngle += d / dp * 0.0002   // ~0.01 degree per dp of finger travel
            }
            Touch.MOVE_CUE -> {
                dragX = x; dragY = y
                val px = tx(x); val py = ty(y)
                dragInvalid = !match.placeCue(
                    px.coerceIn(P.R, if (match.kitchenOnly) P.HEAD_X else P.L - P.R),
                    py.coerceIn(P.R, P.W - P.R)
                )
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
            if (power > 0.02f) {
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

    private fun setCueAngle(x: Float) {
        val f = ((x - elevRect.left) / elevRect.width()).coerceIn(0f, 1f)
        var d = f * P.MAX_ELEVATION.toFloat()
        if (d < 3f) d = 0f
        elevDeg = d
    }

    private companion object {
        val PCT = Array(101) { "$it%" }
        val JUMP_LABELS = Array(61) { "JUMP · $it°" }
        val SPIN_LABELS = arrayOf(
            "center", "right", "left",
            "follow", "follow right", "follow left",
            "draw", "draw right", "draw left"
        )
    }
}
