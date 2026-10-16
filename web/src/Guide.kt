package com.poolgame.web

import com.poolgame.app.core.ControlsGuide
import kotlin.math.hypot
import kotlin.math.min

/**
 * The CONTROLS page: a labelled map of the game screen, then what each numbered control does and
 * how to use it (the same text as the Android app). Tap a number on the map, or an entry in the
 * list, to outline that control.
 */
class Guide {
    private val canvas: dynamic = document.getElementById("map")
    private val page: dynamic = document.getElementById("guide")
    private val layout = ControlsGuide.layout(ControlsGuide.MAP_W, ControlsGuide.MAP_H)
    private val badges = ControlsGuide.badges(layout)
    private val items = ArrayList<dynamic>()
    private var selected = -1
    private var k = 1.0
    private var built = false

    private val bold = "'Arial Narrow', 'Roboto Condensed', 'Helvetica Neue', Arial, sans-serif"
    private val regular = "-apple-system, system-ui, 'Segoe UI', Roboto, Arial, sans-serif"

    fun show() {
        if (!built) build()
        page.style.display = "block"
        page.scrollTop = 0
        selected = -1
        paintItems()
        window.requestAnimationFrame({ _: Double -> draw() })
    }

    fun hide() { page.style.display = "none" }

    fun isOpen() = page.style.display == "block"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun build() {
        built = true
        val list = document.getElementById("guideList")
        for ((i, item) in ControlsGuide.ITEMS.withIndex()) {
            val div = document.createElement("div")
            div.className = "gitem"
            div.innerHTML = "<div class=\"gnum\">${item.n}</div><div><div class=\"gname\">${esc(item.name)}</div>" +
                "<div class=\"gwhere\">${esc(item.where)}</div><ul>" +
                item.how.joinToString("") { "<li>${esc(it)}</li>" } + "</ul></div>"
            div.addEventListener("click", { pick(i, false) })
            list.appendChild(div)
            items.add(div)
        }
        for (sec in ControlsGuide.SECTIONS) {
            val h = document.createElement("div")
            h.className = "sec"
            h.textContent = sec.title.uppercase()
            list.appendChild(h)
            val div = document.createElement("div")
            div.className = "gsec"
            div.innerHTML = "<ul>" + sec.lines.joinToString("") { "<li>${esc(it)}</li>" } + "</ul>"
            list.appendChild(div)
        }
        canvas.addEventListener("click", { e: dynamic ->
            val rect = canvas.getBoundingClientRect()
            val x = (e.clientX as Number).toDouble() - (rect.left as Number).toDouble()
            val y = (e.clientY as Number).toDouble() - (rect.top as Number).toDouble()
            var best = -1; var bestD = Double.MAX_VALUE
            for ((i, b) in badges.withIndex()) {
                val d = hypot(x - b.first * k, y - b.second * k)
                if (d < bestD) { best = i; bestD = d }
            }
            if (best >= 0 && bestD < 40) pick(best, true)
        })
        window.addEventListener("resize", { if (isOpen()) draw() })
    }

    private fun pick(i: Int, scroll: Boolean) {
        selected = if (selected == i && !scroll) -1 else i
        paintItems()
        draw()
        if (scroll) items[i].scrollIntoView(js("({ behavior: 'smooth', block: 'center' })"))
        else page.scrollTo(js("({ top: 0, behavior: 'smooth' })"))
    }

    private fun paintItems() {
        for ((i, d) in items.withIndex()) d.className = if (i == selected) "gitem on" else "gitem"
    }

    private fun X(v: Double) = v * k

    private fun Ctx2D.box(b: ControlsGuide.Box, r: Double) = roundRect(X(b.l), X(b.t), X(b.r - b.l), X(b.b - b.t), r)

    private fun font(px: Double, b: Boolean) = "${if (b) "bold " else ""}${px}px ${if (b) bold else regular}"

