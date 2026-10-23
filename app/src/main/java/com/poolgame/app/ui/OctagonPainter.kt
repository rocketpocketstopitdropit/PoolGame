package com.poolgame.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.poolgame.app.core.BumperColors
import com.poolgame.app.core.Table
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The octagon bumper table seen from above: the wooden ledge, the ramps (each shaded by which way it
 * faces, lighter toward the top), the cloth, the cups ringed in the colour of whoever shoots into
 * them, and the bumpers. Used for the game's table and for the menu's layout preview.
 */
object OctagonPainter {
    private val LIGHT = run { val l = sqrt(0.35 * 0.35 + 0.55 * 0.55 + 1.0); doubleArrayOf(-0.35 / l, -0.55 / l, 1.0 / l) }

    private fun shade(col: Int, k: Double): Int {
        val r = (((col shr 16) and 255) * k).toInt().coerceIn(0, 255)
        val g = (((col shr 8) and 255) * k).toInt().coerceIn(0, 255)
        val b = ((col and 255) * k).toInt().coerceIn(0, 255)
        return (col and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Draws the table with its centre at (cx, cy) on screen and [scale] pixels per metre. [rings] are the
     * cups' ring colours (see [BumperColors.cupRings]); [layout] picks the bumpers.
     */
    fun draw(c: Canvas, cx: Float, cy: Float, scale: Float, layout: Int, rings: IntArray, dp: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun outline(d: Double, dx: Float = 0f, dy: Float = 0f): Path {
            val o = Table.octOutline(d)
            val path = Path()
            for (i in 0 until 8) {
                val x = cx + (o[2 * i] * scale).toFloat() + dx; val y = cy + (o[2 * i + 1] * scale).toFloat() + dy
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
        }
        val outer = Table.octOutline(Table.OCT_OUTER)
        // Shadow under the table, then the wooden ledge.
        p.color = 0x66000000
        c.drawPath(outline(Table.OCT_OUTER, 3 * dp, 6 * dp), p)
        val left = cx + (outer[8] * scale).toFloat(); val right = cx + (outer[0] * scale).toFloat()
        val top = cy + (outer[5] * scale).toFloat(); val bottom = cy + (outer[13] * scale).toFloat()
        p.shader = LinearGradient(left, top, right, bottom,
            intArrayOf(0xFF6B3A1C.toInt(), 0xFF4A2610.toInt(), 0xFF5E3218.toInt(), 0xFF3C1E0C.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(outline(Table.OCT_OUTER), p)
        p.shader = null
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
        val gr = java.util.Random(3)
        repeat(26) {
            grain.color = if (gr.nextBoolean()) 0x18FFFFFF else 0x22000000
            c.drawPath(outline(Table.RAMP_H + 0.008 + gr.nextDouble() * (Table.LEDGE_W - 0.016)), grain)
        }
        grain.color = 0x33FFFFFF; grain.strokeWidth = 1.5f * dp
        c.drawPath(outline(Table.OCT_OUTER - 1.5 / scale), grain)
        // The ramps: one band per side, from the foot to the top, lit by the way it faces.
        val foot = Table.octOutline(0.0)
        val crest = Table.octOutline(Table.RAMP_H)
        val cloth = 0xFF1C7E4C.toInt()
        for (i in 0 until 8) {
            val j = (i + 1) % 8
            val fx0 = cx + (foot[2 * i] * scale).toFloat(); val fy0 = cy + (foot[2 * i + 1] * scale).toFloat()
            val fx1 = cx + (foot[2 * j] * scale).toFloat(); val fy1 = cy + (foot[2 * j + 1] * scale).toFloat()
            val tx0 = cx + (crest[2 * i] * scale).toFloat(); val ty0 = cy + (crest[2 * i + 1] * scale).toFloat()
            val tx1 = cx + (crest[2 * j] * scale).toFloat(); val ty1 = cy + (crest[2 * j + 1] * scale).toFloat()
            // Outward normal of this side, from its two cloth corners.
            var nx = (fy1 - fy0).toDouble(); var ny = -(fx1 - fx0).toDouble()
            val nl = sqrt(nx * nx + ny * ny); nx /= nl; ny /= nl
            val mxF = (fx0 + fx1) / 2; val myF = (fy0 + fy1) / 2
            if (nx * (mxF - cx) + ny * (myF - cy) < 0) { nx = -nx; ny = -ny }
            val r2 = sqrt(2.0)
            val k = (0.62 + 0.5 * (-nx / r2 * LIGHT[0] - ny / r2 * LIGHT[1] + 1 / r2 * LIGHT[2])).coerceIn(0.4, 1.15)
            val path = Path().apply { moveTo(fx0, fy0); lineTo(fx1, fy1); lineTo(tx1, ty1); lineTo(tx0, ty0); close() }
            val mxT = (tx0 + tx1) / 2; val myT = (ty0 + ty1) / 2
            p.shader = LinearGradient(mxF, myF, mxT, myT, shade(cloth, k * 0.82), shade(cloth, k * 1.08), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
        }
        // Creases where the ramps meet, the foot's shadow and the top edge.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = 0x33000000 }
        for (i in 0 until 8) c.drawLine(cx + (foot[2 * i] * scale).toFloat(), cy + (foot[2 * i + 1] * scale).toFloat(),
            cx + (crest[2 * i] * scale).toFloat(), cy + (crest[2 * i + 1] * scale).toFloat(), line)
        line.color = 0x55FFFFFF; line.strokeWidth = 1.4f * dp
        c.drawPath(outline(Table.RAMP_H), line)
        // The cloth.
        val r = (Table.OCT_HX * scale).toFloat()
        p.shader = RadialGradient(cx, cy, r * 1.05f, intArrayOf(0xFF22935A.toInt(), 0xFF1C7E4C.toInt(), 0xFF166A3F.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(outline(0.0), p)
        p.shader = null
        line.color = 0x40000000; line.strokeWidth = 1.2f * dp
        c.drawPath(outline(0.0), line)
        // Diamonds along the ledge.
        p.color = 0xFFEDE3C8.toInt()
        val mid = Table.octOutline(Table.RAMP_H + Table.LEDGE_W / 2)
        val dr = (0.012 * scale).toFloat()
        for (i in 0 until 8) {
            val j = (i + 1) % 8
            for (t in floatArrayOf(0.25f, 0.5f, 0.75f)) {
                val x = cx + ((mid[2 * i] + (mid[2 * j] - mid[2 * i]) * t) * scale).toFloat()
                val y = cy + ((mid[2 * i + 1] + (mid[2 * j + 1] - mid[2 * i + 1]) * t) * scale).toFloat()
                val path = Path().apply { moveTo(x, y - dr); lineTo(x + dr * 0.6f, y); lineTo(x, y + dr); lineTo(x - dr * 0.6f, y); close() }
                c.drawPath(path, p)
            }
        }
        // Cups: the hole is exactly the drop zone, outlined; round it a ring in the shooter's colour.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = 0xFFF2E3B3.toInt() }
        val own = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * dp }
        val shadeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * dp; color = 0x59000000 }
        val cr = (Table.CUP_R * scale).toFloat()
        for ((i, cup) in Table.octCups().withIndex()) {
            val x = cx + (cup[0] * scale).toFloat(); val y = cy + (cup[1] * scale).toFloat()
            own.color = rings.getOrElse(i) { BumperColors.RING_NONE }
            c.drawCircle(x, y, cr + 5 * dp, own)
            p.shader = RadialGradient(x - cr * 0.2f, y - cr * 0.25f, cr,
                intArrayOf(0xFF050505.toInt(), 0xFF101010.toInt(), 0xFF2A2A2A.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, cr, p)
            p.shader = null
            c.drawCircle(x, y, cr, ring)
            c.drawCircle(x, y, cr + 3 * dp, shadeRing)
        }
        // Bumpers: a black rubber ring with a red band and a metal cap, seen from above.
        val pr = (Table.POST_R * scale).toFloat()
        for (pt in Table.octPosts(layout)) {
            val px = cx + (pt[0] * scale).toFloat(); val py = cy + (pt[1] * scale).toFloat()
            post(c, p, px, py, pr)
        }
    }

    /** A bumper seen from above. */
    fun post(c: Canvas, p: Paint, px: Float, py: Float, pr: Float) {
        p.shader = null
        p.color = 0x59000000; c.drawCircle(px + pr * 0.2f, py + pr * 0.32f, pr * 1.08f, p)
        p.color = 0xFF1C1C1C.toInt(); c.drawCircle(px, py, pr, p)
        p.color = 0xFF8E1A1A.toInt(); c.drawCircle(px, py, pr * 0.8f, p)
        p.color = 0xFF1C1C1C.toInt(); c.drawCircle(px, py, pr * 0.62f, p)
        p.shader = RadialGradient(px - pr * 0.2f, py - pr * 0.25f, pr * 0.5f, 0xFFF4F6F8.toInt(), 0xFF8D949B.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(px, py, pr * 0.5f, p)
        p.shader = null
    }
}

/** The menu's small picture of the octagon table with the chosen bumper layout. */
class OctPreview(context: Context) : View(context) {
    var layout = 0
        set(v) { field = v; invalidate() }
    var players = 4
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val tw = 2 * (Table.OCT_HX + Table.OCT_OUTER); val th = 2 * (Table.OCT_HY + Table.OCT_OUTER)
        val scale = (min(w / tw, h / th) * 0.96).toFloat()
        OctagonPainter.draw(canvas, w / 2, h / 2, scale, layout, BumperColors.cupRings(true, players), resources.displayMetrics.density * 0.6f)
    }
}
