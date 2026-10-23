package com.poolgame.web

import com.poolgame.app.core.BumperColors
import com.poolgame.app.core.Table
import kotlin.math.sqrt

/**
 * The octagon bumper table seen from above (the web version of the app's OctagonPainter): the wooden
 * ledge, the ramps (each shaded by which way it faces, lighter toward the top), the cloth, the cups
 * ringed in the colour of whoever shoots into them, and the bumpers.
 */
object OctagonPainter {
    private val LIGHT = run { val l = sqrt(0.35 * 0.35 + 0.55 * 0.55 + 1.0); doubleArrayOf(-0.35 / l, -0.55 / l, 1.0 / l) }

    private fun shade(c: Int, k: Double): String {
        val r = (((c shr 16) and 255) * k).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 255) * k).toInt().coerceIn(0, 255)
        val b = ((c and 255) * k).toInt().coerceIn(0, 255)
        return "rgb($r,$g,$b)"
    }

    /** Draws the table with its centre at (cx, cy) and [scale] pixels per metre; [rings] are the cups' ring colours. */
    fun draw(c: Ctx2D, cx: Double, cy: Double, scale: Double, layout: Int, rings: IntArray, px: Double = 1.0) {
        fun outline(d: Double, dx: Double = 0.0, dy: Double = 0.0) {
            val o = Table.octOutline(d)
            c.beginPath()
            for (i in 0 until 8) {
                val x = cx + o[2 * i] * scale + dx; val y = cy + o[2 * i + 1] * scale + dy
                if (i == 0) c.moveTo(x, y) else c.lineTo(x, y)
            }
            c.closePath()
        }
        val outer = Table.octOutline(Table.OCT_OUTER)
        c.fillStyle = "rgba(0,0,0,0.4)"; outline(Table.OCT_OUTER, 3 * px, 6 * px); c.fill()
        val wood = c.createLinearGradient(cx + outer[8] * scale, cy + outer[13] * scale, cx + outer[0] * scale, cy + outer[5] * scale)
        wood.addColorStop(0.0, "#6B3A1C"); wood.addColorStop(0.33, "#4A2610"); wood.addColorStop(0.66, "#5E3218"); wood.addColorStop(1.0, "#3C1E0C")
        c.fillStyle = wood; outline(Table.OCT_OUTER); c.fill()
        // Wood grain round the ledge.
        var seed = 3
        fun rnd(): Double { seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF; return seed / 2147483647.0 }
        c.lineWidth = 1.0
        repeat(26) {
            c.strokeStyle = if (rnd() < 0.5) "rgba(255,255,255,0.09)" else "rgba(0,0,0,0.13)"
            outline(Table.RAMP_H + 0.008 + rnd() * (Table.LEDGE_W - 0.016)); c.stroke()
        }
        c.strokeStyle = "rgba(255,255,255,0.2)"; c.lineWidth = 1.5 * px
        outline(Table.OCT_OUTER - 1.5 / scale); c.stroke()
        // The ramps: one band per side, from the foot to the top, lit by the way it faces.
        val foot = Table.octOutline(0.0)
        val crest = Table.octOutline(Table.RAMP_H)
        val cloth = 0xFF1C7E4C.toInt()
        for (i in 0 until 8) {
            val j = (i + 1) % 8
            val fx0 = cx + foot[2 * i] * scale; val fy0 = cy + foot[2 * i + 1] * scale
            val fx1 = cx + foot[2 * j] * scale; val fy1 = cy + foot[2 * j + 1] * scale
            val tx0 = cx + crest[2 * i] * scale; val ty0 = cy + crest[2 * i + 1] * scale
            val tx1 = cx + crest[2 * j] * scale; val ty1 = cy + crest[2 * j + 1] * scale
            var nx = fy1 - fy0; var ny = -(fx1 - fx0)
            val nl = sqrt(nx * nx + ny * ny); nx /= nl; ny /= nl
            val mxF = (fx0 + fx1) / 2; val myF = (fy0 + fy1) / 2
            if (nx * (mxF - cx) + ny * (myF - cy) < 0) { nx = -nx; ny = -ny }
            val r2 = sqrt(2.0)
            val k = (0.62 + 0.5 * (-nx / r2 * LIGHT[0] - ny / r2 * LIGHT[1] + 1 / r2 * LIGHT[2])).coerceIn(0.4, 1.15)
            val g = c.createLinearGradient(mxF, myF, (tx0 + tx1) / 2, (ty0 + ty1) / 2)
            g.addColorStop(0.0, shade(cloth, k * 0.82)); g.addColorStop(1.0, shade(cloth, k * 1.08))
            c.fillStyle = g
            c.beginPath(); c.moveTo(fx0, fy0); c.lineTo(fx1, fy1); c.lineTo(tx1, ty1); c.lineTo(tx0, ty0); c.closePath(); c.fill()
        }
        // Creases where the ramps meet, and the top edge.
        c.strokeStyle = "rgba(0,0,0,0.2)"; c.lineWidth = 1.0 * px
        for (i in 0 until 8) c.line(cx + foot[2 * i] * scale, cy + foot[2 * i + 1] * scale, cx + crest[2 * i] * scale, cy + crest[2 * i + 1] * scale)
        c.strokeStyle = "rgba(255,255,255,0.33)"; c.lineWidth = 1.4 * px
        outline(Table.RAMP_H); c.stroke()
        // The cloth.
        val r = Table.OCT_HX * scale
        val clothG = c.createRadialGradient(cx, cy, 0.0, cx, cy, r * 1.05)
        clothG.addColorStop(0.0, "#22935A"); clothG.addColorStop(0.6, "#1C7E4C"); clothG.addColorStop(1.0, "#166A3F")
        c.fillStyle = clothG; outline(0.0); c.fill()
        c.strokeStyle = "rgba(0,0,0,0.25)"; c.lineWidth = 1.2 * px; c.stroke()
        // Diamonds along the ledge.
        c.fillStyle = "#EDE3C8"
        val mid = Table.octOutline(Table.RAMP_H + Table.LEDGE_W / 2)
        val dr = 0.012 * scale
        for (i in 0 until 8) {
            val j = (i + 1) % 8
            for (t in doubleArrayOf(0.25, 0.5, 0.75)) {
                val x = cx + (mid[2 * i] + (mid[2 * j] - mid[2 * i]) * t) * scale
                val y = cy + (mid[2 * i + 1] + (mid[2 * j + 1] - mid[2 * i + 1]) * t) * scale
                c.beginPath(); c.moveTo(x, y - dr); c.lineTo(x + dr * 0.6, y); c.lineTo(x, y + dr); c.lineTo(x - dr * 0.6, y); c.closePath(); c.fill()
            }
        }
        // Cups, ringed in the colour of whoever shoots into them.
        val cr = Table.CUP_R * scale
        for ((i, cup) in Table.octCups().withIndex()) {
            val x = cx + cup[0] * scale; val y = cy + cup[1] * scale
            c.strokeStyle = col(rings.getOrElse(i) { BumperColors.RING_NONE }); c.lineWidth = 4.0 * px
            c.circle(x, y, cr + 5 * px); c.stroke()
            val hole = c.createRadialGradient(x - cr * 0.2, y - cr * 0.25, 0.0, x, y, cr)
            hole.addColorStop(0.0, "#050505"); hole.addColorStop(0.7, "#101010"); hole.addColorStop(1.0, "#2A2A2A")
            c.fillStyle = hole; c.circle(x, y, cr); c.fill()
            c.strokeStyle = "#F2E3B3"; c.lineWidth = 2.0 * px; c.circle(x, y, cr); c.stroke()
            c.strokeStyle = "rgba(0,0,0,0.35)"; c.lineWidth = 3.0 * px; c.circle(x, y, cr + 3 * px); c.stroke()
        }
        // Bumpers.
        val pr = Table.POST_R * scale
        for (pt in Table.octPosts(layout)) post(c, cx + pt[0] * scale, cy + pt[1] * scale, pr)
    }

    /** A bumper seen from above: a black rubber ring with a red band and a metal cap. */
    fun post(c: Ctx2D, px: Double, py: Double, pr: Double) {
        c.fillStyle = "rgba(0,0,0,0.35)"; c.circle(px + pr * 0.2, py + pr * 0.32, pr * 1.08); c.fill()
        c.fillStyle = "#1C1C1C"; c.circle(px, py, pr); c.fill()
        c.fillStyle = "#8E1A1A"; c.circle(px, py, pr * 0.8); c.fill()
        c.fillStyle = "#1C1C1C"; c.circle(px, py, pr * 0.62); c.fill()
        val cap = c.createRadialGradient(px - pr * 0.2, py - pr * 0.25, 0.0, px, py, pr * 0.5)
        cap.addColorStop(0.0, "#F4F6F8"); cap.addColorStop(1.0, "#8D949B")
        c.fillStyle = cap; c.circle(px, py, pr * 0.5); c.fill()
    }
}
