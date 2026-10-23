package com.poolgame.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.poolgame.app.core.Ball
import com.poolgame.app.core.BumperColors
import com.poolgame.app.core.NB
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Renders each ball as a lit 3D sphere, per pixel, using the ball's orientation quaternion so
 * stripes, number discs and the cue ball's dots visibly roll and show spin.
 */
class BallRenderer {
    companion object {
        val BASE = intArrayOf(
            0xFFF4F0E2.toInt(), // cue (ivory)
            0xFFF2BE00.toInt(), 0xFF1543B5.toInt(), 0xFFD0201E.toInt(), 0xFF5B2A8E.toInt(),
            0xFFF06612.toInt(), 0xFF117A3C.toInt(), 0xFF7E1C1C.toInt(), 0xFF151515.toInt(),
            0xFFF2BE00.toInt(), 0xFF1543B5.toInt(), 0xFFD0201E.toInt(), 0xFF5B2A8E.toInt(),
            0xFFF06612.toInt(), 0xFF117A3C.toInt(), 0xFF7E1C1C.toInt()
        )
        const val WHITE = 0xFFF4F0E2.toInt()
        const val DOT = 0xFFC8222A.toInt()
        fun isStripe(id: Int) = id in 9..15
        // Practice set: plain red and yellow balls (plus the black), no numbers.
        const val PRACTICE_RED = 0xFFC62828.toInt()
        const val PRACTICE_YELLOW = 0xFFF2BE00.toInt()
        // Bumper pool: five balls per player (red, white, and on the octagon table blue and yellow); each
        // player's marked one is spotted in another colour.
        fun isMarked(id: Int) = BumperColors.isMarked(id)
        // Light from upper left, above the table (world space; up is -z).
        private val LL = sqrt(0.35f * 0.35f + 0.55f * 0.55f + 1f)
        val LX = -0.35f / LL; val LY = -0.55f / LL; val LZ = -1f / LL
    }

    /** Practice style: solid colours, no stripes or numbers. */
    var plain = false
        set(v) { field = v; for (i in 0 until NB) lastQ[i][0] = Double.NaN }

    /** Bumper pool style: red balls 1-5, white 6-10, blue 11-15, yellow 16-20, no numbers; the first of each five is spotted (the marked balls). */
    var bumper = false
        set(v) { field = v; for (i in 0 until NB) lastQ[i][0] = Double.NaN }

    private fun colorOf(id: Int): Int = if (bumper) BumperColors.ball(id) else if (!plain) BASE[id.coerceAtMost(15)] else when (id) {
        0 -> BASE[0]
        8 -> BASE[8]
        in 1..7 -> PRACTICE_RED
        else -> PRACTICE_YELLOW
    }

    var radius = 0f
        private set

    /**
     * Seen from a camera instead of from straight above (the 3D view): the screen's right, down and
     * toward-the-viewer directions in world space (9 numbers), or null for the top-down view.
     */
    private var view: DoubleArray? = null
    private val viewNow = DoubleArray(9)
    private val viewDrawn = Array(NB) { DoubleArray(9) { Double.NaN } }

