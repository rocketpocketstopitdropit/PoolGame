package com.poolgame.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.poolgame.app.core.ControlsGuide
import kotlin.math.hypot
import kotlin.math.min

/**
 * CONTROLS: a labelled map of the game screen, then what each numbered control does and how to use
 * it. Tap a number on the map (or an entry in the list) to outline that control.
 */
class ControlsActivity : Activity() {

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    private lateinit var map: ScreenMapView
    private lateinit var scroll: ScrollView
    private val itemViews = ArrayList<LinearLayout>()

    private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-condensed", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF0F3D27.toInt(), 0xFF0B2419.toInt(), 0xFF0A0F12.toInt())
        )
        scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(24f))
        }
        scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)
        val maxW = min(dpi(820f), resources.displayMetrics.widthPixels - dpi(32f))

        // Header: back, title, tutorial.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("‹ Back", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF39434C.toInt(), dp(12f))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dpi(92f), dpi(42f)))
        header.addView(label("CONTROLS", 26f, Color.WHITE, true).apply {
            letterSpacing = 0.2f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("▶ Tutorial", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF2F8F5B.toInt(), dp(12f))
            setOnClickListener {
                startActivity(Intent(this@ControlsActivity, GameActivity::class.java).apply {
                    putExtra(GameActivity.EXTRA_TUTORIAL, true)
                })
            }
        }, LinearLayout.LayoutParams(dpi(120f), dpi(42f)))
        col.addView(header, LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(label("The game screen, held sideways. Tap a number to outline that control and jump to how it works.",
            13f, 0xFF9AA7B1.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, dpi(10f), 0, dpi(10f)); setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT))

        map = ScreenMapView(this) { i -> select(i, scrollTo = true) }
        // As wide as fits, but small enough to be seen whole under the header.
        val fitH = resources.displayMetrics.heightPixels - dpi(120f)
        val mapW = min(maxW, (fitH * ControlsGuide.MAP_W / ControlsGuide.MAP_H).toInt())
        val mapH = (mapW * ControlsGuide.MAP_H / ControlsGuide.MAP_W).toInt()
        col.addView(map, LinearLayout.LayoutParams(mapW, mapH))

        // The numbered list.
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(14f), 0, 0) }
        for ((i, item) in ControlsGuide.ITEMS.withIndex()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(0xE6151B20.toInt(), dp(14f), dpi(1f), 0x33FFFFFF)
                setPadding(dpi(14f), dpi(12f), dpi(16f), dpi(12f))
                setOnClickListener { select(i, scrollTo = false) }
            }
            box.addView(label(item.n.toString(), 16f, 0xFF1A1A1A.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFFF2C94C.toInt(), dp(15f))
            }, LinearLayout.LayoutParams(dpi(30f), dpi(30f)))
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12f), 0, 0, 0) }
            text.addView(label(item.name, 17f, Color.WHITE, true))
            text.addView(label(item.where, 12f, 0xFFF2C94C.toInt()).apply { setPadding(0, 0, 0, dpi(6f)) })
            for (line in item.how) text.addView(label("•  $line", 14f, 0xFFDDE3E8.toInt()).apply {
                setLineSpacing(0f, 1.2f); setPadding(0, dpi(2f), 0, dpi(2f))
            })
            box.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            itemViews += box
            list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        }
        for (sec in ControlsGuide.SECTIONS) {
            list.addView(label(sec.title.uppercase(), 11.5f, 0xFF7F8C96.toInt(), true).apply {
                letterSpacing = 0.12f; setPadding(dpi(4f), dpi(18f), 0, dpi(6f))
            })
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xE6151B20.toInt(), dp(14f), dpi(1f), 0x33FFFFFF)
                setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
            }
            for (line in sec.lines) box.addView(label("•  $line", 14f, 0xFFDDE3E8.toInt()).apply {
                setLineSpacing(0f, 1.2f); setPadding(0, dpi(3f), 0, dpi(3f))
            })
            list.addView(box)
        }
        col.addView(list, LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun select(i: Int, scrollTo: Boolean) {
        map.selected = if (map.selected == i && !scrollTo) -1 else i
        map.invalidate()
        for ((k, v) in itemViews.withIndex()) {
            v.background = rounded(if (k == map.selected) 0xFF233A2E.toInt() else 0xE6151B20.toInt(), dp(14f), dpi(if (k == map.selected) 2f else 1f),
                if (k == map.selected) 0xFFF2C94C.toInt() else 0x33FFFFFF)
        }
        if (scrollTo) itemViews[i].let { v -> scroll.post { scroll.smoothScrollTo(0, (v.parent as View).top + v.top - dpi(60f)) } }
        else scroll.post { scroll.smoothScrollTo(0, 0) }
    }
}

/** The game screen drawn as a labelled diagram, using the same layout as the real screen. */
@SuppressLint("ViewConstructor")
class ScreenMapView(context: Context, private val onPick: (Int) -> Unit) : View(context) {

    var selected = -1
    private val layout = ControlsGuide.layout(ControlsGuide.MAP_W, ControlsGuide.MAP_H)
    private val badges = ControlsGuide.badges(layout)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private var k = 1f       // view pixels per map unit
    private val r = RectF()

