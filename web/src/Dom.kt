package com.poolgame.web

// Minimal typed bindings for the browser APIs the game uses. Declared here so the web build
// needs nothing beyond the Kotlin standard library.

external val document: dynamic
external val window: dynamic
external val navigator: dynamic
external val localStorage: dynamic

external interface Ctx2D {
    var fillStyle: dynamic
    var strokeStyle: dynamic
    var lineWidth: Double
    var lineCap: String
    var lineJoin: String
    var font: String
    var textAlign: String
    var textBaseline: String
    var globalAlpha: Double
    var imageSmoothingEnabled: Boolean

    fun save()
    fun restore()
    fun translate(x: Double, y: Double)
    fun rotate(a: Double)
    fun scale(x: Double, y: Double)
    fun setTransform(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double)
    fun beginPath()
    fun closePath()
    fun moveTo(x: Double, y: Double)
    fun lineTo(x: Double, y: Double)
    fun arc(x: Double, y: Double, r: Double, a0: Double, a1: Double)
    fun arcTo(x1: Double, y1: Double, x2: Double, y2: Double, r: Double)
    fun rect(x: Double, y: Double, w: Double, h: Double)
    fun fill()
    fun stroke()
    fun clip()
    fun fillRect(x: Double, y: Double, w: Double, h: Double)
    fun clearRect(x: Double, y: Double, w: Double, h: Double)
    fun fillText(t: String, x: Double, y: Double)
    fun measureText(t: String): TextMetrics
    fun setLineDash(segments: Array<Double>)
    fun createLinearGradient(x0: Double, y0: Double, x1: Double, y1: Double): Gradient
    fun createRadialGradient(x0: Double, y0: Double, r0: Double, x1: Double, y1: Double, r1: Double): Gradient
    fun drawImage(img: dynamic, x: Double, y: Double)
    fun drawImage(img: dynamic, x: Double, y: Double, w: Double, h: Double)
    fun createImageData(w: Int, h: Int): ImageData
    fun putImageData(d: ImageData, x: Double, y: Double)
}

external interface Gradient {
    fun addColorStop(offset: Double, color: String)
}

external interface TextMetrics {
    val width: Double
}

external interface ImageData {
    val data: dynamic
    val width: Int
    val height: Int
}

fun now(): Double = window.performance.now() as Double

/** Create an offscreen canvas of the given pixel size. */
fun offscreen(w: Int, h: Int): dynamic {
    val c = document.createElement("canvas")
    c.width = w
    c.height = h
    return c
}

fun ctxOf(canvas: dynamic): Ctx2D = canvas.getContext("2d").unsafeCast<Ctx2D>()

/** Android-style ARGB int to a CSS colour. */
fun col(argb: Int): String {
    val a = (argb ushr 24) and 255
    val r = (argb shr 16) and 255
    val g = (argb shr 8) and 255
    val b = argb and 255
    return if (a == 255) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${a / 255.0})"
}

fun col(argb: Long): String = col(argb.toInt())

class Rect(var l: Double = 0.0, var t: Double = 0.0, var r: Double = 0.0, var b: Double = 0.0) {
    fun set(l: Double, t: Double, r: Double, b: Double) { this.l = l; this.t = t; this.r = r; this.b = b }
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    fun contains(x: Double, y: Double) = x >= l && x <= r && y >= t && y <= b
}

fun Ctx2D.roundRect(x: Double, y: Double, w: Double, h: Double, r: Double) {
    val rr = minOf(r, w / 2, h / 2)
    beginPath()
    moveTo(x + rr, y)
    arcTo(x + w, y, x + w, y + h, rr)
    arcTo(x + w, y + h, x, y + h, rr)
    arcTo(x, y + h, x, y, rr)
    arcTo(x, y, x + w, y, rr)
    closePath()
}

fun Ctx2D.roundRect(r: Rect, rad: Double) = roundRect(r.l, r.t, r.w, r.h, rad)

fun Ctx2D.circle(x: Double, y: Double, r: Double) {
    beginPath()
    arc(x, y, r, 0.0, 2 * kotlin.math.PI)
}

fun Ctx2D.line(x1: Double, y1: Double, x2: Double, y2: Double) {
    beginPath(); moveTo(x1, y1); lineTo(x2, y2); stroke()
}