    fun setView(v: DoubleArray?) {
        if (v == null) { view = null; return }
        v.copyInto(viewNow); view = viewNow
    }
    private var size = 0
    private lateinit var nx: FloatArray
    private lateinit var ny: FloatArray
    private lateinit var nz: FloatArray
    private lateinit var shade: FloatArray
    private lateinit var spec: FloatArray
    private lateinit var alpha: FloatArray
    private lateinit var px: IntArray
    private val bitmaps = arrayOfNulls<Bitmap>(NB)
    private val lastQ = Array(NB) { DoubleArray(4) { Double.NaN } }
    private val m = DoubleArray(9)
    var shadow: Bitmap? = null
        private set

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    fun setRadius(r: Float) {
        if (r == radius) return
        radius = r
        size = ceil(2 * r).toInt() + 2
        val n = size * size
        nx = FloatArray(n); ny = FloatArray(n); nz = FloatArray(n)
        shade = FloatArray(n); spec = FloatArray(n); alpha = FloatArray(n)
        px = IntArray(n)
        // Light from upper left, above the table. Up is -z.
        var lx = -0.35f; var ly = -0.55f; var lz = -1.0f
        val ll = sqrt(lx * lx + ly * ly + lz * lz); lx /= ll; ly /= ll; lz /= ll
        var hx = lx; var hy = ly; var hz = lz - 1f
        val hl = sqrt(hx * hx + hy * hy + hz * hz); hx /= hl; hy /= hl; hz /= hl
        val c = size / 2f
        for (j in 0 until size) for (i in 0 until size) {
            val k = j * size + i
            val dx = i + 0.5f - c; val dy = j + 0.5f - c
            val d = sqrt(dx * dx + dy * dy)
            alpha[k] = (r - d + 0.5f).coerceIn(0f, 1f)
            if (alpha[k] <= 0f) continue
            var u = dx / r; var v = dy / r
            val uv = u * u + v * v
            val z: Float
            if (uv >= 1f) { val s = 1f / sqrt(uv); u *= s; v *= s; z = 0f } else z = -sqrt(1f - uv)
            nx[k] = u; ny[k] = v; nz[k] = z
            val lam = max(0f, u * lx + v * ly + z * lz)
            shade[k] = (0.38f + 0.66f * lam) * (0.82f + 0.18f * -z)
            spec[k] = max(0f, u * hx + v * hy + z * hz).toDouble().pow(70.0).toFloat() * 0.85f
        }
        for (i in 0 until NB) { bitmaps[i]?.recycle(); bitmaps[i] = null; lastQ[i][0] = Double.NaN }
        val sh = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(size.toFloat(), size.toFloat(), r * 1.25f,
            intArrayOf(0x88000000.toInt(), 0x44000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        Canvas(sh).drawCircle(size.toFloat(), size.toFloat(), r * 1.3f, p)
        shadow?.recycle(); shadow = sh
        numPaint.textSize = r * 0.82f
    }

    private fun matrix(b: Ball) {
        val w = b.qw; val x = b.qx; val y = b.qy; val z = b.qz
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y - w * z); m[2] = 2 * (x * z + w * y)
        m[3] = 2 * (x * y + w * z); m[4] = 1 - 2 * (x * x + z * z); m[5] = 2 * (y * z - w * x)
        m[6] = 2 * (x * z - w * y); m[7] = 2 * (y * z + w * x); m[8] = 1 - 2 * (x * x + y * y)
    }

    fun sprite(b: Ball): Bitmap {
        val q = lastQ[b.id]
        val cached = bitmaps[b.id]
        val vw = view
        val vd = viewDrawn[b.id]
        val sameView = if (vw == null) vd[0].isNaN() else vd.contentEquals(vw)
        if (cached != null && sameView && q[0] == b.qw && q[1] == b.qx && q[2] == b.qy && q[3] == b.qz) return cached
        q[0] = b.qw; q[1] = b.qx; q[2] = b.qy; q[3] = b.qz
        if (vw == null) vd[0] = Double.NaN else vw.copyInto(vd)
        matrix(b)
        val base = colorOf(b.id)
        val br = (base shr 16) and 255; val bg = (base shr 8) and 255; val bb = base and 255
        val wr = (WHITE shr 16) and 255; val wg = (WHITE shr 8) and 255; val wb = WHITE and 255
        val dr = (DOT shr 16) and 255; val dg = (DOT shr 8) and 255; val db = DOT and 255
        val stripe = !plain && !bumper && isStripe(b.id)
        val cue = b.id == 0
        val numbered = !plain && !bumper
        // A marked bumper pool ball: spots of the other colour (white on red, red on white).
        val marked = bumper && isMarked(b.id)
        val spot = BumperColors.spot(b.id)
        val sr = (spot shr 16) and 255; val sg = (spot shr 8) and 255; val sb = spot and 255
        val m0 = m[0].toFloat(); val m1 = m[1].toFloat(); val m2 = m[2].toFloat()
        val m3 = m[3].toFloat(); val m4 = m[4].toFloat(); val m5 = m[5].toFloat()
        val m6 = m[6].toFloat(); val m7 = m[7].toFloat(); val m8 = m[8].toFloat()
        val n = size * size
        // 3D view: the screen's axes in world space, and the light and highlight worked out per pixel.
        val v0 = (vw?.get(0) ?: 1.0).toFloat(); val v1 = (vw?.get(1) ?: 0.0).toFloat(); val v2 = (vw?.get(2) ?: 0.0).toFloat()
        val v3 = (vw?.get(3) ?: 0.0).toFloat(); val v4 = (vw?.get(4) ?: 1.0).toFloat(); val v5 = (vw?.get(5) ?: 0.0).toFloat()
        val v6 = (vw?.get(6) ?: 0.0).toFloat(); val v7 = (vw?.get(7) ?: 0.0).toFloat(); val v8 = (vw?.get(8) ?: -1.0).toFloat()
        var hx = LX + v6; var hy = LY + v7; var hz = LZ + v8
        val hl = sqrt(hx * hx + hy * hy + hz * hz); hx /= hl; hy /= hl; hz /= hl
        for (k in 0 until n) {
            val a = alpha[k]
            if (a <= 0f) { px[k] = 0; continue }
            var wx = nx[k]; var wy = ny[k]; var wz = nz[k]
            var sh = shade[k]; var spc = spec[k]
            if (vw != null) {
                // nx, ny are the screen offsets; -nz is toward the viewer.
                val u = wx; val vv = wy; val t = -wz
                wx = u * v0 + vv * v3 + t * v6
                wy = u * v1 + vv * v4 + t * v7
                wz = u * v2 + vv * v5 + t * v8
                val lam = max(0f, wx * LX + wy * LY + wz * LZ)
                sh = (0.38f + 0.66f * lam) * (0.82f + 0.18f * t)
                var hs = max(0f, wx * hx + wy * hy + wz * hz)
                hs *= hs; hs *= hs; hs *= hs; hs *= hs; hs *= hs; hs *= hs   // ^64
                spc = hs * 0.85f
            }
            // world -> body (transpose)
            val bx = m0 * wx + m3 * wy + m6 * wz
            val by = m1 * wx + m4 * wy + m7 * wz
            val bz = m2 * wx + m5 * wy + m8 * wz
            var r: Int; var g: Int; var bl: Int
            if (cue) {
                val dot = max(max(kotlin.math.abs(bx), kotlin.math.abs(by)), kotlin.math.abs(bz)) > 0.975f
                if (dot) { r = dr; g = dg; bl = db } else { r = wr; g = wg; bl = wb }
            } else if (marked) {
                val dot = max(max(kotlin.math.abs(bx), kotlin.math.abs(by)), kotlin.math.abs(bz)) > 0.93f
                if (dot) { r = sr; g = sg; bl = sb } else { r = br; g = bg; bl = bb }
            } else {
                val disc = numbered && kotlin.math.abs(bz) > 0.86f
                val colored = if (stripe) kotlin.math.abs(bx) < 0.52f && !disc else !disc
                if (colored) { r = br; g = bg; bl = bb } else { r = wr; g = wg; bl = wb }
            }
            val s = sh; val sp = spc * 255f
            r = min(255f, r * s + sp).toInt(); g = min(255f, g * s + sp).toInt(); bl = min(255f, bl * s + sp).toInt()
            val ai = (a * 255f).toInt()
            // premultiplied-free ARGB; Bitmap.setPixels expects non-premultiplied
            px[k] = (ai shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val bmp = cached ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmaps[b.id] = it }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /** Draw ball `b` centred at (sx, sy) on screen. */
    fun draw(canvas: Canvas, b: Ball, sx: Float, sy: Float, scale: Float = 1f, paint: Paint? = null) {
        val bmp = sprite(b)
        val half = size / 2f
        if (scale == 1f) {
            canvas.drawBitmap(bmp, sx - half, sy - half, paint)
        } else {
            canvas.save()
            canvas.translate(sx, sy); canvas.scale(scale, scale)
            canvas.drawBitmap(bmp, -half, -half, paint)
            canvas.restore()
        }
        if (b.id == 0 || plain || bumper || scale * radius < 6f) return
        // Number on whichever disc faces the viewer.
        matrix(b)
        val vw = view
        // The disc axis (body z) in world space, then on screen.
        val ax = m[2]; val ay = m[5]; val az = m[8]
        val toward: Double; val sxo: Double; val syo: Double
        if (vw == null) { toward = -az; sxo = ax; syo = ay }
        else {
            toward = ax * vw[6] + ay * vw[7] + az * vw[8]
            sxo = ax * vw[0] + ay * vw[1] + az * vw[2]
            syo = ax * vw[3] + ay * vw[4] + az * vw[5]
        }
        val s = if (toward > 0) 1.0 else -1.0
        val facing = (s * toward).toFloat()
        if (facing < 0.4f) return
        val ox = (s * sxo).toFloat() * radius * scale
        val oy = (s * syo).toFloat() * radius * scale
        numPaint.alpha = (255 * ((facing - 0.4f) / 0.3f).coerceIn(0f, 1f)).toInt()
        canvas.save()
        canvas.translate(sx + ox, sy + oy)
        // Foreshorten the number along the radial direction.
        val ang = Math.toDegrees(kotlin.math.atan2(oy.toDouble(), ox.toDouble())).toFloat()
        canvas.rotate(ang); canvas.scale(facing, 1f); canvas.rotate(-ang)
        canvas.scale(scale, scale)
        canvas.drawText(b.id.toString(), 0f, numPaint.textSize * 0.36f, numPaint)
        canvas.restore()
    }

    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** The ball's shadow on the cloth; [scale] and [alpha] let a ball in the air cast a smaller, fainter one. */
    fun drawShadow(canvas: Canvas, sx: Float, sy: Float, scale: Float = 1f, alpha: Int = 255) {
        val sh = shadow ?: return
        if (scale == 1f && alpha == 255) {
            canvas.drawBitmap(sh, sx - size + radius * 0.22f, sy - size + radius * 0.32f, null)
            return
        }
        shadowPaint.alpha = alpha
        canvas.save()
        canvas.translate(sx + radius * 0.22f, sy + radius * 0.32f)
        canvas.scale(scale, scale)
        canvas.drawBitmap(sh, -size.toFloat(), -size.toFloat(), shadowPaint)
        canvas.restore()
    }

    // Unit-circle shading laid over a flat icon to make it look like a lit ball.
    private val trayShade = RadialGradient(-0.38f, -0.42f, 1.55f,
        intArrayOf(0x66FFFFFF, 0x00FFFFFF, 0x00000000, 0x70000000), floatArrayOf(0f, 0.32f, 0.6f, 1f), Shader.TileMode.CLAMP)

    /** A ball in the pocketed-ball tray: the number faces out, shaded like a sphere. */
    fun drawTrayBall(canvas: Canvas, id: Int, cx: Float, cy: Float, r: Float, p: Paint) {
        drawIcon(canvas, id, cx, cy, r, false, p)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r, r)
        p.style = Paint.Style.FILL
        p.color = Color.BLACK
        p.shader = trayShade
        canvas.drawCircle(0f, 0f, 1f, p)
        p.shader = null
        canvas.restore()
    }

    /** Flat icon used in the HUD. */
    fun drawIcon(canvas: Canvas, id: Int, cx: Float, cy: Float, r: Float, dim: Boolean, p: Paint) {
        p.style = Paint.Style.FILL
        p.shader = null
        val base = colorOf(id)
        val alphaMul = if (dim) 0.22f else 1f
        val striped = !plain && !bumper && isStripe(id)
        p.color = if (striped) WHITE else base
        p.alpha = (255 * alphaMul).toInt()
        canvas.drawCircle(cx, cy, r, p)
        if (bumper) {
            if (isMarked(id)) {
                p.color = BumperColors.spot(id); p.alpha = (255 * alphaMul).toInt()
                canvas.drawCircle(cx, cy, r * 0.32f, p)
            }
            p.alpha = 255
            return
        }
        if (plain) { p.alpha = 255; return }
        if (striped) {
            canvas.save()
            canvas.clipRect(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.55f)
            p.color = base; p.alpha = (255 * alphaMul).toInt()
            canvas.drawCircle(cx, cy, r, p)
            canvas.restore()
        }
        p.color = WHITE; p.alpha = (255 * alphaMul).toInt()
        canvas.drawCircle(cx, cy, r * 0.5f, p)
        p.color = Color.BLACK; p.alpha = (255 * alphaMul).toInt()
        p.textAlign = Paint.Align.CENTER
        p.typeface = numPaint.typeface
        p.textSize = r * 0.85f
        canvas.drawText(id.toString(), cx, cy + r * 0.3f, p)
        p.alpha = 255
    }
}
