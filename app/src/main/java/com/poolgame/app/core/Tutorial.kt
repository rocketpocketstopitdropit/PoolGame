package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Which control on the game screen a tutorial step points at. */
enum class TutTarget { NONE, TABLE, WHEEL, SPIN, JUMP, POWER, TRAY, CUE, MENU }

/**
 * The hands-on tutorial: a short tour of the game screen's controls, played on a real table.
 * Each step highlights one control and waits until the player has actually used it (or, for the
 * few steps that only point something out, until they tap the card's button).
 *
 * This is plain Kotlin shared by the Android app and the web version; each screen draws the
 * highlight, the card and the moving "finger" itself, and reports what the player did through
 * the on...() calls below.
 */
class Tutorial {

    enum class Step(val target: TutTarget, val title: String, val button: String?) {
        WELCOME(TutTarget.NONE, "LEARN THE CONTROLS", "Start"),
        AIM(TutTarget.TABLE, "AIM", null),
        FINE_AIM(TutTarget.WHEEL, "FINE AIM", null),
        SPIN(TutTarget.SPIN, "SPIN", null),
        JUMP(TutTarget.JUMP, "JUMP", null),
        POWER(TutTarget.POWER, "POWER & SHOOT", null),
        TRAY(TutTarget.TRAY, "BALL TRAY", "Next"),
        BALL_IN_HAND(TutTarget.CUE, "BALL IN HAND", null),
        MENU(TutTarget.MENU, "PAUSE MENU", "Next"),
        DONE(TutTarget.NONE, "YOU'RE ALL SET", "Finish")
    }

    var step = Step.WELCOME
        private set
    /** Seconds left of the "Got it" flash before moving on (0 when not flashing). */
    var flash = 0.0
        private set
    private var entered = true

    /** True once after each new step starts (read it every frame), so the screen can prepare for that step. */
    fun takeEntered(): Boolean { val e = entered; entered = false; return e }

    private var aimTurned = 0.0      // radians the cue has been turned by dragging on the table
    private var wheelMoved = 0.0     // dp the fine-aim wheel has been dragged
    private var spinSet = false
    var jumpRaised = false
        private set
    /** The tutorial shot has been played and the balls are still rolling. */
    var rolling = false
        private set
    var potted = false
        private set
    private var cueMoved = 0.0       // metres the cue ball has been dragged (ball in hand)

    /** Steps that count toward "Step n of N" (not the welcome and finish cards). */
    val total get() = Step.DONE.ordinal - 1
    val number get() = step.ordinal

    val finished get() = step == Step.DONE && flash <= 0.0 && done
    private var done = false

    val title get() = step.title

    val body: String get() = when (step) {
        Step.WELCOME -> "A one-minute, hands-on tour of the controls. Use the highlighted control the way the card " +
            "says; it moves on by itself once you've got it. Tap Skip any time to leave."
        Step.AIM -> "Drag your finger anywhere on the table. The cue points from the cue ball toward your finger, " +
            "and the lines show where the balls will go."
        Step.FINE_AIM -> "Drag up or down on the FINE AIM roller to nudge the cue a tiny bit. Slow drags are extra " +
            "fine; a quick flick swings it further."
        Step.SPIN -> "Touch the white ball where you want the cue tip to strike: top, bottom, left or right. The red " +
            "dot marks the spot. Tap its centre to reset."
        Step.JUMP -> if (!jumpRaised) "Slide the JUMP slider up to tilt the cue up for a jump shot (0–60°)."
            else "Now slide it back down to the bottom (level) for a normal shot."
        Step.POWER -> if (rolling) "Watch it roll…"
            else "We've lined up a shot for you. Put a finger on the POWER bar and pull down: the further, the " +
                "harder. Let go to shoot. Slide back to the top before letting go to cancel."
        Step.TRAY -> (if (potted) "Nice shot! " else "Missed — no problem. ") +
            "Every ball you pocket rolls down this tray, lined up in the order it was made."
        Step.BALL_IN_HAND -> "With ball in hand, a gold ring pulses around the cue ball. Drag the cue ball to put it " +
            "where you want it."
        Step.MENU -> "Tap ☰ to pause (on Android the Back button does it too): resume, turn shot prediction on or " +
            "off, restart, or go back to the main menu. Your game is saved when you leave. Try it, then tap Resume."
        Step.DONE -> "You can play this tour again, or see a labelled map of every control on the screen, under " +
            "CONTROLS in the main menu."
    }

