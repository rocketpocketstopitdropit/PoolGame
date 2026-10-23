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
import com.poolgame.app.core.CallMode
import com.poolgame.app.core.GameMode
import com.poolgame.app.core.GameSave
import com.poolgame.app.core.TableSize

class GameActivity : Activity(), GameView.Host {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_VS_CPU = "vsCpu"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_CLOCK = "clock"
        const val EXTRA_PRACTICE = "practice"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_TRICK_LEVEL = "trickLevel"
        const val EXTRA_TUTORIAL = "tutorial"
        const val EXTRA_TABLE = "tableSize"
        const val EXTRA_PRACTICE_RULES = "practiceRules"
        const val EXTRA_OCT_PLAYERS = "octPlayers"
        const val EXTRA_OCT_TEAMS = "octTeams"
        const val EXTRA_OCT_LAYOUT = "octLayout"

        const val PREFS = "pool"
        const val KEY_SAVED = "savedGame"

        /** One-line description of the saved game for the menu's Resume button, or null. */
        fun savedSummary(ctx: Context): String? {
            val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SAVED, null) ?: return null
            val s = GameSave.readSettings(text) ?: return null
            val name = Stats.modeName(s.mode)
            val size = if (s.mode == GameMode.BUMPER || s.mode == GameMode.OCTAGON) "" else " · ${s.tableSize.label}"
            val oct = "${Stats.octVariantName(Stats.octVariant(s.octPlayers, s.octTeams))} · ${com.poolgame.app.core.Table.OCT_LAYOUTS[s.octLayout]}"
            return when {
                s.practice && s.mode == GameMode.TRICK -> "Trick-shot practice"
                s.practice && s.mode == GameMode.BUMPER -> "Bumper practice"
                s.practice && s.mode == GameMode.OCTAGON -> "Octagon practice · ${com.poolgame.app.core.Table.OCT_LAYOUTS[s.octLayout]}"
                s.mode == GameMode.OCTAGON && s.vsCpu -> "$name $oct vs CPU level ${s.level}"
                s.mode == GameMode.OCTAGON -> "$name $oct pass & play"
                s.practice && s.practiceRules -> "$name practice$size"
                s.mode == GameMode.TRICK -> if (s.vsCpu) "$name vs CPU level ${s.level}" else "$name pass & play"
                s.practice -> "Free-table practice$size"
                s.vsCpu && s.mode == GameMode.CUTTHROAT -> "$name vs 2 CPUs level ${s.level}$size"
                s.vsCpu -> "$name vs CPU level ${s.level}$size"
                else -> "$name pass & play$size"
            }
        }
    }

    private lateinit var game: GameView
    private lateinit var mode: GameMode
    private var vsCpu = true
    private var level = 5
    private val stats by lazy { Stats(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    /** The hands-on tutorial: a practice table that never touches your saved game or record. */
    private var tutorial = false
    /** Octagon table: the setup the record is kept under (2, 3 or 4 players, or T for teams). */
    private var octVariant = "4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mode = GameMode.values().firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: GameMode.EIGHT_BALL
        vsCpu = intent.getBooleanExtra(EXTRA_VS_CPU, true)
        level = intent.getIntExtra(EXTRA_LEVEL, 5).coerceIn(1, 10)
        var practice = intent.getBooleanExtra(EXTRA_PRACTICE, false)
        var practiceRules = intent.getBooleanExtra(EXTRA_PRACTICE_RULES, false)
        var clock = intent.getIntExtra(EXTRA_CLOCK, 0).coerceIn(0, 300)
        var call = CallMode.values()[prefs.getInt("callMode", 0).coerceIn(0, 2)]
        tutorial = intent.getBooleanExtra(EXTRA_TUTORIAL, false)
        var tableSize = TableSize.fromName(intent.getStringExtra(EXTRA_TABLE))
            ?: TableSize.fromName(prefs.getString("tableSize", null)) ?: TableSize.DEFAULT
        var octPlayers = intent.getIntExtra(EXTRA_OCT_PLAYERS, prefs.getInt("octPlayers", 4)).coerceIn(2, 4)
        var octTeams = intent.getBooleanExtra(EXTRA_OCT_TEAMS, prefs.getBoolean("octTeams", false))
        var octLayout = intent.getIntExtra(EXTRA_OCT_LAYOUT, prefs.getInt("octLayout", 0)).coerceIn(0, 2)

        // Continue a saved game: its own settings win over the menu's current ones.
        var resume: String? = null
        if (tutorial) {
            mode = GameMode.EIGHT_BALL; vsCpu = false; practice = true; clock = 0; call = CallMode.OFF
            tableSize = TableSize.NINE   // the tutorial's lined-up shot is set up on the 9 ft table
        } else if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
            val text = prefs.getString(KEY_SAVED, null)
            val s = text?.let { GameSave.readSettings(it) }
            if (s != null) {
                resume = text
                mode = s.mode; vsCpu = s.vsCpu; level = s.level; practice = s.practice; clock = s.shotClock
                practiceRules = s.practiceRules
                call = s.callMode
                tableSize = s.tableSize   // a paused game keeps the table it was started on
                octPlayers = s.octPlayers; octTeams = s.octTeams; octLayout = s.octLayout
            }
        } else {
            prefs.edit().remove(KEY_SAVED).apply()   // starting fresh replaces any paused game
        }
        // Practice: a game with its own rules on your own, the free table (8-ball's balls), one trick shot, or the bumper table.
        if (practice) {
            if (mode == GameMode.TRICK || mode == GameMode.BUMPER || mode == GameMode.OCTAGON) practiceRules = false
            else if (!practiceRules) mode = GameMode.EIGHT_BALL
            vsCpu = false; clock = 0; call = CallMode.OFF
        }
        if (mode == GameMode.TRICK || mode == GameMode.BUMPER || mode == GameMode.CIRCUIT || mode == GameMode.POCKET_WARS ||
            mode == GameMode.CUTTHROAT || mode == GameMode.OCTAGON) call = CallMode.OFF
        octTeams = octTeams && octPlayers == 4
        this.octVariant = Stats.octVariant(octPlayers, octTeams)

        game = GameView(
            this, mode, vsCpu, level,
            intent.getBooleanExtra(EXTRA_SOUND, prefs.getBoolean("sound", true)),
            clock,
            practice,
            intent.getIntExtra(EXTRA_TRICK_LEVEL, 0),
            prefs.getInt("aimSens", 5),
            tutorial || prefs.getBoolean("prediction", true),   // the tutorial shows where the balls will go
            resume,
            call,
            this,
            tutorial,
            tableSize,
            practiceRules,
            octPlayers,
            octTeams,
            octLayout
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
        if (tutorial) return   // never replace a paused game with the tutorial
        val state = game.saveState()
        prefs.edit().apply { if (state != null) putString(KEY_SAVED, state) else remove(KEY_SAVED) }.apply()
    }

    override fun onResume() { super.onResume(); game.resume() }
    override fun onPause() { game.pause(); saveGame(); super.onPause() }
    override fun onDestroy() { game.release(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = onMenuRequested()

    override fun onGameFinished(winner: Int): String {
        if (tutorial) return ""
        prefs.edit().remove(KEY_SAVED).apply()
        if (game.isPractice()) return ""
        val lvl = if (vsCpu) level else 0
        if (mode == GameMode.OCTAGON) {
            stats.recordOctagon(octVariant, vsCpu, lvl, game.youWon(), game.winningSeat())
            return stats.octSummary(octVariant, vsCpu, lvl).substringBefore('\n')
        }
        stats.record(mode, vsCpu, lvl, winner)
        return stats.summary(mode, vsCpu, lvl)
    }

    override fun onRpsFinished(winner: Int, rounds: Int) = stats.recordRps(vsCpu, winner, rounds)

    override fun onMenuRequested() {
        game.menuOpen = true
        game.onMenuOpened()
        val prediction = if (game.showPrediction) "Shot prediction: ON (tap to turn off)" else "Shot prediction: OFF (tap to turn on)"
        val restart = when { tutorial -> "Restart tutorial"; mode == GameMode.TRICK -> "Restart match"; else -> "Restart rack" }
        val leave = if (tutorial) "Leave tutorial" else "Main menu (your game is saved)"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (tutorial) "Paused (tutorial)" else "Paused")
            .setItems(arrayOf("Resume", prediction, restart, leave)) { _, which ->
                when (which) {
                    1 -> {
                        game.showPrediction = !game.showPrediction
                        if (!tutorial) prefs.edit().putBoolean("prediction", game.showPrediction).apply()
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
