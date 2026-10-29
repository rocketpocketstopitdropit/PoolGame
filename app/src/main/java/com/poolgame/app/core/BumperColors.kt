package com.poolgame.app.core

/**
 * Bumper pool colours, shared by the app, the web version and the 3D view. Player 0 has the red
 * balls, 1 the white, 2 the blue and 3 the yellow (the last two only on the octagon table). Each
 * player's marked ball is spotted in [SPOT]; each cup is ringed in the colour of the player who
 * shoots into it ([RING]).
 */
object BumperColors {
    const val RED = 0xFFC62828.toInt()
    const val WHITE = 0xFFF4F0E2.toInt()
    const val BLUE = 0xFF1F57C9.toInt()
    const val YELLOW = 0xFFF2C21B.toInt()
    val BALL = intArrayOf(RED, WHITE, BLUE, YELLOW)
    val SPOT = intArrayOf(WHITE, RED, WHITE, BLUE)
    val RING = intArrayOf(RED, 0xFFEDE6D2.toInt(), 0xFF2F6FE0.toInt(), YELLOW)
    /** A cup nobody is shooting at (an empty seat on the octagon table). */
    const val RING_NONE = 0xFF5C646B.toInt()

    /** Balls each player has (five, or 6 to 8 on the Gauntlet): set by the match. */
    var each = 5

    /** Bumper ball [id]'s player (0..3), or -1. */
    fun ownerOf(id: Int) = if (id in 1..4 * each) (id - 1) / each else -1
    fun isMarked(id: Int) = id in 1..4 * each && (id - 1) % each == 0
    fun ball(id: Int) = BALL[ownerOf(id).coerceIn(0, 3)]
    fun spot(id: Int) = SPOT[ownerOf(id).coerceIn(0, 3)]

    /**
     * The ring colour of each cup (index into Table.pockets): the colour of whoever shoots into it.
     * Small table: cup 0 (left) white, cup 1 (right) red. Octagon table with [players] players: cups
     * across from the players' ends; a cup nobody shoots at is grey.
     */
    fun cupRings(octagon: Boolean, players: Int): IntArray {
        if (!octagon) return intArrayOf(RING[1], RING[0])
        val out = IntArray(4) { RING_NONE }
        for (p in 0 until players.coerceIn(2, 4)) out[Match.OCT_TARGET[p]] = RING[p]
        return out
    }
}
