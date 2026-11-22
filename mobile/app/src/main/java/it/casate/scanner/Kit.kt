package it.casate.scanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The building blocks of the app (v4 design): every screen is made of these, so spacing, sizes and
 * colours are the same everywhere. All touch targets are at least 44 dp; texts that can be long are
 * one line with "…" (no broken layouts).
 */

/** Lays children out left to right, wrapping to a new line (choice chips). */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + lineH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}

fun View.haptic(strong: Boolean = false) {
    performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
}

fun TextView.oneLine(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }
fun TextView.lines(n: Int): TextView { maxLines = n; ellipsize = TextUtils.TruncateAt.END; return this }

/** Big full-width action. `enabled = false` = grey and does nothing (tap explains via `whyDisabled`). */
fun Ui.primary(label: String, color: Int = C.BLUE, enabled: Boolean = true, icon: Icons.Spec? = null,
               whyDisabled: (() -> Unit)? = null, onClick: () -> Unit): View {
    val r = row(Gravity.CENTER)
    r.background = bg(if (enabled) color else 0xFFE4E8EF.toInt(), 16)
    val fg = if (enabled) Color.WHITE else C.FAINT
    if (icon != null) r.addView(icon(icon, fg, 20, 2.4f), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
    r.addView(text(label, 16f, fg, bold).oneLine(), lp(w = WRAP))
    r.contentDescription = label
    r.minimumHeight = dp(56)
    return tap(r, 16, enabled) { r.haptic(); if (enabled) onClick() else whyDisabled?.invoke() }
}

/** Quiet text action ("Annulla", "Togli dalla lista"). */
fun Ui.ghost(label: String, color: Int = C.INK, onClick: () -> Unit): View {
    val t = text(label, 15f, color, medium).apply { gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(16), 0, dp(16), 0) }
    return tap(t, 14) { onClick() }
}

/** Outlined compact button ("Conta", "Segnala"). */
fun Ui.outline(label: String, color: Int = C.INK, line: Int = C.LINE2, onClick: () -> Unit): View {
    val t = text(label, 14f, color, bold).apply {
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        background = bg(Color.TRANSPARENT, 12, line, 1)
    }
    return tap(t, 12) { onClick() }
}

/** A selectable chip (reasons); selected = filled. */
fun Ui.choice(label: String, selected: Boolean, color: Int = C.BLUE, onClick: () -> Unit): View {
    val t = text(label, 14f, if (selected) Color.WHITE else C.INK, if (selected) bold else medium).apply {
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        background = if (selected) bg(color, 999) else bg(C.CARD, 999, C.LINE2, 1)
    }
    return tap(t, 999, selected) { t.haptic(); onClick() }
}

