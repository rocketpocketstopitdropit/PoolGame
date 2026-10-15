package com.poolgame.web

import com.poolgame.app.core.Ball
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Per-pixel lit, rolling balls — the same look as the Android version. */
class Balls {
    companion object {
        val BASE = intArrayOf(
            0xFFF4F0E2.toInt(),
            0xFFF2BE00.toInt(), 0xFF1543B5.toInt(), 0xFFD0201E.toInt(), 0xFF5B2A8E.toInt(),
            0xFFF06612.toInt(), 0xFF117A3C.toInt(), 0xFF7E1C1C.toInt(), 0xFF151515.toInt(),
            0xFFF2BE00.toInt(), 0xFF1543B5.toInt(), 0xFFD0201E.toInt(), 0xFF5B2A8E.toInt(),
            0xFFF06612.toInt(), 0xFF117A3C.toInt(), 0xFF7E1C1C.toInt()
        )
        const val WHITE = 0xFFF4F0E2.toInt()
        const val DOT = 0xFFC8222A.toInt()
        const val PRACTICE_RED = 0xFFC62828.toInt()
        const val PRACTICE_YELLOW = 0xFFF2BE00.toInt()
        fun isStripe(id: Int) = id in 9..15
    }

    var plain = false
        set(v) { field = v; for (i in 0 until 16) lastQ[i][0] = Double.NaN }

    var radius = 0.0       // CSS px
        private set
    private var dpr = 1.0
    private var size = 0   // device px
    private var nx = DoubleArray(0); private var ny = DoubleArray(0); private var nz = DoubleArray(0)
    private var shade = DoubleArray(0); private var spec = DoubleArray(0); private var alpha = DoubleArray(0)
    private val canvases = arrayOfNulls<dynamic>(16)
    private val images = arrayOfNulls<ImageData>(16)
    private val lastQ = Array(16) { DoubleArray(4) { Double.NaN } }
    private val m = DoubleArray(9)
    private var shadow: dynamic = null
    private var shadowSize = 0

    private fun colorOf(id: Int): Int = if (!plain) BASE[id] else when (id) {
        0 -> BASE[0]
        8 -> BASE[8]
        in 1..7 -> PRACTICE_RED
        else -> PRACTICE_YELLOW
    }

    fun setRadius(rCss: Double, devicePixelRatio: Double) {
        if (rCss == radius && devicePixelRatio == dpr) return
        radius = rCss; dpr = devicePixelRatio
        val r = rCss * dpr
        size = ceil(2 * r).toInt() + 2
        val n = size * size
        nx = DoubleArray(n); ny = DoubleArray(n); nz = DoubleArray(n)
        shade = DoubleArray(n); spec = DoubleArray(n); alpha = DoubleArray(n)
        var lx = -0.35; var ly = -0.55; var lz = -1.0
        val ll = sqrt(lx * lx + ly * ly + lz * lz); lx /= ll; ly /= ll; lz /= ll
        var hx = lx; var hy = ly; var hz = lz - 1
        val hl = sqrt(hx * hx + hy * hy + hz * hz); hx /= hl; hy /= hl; hz /= hl
        val c = size / 2.0
        for (j in 0 until size) for (i in 0 until size) {
            val k = j * size + i
            val dx = i + 0.5 - c; val dy = j + 0.5 - c
            val d = sqrt(dx * dx + dy * dy)
            alpha[k] = (r - d + 0.5).coerceIn(0.0, 1.0)
            if (alpha[k] <= 0) continue
            var u = dx / r; var v = dy / r
            val uv = u * u + v * v
            val z: Double
            if (uv >= 1) { val s = 1 / sqrt(uv); u *= s; v *= s; z = 0.0 } else z = -sqrt(1 - uv)
            nx[k] = u; ny[k] = v; nz[k] = z
            val lam = max(0.0, u * lx + v * ly + z * lz)
            shade[k] = (0.38 + 0.66 * lam) * (0.82 + 0.18 * -z)
            spec[k] = max(0.0, u * hx + v * hy + z * hz).pow(70.0) * 0.85
        }
        for (i in 0 until 16) {
            val cv = offscreen(size, size)
            canvases[i] = cv
            images[i] = ctxOf(cv).createImageData(size, size)
            lastQ[i][0] = Double.NaN
        }
        shadowSize = size * 2
        val sh = offscreen(shadowSize, shadowSize)
        val sc = ctxOf(sh)
        val g = sc.createRadialGradient(size.toDouble(), size.toDouble(), 0.0, size.toDouble(), size.toDouble(), r * 1.25)
        g.addColorStop(0.0, "rgba(0,0,0,0.53)"); g.addColorStop(0.6, "rgba(0,0,0,0.27)"); g.addColorStop(1.0, "rgba(0,0,0,0)")
        sc.fillStyle = g
        sc.circle(size.toDouble(), size.toDouble(), r * 1.3); sc.fill()
        shadow = sh
    }

