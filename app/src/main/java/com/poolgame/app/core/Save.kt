package com.poolgame.app.core

/** Game settings that travel with a saved game. */
class GameSettings(
    val mode: GameMode,
    val vsCpu: Boolean,
    val level: Int,
    val shotClock: Int,
    val practice: Boolean,
    val breaker: Int,
    val callMode: CallMode = CallMode.OFF,
    /** Table played on (8-ball, 9-ball, practice). Saves from before sizes existed were on the 9 ft table. */
    val tableSize: TableSize = TableSize.NINE,
    /** Practice with the game's own rules (on your own); false for the free table or a trick shot. */
    val practiceRules: Boolean = false,
    /** Octagon table: how many players (2 to 4), whether the four play as two teams, and the bumper layout. */
    val octPlayers: Int = 2,
    val octTeams: Boolean = false,
    val octLayout: Int = 0,
    /** Bar rules: a scratch gives ball in hand in the kitchen (behind the head string) instead of anywhere. */
    val scratchKitchen: Boolean = false,
    /** Trick-shot match: which set of levels (0 the gap jumps, 1 the classics). */
    val trickSet: Int = 0,
    /** The Gauntlet: balls each (6 to 8), and in 2 v 2 red's partner (1 across, 2 or 3 beside). */
    val gauntEach: Int = 7,
    val partner: Int = 1
)

/**
 * Saves and restores a game in progress as plain text, so you can leave to the menu (or close
 * the app) and pick the same game back up. Shared by the Android and web versions.
 */
object GameSave {
    fun write(m: Match, s: GameSettings): String {
        val sb = StringBuilder()
        fun kv(k: String, v: Any) { sb.append(k).append('=').append(v.toString()).append('\n') }
        kv("v", 1)
        kv("mode", s.mode.name); kv("vsCpu", s.vsCpu); kv("level", s.level)
        kv("clock", s.shotClock); kv("practice", s.practice); kv("breaker", s.breaker)
        kv("call", s.callMode.name)
        kv("table", s.tableSize.name)
        kv("prules", s.practiceRules)
        kv("octp", s.octPlayers); kv("octt", s.octTeams); kv("octl", s.octLayout)
        kv("barscr", s.scratchKitchen); kv("tset", s.trickSet)
        kv("each", s.gauntEach); kv("partner", s.partner)
        kv("current", m.current)
        kv("g0", m.groups[0].name); kv("g1", m.groups[1].name)
        kv("bih", m.ballInHand); kv("kitchen", m.kitchenOnly); kv("brk", m.isBreak)
        kv("f0", m.consecutiveFouls[0]); kv("f1", m.consecutiveFouls[1])
        kv("msg", m.message.replace('\n', ' '))
        val t = m.trick
        if (m.mode == GameMode.TRICK) {
            kv("tl", "${t.levelNo},${t.level},${t.starter},${if (t.suddenDeath) 1 else 0}," +
                "${t.tries[0]},${t.tries[1]},${t.made[0]},${t.made[1]},${t.bonus[0]},${t.bonus[1]}," +
                "${if (t.done[0]) 1 else 0},${if (t.done[1]) 1 else 0},${t.levelWins[0]},${t.levelWins[1]},${if (t.resetPending) 1 else 0}")
            kv("tr", t.results.joinToString(";"))
        }
        if (m.mode == GameMode.CIRCUIT) {
            kv("ct", m.circuit.writeTargets())
            kv("co", m.circuit.writeObs())
        }
        if (m.mode == GameMode.POCKET_WARS) kv("pw", m.pockets.write())
        if (m.mode == GameMode.CUTTHROAT) kv("cut", m.cut.write())
        for (b in m.world.balls) {
            kv("b${b.id}", "${b.x},${b.y},${if (b.onTable) 1 else 0},${b.qw},${b.qx},${b.qy},${b.qz}")
        }
        return sb.toString()
    }

    fun readSettings(text: String): GameSettings? = try {
        val p = parse(text)
        GameSettings(
            GameMode.valueOf(p.getValue("mode")),
            p.getValue("vsCpu").toBoolean(),
            p.getValue("level").toInt(),
            p.getValue("clock").toInt(),
            p.getValue("practice").toBoolean(),
            p.getValue("breaker").toInt(),
            p["call"]?.let { CallMode.valueOf(it) } ?: CallMode.OFF,
            TableSize.fromName(p["table"]) ?: TableSize.NINE,
            p["prules"]?.toBoolean() ?: false,
            p["octp"]?.toIntOrNull()?.coerceIn(2, 4) ?: 2,
            p["octt"]?.toBoolean() ?: false,
            p["octl"]?.toIntOrNull()?.coerceIn(0, Table.OCT_LAYOUTS.size - 1) ?: 0,
            p["barscr"]?.toBoolean() ?: false,
            p["tset"]?.toIntOrNull()?.coerceIn(0, Trick.SETS.size - 1) ?: 0,
            p["each"]?.toIntOrNull()?.coerceIn(6, 8) ?: 7,
            p["partner"]?.toIntOrNull()?.coerceIn(1, 3) ?: 1
        )
    } catch (e: Throwable) { null }

