package com.poolgame.web

import com.poolgame.app.core.Ai
import com.poolgame.app.core.CallMode
import com.poolgame.app.core.GameMode
import com.poolgame.app.core.GameSave
import com.poolgame.app.core.GameSettings
import com.poolgame.app.core.Group
import com.poolgame.app.core.Match
import com.poolgame.app.core.P
import com.poolgame.app.core.Plan
import com.poolgame.app.core.Prediction
import com.poolgame.app.core.Predictor
import com.poolgame.app.core.Table
import com.poolgame.app.core.World
import com.poolgame.app.core.WorldListener
import com.poolgame.app.core.toRad
import kotlin.math.PI
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
}

/** The game screen, drawn on a canvas. A port of the Android GameView. */
class Game(
    private val mode: GameMode,
    private val vsCpu: Boolean,
    private val cpuLevel: Int,
    private val shotClock: Int,
    private val practice: Boolean,
    aimSensitivity: Int,
    var showPrediction: Boolean,
    resume: String?,
    private val callMode: CallMode,
    private val sfx: Sounds,
    private val host: GameHost
) {
    private enum class Phase { HUMAN, CPU_THINK, CPU_ANIM, RUNNING, PAUSE, OVER }
    private enum class Touch { NONE, AIM, POWER, SPIN, ELEV, WHEEL, MOVE_CUE, BUTTON, PENDING, MOVE_BALL }

    private val names = if (vsCpu) arrayOf("You", "CPU") else arrayOf("Player 1", "Player 2")
    private val rng = Random(now().toLong())
    private var match = Match(mode, names)
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
    private var wheelOffset = 0.0
    private var dragInvalid = false
    private var dragX = 0.0; private var dragY = 0.0

    private var practiceSnapshot: World? = null
    private val retryBtn = Rect(); private val rackBtn = Rect(); private val clearBtn = Rect()
    private val trayIds = IntArray(15); private val trayX = DoubleArray(15)
    private var trayN = 0; private var trayY = 0.0; private var trayR = 0.0
    private var movingBall = -1

    private val wheelRadPerPx = 0.0002 * 1.6.pow((aimSensitivity.coerceIn(1, 10) - 5).toDouble())
    private var turnSave: String? = null
    private val hit = DoubleArray(4)

    private var callFlashUntil = 0.0
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
    private val elevRect = Rect(); private val wheelRect = Rect()
    private val menuBtn = Rect(); private val rematchBtn = Rect(); private val menuExitBtn = Rect()
    private val overPanel = Rect(); private val leftCard = Rect(); private val rightCard = Rect()
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
        balls.plain = practice
        if (resume == null || !resumeGame(resume)) newGame(0)
    }

    private fun makeListener() = object : WorldListener {
        override fun onBallHit(speed: Double) = sfx.ball(speed)
        override fun onCushion(speed: Double) { if (speed > 0.15) sfx.rail(speed) }
        override fun onPocket(id: Int) {
            sfx.drop()
            val b = match.world.balls[id]
            sinks += Sink(id, b.x, b.y, 0.0)
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
        match.callMode = if (practice) CallMode.OFF else callMode
        if (practice) {
            match.ballInHand = false
            match.kitchenOnly = false
            match.message = "Practice · hold any ball to move it"
            practiceSnapshot = null
        }
        sinks.clear()
        spinX = 0.0; spinY = 0.0; power = 0.0; elevDeg = 0.0
        gameOverTitle = ""; recordLine = ""
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
        spinX = 0.0; spinY = 0.0; power = 0.0; elevDeg = 0.0
        gameOverTitle = ""; recordLine = ""
        message = if (practice) "Practice · hold any ball to move it" else "Game resumed · " + match.message
        startTurn()
        return true
    }

    /** The game to save when leaving it, or null when it's over. A rolling shot is undone. */
    fun saveState(): String? {
        if (match.winner >= 0 || phase == Phase.OVER) return null
        return when (phase) {
            Phase.HUMAN, Phase.PAUSE -> GameSave.write(match, settings())
            else -> turnSave ?: GameSave.write(match, settings())
        }
    }

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
        power = 0.0
        elevDeg = 0.0
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
        } else {
            phase = Phase.HUMAN
            spinX = 0.0; spinY = 0.0
            clockLeft = shotClock.toDouble()
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

    private fun nearestPocketToAim(): Int {
        match.world.castCue(aimAngle, hit)
        val id = hit[3].toInt()
        val b = if (hit[0] == 1.0 && id > 0) match.world.balls[id] else match.world.balls[0]
        return match.world.nearestPocket(b.x, b.y)
    }

    private fun speedFor(pw: Double) = 0.25 + (P.MAX_CUE_SPEED - 0.25) * pw.pow(1.6)
    private fun powerFor(speed: Double) = ((speed - 0.25) / (P.MAX_CUE_SPEED - 0.25)).coerceIn(0.0, 1.0).pow(1 / 1.6)

    private fun shoot(angle: Double, speed: Double, side: Double, vert: Double, elev: Double) {
        if (practice) practiceSnapshot = World().also { it.copyFrom(match.world) }
        turnSave = GameSave.write(match, settings())
        sfx.strike(speed)
        match.shoot(angle, speed, side, vert, elev)
        phase = Phase.RUNNING
        clearTouches()
    }

    private fun clearTouches() { for (i in ptrMode.indices) ptrMode[i] = Touch.NONE }
    private fun anyTouch(t: Touch): Boolean { for (m in ptrMode) if (m == t) return true; return false }

    private fun checkLongPress() {
        if (!practice) return
        val t = now()
        for (i in ptrMode.indices) if (ptrMode[i] == Touch.PENDING && t - ptrDownAt[i] > 280) {
            ptrMode[i] = Touch.MOVE_BALL
            movingBall = ptrBall[i]
            dragInvalid = false
            try { if (navigator.vibrate != null) navigator.vibrate(15) } catch (e: dynamic) { }
        }
    }

    private fun moveBall(id: Int, x: Double, y: Double) {
        dragX = x; dragY = y
        val px = tx(x).coerceIn(P.R, P.L - P.R); val py = ty(y).coerceIn(P.R, P.W - P.R)
        val w = match.world
        if (w.isFree(px, py, id)) { val b = w.balls[id]; b.x = px; b.y = py; b.stop(); dragInvalid = false } else dragInvalid = true
    }

    private fun practiceRetry() {
        val snap = practiceSnapshot ?: return
        if (phase != Phase.HUMAN && phase != Phase.RUNNING) return
        match.world.copyFrom(snap)
        for (b in match.world.balls) b.moving = false
        sinks.clear(); phase = Phase.HUMAN; power = 0.0
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
                val a = 2 * PI * k / steps
                val x = P.L / 2 + 0.25 + r * cos(a); val y = P.W / 2 + r * sin(a)
                if (w.isFree(x, y, id)) {
                    val b = w.balls[id]; b.x = x; b.y = y; b.stop(); b.onTable = true
                    message = "Hold the ball to move it"
                    return
                }
            }
            r += 0.06
        }
    }

    fun update(dt: Double) {
        if (sinks.isNotEmpty()) {
            for (s in sinks) s.age += dt
            sinks.removeAll { it.age > 0.35 }
        }
        when (phase) {
            Phase.HUMAN -> {
                checkLongPress()
                if (!practice && shotClock > 0 && !menuOpen && clockStarted) {
                    clockLeft -= dt
                    val secs = ceil(clockLeft).toInt()
                    if (secs in 1..5 && secs != lastBeep) { lastBeep = secs; sfx.beep() }
                    if (clockLeft <= 0) {
                        clearTouches(); power = 0.0
                        message = match.timeFoul().message
                        phase = Phase.PAUSE; pauseT = 1.0
                    }
                }
            }
            Phase.RUNNING -> {
                match.world.advance(dt)
                if (!match.world.anyMoving()) {
                    if (practice) {
                        message = if (!match.world.balls[0].onTable) { match.respawnCue(); "Cue ball respotted · hold it to move it" } else ""
                        phase = Phase.HUMAN; power = 0.0
                    } else {
                        val out = match.resolve()
                        message = out.message
                        phase = Phase.PAUSE
                        pauseT = if (out.foul || out.winner >= 0) 0.9 else 0.45
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
                    spinX = p.side / P.MAX_TIP * e
                    spinY = -p.vert / P.MAX_TIP * e
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

    private fun sx(x: Double) = ox + x * scale
    private fun sy(y: Double) = oy + y * scale
    private fun tx(px: Double) = (px - ox) / scale
    private fun ty(py: Double) = (py - oy) / scale

    fun resize(w: Double, h: Double, devicePixelRatio: Double) {
        width = w; height = h; dpr = devicePixelRatio
        hudH = max(h * 0.13, 44.0)
        val leftW = max(w * 0.1, 64.0)
        val rightW = max(w * 0.15, 96.0)
        tableArea.set(leftW, hudH, w - rightW, h)
        val rail = 0.115
        val tw = P.L + 2 * rail; val th = P.W + 2 * rail
        scale = min(tableArea.w / tw, (tableArea.h - 6) / th) * 0.98
        ox = tableArea.l + (tableArea.w - tw * scale) / 2 + rail * scale
        oy = tableArea.t + (tableArea.h - th * scale) / 2 + rail * scale
        rpx = P.R * scale
        balls.setRadius(rpx, dpr)

        val pw = leftW * 0.36
        powerRect.set(leftW / 2 - pw / 2, hudH + 34, leftW / 2 + pw / 2, h - 18)
        spinR = min(rightW * 0.32, (h - hudH) * 0.15)
        spinCx = w - rightW / 2
        spinCy = hudH + 24 + spinR
        val elevTop = spinCy + spinR + 44
        elevRect.set(w - rightW + 12, elevTop, w - 12, elevTop + 22)
        val ww = min(rightW * 0.42, 52.0)
        wheelRect.set(spinCx - ww / 2, elevRect.b + 30, spinCx + ww / 2, h - 16)
        menuBtn.set(8.0, 8.0, 52.0, 44.0)
        val cardW = min(w * 0.27, 260.0)
        val cardH = hudH - 16
        leftCard.set(menuBtn.r + 10, 8.0, menuBtn.r + 10 + cardW, 8 + cardH)
        rightCard.set(w - 8 - cardW, 8.0, w - 8, 8 + cardH)
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
        bc.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
        drawBackground(bc, w, h, rail)
        background = bg

        powerGrad = bc.createLinearGradient(0.0, powerRect.t, 0.0, powerRect.b).also {
            it.addColorStop(0.0, "#3CCB7F"); it.addColorStop(0.5, "#F2C94C"); it.addColorStop(1.0, "#EB5757")
        }
        spinGrad = bc.createRadialGradient(spinCx - spinR * 0.3, spinCy - spinR * 0.35, 0.0, spinCx - spinR * 0.3, spinCy - spinR * 0.35, spinR * 1.3).also {
            it.addColorStop(0.0, "#FFFFFF"); it.addColorStop(0.55, "#E9E4D4"); it.addColorStop(1.0, "#AFA997")
        }
        wheelGrad = bc.createLinearGradient(wheelRect.l, 0.0, wheelRect.r, 0.0).also {
            it.addColorStop(0.0, "#15191D"); it.addColorStop(0.5, "#39434C"); it.addColorStop(1.0, "#15191D")
        }
        val buttW = 0.015 * scale
        for (i in 0 until 8) stickGrads[i] = bc.createLinearGradient(0.0, -buttW, 0.0, buttW).also {
            it.addColorStop(0.0, col(stickColors[i * 2 + 1])); it.addColorStop(0.5, col(stickColors[i * 2])); it.addColorStop(1.0, col(stickColors[i * 2 + 1]))
        }
        predKey[0] = Double.NaN
    }

    private fun drawBackground(c: Ctx2D, w: Double, h: Double, rail: Double) {
        val g0 = c.createLinearGradient(0.0, 0.0, 0.0, h)
        g0.addColorStop(0.0, "#1A2026"); g0.addColorStop(1.0, "#0D1013")
        c.fillStyle = g0; c.fillRect(0.0, 0.0, w, h)

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

        // Pockets: the hole is exactly the drop zone, outlined in a bright ring.
        for (pk in Table.pockets) {
            val pr = pk.capR * scale
            val hole = c.createRadialGradient(sx(pk.cx), sy(pk.cy), 0.0, sx(pk.cx), sy(pk.cy), pr)
            hole.addColorStop(0.0, "#000"); hole.addColorStop(0.8, "#000"); hole.addColorStop(1.0, "#141414")
            c.fillStyle = hole; c.circle(sx(pk.cx), sy(pk.cy), pr); c.fill()
            c.strokeStyle = "#F2E3B3"; c.lineWidth = 2.0
            c.circle(sx(pk.cx), sy(pk.cy), pr); c.stroke()
        }
        c.strokeStyle = "#0E4A2D"; c.lineWidth = 2.0; c.lineCap = "round"
        for (sg in Table.segs) c.line(sx(sg.ax), sy(sg.ay), sx(sg.bx), sy(sg.by))

        c.fillStyle = "#EDE3C8"
        val dOff = rail * 0.55
        val dr = 0.012 * scale
        fun diamond(x: Double, y: Double) {
            c.beginPath(); c.moveTo(x, y - dr); c.lineTo(x + dr * 0.6, y); c.lineTo(x, y + dr); c.lineTo(x - dr * 0.6, y); c.closePath(); c.fill()
        }
        for (i in 1..7) if (i != 4) { val x = P.L * i / 8; diamond(sx(x), sy(-dOff)); diamond(sx(x), sy(P.W + dOff)) }
        for (i in 1..3) { val y = P.W * i / 4; diamond(sx(-dOff), sy(y)); diamond(sx(P.L + dOff), sy(y)) }
        c.strokeStyle = "rgba(255,255,255,0.19)"; c.lineWidth = 1.0
        c.setLineDash(arrayOf(4.0, 6.0)); c.line(sx(P.HEAD_X), sy(0.0), sx(P.HEAD_X), sy(P.W)); c.setLineDash(noDash)
        c.fillStyle = "rgba(255,255,255,0.33)"
        c.circle(sx(P.FOOT_X), sy(P.FOOT_Y), 2.5); c.fill()
        c.circle(sx(P.HEAD_X), sy(P.FOOT_Y), 2.5); c.fill()
    }

    // ------------------------------------------------------------------ drawing

    fun draw(ctx: Ctx2D) {
        ctx.drawImage(background, 0.0, 0.0, width, height)
        val w = match.world
        val humanTurn = phase == Phase.HUMAN

        if (humanTurn && match.ballInHand && match.kitchenOnly) {
            ctx.fillStyle = "rgba(255,255,255,0.13)"
            ctx.fillRect(sx(0.0), sy(0.0), P.HEAD_X * scale, P.W * scale)
        }
        if (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) drawGuide(ctx)

        val cue = w.balls[0]
        var cueX = cue.x; var cueY = cue.y
        val plan = cpuPlan
        if (phase == Phase.CPU_ANIM && plan != null && plan.hasPlacement && animT < 0.45) {
            val k = (animT / 0.45).coerceIn(0.0, 1.0)
            cueX = animFromX + (plan.placeX - animFromX) * k
            cueY = animFromY + (plan.placeY - animFromY) * k
        }
        for (b in w.balls) if (b.onTable) {
            val bx = if (b.id == 0) cueX else b.x; val by = if (b.id == 0) cueY else b.y
            val lift = b.z * scale
            balls.drawShadow(ctx, sx(bx) + lift * 0.4, sy(by) + lift * 0.6)
        }
        for (s in sinks) {
            val k = 1 - s.age / 0.35
            balls.draw(ctx, w.balls[s.id], sx(s.x), sy(s.y), 0.55 + 0.45 * k, k.coerceIn(0.0, 1.0))
        }
        for (b in w.balls) if (b.onTable && b.z <= 0.0) {
            val bx = if (b.id == 0) cueX else b.x; val by = if (b.id == 0) cueY else b.y
            balls.draw(ctx, b, sx(bx), sy(by))
        }
        for (b in w.balls) if (b.onTable && b.z > 0.0) balls.draw(ctx, b, sx(b.x), sy(b.y), 1 + b.z * 4)

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

        val showCue = (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && cue.onTable) drawCueStick(ctx, sx(cueX), sy(cueY))

        drawHud(ctx)
        drawPowerBar(ctx)
        drawSpin(ctx)
        drawElevation(ctx)
        drawWheel(ctx)
        if (phase == Phase.OVER) drawGameOver(ctx)
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

    private fun guideSpeed() = speedFor(if (power > 0.02) power else lastPower)

    private fun updatePrediction() {
        val c = match.world.balls[0]
        val sp = guideSpeed()
        val k = doubleArrayOf(aimAngle, sp, spinX, spinY, elevDeg, c.x, c.y, match.current.toDouble(),
            generation + (if (match.ballInHand) 0.5 else 0.0))
        var same = true
        for (i in 0 until 9) if (predKey[i] != k[i]) { same = false; predKey[i] = k[i] }
        if (same) return
        Predictor.run(match.world, scratchWorld, aimAngle, sp, spinX * P.MAX_TIP, -spinY * P.MAX_TIP, toRad(elevDeg), prediction)
    }

    private fun drawGuide(ctx: Ctx2D) {
        val c = match.world.balls[0]
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
            val legal = practice || pr.hitId in match.legalTargets()
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
            ctx.strokeStyle = "rgba(255,80,80,0.88)"; ctx.lineWidth = 2.0
            ctx.circle(sx(pr.cueX[pr.cueN - 1].toDouble()), sy(pr.cueY[pr.cueN - 1].toDouble()), rpx * 1.3); ctx.stroke()
        }
    }

    private fun drawCueStick(ctx: Ctx2D, cx: Double, cy: Double) {
        val s = scale
        val fore = cos(toRad(elevDeg))
        val gap = (P.R + 0.012 + power * 0.22) * s * fore
        val len = 1.45 * s * fore
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
            val a = -gap - stickSegs[i] * s * fore; val b = -gap - stickSegs[i + 1] * s * fore
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
        if (practice) { drawPracticeHud(ctx); return }
        drawPlayerCard(ctx, leftCard, 0)
        drawPlayerCard(ctx, rightCard, 1)
        val cardH = leftCard.h
        val midL = leftCard.r + 10; val midR = rightCard.l - 10
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.fillStyle = "#E8ECEF"; ctx.font = font(min(15.0, hudH * 0.26), false)
        val msg = when (phase) {
            Phase.CPU_THINK, Phase.CPU_ANIM -> if (message.isNotEmpty()) message else "CPU is thinking…"
            Phase.HUMAN -> when {
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
            ctx.fillText("CPU (level $cpuLevel) is thinking…", (midL + midR) / 2, subY)
        } else if (mode == GameMode.NINE_BALL) {
            val r = min(hudH * 0.13, 9.0)
            var x = (midL + midR) / 2 - 9 * r * 2.4 / 2 + r
            for (id in 1..9) { balls.icon(ctx, id, x, subY - r * 0.4, r, !match.world.balls[id].onTable); x += r * 2.4 }
        }
    }

    private fun drawPracticeHud(ctx: Ctx2D) {
        ctx.fillStyle = "#1E252B"
        ctx.roundRect(leftCard, 10.0); ctx.fill()
        ctx.roundRect(rightCard, 10.0); ctx.fill()
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(16.0, leftCard.h * 0.32), true); ctx.fillStyle = "#FFF"
        ctx.fillText("PRACTICE", leftCard.l + 12, leftCard.t + leftCard.h * 0.42)
        ctx.font = font(min(11.0, leftCard.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
        ctx.fillText(ellipsize(ctx, if (message.isNotEmpty()) message else "Hold any ball to move it", leftCard.w - 20), leftCard.l + 12, leftCard.t + leftCard.h * 0.78)
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
        button(clearBtn, "Clear table", true)
        trayN = 0
        for (id in 1..15) if (!match.world.balls[id].onTable) trayIds[trayN++] = id
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(11.0, rightCard.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
        ctx.fillText(if (trayN == 0) "All balls on the table" else "Tap a ball to put it back", rightCard.l + 12, rightCard.t + rightCard.h * 0.32)
        trayR = min(rightCard.h * 0.17, (rightCard.w - 24) / (15 * 2.2))
        trayY = rightCard.t + rightCard.h * 0.68
        var x = rightCard.l + 12 + trayR
        for (i in 0 until trayN) { trayX[i] = x; balls.icon(ctx, trayIds[i], x, trayY, trayR, false); x += trayR * 2.2 }
    }

    private fun drawPlayerCard(ctx: Ctx2D, r: Rect, player: Int) {
        val active = match.current == player && phase != Phase.OVER
        ctx.fillStyle = if (active) "#2B3A33" else "#1E252B"
        ctx.roundRect(r, 10.0); ctx.fill()
        if (active) { ctx.strokeStyle = "#F2C94C"; ctx.lineWidth = 2.0; ctx.roundRect(r, 10.0); ctx.stroke() }
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(16.0, r.h * 0.32), true); ctx.fillStyle = "#FFF"
        ctx.fillText(if (isCpu(player)) "CPU · Lv $cpuLevel" else names[player], r.l + 12, r.t + r.h * 0.4)
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
        if (mode == GameMode.EIGHT_BALL) {
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
        ctx.circle(spinCx, spinCy, spinR * 0.5); ctx.stroke()
        ctx.fillStyle = "#D62828"
        ctx.circle(spinCx + spinX * spinR * 0.92, spinCy + spinY * spinR * 0.92, max(5.0, spinR * 0.13)); ctx.fill()
        ctx.font = font(10.5, false); ctx.fillStyle = "#9AA7B1"
        val v = if (spinY < -0.08) "follow " else if (spinY > 0.08) "draw " else ""
        val h = if (spinX > 0.08) "right" else if (spinX < -0.08) "left" else ""
        ctx.fillText((v + h).trim().ifEmpty { "center" }, spinCx, spinCy + spinR + 14)
    }

    private fun drawElevation(ctx: Ctx2D) {
        val r = elevRect
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(11.0, true)
        ctx.fillStyle = if (elevDeg > 0.5) "#F2C94C" else "#9AA7B1"
        ctx.fillText(if (elevDeg > 0.5) "JUMP · ${elevDeg.toInt()}°" else "CUE ANGLE · level", r.cx, r.t - 8)
        val cy = r.cy
        ctx.fillStyle = "#1E252B"; ctx.roundRect(r.l, cy - 3, r.w, 6.0, 3.0); ctx.fill()
        val kx = r.l + r.w * (elevDeg / P.MAX_ELEVATION)
        ctx.fillStyle = "#F2C94C"; ctx.roundRect(r.l, cy - 3, kx - r.l, 6.0, 3.0); ctx.fill()
        ctx.fillStyle = "#FFF"; ctx.circle(kx, cy, 8.0); ctx.fill()
    }

    private fun drawWheel(ctx: Ctx2D) {
        val r = wheelRect
        if (r.h < 40) return
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(11.0, true); ctx.fillStyle = "#9AA7B1"
        ctx.fillText("FINE AIM", r.cx, r.t - 8)
        ctx.fillStyle = wheelGrad; ctx.roundRect(r, 10.0); ctx.fill()
        ctx.save()
        ctx.beginPath(); ctx.rect(r.l, r.t, r.w, r.h); ctx.clip()
        val spacing = 9.0
        var y = r.t + ((wheelOffset % spacing) + spacing) % spacing
        ctx.lineWidth = 2.0
        while (y < r.b) {
            val rel = ((y - r.cy) / (r.h / 2)).coerceIn(-1.0, 1.0)
            ctx.strokeStyle = "rgba(150,165,178,${0.78 * (1 - rel * rel)})"
            ctx.line(r.l + 6, y, r.r - 6, y)
            y += spacing
        }
        ctx.restore()
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
        if (recordLine.isNotEmpty()) {
            ctx.fillStyle = "#F2C94C"; ctx.font = font(14.0, false)
            ctx.fillText(ellipsize(ctx, recordLine, pw - 24), overPanel.cx, overPanel.t + ph * 0.52)
        }
        val bw = pw * 0.38; val bh = 46.0
        val by = overPanel.b - bh - 20
        rematchBtn.set(overPanel.cx - bw - 8, by, overPanel.cx - 8, by + bh)
        menuExitBtn.set(overPanel.cx + 8, by, overPanel.cx + bw + 8, by + bh)
        ctx.fillStyle = "#F2C94C"; ctx.roundRect(rematchBtn, 12.0); ctx.fill()
        ctx.fillStyle = "#39434C"; ctx.roundRect(menuExitBtn, 12.0); ctx.fill()
        ctx.font = font(16.0, true); ctx.textBaseline = "middle"
        ctx.fillStyle = "#1A1A1A"; ctx.fillText("Rematch", rematchBtn.cx, rematchBtn.cy)
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
                    for (i in 0 until trayN) if (hypot(x - trayX[i], y - trayY) < max(trayR * 1.3, 14.0)) { returnBall(trayIds[i]); break }
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
            powerRect.l - 20 <= x && x <= powerRect.r + 20 && y >= powerRect.t - 30 && y <= powerRect.b + 20 ->
                if (anyTouch(Touch.POWER)) Touch.NONE else { ptrStartY[id] = y; power = 0.0; Touch.POWER }
            spinDist <= spinR * 1.2 -> {
                if (spinDist < spinR * 0.14) { spinX = 0.0; spinY = 0.0 } else setSpin(x, y)
                Touch.SPIN
            }
            x >= elevRect.l - 10 && x <= elevRect.r + 10 && y >= elevRect.t - 22 && y <= elevRect.b + 10 -> { setCueAngle(x); Touch.ELEV }
            x >= wheelRect.l - 16 && x <= wheelRect.r + 16 && y >= wheelRect.t && y <= wheelRect.b -> { ptrLastY[id] = y; Touch.WHEEL }
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
            tableArea.contains(x, y) -> { aimAt(x, y); Touch.AIM }
            else -> Touch.NONE
        }
        val m = ptrMode[id]
        if (m == Touch.POWER || m == Touch.WHEEL || m == Touch.AIM) clockStarted = true
    }

    private fun pocketUnder(x: Double, y: Double): Int {
        for ((i, pk) in Table.pockets.withIndex()) if (hypot(x - sx(pk.cx), y - sy(pk.cy)) < pk.capR * scale + 14) return i
        return -1
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

    fun pointerMove(id: Int, x: Double, y: Double) {
        if (phase != Phase.HUMAN || id !in ptrMode.indices) return
        when (ptrMode[id]) {
            Touch.POWER -> power = ((y - ptrStartY[id]) / (powerRect.h * 0.9)).coerceIn(0.0, 1.0)
            Touch.SPIN -> setSpin(x, y)
            Touch.ELEV -> setCueAngle(x)
            Touch.WHEEL -> {
                val d = y - ptrLastY[id]
                ptrLastY[id] = y
                wheelOffset += d
                aimAngle += d * wheelRadPerPx
            }
            Touch.MOVE_CUE -> {
                dragX = x; dragY = y
                dragInvalid = !match.placeCue(
                    tx(x).coerceIn(P.R, if (match.kitchenOnly) P.HEAD_X else P.L - P.R),
                    ty(y).coerceIn(P.R, P.W - P.R)
                )
            }
            Touch.AIM -> aimAt(x, y)
            Touch.PENDING -> if (hypot(x - ptrDownX[id], y - ptrDownY[id]) > 10) { ptrMode[id] = Touch.AIM; aimAt(x, y) }
            Touch.MOVE_BALL -> moveBall(ptrBall[id], x, y)
            else -> {}
        }
    }

    fun pointerUp(id: Int) {
        if (id !in ptrMode.indices) return
        val mode = ptrMode[id]
        ptrMode[id] = Touch.NONE
        if (phase != Phase.HUMAN) return
        if (mode == Touch.POWER) {
            if (power > 0.02 && match.callRequired() && match.calledPocket < 0) {
                power = 0.0
                callFlashUntil = now() + 1500
            } else if (power > 0.02) {
                lastPower = power
                shoot(aimAngle, speedFor(power), spinX * P.MAX_TIP, -spinY * P.MAX_TIP, toRad(elevDeg))
            } else power = 0.0
        }
        if (mode == Touch.MOVE_CUE) dragInvalid = false
        if (mode == Touch.PENDING) aimAt(ptrDownX[id], ptrDownY[id])
        if (mode == Touch.MOVE_BALL) { movingBall = -1; dragInvalid = false }
    }

    fun cancelTouches() { if (anyTouch(Touch.POWER)) power = 0.0; clearTouches(); movingBall = -1 }

    private fun aimAt(x: Double, y: Double) {
        val c = match.world.balls[0]
        val dx = x - sx(c.x); val dy = y - sy(c.y)
        if (hypot(dx, dy) > rpx * 0.8) aimAngle = atan2(dy, dx)
    }

    private fun setSpin(x: Double, y: Double) {
        var u = (x - spinCx) / (spinR * 0.92); var v = (y - spinCy) / (spinR * 0.92)
        val m = hypot(u, v)
        if (m > 1) { u /= m; v /= m }
        spinX = u; spinY = v
    }

    private fun setCueAngle(x: Double) {
        val f = ((x - elevRect.l) / elevRect.w).coerceIn(0.0, 1.0)
        val d = f * P.MAX_ELEVATION
        elevDeg = if (d < 3) 0.0 else d
    }

}
