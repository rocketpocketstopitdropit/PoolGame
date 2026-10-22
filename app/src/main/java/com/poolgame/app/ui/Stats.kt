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
        val name = modeName(mode)
        val w = wins(mode, vsCpu, level); val l = losses(mode, vsCpu, level)
        return if (vsCpu) "Your $name record vs level $level: $w–$l"
        else "$name pass & play: Player 1 $w – Player 2 $l"
    }

    // ------------------------------------------------------------------ rock, paper, scissors

    /**
     * One finished rock-paper-scissors game (deciding who breaks). [winner] is 0 for you / player 1,
     * 1 for the CPU / player 2; [rounds] counts every throw, ties included.
     */
    fun recordRps(vsCpu: Boolean, winner: Int, rounds: Int) {
        val k = if (vsCpu) (if (winner == 0) "rps_cpu_w" else "rps_cpu_l") else (if (winner == 0) "rps_pp_1" else "rps_pp_2")
        prefs.edit()
            .putInt(k, prefs.getInt(k, 0) + 1)
            .putInt("rps_games", prefs.getInt("rps_games", 0) + 1)
            .putInt("rps_rounds", prefs.getInt("rps_rounds", 0) + rounds)
            .putInt("rps_max", maxOf(prefs.getInt("rps_max", 0), rounds))
            .apply()
    }

    /** Two lines for the menu: your rock-paper-scissors record, and how long games take. */
    fun rpsSummary(vsCpu: Boolean): String {
        val games = prefs.getInt("rps_games", 0)
        if (games == 0) return "Rock, paper, scissors: no games yet"
        val rec = if (vsCpu) "Rock, paper, scissors vs CPU:  ${prefs.getInt("rps_cpu_w", 0)} W – ${prefs.getInt("rps_cpu_l", 0)} L"
        else "Rock, paper, scissors:  Player 1  ${prefs.getInt("rps_pp_1", 0)} – ${prefs.getInt("rps_pp_2", 0)}  Player 2"
        val avg = prefs.getInt("rps_rounds", 0).toDouble() / games
        val max = prefs.getInt("rps_max", 0)
        return "$rec\nLongest: $max ${if (max == 1) "round" else "rounds"} · average ${fmt1(avg)} rounds to a winner"
    }

    private fun fmt1(v: Double): String {
        val t = Math.round(v * 10)
        return "${t / 10}.${t % 10}"
    }

    fun reset() = prefs.edit().clear().apply()

    companion object {
        fun modeName(mode: GameMode) = when (mode) {
            GameMode.EIGHT_BALL -> "8-Ball"
            GameMode.NINE_BALL -> "9-Ball"
            GameMode.TRICK -> "Trick shots"
            GameMode.BUMPER -> "Bumper pool"
            GameMode.CIRCUIT -> "Circuit Pool"
        }
    }
}