    /** True while [t] may be used: only the highlighted control works during a step. */
    fun allows(t: TutTarget): Boolean = flash <= 0.0 && !rolling && step.target == t

    /** Call once per frame. */
    fun update(dt: Double) {
        if (flash > 0.0) {
            flash -= dt
            if (flash <= 0.0) advance()
        }
    }

    /** The card's button (Start / Next / Finish). */
    fun next() {
        if (step.button == null || flash > 0.0) return
        if (step == Step.DONE) { done = true; return }
        advance()
    }

    private fun advance() {
        flash = 0.0
        if (step == Step.DONE) return
        step = Step.values()[step.ordinal + 1]
        entered = true
    }

    private fun complete() { if (flash <= 0.0) flash = 0.9 }

    // ------------------------------------------------------------------ what the player did

    /** The cue was turned by [delta] radians by dragging on the table. */
    fun onAim(delta: Double) {
        if (step != Step.AIM) return
        aimTurned += abs(delta)
        if (aimTurned > 0.5) complete()
    }

    /** The fine-aim wheel was dragged [dpMoved] dp. */
    fun onWheel(dpMoved: Double) {
        if (step != Step.FINE_AIM) return
        wheelMoved += abs(dpMoved)
        if (wheelMoved > 90.0) complete()
    }

    /** The spin was set to (x, y), each -1..1. */
    fun onSpin(x: Double, y: Double) {
        if (step == Step.SPIN && hypot(x, y) > 0.35) spinSet = true
    }

    /** The cue angle is now [deg] degrees. */
    fun onElevation(deg: Double) {
        if (step != Step.JUMP) return
        if (deg >= 20.0) jumpRaised = true
    }

    /** A finger was lifted off [t]. Steps that end on letting go finish here. */
    fun onRelease(t: TutTarget, spinNow: Double, elevNow: Double) {
        when {
            step == Step.SPIN && t == TutTarget.SPIN && spinSet && spinNow > 0.2 -> complete()
            step == Step.JUMP && t == TutTarget.JUMP && jumpRaised && elevNow < 0.5 -> complete()
            step == Step.BALL_IN_HAND && t == TutTarget.CUE && cueMoved > 0.12 -> complete()
        }
    }

    /** The pause menu was opened. */
    fun onMenuOpened() { if (step == Step.MENU) complete() }

    fun onShot() { if (step == Step.POWER) rolling = true }

    /** The tutorial shot has stopped rolling; [made] if the 1 went in. */
    fun onShotDone(made: Boolean) {
        if (step != Step.POWER || !rolling) return
        rolling = false
        potted = made
        advance()
    }

    /** The cue ball was dragged [metres] (ball in hand). */
    fun onCueMoved(metres: Double) { if (step == Step.BALL_IN_HAND) cueMoved += abs(metres) }

    // ------------------------------------------------------------------ the demonstration finger

