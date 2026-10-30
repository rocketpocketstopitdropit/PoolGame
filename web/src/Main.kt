package com.poolgame.web

import com.poolgame.app.core.CallMode
import com.poolgame.app.core.GameMode
import com.poolgame.app.core.GameSave
import com.poolgame.app.core.TableSize
import kotlin.math.PI
import kotlin.math.min

fun main() {
    App().start()
}

/** Wires the HTML menu, the canvas, touch input and the frame loop. */
class App : GameHost, NetHost {
    private val canvas: dynamic = document.getElementById("game")
    private val ctx: Ctx2D = ctxOf(canvas)
    private val sounds = Sounds()
    private var game: Game? = null
    private var rotated = false
    private var vw = 0.0; private var vh = 0.0; private var dpr = 1.0
    private var lastTs = -1.0
    private val slots = HashMap<Int, Int>()
    private var mediaRec: dynamic = null

    // Menu state (same keys and meaning as the Android app)
    // The game, and who it's against: 0 the computer, 1 pass & play, 2 practice on your own.
    private var gameMode = GameMode.EIGHT_BALL
    private var vsCpu = Store.getBool("vsCpu", true)
    private var opp = 0
    private var level = Store.getInt("level", 5).coerceIn(1, 10)
    private var clockOn = Store.getBool("clockOn", false)
    private var clockSec = Store.getInt("clockSec", 30).coerceIn(10, 120)
    private var sound = Store.getBool("sound", true)
    private var aimSens = Store.getInt("aimSens", 5).coerceIn(1, 10)
    private var callIdx = Store.getInt("callMode", 0).coerceIn(0, 2)
    private var scratchKitchen = Store.get("scratchKitchen", "false") == "true"
    private var trickSet = Store.getInt("trickSet", 0).coerceIn(0, 1)
    private var prediction = Store.getBool("prediction", true)
    /** Practice a pool game with its rules (false: the free table). */
    private var practiceRules = Store.getBool("practiceRules", true)
    private var practiceLevel = Store.getInt("practiceLevel", 0).coerceIn(0, com.poolgame.app.core.Trick.LEVELS.size - 1)
    private var askTutorial = Store.getBool("askTutorial", true)
    private var tableSize = TableSize.fromName(Store.get("tableSize", "")) ?: TableSize.DEFAULT
    // The octagon table: 2, 3 or 4 players, two teams of two (with 4), and which bumper layout.
    private var octPlayers = Store.getInt("octPlayers", 4).coerceIn(2, 4)
    private var octTeams = Store.getBool("octTeams", false) && octPlayers == 4
    /** The Gauntlet: players (2 to 4), teams, red's partner in 2 v 2 (1 across, 2 blue, 3 yellow), and balls each. */
    private var gPlayers = Store.getInt("gPlayers", 4).coerceIn(2, 4)
    private var gTeams = Store.getBool("gTeams", false) && gPlayers == 4
    private var gPartner = Store.getInt("gPartner", 1).coerceIn(1, 3)
    private var gEach = Store.getInt("gEach", 7).coerceIn(6, 8)
    private var octLayout = Store.getInt("octLayout", 0).coerceIn(0, com.poolgame.app.core.Table.OCT_LAYOUTS.size - 1)
    private var playingOct = "4"
    private val guide = Guide()
    /** Online play: the room we're in (or hosting), if any. */
    private val online = Online(this)
    private var playingOnline = false

    // What the current game is (may differ from the menu when resuming a saved game)
    private var playingCpu = true
    private var playingLevel = 5
    private var playingMode = GameMode.EIGHT_BALL
    private var playingPractice = false

    private val levelNames = arrayOf("Beginner", "Novice", "Casual", "Bar Player", "Club Player",
        "Skilled", "Advanced", "Expert", "Master", "Pro")

    private fun el(id: String): dynamic = document.getElementById(id)

    init {
        val stored = GameMode.values().firstOrNull { it.name == Store.get("game", "") }
        if (stored != null) {
            gameMode = stored
            opp = Store.getInt("opp", if (vsCpu) 0 else 1).coerceIn(0, 3)
        } else {
            // From before practice was an opponent choice: one number for the game type, with Practice as one of them.
            when (Store.getInt("modeIdx", 0)) {
                1 -> gameMode = GameMode.NINE_BALL
                3 -> gameMode = GameMode.TRICK
                4 -> gameMode = GameMode.BUMPER
                5 -> gameMode = GameMode.CIRCUIT
                2 -> {
                    opp = 2
                    when (Store.getInt("practiceKind", if (Store.getBool("practiceTrick", false)) 1 else 0)) {
                        1 -> gameMode = GameMode.TRICK
                        2 -> gameMode = GameMode.BUMPER
                        else -> practiceRules = false
                    }
                }
            }
            if (opp != 2) opp = if (vsCpu) 0 else 1
        }
        vsCpu = opp == 0
    }

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
        // Mouse wheel zooms the 3D view (a computer has no two-finger pinch).
        canvas.addEventListener("wheel", { e: dynamic -> wheel(e) }, opts)

        // Each time the app is opened, offer the tutorial (unless they've said not to ask).
        if (askTutorial && !joiningByLink) el("tutAsk").style.display = "flex"

        try {
            if (navigator.serviceWorker != null && window.location.protocol == "https:") navigator.serviceWorker.register("sw.js")
        } catch (e: dynamic) { }
        if ((window.location.search as String).contains("debug=1")) window.poolDebug = { game?.debugJson() }
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

