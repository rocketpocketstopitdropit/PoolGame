package com.poolgame.app.core

import kotlin.math.max
import kotlin.math.min

/**
 * The CONTROLS screen: a labelled map of the game screen plus what every input does and how to
 * use it. Shared by the Android app and the web version so both say the same thing; each draws
 * the map itself from [layout], which uses the same measurements as the game screen.
 */
object ControlsGuide {

    /** One numbered control on the map. */
    class Item(val n: Int, val name: String, val where: String, val how: List<String>)

    /** A box on the screen map: left, top, right, bottom in dp. */
    class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    /** Where everything is on a landscape game screen [w] x [h] dp (the same layout the game uses). */
    class Layout(
        val menu: Box, val leftCard: Box, val status: Box, val rightCard: Box,
        val power: Box, val tray: Box, val table: Box, val cloth: Box,
        val jump: Box, val spinCx: Double, val spinCy: Double, val spinR: Double, val wheel: Box
    )

    fun layout(w: Double, h: Double): Layout {
        val hudH = max(h * 0.13, 44.0)
        val leftW = max(w * 0.08, 58.0)
        val trayW = 28.0
        val stripW = 26.0
        val rightW = max(w * 0.15, 96.0) + stripW
        val tray = Box(leftW, hudH + 34, leftW + trayW, h - 18)
        val areaL = tray.r + 6; val areaR = w - rightW
        val rail = 0.115
        val tw = P.L + 2 * rail; val th = P.W + 2 * rail
        val scale = min((areaR - areaL) / tw, (h - hudH - 6) / th) * 0.98
        val ox = areaL + ((areaR - areaL) - tw * scale) / 2
        val oy = hudH + ((h - hudH) - th * scale) / 2
        val table = Box(ox, oy, ox + tw * scale, oy + th * scale)
        val cloth = Box(ox + rail * scale, oy + rail * scale, ox + (rail + P.L) * scale, oy + (rail + P.W) * scale)
        val pw = min(leftW * 0.45, 30.0)
        val power = Box(leftW / 2 - pw / 2, hudH + 34, leftW / 2 + pw / 2, h - 18)
        val colL = w - rightW
        val stripCx = colL + 4 + stripW / 2
        val spinL = colL + stripW + 8; val spinRt = w - 8
        val spinR = min((spinRt - spinL) * 0.42, (h - hudH) * 0.15)
        val spinCx = (spinL + spinRt) / 2
        val spinCy = hudH + 24 + spinR
        val jump = Box(stripCx - 3, spinCy - spinR, stripCx + 3, spinCy + spinR + 16)
        val wheel = Box(colL + 8, jump.b + 38, w - 10, h - 14)
        val menu = Box(8.0, 8.0, 52.0, 44.0)
        val cardW = min(w * 0.27, 260.0)
        val cardH = hudH - 16
        val leftCard = Box(menu.r + 10, 8.0, menu.r + 10 + cardW, 8 + cardH)
        val rightCard = Box(w - 8 - cardW, 8.0, w - 8, 8 + cardH)
        val status = Box(leftCard.r + 10, 8.0, rightCard.l - 10, 8 + cardH)
        return Layout(menu, leftCard, status, rightCard, power, tray, table, cloth, jump, spinCx, spinCy, spinR, wheel)
    }

    /** The screen map is drawn for a typical phone held sideways. */
    const val MAP_W = 800.0
    const val MAP_H = 370.0

    /** Where each numbered badge goes on the map (dp, same space as [layout]), in [ITEMS] order. */
    fun badges(l: Layout): List<Pair<Double, Double>> = listOf(
        l.menu.r - 2 to l.menu.b - 2,                             // 1 menu
        l.leftCard.cx to l.leftCard.b + 14,                       // 2 player cards
        l.status.cx to l.status.b + 14,                           // 3 status line
        l.power.cx to l.power.t + (l.power.b - l.power.t) * 0.45, // 4 power
        l.tray.cx to l.tray.b - 30,                               // 5 tray
        l.cloth.l + (l.cloth.r - l.cloth.l) * 0.62 to l.cloth.cy, // 6 table
        l.jump.cx to l.jump.t + (l.jump.b - l.jump.t) * 0.4,       // 7 jump
        l.spinCx to l.spinCy,                                     // 8 spin
        l.wheel.cx to l.wheel.cy                                  // 9 fine aim
    )

    /** The part of the map to outline when item [i] (0-based, [ITEMS] order) is selected. */
    fun areas(l: Layout, i: Int): List<Box> {
        fun grow(b: Box, d: Double) = Box(b.l - d, b.t - d, b.r + d, b.b + d)
        return when (i) {
            0 -> listOf(grow(l.menu, 4.0))
            1 -> listOf(grow(l.leftCard, 4.0), grow(l.rightCard, 4.0))
            2 -> listOf(grow(l.status, 2.0))
            3 -> listOf(Box(l.power.l - 8, l.power.t - 24, l.power.r + 8, l.power.b + 8))
            4 -> listOf(grow(l.tray, 5.0))
            5 -> listOf(grow(l.table, 3.0))
            6 -> listOf(Box(l.jump.l - 12, l.jump.t - 22, l.jump.r + 12, l.jump.b + 28))
            7 -> { val r = l.spinR + 8; listOf(Box(l.spinCx - r, l.spinCy - r, l.spinCx + r, l.spinCy + r)) }
            else -> listOf(Box(l.wheel.l - 5, l.wheel.t - 20, l.wheel.r + 5, l.wheel.b + 5))
        }
    }