    fun draw() {
        // As wide as fits, but small enough to be seen whole under the header.
        val fitH = (window.innerHeight as Number).toDouble() - 110
        canvas.style.width = "min(100%, ${fitH * ControlsGuide.MAP_W / ControlsGuide.MAP_H}px)"
        val cssW = (canvas.clientWidth as Number).toDouble()
        if (cssW <= 0) return
        val cssH = cssW * ControlsGuide.MAP_H / ControlsGuide.MAP_W
        val dpr = min((window.devicePixelRatio as Number?)?.toDouble() ?: 1.0, 2.5)
        canvas.style.height = "${cssH}px"
        canvas.width = (cssW * dpr).toInt()
        canvas.height = (cssH * dpr).toInt()
        val c = ctxOf(canvas)
        c.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
        k = cssW / ControlsGuide.MAP_W
        val d = k
        val L = layout
        c.setLineDash(arrayOf())

        // Phone body and screen.
        c.fillStyle = "#05080A"; c.roundRect(0.0, 0.0, cssW, cssH, 22 * d); c.fill()
        val bg = c.createLinearGradient(0.0, 0.0, 0.0, cssH)
        bg.addColorStop(0.0, "#1A2026"); bg.addColorStop(1.0, "#0D1013")
        c.fillStyle = bg; c.roundRect(2 * d, 2 * d, cssW - 4 * d, cssH - 4 * d, 20 * d); c.fill()

        // Table: wood, cloth, pockets, a rack, the cue ball, cue and aim line.
        c.fillStyle = "#5E3218"; c.box(L.table, 10 * d); c.fill()
        val cl = L.cloth
        val cloth = c.createRadialGradient(X(cl.cx), X(cl.cy), 0.0, X(cl.cx), X(cl.cy), X(cl.r - cl.l) * 0.6)
        cloth.addColorStop(0.0, "#22935A"); cloth.addColorStop(1.0, "#145F38")
        c.fillStyle = cloth; c.fillRect(X(cl.l), X(cl.t), X(cl.r - cl.l), X(cl.b - cl.t))
        c.fillStyle = "#000000"
        for ((px, py) in listOf(cl.l to cl.t, cl.cx to cl.t - 3, cl.r to cl.t, cl.l to cl.b, cl.cx to cl.b + 3, cl.r to cl.b)) {
            c.circle(X(px), X(py), 8 * d); c.fill()
        }
        val bw = cl.r - cl.l; val bh = cl.b - cl.t
        val br = bw * 0.0112 * 1.6 * k
        val cols = arrayOf("#F2C94C", "#2F6FD6", "#D62828", "#6A3FB5", "#F08A24", "#1F8A4C", "#8B2A1E", "#111111", "#F2C94C", "#2F6FD6")
        var n = 0
        val fx = X(cl.l + bw * 0.75); val fy = X(cl.cy)
        for (row in 0..3) for (j in 0..row) {
            c.fillStyle = cols[n++ % cols.size]
            c.circle(fx + row * br * 1.75, fy + (j - row / 2.0) * br * 2.02, br); c.fill()
        }
        val cx = X(cl.l + bw * 0.25); val cy = X(cl.cy + bh * 0.12)
        c.setLineDash(arrayOf(5 * d, 4 * d)); c.lineWidth = 1.4 * d; c.strokeStyle = "rgba(255,255,255,0.7)"
        c.line(cx, cy, fx - br, fy + br * 0.3)
        c.setLineDash(arrayOf())
        val ux = fx - cx; val uy = fy - cy; val ul = hypot(ux, uy)
        c.lineWidth = 4 * d; c.strokeStyle = "#D9B98A"; c.lineCap = "round"
        c.line(cx - ux / ul * br * 1.8, cy - uy / ul * br * 1.8, cx - ux / ul * 150 * d, cy - uy / ul * 150 * d)
        c.fillStyle = "#F5F2E8"; c.circle(cx, cy, br); c.fill()

        // HUD: menu, player cards and the status line.
        c.fillStyle = "#232B32"; c.box(L.menu, 8 * d); c.fill()
        c.fillStyle = "#CED6DD"
        for (i in -1..1) { c.roundRect(X(L.menu.cx - 9), X(L.menu.cy + i * 6 - 1.2), X(18.0), X(2.4), d); c.fill() }
        c.fillStyle = "#2B3A33"; c.box(L.leftCard, 10 * d); c.fill()
        c.lineWidth = 2 * d; c.strokeStyle = "#F2C94C"; c.box(L.leftCard, 10 * d); c.stroke()
        c.fillStyle = "#1E252B"; c.box(L.rightCard, 10 * d); c.fill()
        c.textBaseline = "alphabetic"; c.textAlign = "left"
        c.font = font(14 * d, true); c.fillStyle = "#FFFFFF"
        c.fillText("You", X(L.leftCard.l + 12), X(L.leftCard.t + 16))
        c.fillText("CPU · Lv 5", X(L.rightCard.l + 12), X(L.rightCard.t + 16))
        c.font = font(9 * d, false); c.fillStyle = "#9AA7B1"
        c.fillText("Open table", X(L.leftCard.l + 12), X(L.leftCard.t + 28))
        c.textAlign = "center"; c.font = font(12 * d, false); c.fillStyle = "#E8ECEF"
        c.fillText("You: place the cue ball behind the line, then break", X(L.status.cx), X(L.status.cy + 4))

        // Power bar (part pulled) and the ball tray.
        c.font = font(10 * d, true); c.fillStyle = "#9AA7B1"
        c.fillText("POWER", X(L.power.cx), X(L.power.t - 10))
        val pw = X(L.power.r - L.power.l)
        c.fillStyle = "#1E252B"; c.box(L.power, pw / 2); c.fill()
        c.save()
        c.beginPath(); c.rect(X(L.power.l), X(L.power.t), pw, X((L.power.b - L.power.t) * 0.38)); c.clip()
        val pg = c.createLinearGradient(0.0, X(L.power.t), 0.0, X(L.power.b))
        pg.addColorStop(0.0, "#3CCB7F"); pg.addColorStop(0.5, "#F2C94C"); pg.addColorStop(1.0, "#EB5757")
        c.fillStyle = pg; c.box(L.power, pw / 2); c.fill()
        c.restore()
        val tw = X(L.tray.r - L.tray.l)
        c.fillStyle = "#2A1408"; c.box(L.tray, tw / 2); c.fill()
        for ((i, colr) in listOf("#F2C94C", "#D62828").withIndex()) {
            c.fillStyle = colr; c.circle(X(L.tray.cx), X(L.tray.b - 12 - i * 23), tw * 0.36); c.fill()
        }

        // Spin ball, fine-aim roller.
        c.fillStyle = "#9AA7B1"; c.font = font(10 * d, true)
        c.fillText("SPIN", X(L.spinCx), X(L.spinCy - L.spinR - 8))
        c.fillText("FINE AIM", X(L.wheel.cx), X(L.wheel.t - 8))
        val sg = c.createRadialGradient(X(L.spinCx - L.spinR * 0.3), X(L.spinCy - L.spinR * 0.35), 0.0,
            X(L.spinCx - L.spinR * 0.3), X(L.spinCy - L.spinR * 0.35), X(L.spinR * 1.3))
        sg.addColorStop(0.0, "#FFFFFF"); sg.addColorStop(0.55, "#E9E4D4"); sg.addColorStop(1.0, "#AFA997")
        c.fillStyle = sg; c.circle(X(L.spinCx), X(L.spinCy), X(L.spinR)); c.fill()
        c.lineWidth = d; c.strokeStyle = "rgba(0,0,0,0.25)"
        c.line(X(L.spinCx - L.spinR), X(L.spinCy), X(L.spinCx + L.spinR), X(L.spinCy))
        c.line(X(L.spinCx), X(L.spinCy - L.spinR), X(L.spinCx), X(L.spinCy + L.spinR))
        c.fillStyle = "#D62828"; c.circle(X(L.spinCx), X(L.spinCy + L.spinR * 0.45), 5 * d); c.fill()
        val wg = c.createLinearGradient(0.0, X(L.wheel.t), 0.0, X(L.wheel.b))
        wg.addColorStop(0.0, "#0E1215"); wg.addColorStop(0.5, "#3C4750"); wg.addColorStop(1.0, "#0E1215")
        c.fillStyle = wg; c.box(L.wheel, 12 * d); c.fill()
        c.strokeStyle = "rgba(160,176,190,0.6)"; c.lineWidth = 1.5 * d
        var y = L.wheel.t + 8
        while (y < L.wheel.b - 4) { c.line(X(L.wheel.l + 10), X(y), X(L.wheel.r - 10), X(y)); y += 11 }

        // The selected control's outline, then the numbered badges.
        if (selected >= 0) {
            c.fillStyle = "rgba(0,0,0,0.4)"; c.roundRect(0.0, 0.0, cssW, cssH, 22 * d); c.fill()
            c.lineWidth = 3 * d; c.strokeStyle = "#F2C94C"
            for (a in ControlsGuide.areas(L, selected)) {
                val rr = if (selected == 7) X(a.r - a.l) / 2 else 10 * d
                c.box(a, rr); c.stroke()
            }
        }
        c.textAlign = "center"
        for ((i, b) in badges.withIndex()) {
            val on = i == selected
            val rad = (if (on) 14 else 11) * d
            c.fillStyle = "rgba(0,0,0,0.6)"; c.circle(X(b.first) + d, X(b.second) + 2 * d, rad); c.fill()
            c.fillStyle = if (on) "#FFFFFF" else "#F2C94C"; c.circle(X(b.first), X(b.second), rad); c.fill()
            val fs = (if (on) 17 else 14) * d
            c.font = font(fs, true); c.fillStyle = "#1A1A1A"
            c.fillText((i + 1).toString(), X(b.first), X(b.second) + fs * 0.36)
        }
    }
}
