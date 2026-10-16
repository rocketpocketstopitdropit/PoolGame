package com.poolgame.app.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The 3D view down the cue (POV). Plain Kotlin shared by the Android app and the web version:
 * [PovCam] places the camera behind the cue ball, over the cue, and projects table points to the
 * screen; [PovScene] turns the table and balls into a list of flat polygons and ball sprites,
 * already projected and in drawing order, which each screen just paints.
 *
 * 3D points here are (x, y, h): x and y on the table as in the rest of the engine, h the height
 * above the cloth in metres (up > 0).
 */
class PovCam {
    // Eye position and the camera's axes (forward, right, up), all in (x, y, h).
    var ex = 0.0; var ey = 0.0; var eh = 0.0
    var fx = 1.0; var fy = 0.0; var fh = 0.0
    var rx = 0.0; var ry = 1.0; var rh = 0.0
    var ux = 0.0; var uy = 0.0; var uh = 1.0
    var focal = 400.0
    var cx = 0.0; var cy = 0.0
    // The view rectangle on screen.
    var left = 0.0; var top = 0.0; var right = 0.0; var bottom = 0.0

    /**
     * Put the eye over the cue: [BACK] metres back along the cue from the cue ball (so it rises with
     * the cue on a jump shot) and [ABOVE] above the shaft, looking a little way past the cue ball.
     * The view fills the screen rectangle (l, t, r, b).
     */
    fun setup(cueX: Double, cueY: Double, aim: Double, elevation: Double, l: Double, t: Double, r: Double, b: Double) {
        left = l; top = t; right = r; bottom = b
        val dx = cos(aim); val dy = sin(aim)
        val ce = cos(elevation); val se = sin(elevation)
        // Up along the cue's own "up" (perpendicular to the shaft, in the vertical plane).
        // (The eye climbs only part of the way up the raised cue, so a jump shot still shows what's ahead.)
        ex = cueX - dx * (BACK * ce - ABOVE * se)
        ey = cueY - dy * (BACK * ce - ABOVE * se)
        eh = P.R + CLIMB * BACK * se + ABOVE * ce
        // Look along the aim, pitched so the cue ball sits a little below the middle of the view
        // (CUE_LOW of the way down from the centre), whatever the cue's angle.
        val hx = cueX - ex; val hy = cueY - ey
        val down = kotlin.math.atan2(eh - P.R, hx * dx + hy * dy)
        val low = CUE_LOW + 0.3 * se
        val pitch = (down - kotlin.math.atan(low * tan(HALF_FOV))).coerceAtLeast(0.0)
        fx = dx * cos(pitch); fy = dy * cos(pitch); fh = -sin(pitch)
        // Right: the shooter's right, level with the table.
        rx = -dy; ry = dx; rh = 0.0
        // Up = right x forward, flipped if needed so it points up.
        ux = ry * fh - rh * fy; uy = rh * fx - rx * fh; uh = rx * fy - ry * fx
        if (uh < 0) { ux = -ux; uy = -uy; uh = -uh }
        cx = (l + r) / 2; cy = (t + b) / 2
        focal = ((b - t) / 2) / tan(HALF_FOV)
    }

    /** Distance in front of the eye (along the view direction). */
    fun depth(x: Double, y: Double, h: Double) = (x - ex) * fx + (y - ey) * fy + (h - eh) * fh

    /** Projects (x, y, h) to the screen: writes sx, sy, depth into out[o..o+2]. False if it's behind the near plane. */
    fun project(x: Double, y: Double, h: Double, out: DoubleArray, o: Int = 0): Boolean {
        val px = x - ex; val py = y - ey; val ph = h - eh
        val d = px * fx + py * fy + ph * fh
        out[o + 2] = d
        // (Points cut exactly at the near plane can land a hair in front of or behind it: still project them.)
        val dd = if (d < NEAR) NEAR else d
        out[o] = cx + focal * (px * rx + py * ry + ph * rh) / dd
        out[o + 1] = cy - focal * (px * ux + py * uy + ph * uh) / dd
        return d >= NEAR * 0.999
    }

