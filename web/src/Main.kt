package com.poolgame.web

import com.poolgame.app.core.GameMode
import kotlin.math.PI
import kotlin.math.min

fun main() {
    App().start()
}

/** Wires the HTML menu, the canvas, touch input and the frame loop. */
class App : GameHost {
    private val canvas: dynamic = document.getElementById("game")
    private val ctx: Ctx2D = ctxOf(canvas)
    private val sounds = Sounds()
    private var game: Game? = null
    private var rotated = false
    private var vw = 0.0; private var vh = 0.0; private var dpr = 1.0
    private var lastTs = -1.0
    private val slots = HashMap<Int, Int>()

    // Menu state (same keys and meaning as the Android app)
    private var modeIdx = Store.getInt("modeIdx", 0)      // 0 = 8-ball, 1 = 9-ball, 2 = practice
    private var vsCpu = Store.getBool("vsCpu", true)
    private var level = Store.getInt("level", 5).coerceIn(1, 10)
    private var clockOn = Store.getBool("clockOn", false)
    private var clockSec = Store.getInt("clockSec", 30).coerceIn(10, 120)
    private var sound = Store.getBool("sound", true)

    private val levelNames = arrayOf("Beginner", "Novice", "Casual", "Bar Player", "Club Player",
        "Skilled", "Advanced", "Expert", "Master", "Pro")

    private fun el(id: String): dynamic = document.getElementById(id)

    fun start() {
        wireMenu()
        refreshMenu()
        window.addEventListener("resize", { resize() })
        window.addEventListener("orientationchange", { window.setTimeout({ resize() }, 200) })
        resize()

        val opts: dynamic = js("({ passive: false })")
        canvas.addEventListener("pointerdown", { e: dynamic -> e.preventDefault(); onPointer(e, 0) }, opts)
        canvas.addEventListener("pointermove", { e: dynamic -> e.preventDefault(); onPointer(e, 1) }, opts)
        canvas.addEventListener("pointerup", { e: dynamic -> e.preventDefault(); onPointer(e, 2) }, opts)
        canvas.addEventListener("pointercancel", { e: dynamic -> onPointer(e, 3) }, opts)
        // Stop iOS from scrolling, zooming or showing the magnifier while playing.
        document.addEventListener("touchmove", { e: dynamic -> if (game != null) e.preventDefault() }, opts)
        document.addEventListener("gesturestart", { e: dynamic -> e.preventDefault() }, opts)
        canvas.addEventListener("contextmenu", { e: dynamic -> e.preventDefault() }, opts)

        try {
            if (navigator.serviceWorker != null && window.location.protocol == "https:") navigator.serviceWorker.register("sw.js")
        } catch (e: dynamic) { }
        window.requestAnimationFrame(::frame)
    }

    private fun resize() {
        vw = (window.innerWidth as Number).toDouble()
        vh = (window.innerHeight as Number).toDouble()
        dpr = min((window.devicePixelRatio as Number?)?.toDouble() ?: 1.0, 2.5)
        canvas.width = (vw * dpr).toInt()
        canvas.height = (vh * dpr).toInt()
        canvas.style.width = "${vw}px"
        canvas.style.height = "${vh}px"
        // The game is landscape. If the screen is portrait (e.g. rotation lock on), draw it turned
        // sideways so it fills the screen when the phone is held horizontally.
        rotated = vh > vw
        val lw = if (rotated) vh else vw
        val lh = if (rotated) vw else vh
        game?.resize(lw, lh, dpr)
    }

    private fun frame(ts: Double) {
        val g = game
        if (g != null) {
            val dt = if (lastTs < 0) 0.0 else ((ts - lastTs) / 1000.0).coerceIn(0.0, 0.05)
            lastTs = ts
            g.update(dt)
            ctx.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
            if (rotated) { ctx.translate(vw, 0.0); ctx.rotate(PI / 2) }
            g.draw(ctx)
        } else lastTs = -1.0
        window.requestAnimationFrame(::frame)
    }

    private fun onPointer(e: dynamic, kind: Int) {
        val g = game ?: return
        val pid = e.pointerId as Int
        val cx = (e.clientX as Number).toDouble(); val cy = (e.clientY as Number).toDouble()
        val x = if (rotated) cy else cx
        val y = if (rotated) vw - cx else cy
        when (kind) {
            0 -> {
                var slot = -1
                for (s in 0 until 10) if (s !in slots.values) { slot = s; break }
                if (slot < 0) return
                val first = slots.isEmpty()
                slots[pid] = slot
                try { canvas.setPointerCapture(pid) } catch (ex: dynamic) { }
                g.pointerDown(slot, x, y, first)
            }
            1 -> { val s = slots[pid] ?: return; g.pointerMove(s, x, y) }
            2 -> { val s = slots.remove(pid) ?: return; g.pointerUp(s) }
            else -> { slots.remove(pid); if (slots.isEmpty()) g.cancelTouches() }
        }
    }

    // ------------------------------------------------------------------ menu

