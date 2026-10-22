package com.poolgame.app.core

import kotlin.random.Random

/**
 * Circuit Pool's hidden information. Each player is dealt three of the fifteen balls in secret; the
 * other nine are neutral. What's public is how many of each player's targets are down, after every
 * shot — [obs] keeps that record, which is all a player (or the computer) may use to work out the
 * opponent's targets.
 */
class CircuitState {
    /** Each player's three target balls, in number order. */
    val targets = arrayOf(IntArray(3), IntArray(3))
    /** After each shot: [mask of balls down (bit n = ball n), player 0's targets down, player 1's targets down]. */
    val obs = ArrayList<IntArray>()

    fun deal(rng: Random) {
        val d = (1..15).shuffled(rng)
        targets[0] = d.subList(0, 3).sorted().toIntArray()
        targets[1] = d.subList(3, 6).sorted().toIntArray()
        obs.clear()
    }

    fun copyFrom(o: CircuitState) {
        targets[0] = o.targets[0].copyOf(); targets[1] = o.targets[1].copyOf()
        obs.clear(); for (e in o.obs) obs += e.copyOf()
    }

    /** Player [p]'s count of targets down as last seen by both players (0 before the first shot). */
    fun publicSunk(p: Int): Int = obs.lastOrNull()?.get(1 + p) ?: 0

    /** Save format: "a,b,c;d,e,f" and the observations as "mask,s0,s1;...". */
    fun writeTargets() = "${targets[0].joinToString(",")};${targets[1].joinToString(",")}"
    fun writeObs() = obs.joinToString(";") { it.joinToString(",") }

    fun read(t: String, o: String?) {
        val parts = t.split(';')
        for (p in 0..1) {
            val ids = parts[p].split(',').map { it.toInt() }
            require(ids.size == 3 && ids.all { it in 1..15 })
            targets[p] = ids.toIntArray()
        }
        require((targets[0] + targets[1]).toSet().size == 6)
        obs.clear()
        o?.split(';')?.filter { it.isNotEmpty() }?.forEach { e -> obs += e.split(',').map { it.toInt() }.toIntArray() }
    }
}

/**
 * What player [me] can work out about the opponent's targets from what's public: every set of three
 * balls (other than [me]'s own) that agrees with the opponent's count after every shot so far. Each
 * such set is equally likely. The computer plays from this, never from the opponent's real targets.
 */
class CircuitBelief(state: CircuitState, me: Int) {
    /** The possible opponent target sets, as bit masks (bit n = ball n). */
    val sets: IntArray
    /** For each ball, the chance it's one of the opponent's targets. */
    val pOpp = DoubleArray(16)
    val mine: IntArray = state.targets[me].copyOf()
    val oppSunk = state.publicSunk(1 - me)

    init {
        val pool = (1..15).filter { it !in mine }
        val out = ArrayList<Int>()
        for (i in pool.indices) for (j in i + 1 until pool.size) for (k in j + 1 until pool.size) {
            val m = (1 shl pool[i]) or (1 shl pool[j]) or (1 shl pool[k])
            if (state.obs.all { (m and it[0]).countOneBits() == it[2 - me] }) out += m
        }
        sets = out.toIntArray()
        if (sets.isNotEmpty()) for (id in 1..15) {
            var n = 0
            for (m in sets) if ((m and (1 shl id)) != 0) n++
            pOpp[id] = n.toDouble() / sets.size
        }
    }
}
