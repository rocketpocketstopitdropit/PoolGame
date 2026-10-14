package com.poolgame.app.core

/** Game settings that travel with a saved game. */
class GameSettings(
    val mode: GameMode,
    val vsCpu: Boolean,
    val level: Int,
    val shotClock: Int,
    val practice: Boolean,
    val breaker: Int
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
        kv("current", m.current)
        kv("g0", m.groups[0].name); kv("g1", m.groups[1].name)
        kv("bih", m.ballInHand); kv("kitchen", m.kitchenOnly); kv("brk", m.isBreak)
        kv("f0", m.consecutiveFouls[0]); kv("f1", m.consecutiveFouls[1])
        kv("msg", m.message.replace('\n', ' '))
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
            p.getValue("breaker").toInt()
        )
    } catch (e: Throwable) { null }

    /** Load the saved state into [m] (which must use the saved game mode). Returns false if the text is unusable. */
    fun restore(text: String, m: Match): Boolean = try {
        val p = parse(text)
        if (GameMode.valueOf(p.getValue("mode")) != m.mode) throw IllegalStateException("mode")
        m.current = p.getValue("current").toInt()
        m.groups[0] = Group.valueOf(p.getValue("g0")); m.groups[1] = Group.valueOf(p.getValue("g1"))
        m.ballInHand = p.getValue("bih").toBoolean()
        m.kitchenOnly = p.getValue("kitchen").toBoolean()
        m.isBreak = p.getValue("brk").toBoolean()
        m.consecutiveFouls[0] = p.getValue("f0").toInt(); m.consecutiveFouls[1] = p.getValue("f1").toInt()
        m.message = p["msg"] ?: ""
        m.winner = -1
        for (b in m.world.balls) {
            val f = p.getValue("b${b.id}").split(',')
            b.stop()
            b.x = f[0].toDouble(); b.y = f[1].toDouble(); b.onTable = f[2] == "1"
            b.qw = f[3].toDouble(); b.qx = f[4].toDouble(); b.qy = f[5].toDouble(); b.qz = f[6].toDouble()
        }
        m.world.log.reset()
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