    private fun wireMenu() {
        segment("seg-mode") { modeIdx = it; save(); refreshMenu() }
        segment("seg-opp") { vsCpu = it == 0; save(); refreshMenu() }
        el("skill").addEventListener("input", { level = (el("skill").value as String).toInt(); save(); refreshMenu() })
        el("clockOn").addEventListener("change", { clockOn = el("clockOn").checked as Boolean; save(); refreshMenu() })
        el("clockSec").addEventListener("input", { clockSec = (el("clockSec").value as String).toInt(); save(); refreshMenu() })
        el("sound").addEventListener("change", { sound = el("sound").checked as Boolean; save() })
        el("resetRec").addEventListener("click", {
            if (window.confirm("Reset your record? This clears all wins and losses.") as Boolean) { Store.resetRecord(); refreshMenu() }
        })
        el("play").addEventListener("click", { sounds.unlock(); startGame() })
        el("resume").addEventListener("click", { hidePause() })
        el("restart").addEventListener("click", { hidePause(); game?.restart() })
        el("quit").addEventListener("click", { hidePause(); showMenu() })
        val ua = navigator.userAgent as String
        val ios = Regex("iPhone|iPad|iPod").containsMatchIn(ua)
        val standalone = (navigator.standalone == true) ||
            (window.matchMedia("(display-mode: standalone)").matches == true) ||
            (window.matchMedia("(display-mode: fullscreen)").matches == true)
        el("iosHint").style.display = if (ios && !standalone) "block" else "none"
    }

    private fun segment(id: String, onPick: (Int) -> Unit) {
        val buttons = el(id).querySelectorAll("button")
        val n = buttons.length as Int
        for (i in 0 until n) buttons[i].addEventListener("click", { onPick(i) })
    }

    private fun setSegment(id: String, sel: Int) {
        val buttons = el(id).querySelectorAll("button")
        val n = buttons.length as Int
        for (i in 0 until n) buttons[i].className = if (i == sel) "on" else ""
    }

    private fun modeName() = if (modeIdx == 1) GameMode.NINE_BALL.name else GameMode.EIGHT_BALL.name

    private fun refreshMenu() {
        setSegment("seg-mode", modeIdx)
        setSegment("seg-opp", if (vsCpu) 0 else 1)
        el("matchOpts").style.display = if (modeIdx == 2) "none" else "block"
        el("skillBlock").style.display = if (vsCpu) "block" else "none"
        el("skill").value = level.toString()
        el("skillLabel").textContent = "Level $level · ${levelNames[level - 1]}"
        el("clockOn").checked = clockOn
        el("clockSec").value = clockSec.toString()
        el("clockSec").disabled = !clockOn
        el("clockLabel").textContent = if (clockOn) "$clockSec seconds per shot" else "Off"
        el("sound").checked = sound
        val m = modeName()
        val name = if (modeIdx == 1) "9-Ball" else "8-Ball"
        el("record").innerHTML = if (vsCpu)
            "$name vs level $level: <b>${Store.wins(m, true, level)} W – ${Store.losses(m, true, level)} L</b><br>" +
                "$name vs all levels: ${Store.totalWins(m)} W – ${Store.totalLosses(m)} L"
        else "$name pass &amp; play: Player 1 <b>${Store.wins(m, false, 0)} – ${Store.losses(m, false, 0)}</b> Player 2"
    }

    private fun save() {
        Store.set("modeIdx", modeIdx.toString()); Store.set("vsCpu", vsCpu.toString())
        Store.set("level", level.toString()); Store.set("clockOn", clockOn.toString())
        Store.set("clockSec", clockSec.toString()); Store.set("sound", sound.toString())
    }

    private fun startGame() {
        val practice = modeIdx == 2
        val mode = if (modeIdx == 1) GameMode.NINE_BALL else GameMode.EIGHT_BALL
        sounds.enabled = sound
        game = Game(mode, vsCpu && !practice, level, if (clockOn && !practice) clockSec else 0, practice, sounds, this)
        el("menu").style.display = "none"
        canvas.style.display = "block"
        slots.clear()
        resize()
    }

    private fun showMenu() {
        game = null
        canvas.style.display = "none"
        el("menu").style.display = "flex"
        refreshMenu()
    }

    private fun hidePause() {
        el("pause").style.display = "none"
        game?.menuOpen = false
    }

    override fun onMenuRequested() {
        game?.menuOpen = true
        el("pause").style.display = "flex"
    }

    override fun onExitToMenu() = showMenu()

    override fun onGameFinished(winner: Int): String {
        if (game == null) return ""
        val m = modeName()
        val cpu = vsCpu && modeIdx != 2
        val lvl = if (cpu) level else 0
        Store.record(m, cpu, lvl, winner)
        val name = if (modeIdx == 1) "9-Ball" else "8-Ball"
        return if (cpu) "Your $name record vs level $level: ${Store.wins(m, true, lvl)}–${Store.losses(m, true, lvl)}"
        else "$name pass & play: Player 1 ${Store.wins(m, false, 0)} – Player 2 ${Store.losses(m, false, 0)}"
    }
}
