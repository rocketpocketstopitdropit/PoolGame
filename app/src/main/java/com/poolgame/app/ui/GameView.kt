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
import android.view.MotionEvent
import android.view.View
import com.poolgame.app.core.Ai
import com.poolgame.app.core.GameMode
import com.poolgame.app.core.Group
import com.poolgame.app.core.Match
import com.poolgame.app.core.P
import com.poolgame.app.core.Plan
import com.poolgame.app.core.Table
import com.poolgame.app.core.WorldListener
import kotlin.math.abs
import kotlin.math.atan2
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
    private val host: Host
) : View(context), Choreographer.FrameCallback {

    interface Host {
        fun onMenuRequested()
        fun onExitToMenu()
    }

    private enum class Phase { HUMAN, CPU_THINK, CPU_ANIM, RUNNING, PAUSE, OVER }
    private enum class Touch { NONE, AIM, POWER, SPIN, WHEEL, MOVE_CUE, BUTTON }

    private val names = if (vsCpu) arrayOf("You", "CPU") else arrayOf("Player 1", "Player 2")
    private val rng = Random(System.nanoTime())
    private var match = Match(mode, names)
    private var breaker = 0
    private var phase = Phase.HUMAN
    private var generation = 0

    // Shot controls
    private var aimAngle = 0.0
    private var power = 0f            // 0..1
    private var spinX = 0f            // -1..1 (right english > 0)
    private var spinY = 0f            // -1..1 (screen down = draw)
    private var touch = Touch.NONE
    private var touchStartY = 0f
    private var lastTouchY = 0f
    private var wheelOffset = 0f
    private var dragInvalid = false
    private var dragX = 0f; private var dragY = 0f

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

    private class Sink(val id: Int, val x: Double, val y: Double, var age: Float)
    private val sinks = ArrayList<Sink>()

    private val balls = BallRenderer()
    private val sfx = Sfx(soundOn)

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
    private var background: Bitmap? = null

    // Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private var dash: DashPathEffect? = null
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val hit = DoubleArray(4)

    init {
        isFocusable = true
        match.world.trackOrientation = true
        match.world.listener = makeListener()
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
        sinks.clear()
        spinX = 0f; spinY = 0f; power = 0f
        gameOverTitle = ""
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
            return
        }
        power = 0f
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
            }.apply { isDaemon = true; start() }
        } else {
            phase = Phase.HUMAN
            spinX = 0f; spinY = 0f
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
            // Should not happen, but never get stuck.
            cpuPlan = Plan(p.angle, p.speed, p.side, p.vert, p.kind)
        }
        phase = Phase.CPU_ANIM
    }

    private fun speedFor(pw: Float): Double = 0.25 + (P.MAX_CUE_SPEED - 0.25) * pw.toDouble().pow(1.6)
    private fun powerFor(speed: Double): Float = ((speed - 0.25) / (P.MAX_CUE_SPEED - 0.25)).coerceIn(0.0, 1.0).pow(1 / 1.6).toFloat()

    private fun shoot(angle: Double, speed: Double, side: Double, vert: Double) {
        sfx.strike(speed)
        match.shoot(angle, speed, side, vert)
        phase = Phase.RUNNING
        touch = Touch.NONE
    }

    private fun update(dt: Double) {
        for (s in sinks) s.age += dt.toFloat()
        sinks.removeAll { it.age > 0.35f }
        when (phase) {
            Phase.RUNNING -> {
                match.world.advance(dt)
                if (!match.world.anyMoving()) {
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
                }
                if (animT >= tAim + aimDur) {
                    val k = ((animT - tAim - aimDur) / pullDur).coerceIn(0.0, 1.0)
                    power = powerFor(p.speed) * k.toFloat()
                }
                if (animT >= tAim + aimDur + pullDur + 0.12) {
                    cpuPlan = null
                    shoot(p.angle, p.speed, p.side, p.vert)
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

    fun setSound(on: Boolean) { sfx.enabled = on }

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
        spinR = min(rightW * 0.34f, (h - hudH) * 0.17f)
        spinCx = w - rightW / 2
        spinCy = hudH + 26 * dp + spinR
        val ww = min(rightW * 0.42f, 52 * dp)
        wheelRect.set(spinCx - ww / 2, spinCy + spinR + 40 * dp, spinCx + ww / 2, h - 18 * dp)
        menuBtn.set(8 * dp, 8 * dp, 8 * dp + 44 * dp, 8 * dp + 36 * dp)

        background?.recycle()
        background = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawBackground(Canvas(it), w, h, rail) }
    }

    private fun drawBackground(c: Canvas, w: Int, h: Int, rail: Double) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1A2026.toInt(), 0xFF0D1013.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // Wooden frame
        val outer = RectF(sx(-rail), sy(-rail), sx(P.L + rail), sy(P.W + rail))
        val corner = (0.06 * scale).toFloat()
        p.color = 0x66000000
        c.drawRoundRect(RectF(outer.left + 3 * dp, outer.top + 6 * dp, outer.right + 3 * dp, outer.bottom + 8 * dp), corner, corner, p)
        p.shader = LinearGradient(outer.left, outer.top, outer.right, outer.bottom,
            intArrayOf(0xFF6B3A1C.toInt(), 0xFF4A2610.toInt(), 0xFF5E3218.toInt(), 0xFF3C1E0C.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(outer, corner, corner, p)
        p.shader = null
        // wood grain
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

        // Cushions
        val cush = 0.045
        p.color = 0xFF14583A.toInt()
        c.drawRect(sx(-cush), sy(-cush), sx(P.L + cush), sy(P.W + cush), p)
        // Cloth
        p.shader = RadialGradient(sx(P.L / 2), sy(P.W / 2), (P.L * 0.62 * scale).toFloat(),
            intArrayOf(0xFF22935A.toInt(), 0xFF1C7E4C.toInt(), 0xFF145F38.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(sx(0.0), sy(0.0), sx(P.L), sy(P.W), p)
        p.shader = null
        // Cushion inner shadow
        p.shader = LinearGradient(0f, sy(0.0), 0f, sy(0.025), 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(sx(0.0), sy(0.0), sx(P.L), sy(0.025), p)
        p.shader = LinearGradient(sx(0.0), 0f, sx(0.025), 0f, 0x44000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(sx(0.0), sy(0.0), sx(0.025), sy(P.W), p)
        p.shader = null

        // Pockets
        for (pk in Table.pockets) {
            val r = ((pk.capR + 0.004) * scale).toFloat()
            p.color = 0xFF2A1A10.toInt()
            c.drawCircle(sx(pk.cx), sy(pk.cy), r + 3 * dp, p)
            p.shader = RadialGradient(sx(pk.cx), sy(pk.cy), r, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF1B1B1B.toInt()),
                floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(sx(pk.cx), sy(pk.cy), r, p)
            p.shader = null
        }
        // Cushion noses
        val nose = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = 0xFF0E4A2D.toInt(); strokeCap = Paint.Cap.ROUND }
        for (sg in Table.segs) c.drawLine(sx(sg.ax), sy(sg.ay), sx(sg.bx), sy(sg.by), nose)

        // Diamonds
        p.color = 0xFFEDE3C8.toInt()
        val dOff = rail * 0.55
        val dr = 0.012 * scale
        for (i in 1..7) if (i != 4) {
            val x = P.L * i / 8
            diamond(c, sx(x), sy(-dOff), dr.toFloat(), p)
            diamond(c, sx(x), sy(P.W + dOff), dr.toFloat(), p)
        }
        for (i in 1..3) {
            val y = P.W * i / 4
            diamond(c, sx(-dOff), sy(y), dr.toFloat(), p)
            diamond(c, sx(P.L + dOff), sy(y), dr.toFloat(), p)
        }
        // Head string and foot spot
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

        if (humanTurn && touch != Touch.MOVE_CUE) drawGuide(canvas)

        // Shadows, then balls
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
            balls.drawShadow(canvas, sx(bx), sy(by))
        }
        for (s in sinks) {
            val k = 1f - s.age / 0.35f
            paint.alpha = (255 * k).toInt()
            balls.draw(canvas, w.balls[s.id], sx(s.x), sy(s.y), 0.55f + 0.45f * k, paint)
            paint.alpha = 255
        }
        for (b in w.balls) if (b.onTable) {
            val bx = if (b.id == 0) cueX else b.x; val by = if (b.id == 0) cueY else b.y
            balls.draw(canvas, b, sx(bx), sy(by))
        }

        if (humanTurn && match.ballInHand) {
            val pulse = (0.5 + 0.5 * sin(System.nanoTime() / 2.5e8)).toFloat()
            guide.pathEffect = null
            guide.strokeWidth = 2 * dp
            guide.color = Color.argb((120 + 120 * pulse).toInt(), 255, 214, 64)
            canvas.drawCircle(sx(cue.x), sy(cue.y), rpx * (1.5f + 0.25f * pulse), guide)
            if (touch == Touch.MOVE_CUE && dragInvalid) {
                guide.color = 0xCCFF4040.toInt()
                canvas.drawCircle(dragX, dragY, rpx, guide)
            }
        }

        val showCue = (humanTurn && touch != Touch.MOVE_CUE) ||
            (phase == Phase.CPU_ANIM && (plan == null || !plan.hasPlacement || animT >= 0.45))
        if (showCue && cue.onTable) drawCueStick(canvas, sx(cueX), sy(cueY))

        drawHud(canvas)
        drawPowerBar(canvas)
        drawSpin(canvas)
        drawWheel(canvas)
        if (phase == Phase.OVER) drawGameOver(canvas)
    }

    private fun drawGuide(canvas: Canvas) {
        val w = match.world
        val c = w.balls[0]
        if (!c.onTable) return
        w.castCue(aimAngle, hit)
        val cx = sx(c.x); val cy = sy(c.y)
        guide.strokeWidth = 1.6f * dp
        guide.pathEffect = dash ?: DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f).also { dash = it }
        guide.color = 0xB0FFFFFF.toInt()
        val dx = cos(aimAngle); val dy = sin(aimAngle)
        when (hit[0].toInt()) {
            1 -> {
                val gx = hit[1]; val gy = hit[2]; val id = hit[3].toInt()
                val legal = id in match.legalTargets()
                canvas.drawLine(cx, cy, sx(gx), sy(gy), guide)
                guide.pathEffect = null
                guide.color = if (legal) 0xE0FFFFFF.toInt() else 0xE0FF5050.toInt()
                canvas.drawCircle(sx(gx), sy(gy), rpx, guide)
                val ob = w.balls[id]
                val nx = ob.x - gx; val ny = ob.y - gy
                val nl = hypot(nx, ny)
                val ux = nx / nl; val uy = ny / nl
                val full = (dx * ux + dy * uy).coerceIn(0.0, 1.0)
                val len = 0.12 + 0.45 * full
                guide.strokeWidth = 2.2f * dp
                canvas.drawLine(sx(ob.x + ux * P.R), sy(ob.y + uy * P.R), sx(ob.x + ux * (P.R + len)), sy(ob.y + uy * (P.R + len)), guide)
                // Cue ball tangent line (stun line)
                var txv = dx - full * ux; var tyv = dy - full * uy
                val tl = hypot(txv, tyv)
                if (tl > 1e-3) {
                    txv /= tl; tyv /= tl
                    guide.color = 0x80FFFFFF.toInt()
                    guide.strokeWidth = 1.4f * dp
                    val l2 = 0.08 + 0.25 * (1 - full)
                    canvas.drawLine(sx(gx), sy(gy), sx(gx + txv * l2), sy(gy + tyv * l2), guide)
                }
                if (!legal) {
                    guide.color = 0xE0FF5050.toInt(); guide.strokeWidth = 2.4f * dp
                    val r = rpx * 0.55f
                    canvas.drawLine(sx(gx) - r, sy(gy) - r, sx(gx) + r, sy(gy) + r, guide)
                    canvas.drawLine(sx(gx) - r, sy(gy) + r, sx(gx) + r, sy(gy) - r, guide)
                }
            }
            2 -> {
                val hx = hit[1]; val hy = hit[2]
                canvas.drawLine(cx, cy, sx(hx), sy(hy), guide)
                var rx = dx; var ry = dy
                if (hx <= P.R + 1e-6 || hx >= P.L - P.R - 1e-6) rx = -rx
                if (hy <= P.R + 1e-6 || hy >= P.W - P.R - 1e-6) ry = -ry
                guide.color = 0x70FFFFFF
                canvas.drawLine(sx(hx), sy(hy), sx(hx + rx * 0.3), sy(hy + ry * 0.3), guide)
                guide.pathEffect = null
                guide.color = 0xB0FFFFFF.toInt()
                canvas.drawCircle(sx(hx), sy(hy), rpx, guide)
            }
        }
        guide.pathEffect = null
    }

    private fun drawCueStick(canvas: Canvas, cx: Float, cy: Float) {
        val s = scale
        val gap = (P.R + 0.012 + power * 0.22) * s
        val len = 1.45 * s
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(Math.toDegrees(aimAngle).toFloat())
        val x0 = (-gap).toFloat()
        val tipW = (0.0065 * s).toFloat()
        val buttW = (0.015 * s).toFloat()
        fun halfW(x: Double) = tipW + (buttW - tipW) * ((-x - gap) / len).toFloat()
        fun seg(fromM: Double, toM: Double, color: Int, color2: Int = color) {
            val a = -gap - fromM * s; val b = -gap - toM * s
            path.reset()
            path.moveTo(a.toFloat(), -halfW(a)); path.lineTo(b.toFloat(), -halfW(b))
            path.lineTo(b.toFloat(), halfW(b)); path.lineTo(a.toFloat(), halfW(a)); path.close()
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, -buttW, 0f, buttW, intArrayOf(color2, color, color2), null, Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
            paint.shader = null
        }
        // shadow
        paint.style = Paint.Style.FILL
        paint.color = 0x55000000
        canvas.save(); canvas.translate(3 * dp, 5 * dp)
        path.reset()
        path.moveTo(x0, -tipW); path.lineTo((x0 - len).toFloat(), -buttW); path.lineTo((x0 - len).toFloat(), buttW); path.lineTo(x0, tipW); path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
        seg(0.0, 0.008, 0xFF3D7BC9.toInt(), 0xFF24518A.toInt())        // chalked tip
        seg(0.008, 0.03, 0xFFF3EEDF.toInt(), 0xFFBFB8A4.toInt())       // ferrule
        seg(0.03, 0.72, 0xFFE9CF9E.toInt(), 0xFFB08B54.toInt())        // maple shaft
        seg(0.72, 0.74, 0xFF2B2B2B.toInt(), 0xFF101010.toInt())        // joint
        seg(0.74, 0.98, 0xFF5A2A14.toInt(), 0xFF2E1408.toInt())        // forearm
        seg(0.98, 1.22, 0xFF1C1C1C.toInt(), 0xFF080808.toInt())        // wrap
        seg(1.22, 1.43, 0xFF5A2A14.toInt(), 0xFF2E1408.toInt())        // butt
        seg(1.43, 1.45, 0xFF111111.toInt())                            // bumper
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas) {
        val w = width.toFloat()
        // Menu button
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF232B32.toInt()
        canvas.drawRoundRect(menuBtn, 8 * dp, 8 * dp, paint)
        paint.color = 0xFFCED6DD.toInt()
        val mx = menuBtn.centerX(); val my = menuBtn.centerY()
        for (i in -1..1) canvas.drawRoundRect(mx - 9 * dp, my + i * 6 * dp - 1.2f * dp, mx + 9 * dp, my + i * 6 * dp + 1.2f * dp, dp, dp, paint)

        val cardW = min(w * 0.27f, 260 * dp)
        val cardH = hudH - 16 * dp
        val left = RectF(menuBtn.right + 10 * dp, 8 * dp, menuBtn.right + 10 * dp + cardW, 8 * dp + cardH)
        val right = RectF(w - 8 * dp - cardW, 8 * dp, w - 8 * dp, 8 * dp + cardH)
        drawPlayerCard(canvas, left, 0)
        drawPlayerCard(canvas, right, 1)

        // Message in the middle
        val midL = left.right + 10 * dp; val midR = right.left - 10 * dp
        text.typeface = regular
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFE8ECEF.toInt()
        text.textSize = min(15 * dp, hudH * 0.26f)
        var msg = when (phase) {
            Phase.CPU_THINK, Phase.CPU_ANIM -> if (message.isNotEmpty()) message else "CPU is thinking…"
            Phase.HUMAN -> when {
                match.ballInHand && match.isBreak -> "${names[match.current]}: place the cue ball behind the line, then break"
                match.ballInHand -> "${names[match.current]}: ball in hand — drag the cue ball"
                else -> message
            }
            else -> message
        }
        msg = ellipsize(msg, midR - midL)
        canvas.drawText(msg, (midL + midR) / 2, 8 * dp + cardH * 0.42f, text)
        // 9-ball rail of remaining balls, or thinking indicator
        val subY = 8 * dp + cardH * 0.82f
        if (phase == Phase.CPU_THINK) {
            text.textSize = min(12 * dp, hudH * 0.2f); text.color = 0xFFF2C94C.toInt()
            val dots = ".".repeat(((System.nanoTime() / 300_000_000) % 4).toInt())
            canvas.drawText("CPU (level $cpuLevel) is thinking$dots", (midL + midR) / 2, subY, text)
        } else if (mode == GameMode.NINE_BALL) {
            val r = min(hudH * 0.13f, 9 * dp)
            val ids = (1..9).toList()
            val total = ids.size * r * 2.4f
            var x = (midL + midR) / 2 - total / 2 + r
            for (id in ids) {
                balls.drawIcon(canvas, id, x, subY - r * 0.4f, r, !match.world.balls[id].onTable, paint)
                x += r * 2.4f
            }
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
        text.typeface = regular
        text.textSize = min(11 * dp, r.height() * 0.22f)
        text.color = 0xFF9AA7B1.toInt()
        val iconR = min(r.height() * 0.16f, 9 * dp)
        val iy = r.top + r.height() * 0.72f
        if (mode == GameMode.EIGHT_BALL) {
            val g = match.groups[player]
            if (g == Group.OPEN) {
                canvas.drawText(if (match.isBreak) "" else "Open table", r.left + 12 * dp, iy + iconR * 0.4f, text)
            } else {
                val ids = if (g == Group.SOLIDS) (1..7) else (9..15)
                var x = r.left + 12 * dp + iconR
                for (id in ids) {
                    balls.drawIcon(canvas, id, x, iy, iconR, !match.world.balls[id].onTable, paint)
                    x += iconR * 2.3f
                }
                if (match.onEight(player)) balls.drawIcon(canvas, 8, x + iconR * 0.4f, iy, iconR, false, paint)
            }
        } else {
            canvas.drawText(if (match.current == player && !match.isBreak) "Shoot the ${match.lowestBall()}" else "", r.left + 12 * dp, iy + iconR * 0.4f, text)
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
            val fillB = r.top + (r.height()) * power
            canvas.save()
            canvas.clipRect(r.left, r.top, r.right, fillB)
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom,
                intArrayOf(0xFF3CCB7F.toInt(), 0xFFF2C94C.toInt(), 0xFFEB5757.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, r.width() / 2, r.width() / 2, paint)
            paint.shader = null
            canvas.restore()
            // handle
            paint.color = Color.WHITE
            canvas.drawRoundRect(r.left - 4 * dp, fillB - 3 * dp, r.right + 4 * dp, fillB + 3 * dp, 3 * dp, 3 * dp, paint)
            text.color = Color.WHITE; text.textSize = 12 * dp
            canvas.drawText("${(power * 100).toInt()}%", r.centerX(), r.bottom + 14 * dp, text)
        } else if (phase == Phase.HUMAN) {
            // hint chevrons
            paint.color = 0x55FFFFFF
            for (i in 0..2) {
                val y = r.top + 20 * dp + i * 12 * dp
                path.reset()
                path.moveTo(r.centerX() - 7 * dp, y); path.lineTo(r.centerX(), y + 6 * dp); path.lineTo(r.centerX() + 7 * dp, y)
                guide.pathEffect = null; guide.strokeWidth = 2 * dp; guide.color = 0x66FFFFFF
                canvas.drawPath(path, guide)
            }
        }
    }

    private fun drawSpin(canvas: Canvas) {
        text.textAlign = Paint.Align.CENTER; text.typeface = bold
        text.textSize = 11 * dp; text.color = 0xFF9AA7B1.toInt()
        canvas.drawText("SPIN", spinCx, spinCy - spinR - 10 * dp, text)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(spinCx - spinR * 0.3f, spinCy - spinR * 0.35f, spinR * 1.3f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFE9E4D4.toInt(), 0xFFAFA997.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(spinCx, spinCy, spinR, paint)
        paint.shader = null
        guide.pathEffect = null; guide.strokeWidth = 1 * dp; guide.color = 0x40000000
        canvas.drawLine(spinCx - spinR, spinCy, spinCx + spinR, spinCy, guide)
        canvas.drawLine(spinCx, spinCy - spinR, spinCx, spinCy + spinR, guide)
        canvas.drawCircle(spinCx, spinCy, spinR * 0.5f, guide)
        paint.color = 0xFFD62828.toInt()
        val dx = spinCx + spinX * spinR * 0.92f; val dy = spinCy + spinY * spinR * 0.92f
        canvas.drawCircle(dx, dy, max(5 * dp, spinR * 0.13f), paint)
        text.typeface = regular
        text.textSize = 10.5f * dp; text.color = 0xFF9AA7B1.toInt()
        val label = when {
            abs(spinX) < 0.08f && abs(spinY) < 0.08f -> "center"
            else -> buildString {
                if (spinY < -0.08f) append("follow ") else if (spinY > 0.08f) append("draw ")
                if (spinX > 0.08f) append("right") else if (spinX < -0.08f) append("left")
            }.trim()
        }
        canvas.drawText(label, spinCx, spinCy + spinR + 14 * dp, text)
    }

    private fun drawWheel(canvas: Canvas) {
        val r = wheelRect
        if (r.height() < 40 * dp) return
        text.textAlign = Paint.Align.CENTER; text.typeface = bold
        text.textSize = 11 * dp; text.color = 0xFF9AA7B1.toInt()
        canvas.drawText("FINE AIM", r.centerX(), r.top - 8 * dp, text)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(r.left, 0f, r.right, 0f,
            intArrayOf(0xFF15191D.toInt(), 0xFF39434C.toInt(), 0xFF15191D.toInt()), null, Shader.TileMode.CLAMP)
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
        val pw = min(w * 0.5f, 420 * dp); val ph = min(h * 0.6f, 230 * dp)
        overPanel.set(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        paint.color = 0xFF1E252B.toInt()
        canvas.drawRoundRect(overPanel, 16 * dp, 16 * dp, paint)
        guide.color = 0xFFF2C94C.toInt(); guide.strokeWidth = 2 * dp; guide.pathEffect = null
        canvas.drawRoundRect(overPanel, 16 * dp, 16 * dp, guide)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold; text.textSize = 30 * dp; text.color = Color.WHITE
        canvas.drawText(gameOverTitle, overPanel.centerX(), overPanel.top + ph * 0.3f, text)
        text.typeface = regular; text.textSize = 13 * dp; text.color = 0xFFB8C2CA.toInt()
        val detail = match.message.substringAfter("(", "").removeSuffix(")")
        if (detail.isNotEmpty()) canvas.drawText(ellipsize(detail, pw - 24 * dp), overPanel.centerX(), overPanel.top + ph * 0.45f, text)
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
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(x, y)
            MotionEvent.ACTION_MOVE -> onMove(x, y)
            MotionEvent.ACTION_UP -> onUp(x, y)
            MotionEvent.ACTION_CANCEL -> { if (touch == Touch.POWER) power = 0f; touch = Touch.NONE }
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        if (menuBtn.contains(x, y) && phase != Phase.OVER) { touch = Touch.BUTTON; host.onMenuRequested(); return }
        if (phase == Phase.OVER) {
            if (rematchBtn.contains(x, y)) newGame(1 - breaker)
            else if (menuExitBtn.contains(x, y)) host.onExitToMenu()
            touch = Touch.BUTTON
            return
        }
        if (phase != Phase.HUMAN) { touch = Touch.NONE; return }
        val c = match.world.balls[0]
        val spinDist = hypot(x - spinCx, y - spinCy)
        when {
            powerRect.left - 20 * dp <= x && x <= powerRect.right + 20 * dp && y >= powerRect.top - 30 * dp && y <= powerRect.bottom + 20 * dp -> {
                touch = Touch.POWER; touchStartY = y; power = 0f
            }
            spinDist <= spinR * 1.25f -> {
                touch = Touch.SPIN
                if (spinDist < spinR * 0.14f) { spinX = 0f; spinY = 0f } else setSpin(x, y)
            }
            x >= wheelRect.left - 16 * dp && x <= wheelRect.right + 16 * dp && y >= wheelRect.top && y <= wheelRect.bottom -> {
                touch = Touch.WHEEL; lastTouchY = y
            }
            match.ballInHand && hypot(x - sx(c.x), y - sy(c.y)) < max(rpx * 3f, 28 * dp) -> {
                touch = Touch.MOVE_CUE; dragInvalid = false
            }
            tableArea.contains(x, y) -> { touch = Touch.AIM; aimAt(x, y) }
            else -> touch = Touch.NONE
        }
    }

    private fun onMove(x: Float, y: Float) {
        if (phase != Phase.HUMAN) return
        when (touch) {
            Touch.POWER -> power = ((y - touchStartY) / (powerRect.height() * 0.9f)).coerceIn(0f, 1f)
            Touch.SPIN -> setSpin(x, y)
            Touch.WHEEL -> {
                val d = y - lastTouchY
                lastTouchY = y
                wheelOffset += d
                aimAngle += d / dp * 0.0009   // ~0.05 degrees per dp
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
            else -> {}
        }
    }

    private fun onUp(x: Float, y: Float) {
        if (phase == Phase.HUMAN) {
            if (touch == Touch.POWER) {
                if (power > 0.02f) {
                    shoot(aimAngle, speedFor(power), (spinX * P.MAX_TIP).toDouble(), (-spinY * P.MAX_TIP).toDouble())
                } else power = 0f
            }
            if (touch == Touch.MOVE_CUE) dragInvalid = false
        }
        touch = Touch.NONE
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
}
