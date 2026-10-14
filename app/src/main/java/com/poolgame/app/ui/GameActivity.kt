package com.poolgame.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.poolgame.app.core.GameMode

class GameActivity : Activity(), GameView.Host {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_VS_CPU = "vsCpu"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_SOUND = "sound"
    }

    private lateinit var game: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mode = if (intent.getStringExtra(EXTRA_MODE) == GameMode.NINE_BALL.name) GameMode.NINE_BALL else GameMode.EIGHT_BALL
        game = GameView(
            this, mode,
            intent.getBooleanExtra(EXTRA_VS_CPU, true),
            intent.getIntExtra(EXTRA_LEVEL, 5).coerceIn(1, 10),
            intent.getBooleanExtra(EXTRA_SOUND, true),
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

    override fun onResume() { super.onResume(); game.resume() }
    override fun onPause() { game.pause(); super.onPause() }
    override fun onDestroy() { game.release(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = onMenuRequested()

    override fun onMenuRequested() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Paused")
            .setItems(arrayOf("Resume", "Restart rack", "Quit to menu")) { _, which ->
                when (which) {
                    1 -> game.restart()
                    2 -> finish()
                }
            }
            .show()
    }

    override fun onExitToMenu() = finish()
}
