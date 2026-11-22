package it.casate.scanner

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.ImageView
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
    /** "only if urgent": allowed, but an exception (not red = forbidden) */
    const val ORANGE = 0xFFC2410C.toInt()
    const val ORANGE_SOFT = 0xFFFFEDD5.toInt()
}

class Ui(private val a: Activity) {
    var images: Images? = null
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

    private fun dark(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 140

    /** Native touch feedback (ripple) over `content`, clipped to the rounded corners. */
    fun ripple(content: Drawable?, radius: Int = 14, onDark: Boolean = false): Drawable {
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpf(radius.toFloat()) }
        return RippleDrawable(ColorStateList.valueOf(if (onDark) 0x40FFFFFF else 0x220B1220), content, mask)
    }

    /** Makes any view a button: ripple on its background, click, focus. */
    fun <V : View> tap(v: V, radius: Int = 14, onDark: Boolean = false, onClick: () -> Unit): V {
        v.background = ripple(v.background, radius, onDark)
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener { onClick() }
        return v
    }

    /** A big tappable button: filled (`fill`) or outlined (fill = 0). */
    fun button(label: String, fill: Int, fg: Int = Color.WHITE, outline: Int = 0, size: Float = 16f, onClick: () -> Unit) =
        text(label, size, fg, bold).apply {
            gravity = Gravity.CENTER
            val stroke = if (outline != 0) outline else if (fill == 0) C.LINE2 else 0
            background = bg(if (fill != 0) fill else C.CARD, 14, stroke, if (outline != 0 && fill == 0) 2 else 1)
            tap(this, 14, fill != 0 && dark(fill)) { onClick() }
            contentDescription = label
        }

    /** Button with a line icon before the label. */
    fun iconButton(label: String, icon: Icons.Spec, fill: Int, fg: Int = Color.WHITE, outline: Int = 0, size: Float = 16f,
                   after: Boolean = false, onClick: () -> Unit): LinearLayout {
        val r = row(Gravity.CENTER)
        val stroke = if (outline != 0) outline else if (fill == 0) C.LINE2 else 0
        r.background = bg(if (fill != 0) fill else C.CARD, 14, stroke, if (outline != 0 && fill == 0) 2 else 1)
        if (after) {
            r.addView(text(label, size, fg, bold), lp(w = WRAP))
            r.addView(icon(icon, fg, 18, 2.2f), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
        } else {
            r.addView(icon(icon, fg, 18, 2.2f))
            r.addView(text(label, size, fg, bold), lp(w = WRAP, start = 8))
        }
        r.contentDescription = label
        return tap(r, 14, fill != 0 && dark(fill)) { onClick() }
    }

    fun icon(spec: Icons.Spec, color: Int, size: Int = 20, stroke: Float = 2f) = ImageView(a).apply {
        setImageDrawable(IconDrawable(spec, color, stroke))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Round badge with a line icon (the coloured headers of the scan results). */
    fun iconBadge(spec: Icons.Spec, fill: Int, fg: Int, size: Int = 52, iconSize: Int = 28, stroke: Float = 2.4f): View {
        val f = FrameLayout(a)
        f.background = if (fill == 0) null else circle(fill)
        val i = ImageView(a).apply { setImageDrawable(IconDrawable(spec, fg, stroke)) }
        f.addView(i, FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return f
    }

    /** Ticked / empty square box. */
    fun checkbox(on: Boolean, size: Int = 24): View {
        val f = FrameLayout(a)
        f.background = if (on) bg(C.NAVY, 6) else bg(C.CARD, 6, C.LINE2, 2)
        if (on) f.addView(ImageView(a).apply { setImageDrawable(IconDrawable(Icons.CHECK, Color.WHITE, 3f)) },
            FrameLayout.LayoutParams(dp(size - 8), dp(size - 8), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return f
    }

    /**
     * The product photo (from the image server), with the item's first letters until it arrives or when
     * there is none. Tap = bigger photo.
     */
    fun photo(ean: String, name: String, size: Int = 64, fg: Int = C.BLUE, fill: Int = C.CARD, faded: Boolean = false): View {
        val f = FrameLayout(a)
        f.background = bg(fill, if (size >= 56) 14 else 10, if (fill == C.CARD) C.LINE else 0)
        f.outlineProvider = ViewOutlineProvider.BACKGROUND
        f.clipToOutline = true
        val letters = name.split(' ', '.', '-').filter { it.isNotEmpty() && it[0].isLetter() }.take(2)
            .joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }
        val t = text(letters, if (size >= 56) 18f else 13f, fg, bold).apply { gravity = Gravity.CENTER }
        f.addView(t, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            if (faded) alpha = 0.55f
            val p = dp(if (size >= 56) 4 else 2)
            setPadding(p, p, p, p)
        }
        f.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        f.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        val im = images
        if (im != null && ean.isNotEmpty()) {
            im.into(img, ean, dp(size)) { ok ->
                img.visibility = if (ok) View.VISIBLE else View.GONE
                if (ok) {
                    f.isClickable = true
                    f.contentDescription = "Foto di $name"
                    f.setOnClickListener { zoom(ean, name) }
                }
            }
        }
        return f
    }

    /** The photo big, in a dialog (tap anywhere to close). */
    fun zoom(ean: String, name: String) {
        val im = images ?: return
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val c = col(16)
        c.background = bg(C.CARD, 22)
        val side = (a.resources.displayMetrics.widthPixels * 0.8).toInt()
        val img = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        c.addView(img, LinearLayout.LayoutParams(side, side))
        c.addView(text(name, 14f, C.INK, bold), lp(top = 12))
        c.addView(num(ean, 12f, C.MUTED), lp(top = 4))
        c.setOnClickListener { d.dismiss() }
        im.into(img, ean, side) {}
        d.setContentView(c)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
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
