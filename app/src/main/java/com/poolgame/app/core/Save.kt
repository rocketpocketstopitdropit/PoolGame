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
    val tableSize: TableSize = TableSize.NINE
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
            TableSize.fromName(p["table"]) ?: TableSize.NINE
        )
    } catch (e: Throwable) { null }

    /** Load the saved state into [m] (which must use the saved game mode). Returns false if the text is unusable. */
    fun restore(text: String, m: Match): Boolean = try {
        val p = parse(text)
        if (GameMode.valueOf(p.getValue("mode")) != m.mode) throw IllegalStateException("mode")
        if (m.mode != GameMode.TRICK && m.mode != GameMode.BUMPER && (TableSize.fromName(p["table"]) ?: TableSize.NINE) != Table.size)
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
        if (m.mode == GameMode.TRICK) {
            val f = p.getValue("tl").split(',').map { it.toInt() }
            if (f.size < 15) throw IllegalStateException("old trick save")
            val t = m.trick
            t.levelNo = f[0]; t.level = f[1].coerceIn(0, Trick.LEVELS.size - 1); t.starter = f[2]
            t.suddenDeath = f[3] == 1
            t.tries[0] = f[4]; t.tries[1] = f[5]; t.made[0] = f[6]; t.made[1] = f[7]
            t.bonus[0] = f[8]; t.bonus[1] = f[9]; t.done[0] = f[10] == 1; t.done[1] = f[11] == 1
            t.levelWins[0] = f[12]; t.levelWins[1] = f[13]; t.resetPending = f[14] == 1
            t.results.clear()
            p["tr"]?.split(';')?.filter { it.isNotEmpty() }?.forEach { t.results += it.toInt() }
            m.applyTrickLayout()
        }
        for (b in m.world.balls) {
            val f = p.getValue("b${b.id}").split(',')
            b.stop()
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