    private fun wheel(e: dynamic) {
        val g = game ?: return
        e.preventDefault()
        val cx = (e.clientX as Number).toDouble(); val cy = (e.clientY as Number).toDouble()
        val x = if (rotated) cy else cx
        val y = if (rotated) vw - cx else cy
        g.wheel(x, y, (e.deltaY as Number).toDouble())
    }

    // ------------------------------------------------------------------ menu

    private fun wireMenu() {
        segment("seg-mode") { gameMode = GAMES[it]; save(); refreshMenu() }
        // The buttons: Computer, Pass & Play, Online, Practice (stored as 0, 1, 3, 2: practice was there first).
        segment("seg-opp") { opp = OPP_OF_BUTTON[it]; vsCpu = opp == 0; save(); refreshMenu() }
        segment("seg-prules") { practiceRules = it == 0; save(); refreshMenu() }
        segment("seg-call") { callIdx = it; save(); refreshMenu() }
        segment("seg-scratch") { scratchKitchen = it == 1; save(); refreshMenu() }
        segment("seg-tset") { trickSet = it; save(); refreshMenu() }
        segment("seg-size") { tableSize = TableSize.values()[it]; save(); refreshMenu() }
        segment("seg-octp") { octTeams = it == 3; octPlayers = if (it == 3) 4 else it + 2; save(); refreshMenu() }
        segment("seg-gp") { gTeams = it == 3; gPlayers = if (it == 3) 4 else it + 2; save(); refreshMenu() }
        segment("seg-gpartner") { gPartner = it + 1; save(); refreshMenu() }
        segment("seg-geach") { gEach = it + 6; save(); refreshMenu() }
        segment("seg-octl") { octLayout = it; save(); refreshMenu() }
        val pl = el("seg-plevel")
        for ((i, lv) in com.poolgame.app.core.Trick.LEVELS.withIndex()) {
            val b = document.createElement("button")
            b.textContent = lv.name
            b.addEventListener("click", { practiceLevel = i; save(); refreshMenu() })
            pl.appendChild(b)
        }
        el("skill").addEventListener("input", { level = (el("skill").value as String).toInt(); save(); refreshMenu() })
        el("clockOn").addEventListener("change", { clockOn = el("clockOn").checked as Boolean; save(); refreshMenu() })
        el("clockSec").addEventListener("input", { clockSec = (el("clockSec").value as String).toInt(); save(); refreshMenu() })
        el("sound").addEventListener("change", { sound = el("sound").checked as Boolean; save() })
        el("resetRec").addEventListener("click", {
            if (window.confirm("Reset your record? This clears all wins and losses, and your rock, paper, scissors stats.") as Boolean) { Store.resetRecord(); refreshMenu() }
        })
        el("voiceRec").addEventListener("click", { onVoiceRecord() })
        el("voicePlay").addEventListener("click", { sounds.unlock(); sounds.previewVoice() })
        el("voiceDel").addEventListener("click", { VoiceStore.clear(); refreshVoice(null) })
        el("aimSens").addEventListener("input", { aimSens = (el("aimSens").value as String).toInt(); save(); refreshMenu() })
        el("prediction").addEventListener("change", { prediction = el("prediction").checked as Boolean; save() })
        el("play").addEventListener("click", { sounds.unlock(); if (opp == 3) hostOnline() else startGame(false) })
        wireOnline()
        // Learning the controls: the tutorial, the screen guide, and the opening prompt.
        el("tutBtn").addEventListener("click", { sounds.unlock(); startTutorial() })
        el("guideBtn").addEventListener("click", { guide.show() })
        el("guideBack").addEventListener("click", { guide.hide() })
        el("guideTut").addEventListener("click", { guide.hide(); sounds.unlock(); startTutorial() })
        el("askTut").addEventListener("change", { askTutorial = el("askTut").checked as Boolean; Store.set("askTutorial", askTutorial.toString()) })
        fun closeAsk() {
            el("tutAsk").style.display = "none"
            if (el("tutNever").checked == true) {
                askTutorial = false; Store.set("askTutorial", "false"); el("askTut").checked = false
            }
        }
        el("tutYes").addEventListener("click", { closeAsk(); sounds.unlock(); startTutorial() })
        el("tutNo").addEventListener("click", { closeAsk() })
        el("resumeBtn").addEventListener("click", { sounds.unlock(); startGame(true) })
        el("resume").addEventListener("click", { hidePause() })
        el("restart").addEventListener("click", { hidePause(); game?.restart() })
        el("predToggle").addEventListener("click", {
            val g = game
            if (g != null) {
                g.showPrediction = !g.showPrediction
                if (!g.isTutorial()) { prediction = g.showPrediction; save() }
                el("predToggle").textContent = if (prediction) "Shot prediction: On" else "Shot prediction: Off"
            }
        })
        el("quit").addEventListener("click", { hidePause(); if (playingOnline) leaveOnline() else { saveGame(); showMenu() } })
        // Keep the game if the browser tab is closed or the phone switches apps.
        document.addEventListener("visibilitychange", { if (document.hidden == true) saveGame() })
        window.addEventListener("pagehide", { saveGame() })
        val ua = navigator.userAgent as String
        val ios = Regex("iPhone|iPad|iPod").containsMatchIn(ua)
        val standalone = (navigator.standalone == true) ||
            (window.matchMedia("(display-mode: standalone)").matches == true) ||
            (window.matchMedia("(display-mode: fullscreen)").matches == true)
        el("iosHint").style.display = if (ios && !standalone) "block" else "none"
    }

