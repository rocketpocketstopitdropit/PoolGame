package com.poolgame.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
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
    private var clockOn = false
    private var clockSec = 30
    private val stats by lazy { Stats(this) }
    private var recordText: TextView? = null

    private val levelNames = arrayOf(
        "Beginner", "Novice", "Casual", "Bar Player", "Club Player",
        "Skilled", "Advanced", "Expert", "Master", "Pro"
    )

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = if (prefs.getString("mode", "") == GameMode.NINE_BALL.name) GameMode.NINE_BALL else GameMode.EIGHT_BALL
        vsCpu = prefs.getBoolean("vsCpu", true)
        level = prefs.getInt("level", 5).coerceIn(1, 10)
        sound = prefs.getBoolean("sound", true)
        practice = prefs.getBoolean("practice", false)
        clockOn = prefs.getBoolean("clockOn", false)
        clockSec = prefs.getInt("clockSec", 30).coerceIn(10, 120)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshRecord()
    }

    private fun refreshRecord() {
        val t = recordText ?: return
        val name = if (mode == GameMode.EIGHT_BALL) "8-Ball" else "9-Ball"
        t.text = if (vsCpu) {
            "$name vs level $level:  ${stats.wins(mode, true, level)} W – ${stats.losses(mode, true, level)} L\n" +
                "$name vs all levels:  ${stats.totalWinsVsCpu(mode)} W – ${stats.totalLossesVsCpu(mode)} L"
        } else {
            "$name pass & play:  Player 1  ${stats.wins(mode, false, 0)} – ${stats.losses(mode, false, 0)}  Player 2"
        }
    }

    private fun save() {
        prefs.edit().putString("mode", mode.name).putBoolean("vsCpu", vsCpu)
            .putInt("level", level).putBoolean("sound", sound)
            .putBoolean("clockOn", clockOn).putInt("clockSec", clockSec)
            .putBoolean("practice", practice).apply()
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
        card.addView(label("8-Ball · 9-Ball · practice · real spin", 14f, 0xFF9AA7B1.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dpi(14f)) })

        // Game type
        card.addView(section("GAME"))
        var matchBlockRef: View? = null
        val modeRow = segmented(listOf("8-Ball", "9-Ball", "Practice"), if (practice) 2 else if (mode == GameMode.EIGHT_BALL) 0 else 1) {
            practice = it == 2
            if (it < 2) mode = if (it == 0) GameMode.EIGHT_BALL else GameMode.NINE_BALL
            matchBlockRef?.visibility = if (practice) View.GONE else View.VISIBLE
            save()
        }
        card.addView(modeRow)

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
                    .setMessage("This clears all wins and losses for every game type and level.")
                    .setPositiveButton("Reset") { _, _ -> stats.reset(); refreshRecord() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        refreshRecord()
        matchBlock.visibility = if (practice) View.GONE else View.VISIBLE
        card.addView(matchBlock)
        matchBlockRef = matchBlock

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

        // Play
        val play = label("PLAY", 20f, 0xFF1A1A1A.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            background = rounded(0xFFF2C94C.toInt(), dp(14f))
            setOnClickListener { startGame() }
        }
        card.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(54f)).apply { topMargin = dpi(18f) })

        card.addView(label(
            "Drag on the table to aim, fine-tune with the wheel, touch the cue ball on the right to add spin, " +
                "raise the cue angle for a jump shot, then pull the power bar down and let go to shoot. " +
                "With ball in hand, drag the cue ball to place it.",
            12.5f, 0xFF8A97A1.toInt()
        ).apply { setPadding(0, dpi(14f), 0, 0); setLineSpacing(0f, 1.15f) })

        setContentView(root)
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
            putExtra(GameActivity.EXTRA_MODE, mode.name)
            putExtra(GameActivity.EXTRA_VS_CPU, vsCpu)
            putExtra(GameActivity.EXTRA_LEVEL, level)
            putExtra(GameActivity.EXTRA_SOUND, sound)
            putExtra(GameActivity.EXTRA_CLOCK, if (clockOn && !practice) clockSec else 0)
            putExtra(GameActivity.EXTRA_PRACTICE, practice)
        })
    }
}