    /**
     * A line segment on screen, cut at the near plane: writes x1, y1, x2, y2 into [out]. False if
     * it's entirely behind the eye.
     */
    fun segment(x1: Double, y1: Double, h1: Double, x2: Double, y2: Double, h2: Double, out: DoubleArray): Boolean {
        var ax = x1; var ay = y1; var ah = h1; var bx = x2; var by = y2; var bh = h2
        val d1 = depth(ax, ay, ah); val d2 = depth(bx, by, bh)
        if (d1 < NEAR && d2 < NEAR) return false
        if (d1 < NEAR) { val k = (NEAR - d1) / (d2 - d1); ax += (bx - ax) * k; ay += (by - ay) * k; ah += (bh - ah) * k }
        else if (d2 < NEAR) { val k = (NEAR - d2) / (d1 - d2); bx += (ax - bx) * k; by += (ay - by) * k; bh += (ah - bh) * k }
        project(ax, ay, ah, tmp, 0); out[0] = tmp[0]; out[1] = tmp[1]
        project(bx, by, bh, tmp, 0); out[2] = tmp[0]; out[3] = tmp[1]
        return true
    }
    private val tmp = DoubleArray(3)

    /** On-screen radius of a ball whose centre is [depth] away. */
    fun ballRadius(depth: Double) = focal * P.R / depth

    /**
     * How a ball looks from here, for drawing its sprite: the screen's right, down and toward-the-viewer
     * directions in the engine's ball frame (x, y on the table, z into the table).
     */
    fun viewBasis(out: DoubleArray) {
        out[0] = rx; out[1] = ry; out[2] = -rh
        out[3] = -ux; out[4] = -uy; out[5] = uh
        out[6] = -fx; out[7] = -fy; out[8] = fh
    }

    companion object {
        const val NEAR = 0.03
        const val BACK = 0.42
        const val ABOVE = 0.12
        const val CUE_LOW = 0.5
        const val CLIMB = 0.6
        val HALF_FOV = 25.0 * PI / 180
    }
}

/**
 * The table and balls seen from a [PovCam], as a list of things to paint in order (back to front).
 * Everything is already projected to screen coordinates.
 */
class PovScene {
    companion object {
        const val POLY = 0
        const val BALL = 1

        // Polygon colours (ARGB). A polygon with [CLOTH] colour gets the cloth's lighting gradient.
        const val CLOTH = 0xFF1C7E4C.toInt()
        const val CLOTH_EDGE = 0xFF145F38.toInt()
        const val CUSHION_FACE = 0xFF0F5232.toInt()
        const val CUSHION_TOP = 0xFF187243.toInt()
        const val WOOD = 0xFF5E3218.toInt()
        const val WOOD_SIDE = 0xFF3C1E0C.toInt()
        const val WOOD_FAR = 0xFF4A2610.toInt()
        const val SLATE = 0xFF2C3034.toInt()
        const val POCKET = 0xFF050505.toInt()
        const val SHADOW = 0x5A000000
        const val DIAMOND = 0xFFEDE3C8.toInt()

        const val NOSE_H = 0.036     // cushion nose height
        const val RAIL_H = 0.044     // top of the rails
        const val CUSHION_W = 0.045  // cushion nose to the wooden rail
        const val RAIL_W = 0.115     // cushion nose to the outside of the table
        const val APRON = 0.2        // how far the table's sides go down below the cloth
        private const val PIECE = 0.14   // long rails are cut into pieces this long so they sort with the balls
    }

    // One entry per item: kind, colour (polygons) or ball id, start and count into the point arrays
    // (polygons) or the ball's screen x, y, radius (balls), and the depth used for sorting.
    var n = 0
        private set
    private var cap = 512
    var kind = IntArray(cap); private set
    var color = IntArray(cap); private set
    var start = IntArray(cap); private set
    var count = IntArray(cap); private set
    var bx = DoubleArray(cap); private set
    var by = DoubleArray(cap); private set
    var br = DoubleArray(cap); private set
    private var depth = DoubleArray(cap)
    private var layer = IntArray(cap)
    var order = IntArray(cap); private set
    /** Items order[0 until guideAt] are on (or under) the cloth: draw the aim guide after them, then the rest. */
    var guideAt = 0
        private set
    /** Screen points of all polygons. */
    var px = DoubleArray(4096); private set
    var py = DoubleArray(4096); private set
    private var np = 0

    // Where the cloth's lighting is brightest, and how far it spreads (screen).
    var clothCx = 0.0; var clothCy = 0.0; var clothR = 1.0

