package com.poolgame.app.core

/**
 * Cutthroat: three players, three groups of five — 1–5, 6–10 and 11–15. Each player's group is the
 * one they don't want sunk; you try to sink the other two players' balls, and the last player with a
 * ball still on the table wins.
 *
 * The table is open after the break. The first player to sink a ball on a legal shot picks any group they
 * like; the next player to do so picks one of the two left, and the third player gets the last.
 */
class CutthroatState {
    /** Each player's group: 0 = balls 1–5, 1 = 6–10, 2 = 11–15, or -1 while they haven't got one. */
    val group = IntArray(3) { -1 }

    /** A player who has just sunk a ball on an open table and now picks their group, or -1. */
    var choosing = -1

    fun reset() { group.fill(-1); choosing = -1 }

    fun copyFrom(o: CutthroatState) { o.group.copyInto(group); choosing = o.choosing }

    /** The groups nobody has yet. */
    fun free(): List<Int> = (0..2).filter { it !in group }

    /** The player who has [g] as their group, or -1. */
    fun ownerOfGroup(g: Int) = group.indexOf(g)

    /** True once all three groups are handed out. */
    fun assigned() = group.all { it >= 0 }

    /** Save format: "g0,g1,g2,choosing". */
    fun write() = group.joinToString(",") + ",$choosing"

    fun read(t: String) {
        val g = t.split(',').map { it.toInt() }
        require(g.size >= 3 && g.all { it in -1..2 })
        for (i in 0..2) group[i] = g[i]
        choosing = g.getOrElse(3) { -1 }
    }

    companion object {
        /** The group a ball belongs to (0, 1 or 2), or -1 for the cue ball. */
        fun groupOf(id: Int) = if (id in 1..15) (id - 1) / 5 else -1
        fun ballsOf(g: Int): IntRange = (g * 5 + 1)..(g * 5 + 5)
        fun groupName(g: Int) = when (g) { 0 -> "1–5"; 1 -> "6–10"; 2 -> "11–15"; else -> "open" }
    }
}