    private fun X(v: Double) = (v * k).toFloat()
    private fun box(b: ControlsGuide.Box) = r.apply { set(X(b.l), X(b.t), X(b.r), X(b.b)) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            var best = -1; var bestD = Float.MAX_VALUE
            for ((i, b) in badges.withIndex()) {
                val d = hypot(e.x - X(b.first), e.y - X(b.second))
                if (d < bestD) { best = i; bestD = d }
            }
            if (best >= 0 && bestD < 40 * resources.displayMetrics.density) onPick(best)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        k = (width / ControlsGuide.MAP_W).toFloat()
        val L = layout
        val d = k   // one map unit (dp on a real phone)
        // Phone body and screen.
        p.style = Paint.Style.FILL; p.shader = null
        p.color = 0xFF05080A.toInt()
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 22 * d, 22 * d, p)
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF1A2026.toInt(), 0xFF0D1013.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(2 * d, 2 * d, width - 2 * d, height - 2 * d, 20 * d, 20 * d, p)
        p.shader = null

        // Table: wood, cloth, pockets, a rack, the cue ball, cue and aim line.
        p.color = 0xFF5E3218.toInt()
        c.drawRoundRect(box(L.table), 10 * d, 10 * d, p)
        p.shader = RadialGradient(X(L.cloth.cx), X(L.cloth.cy), X(L.cloth.r - L.cloth.l) * 0.6f,
            0xFF22935A.toInt(), 0xFF145F38.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(box(L.cloth), p)
        p.shader = null
        val cl = L.cloth
        val pr = 8 * d
        p.color = Color.BLACK
        for ((px, py) in listOf(cl.l to cl.t, cl.cx to cl.t - 3, cl.r to cl.t, cl.l to cl.b, cl.cx to cl.b + 3, cl.r to cl.b))
            c.drawCircle(X(px), X(py), pr, p)
        val bw = (cl.r - cl.l); val bh = (cl.b - cl.t)
        val br = (bw * 0.0112 * 1.6).toFloat() * k   // a little bigger than real so it reads
        val cols = intArrayOf(0xFFF2C94C.toInt(), 0xFF2F6FD6.toInt(), 0xFFD62828.toInt(), 0xFF6A3FB5.toInt(), 0xFFF08A24.toInt(),
            0xFF1F8A4C.toInt(), 0xFF8B2A1E.toInt(), 0xFF111111.toInt(), 0xFFF2C94C.toInt(), 0xFF2F6FD6.toInt())
        var n = 0
        val fx = cl.l + bw * 0.75; val fy = cl.cy
        for (row in 0..3) for (j in 0..row) {
            p.color = cols[n++ % cols.size]
            c.drawCircle(X(fx) + row * br * 1.75f, X(fy) + (j - row / 2f) * br * 2.02f, br, p)
        }
        val cx = X(cl.l + bw * 0.25); val cy = X(cl.cy + bh * 0.12)
        s.pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f); s.strokeWidth = 1.4f * d; s.color = 0xB0FFFFFF.toInt()
        c.drawLine(cx, cy, X(fx) - br, X(fy) + br * 0.3f, s)
        s.pathEffect = null
        s.strokeWidth = 4 * d; s.color = 0xFFD9B98A.toInt()
        val ux = (X(fx) - cx); val uy = (X(fy) - cy); val ul = hypot(ux, uy)
        c.drawLine(cx - ux / ul * br * 1.8f, cy - uy / ul * br * 1.8f, cx - ux / ul * 150 * d, cy - uy / ul * 150 * d, s)
        p.color = 0xFFF5F2E8.toInt()
        c.drawCircle(cx, cy, br, p)

        // HUD: menu, player cards and the status line.
        p.color = 0xFF232B32.toInt()
        c.drawRoundRect(box(L.menu), 8 * d, 8 * d, p)
        p.color = 0xFFCED6DD.toInt()
        for (i in -1..1) c.drawRoundRect(X(L.menu.cx) - 9 * d, X(L.menu.cy) + i * 6 * d - 1.2f * d, X(L.menu.cx) + 9 * d, X(L.menu.cy) + i * 6 * d + 1.2f * d, d, d, p)
        p.color = 0xFF2B3A33.toInt()
        c.drawRoundRect(box(L.leftCard), 10 * d, 10 * d, p)
        s.strokeWidth = 2 * d; s.color = 0xFFF2C94C.toInt()
        c.drawRoundRect(box(L.leftCard), 10 * d, 10 * d, s)
        p.color = 0xFF1E252B.toInt()
        c.drawRoundRect(box(L.rightCard), 10 * d, 10 * d, p)
        t.typeface = bold; t.textSize = 14 * d; t.textAlign = Paint.Align.LEFT; t.color = Color.WHITE
        c.drawText("You", X(L.leftCard.l + 12), X(L.leftCard.t + 16), t)
        c.drawText("CPU · Lv 5", X(L.rightCard.l + 12), X(L.rightCard.t + 16), t)
        t.typeface = Typeface.DEFAULT; t.textSize = 9 * d; t.color = 0xFF9AA7B1.toInt()
        c.drawText("Open table", X(L.leftCard.l + 12), X(L.leftCard.t + 28), t)
        t.textAlign = Paint.Align.CENTER; t.textSize = 12 * d; t.color = 0xFFE8ECEF.toInt()
        c.drawText("You: place the cue ball behind the line, then break", X(L.status.cx), X(L.status.cy + 4), t)

