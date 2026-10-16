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
    private val tutorialMode: Boolean = false   // the hands-on tutorial (a practice table with a guide)
) {
    private enum class Phase { RPS, HUMAN, CPU_THINK, CPU_ANIM, RUNNING, PAUSE, OVER }
    private enum class Rps { PICK, SHAKE, REVEAL }
    private enum class Touch { NONE, AIM, POWER, SPIN, ELEV, WHEEL, MOVE_CUE, BUTTON, PENDING, MOVE_BALL }

    /** Free-table practice: no rules, move any ball. (The tutorial runs on the practice table too.) */
    private val practice = practiceMode && mode != GameMode.TRICK

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

    private val names = when {
        vsCpu -> arrayOf("You", "CPU")
        trickPractice -> arrayOf("You", "You")
        else -> arrayOf("Player 1", "Player 2")
    }
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
    private val ptrLastT = DoubleArray(10)   // fine-aim wheel: time of the last move (ms)
    private val ptrVel = DoubleArray(10)     // fine-aim wheel: smoothed finger speed (px/s)
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
    private val potIds = IntArray(16)
    private val potY = DoubleArray(16)
    private val potV = DoubleArray(16)
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
        balls.plain = practice && !tutorialMode
        if (resume == null || !resumeGame(resume)) newGame(0)
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
        // Trick shots don't need rock, paper, scissors: who goes first doesn't matter.
        if (practice || trick) startTurn() else startRps(false)
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

    private fun settings() = GameSettings(mode, vsCpu, cpuLevel, shotClock, practiceMode, breaker, match.callMode)

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
        spinX = 0.0; spinY = 0.0; power = 0.0; elevDeg = 0.0
        gameOverTitle = ""; recordLine = ""
        message = when {
            practice -> "Practice · hold any ball to move it"
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
            Phase.HUMAN, Phase.PAUSE -> GameSave.write(match, settings())
            else -> turnSave ?: GameSave.write(match, settings())
        }
    }

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
        for (id in 1..15) { val b = match.world.balls[id]; b.onTable = false; b.stop() }
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
                        message = match.timeFoul().message
                        phase = Phase.PAUSE; pauseT = 1.0
                    }
                }
            }
            Phase.RUNNING -> {
                match.world.advance(dt)
                if (!match.world.anyMoving()) {
                    if (practice) {
                        tut?.onShotDone(!match.world.balls[Tutorial.TARGET_BALL].onTable)
                        message = if (!match.world.balls[0].onTable) { match.respawnCue(); "Cue ball respotted · hold it to move it" } else ""
                        phase = Phase.HUMAN; power = 0.0
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
        val c = match.world.balls[0]
        return when {
            powerRect.l - 20 <= x && x <= powerRect.r + 20 && y >= powerRect.t - 30 && y <= powerRect.b + 20 -> TutTarget.POWER
            x >= elevRect.l - 14 && x <= elevRect.r + 9 && y >= elevRect.t - 14 && y <= elevRect.b + 12 -> TutTarget.JUMP
            hypot(x - spinCx, y - spinCy) <= spinR * 1.2 -> TutTarget.SPIN
            x >= wheelRect.l - 6 && x <= wheelRect.r + 8 && y >= wheelRect.t - 6 && y <= wheelRect.b + 8 -> TutTarget.WHEEL
            match.ballInHand && hypot(x - sx(c.x), y - sy(c.y)) < max(rpx * 3, 28.0) -> TutTarget.CUE
            tableArea.contains(x, y) -> TutTarget.TABLE
            else -> TutTarget.NONE
        }
    }

    private fun targetOf(m: Touch) = when (m) {
        Touch.POWER -> TutTarget.POWER
        Touch.ELEV -> TutTarget.JUMP
        Touch.SPIN -> TutTarget.SPIN
        Touch.WHEEL -> TutTarget.WHEEL
        Touch.MOVE_CUE -> TutTarget.CUE
        Touch.AIM -> TutTarget.TABLE
        else -> TutTarget.NONE
    }

    /** Sets [tutShape] to the highlighted control's box ([tutRound]: it's a circle). Returns false for none. */
    private fun tutorialShape(t: Tutorial): Boolean {
        val pad = 6.0
        tutRound = false
        when (t.step.target) {
            TutTarget.TABLE -> tutShape.set(sx(-0.04), sy(-0.04), sx(P.L + 0.04), sy(P.W + 0.04))
            TutTarget.WHEEL -> tutShape.set(wheelRect.l - pad, wheelRect.t - 22, wheelRect.r + pad, wheelRect.b + pad)
            TutTarget.SPIN -> { val r = spinR + 5; tutShape.set(spinCx - r, spinCy - r, spinCx + r, spinCy + r); tutRound = true }
            TutTarget.JUMP -> tutShape.set(elevRect.l - 14, elevRect.t - 22, elevRect.r + 14, elevRect.b + 30)
            TutTarget.POWER -> tutShape.set(powerRect.l - 10, powerRect.t - 24, powerRect.r + 10, powerRect.b + 18)
            TutTarget.TRAY -> tutShape.set(trayRect.l - pad, trayRect.t - pad, trayRect.r + pad, trayRect.b + pad)
            TutTarget.CUE -> {
                val c = match.world.balls[0]
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
                t.finger(tutT, tutShape.l, tutShape.t, tutShape.r, tutShape.b, tutFinger)
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
        // Left: power bar, then the ball tray. Right: the cue-angle slider beside the spin ball,
        // and the fine-aim wheel filling the space below them.
        val leftW = max(w * 0.08, 58.0)
        val trayW = 28.0
        val stripW = 26.0
        val rightW = max(w * 0.15, 96.0) + stripW
        trayRect.set(leftW, hudH + 34, leftW + trayW, h - 18)
        tableArea.set(trayRect.r + 6, hudH, w - rightW, h)
        val rail = 0.115
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
        val stripCx = colL + 4 + stripW / 2
        val spinL = colL + stripW + 8; val spinRt = w - 8
        spinR = min((spinRt - spinL) * 0.42, (h - hudH) * 0.15)
        spinCx = (spinL + spinRt) / 2
        spinCy = hudH + 24 + spinR
        elevRect.set(stripCx - 3, spinCy - spinR, stripCx + 3, spinCy + spinR + 16)
        wheelRect.set(colL + 8, elevRect.b + 38, w - 10, h - 14)
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
        for (pk in Table.pockets) {
            val pr = pk.capR * scale
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
        if (Table.gap) return
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

        val cue = w.balls[0]
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
            val bx = if (b.id == 0) cueX else b.x; val by = if (b.id == 0) cueY else b.y
            if (b.z < 0.0) continue
            val h = (b.z / 0.12).coerceIn(0.0, 1.0)
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
        for (b in w.balls) if (b.onTable && b.z == 0.0) {
            val bx = if (b.id == 0) cueX else b.x; val by = if (b.id == 0) cueY else b.y
            balls.draw(ctx, b, sx(bx), sy(by))
        }
        // In the air: the height is exaggerated on screen (the distance is real) so a jump is easy to
        // see. The ball is drawn lifted toward the top of the screen and bigger, away from its shadow.
        for (b in w.balls) if (b.onTable && b.z > 0.0) {
            val lift = b.z * JUMP_LIFT * scale
            balls.draw(ctx, b, sx(b.x) - lift * 0.25, sy(b.y) - lift, 1 + b.z * JUMP_GROW)
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

        val showCue = (humanTurn && !anyTouch(Touch.MOVE_CUE) && movingBall < 0) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && cue.onTable) drawCueStick(ctx, sx(cueX), sy(cueY))

        if (bannerT > 0 && trick) drawBanner(ctx)
        drawHud(ctx)
        drawPowerBar(ctx)
        drawTray(ctx)
        drawSpin(ctx)
        drawElevation(ctx)
        drawWheel(ctx)
        if (phase == Phase.RPS) drawRps(ctx)
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
            else -> "LEVEL ${t.levelNo + 1} OF ${Trick.LEVELS.size} · ${lv.name.uppercase()}"
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
        // Jump shots: where the cue ball first comes down. It slides smoothly as you add power.
        if (!pr.landX.isNaN()) {
            ctx.setLineDash(noDash)
            ctx.strokeStyle = "rgba(255,213,74,0.94)"; ctx.lineWidth = 2.0
            ctx.circle(sx(pr.landX), sy(pr.landY), rpx); ctx.stroke()
            ctx.fillStyle = "rgba(255,213,74,0.94)"; ctx.circle(sx(pr.landX), sy(pr.landY), 2.2); ctx.fill()
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
        if (tutorialMode) { drawTutorialHud(ctx); return }
        if (practice) { drawPracticeHud(ctx); return }
        if (trickPractice) { drawTrickPracticeHud(ctx); return }
        drawPlayerCard(ctx, leftCard, 0)
        drawPlayerCard(ctx, rightCard, 1)
        val cardH = leftCard.h
        val midL = leftCard.r + 10; val midR = rightCard.l - 10
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.fillStyle = "#E8ECEF"; ctx.font = font(min(15.0, hudH * 0.26), false)
        val msg = when (phase) {
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
        ctx.fillText(ellipsize(ctx, msg, midR - midL), (midL + midR) / 2, 8 + cardH * 0.42)
        val subY = 8 + cardH * 0.82
        if (phase == Phase.CPU_THINK) {
            ctx.font = font(min(12.0, hudH * 0.2), false); ctx.fillStyle = "#F2C94C"
            ctx.fillText("CPU (level $cpuLevel) is thinking…", (midL + midR) / 2, subY)
        } else if (trick) {
            val t = match.trick
            ctx.font = font(min(12.0, hudH * 0.2), false); ctx.fillStyle = "#F2C94C"
            val lvl = if (t.suddenDeath) "Sudden death" else "Level ${t.levelNo + 1} of ${Trick.LEVELS.size}"
            ctx.fillText(ellipsize(ctx, "$lvl · ${Trick.LEVELS[t.level].name} · fewest shots wins", midR - midL), (midL + midR) / 2, subY)
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
        // Balls off the table sit in the tray on the left: tap one there to bring it back.
        ctx.textAlign = "left"; ctx.textBaseline = "alphabetic"
        ctx.font = font(min(11.0, rightCard.h * 0.22), false); ctx.fillStyle = "#9AA7B1"
        ctx.fillText(ellipsize(ctx, if (potN == 0) "All balls on the table" else "Tap a ball in the tray (left) to put it back", rightCard.w - 20),
            rightCard.l + 12, rightCard.t + rightCard.h * 0.42)
    }

    private fun drawPlayerCard(ctx: Ctx2D, r: Rect, player: Int) {
        val active = match.current == player && phase != Phase.OVER && phase != Phase.RPS
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
        ctx.circle(spinCx, spinCy, spinR * 0.5); ctx.stroke()
        ctx.fillStyle = "#D62828"
        ctx.circle(spinCx + spinX * spinR * 0.92, spinCy + spinY * spinR * 0.92, max(5.0, spinR * 0.13)); ctx.fill()
        ctx.font = font(10.5, false); ctx.fillStyle = "#9AA7B1"
        val v = if (spinY < -0.08) "follow " else if (spinY > 0.08) "draw " else ""
        val h = if (spinX > 0.08) "right" else if (spinX < -0.08) "left" else ""
        ctx.fillText((v + h).trim().ifEmpty { "center" }, spinCx, spinCy + spinR + 14)
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

    /** Cue angle: a vertical slider beside the spin ball. Slide up to raise the cue for a jump. */
    private fun drawElevation(ctx: Ctx2D) {
        val r = elevRect
        val cx = r.cx
        val raised = elevDeg > 0.5
        ctx.textAlign = "center"; ctx.textBaseline = "alphabetic"
        ctx.font = font(11.0, true)
        ctx.fillStyle = if (raised) "#F2C94C" else "#9AA7B1"
        ctx.fillText("JUMP", cx, spinCy - spinR - 8)
        ctx.fillStyle = "#1E252B"; ctx.roundRect(r, 3.0); ctx.fill()
        ctx.strokeStyle = "rgba(255,255,255,0.33)"; ctx.lineWidth = 1.0
        for (k in 1..3) {
            val ty = r.b - r.h * k * 15 / P.MAX_ELEVATION
            ctx.line(r.l - 5, ty, r.l - 2, ty)
        }
        val ky = r.b - r.h * (elevDeg / P.MAX_ELEVATION)
        ctx.fillStyle = "#F2C94C"; ctx.roundRect(r.l, ky, r.w, r.b - ky, 3.0); ctx.fill()
        ctx.fillStyle = "#FFF"; ctx.circle(cx, ky, 8.0); ctx.fill()
        ctx.font = font(10.5, false)
        ctx.fillStyle = if (raised) "#F2C94C" else "#9AA7B1"
        ctx.fillText(if (raised) "${elevDeg.toInt()}°" else "level", cx, r.b + 22)
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
                phase == Phase.HUMAN && x >= trayRect.l - 8 && x <= trayRect.r + 8 && y >= trayRect.t && y <= trayRect.b -> {
                    // Tap a ball in the tray to put it back on the table.
                    for (i in 0 until potN) if (abs(y - potY[i]) < max(potR * 1.1, 10.0)) { returnBall(potIds[i]); break }
                    ptrMode[id] = Touch.BUTTON
                    return
                }
            }
        }
        if (phase != Phase.HUMAN) return
        val c = match.world.balls[0]
        val spinDist = hypot(x - spinCx, y - spinCy)
        val grabbed = if (practice && !tutorialMode && tableArea.contains(x, y)) ballUnder(x, y) else -1
        ptrMode[id] = when {
            powerRect.l - 20 <= x && x <= powerRect.r + 20 && y >= powerRect.t - 30 && y <= powerRect.b + 20 ->
                if (anyTouch(Touch.POWER)) Touch.NONE else { ptrStartY[id] = y; power = 0.0; Touch.POWER }
            x >= elevRect.l - 14 && x <= elevRect.r + 9 && y >= elevRect.t - 14 && y <= elevRect.b + 12 -> { setCueAngle(y); Touch.ELEV }
            spinDist <= spinR * 1.2 -> {
                if (spinDist < spinR * 0.14) { spinX = 0.0; spinY = 0.0 } else setSpin(x, y)
                Touch.SPIN
            }
            x >= wheelRect.l - 6 && x <= wheelRect.r + 8 && y >= wheelRect.t - 6 && y <= wheelRect.b + 8 -> {
                ptrLastY[id] = y; ptrLastT[id] = now(); ptrVel[id] = 0.0; Touch.WHEEL
            }
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

    /**
     * Fine-aim acceleration: how much the cue turns per pixel of finger travel, from the finger's
     * speed. Slow drags are about 3x finer than the base setting; ~150 px/s is the base speed;
     * fast swipes turn up to 25x as far, so a flick swings the cue a long way.
     */
    private fun wheelGain(v: Double): Double = (0.3 + 0.7 * (v / 150.0).pow(1.7)).coerceAtMost(25.0)

    fun pointerMove(id: Int, x: Double, y: Double) {
        if (phase != Phase.HUMAN || id !in ptrMode.indices) return
        when (ptrMode[id]) {
            Touch.POWER -> power = ((y - ptrStartY[id]) / (powerRect.h * 0.9)).coerceIn(0.0, 1.0)
            Touch.SPIN -> setSpin(x, y)
            Touch.ELEV -> setCueAngle(y)
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
                if (trick) {
                    // Stay in the break area (or the nearest of the level's start zones).
                    val q = Trick.clampPlacement(Trick.LEVELS[match.trick.level], tx(x), ty(y))
                    dragInvalid = !match.placeCue(q[0], q[1])
                } else {
                    val maxX = if (match.kitchenOnly) P.HEAD_X else P.L - P.R
                    val c0 = match.world.balls[0]
                    val bx = c0.x; val by = c0.y
                    dragInvalid = !match.placeCue(tx(x).coerceIn(P.R, maxX), ty(y).coerceIn(P.R, P.W - P.R))
                    tut?.onCueMoved(hypot(c0.x - bx, c0.y - by))
                }
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
        tut?.onRelease(targetOf(mode), hypot(spinX, spinY), elevDeg)
        if (mode == Touch.POWER) {
            if (power > 0.02 && match.callRequired() && match.calledPocket < 0) {
                power = 0.0
                callFlashUntil = now() + 1500
            } else if (power > 0.02) {
                lastPower = power
                tut?.onShot()
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
        if (hypot(dx, dy) > rpx * 0.8) {
            val a = atan2(dy, dx)
            var d = a - aimAngle
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            tut?.onAim(d)
            aimAngle = a
        }
    }

    private fun setSpin(x: Double, y: Double) {
        var u = (x - spinCx) / (spinR * 0.92); var v = (y - spinCy) / (spinR * 0.92)
        val m = hypot(u, v)
        if (m > 1) { u /= m; v /= m }
        spinX = u; spinY = v
        tut?.onSpin(u, v)
    }

    private fun setCueAngle(y: Double) {
        val f = ((elevRect.b - y) / elevRect.h).coerceIn(0.0, 1.0)
        val d = f * P.MAX_ELEVATION
        elevDeg = if (d < 3) 0.0 else d
        tut?.onElevation(elevDeg)
    }

    private companion object {
        const val RPS_BEAT = 0.42
        /** On screen, a jumping ball is drawn this many times higher than it really is. */
        const val JUMP_LIFT = 1.6
        /** ...and this much bigger per metre of height (closer to the viewer). */
        const val JUMP_GROW = 8.0
        val GESTURES = arrayOf("\u270A", "\u270B", "\u270C\uFE0F")   // fist, flat hand, peace sign
        val GESTURE_NAMES = arrayOf("Rock", "Paper", "Scissors")
        val SHAKE_WORDS = arrayOf("Rock…", "Paper…", "Scissors…")
    }
}
