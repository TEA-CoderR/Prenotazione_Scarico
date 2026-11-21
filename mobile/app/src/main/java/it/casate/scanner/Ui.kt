package it.casate.scanner

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Colours and small view builders shared by the screens (no layouts / XML: everything is built in code). */
object C {
    const val NAVY = 0xFF0B1220.toInt()
    const val NAVY2 = 0xFF131D33.toInt()
    const val NAVY3 = 0xFF16213A.toInt()
    const val NAVY_LINE = 0xFF23314F.toInt()
    const val ON_NAVY = 0xFFC9D3E8.toInt()
    const val ON_NAVY_MUTED = 0xFF9AA6BF.toInt()
    const val CANVAS = 0xFFF3F5F9.toInt()
    const val CARD = Color.WHITE
    const val LINE = 0xFFE1E6EF.toInt()
    const val LINE2 = 0xFFD5DBE6.toInt()
    const val INK = 0xFF0B1220.toInt()
    const val MUTED = 0xFF5B6478.toInt()
    const val FAINT = 0xFF8A93A6.toInt()
    const val BLUE = 0xFF2F6BFF.toInt()
    const val BLUE_SOFT = 0xFFEAF0FF.toInt()
    const val BLUE_INK = 0xFF1D4FD8.toInt()
    const val GREEN = 0xFF0E9F6E.toInt()
    const val GREEN_SOFT = 0xFFDCF7EC.toInt()
    const val GREEN_INK = 0xFF0B6B4B.toInt()
    const val AMBER = 0xFFF5A524.toInt()
    const val AMBER_SOFT = 0xFFFFF4DB.toInt()
    const val AMBER_LINE = 0xFFF5D78E.toInt()
    const val AMBER_INK = 0xFF7A5300.toInt()
    const val RED = 0xFFD92D20.toInt()
    const val RED_SOFT = 0xFFFEE4E2.toInt()
    const val RED_BG = 0xFFFFF6F5.toInt()
    const val RED_INK = 0xFFB42318.toInt()
    const val YELLOW = 0xFFFFC857.toInt()
    const val CORAL = 0xFFFF8A7A.toInt()
    const val MINT = 0xFF2DD4A3.toInt()
}

class Ui(private val a: Activity) {
    val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * a.resources.displayMetrics.density

    fun text(s: CharSequence, size: Float = 15f, color: Int = C.INK, face: Typeface? = null) = TextView(a).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (face != null) typeface = face
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }

    fun num(s: CharSequence, size: Float, color: Int = C.INK) = text(s, size, color, mono)

    fun bg(color: Int, radius: Int = 14, stroke: Int = 0, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radius.toFloat())
        if (stroke != 0) setStroke(dp(strokeW), stroke)
    }

    fun dashed(color: Int, fill: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radius.toFloat())
        setStroke(dp(1), color, dpf(5f), dpf(4f))
    }

    fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun col(pad: Int = 0) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, weight: Float = 0f, start: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); marginStart = dp(start) }

    fun fixed(w: Int, h: Int, start: Int = 0) = LinearLayout.LayoutParams(dp(w), dp(h)).apply { marginStart = dp(start) }

    fun pad(v: View, l: Int, t: Int, r: Int, b: Int): View {
        v.setPadding(dp(l), dp(t), dp(r), dp(b))
        return v
    }

    /** A big tappable button: filled (`fill`) or outlined (fill = 0). */
    fun button(label: String, fill: Int, fg: Int = Color.WHITE, outline: Int = 0, size: Float = 16f, onClick: () -> Unit) =
        text(label, size, fg, bold).apply {
            gravity = Gravity.CENTER
            val stroke = if (outline != 0) outline else if (fill == 0) C.LINE2 else 0
            background = bg(if (fill != 0) fill else C.CARD, 14, stroke, if (outline != 0 && fill == 0) 2 else 1)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            contentDescription = label
        }

    /** Small rounded label ("CONSIGLIATO", "URGENZA"). */
    fun chip(label: String, fg: Int, fill: Int, size: Float = 12f) = text(label, size, fg, bold).apply {
        background = bg(fill, 7)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    fun dot(color: Int, size: Int = 10) = View(a).apply { background = circle(color) }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun struck(t: TextView): TextView {
        t.paintFlags = t.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        return t
    }

    /** Coloured round icon with a symbol (✓ ⇄ ◷ ✕ …). */
    fun badge(symbol: String, fill: Int, fg: Int, size: Int = 52, textSize: Float = 26f): View {
        val f = FrameLayout(a)
        f.background = circle(fill)
        val t = text(symbol, textSize, fg, bold).apply { gravity = Gravity.CENTER }
        f.addView(t, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        f.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return f
    }

    /** Square with the item's first letters (there are no product photos on the device). */
    fun initials(name: String, fg: Int, fill: Int = C.CARD, size: Int = 60): View {
        val letters = name.split(' ', '.', '-').filter { it.isNotEmpty() && it[0].isLetter() }.take(2)
            .joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }
        val t = text(letters, 18f, fg, bold).apply {
            gravity = Gravity.CENTER
            background = bg(fill, 14, if (fill == C.CARD) C.LINE else 0)
        }
        t.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return t
    }

    /** One small stat tile: label over a number. */
    fun tile(label: String, value: String, unit: String = "", fill: Int = C.CARD, line: Int = C.LINE, fg: Int = C.INK, labelFg: Int = C.MUTED): View {
        val c = col()
        c.background = bg(fill, 12, line)
        c.setPadding(dp(9), dp(8), dp(6), dp(8))
        c.addView(text(label, 11f, labelFg, medium).apply { maxLines = 1 })
        val r = row(Gravity.BOTTOM)
        r.addView(num(value, 18f, fg))
        if (unit.isNotEmpty()) r.addView(text(" $unit", 11f, labelFg))
        c.addView(r, lp(top = 3))
        return c
    }

    fun spacer(h: Int) = View(a).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
}