    /** Load the saved state into [m] (which must use the saved game mode). Returns false if the text is unusable. */
    fun restore(text: String, m: Match): Boolean = try {
        val p = parse(text)
        if (GameMode.valueOf(p.getValue("mode")) != m.mode) throw IllegalStateException("mode")
        if (m.mode == GameMode.OCTAGON && (p["octl"]?.toIntOrNull() ?: 0) != Table.octLayout) throw IllegalStateException("layout")
        if (m.mode != GameMode.TRICK && m.mode != GameMode.BUMPER && m.mode != GameMode.OCTAGON && m.mode != GameMode.GAUNTLET &&
            (TableSize.fromName(p["table"]) ?: TableSize.NINE) != Table.size)
            throw IllegalStateException("table size")   // the match must be made on the saved table
        m.current = p.getValue("current").toInt()
        m.groups[0] = Group.valueOf(p.getValue("g0")); m.groups[1] = Group.valueOf(p.getValue("g1"))
        m.ballInHand = p.getValue("bih").toBoolean()
        m.kitchenOnly = p.getValue("kitchen").toBoolean()
        m.isBreak = p.getValue("brk").toBoolean()
        m.consecutiveFouls[0] = p.getValue("f0").toInt(); m.consecutiveFouls[1] = p.getValue("f1").toInt()
        m.message = p["msg"] ?: ""
        m.winner = -1
        m.callMode = p["call"]?.let { CallMode.valueOf(it) } ?: CallMode.OFF
        m.scratchKitchen = p["barscr"]?.toBoolean() ?: false
        m.teams = m.fourWay && m.players == 4 && p["octt"]?.toBoolean() == true
        if (m.mode == GameMode.GAUNTLET) {
            m.each = p["each"]?.toIntOrNull()?.coerceIn(6, 8) ?: 7
            m.partner = p["partner"]?.toIntOrNull()?.coerceIn(1, 3) ?: 1
        }
        if (m.mode == GameMode.TRICK) {
            val f = p.getValue("tl").split(',').map { it.toInt() }
            if (f.size < 15) throw IllegalStateException("old trick save")
            val t = m.trick
            t.levelNo = f[0]; t.level = f[1].coerceIn(0, Trick.LEVELS.size - 1); t.starter = f[2]
            t.suddenDeath = f[3] == 1
            t.tries[0] = f[4]; t.tries[1] = f[5]; t.made[0] = f[6]; t.made[1] = f[7]
            t.bonus[0] = f[8]; t.bonus[1] = f[9]; t.done[0] = f[10] == 1; t.done[1] = f[11] == 1
            t.levelWins[0] = f[12]; t.levelWins[1] = f[13]; t.resetPending = f[14] == 1
            t.set = Trick.LEVELS[t.level].set
            t.results.clear()
            p["tr"]?.split(';')?.filter { it.isNotEmpty() }?.forEach { t.results += it.toInt() }
            m.applyTrickLayout()
        }
        if (m.mode == GameMode.CIRCUIT) m.circuit.read(p.getValue("ct"), p["co"])
        if (m.mode == GameMode.POCKET_WARS) m.pockets.read(p.getValue("pw"))
        if (m.mode == GameMode.CUTTHROAT) m.cut.read(p.getValue("cut"))
        for (b in m.world.balls) {
            b.stop()
            // (Saves from before the octagon table have no balls past 15.)
            val f = p["b${b.id}"]?.split(',') ?: if (b.id > 15) { b.onTable = false; continue } else throw IllegalStateException("ball")
            b.x = f[0].toDouble(); b.y = f[1].toDouble(); b.onTable = f[2] == "1"
            b.qw = f[3].toDouble(); b.qx = f[4].toDouble(); b.qy = f[5].toDouble(); b.qz = f[6].toDouble()
        }
        m.world.log.reset()
        m.afterRestore()
        true
    } catch (e: Throwable) { false }

    private fun parse(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.split('\n')) {
            val i = line.indexOf('=')
            if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }
}