        // Power bar (part pulled) and the ball tray.
        t.typeface = bold; t.textSize = 10 * d; t.color = 0xFF9AA7B1.toInt()
        c.drawText("POWER", X(L.power.cx), X(L.power.t - 10), t)
        p.color = 0xFF1E252B.toInt()
        val pw = X(L.power.r - L.power.l)
        c.drawRoundRect(box(L.power), pw / 2, pw / 2, p)
        c.save()
        c.clipRect(X(L.power.l), X(L.power.t), X(L.power.r), X(L.power.t + (L.power.b - L.power.t) * 0.38))
        p.shader = LinearGradient(0f, X(L.power.t), 0f, X(L.power.b), intArrayOf(0xFF3CCB7F.toInt(), 0xFFF2C94C.toInt(), 0xFFEB5757.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(box(L.power), pw / 2, pw / 2, p)
        p.shader = null
        c.restore()
        p.color = 0xFF2A1408.toInt()
        val tw = X(L.tray.r - L.tray.l)
        c.drawRoundRect(box(L.tray), tw / 2, tw / 2, p)
        for ((i, colr) in listOf(0xFFF2C94C.toInt(), 0xFFD62828.toInt()).withIndex()) {
            p.color = colr
            c.drawCircle(X(L.tray.cx), X(L.tray.b - 12 - i * 23), tw * 0.36f, p)
        }

        // Jump slider, spin ball, fine-aim roller.
        t.color = 0xFF9AA7B1.toInt(); t.typeface = bold; t.textSize = 10 * d
        c.drawText("JUMP", X(L.jump.cx), X(L.spinCy - L.spinR - 8), t)
        c.drawText("SPIN", X(L.spinCx), X(L.spinCy - L.spinR - 8), t)
        c.drawText("FINE AIM", X(L.wheel.cx), X(L.wheel.t - 8), t)
        p.color = 0xFF1E252B.toInt()
        c.drawRoundRect(box(L.jump), 3 * d, 3 * d, p)
        p.color = Color.WHITE
        c.drawCircle(X(L.jump.cx), X(L.jump.b), 7 * d, p)
        p.shader = RadialGradient(X(L.spinCx - L.spinR * 0.3), X(L.spinCy - L.spinR * 0.35), X(L.spinR * 1.3),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFE9E4D4.toInt(), 0xFFAFA997.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(X(L.spinCx), X(L.spinCy), X(L.spinR), p)
        p.shader = null
        s.strokeWidth = d; s.color = 0x40000000
        c.drawLine(X(L.spinCx - L.spinR), X(L.spinCy), X(L.spinCx + L.spinR), X(L.spinCy), s)
        c.drawLine(X(L.spinCx), X(L.spinCy - L.spinR), X(L.spinCx), X(L.spinCy + L.spinR), s)
        p.color = 0xFFD62828.toInt()
        c.drawCircle(X(L.spinCx), X(L.spinCy + L.spinR * 0.45), 5 * d, p)
        p.shader = LinearGradient(0f, X(L.wheel.t), 0f, X(L.wheel.b), intArrayOf(0xFF0E1215.toInt(), 0xFF3C4750.toInt(), 0xFF0E1215.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(box(L.wheel), 12 * d, 12 * d, p)
        p.shader = null
        s.color = 0x99A0B0BE.toInt(); s.strokeWidth = 1.5f * d
        var y = L.wheel.t + 8
        while (y < L.wheel.b - 4) { c.drawLine(X(L.wheel.l + 10), X(y), X(L.wheel.r - 10), X(y), s); y += 11 }

        // The selected control's outline, then the numbered badges.
        if (selected >= 0) {
            p.color = 0x66000000
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 22 * d, 22 * d, p)
            s.strokeWidth = 3 * d; s.color = 0xFFF2C94C.toInt()
            for (a in ControlsGuide.areas(L, selected)) {
                val rr = if (selected == 7) X(a.r - a.l) / 2 else 10 * d
                c.drawRoundRect(box(a), rr, rr, s)
            }
        }
        for ((i, b) in badges.withIndex()) {
            val on = i == selected
            val rad = (if (on) 14 else 11) * d
            p.color = 0x99000000.toInt()
            c.drawCircle(X(b.first) + d, X(b.second) + 2 * d, rad, p)
            p.color = if (on) Color.WHITE else 0xFFF2C94C.toInt()
            c.drawCircle(X(b.first), X(b.second), rad, p)
            t.typeface = bold; t.textSize = (if (on) 17 else 14) * d; t.color = 0xFF1A1A1A.toInt(); t.textAlign = Paint.Align.CENTER
            c.drawText((i + 1).toString(), X(b.first), X(b.second) + t.textSize * 0.36f, t)
        }
    }
}
