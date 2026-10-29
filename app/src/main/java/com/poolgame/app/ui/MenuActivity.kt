package com.poolgame.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.poolgame.app.core.GameMode
import com.poolgame.app.core.TableSize

class MenuActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("pool", Context.MODE_PRIVATE) }
    private var mode = GameMode.EIGHT_BALL
    private var vsCpu = true
    private var level = 5
    private var sound = true
    private var practice = false
    private var opp = 0                    // 0 the computer, 1 pass & play, 2 practice on your own
    private var practiceRules = true       // practice a pool game with its rules (false: the free table)
    private var practiceLevel = 0
    private var aimSens = 5
    private var callMode = 0
    private var scratchKitchen = false
    private var trickSet = 0
    private var tableSize = TableSize.DEFAULT
    private var prediction = true
    private var resumeBtn: TextView? = null
    private var clockOn = false
    private var clockSec = 30
    private var askTutorial = true
    // The octagon table: 2, 3 or 4 players, two teams of two (with 4), and which bumper layout.
    private var octPlayers = 4
    private var octTeams = false
    /** The Gauntlet: players (2 to 4), teams, red's partner in 2 v 2 (1 across, 2 blue, 3 yellow), and balls each. */
    private var gPlayers = 4
    private var gTeams = false
    private var gPartner = 1
    private var gEach = 7
    private var octLayout = 0
    private val stats by lazy { Stats(this) }
    private var recordText: TextView? = null

    // Your own voice for table fouls
    private var recorder: VoiceRecorder? = null
    private var player: MediaPlayer? = null
    private var voiceStatus: TextView? = null
    private var recordBtn: TextView? = null
    private var playBtn: TextView? = null
    private var deleteBtn: TextView? = null

    private val levelNames = arrayOf(
        "Beginner", "Novice", "Casual", "Bar Player", "Club Player",
        "Skilled", "Advanced", "Expert", "Master", "Pro"
    )

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = GameMode.values().firstOrNull { it.name == prefs.getString("mode", "") } ?: GameMode.EIGHT_BALL
        vsCpu = prefs.getBoolean("vsCpu", true)
        level = prefs.getInt("level", 5).coerceIn(1, 10)
        sound = prefs.getBoolean("sound", true)
        practiceRules = prefs.getBoolean("practiceRules", true)
        if (prefs.contains("opp")) opp = prefs.getInt("opp", 0).coerceIn(0, 2)
        else if (prefs.getBoolean("practice", false)) {
            // From before practice was an opponent choice: Practice was its own game type (free table, trick shot or bumper).
            opp = 2
            when (prefs.getInt("practiceKind", if (prefs.getBoolean("practiceTrick", false)) 1 else 0)) {
                1 -> mode = GameMode.TRICK
                2 -> mode = GameMode.BUMPER
                else -> { mode = GameMode.EIGHT_BALL; practiceRules = false }
            }
        } else opp = if (vsCpu) 0 else 1
        practice = opp == 2
        vsCpu = opp == 0
        practiceLevel = prefs.getInt("practiceLevel", 0).coerceIn(0, com.poolgame.app.core.Trick.LEVELS.size - 1)
        aimSens = prefs.getInt("aimSens", 5).coerceIn(1, 10)
        callMode = prefs.getInt("callMode", 0).coerceIn(0, 2)
        scratchKitchen = prefs.getBoolean("scratchKitchen", false)
        trickSet = prefs.getInt("trickSet", 0).coerceIn(0, 1)
        tableSize = TableSize.fromName(prefs.getString("tableSize", null)) ?: TableSize.DEFAULT
        prediction = prefs.getBoolean("prediction", true)
        clockOn = prefs.getBoolean("clockOn", false)
        clockSec = prefs.getInt("clockSec", 30).coerceIn(10, 120)
        askTutorial = prefs.getBoolean("askTutorial", true)
        octPlayers = prefs.getInt("octPlayers", 4).coerceIn(2, 4)
        octTeams = prefs.getBoolean("octTeams", false) && octPlayers == 4
        gPlayers = prefs.getInt("gPlayers", 4).coerceIn(2, 4)
        gTeams = prefs.getBoolean("gTeams", false) && gPlayers == 4
        gPartner = prefs.getInt("gPartner", 1).coerceIn(1, 3)
        gEach = prefs.getInt("gEach", 7).coerceIn(6, 8)
        octLayout = prefs.getInt("octLayout", 0).coerceIn(0, com.poolgame.app.core.Table.OCT_LAYOUTS.size - 1)
        build()
        // Each time the app is opened (not when the screen is merely recreated), offer the tutorial.
        if (savedInstanceState == null && askTutorial) offerTutorial()
    }

    /** "Want a quick tutorial?", with a box to stop asking (it can be turned back on under CONTROLS). */
    private fun offerTutorial() {
        val box = CheckBox(this).apply {
            text = "Don't ask again"
            setTextColor(0xFFDDE3E8.toInt())
            textSize = 14f
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dpi(20f), dpi(4f), dpi(20f), 0)
            addView(box)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Want a quick tutorial?")
            .setMessage("A one-minute, hands-on tour of the controls on a real table. You can also find it any time " +
                "under CONTROLS in this menu.")
            .setView(wrap)
            .setPositiveButton("Show me") { _, _ -> rememberAsk(box.isChecked); startTutorial() }
            .setNegativeButton("Not now") { _, _ -> rememberAsk(box.isChecked) }
            .setOnCancelListener { rememberAsk(box.isChecked) }
            .show()
    }

    private var askSwitch: Switch? = null

    private fun rememberAsk(dontAsk: Boolean) {
        if (!dontAsk) return
        askTutorial = false
        prefs.edit().putBoolean("askTutorial", false).apply()
        askSwitch?.isChecked = false
    }

    private fun startTutorial() {
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_TUTORIAL, true)
            putExtra(GameActivity.EXTRA_SOUND, sound)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshRecord()
        val saved = GameActivity.savedSummary(this)
        resumeBtn?.apply {
            visibility = if (saved != null) View.VISIBLE else View.GONE
            text = "RESUME · ${saved ?: ""}"
        }
    }

    private fun refreshRecord() {
        val t = recordText ?: return
        val name = Stats.modeName(mode)
        t.text = (if (mode == GameMode.OCTAGON) {
            stats.octSummary(Stats.octVariant(octPlayers, octTeams), vsCpu, level)
        } else if (mode == GameMode.GAUNTLET) {
            stats.octSummary(Stats.octVariant(gPlayers, gTeams), vsCpu, level, GameMode.GAUNTLET)
        } else if (mode == GameMode.CUTTHROAT && !vsCpu) {
            "$name pass & play wins:  Player 1  ${stats.cutthroatWins(0)} · Player 2  ${stats.cutthroatWins(1)} · Player 3  ${stats.cutthroatWins(2)}"
        } else if (mode == GameMode.CUTTHROAT) {
            "$name vs two level $level CPUs:  ${stats.wins(mode, true, level)} W – ${stats.losses(mode, true, level)} L\n" +
                "$name vs all levels:  ${stats.totalWinsVsCpu(mode)} W – ${stats.totalLossesVsCpu(mode)} L"
        } else if (vsCpu) {
            "$name vs level $level:  ${stats.wins(mode, true, level)} W – ${stats.losses(mode, true, level)} L\n" +
                "$name vs all levels:  ${stats.totalWinsVsCpu(mode)} W – ${stats.totalLossesVsCpu(mode)} L"
        } else {
            "$name pass & play:  Player 1  ${stats.wins(mode, false, 0)} – ${stats.losses(mode, false, 0)}  Player 2"
        }) + "\n\n" + stats.rpsSummary(vsCpu)
    }

    private fun save() {
        prefs.edit().putString("mode", mode.name).putBoolean("vsCpu", vsCpu)
            .putInt("level", level).putBoolean("sound", sound)
            .putBoolean("clockOn", clockOn).putInt("clockSec", clockSec)
            .putBoolean("practice", practice)
            .putInt("opp", opp).putBoolean("practiceRules", practiceRules).putInt("practiceLevel", practiceLevel)
            .putInt("aimSens", aimSens).putInt("callMode", callMode).putBoolean("scratchKitchen", scratchKitchen).putInt("trickSet", trickSet).putString("tableSize", tableSize.name)
            .putBoolean("prediction", prediction)
            .putInt("octPlayers", octPlayers).putBoolean("octTeams", octTeams).putInt("octLayout", octLayout)
            .putInt("gPlayers", gPlayers).putBoolean("gTeams", gTeams).putInt("gPartner", gPartner).putInt("gEach", gEach).apply()
        refreshRecord()
    }

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

    private fun build() {
        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF0F3D27.toInt(), 0xFF0B2419.toInt(), 0xFF0A0F12.toInt())
        )
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24f), dpi(20f), dpi(24f), dpi(20f))
        }
        scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xE6151B20.toInt(), dp(20f), dpi(1f), 0x33FFFFFF)
            setPadding(dpi(28f), dpi(22f), dpi(28f), dpi(22f))
        }
        col.addView(card, LinearLayout.LayoutParams(minOf(dpi(560f), resources.displayMetrics.widthPixels - dpi(48f)), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })

        card.addView(label("POOL", 38f, Color.WHITE, true).apply { letterSpacing = 0.25f; gravity = Gravity.CENTER })
        card.addView(label("8-Ball · 9-Ball · Circuit Pool · Pocket Wars · Cutthroat · bumper pool · octagon bumper · trick shots", 14f, 0xFF9AA7B1.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dpi(14f)) })

        // Game type
        card.addView(section("GAME"))
        var matchBlockRef: View? = null
        var callBlockRef: View? = null
        var sizeBlockRef: View? = null
        var skillBlockRef: View? = null
        var skillTitleRef: TextView? = null
        var refresh: () -> Unit = {}
        fun help(t: String) = label(t, 12.5f, 0xFF8A97A1.toInt()).apply { setPadding(0, dpi(8f), 0, 0); setLineSpacing(0f, 1.15f) }
        val trickHelp = help("Trick shots, taking turns with the computer or a friend: whoever makes each level in " +
            "fewer shots wins it, and most levels wins the match. Gap jumps: half the table's missing between the " +
            "break area and the far end; jump the cue ball across and sink the 1. Classics: famous trick shots on an " +
            "ordinary table, mostly getting the 1 in round, over or between other balls.")
        val bumperHelp = help("Bumper pool: a small table with a cup at each end and twelve bumpers in the way. " +
            "No cue ball: you shoot your own five balls (red, or white) into the far cup, your marked (spotted) ball " +
            "first. Both marked balls go at once to open. Your ball in your own cup, a ball off the table or a jump " +
            "shot is a foul: your opponent drops two of theirs in. First to sink all five wins.")
        val circuitHelp = help("Circuit Pool: each player is secretly dealt three of the fifteen balls; the other nine " +
            "are neutral. Hit any ball first. Sinking your own or a neutral ball keeps you shooting; sinking one of your " +
            "opponent's counts for them and ends your turn. You see your own targets and how many your opponent has " +
            "down, never which. First to sink all three wins.")
        val warsHelp = help("Pocket Wars: all six pockets start open. Sink a ball in an open pocket to claim it (it's " +
            "ringed in your colour); every ball scores its number. Your own pockets keep you shooting; a ball in your " +
            "opponent's pocket scores for them and ends your turn, unless you banked it in off a rail, which steals the " +
            "pocket. A foul spots the balls back. First to 61 points wins.")
        val cutHelp = help("Cutthroat: three players, three groups — 1–5, 6–10 and 11–15. After the break the table is " +
            "open: the first player to sink a ball picks any group, the next picks one of the other two, and the last " +
            "player gets what's left. Keep your own balls up and sink everyone else's; any ball you sink keeps you " +
            "shooting. A foul gives the next player ball in hand and each opponent one of their balls back. The last " +
            "player with a ball on the table wins. Against the computer you play two CPUs, both at the level below; pass & " +
            "play is for three people sharing the phone.")
        val octHelp = help("Octagon bumper: a big eight-sided bumper table with a cup on every side and ramps instead of " +
            "cushions — 45°, 6″ high, with a hard corner at the bottom. A ball thumps into one, loses speed and rolls back " +
            "down; hit it hard enough and it goes over the top and off the table. Two to four players (or two teams of two), five balls each: you shoot into the cup across from you, " +
            "your marked ball first. Every marked ball goes at once to open, in any direction (no jump shots). Your ball " +
            "in a cup that isn't yours, a ball off the table or a jump shot is a foul: everyone else (in teams, the other " +
            "team) drops one in. First to sink all five wins; in teams, a player whose five are down shoots their " +
            "partner's, and the team wins when all ten are down.")
        val gauntHelp = help("The Gauntlet: an 80″ square bumper table with cushions, a cup in the middle of each side and 68 " +
            "bumpers. Nothing goes in from where the balls start in one stroke. In the middle is the Cage: two loop-de-loops " +
            "crossing over a hanging dish. Your own curved ramp points straight at your cup: hit it right, jump the gap onto " +
            "the dish and it flings the ball on toward the cup. Through a loop, a deflector bumper sends it at a cup. A cup " +
            "only catches a ball that's not going too fast. Two to four players or 2 v 2, 6 to 8 balls each, the octagon's " +
            "rules: your marked ball first, every marked ball goes at once to open, and a foul lets everyone else drop one in.")
        val modes = listOf(GameMode.EIGHT_BALL, GameMode.NINE_BALL, GameMode.CIRCUIT, GameMode.POCKET_WARS,
            GameMode.CUTTHROAT, GameMode.BUMPER, GameMode.OCTAGON, GameMode.GAUNTLET, GameMode.TRICK)
        card.addView(segmentedGrid(listOf("8-Ball", "9-Ball", "Circuit", "Pocket Wars", "Cutthroat", "Bumper", "Octagon", "Gauntlet", "Trick"), 3,
            modes.indexOf(mode).coerceAtLeast(0)) { mode = modes[it]; refresh(); save() })
        for (h in listOf(circuitHelp, warsHelp, cutHelp, trickHelp, bumperHelp, octHelp, gauntHelp)) card.addView(h)
        // Trick shots: which set of levels a match plays.
        val trickSetBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trickSetBlock.addView(section("TRICK SHOTS"))
        trickSetBlock.addView(segmented(com.poolgame.app.core.Trick.SETS, trickSet) { trickSet = it; save() })
        card.addView(trickSetBlock)

        // The octagon table: how many play, and which bumpers (with a picture of the table).
        val octBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        octBlock.addView(section("PLAYERS"))
        val playerOpts = listOf("2", "3", "4", "2 v 2")
        val octPlayersHelp = help("")
        val preview = OctPreview(this)
        fun refreshOct() {
            preview.layout = octLayout
            preview.players = octPlayers
            octPlayersHelp.text = when {
                octTeams -> "Two teams: red & white against blue & yellow, partners across the table from each other. " +
                    (if (vsCpu) "You're red, with CPU 1 as your partner." else "")
                vsCpu -> "You against ${if (octPlayers == 2) "one CPU" else "${octPlayers - 1} CPUs"}, all at the skill below."
                else -> "$octPlayers people sharing the phone."
            } + " Turns go round the table: red, blue, white, yellow."
        }
        octBlock.addView(segmented(playerOpts, if (octTeams) 3 else octPlayers - 2) {
            octTeams = it == 3; octPlayers = if (it == 3) 4 else it + 2; refreshOct(); save()
        })
        octBlock.addView(octPlayersHelp)
        octBlock.addView(section("BUMPER LAYOUT"))
        val layoutHelp = help("")
        octBlock.addView(segmented(com.poolgame.app.core.Table.OCT_LAYOUTS.toList(), octLayout) {
            octLayout = it; layoutHelp.text = com.poolgame.app.core.Table.OCT_LAYOUT_HELP[it]; refreshOct(); save()
        })
        layoutHelp.text = com.poolgame.app.core.Table.OCT_LAYOUT_HELP[octLayout]
        octBlock.addView(layoutHelp)
        octBlock.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(190f)).apply { topMargin = dpi(8f) })
        refreshOct()
        card.addView(octBlock)

        // The Gauntlet: how many play, who partners whom in 2 v 2, and how many balls each.
        val gBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gBlock.addView(section("PLAYERS"))
        val gPlayersHelp = help("")
        val partnerBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshG() {
            val mate = when (gPartner) { 1 -> "white (across the table)"; 2 -> "blue (beside you)"; else -> "yellow (beside you)" }
            gPlayersHelp.text = when {
                gTeams -> "Two teams of two: red & $mate against the other two. Turns always swap teams. " +
                    (if (vsCpu) "You're red, with a CPU as your partner." else "")
                vsCpu -> "You against ${if (gPlayers == 2) "one CPU" else "${gPlayers - 1} CPUs"}, all at the skill below."
                else -> "$gPlayers people sharing the phone."
            }
            partnerBlock.visibility = if (gTeams) View.VISIBLE else View.GONE
        }
        gBlock.addView(segmented(playerOpts, if (gTeams) 3 else gPlayers - 2) {
            gTeams = it == 3; gPlayers = if (it == 3) 4 else it + 2; refreshG(); save()
        })
        gBlock.addView(gPlayersHelp)
        partnerBlock.addView(section("PARTNERS"))
        partnerBlock.addView(segmented(listOf("Across", "Beside: blue", "Beside: yellow"), gPartner - 1) {
            gPartner = it + 1; refreshG(); save()
        })
        gBlock.addView(partnerBlock)
        gBlock.addView(section("BALLS EACH"))
        gBlock.addView(segmented(listOf("6", "7", "8"), gEach - 6) { gEach = it + 6; save() })
        refreshG()
        card.addView(gBlock)

        // Opponent: the computer, a friend, or practice on your own.
        card.addView(section("OPPONENT"))
        card.addView(segmented(listOf("Computer", "Pass & Play", "Practice"), opp) {
            opp = it; vsCpu = it == 0; practice = it == 2; refresh(); save()
        })

        // Practice: with the game's rules or the free table; a trick shot (pick which); the bumper table.
        val practiceBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rulesBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val levelBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        practiceBlock.addView(section("PRACTICE"))
        val rulesHelp = help("")
        rulesBlock.addView(segmented(listOf("With the rules", "Free table"), if (practiceRules) 0 else 1) {
            practiceRules = it == 0; refresh(); save()
        })
        rulesBlock.addView(rulesHelp)
        practiceBlock.addView(rulesBlock)
        val bumperPracticeHelp = help("The bumper table on your own, no rules: tap any ball to shoot it, hold one to move it.")
        val octPracticeHelp = help("The octagon table on your own, with every colour's balls and the layout above, no rules: tap any ball to shoot it, hold one to move it.")
        practiceBlock.addView(octPracticeHelp)
        practiceBlock.addView(bumperPracticeHelp)
        val names = com.poolgame.app.core.Trick.LEVELS.map { it.name }
        levelBlock.addView(section("WHICH TRICK SHOT"))
        // Two rows of three; one selection across both.
        val rows = ArrayList<LinearLayout>()
        val cells = ArrayList<TextView>()
        fun paintLevels() {
            cells.forEachIndexed { i, v ->
                v.background = if (i == practiceLevel) rounded(0xFF2F8F5B.toInt(), dp(9f)) else null
                v.setTextColor(if (i == practiceLevel) Color.WHITE else 0xFF9AA7B1.toInt())
            }
        }
        for (r in 0 until (names.size + 2) / 3) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(0xFF0E1215.toInt(), dp(12f))
                setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
            }
            for (c in 0 until 3) {
                val i = r * 3 + c
                if (i >= names.size) break
                val v = label(names[i], 14f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { practiceLevel = i; paintLevels(); save() }
                }
                cells += v
                row.addView(v, LinearLayout.LayoutParams(0, dpi(40f), 1f))
            }
            rows += row
            levelBlock.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = dpi(6f) })
        }
        paintLevels()
        practiceBlock.addView(levelBlock)
        card.addView(practiceBlock)

        refresh = {
            val poolTable = mode != GameMode.TRICK && mode != GameMode.BUMPER && mode != GameMode.OCTAGON && mode != GameMode.GAUNTLET
            practiceBlock.visibility = if (practice) View.VISIBLE else View.GONE
            rulesBlock.visibility = if (practice && poolTable) View.VISIBLE else View.GONE
            levelBlock.visibility = if (practice && mode == GameMode.TRICK) View.VISIBLE else View.GONE
            bumperPracticeHelp.visibility = if (practice && mode == GameMode.BUMPER) View.VISIBLE else View.GONE
            octPracticeHelp.visibility = if (practice && (mode == GameMode.OCTAGON || mode == GameMode.GAUNTLET)) View.VISIBLE else View.GONE
            octPracticeHelp.text = if (mode == GameMode.GAUNTLET) "The Gauntlet on your own, with every colour's balls, no rules: tap any ball to shoot it, hold one to move it."
                else "The octagon table on your own, with every colour's balls and the layout above, no rules: tap any ball to shoot it, hold one to move it."
            octHelp.visibility = if (mode == GameMode.OCTAGON) View.VISIBLE else View.GONE
            octBlock.visibility = if (mode == GameMode.OCTAGON) View.VISIBLE else View.GONE
            gauntHelp.visibility = if (mode == GameMode.GAUNTLET) View.VISIBLE else View.GONE
            gBlock.visibility = if (mode == GameMode.GAUNTLET) View.VISIBLE else View.GONE
            refreshG()
            refreshOct()
            rulesHelp.text = if (practiceRules) "On your own with the ${Stats.modeName(mode)} rules: you take every shot, and each " +
                "one is judged as in a game (fouls give you ball in hand). Retry a shot, re-rack, or hold a ball to move it."
                else "No rules: plain red and yellow balls (and the black). Hold any ball to move it anywhere."
            matchBlockRef?.visibility = if (practice) View.GONE else View.VISIBLE
            skillBlockRef?.visibility = if (vsCpu) View.VISIBLE else View.GONE
            callBlockRef?.visibility = if (poolTable) View.VISIBLE else View.GONE
            trickHelp.visibility = if (mode == GameMode.TRICK) View.VISIBLE else View.GONE
            trickSetBlock.visibility = if (mode == GameMode.TRICK && !practice) View.VISIBLE else View.GONE
            bumperHelp.visibility = if (mode == GameMode.BUMPER) View.VISIBLE else View.GONE
            circuitHelp.visibility = if (mode == GameMode.CIRCUIT) View.VISIBLE else View.GONE
            warsHelp.visibility = if (mode == GameMode.POCKET_WARS) View.VISIBLE else View.GONE
            cutHelp.visibility = if (mode == GameMode.CUTTHROAT) View.VISIBLE else View.GONE
            skillTitleRef?.text = when {
                mode == GameMode.CUTTHROAT -> "COMPUTER SKILL (BOTH CPUs)"
                mode == GameMode.OCTAGON && octPlayers > 2 -> "COMPUTER SKILL (ALL CPUs)"
                mode == GameMode.GAUNTLET && gPlayers > 2 -> "COMPUTER SKILL (ALL CPUs)"
                else -> "COMPUTER SKILL"
            }
            // Trick shots are always on the 9 ft table (with its gap) and bumper pool on its own table.
            sizeBlockRef?.visibility = if (poolTable) View.VISIBLE else View.GONE
        }

        // Table size (every game but trick shots and bumper pool)
        val sizeBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sizeBlock.addView(section("TABLE SIZE"))
        val sizes = TableSize.values()
        val sizeHelp = label("", 12f, 0xFF8A97A1.toInt()).apply { setPadding(0, dpi(6f), 0, 0) }
        fun refreshSize() { sizeHelp.text = tableSize.description }
        sizeBlock.addView(segmented(sizes.map { it.label }, sizes.indexOf(tableSize)) {
            tableSize = sizes[it]; refreshSize(); save()
        })
        sizeBlock.addView(sizeHelp)
        refreshSize()
        card.addView(sizeBlock)
        sizeBlockRef = sizeBlock

        // Everything below only applies to real games, not practice.
        val matchBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val skillBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        skillBlockRef = skillBlock

        // Skill
        val skillLabel = label("", 15f, Color.WHITE, true)
        fun refreshSkill() { skillLabel.text = "Level $level · ${levelNames[level - 1]}" }
        refreshSkill()
        val skillTitle = section("COMPUTER SKILL")
        skillTitleRef = skillTitle
        skillBlock.addView(skillTitle)
        skillBlock.addView(skillLabel)
        val seek = SeekBar(this).apply {
            max = 9
            progress = level - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { level = p + 1; refreshSkill(); save() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        skillBlock.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40f)))
        matchBlock.addView(skillBlock)

        // Called pocket (not used for trick shots)
        val callBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        callBlock.addView(section("CALL POCKET"))
        callBlock.addView(segmented(listOf("Off", "8 & 9 only", "Every shot"), callMode) { callMode = it; save() })
        callBlock.addView(label("Tap a pocket to call it. A ball in the wrong pocket is slop: your turn ends " +
            "(the 8 in the wrong pocket loses; a 9 in the wrong pocket is respotted). Circuit, Pocket Wars and Cutthroat " +
            "use Every shot only (in Pocket Wars a slopped ball is spotted and scores nothing).", 12f, 0xFF8A97A1.toInt())
            .apply { setPadding(0, dpi(6f), 0, 0) })
        // Bar rules: ball in hand in the kitchen after a scratch.
        callBlock.addView(section("AFTER A SCRATCH"))
        callBlock.addView(segmented(listOf("Ball in hand anywhere", "In the kitchen (bar rules)"), if (scratchKitchen) 1 else 0) {
            scratchKitchen = it == 1; save()
        })
        callBlock.addView(label("Bar rules: after a scratch the next player places the cue ball behind the head string and " +
            "must shoot out of the kitchen: a ball behind the line can't be hit until the cue ball has crossed it. " +
            "Other fouls still give ball in hand anywhere.", 12f, 0xFF8A97A1.toInt())
            .apply { setPadding(0, dpi(6f), 0, 0) })
        matchBlock.addView(callBlock)
        callBlockRef = callBlock

        // Shot clock
        matchBlock.addView(section("SHOT CLOCK"))
        val clockLabel = label("", 15f, Color.WHITE, true)
        val clockSeek = SeekBar(this)
        fun refreshClock() {
            clockLabel.text = if (clockOn) "$clockSec seconds per shot" else "Off"
            clockSeek.isEnabled = clockOn
            clockSeek.alpha = if (clockOn) 1f else 0.35f
        }
        @Suppress("DEPRECATION")
        val clockSwitch = Switch(this).apply {
            text = "Time limit for each shot (vs CPU and pass & play)"
            setTextColor(0xFFDDE3E8.toInt())
            textSize = 14f
            isChecked = clockOn
            setOnCheckedChangeListener { _, c -> clockOn = c; refreshClock(); save() }
        }
        matchBlock.addView(clockSwitch)
        matchBlock.addView(clockLabel)
        clockSeek.max = 22   // 10..120 s in 5 s steps
        clockSeek.progress = (clockSec - 10) / 5
        clockSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { clockSec = 10 + p * 5; refreshClock(); save() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        matchBlock.addView(clockSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40f)))
        refreshClock()

        // Record
        matchBlock.addView(section("YOUR RECORD"))
        recordText = label("", 14f, 0xFFDDE3E8.toInt()).apply { setLineSpacing(0f, 1.25f) }
        matchBlock.addView(recordText)
        matchBlock.addView(label("Reset record", 13f, 0xFFEB5757.toInt()).apply {
            setPadding(0, dpi(8f), 0, dpi(4f))
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MenuActivity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Reset your record?")
                    .setMessage("This clears all wins and losses for every game type and level, and your rock, paper, scissors stats.")
                    .setPositiveButton("Reset") { _, _ -> stats.reset(); refreshRecord() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        refreshRecord()
        card.addView(matchBlock)
        matchBlockRef = matchBlock
        refresh()

        // Controls: the tutorial, the screen guide, and the control settings (apply to every game type)
        card.addView(section("CONTROLS"))
        card.addView(label("Learn what every button, slider and gesture on the game screen does.", 12.5f, 0xFF8A97A1.toInt())
            .apply { setLineSpacing(0f, 1.15f); setPadding(0, 0, 0, dpi(8f)) })
        val learnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tutBtn = label("▶ Tutorial", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF2F8F5B.toInt(), dp(12f))
            setOnClickListener { startTutorial() }
        }
        val guideBtn = label("Screen guide", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF39434C.toInt(), dp(12f))
            setOnClickListener { startActivity(Intent(this@MenuActivity, ControlsActivity::class.java)) }
        }
        learnRow.addView(tutBtn, LinearLayout.LayoutParams(0, dpi(46f), 1f))
        learnRow.addView(guideBtn, LinearLayout.LayoutParams(0, dpi(46f), 1f).apply { leftMargin = dpi(8f) })
        card.addView(learnRow)
        @Suppress("DEPRECATION")
        val askSw = Switch(this).apply {
            text = "Offer the tutorial when the app opens"
            setTextColor(0xFFDDE3E8.toInt())
            textSize = 14f
            isChecked = askTutorial
            setOnCheckedChangeListener { _, c -> askTutorial = c; prefs.edit().putBoolean("askTutorial", c).apply() }
        }
        askSwitch = askSw
        card.addView(askSw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f); bottomMargin = dpi(8f) })
        val sensLabel = label("", 15f, Color.WHITE, true)
        fun refreshSens() { sensLabel.text = "Fine-aim wheel speed: $aimSens" + if (aimSens == 5) " (default)" else if (aimSens < 5) " (finer)" else " (faster)" }
        refreshSens()
        card.addView(sensLabel)
        card.addView(SeekBar(this).apply {
            max = 9
            progress = aimSens - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { aimSens = p + 1; refreshSens(); save() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40f)))
        @Suppress("DEPRECATION")
        val predSwitch = Switch(this).apply {
            text = "Show shot prediction (where the balls will go)"
            setTextColor(0xFFDDE3E8.toInt())
            textSize = 14f
            isChecked = prediction
            setOnCheckedChangeListener { _, c -> prediction = c; save() }
        }
        card.addView(predSwitch)

        // Sound
        @Suppress("DEPRECATION")
        val soundSwitch = Switch(this).apply {
            text = "Sound effects"
            setTextColor(0xFFDDE3E8.toInt())
            textSize = 15f
            isChecked = sound
            setOnCheckedChangeListener { _, c -> sound = c; save() }
        }
        card.addView(soundSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })

        // Your voice for table fouls
        card.addView(section("FOUL VOICE"))
        card.addView(label("Plays when someone hits nothing, hits the wrong ball first, or gets no rail with nothing potted. " +
            "Tap Record and say it (up to 3 seconds).", 12.5f, 0xFF8A97A1.toInt()).apply { setLineSpacing(0f, 1.15f) })
        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(8f), 0, 0) }
        fun pill(t: String, color: Int, onClick: () -> Unit) = label(t, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(color, dp(12f))
            setOnClickListener { onClick() }
        }
        recordBtn = pill("● Record", 0xFFB23A3A.toInt()) { onRecordTapped() }
        playBtn = pill("▶ Play", 0xFF39434C.toInt()) { playVoice() }
        deleteBtn = pill("Delete", 0xFF39434C.toInt()) { Sfx.voiceFile(this).delete(); refreshVoice(null) }
        for ((i, b) in listOf(recordBtn!!, playBtn!!, deleteBtn!!).withIndex()) {
            voiceRow.addView(b, LinearLayout.LayoutParams(0, dpi(44f), 1f).apply { if (i > 0) leftMargin = dpi(8f) })
        }
        card.addView(voiceRow)
        voiceStatus = label("", 13f, 0xFFDDE3E8.toInt()).apply { setPadding(0, dpi(6f), 0, 0) }
        card.addView(voiceStatus)
        refreshVoice(null)

        // Resume a paused game
        resumeBtn = label("RESUME", 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF2F8F5B.toInt(), dp(14f))
            setOnClickListener {
                startActivity(Intent(this@MenuActivity, GameActivity::class.java).apply {
                    putExtra(GameActivity.EXTRA_RESUME, true)
                    putExtra(GameActivity.EXTRA_SOUND, sound)
                })
            }
            visibility = View.GONE
        }
        card.addView(resumeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(50f)).apply { topMargin = dpi(18f) })

        // Play
        val play = label("PLAY", 20f, 0xFF1A1A1A.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            background = rounded(0xFFF2C94C.toInt(), dp(14f))
            setOnClickListener { startGame() }
        }
        card.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(54f)).apply { topMargin = dpi(18f) })

        card.addView(label(
            "Rock, paper, scissors decides who breaks (in Cutthroat the break goes round). Drag on the table to aim, fine-tune with the wheel " +
                "(slow drags are extra fine, fast swipes swing the cue a long way), touch the cue ball on the right " +
                "to add spin, then pull the power bar down and let go to shoot. Tap a ball to look down the cue in 3D: " +
                "drag up there to raise the cue for a jump shot. With ball in hand, drag the cue ball to place it.",
            12.5f, 0xFF8A97A1.toInt()
        ).apply { setPadding(0, dpi(14f), 0, 0); setLineSpacing(0f, 1.15f) })

        setContentView(root)
    }

    // ------------------------------------------------------------------ foul voice

    private fun refreshVoice(note: String?) {
        val f = Sfx.voiceFile(this)
        val has = f.exists()
        playBtn?.alpha = if (has) 1f else 0.35f
        deleteBtn?.alpha = if (has) 1f else 0.35f
        val secs = if (has) (f.length() - 44) / 2.0 / 44100 else 0.0
        voiceStatus?.text = note ?: if (has) "Your voice is set (${"%.1f".format(secs)} s)" else "Not recorded yet — a cough plays instead"
    }

    private fun onRecordTapped() {
        val r = recorder
        if (r != null && r.isRecording) { r.stop(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startRecording()
        else refreshVoice("Recording needs the microphone permission")
    }

    private fun startRecording() {
        stopPlayback()
        val r = VoiceRecorder(this)
        recorder = r
        recordBtn?.text = "■ Stop"
        refreshVoice("Recording… say it now")
        r.start { secs ->
            runOnUiThread {
                recordBtn?.text = "● Record"
                refreshVoice(when {
                    secs > 0 -> "Got it (${"%.1f".format(secs)} s) · it plays on the next foul"
                    secs == 0.0 -> "Didn't hear anything — try again a bit closer to the mic"
                    else -> "Couldn't record on this phone"
                })
            }
        }
    }

    private fun playVoice() {
        val f = Sfx.voiceFile(this)
        if (!f.exists()) return
        stopPlayback()
        player = try {
            MediaPlayer().apply {
                setDataSource(f.path); prepare(); start()
                setOnCompletionListener { stopPlayback() }
            }
        } catch (_: Throwable) { null }
    }

    private fun stopPlayback() {
        try { player?.release() } catch (_: Throwable) { }
        player = null
    }

    override fun onPause() {
        recorder?.stop()
        stopPlayback()
        super.onPause()
    }

    private fun section(t: String) = label(t, 11.5f, 0xFF7F8C96.toInt(), true).apply {
        letterSpacing = 0.12f
        setPadding(0, dpi(14f), 0, dpi(6f))
    }

    /** Like [segmented], in rows of [cols]: one selection across all the rows. */
    private fun segmentedGrid(options: List<String>, cols: Int, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF0E1215.toInt(), dp(12f))
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                v.background = if (i == sel) rounded(0xFF2F8F5B.toInt(), dp(9f)) else null
                v.setTextColor(if (i == sel) Color.WHITE else 0xFF9AA7B1.toInt())
            }
        }
        for (r in 0 until (options.size + cols - 1) / cols) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val i = r * cols + c
                if (i >= options.size) break
                val v = label(options[i], if (cols > 3) 14f else 15f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { paint(i); onPick(i) }
                }
                views += v
                row.addView(v, LinearLayout.LayoutParams(0, dpi(42f), 1f))
            }
            // A short last row keeps the same cell widths as the full ones.
            repeat(cols - minOf(cols, options.size - r * cols)) { row.addView(View(this), LinearLayout.LayoutParams(0, dpi(42f), 1f)) }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        paint(selected)
        return col
    }

    private fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFF0E1215.toInt(), dp(12f))
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                v.background = if (i == sel) rounded(0xFF2F8F5B.toInt(), dp(9f)) else null
                v.setTextColor(if (i == sel) Color.WHITE else 0xFF9AA7B1.toInt())
            }
        }
        options.forEachIndexed { i, o ->
            val v = label(o, if (options.size > 4) 14f else 15f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { paint(i); onPick(i) }
            }
            views += v
            row.addView(v, LinearLayout.LayoutParams(0, dpi(42f), 1f))
        }
        paint(selected)
        return row
    }

    private fun startGame() {
        save()
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_MODE, mode.name)
            putExtra(GameActivity.EXTRA_PRACTICE_RULES, practiceRules)
            putExtra(GameActivity.EXTRA_TRICK_LEVEL, practiceLevel)
            putExtra(GameActivity.EXTRA_TABLE, tableSize.name)
            putExtra(GameActivity.EXTRA_VS_CPU, vsCpu)
            putExtra(GameActivity.EXTRA_LEVEL, level)
            putExtra(GameActivity.EXTRA_SOUND, sound)
            putExtra(GameActivity.EXTRA_CLOCK, if (clockOn && !practice) clockSec else 0)
            putExtra(GameActivity.EXTRA_PRACTICE, practice)
            putExtra(GameActivity.EXTRA_OCT_PLAYERS, if (mode == GameMode.GAUNTLET) gPlayers else octPlayers)
            putExtra(GameActivity.EXTRA_OCT_TEAMS, if (mode == GameMode.GAUNTLET) gTeams else octTeams)
            putExtra(GameActivity.EXTRA_G_EACH, gEach)
            putExtra(GameActivity.EXTRA_PARTNER, gPartner)
            putExtra(GameActivity.EXTRA_OCT_LAYOUT, octLayout)
            putExtra(GameActivity.EXTRA_TRICK_SET, trickSet)
        })
    }

    private companion object {
        const val REQ_MIC = 7
    }
}