    /**
     * Where the demonstration finger is at time [t] (seconds), for a control occupying the box
     * (l, top, r, b) on screen. Writes x, y, opacity (0..1) and press (0 = hovering, 1 = touching)
     * into [out]. The finger loops through the gesture the current step asks for.
     */
    fun finger(t: Double, l: Double, top: Double, r: Double, b: Double, out: DoubleArray) {
        val w = r - l; val h = b - top
        val cycle = 2.6
        val k = (t % cycle) / cycle
        // 0..0.12 come in and press, 0.12..0.62 move, 0.62..0.75 lift, then fade out.
        val press = when { k < 0.12 -> k / 0.12; k < 0.62 -> 1.0; k < 0.75 -> 1 - (k - 0.62) / 0.13; else -> 0.0 }
        val m = ((k - 0.12) / 0.5).coerceIn(0.0, 1.0)
        val e = m * m * (3 - 2 * m)
        val alpha = when { k < 0.06 -> k / 0.06; k > 0.85 -> (1 - k) / 0.15; else -> 1.0 }
        var x0 = 0.5; var y0 = 0.5; var x1 = 0.5; var y1 = 0.5
        when (step.target) {
            TutTarget.TABLE -> { x0 = 0.62; y0 = 0.78; x1 = 0.86; y1 = 0.5 }
            TutTarget.WHEEL -> { x0 = 0.5; y0 = 0.62; x1 = 0.5; y1 = 0.3 }
            TutTarget.SPIN -> { x0 = 0.5; y0 = 0.5; x1 = 0.5; y1 = 0.84 }
            TutTarget.JUMP -> if (!jumpRaised) { y0 = 0.95; y1 = 0.2 } else { y0 = 0.2; y1 = 0.97 }
            TutTarget.POWER -> { y0 = 0.05; y1 = 0.62 }
            TutTarget.CUE -> { x0 = 0.5; y0 = 0.5; x1 = 0.5 + 1.6 * cos(PI * 0.15); y1 = 0.5 + 1.6 * sin(PI * 0.15) }
            else -> {}   // tap in the middle
        }
        out[0] = l + w * (x0 + (x1 - x0) * e)
        out[1] = top + h * (y0 + (y1 - y0) * e)
        out[2] = alpha
        out[3] = press
    }

    companion object {
        const val TARGET_BALL = 1
        private const val CUT = 15.0 * PI / 180

        /**
         * The tutorial table: the 1 near a corner pocket with the cue ball set for a gentle cut
         * (easy at any power, and the cue ball runs away from the pocket instead of following the 1
         * in), plus a few balls out of the way.
         */
        fun setup(m: Match) {
            val w = m.world
            for (b in w.balls) { b.onTable = false; b.stop() }
            val pk = pocket()
            val t = w.balls[TARGET_BALL]
            t.x = pk.aimX - 0.47; t.y = pk.aimY - 0.19; t.onTable = true
            val d = hypot(pk.aimX - t.x, pk.aimY - t.y)
            val ux = (pk.aimX - t.x) / d; val uy = (pk.aimY - t.y) / d
            val gx = t.x - ux * 2 * P.R; val gy = t.y - uy * 2 * P.R
            // The cue ball comes in 15 degrees off the line to the pocket.
            val a = atan2(uy, ux) - CUT
            val c = w.balls[0]
            c.x = gx - 0.8 * cos(a); c.y = gy - 0.8 * sin(a); c.onTable = true
            fun put(id: Int, x: Double, y: Double) { val b = w.balls[id]; b.x = x; b.y = y; b.onTable = true }
            put(3, P.L * 0.8, P.W * 0.24)
            put(6, P.L * 0.52, P.W * 0.14)
            put(12, P.L * 0.9, P.W * 0.62)
            m.ballInHand = false
            m.kitchenOnly = false
        }

        /**
         * Ball in hand: the cue ball is put in the open lower-left part of the table (clear of the
         * card), so it's easy to see and grab.
         */
        fun placeForBallInHand(m: Match) {
            val w = m.world
            m.ballInHand = true; m.kitchenOnly = false
            for (fx in doubleArrayOf(0.3, 0.22, 0.38, 0.46, 0.14)) for (fy in doubleArrayOf(0.72, 0.62, 0.82)) {
                val x = P.L * fx; val y = P.W * fy
                if (w.isFree(x, y, 0)) { m.placeCue(x, y); return }
            }
            if (!w.balls[0].onTable) m.respawnCue()
        }

        /** The pocket the tutorial shot goes into: the corner nearest the bottom right of the screen. */
        fun pocket(): Pocket = Table.pockets.filter { !it.side }.maxByOrNull { it.cx + it.cy } ?: Table.pockets.first()

        /** Aim straight through the 1 into [pocket]. */
        fun lineUp(w: World): Double {
            val c = w.balls[0]; val t = w.balls[TARGET_BALL]
            if (!t.onTable) return 0.0
            val pk = pocket()
            val d = hypot(pk.aimX - t.x, pk.aimY - t.y)
            val gx = t.x - (pk.aimX - t.x) / d * 2 * P.R
            val gy = t.y - (pk.aimY - t.y) / d * 2 * P.R
            return atan2(gy - c.y, gx - c.x)
        }
    }
}
