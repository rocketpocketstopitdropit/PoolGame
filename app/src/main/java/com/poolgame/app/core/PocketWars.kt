package com.poolgame.app.core

/**
 * Pocket Wars: who owns each of the six pockets, and each player's points. Every ball is worth its
 * number (1–15, 120 in all); first to [WIN] wins.
 */
class PocketWarsState {
    /** Owner of each pocket (index into Table.pockets): 0, 1, or -1 while it's open. */
    val owner = IntArray(6) { -1 }
    val score = IntArray(2)

    fun reset() { owner.fill(-1); score.fill(0) }

    fun copyFrom(o: PocketWarsState) {
        o.owner.copyInto(owner); o.score.copyInto(score)
    }

    fun pocketsOf(p: Int) = owner.count { it == p }

    /** Save format: "o0,o1,o2,o3,o4,o5;s0,s1". */
    fun write() = "${owner.joinToString(",")};${score.joinToString(",")}"

    fun read(t: String) {
        val (a, b) = t.split(';')
        val o = a.split(',').map { it.toInt() }
        val s = b.split(',').map { it.toInt() }
        require(o.size == 6 && o.all { it in -1..1 } && s.size == 2)
        for (i in 0 until 6) owner[i] = o[i]
        score[0] = s[0]; score[1] = s[1]
    }

    companion object {
        /** Points needed to win (more than half of the 120 on the table). */
        const val WIN = 61
        const val TOTAL = 120
    }
}
