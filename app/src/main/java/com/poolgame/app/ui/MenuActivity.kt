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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.poolgame.app.core.GameMode

class MenuActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("pool", Context.MODE_PRIVATE) }
    private var mode = GameMode.EIGHT_BALL
    private var vsCpu = true
    private var level = 5
    private var sound = true
    private var practice = false
    private var practiceTrick = false      // practice: a trick shot instead of the free table
    private var practiceLevel = 0
    private var aimSens = 5
    private var callMode = 0
    private var prediction = true
    private var resumeBtn: TextView? = null
    private var clockOn = false
    private var clockSec = 30
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
        practice = prefs.getBoolean("practice", false)
        practiceTrick = prefs.getBoolean("practiceTrick", false)
        practiceLevel = prefs.getInt("practiceLevel", 0).coerceIn(0, com.poolgame.app.core.Trick.LEVELS.size - 1)
        aimSens = prefs.getInt("aimSens", 5).coerceIn(1, 10)
        callMode = prefs.getInt("callMode", 0).coerceIn(0, 2)
        prediction = prefs.getBoolean("prediction", true)
        clockOn = prefs.getBoolean("clockOn", false)
        clockSec = prefs.getInt("clockSec", 30).coerceIn(10, 120)
        build()
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
        t.text = (if (vsCpu) {
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
            .putBoolean("practiceTrick", practiceTrick).putInt("practiceLevel", practiceLevel)
            .putInt("aimSens", aimSens).putInt("callMode", callMode).putBoolean("prediction", prediction).apply()
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
        card.addView(label("8-Ball · 9-Ball · trick shots · practice", 14f, 0xFF9AA7B1.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dpi(14f)) })

        // Game type
        card.addView(section("GAME"))
        var matchBlockRef: View? = null
        var callBlockRef: View? = null
        val trickHelp = label("Trick shots: half the table's missing between the break area and the far end. " +
            "Jump the cue ball across the gap and sink the 1. Five levels, taking turns with the computer " +
            "or a friend: whoever makes it in fewer shots wins the level, and most levels wins the match.",
            12.5f, 0xFF8A97A1.toInt()).apply { setPadding(0, dpi(8f), 0, 0); setLineSpacing(0f, 1.15f) }
        // Practice: the free table, or one trick shot (pick which).
        val practiceBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val levelBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        practiceBlock.addView(section("PRACTICE"))
        practiceBlock.addView(segmented(listOf("Free table", "Trick shot"), if (practiceTrick) 1 else 0) {
            practiceTrick = it == 1
            levelBlock.visibility = if (practiceTrick) View.VISIBLE else View.GONE
            save()
        })
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
        levelBlock.visibility = if (practiceTrick) View.VISIBLE else View.GONE
        practiceBlock.addView(levelBlock)

        fun refreshMode() {
            practiceBlock.visibility = if (practice) View.VISIBLE else View.GONE
            matchBlockRef?.visibility = if (practice) View.GONE else View.VISIBLE
            callBlockRef?.visibility = if (mode == GameMode.TRICK) View.GONE else View.VISIBLE
            trickHelp.visibility = if (mode == GameMode.TRICK && !practice) View.VISIBLE else View.GONE
        }
        val modeSel = when { practice -> 3; mode == GameMode.TRICK -> 2; mode == GameMode.NINE_BALL -> 1; else -> 0 }
        val modeRow = segmented(listOf("8-Ball", "9-Ball", "Trick", "Practice"), modeSel) {
            practice = it == 3
            if (it < 3) mode = when (it) { 0 -> GameMode.EIGHT_BALL; 1 -> GameMode.NINE_BALL; else -> GameMode.TRICK }
            refreshMode()
            save()
        }
        card.addView(modeRow)
        card.addView(trickHelp)
        card.addView(practiceBlock)

        // Everything below only applies to real games, not practice.
        val matchBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Opponent
        matchBlock.addView(section("OPPONENT"))
        val skillBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val oppRow = segmented(listOf("Computer", "Pass & Play"), if (vsCpu) 0 else 1) {
            vsCpu = it == 0; save()
            skillBlock.visibility = if (vsCpu) View.VISIBLE else View.GONE
        }
        matchBlock.addView(oppRow)

        // Skill
        val skillLabel = label("", 15f, Color.WHITE, true)
        fun refreshSkill() { skillLabel.text = "Level $level · ${levelNames[level - 1]}" }
        refreshSkill()
        skillBlock.addView(section("COMPUTER SKILL"))
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
        skillBlock.visibility = if (vsCpu) View.VISIBLE else View.GONE
        matchBlock.addView(skillBlock)

        // Called pocket (not used for trick shots)
        val callBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        callBlock.addView(section("CALL POCKET"))
        callBlock.addView(segmented(listOf("Off", "8 & 9 only", "Every shot"), callMode) { callMode = it; save() })
        callBlock.addView(label("Tap a pocket to call it. A ball in the wrong pocket is slop: your turn ends " +
            "(the 8 in the wrong pocket loses; a 9 in the wrong pocket is respotted).", 12f, 0xFF8A97A1.toInt())
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
        refreshMode()

        // Controls (apply to every game type)
        card.addView(section("CONTROLS"))
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
            "Rock, paper, scissors decides who breaks. Drag on the table to aim, fine-tune with the wheel " +
                "(slow drags are extra fine, fast swipes swing the cue a long way), touch the cue ball on the right " +
                "to add spin, slide the JUMP slider up for a jump shot, then pull the power bar down and let go to " +
                "shoot. With ball in hand, drag the cue ball to place it.",
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
            val v = label(o, 15f, Color.WHITE, true).apply {
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
            putExtra(GameActivity.EXTRA_MODE, when {
                practice && practiceTrick -> GameMode.TRICK.name
                practice -> GameMode.EIGHT_BALL.name
                else -> mode.name
            })
            putExtra(GameActivity.EXTRA_TRICK_LEVEL, practiceLevel)
            putExtra(GameActivity.EXTRA_VS_CPU, vsCpu)
            putExtra(GameActivity.EXTRA_LEVEL, level)
            putExtra(GameActivity.EXTRA_SOUND, sound)
            putExtra(GameActivity.EXTRA_CLOCK, if (clockOn && !practice) clockSec else 0)
            putExtra(GameActivity.EXTRA_PRACTICE, practice)
        })
    }

    private companion object {
        const val REQ_MIC = 7
    }
}