    private fun matrix(b: Ball) {
        val w = b.qw; val x = b.qx; val y = b.qy; val z = b.qz
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y - w * z); m[2] = 2 * (x * z + w * y)
        m[3] = 2 * (x * y + w * z); m[4] = 1 - 2 * (x * x + z * z); m[5] = 2 * (y * z - w * x)
        m[6] = 2 * (x * z - w * y); m[7] = 2 * (y * z + w * x); m[8] = 1 - 2 * (x * x + y * y)
    }

    private fun sprite(b: Ball): dynamic {
        val q = lastQ[b.id]
        val cv = canvases[b.id]
        if (q[0] == b.qw && q[1] == b.qx && q[2] == b.qy && q[3] == b.qz) return cv
        q[0] = b.qw; q[1] = b.qx; q[2] = b.qy; q[3] = b.qz
        matrix(b)
        val base = colorOf(b.id)
        val br = (base shr 16) and 255; val bg = (base shr 8) and 255; val bb = base and 255
        val wr = (WHITE shr 16) and 255; val wg = (WHITE shr 8) and 255; val wb = WHITE and 255
        val dr = (DOT shr 16) and 255; val dg = (DOT shr 8) and 255; val db = DOT and 255
        val stripe = !plain && isStripe(b.id)
        val cue = b.id == 0
        val numbered = !plain
        val m0 = m[0]; val m1 = m[1]; val m2 = m[2]; val m3 = m[3]; val m4 = m[4]; val m5 = m[5]
        val m6 = m[6]; val m7 = m[7]; val m8 = m[8]
        val img = images[b.id]!!
        val data = img.data
        val n = size * size
        for (k in 0 until n) {
            val a = alpha[k]
            val o = k * 4
            if (a <= 0) { data[o + 3] = 0; continue }
            val wx = nx[k]; val wy = ny[k]; val wz = nz[k]
            val bx = m0 * wx + m3 * wy + m6 * wz
            val by = m1 * wx + m4 * wy + m7 * wz
            val bz = m2 * wx + m5 * wy + m8 * wz
            var r: Int; var g: Int; var bl: Int
            if (cue) {
                if (max(max(abs(bx), abs(by)), abs(bz)) > 0.975) { r = dr; g = dg; bl = db } else { r = wr; g = wg; bl = wb }
            } else {
                val disc = numbered && abs(bz) > 0.86
                val colored = if (stripe) abs(bx) < 0.52 && !disc else !disc
                if (colored) { r = br; g = bg; bl = bb } else { r = wr; g = wg; bl = wb }
            }
            val s = shade[k]; val sp = spec[k] * 255
            data[o] = min(255.0, r * s + sp).toInt()
            data[o + 1] = min(255.0, g * s + sp).toInt()
            data[o + 2] = min(255.0, bl * s + sp).toInt()
            data[o + 3] = (a * 255).toInt()
        }
        ctxOf(cv).putImageData(img, 0.0, 0.0)
        return cv
    }

    fun draw(ctx: Ctx2D, b: Ball, sx: Double, sy: Double, scale: Double = 1.0, alphaMul: Double = 1.0) {
        val cv = sprite(b)
        val sz = size / dpr * scale
        ctx.globalAlpha = alphaMul
        ctx.drawImage(cv, sx - sz / 2, sy - sz / 2, sz, sz)
        ctx.globalAlpha = 1.0
        if (b.id == 0 || plain || scale < 0.6) return
        matrix(b)
        val s = if (m[8] < 0) 1.0 else -1.0
        val facing = -s * m[8]
        if (facing < 0.4) return
        val ox = s * m[2] * radius * scale
        val oy = s * m[5] * radius * scale
        ctx.save()
        ctx.globalAlpha = ((facing - 0.4) / 0.3).coerceIn(0.0, 1.0) * alphaMul
        ctx.translate(sx + ox, sy + oy)
        val ang = atan2(oy, ox)
        ctx.rotate(ang); ctx.scale(facing, 1.0); ctx.rotate(-ang)
        ctx.scale(scale, scale)
        ctx.fillStyle = "#111"
        ctx.font = "bold ${(radius * 0.82).toInt().coerceAtLeast(6)}px 'Arial Narrow', Arial, sans-serif"
        ctx.textAlign = "center"; ctx.textBaseline = "middle"
        ctx.fillText(b.id.toString(), 0.0, radius * 0.04)
        ctx.restore()
    }

    /** The ball's shadow on the cloth; [scale] and [alpha] let a ball in the air cast a smaller, fainter one. */
    fun drawShadow(ctx: Ctx2D, sx: Double, sy: Double, scale: Double = 1.0, alpha: Double = 1.0) {
        val sz = shadowSize / dpr * scale
        val a0 = ctx.globalAlpha
        if (alpha < 1.0) ctx.globalAlpha = a0 * alpha
        ctx.drawImage(shadow, sx - sz / 2 + radius * 0.22, sy - sz / 2 + radius * 0.32, sz, sz)
        ctx.globalAlpha = a0
    }

    /** Flat icon for the HUD. */
    /** A ball in the pocketed-ball tray: the number faces out, shaded like a sphere. */
    fun trayBall(ctx: Ctx2D, id: Int, cx: Double, cy: Double, r: Double) {
        icon(ctx, id, cx, cy, r, false)
        val g = ctx.createRadialGradient(cx - r * 0.38, cy - r * 0.42, 0.0, cx - r * 0.38, cy - r * 0.42, r * 1.55)
        g.addColorStop(0.0, "rgba(255,255,255,0.4)"); g.addColorStop(0.32, "rgba(255,255,255,0)")
        g.addColorStop(0.6, "rgba(0,0,0,0)"); g.addColorStop(1.0, "rgba(0,0,0,0.44)")
        ctx.fillStyle = g; ctx.circle(cx, cy, r); ctx.fill()
    }

    fun icon(ctx: Ctx2D, id: Int, cx: Double, cy: Double, r: Double, dim: Boolean) {
        val base = colorOf(id)
        ctx.globalAlpha = if (dim) 0.22 else 1.0
        val striped = !plain && isStripe(id)
        ctx.fillStyle = col(if (striped) WHITE else base)
        ctx.circle(cx, cy, r); ctx.fill()
        if (!plain) {
            if (striped) {
                ctx.save()
                ctx.beginPath(); ctx.rect(cx - r, cy - r * 0.55, 2 * r, r * 1.1); ctx.clip()
                ctx.fillStyle = col(base); ctx.circle(cx, cy, r); ctx.fill()
                ctx.restore()
            }
            ctx.fillStyle = col(WHITE); ctx.circle(cx, cy, r * 0.5); ctx.fill()
            ctx.fillStyle = "#000"
            ctx.font = "bold ${(r * 0.85).toInt().coerceAtLeast(5)}px Arial, sans-serif"
            ctx.textAlign = "center"; ctx.textBaseline = "middle"
            ctx.fillText(id.toString(), cx, cy + r * 0.05)
        }
        ctx.globalAlpha = 1.0
    }
}