    // ------------------------------------------------------------------ foul voice

    private fun refreshVoice(note: String?) {
        val secs = VoiceStore.seconds()
        el("voicePlay").disabled = secs <= 0.0
        el("voiceDel").disabled = secs <= 0.0
        el("voiceStatus").textContent = note ?: if (secs > 0) "Your voice is set (${fmt1(secs)} s)" else "Not recorded yet — a cough plays instead"
    }

    private fun fmt1(v: Double): String { val t = kotlin.math.round(v * 10).toInt(); return "${t / 10}.${t % 10}" }

    /** Record from the microphone (up to 3 s, or tap again to stop), clean it up and keep it. */
    private fun onVoiceRecord() {
        val current = mediaRec
        if (current != null) { try { current.stop() } catch (_: dynamic) { }; return }
        sounds.unlock()
        val md = navigator.mediaDevices
        val ctor = window.MediaRecorder
        if (md == null || ctor == null) { refreshVoice("Recording isn't supported in this browser"); return }
        md.getUserMedia(js("({ audio: true })")).then({ stream: dynamic ->
            val chunks: dynamic = js("[]")
            val rec: dynamic = js("new ctor(stream)")
            mediaRec = rec
            rec.ondataavailable = { e: dynamic -> if ((e.data.size as Number).toInt() > 0) chunks.push(e.data) }
            rec.onstop = {
                try { stream.getTracks().forEach { t: dynamic -> t.stop() } } catch (_: dynamic) { }
                mediaRec = null
                el("voiceRec").innerHTML = "&#9679; Record"
                val type = rec.mimeType
                val blob: dynamic = js("new Blob(chunks, { type: type })")
                blob.arrayBuffer().then({ ab: dynamic ->
                    sounds.context().decodeAudioData(ab, { buf: dynamic ->
                        val ch = buf.getChannelData(0)
                        val n = (ch.length as Number).toInt()
                        val samples = DoubleArray(n) { (ch[it] as Number).toDouble() }
                        val secs = VoiceStore.save(samples, (buf.sampleRate as Number).toInt())
                        refreshVoice(if (secs > 0) "Got it (${fmt1(secs)} s) · it plays on the next foul"
                            else "Didn't hear anything — try again a bit closer to the mic")
                    }, { _: dynamic -> refreshVoice("Couldn't read the recording") })
                })
            }
            rec.start()
            el("voiceRec").innerHTML = "&#9632; Stop"
            refreshVoice("Recording… say it now")
            window.setTimeout({ if (mediaRec === rec) try { rec.stop() } catch (_: dynamic) { } }, 3000)
        }, { _: dynamic -> refreshVoice("Recording needs microphone access") })
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

    private fun menuMode() = gameMode
    private fun modeName() = menuMode().name
    private fun nameOf(m: GameMode) = when (m) {
        GameMode.EIGHT_BALL -> "8-Ball"; GameMode.NINE_BALL -> "9-Ball"; GameMode.TRICK -> "Trick shots"; GameMode.BUMPER -> "Bumper pool"
        GameMode.CIRCUIT -> "Circuit Pool"; GameMode.POCKET_WARS -> "Pocket Wars"; GameMode.CUTTHROAT -> "Cutthroat"
        GameMode.OCTAGON -> "Octagon bumper"; GameMode.GAUNTLET -> "Gauntlet"
    }

    /** The menu's picture of the octagon table with the chosen bumper layout. */
    private fun drawOctPreview() {
        val cv = el("octPreview")
        val w = (cv.clientWidth as Number).toDouble(); val h = (cv.clientHeight as Number).toDouble()
        if (w <= 0 || h <= 0) return
        val d = min((window.devicePixelRatio as Number?)?.toDouble() ?: 1.0, 2.5)
        cv.width = (w * d).toInt(); cv.height = (h * d).toInt()
        val c = ctxOf(cv)
        c.setTransform(d, 0.0, 0.0, d, 0.0, 0.0)
        c.clearRect(0.0, 0.0, w, h)
        val T = com.poolgame.app.core.Table
        val tw = 2 * (T.OCT_HX + T.OCT_OUTER); val th = 2 * (T.OCT_HY + T.OCT_OUTER)
        val sc = min(w / tw, h / th) * 0.96
        OctagonPainter.draw(c, w / 2, h / 2, sc, octLayout, com.poolgame.app.core.BumperColors.cupRings(true, octPlayers), 0.6)
    }

    private fun refreshMenu() {
        val practice = opp == 2
        val net = opp == 3
        el("onlineOpts").style.display = if (net) "block" else "none"
        el("netName").value = online.name
        el("play").textContent = if (net) "HOST ONLINE GAME" else "PLAY"
        val poolTable = gameMode != GameMode.TRICK && gameMode != GameMode.BUMPER && gameMode != GameMode.OCTAGON && gameMode != GameMode.GAUNTLET
        setSegment("seg-mode", GAMES.indexOf(gameMode))
        setSegment("seg-opp", OPP_OF_BUTTON.indexOf(opp))
        setSegment("seg-call", callIdx)
        setSegment("seg-scratch", if (scratchKitchen) 1 else 0)
        setSegment("seg-tset", trickSet)
        el("matchOpts").style.display = if (practice) "none" else "block"
        el("practiceOpts").style.display = if (practice) "block" else "none"
        el("prulesBlock").style.display = if (poolTable) "block" else "none"
        setSegment("seg-prules", if (practiceRules) 0 else 1)
        el("prulesHelp").textContent = if (practiceRules) "On your own with the ${nameOf(gameMode)} rules: you take every shot, " +
            "and each one is judged as in a game (fouls give you ball in hand). Retry a shot, re-rack, or hold a ball to move it."
            else "No rules: plain red and yellow balls (and the black). Hold any ball to move it anywhere."
        el("pbumperHelp").style.display = if (gameMode == GameMode.BUMPER) "block" else "none"
        el("poctHelp").style.display = if (gameMode == GameMode.OCTAGON || gameMode == GameMode.GAUNTLET) "block" else "none"
        el("poctHelp").textContent = if (gameMode == GameMode.GAUNTLET) "The Gauntlet on your own, with every colour's balls, no rules: tap any ball to shoot it, hold one to move it."
            else "The octagon table on your own, with every colour's balls and the layout above, no rules: tap any ball to shoot it, hold one to move it."
        val gaunt = gameMode == GameMode.GAUNTLET
        el("gauntHelp").style.display = if (gaunt) "block" else "none"
        el("gBlock").style.display = if (gaunt) "block" else "none"
        el("gPartnerBlock").style.display = if (gTeams) "block" else "none"
        setSegment("seg-gp", if (gTeams) 3 else gPlayers - 2)
        setSegment("seg-gpartner", gPartner - 1)
        setSegment("seg-geach", gEach - 6)
        val mate = when (gPartner) { 1 -> "white (across the table)"; 2 -> "blue (beside you)"; else -> "yellow (beside you)" }
        el("gpHelp").textContent = when {
            gTeams -> "Two teams of two: red & $mate against the other two. Turns always swap teams. " +
                (if (vsCpu) "You're red, with a CPU as your partner." else "")
            vsCpu -> "You against ${if (gPlayers == 2) "one CPU" else "${gPlayers - 1} CPUs"}, all at the skill below."
            opp == 3 -> "$gPlayers players, each on their own phone."
            else -> "$gPlayers people sharing the phone."
        }
        val oct = gameMode == GameMode.OCTAGON
        el("octHelp").style.display = if (oct) "block" else "none"
        el("octBlock").style.display = if (oct) "block" else "none"
        setSegment("seg-octp", if (octTeams) 3 else octPlayers - 2)
        setSegment("seg-octl", octLayout)
        el("octlHelp").textContent = com.poolgame.app.core.Table.OCT_LAYOUT_HELP[octLayout]
        el("octpHelp").textContent = when {
            octTeams -> "Two teams: red & white against blue & yellow, partners across the table from each other. " +
                (if (vsCpu) "You're red, with CPU 1 as your partner." else "")
            vsCpu -> "You against ${if (octPlayers == 2) "one CPU" else "${octPlayers - 1} CPUs"}, all at the skill below."
            opp == 3 -> "$octPlayers players, each on their own phone."
            else -> "$octPlayers people sharing the phone."
        } + " Turns go round the table: red, blue, white, yellow."
        if (oct) window.setTimeout({ drawOctPreview() }, 0)
        el("plevelBlock").style.display = if (gameMode == GameMode.TRICK) "block" else "none"
        setSegment("seg-plevel", practiceLevel)
        val poolGame = gameMode == GameMode.EIGHT_BALL || gameMode == GameMode.NINE_BALL || gameMode == GameMode.CIRCUIT ||
            gameMode == GameMode.POCKET_WARS || gameMode == GameMode.CUTTHROAT
        el("callBlock").style.display = if (poolGame) "block" else "none"
        el("scratchBlock").style.display = if (poolGame) "block" else "none"
        el("trickHelp").style.display = if (gameMode == GameMode.TRICK) "block" else "none"
        el("trickSetBlock").style.display = if (gameMode == GameMode.TRICK && opp != 2) "block" else "none"
        el("bumperHelp").style.display = if (gameMode == GameMode.BUMPER) "block" else "none"
        el("circuitHelp").style.display = if (gameMode == GameMode.CIRCUIT) "block" else "none"
        el("warsHelp").style.display = if (gameMode == GameMode.POCKET_WARS) "block" else "none"
        el("cutHelp").style.display = if (gameMode == GameMode.CUTTHROAT) "block" else "none"
        el("skillTitle").textContent = when {
            gameMode == GameMode.CUTTHROAT -> "COMPUTER SKILL (BOTH CPUs)"
            gameMode == GameMode.OCTAGON && octPlayers > 2 -> "COMPUTER SKILL (ALL CPUs)"
            gameMode == GameMode.GAUNTLET && gPlayers > 2 -> "COMPUTER SKILL (ALL CPUs)"
            else -> "COMPUTER SKILL"
        }
        // Trick shots are always on the 9 ft table (with its gap) and bumper pool on its own table, so the size choice hides for them.
        el("sizeBlock").style.display = if (poolTable) "block" else "none"
        setSegment("seg-size", tableSize.ordinal)
        el("sizeHelp").textContent = tableSize.description
        el("skillBlock").style.display = if (vsCpu) "block" else "none"
        el("skill").value = level.toString()
        el("skillLabel").textContent = "Level $level · ${levelNames[level - 1]}"
        el("clockOn").checked = clockOn
        el("clockSec").value = clockSec.toString()
        el("clockSec").disabled = !clockOn
        el("clockLabel").textContent = if (clockOn) "$clockSec seconds per shot" else "Off"
        el("sound").checked = sound
        el("aimSens").value = aimSens.toString()
        el("sensLabel").textContent = "Fine-aim wheel speed: $aimSens" +
            if (aimSens == 5) " (default)" else if (aimSens < 5) " (finer)" else " (faster)"
        el("prediction").checked = prediction
        el("askTut").checked = askTutorial
        if (mediaRec == null) refreshVoice(null)
        val saved = savedSummary()
        el("resumeBtn").style.display = if (saved != null) "block" else "none"
        el("resumeBtn").textContent = "RESUME · ${saved ?: ""}"
        val m = modeName()
        val name = nameOf(menuMode())
        el("record").innerHTML = (if (net)
            "$name online: <b>${Store.onlineWins(m)} W – ${Store.onlineLosses(m)} L</b>"
        else if (gameMode == GameMode.OCTAGON)
            Store.octSummary(Store.octVariant(octPlayers, octTeams), vsCpu, level)
        else if (gameMode == GameMode.GAUNTLET)
            Store.octSummary(Store.octVariant(gPlayers, gTeams), vsCpu, level, game = "GAUNTLET")
        else if (gameMode == GameMode.CUTTHROAT && !vsCpu)
            "$name pass &amp; play wins: Player 1 <b>${Store.cutthroatWins(0)}</b> · Player 2 <b>${Store.cutthroatWins(1)}</b> · " +
                "Player 3 <b>${Store.cutthroatWins(2)}</b>"
        else if (gameMode == GameMode.CUTTHROAT)
            "$name vs two level $level CPUs: <b>${Store.wins(m, true, level)} W – ${Store.losses(m, true, level)} L</b><br>" +
                "$name vs all levels: ${Store.totalWins(m)} W – ${Store.totalLosses(m)} L"
        else if (vsCpu)
            "$name vs level $level: <b>${Store.wins(m, true, level)} W – ${Store.losses(m, true, level)} L</b><br>" +
                "$name vs all levels: ${Store.totalWins(m)} W – ${Store.totalLosses(m)} L"
        else "$name pass &amp; play: Player 1 <b>${Store.wins(m, false, 0)} – ${Store.losses(m, false, 0)}</b> Player 2") +
            "<br><br>" + Store.rpsSummary(vsCpu)
    }

    private fun save() {
        Store.set("game", gameMode.name); Store.set("opp", opp.toString()); Store.set("vsCpu", vsCpu.toString())
        Store.set("level", level.toString()); Store.set("clockOn", clockOn.toString())
        Store.set("clockSec", clockSec.toString()); Store.set("sound", sound.toString())
        Store.set("aimSens", aimSens.toString()); Store.set("callMode", callIdx.toString()); Store.set("scratchKitchen", scratchKitchen.toString()); Store.set("trickSet", trickSet.toString()); Store.set("prediction", prediction.toString())
        Store.set("practiceRules", practiceRules.toString()); Store.set("practiceLevel", practiceLevel.toString())
        Store.set("tableSize", tableSize.name)
        Store.set("octPlayers", octPlayers.toString()); Store.set("octTeams", octTeams.toString()); Store.set("octLayout", octLayout.toString())
        Store.set("gPlayers", gPlayers.toString()); Store.set("gTeams", gTeams.toString()); Store.set("gPartner", gPartner.toString())
        Store.set("gEach", gEach.toString())
    }

    private fun savedSummary(): String? {
        val s = GameSave.readSettings(Store.get("savedGame", "")) ?: return null
        val name = nameOf(s.mode)
        val size = if (s.mode == GameMode.BUMPER || s.mode == GameMode.OCTAGON || s.mode == GameMode.GAUNTLET) "" else " · ${s.tableSize.label}"
        val oct = "${Store.octVariantName(Store.octVariant(s.octPlayers, s.octTeams))} · ${com.poolgame.app.core.Table.OCT_LAYOUTS[s.octLayout]}"
        return when {
            s.practice && s.mode == GameMode.TRICK -> "Trick-shot practice"
            s.practice && s.mode == GameMode.BUMPER -> "Bumper practice"
            s.practice && s.mode == GameMode.OCTAGON -> "Octagon practice · ${com.poolgame.app.core.Table.OCT_LAYOUTS[s.octLayout]}"
            s.mode == GameMode.OCTAGON && s.vsCpu -> "$name $oct vs CPU level ${s.level}"
            s.mode == GameMode.OCTAGON -> "$name $oct pass & play"
            s.practice && s.mode == GameMode.GAUNTLET -> "Gauntlet practice"
            s.mode == GameMode.GAUNTLET -> "$name ${Store.octVariantName(Store.octVariant(s.octPlayers, s.octTeams))} · ${s.gauntEach} balls" +
                if (s.vsCpu) " vs CPU level ${s.level}" else " pass & play"
            s.practice && s.practiceRules -> "$name practice$size"
            s.mode == GameMode.TRICK -> if (s.vsCpu) "$name vs CPU level ${s.level}" else "$name pass & play"
            s.practice -> "Free-table practice$size"
            s.vsCpu && s.mode == GameMode.CUTTHROAT -> "$name vs 2 CPUs level ${s.level}$size"
            s.vsCpu -> "$name vs CPU level ${s.level}$size"
            else -> "$name pass & play$size"
        }
    }

    private fun saveGame() {
        val g = game ?: return
        if (g.isTutorial() || g.isOnline()) return   // (an online game lives in its room, not in the saved game)   // never replace a paused game with the tutorial
        Store.set("savedGame", g.saveState() ?: "")
    }

    private fun startGame(resume: Boolean) {
        playingOnline = false
        var practice = opp == 2
        var mode = gameMode
        var rules = practiceRules
        var cpu = vsCpu && !practice
        var lvl = level
        var clock = if (clockOn && !practice) clockSec else 0
        var call = CallMode.values()[callIdx]
        var text: String? = null
        var size = tableSize
        val gaunt = gameMode == GameMode.GAUNTLET
        var oPlayers = if (gaunt) gPlayers else octPlayers; var oTeams = if (gaunt) gTeams else octTeams; var oLayout = octLayout
        var each = gEach; var mate = gPartner
        var barScratch = scratchKitchen
        var tSet = trickSet
        if (resume) {
            val saved = Store.get("savedGame", "")
            val s = GameSave.readSettings(saved)
            if (s != null) {
                text = saved
                practice = s.practice; mode = s.mode; cpu = s.vsCpu; lvl = s.level; clock = s.shotClock
                rules = s.practiceRules
                call = s.callMode
                size = s.tableSize   // a paused game keeps the table it was started on
                oPlayers = s.octPlayers; oTeams = s.octTeams; oLayout = s.octLayout
                barScratch = s.scratchKitchen
                tSet = s.trickSet
                each = s.gauntEach; mate = s.partner
            }
        } else Store.set("savedGame", "")
        // Practice: a game with its own rules on your own, the free table (8-ball's balls), one trick shot, or the bumper table.
        if (practice) {
            if (mode == GameMode.TRICK || mode == GameMode.BUMPER || mode == GameMode.OCTAGON || mode == GameMode.GAUNTLET) rules = false
            else if (!rules) mode = GameMode.EIGHT_BALL
            cpu = false; clock = 0
        }
        // Pockets can be called in every game on the regulation table (not trick shots or bumper pool).
        if (practice || mode == GameMode.TRICK || mode == GameMode.BUMPER || mode == GameMode.OCTAGON || mode == GameMode.GAUNTLET) call = CallMode.OFF
        oTeams = oTeams && oPlayers == 4
        playingOct = Store.octVariant(oPlayers, oTeams)
        sounds.enabled = sound
        playingCpu = cpu; playingLevel = lvl; playingMode = mode; playingPractice = practice
        el("predToggle").textContent = if (prediction) "Shot prediction: On" else "Shot prediction: Off"
        el("restart").textContent = if (mode == GameMode.TRICK) "Restart match" else "Restart rack"
        el("restart").style.display = "block"
        el("quit").textContent = "Main menu (game is saved)"
        game = Game(mode, cpu, lvl, clock, practice, practiceLevel, aimSens, prediction, text, call, sounds, this, tableSize = size, practiceRules = rules,
            octPlayers = oPlayers, octTeams = oTeams, octLayout = oLayout, scratchKitchen = barScratch, trickSet = tSet, gauntEach = each, partner = mate)
        showGame()
    }

    /** The hands-on tutorial: a practice table with a guide. Your saved game and record are left alone. */
    private fun startTutorial() {
        playingOnline = false
        el("restart").style.display = "block"
        sounds.enabled = sound
        playingCpu = false; playingLevel = level; playingMode = GameMode.EIGHT_BALL; playingPractice = true
        el("predToggle").textContent = "Shot prediction: On"
        el("restart").textContent = "Restart tutorial"
        el("quit").textContent = "Leave tutorial"
        game = Game(GameMode.EIGHT_BALL, false, level, 0, true, 0, aimSens, true, null, CallMode.OFF, sounds, this,
            tutorialMode = true, tableSize = TableSize.NINE)   // its lined-up shot is set up on the 9 ft table
        showGame()
    }

    private fun showGame() {
        el("menu").style.display = "none"
        guide.hide()
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
        game?.onMenuOpened()
        el("pause").style.display = "flex"
    }

    override fun onExitToMenu() { if (playingOnline) leaveOnline() else showMenu() }

    override fun onRpsFinished(winner: Int, rounds: Int) = Store.recordRps(playingCpu, winner, rounds)

    override fun onGameFinished(winner: Int): String {
        if (playingOnline) {
            val g = game ?: return ""
            val m = playingMode.name
            if (online.mySeat < 0) return ""
            Store.recordOnline(m, g.youWon())
            return "Your ${nameOf(playingMode)} online record: ${Store.onlineWins(m)}–${Store.onlineLosses(m)}"
        }
        Store.set("savedGame", "")
        if (game == null || playingPractice) return ""
        val m = playingMode.name
        val cpu = playingCpu
        val lvl = if (cpu) playingLevel else 0
        if (playingMode == GameMode.OCTAGON || playingMode == GameMode.GAUNTLET) {
            val g = game!!
            Store.recordOctagon(playingOct, cpu, lvl, g.youWon(), g.winningSeat(), playingMode.name)
            return Store.octSummary(playingOct, cpu, lvl, plain = true, game = playingMode.name)
        }
        Store.record(m, cpu, lvl, winner)
        val name = nameOf(playingMode)
        if (playingMode == GameMode.CUTTHROAT) return if (cpu) "Your $name record vs two level $lvl CPUs: ${Store.wins(m, true, lvl)}–${Store.losses(m, true, lvl)}"
            else "$name wins: Player 1 ${Store.cutthroatWins(0)} · Player 2 ${Store.cutthroatWins(1)} · Player 3 ${Store.cutthroatWins(2)}"
        return if (cpu) "Your $name record vs level $lvl: ${Store.wins(m, true, lvl)}–${Store.losses(m, true, lvl)}"
        else "$name pass & play: Player 1 ${Store.wins(m, false, 0)} – Player 2 ${Store.losses(m, false, 0)}"
    }

    // ------------------------------------------------------------------ online

    private fun wireOnline() {
        el("netName").addEventListener("input", { online.name = el("netName").value as String; online.announceName() })
        el("lobbyName").addEventListener("input", { online.name = el("lobbyName").value as String; online.announceName(); refreshLobby() })
        el("joinBtn").addEventListener("click", { sounds.unlock(); joinOnline(el("joinCode").value as String) })
        el("joinCode").addEventListener("keydown", { e: dynamic -> if (e.key == "Enter") { sounds.unlock(); joinOnline(el("joinCode").value as String) } })
        el("lobbyLeave").addEventListener("click", { leaveOnline() })
        el("lobbyStart").addEventListener("click", { sounds.unlock(); startOnlineGame(null) })
        el("lobbyCopy").addEventListener("click", { copyText(online.code, "Code copied") })
        el("lobbyShare").addEventListener("click", {
            val link = online.inviteLink()
            val text = "Play pool with me! Open this link, or open the Pool app → Online and type the code ${online.code}"
            try {
                if (navigator.share != null) {
                    val data: dynamic = js("({})")
                    data.title = "Pool"; data.text = text; data.url = link
                    navigator.share(data).catch { _: dynamic -> }
                } else copyText(link, "Invite link copied")
            } catch (e: dynamic) { copyText(link, "Invite link copied") }
        })
        // Opened from an invite link: straight into that room.
        val m = Regex("[?&]room=([A-Za-z]+)").find(window.location.search as String)
        if (m != null) {
            opp = 3; vsCpu = false; save(); refreshMenu()
            joiningByLink = true
            window.setTimeout({ joinOnline(m.groupValues[1]) }, 0)
        }
    }

    private var toastUntil = 0.0
    /** Opened from an invite link (no tutorial question, straight into the room). */
    private var joiningByLink = false

    private fun copyText(t: String, done: String) {
        try {
            navigator.clipboard.writeText(t).then({ _: dynamic -> lobbyNote(done) }, { _: dynamic -> lobbyNote(t) })
        } catch (e: dynamic) { lobbyNote(t) }
    }

    private fun lobbyNote(t: String) {
        el("lobbyStatus").textContent = t
        toastUntil = now() + 2500
        window.setTimeout({ refreshLobby() }, 2600)
    }

    /** The room's settings: what the host picked in the menu. */
    private fun menuSettings(): dynamic {
        val s: dynamic = js("({})")
        s.mode = gameMode.name; s.size = tableSize.name; s.call = callIdx; s.bar = scratchKitchen
        s.clock = if (clockOn) clockSec else 0; s.tset = trickSet
        val gaunt = gameMode == GameMode.GAUNTLET
        s.np = if (gaunt) gPlayers else octPlayers; s.teams = if (gaunt) gTeams else octTeams
        s.layout = octLayout; s.each = gEach; s.partner = gPartner
        return s
    }

    private fun seatsNeeded(s: dynamic): Int = when (GameMode.valueOf(s.mode as String)) {
        GameMode.CUTTHROAT -> 3
        GameMode.OCTAGON, GameMode.GAUNTLET -> if (s.teams == true) 4 else (s.np as Int)
        else -> 2
    }

    private fun describe(s: dynamic): String {
        val mode = GameMode.valueOf(s.mode as String)
        val parts = ArrayList<String>()
        parts += nameOf(mode)
        val pool = mode == GameMode.EIGHT_BALL || mode == GameMode.NINE_BALL || mode == GameMode.CIRCUIT ||
            mode == GameMode.POCKET_WARS || mode == GameMode.CUTTHROAT
        if (pool) parts += (TableSize.fromName(s.size as String) ?: TableSize.DEFAULT).label
        if (mode == GameMode.OCTAGON || mode == GameMode.GAUNTLET)
            parts += if (s.teams == true) "2 v 2" else "${s.np} players"
        if (mode == GameMode.OCTAGON) parts += com.poolgame.app.core.Table.OCT_LAYOUTS[(s.layout as Int).coerceIn(0, com.poolgame.app.core.Table.OCT_LAYOUTS.size - 1)]
        if (mode == GameMode.GAUNTLET) parts += "${s.each} balls each"
        if (mode == GameMode.TRICK) parts += if (s.tset == 1) "Classics" else "Gap jumps"
        if (pool && (s.call as Int) > 0) parts += if (s.call == 1) "call 8 & 9" else "call every shot"
        if (pool && s.bar == true) parts += "bar rules"
        if ((s.clock as Int) > 0) parts += "${s.clock} s shot clock"
        return parts.joinToString(" · ")
    }

    private fun hostOnline() {
        if (online.name.isEmpty()) { online.name = (el("netName").value as String) }
        val s = menuSettings()
        online.hostRoom(s, seatsNeeded(s))
        setRoomInUrl(online.code)
        showLobby()
    }

    private fun joinOnline(code: String) {
        if (online.name.isEmpty()) online.name = el("netName").value as String
        online.joinRoom(code)
        if (online.code.isNotEmpty()) setRoomInUrl(online.code)
        showLobby()
    }

    private fun leaveOnline() {
        online.leave()
        playingOnline = false
        setRoomInUrl(null)
        el("lobby").style.display = "none"
        if (game != null) showMenu() else refreshMenu()
    }

    private fun setRoomInUrl(code: String?) {
        try {
            val loc = window.location
            val keep = Regex("[?&](broker=[^&]+)").find(loc.search as String)?.groupValues?.get(1)
            val q = listOfNotNull(code?.let { "room=$it" }, keep).joinToString("&")
            window.history.replaceState(null, "", loc.pathname + (if (q.isEmpty()) "" else "?$q"))
        } catch (e: dynamic) { }
    }

    private fun showLobby() {
        el("lobby").style.display = "flex"
        el("lobbyName").value = online.name
        refreshLobby()
    }

    private fun refreshLobby() {
        if (el("lobby").style.display != "flex") return
        el("lobbyCode").textContent = online.code.ifEmpty { "·····" }
        if (now() > toastUntil) el("lobbyStatus").textContent = when (online.status) {
            "connecting" -> "Connecting…"
            "reconnecting" -> "Connection lost — reconnecting…"
            "failed" -> "Can't reach the game server — check your connection"
            else -> if (online.isHost) "Your game is open — send the code to your friends" else if (online.cfg == null) "Looking for the game…" else "Connected"
        }
        val c = online.cfg
        el("lobbyGame").textContent = if (c != null) describe(c.s) else ""
        val seats = online.seats()
        val need = online.need()
        val html = StringBuilder()
        for (i in 0 until maxOf(need, seats.size)) {
            val s = seats.getOrNull(i)
            if (s == null) { html.append("<div class='seat empty'><span class='dot'></span>Waiting for a player…</div>"); continue }
            val on = online.seatOnline(i)
            val who = esc(s.second) + (if (s.first == online.netId) " (you)" else "") + (if (i == 0) " · host" else "")
            html.append("<div class='seat${if (on) "" else " off"}'><span class='dot'></span>$who</div>")
        }
        el("lobbySeats").innerHTML = html.toString()
        val full = seats.size >= need && c != null
        el("lobbyStart").style.display = if (online.isHost) "block" else "none"
        el("lobbyStart").disabled = !(full && online.status == "online" && (0 until need).all { online.seatOnline(it) })
        el("lobbyHelp").textContent = when {
            online.isHost && !full -> "Waiting for ${need - seats.size} more ${if (need - seats.size == 1) "player" else "players"}. " +
                "Tap Send invite, or they can open Pool → Online and type the code."
            online.isHost -> "Everyone's here. Start when you're ready."
            c == null -> ""
            online.mySeat < 0 -> "This game is full — you'll be able to watch once it starts."
            else -> "You're in. Waiting for the host to start the game…"
        }
    }

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun onLobby() = refreshLobby()

    override fun onNetClosed(why: String) {
        online.leave()
        playingOnline = false
        setRoomInUrl(null)
        el("lobby").style.display = "none"
        if (game != null) showMenu()
        window.setTimeout({ window.alert(why) }, 50)
    }

    override fun onNetStart(act: dynamic) { startOnlineGame(act) }

    /** Start (host: [act] null) or join (the room's act) an online game with the room's settings. */
    private fun startOnlineGame(act: dynamic) {
        val c = online.cfg ?: return
        val s = c.s
        val mode = GameMode.valueOf(s.mode as String)
        val size = TableSize.fromName(s.size as String) ?: TableSize.DEFAULT
        val seats = online.seats()
        val n = seatsNeeded(s)
        val names = Array(n) { i -> seats.getOrNull(i)?.second?.trim().orEmpty() }
        for (i in names.indices) {
            if (names[i].isEmpty() || names[i].equals("you", true) || names.count { it.equals(names[i], true) } > 1) names[i] = "Player ${i + 1}"
        }
        if (act == null) online.markStarted()
        playingOnline = true
        sounds.enabled = sound
        playingCpu = false; playingLevel = 0; playingMode = mode; playingPractice = false
        playingOct = Store.octVariant(s.np as Int, s.teams == true)
        el("lobby").style.display = "none"
        el("predToggle").textContent = if (prediction) "Shot prediction: On" else "Shot prediction: Off"
        el("restart").style.display = "none"
        el("quit").textContent = "Leave the game"
        val pool = mode == GameMode.EIGHT_BALL || mode == GameMode.NINE_BALL || mode == GameMode.CIRCUIT ||
            mode == GameMode.POCKET_WARS || mode == GameMode.CUTTHROAT
        val call = if (pool) CallMode.values()[(s.call as Int).coerceIn(0, 2)] else CallMode.OFF
        game = Game(mode, false, level, s.clock as Int, false, 0, aimSens, prediction, null, call, sounds, this, tableSize = size,
            practiceRules = false, octPlayers = s.np as Int, octTeams = s.teams == true && (s.np as Int) == 4,
            octLayout = s.layout as Int, scratchKitchen = s.bar == true && pool, trickSet = s.tset as Int,
            gauntEach = s.each as Int, partner = s.partner as Int,
            online = online, netStart = act, seatNames = names)
        showGame()
    }

    private companion object {
        /** Which opponent each button picks (0 computer, 1 pass & play, 2 practice, 3 online). */
        val OPP_OF_BUTTON = listOf(0, 1, 3, 2)
        /** The game buttons, in order. */
        val GAMES = listOf(GameMode.EIGHT_BALL, GameMode.NINE_BALL, GameMode.CIRCUIT, GameMode.POCKET_WARS, GameMode.CUTTHROAT,
            GameMode.BUMPER, GameMode.OCTAGON, GameMode.GAUNTLET, GameMode.TRICK)
    }
}