/** Small status pill (ORDINABILE, URGENZA…): dot + text. */
fun Ui.pill(label: String, fg: Int, fill: Int, dot: Boolean = true): View {
    val r = row()
    r.background = bg(fill, 999)
    r.setPadding(dp(10), dp(5), dp(12), dp(5))
    if (dot) r.addView(dot(fg, 8), LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
    r.addView(text(label, 12f, fg, bold).apply { letterSpacing = 0.04f })
    return r
}

fun Ui.card(radius: Int = 18, fill: Int = C.CARD, line: Int = C.LINE): LinearLayout = col().apply { background = bg(fill, radius, line) }

/** Small grey caps title above a block. */
fun Ui.caption(s: String, color: Int = C.MUTED): TextView = text(s.uppercase(), 12f, color, bold).apply { letterSpacing = 0.06f }

/** One row of numbers in a card: label over value, thin separators. `alert` colours one value. */
data class Stat(val label: String, val value: String, val unit: String = "", val color: Int = C.INK)

fun Ui.stats(items: List<Stat>): View {
    val r = row()
    r.background = bg(C.CARD, 16, C.LINE)
    r.setPadding(0, dp(10), 0, dp(10))
    for ((i, s) in items.withIndex()) {
        if (i > 0) r.addView(View(r.context).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(dp(1), dp(30)))
        val c = col()
        c.gravity = Gravity.CENTER_HORIZONTAL
        val v = row(Gravity.BOTTOM)
        v.addView(num(s.value, 18f, s.color))
        if (s.unit.isNotEmpty()) v.addView(text(" " + s.unit, 11f, if (s.color == C.INK) C.MUTED else s.color, medium))
        c.addView(v)
        c.addView(text(s.label, 11f, C.MUTED).oneLine(), lp(w = WRAP, top = 3))
        r.addView(c, lp(w = 0, weight = 1f))
    }
    return r
}

/** List row: leading view, title (1 line), subtitle (1 line), trailing view. Tap = onClick. */
fun Ui.listRow(lead: View?, title: CharSequence, sub: CharSequence?, subColor: Int = C.MUTED, trail: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
    val r = row()
    r.setPadding(dp(4), dp(10), dp(4), dp(10))
    r.minimumHeight = dp(60)
    if (lead != null) r.addView(lead)
    val c = col()
    c.addView(text(title, 15f, C.INK, medium).oneLine())
    if (!sub.isNullOrEmpty()) c.addView(text(sub, 12f, subColor).oneLine(), lp(top = 3))
    r.addView(c, lp(w = 0, weight = 1f, start = if (lead != null) 12 else 0))
    if (trail != null) r.addView(trail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    if (onClick != null) tap(r, 12) { onClick() }
    return r
}

fun Ui.divider(inset: Int = 0): View = View(this.ctx()).apply {
    setBackgroundColor(0xFFE9EDF3.toInt())
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(inset) }
}

/** Large page title for the light tabs (List, To do), Material "large top app bar". */
fun Ui.largeTitle(title: String, sub: String?, trailing: View? = null): View {
    val c = col()
    c.setBackgroundColor(C.CANVAS)
    c.setPadding(dp(20), dp(18), dp(16), dp(10))
    val r = row()
    r.addView(text(title, 30f, C.INK, bold).oneLine(), lp(w = 0, weight = 1f))
    if (trailing != null) r.addView(trailing)
    c.addView(r)
    if (!sub.isNullOrEmpty()) c.addView(text(sub, 13f, C.MUTED).oneLine(), lp(top = 4))
    return c
}

/** Round icon button (44 dp). */
fun Ui.iconTap(spec: Icons.Spec, color: Int, desc: String, onDark: Boolean = false, onClick: () -> Unit): View {
    val f = FrameLayout(ctx())
    f.addView(icon(spec, color, 22, 2.2f), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    f.contentDescription = desc
    f.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    return tap(f, 999, onDark) { onClick() }
}

/**
 * − n + : tap = ±1, hold = faster and faster (1 → 2 → 5 → 10), tap the number = type it. `onChange` runs on
 * every step (cheap updates only); `onDone` once the finger is lifted or a number typed.
 */
class Stepper(private val ui: Ui, private val get: () -> Int, private val set: (Int) -> Unit, private val min: Int,
              private val size: Int, private val numSize: Float, private val title: String,
              private val onChange: () -> Unit, private val onDone: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    val num: TextView = ui.num("${get()}", numSize).apply { gravity = Gravity.CENTER }
    val view: LinearLayout = ui.row()

    init {
        view.addView(key(Icons.MINUS, -1, false), LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)))
        val mid = FrameLayout(view.context)
        mid.addView(num, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        mid.contentDescription = "Scrivi il numero"
        ui.tap(mid, 12) { type() }
        view.addView(mid, LinearLayout.LayoutParams(0, ui.dp(size), 1f))
        view.addView(key(Icons.PLUS, 1, true), LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)))
    }

    fun refresh() { num.text = "${get()}" }

    private fun step(d: Int): Boolean {
        val nv = (get() + d).coerceIn(min, 9999)
        if (nv == get()) return false
        set(nv)
        refresh()
        num.animate().cancel()
        num.scaleX = 1.12f; num.scaleY = 1.12f
        num.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        onChange()
        return true
    }

    private fun key(spec: Icons.Spec, dir: Int, dark: Boolean): View {
        val f = FrameLayout(view.context)
        f.background = ui.ripple(if (dark) ui.bg(C.NAVY, 16) else ui.bg(0xFFF1F4F8.toInt(), 16, C.LINE2), 16, dark)
        f.addView(ui.icon(spec, if (dark) Color.WHITE else C.INK, size / 2 - 4, 2.4f),
            FrameLayout.LayoutParams(ui.dp(size / 2 - 4), ui.dp(size / 2 - 4), Gravity.CENTER))
        f.contentDescription = if (dir > 0) "Aggiungi" else "Togli"
        f.isClickable = true
        var n = 0
        val repeat = object : Runnable {
            override fun run() {
                n++
                val s = when { n > 24 -> 10; n > 14 -> 5; n > 6 -> 2; else -> 1 }
                if (step(dir * s)) main.postDelayed(this, 110)
            }
        }
        f.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { main.removeCallbacks(repeat) }   // page redrawn while held
        })
        @Suppress("ClickableViewAccessibility")
        f.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // the page must not scroll away (and cancel the press) while the finger is on − / +
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.drawableHotspotChanged(e.x, e.y); v.isPressed = true; n = 0
                    if (step(dir)) v.haptic()
                    main.postDelayed(repeat, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    // onDone usually rebuilds the page: never inside the touch dispatch (removing the view that is
                    // being touched made Android crash, e.g. when the scroll view cancelled the press)
                    main.post { onDone() }
                }
            }
            true
        }
        return f
    }

    private fun type() {
        val ctx = view.context
        val e = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("${get()}")
            selectAll()
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val d = android.app.AlertDialog.Builder(ctx).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ -> e.text.toString().toIntOrNull()?.let { set(it.coerceIn(min, 9999)); refresh(); onChange(); main.post { onDone() } } }
            .setNegativeButton("Annulla", null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }
}

/** A button that must be held ~1.5 s (fills up) before it acts: for things that are hard to undo. */
fun Ui.holdButton(label: String, color: Int, dark: Int, onDone: () -> Unit): FrameLayout {
    val hold = FrameLayout(ctx())
    hold.background = bg(color, 16)
    hold.clipToOutline = true
    hold.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    val fill = View(ctx()).apply { setBackgroundColor(dark); pivotX = 0f; scaleX = 0f }
    hold.addView(fill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val t = text(label, 16f, Color.WHITE, bold).apply { gravity = Gravity.CENTER }
    hold.addView(t, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    hold.contentDescription = label
    var anim: ValueAnimator? = null
    var done = false
    @Suppress("ClickableViewAccessibility")
    hold.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!done) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                v.haptic()
                anim = ValueAnimator.ofFloat(fill.scaleX, 1f).apply {
                    duration = (1500 * (1f - fill.scaleX)).toLong().coerceAtLeast(1)
                    addUpdateListener { fill.scaleX = it.animatedValue as Float }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            if (fill.scaleX >= 0.999f && !done) { done = true; t.text = "Invio…"; v.haptic(true); v.post { onDone() } }
                        }
                    })
                    start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!done) {
                anim?.cancel()
                fill.animate().scaleX(0f).setDuration(200).start()
            }
        }
        true
    }
    hold.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
    return hold
}

/** Gradient helper (header glow). */
fun vertical(top: Int, bottom: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