    private lateinit var cam: PovCam
    private val q = DoubleArray(3)
    // Scratch for clipping one polygon (3D, before projection).
    private val cx3 = DoubleArray(64); private val cy3 = DoubleArray(64); private val ch3 = DoubleArray(64)
    private val ox3 = DoubleArray(64); private val oy3 = DoubleArray(64); private val oh3 = DoubleArray(64)

    private fun grow() {
        cap *= 2
        kind = kind.copyOf(cap); color = color.copyOf(cap); start = start.copyOf(cap); count = count.copyOf(cap)
        bx = bx.copyOf(cap); by = by.copyOf(cap); br = br.copyOf(cap); depth = depth.copyOf(cap)
        layer = layer.copyOf(cap); order = order.copyOf(cap)
    }

    /** Adds a flat polygon given by its 3D corners (cut at the near plane). [cull]: skip it when seen from behind. */
    private fun poly(xs: DoubleArray, ys: DoubleArray, hs: DoubleArray, k: Int, col: Int, lay: Int, cull: Boolean = false) {
        if (cull && k >= 3) {
            // Normal from the first three corners; visible if the eye is on its front side.
            val ax = xs[1] - xs[0]; val ay = ys[1] - ys[0]; val ah = hs[1] - hs[0]
            val bxv = xs[2] - xs[0]; val byv = ys[2] - ys[0]; val bh = hs[2] - hs[0]
            val nx = ay * bh - ah * byv; val ny = ah * bxv - ax * bh; val nh = ax * byv - ay * bxv
            if ((cam.ex - xs[0]) * nx + (cam.ey - ys[0]) * ny + (cam.eh - hs[0]) * nh <= 0) return
        }
        // Sutherland-Hodgman against the near plane.
        var m = 0
        var sumD = 0.0
        for (i in 0 until k) {
            val j = (i + 1) % k
            val di = cam.depth(xs[i], ys[i], hs[i]); val dj = cam.depth(xs[j], ys[j], hs[j])
            val inI = di >= PovCam.NEAR; val inJ = dj >= PovCam.NEAR
            if (inI) { ox3[m] = xs[i]; oy3[m] = ys[i]; oh3[m] = hs[i]; m++; sumD += di }
            if (inI != inJ) {
                val t = (PovCam.NEAR - di) / (dj - di)
                ox3[m] = xs[i] + (xs[j] - xs[i]) * t; oy3[m] = ys[i] + (ys[j] - ys[i]) * t; oh3[m] = hs[i] + (hs[j] - hs[i]) * t
                m++; sumD += PovCam.NEAR
            }
            if (m >= 62) break
        }
        if (m < 3) return
        if (np + m > px.size) { px = px.copyOf(px.size * 2); py = py.copyOf(py.size * 2) }
        val s = np
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until m) {
            cam.project(ox3[i], oy3[i], oh3[i], q)
            px[np] = q[0]; py[np] = q[1]; np++
            if (q[0] < minX) minX = q[0]; if (q[0] > maxX) maxX = q[0]
            if (q[1] < minY) minY = q[1]; if (q[1] > maxY) maxY = q[1]
        }
        // Entirely off screen: drop it.
        if (maxX < cam.left || minX > cam.right || maxY < cam.top || minY > cam.bottom) { np = s; return }
        if (n >= cap) grow()
        kind[n] = POLY; color[n] = col; start[n] = s; count[n] = m
        depth[n] = sumD / m; layer[n] = lay
        n++
    }

    private val qx = DoubleArray(4); private val qy = DoubleArray(4); private val qh = DoubleArray(4)

    private fun quad(x0: Double, y0: Double, h0: Double, x1: Double, y1: Double, h1: Double,
                     x2: Double, y2: Double, h2: Double, x3: Double, y3: Double, h3: Double, col: Int, lay: Int, cull: Boolean = false) {
        qx[0] = x0; qy[0] = y0; qh[0] = h0; qx[1] = x1; qy[1] = y1; qh[1] = h1
        qx[2] = x2; qy[2] = y2; qh[2] = h2; qx[3] = x3; qy[3] = y3; qh[3] = h3
        poly(qx, qy, qh, 4, col, lay, cull)
    }

    /** A flat circle lying level at height [h] (pockets, shadows). */
    private fun disc(x: Double, y: Double, h: Double, r: Double, col: Int, lay: Int, segs: Int = 20) {
        for (i in 0 until segs) {
            val a = 2 * PI * i / segs
            cx3[i] = x + r * cos(a); cy3[i] = y + r * sin(a); ch3[i] = h
        }
        poly(cx3, cy3, ch3, segs, col, lay)
    }

    /** Builds the scene: the table as it is now, and the balls in [w] (the cue ball drawn at (cueX, cueY)). */
    fun build(cam: PovCam, w: World, cueX: Double, cueY: Double) {
        this.cam = cam
        n = 0; np = 0
        val L = P.L; val W = P.W
        val rw = RAIL_W

        // ---- layer 0: the sides of the table, the cut faces of the gap, then the cloth.
        val lo = -APRON
        val xSpans = if (Table.gap) listOf(-rw to Table.GAP_X0, Table.GAP_X1 to L + rw) else listOf(-rw to L + rw)
        for ((a, b) in xSpans) {
            // Long sides, facing out (top: -y, bottom: +y).
            quad(a, -rw, RAIL_H, b, -rw, RAIL_H, b, -rw, lo, a, -rw, lo, WOOD_SIDE, 0, cull = true)
            quad(b, W + rw, RAIL_H, a, W + rw, RAIL_H, a, W + rw, lo, b, W + rw, lo, WOOD_SIDE, 0, cull = true)
        }
        quad(-rw, W + rw, RAIL_H, -rw, -rw, RAIL_H, -rw, -rw, lo, -rw, W + rw, lo, WOOD_SIDE, 0, cull = true)
        quad(L + rw, -rw, RAIL_H, L + rw, W + rw, RAIL_H, L + rw, W + rw, lo, L + rw, -rw, lo, WOOD_SIDE, 0, cull = true)
        if (Table.gap) {
            // Cut faces across the gap: the near section's faces +x, the far section's faces -x.
            val g0 = Table.GAP_X0; val g1 = Table.GAP_X1
            val sl = -0.03
            quad(g0, W + rw, 0.0, g0, -rw, 0.0, g0, -rw, sl, g0, W + rw, sl, SLATE, 0, cull = true)
            quad(g1, -rw, 0.0, g1, W + rw, 0.0, g1, W + rw, sl, g1, -rw, sl, SLATE, 0, cull = true)
            quad(g0, W + rw, sl, g0, -rw, sl, g0, -rw, lo, g0, W + rw, lo, WOOD_SIDE, 0, cull = true)
            quad(g1, -rw, sl, g1, W + rw, sl, g1, W + rw, lo, g1, -rw, lo, WOOD_SIDE, 0, cull = true)
        }
        // Cloth (the playing surface, out to the cushions' backs).
        val cw = CUSHION_W
        val clothSpans = if (Table.gap) listOf(-cw to Table.GAP_X0, Table.GAP_X1 to L + cw) else listOf(-cw to L + cw)
        for ((a, b) in clothSpans) {
            quad(a, -cw, 0.0, b, -cw, 0.0, b, W + cw, 0.0, a, W + cw, 0.0, CLOTH, 0)
        }
        cam.project(L / 2, W / 2, 0.0, q)
        clothCx = q[0]; clothCy = q[1]
        clothR = cam.focal * L * 0.75 / q[2].coerceAtLeast(0.3)

        // ---- layer 1: pockets and holes in the cloth, and the balls' shadows.
        for (pk in Table.pockets) {
            if (Table.gap && Table.overGap(pk.cx)) continue
            disc(pk.cx, pk.cy, 0.0005, pk.capR, POCKET, 1)
        }
        for (b in w.balls) {
            if (!b.onTable || b.z < 0.0) continue
            val x = if (b.id == 0) cueX else b.x; val y = if (b.id == 0) cueY else b.y
            val up = (b.z / 0.12).coerceIn(0.0, 1.0)
            disc(x + 0.006, y + 0.008, 0.001, P.R * (0.95 - 0.3 * up), SHADOW, 1, 14)
        }
        guideAt = n

        // ---- layer 2, sorted by depth: cushions, the wooden rails, the pockets' rims, diamonds and balls.
        for (sg in Table.segs) {
            val len = hypot(sg.dx, sg.dy)
            if (len < 1e-6) continue
            // Inward normal: the side facing the middle of the table (or of its section).
            var nx = -sg.dy / len; var ny = sg.dx / len
            val mx = (sg.ax + sg.bx) / 2; val my = (sg.ay + sg.by) / 2
            val midX = if (Table.gap) (if (mx < Table.GAP_X0) Table.GAP_X0 / 2 else (Table.GAP_X1 + L) / 2) else L / 2
            if ((midX - mx) * nx + (W / 2 - my) * ny < 0) { nx = -nx; ny = -ny }
            val pieces = kotlin.math.max(1, kotlin.math.ceil(len / PIECE).toInt())
            for (i in 0 until pieces) {
                val ta = i.toDouble() / pieces; val tb = (i + 1).toDouble() / pieces
                val ax = sg.ax + sg.dx * ta; val ay = sg.ay + sg.dy * ta
                val bxx = sg.ax + sg.dx * tb; val byy = sg.ay + sg.dy * tb
                // Face: from the cloth up to the nose, facing into the table.
                faceToward(ax, ay, bxx, byy, nx, ny, 0.0, NOSE_H, CUSHION_FACE)
                // Top: from the nose back and up to the rail.
                quad(ax, ay, NOSE_H, bxx, byy, NOSE_H, bxx - nx * cw, byy - ny * cw, RAIL_H, ax - nx * cw, ay - ny * cw, RAIL_H, CUSHION_TOP, 2)
            }
        }
        // Wooden rails: a strip along each side, from the cushions' backs to the outside edge.
        for ((a, b) in xSpans) {
            strip(a, -rw, b, -cw)
            strip(a, W + cw, b, W + rw)
        }
        stripY(-rw, -cw, W + cw)
        stripY(L + cw, L + rw, W + cw, far = true)
        // Pocket mouths cut into the rails: the pocket's circle, but only the part beyond the line
        // between its jaw tips (inside that line there's no rail, just the cloth's own hole).
        for (pk in Table.pockets) {
            if (pk.hole) continue
            railHole(pk)
        }
        // Diamonds.
        val dOff = RAIL_W * 0.6
        for (i in 1..7) if (i != 4 && !(Table.gap && i in 2..3)) {
            val x = L * i / 8
            disc(x, -dOff, RAIL_H + 0.001, 0.007, DIAMOND, 2, 6)
            disc(x, W + dOff, RAIL_H + 0.001, 0.007, DIAMOND, 2, 6)
        }
        for (i in 1..3) {
            val y = W * i / 4
            disc(-dOff, y, RAIL_H + 0.001, 0.007, DIAMOND, 2, 6)
            disc(L + dOff, y, RAIL_H + 0.001, 0.007, DIAMOND, 2, 6)
        }
        // Balls.
        for (b in w.balls) {
            if (!b.onTable) continue
            val x = if (b.id == 0) cueX else b.x; val y = if (b.id == 0) cueY else b.y
            if (!cam.project(x, y, P.R + b.z, q)) continue
            val r = cam.ballRadius(q[2])
            if (q[0] + r < cam.left || q[0] - r > cam.right || q[1] + r < cam.top || q[1] - r > cam.bottom) continue
            if (n >= cap) grow()
            kind[n] = BALL; color[n] = b.id; bx[n] = q[0]; by[n] = q[1]; br[n] = r
            depth[n] = q[2]; layer[n] = 2
            n++
        }

        // Order: layers 0 and 1 as built; layer 2 far to near.
        for (i in 0 until n) order[i] = i
        val l2 = guideAt
        // Insertion sort is fine: a few hundred items, mostly in order frame to frame.
        val tail = order.copyOfRange(l2, n).sortedByDescending { depth[it] }
        for (i in tail.indices) order[l2 + i] = tail[i]
    }

    private fun railHole(pk: Pocket) {
        val t = pk.tips
        // Line through the tips; keep the side the pocket's centre is on.
        val lx = t[2] - t[0]; val ly = t[3] - t[1]
        var nx = -ly; var ny = lx
        if ((pk.cx - t[0]) * nx + (pk.cy - t[1]) * ny < 0) { nx = -nx; ny = -ny }
        val segs = 20
        // A little smaller than the drop zone and set back from the table, so it stays clear of the jaws.
        val nl = hypot(nx, ny)
        val ccx = pk.cx + nx / nl * 0.025; val ccy = pk.cy + ny / nl * 0.025
        val r = pk.capR * 0.85
        var m = 0
        for (i in 0 until segs) {
            val a0 = 2 * PI * i / segs; val a1 = 2 * PI * (i + 1) / segs
            val x0 = ccx + r * cos(a0); val y0 = ccy + r * sin(a0)
            val x1 = ccx + r * cos(a1); val y1 = ccy + r * sin(a1)
            val s0 = (x0 - t[0]) * nx + (y0 - t[1]) * ny; val s1 = (x1 - t[0]) * nx + (y1 - t[1]) * ny
            if (s0 >= 0) { cx3[m] = x0; cy3[m] = y0; m++ }
            if ((s0 >= 0) != (s1 >= 0)) {
                val k = s0 / (s0 - s1)
                cx3[m] = x0 + (x1 - x0) * k; cy3[m] = y0 + (y1 - y0) * k; m++
            }
        }
        if (m < 3) return
        for (i in 0 until m) ch3[i] = RAIL_H + 0.0005
        // One dark opening from the rail's top down to the hole in the cloth: the outline around both
        // (on screen), rather than two separate holes with cushion showing between them.
        var k = 0
        var ok = true
        for (i in 0 until m) {
            if (!cam.project(cx3[i], cy3[i], ch3[i], q)) { ok = false; break }
            hx[k] = q[0]; hy[k] = q[1]; k++
        }
        if (ok) for (i in 0 until 16) {
            val a = 2 * PI * i / 16
            if (!cam.project(pk.cx + pk.capR * cos(a), pk.cy + pk.capR * sin(a), 0.0, q)) { ok = false; break }
            hx[k] = q[0]; hy[k] = q[1]; k++
        }
        if (!ok) { poly(cx3, cy3, ch3, m, POCKET, 2); return }
        val hn = hull(k)
        if (hn < 3) return
        if (np + hn > px.size) { px = px.copyOf(px.size * 2); py = py.copyOf(py.size * 2) }
        val st = np
        for (i in 0 until hn) { px[np] = ox3[i]; py[np] = oy3[i]; np++ }
        if (n >= cap) grow()
        kind[n] = POLY; color[n] = POCKET; start[n] = st; count[n] = hn
        depth[n] = cam.depth(ccx, ccy, RAIL_H); layer[n] = 2
        n++
    }

    private val hx = DoubleArray(64); private val hy = DoubleArray(64)

    /** Convex hull of the screen points hx/hy[0 until k] (monotone chain), written to ox3/oy3. Returns its size. */
    private fun hull(k: Int): Int {
        val idx = (0 until k).sortedWith(compareBy({ hx[it] }, { hy[it] }))
        fun cross(o: Int, a: Int, b: Int) = (hx[a] - hx[o]) * (hy[b] - hy[o]) - (hy[a] - hy[o]) * (hx[b] - hx[o])
        val h = IntArray(2 * k)
        var t = 0
        for (i in idx) { while (t >= 2 && cross(h[t - 2], h[t - 1], i) <= 0) t--; h[t++] = i }
        val lower = t + 1
        for (j in idx.indices.reversed()) {
            val i = idx[j]
            while (t >= lower && cross(h[t - 2], h[t - 1], i) <= 0) t--
            h[t++] = i
        }
        val cnt = (t - 1).coerceAtMost(62)
        for (i in 0 until cnt) { ox3[i] = hx[h[i]]; oy3[i] = hy[h[i]] }
        return cnt
    }

    /** A vertical face from (ax, ay) to (bx, by), heights h0..h1, drawn only from the side its normal (nx, ny) faces. */
    private fun faceToward(ax: Double, ay: Double, bx: Double, by: Double, nx: Double, ny: Double, h0: Double, h1: Double, col: Int) {
        val mx = (ax + bx) / 2; val my = (ay + by) / 2
        if ((cam.ex - mx) * nx + (cam.ey - my) * ny <= 0) return
        quad(ax, ay, h0, bx, by, h0, bx, by, h1, ax, ay, h1, col, 2)
    }

    /** A wooden rail strip along x from [x0] to [x1], between y0 and y1, cut into pieces. */
    private fun strip(x0: Double, y0: Double, x1: Double, y1: Double) {
        val pieces = kotlin.math.max(1, kotlin.math.ceil((x1 - x0) / PIECE).toInt())
        for (i in 0 until pieces) {
            val a = x0 + (x1 - x0) * i / pieces; val b = x0 + (x1 - x0) * (i + 1) / pieces
            quad(a, y0, RAIL_H, b, y0, RAIL_H, b, y1, RAIL_H, a, y1, RAIL_H, WOOD, 2)
        }
    }

    /** A wooden rail strip along y (the short ends), from y = -CUSHION_W to [y1]. */
    private fun stripY(x0: Double, x1: Double, y1: Double, far: Boolean = false) {
        val y0 = -CUSHION_W
        val pieces = kotlin.math.max(1, kotlin.math.ceil((y1 - y0) / PIECE).toInt())
        for (i in 0 until pieces) {
            val a = y0 + (y1 - y0) * i / pieces; val b = y0 + (y1 - y0) * (i + 1) / pieces
            quad(x0, a, RAIL_H, x1, a, RAIL_H, x1, b, RAIL_H, x0, b, RAIL_H, if (far) WOOD_FAR else WOOD, 2)
        }
    }

    /**
     * The cue stick, from its tip back toward (and past) the eye, as quads between cross-sections at
     * the distances [segs] (metres from the tip). The tip meets the ball at tip offset ([side], [vert])
     * (fractions of R, looking down the cue), drawn back from it by [gap] metres. Writes 4 screen points
     * per section (left x, y, right x, y) into [out] and returns how many sections are in front of the eye
     * (sections behind it are cut at the near plane, so the last one may be shorter).
     */
    fun cue(cam: PovCam, cueX: Double, cueY: Double, aim: Double, elevation: Double, side: Double, vert: Double,
            gap: Double, segs: DoubleArray, tipW: Double, buttW: Double, out: DoubleArray): Int {
        val fx = cos(aim); val fy = sin(aim)
        val ce = cos(elevation); val se = sin(elevation)
        // Cue direction (forward and down), its "up" and "right".
        val dX = ce * fx; val dY = ce * fy; val dH = -se
        val uX = se * fx; val uY = se * fy; val uH = ce
        val rX = -fy; val rY = fx
        val a = side; val b = vert
        val cc = sqrt((1 - a * a - b * b).coerceAtLeast(0.0))
        // Contact point on the ball, then back along the cue.
        val tipX = cueX + P.R * (a * rX + b * uX - cc * dX) - dX * gap
        val tipY = cueY + P.R * (a * rY + b * uY - cc * dY) - dY * gap
        val tipH = P.R + P.R * (b * uH - cc * dH) - dH * gap
        val len = segs.last()
        var k = 0
        for (i in segs.indices) {
            var s = segs[i]
            var cxp = tipX - dX * s; var cyp = tipY - dY * s; var chp = tipH - dH * s
            val d = cam.depth(cxp, cyp, chp)
            if (d < PovCam.NEAR) {
                if (i == 0) return 0
                // Cut where this section crosses the near plane.
                val s0 = segs[i - 1]
                val d0 = cam.depth(tipX - dX * s0, tipY - dY * s0, tipH - dH * s0)
                s = s0 + (s - s0) * ((d0 - PovCam.NEAR * 1.001) / (d0 - d)).coerceIn(0.0, 1.0)
                cxp = tipX - dX * s; cyp = tipY - dY * s; chp = tipH - dH * s
            }
            val hw = tipW + (buttW - tipW) * (s / len)
            // Width across the view: perpendicular to both the cue and the line of sight.
            var wx = dY * (chp - cam.eh) - dH * (cyp - cam.ey)
            var wy = dH * (cxp - cam.ex) - dX * (chp - cam.eh)
            var wh = dX * (cyp - cam.ey) - dY * (cxp - cam.ex)
            val wl = sqrt(wx * wx + wy * wy + wh * wh)
            if (wl < 1e-9) { wx = rX; wy = rY; wh = 0.0 } else { wx /= wl; wy /= wl; wh /= wl }
            if (wx * rX + wy * rY < 0) { wx = -wx; wy = -wy; wh = -wh }
            cam.project(cxp - wx * hw, cyp - wy * hw, chp - wh * hw, q); out[k * 4] = q[0]; out[k * 4 + 1] = q[1]
            cam.project(cxp + wx * hw, cyp + wy * hw, chp + wh * hw, q); out[k * 4 + 2] = q[0]; out[k * 4 + 3] = q[1]
            k++
            if (s < segs[i]) break
        }
        return k
    }
}
