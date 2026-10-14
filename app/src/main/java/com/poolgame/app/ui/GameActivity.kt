package com.poolgame.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.poolgame.app.core.GameMode
import com.poolgame.app.core.GameSave

class GameActivity : Activity(), GameView.Host {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_VS_CPU = "vsCpu"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_CLOCK = "clock"
        const val EXTRA_PRACTICE = "practice"
        const val EXTRA_RESUME = "resume"

        const val PREFS = "pool"
        const val KEY_SAVED = "savedGame"

        /** One-line description of the saved game for the menu's Resume button, or null. */
        fun savedSummary(ctx: Context): String? {
            val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SAVED, null) ?: return null
            val s = GameSave.readSettings(text) ?: return null
            val name = if (s.mode == GameMode.NINE_BALL) "9-Ball" else "8-Ball"
            return when {
                s.practice -> "Practice"
                s.vsCpu -> "$name vs CPU level ${s.level}"
                else -> "$name pass & play"
            }
        }
    }

    private lateinit var game: GameView
    private lateinit var mode: GameMode
    private var vsCpu = true
    private var level = 5
    private val stats by lazy { Stats(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mode = if (intent.getStringExtra(EXTRA_MODE) == GameMode.NINE_BALL.name) GameMode.NINE_BALL else GameMode.EIGHT_BALL
        vsCpu = intent.getBooleanExtra(EXTRA_VS_CPU, true)
        level = intent.getIntExtra(EXTRA_LEVEL, 5).coerceIn(1, 10)
        var practice = intent.getBooleanExtra(EXTRA_PRACTICE, false)
        var clock = intent.getIntExtra(EXTRA_CLOCK, 0).coerceIn(0, 300)

        // Continue a saved game: its own settings win over the menu's current ones.
        var resume: String? = null
        if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
            val text = prefs.getString(KEY_SAVED, null)
            val s = text?.let { GameSave.readSettings(it) }
            if (s != null) {
                resume = text
                mode = s.mode; vsCpu = s.vsCpu; level = s.level; practice = s.practice; clock = s.shotClock
            }
        } else {
            prefs.edit().remove(KEY_SAVED).apply()   // starting fresh replaces any paused game
        }
        if (practice) { mode = GameMode.EIGHT_BALL; vsCpu = false; clock = 0 }

        game = GameView(
            this, mode, vsCpu, level,
            intent.getBooleanExtra(EXTRA_SOUND, prefs.getBoolean("sound", true)),
            clock,
            practice,
            prefs.getInt("aimSens", 5),
            prefs.getBoolean("prediction", true),
            resume,
            this
        )
        setContentView(game)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    /** Keep the game whenever we leave it (menu, home button, phone call...). */
    private fun saveGame() {
        val state = game.saveState()
        prefs.edit().apply { if (state != null) putString(KEY_SAVED, state) else remove(KEY_SAVED) }.apply()
    }

    override fun onResume() { super.onResume(); game.resume() }
    override fun onPause() { game.pause(); saveGame(); super.onPause() }
    override fun onDestroy() { game.release(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = onMenuRequested()

    override fun onGameFinished(winner: Int): String {
        prefs.edit().remove(KEY_SAVED).apply()
        if (game.isPractice()) return ""
        val lvl = if (vsCpu) level else 0
        stats.record(mode, vsCpu, lvl, winner)
        return stats.summary(mode, vsCpu, lvl)
    }

    override fun onMenuRequested() {
        game.menuOpen = true
        val prediction = if (game.showPrediction) "Shot prediction: ON (tap to turn off)" else "Shot prediction: OFF (tap to turn on)"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Paused")
            .setItems(arrayOf("Resume", prediction, "Restart rack", "Main menu (your game is saved)")) { _, which ->
                when (which) {
                    1 -> {
                        game.showPrediction = !game.showPrediction
                        prefs.edit().putBoolean("prediction", game.showPrediction).apply()
                    }
                    2 -> game.restart()
                    3 -> { saveGame(); finish() }
                }
            }
            .setOnDismissListener { game.menuOpen = false }
            .show()
    }

    override fun onExitToMenu() = finish()
}
