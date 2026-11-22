package it.casate.scanner

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** A column that never grows taller than `ratio` of its parent (the sheet keeps the scanner peeking out). */
class CappedColumn(ctx: Context, private val ratio: Float) : LinearLayout(ctx) {
    init { orientation = VERTICAL }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val parentH = (parent as? View)?.height ?: 0
        val cap = if (parentH > 0) (parentH * ratio).toInt() else MeasureSpec.getSize(hSpec)
        super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/**
 * The bottom sheet where every decision about a scanned item is taken (native-style: slides up over the
 * screen, drag the handle down or tap outside to close, Back closes). The content scrolls; the actions
 * stay fixed at the bottom. A new scan replaces the content in place.
 */
class SheetHost(private val a: MainActivity) {
    private val ui get() = a.ui
    val layer = FrameLayout(a).apply { visibility = View.GONE; isClickable = true }
    private val scrim = View(a).apply { setBackgroundColor(0x990B1220.toInt()) }
    private val panel = CappedColumn(a, 0.93f)
    private val scroll = ScrollView(a).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
    private val foot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    var isOpen = false
        private set
    /** Back inside the sheet (e.g. from the stock choices to the item): return true when handled. */
    var onBack: (() -> Boolean)? = null
    /** Runs when the sheet closes (any way). */
    var onClose: (() -> Unit)? = null

    init {
        layer.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scrim.setOnClickListener { close() }
        panel.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            val r = a.resources.displayMetrics.density * 28
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        panel.elevation = a.resources.displayMetrics.density * 24
        panel.isClickable = true
        // drag handle: pull down to close
        val handle = FrameLayout(a)
        val bar = View(a).apply { background = GradientDrawable().apply { setColor(0xFFD5DBE6.toInt()); cornerRadius = 99f } }
        handle.addView(bar, FrameLayout.LayoutParams(dp(40), dp(5), Gravity.CENTER))
        var y0 = 0f
        @Suppress("ClickableViewAccessibility")
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> y0 = e.rawY
                MotionEvent.ACTION_MOVE -> panel.translationY = (e.rawY - y0).coerceAtLeast(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (panel.translationY > dp(110)) close() else panel.animate().translationY(0f).setDuration(160).start()
            }
            true
        }
        panel.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        foot.setPadding(dp(20), dp(10), dp(20), dp(16))
        panel.addView(foot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layer.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** Shows `body` (scrolls) and `actions` (fixed at the bottom); slides up if it was closed. */
    fun show(body: View, actions: View?) {
        val y = if (isOpen) scroll.scrollY else 0
        scroll.removeAllViews()
        scroll.addView(body)
        foot.removeAllViews()
        if (actions != null) { foot.addView(actions); foot.visibility = View.VISIBLE } else foot.visibility = View.GONE
        // the panel's height may change with the content (cap is re-measured)
        panel.requestLayout()
        if (!isOpen) {
            isOpen = true
            layer.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(180).start()
            panel.translationY = a.resources.displayMetrics.heightPixels.toFloat()
            panel.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        } else scroll.post { scroll.scrollTo(0, y) }
    }

    /** Scrolls back to the top (a different item was scanned). */
    fun top() { scroll.post { scroll.scrollTo(0, 0) } }

    fun back() {
        if (onBack?.invoke() == true) return
        close()
    }

    fun close(animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        onBack = null
        val done = onClose
        onClose = null
        a.currentFocus?.let { a.hideKeyboard(it) }
        if (!animate) { layer.visibility = View.GONE; done?.invoke(); return }
        scrim.animate().alpha(0f).setDuration(160).start()
        panel.animate().translationY(panel.height.toFloat() + dp(40)).setDuration(200).withEndAction {
            if (!isOpen) layer.visibility = View.GONE
        }.start()
        done?.invoke()
    }
}
