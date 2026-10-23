package com.poolgame.app.ui

import android.content.Context
import com.poolgame.app.core.GameMode

/** Win/loss record, kept in SharedPreferences so it survives restarts and app updates. */
class Stats(context: Context) {
    private val prefs = context.getSharedPreferences("pool_stats", Context.MODE_PRIVATE)

    private fun key(mode: GameMode, vsCpu: Boolean, level: Int, slot: String) =
        if (vsCpu) "${mode.name}_cpu${level}_$slot" else "${mode.name}_pp_$slot"

    /** winner: 0 = you / player 1, 1 = CPU / player 2 (Cutthroat: 2 = CPU 2 / player 3). */
    fun record(mode: GameMode, vsCpu: Boolean, level: Int, winner: Int) {
        // Cutthroat pass & play keeps a win count for each of the three players.
        val slot = if (mode == GameMode.CUTTHROAT && !vsCpu) "p${winner + 1}" else if (winner == 0) "w" else "l"
        val k = key(mode, vsCpu, level, slot)
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    fun wins(mode: GameMode, vsCpu: Boolean, level: Int) = prefs.getInt(key(mode, vsCpu, level, "w"), 0)
    fun losses(mode: GameMode, vsCpu: Boolean, level: Int) = prefs.getInt(key(mode, vsCpu, level, "l"), 0)

    /** Cutthroat pass & play: games won by player [p] (0, 1 or 2). */
    fun cutthroatWins(p: Int) = prefs.getInt(key(GameMode.CUTTHROAT, false, 0, "p${p + 1}"), 0)

    fun totalWinsVsCpu(mode: GameMode) = (1..10).sumOf { wins(mode, true, it) }
    fun totalLossesVsCpu(mode: GameMode) = (1..10).sumOf { losses(mode, true, it) }

    // ------------------------------------------------------------------ octagon table
    // Kept per setup: 2, 3 or 4 players, or "T" for two teams of two.

    private fun octKey(variant: String, vsCpu: Boolean, level: Int, slot: String) =
        if (vsCpu) "OCTAGON${variant}_cpu${level}_$slot" else "OCTAGON${variant}_pp_$slot"

    /** One octagon game: [youWon] against the CPUs; in pass & play, [seat] is the winner (in teams, 0 red & white, 1 blue & yellow). */
    fun recordOctagon(variant: String, vsCpu: Boolean, level: Int, youWon: Boolean, seat: Int) {
        val k = octKey(variant, vsCpu, level, if (vsCpu) (if (youWon) "w" else "l") else "p${seat + 1}")
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }
    fun octWins(variant: String, level: Int) = prefs.getInt(octKey(variant, true, level, "w"), 0)
    fun octLosses(variant: String, level: Int) = prefs.getInt(octKey(variant, true, level, "l"), 0)
    fun octSeatWins(variant: String, seat: Int) = prefs.getInt(octKey(variant, false, 0, "p${seat + 1}"), 0)

    /** The octagon record for a setup, in a line or two. */
    fun octSummary(variant: String, vsCpu: Boolean, level: Int): String {
        val name = "Octagon ${octVariantName(variant)}"
        if (vsCpu) return "$name vs level $level:  ${octWins(variant, level)} W – ${octLosses(variant, level)} L\n" +
            "$name vs all levels:  ${(1..10).sumOf { octWins(variant, it) }} W – ${(1..10).sumOf { octLosses(variant, it) }} L"
        if (variant == "T") return "$name pass & play wins:  Red & White  ${octSeatWins(variant, 0)} · Blue & Yellow  ${octSeatWins(variant, 1)}"
        val n = variant.toIntOrNull() ?: 2
        return "$name pass & play wins:  " + (0 until n).joinToString(" · ") { "Player ${it + 1}  ${octSeatWins(variant, it)}" }
    }

    /** Short line for the end-of-game panel. */
    fun summary(mode: GameMode, vsCpu: Boolean, level: Int): String {
        val name = modeName(mode)
        val w = wins(mode, vsCpu, level); val l = losses(mode, vsCpu, level)
        if (mode == GameMode.CUTTHROAT) return if (vsCpu) "Your $name record vs two level $level CPUs: $w–$l"
            else "$name wins: Player 1 ${cutthroatWins(0)} · Player 2 ${cutthroatWins(1)} · Player 3 ${cutthroatWins(2)}"
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
            GameMode.POCKET_WARS -> "Pocket Wars"
            GameMode.CUTTHROAT -> "Cutthroat"
            GameMode.OCTAGON -> "Octagon bumper"
        }

        /** The octagon table's setup as a short key: "2", "3", "4" players or "T" for teams. */
        fun octVariant(players: Int, teams: Boolean) = if (teams && players == 4) "T" else players.coerceIn(2, 4).toString()
        fun octVariantName(variant: String) = if (variant == "T") "2 v 2" else "$variant-player"
    }
}
