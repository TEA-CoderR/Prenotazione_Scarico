package it.casate.scanner

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * Line icons drawn from the same SVG paths as the design (24 × 24 view box, round caps), so the app and
 * the design look the same and no image files or libraries are needed.
 */
object Icons {
    class Spec(val path: Path)

    private fun spec(d: String, vararg circles: FloatArray, build: (Path) -> Unit = {}): Spec {
        val p = parse(d)
        for (c in circles) p.addCircle(c[0], c[1], c[2], Path.Direction.CW)
        build(p)
        return Spec(p)
    }

    val CHECK = spec("M5 12.5l4.5 4.5L19 7.5")
    val SWAP = spec("M4 8h13l-3-3M20 16H7l3 3")
    val CLOCK = spec("M12 7.5V12l3 2", floatArrayOf(12f, 12f, 8.5f))
    val BAN = spec("M6 6l12 12", floatArrayOf(12f, 12f, 8.5f))
    val BOLT = spec("M13 3L5 13h6l-1 8 8-10h-6z")
    val TRUCK = spec("M3 7h11v9H3zM14 10h4l3 3v3h-7", floatArrayOf(7f, 18f, 1.6f), floatArrayOf(17f, 18f, 1.6f))
    val SEARCH = spec("M20 20l-3.5-3.5", floatArrayOf(11f, 11f, 7f))
    val BARCODE = spec("M4 6v12M7 6v12M10 6v12M13 6v12M16 6v12M19 6v12")
    val BACK = spec("M15 6l-6 6 6 6")
    val NEXT = spec("M9 6l6 6-6 6")
    val ARROW = spec("M5 12h14M13 6l6 6-6 6")
    val DOWN = spec("M12 4v16M6 14l6 6 6-6")
    val SEND = spec("M4 12l16-8-6 16-3-7z")
    val ALERT = spec("M12 4l9 16H3zM12 10v4M12 17h.01")
    val SPARK = spec("M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1")
    val LIST = spec("M3 7h18M3 12h18M3 17h12")
    val PLUS = spec("M12 5v14M5 12h14")
    val MINUS = spec("M5 12h14")
    val CLOSE = spec("M6 6l12 12M18 6L6 18")
    val BOX = spec("M3 8l9-5 9 5v8l-9 5-9-5zM3 8l9 5 9-5M12 13v8")
    val INFO = spec("M12 11v5M12 8h.01", floatArrayOf(12f, 12f, 8.5f))
    val REFRESH = spec("M4 18v-6h6") { it.addArc(RectF(4f, 4f, 20f, 20f), 180f, 290f) }
    val TAG = spec("M3 12V4h8l10 10-8 8z", floatArrayOf(7.5f, 7.5f, 1.2f))

    /** A small SVG path reader: M L H V C Z (absolute and relative). Enough for line icons. */
    fun parse(d: String, p: Path = Path()): Path {
        val tok = Regex("[MmLlHhVvCcZzAa]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        var i = 0
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var cmd = 'M'
        fun num() = tok[i++].toFloat()
        while (i < tok.size) {
            val t = tok[i]
            if (t[0].isLetter()) {
                cmd = t[0]
                i++
                if (cmd == 'Z' || cmd == 'z') { p.close(); cx = sx; cy = sy }
                continue
            }
            when (cmd) {
                'M' -> { cx = num(); cy = num(); p.moveTo(cx, cy); sx = cx; sy = cy; cmd = 'L' }
                'm' -> { cx += num(); cy += num(); p.moveTo(cx, cy); sx = cx; sy = cy; cmd = 'l' }
                'L' -> { cx = num(); cy = num(); p.lineTo(cx, cy) }
                'l' -> { cx += num(); cy += num(); p.lineTo(cx, cy) }
                'H' -> { cx = num(); p.lineTo(cx, cy) }
                'h' -> { cx += num(); p.lineTo(cx, cy) }
                'V' -> { cy = num(); p.lineTo(cx, cy) }
                'v' -> { cy += num(); p.lineTo(cx, cy) }
                'C' -> {
                    val a = num(); val b = num(); val c = num(); val e = num(); cx = num(); cy = num()
                    p.cubicTo(a, b, c, e, cx, cy)
                }
                'c' -> {
                    val a = cx + num(); val b = cy + num(); val c = cx + num(); val e = cy + num(); cx += num(); cy += num()
                    p.cubicTo(a, b, c, e, cx, cy)
                }
                'A', 'a' -> {
                    // arcs: only used for the euro sign; drawn as a straight line to the end point
                    repeat(5) { num() }
                    if (cmd == 'A') { cx = num(); cy = num() } else { cx += num(); cy += num() }
                    p.lineTo(cx, cy)
                }
                else -> i++
            }
        }
        return p
    }
}

/** Draws an icon in its bounds; `stroke` is in view-box units like SVG stroke-width. */
class IconDrawable(private val spec: Icons.Spec, color: Int, private val stroke: Float = 2f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        canvas.scale(s, s)
        paint.strokeWidth = stroke
        canvas.drawPath(spec.path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The four corner marks of the scan target (design: 26 px brackets, 3 px blue, 18 px in). */
class Corners(color: Int, private val w: Float, private val len: Float, private val inset: Float, private val r: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left + inset
        val t = b.top + inset
        val rr = b.right - inset
        val bb = b.bottom - inset
        val p = Path()
        p.moveTo(l, t + len); p.lineTo(l, t + r); p.quadTo(l, t, l + r, t); p.lineTo(l + len, t)
        p.moveTo(rr - len, t); p.lineTo(rr - r, t); p.quadTo(rr, t, rr, t + r); p.lineTo(rr, t + len)
        p.moveTo(l, bb - len); p.lineTo(l, bb - r); p.quadTo(l, bb, l + r, bb); p.lineTo(l + len, bb)
        p.moveTo(rr - len, bb); p.lineTo(rr - r, bb); p.quadTo(rr, bb, rr, bb - r); p.lineTo(rr, bb - len)
        canvas.drawPath(p, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