    val ITEMS = listOf(
        Item(1, "Menu ☰", "Top left", listOf(
            "Tap to pause the game.",
            "The pause menu has: Resume, Shot prediction on/off, Restart, and Main menu.",
            "Going to the main menu keeps your game: it shows a RESUME button.",
            "On Android, the phone's Back button opens the same menu."
        )),
        Item(2, "Player cards", "Top left and top right", listOf(
            "Show each player. A gold outline marks whose turn it is.",
            "Below the name: your group of balls (8-ball), the ball to hit (9-ball) or your attempts (trick shots).",
            "With the shot clock on, the seconds left and a shrinking bar appear on your card.",
            "Practice: this area becomes buttons instead. Free table has Retry shot (put every ball back as it was " +
                "before your last shot), Re-rack and Clear table. Trick-shot practice has ◀ Level, Reset and Level ▶."
        )),
        Item(3, "Status line", "Top centre", listOf(
            "Tells you what to do right now, for example \"ball in hand — drag the cue ball\" or \"call your pocket\".",
            "In 9-ball, the row of small balls shows which are still on the table.",
            "This is information only; it can't be tapped."
        )),
        Item(4, "Power bar", "Left edge", listOf(
            "Put a finger anywhere on the bar and pull down. The further you pull, the harder the shot; the " +
                "percentage shows under the bar.",
            "Let go to shoot.",
            "To cancel, slide back up to the top before letting go.",
            "While you hold the power bar you can use another finger on the table, fine aim, spin or jump."
        )),
        Item(5, "Ball tray", "Left, next to the power bar", listOf(
            "Every ball that's pocketed rolls down the tray and lines up from the bottom, in the order it was made.",
            "Practice: tap a ball in the tray to put it back on the table."
        )),
        Item(6, "Table", "Centre", listOf(
            "Aim: drag anywhere on the table. The cue points from the cue ball toward your finger.",
            "Ball in hand: a gold ring pulses around the cue ball. Drag the cue ball to place it (before the break, " +
                "only in the shaded area). A red circle means it can't go there.",
            "Calling a pocket (when Call pocket is on in the menu): tap the pocket you're shooting at; tap it again " +
                "to cancel. Gold rings around the pockets mean a call is needed before you can shoot.",
            "Practice: tap a ball to aim at it. Hold a finger still on any ball for a moment to pick it up, then " +
                "drag it anywhere."
        )),
        Item(7, "Jump slider", "Right, beside the spin ball", listOf(
            "Slide up to tilt the cue up for a jump shot, from level up to 60°. The angle shows under the slider.",
            "Slide all the way down for a normal, level shot.",
            "It goes back to level after every shot."
        )),
        Item(8, "Spin ball", "Right, top", listOf(
            "Touch the white ball where you want the cue tip to strike the cue ball; drag to adjust. A red dot " +
                "marks the spot and the label underneath names it (follow, draw, left, right…).",
            "Tap the centre to reset it.",
            "It resets at the start of every turn."
        )),
        Item(9, "Fine-aim roller", "Right, bottom", listOf(
            "Drag up or down on the roller to turn the cue a little at a time.",
            "It speeds up with your finger: slow drags are extra fine, a quick flick swings the cue a long way.",
            "Change its base speed in the main menu under CONTROLS (Fine-aim wheel speed)."
        ))
    )

    /** Extra notes shown under the numbered list. */
    class Section(val title: String, val lines: List<String>)

    val SECTIONS = listOf(
        Section("Reading the lines on the table", listOf(
            "Dashed white line: the path of the cue ball up to the first ball it hits.",
            "White circle: where the cue ball will be when it touches that ball. The solid line shows where that " +
                "ball goes; the thin line shows where the cue ball goes afterwards.",
            "Red circle with an X: you're not allowed to hit that ball first.",
            "Red ring: the cue ball would go into a pocket.",
            "Yellow line: the cue ball is in the air (jump shot). The yellow ring with a dot is where it lands.",
            "With Shot prediction turned off, you only get a straight dashed line from the cue ball to the cushion."
        )),
        Section("Using two fingers", listOf(
            "Hold the power bar with one finger and keep adjusting with another: drag on the table, the fine-aim " +
                "roller, the spin ball or the jump slider. Let go of the power bar when it's right."
        )),
        Section("Rock, paper, scissors (who breaks)", listOf(
            "Tap Rock, Paper or Scissors under your name. In pass & play each player taps their own side; picks " +
                "stay hidden until both are in.",
            "After the reveal, tap anywhere to continue."
        )),
        Section("End of a game", listOf(
            "Tap Rematch to play again or Menu to go back to the main menu."
        ))
    )
}
