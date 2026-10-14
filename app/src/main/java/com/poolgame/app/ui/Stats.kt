package com.poolgame.app.ui

import android.content.Context
import com.poolgame.app.core.GameMode

/** Win/loss record, kept in SharedPreferences so it survives restarts and app updates. */
class Stats(context: Context) {
    private val prefs = context.getSharedPreferences("pool_stats", Context.MODE_PRIVATE)

    private fun key(mode: GameMode, vsCpu: Boolean, level: Int, slot: String) =
        if (vsCpu) "${mode.name}_cpu${level}_$slot" else "${mode.name}_pp_$slot"

    /** winner: 0 = you / player 1, 1 = CPU / player 2. */
    fun record(mode: GameMode, vsCpu: Boolean, level: Int, winner: Int) {
        val k = key(mode, vsCpu, level, if (winner == 0) "w" else "l")
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    fun wins(mode: GameMode, vsCpu: Boolean, level: Int) = prefs.getInt(key(mode, vsCpu, level, "w"), 0)
    fun losses(mode: GameMode, vsCpu: Boolean, level: Int) = prefs.getInt(key(mode, vsCpu, level, "l"), 0)

    fun totalWinsVsCpu(mode: GameMode) = (1..10).sumOf { wins(mode, true, it) }
    fun totalLossesVsCpu(mode: GameMode) = (1..10).sumOf { losses(mode, true, it) }

    /** Short line for the end-of-game panel. */
    fun summary(mode: GameMode, vsCpu: Boolean, level: Int): String {
        val name = if (mode == GameMode.EIGHT_BALL) "8-Ball" else "9-Ball"
        val w = wins(mode, vsCpu, level); val l = losses(mode, vsCpu, level)
        return if (vsCpu) "Your $name record vs level $level: $w–$l"
        else "$name pass & play: Player 1 $w – Player 2 $l"
    }

    fun reset() = prefs.edit().clear().apply()
}
