package it.casate.scanner

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The item page (the v3 look the stores know): navy bar with Back, the coloured header of the verdict,
 * the content that scrolls, the buttons fixed at the bottom. It covers the screen above the home / list;
 * a new scan replaces it in place. Back = back inside it first (stock choices), then close.
 */
class SheetHost(private val a: MainActivity) {
    val layer = FrameLayout(a).apply { visibility = View.GONE; isClickable = true; setBackgroundColor(C.CANVAS) }
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val top = FrameLayout(a)
    private val hero = FrameLayout(a)
    private val scroll = ScrollView(a).apply { isFillViewport = true }
    private val foot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    var isOpen = false
        private set
    var onBack: (() -> Boolean)? = null
    var onClose: (() -> Unit)? = null

    init {
        col.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(hero, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        foot.setBackgroundColor(Color.WHITE)
        foot.elevation = a.resources.displayMetrics.density * 8
        col.addView(foot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layer.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** `head` = the coloured verdict band (or null), `body` scrolls, `actions` stay at the bottom. */
    fun show(head: View?, body: View, actions: View?, animate: Boolean = false) {
        val y = if (isOpen) scroll.scrollY else 0
        top.removeAllViews()
        top.addView(a.topBar(back = true) { back() })
        hero.removeAllViews()
        if (head != null) hero.addView(head)
        scroll.removeAllViews()
        scroll.addView(body)
        foot.removeAllViews()
        if (actions != null) { foot.addView(actions); foot.visibility = View.VISIBLE } else foot.visibility = View.GONE
        a.statusBar(dark = true)
        if (!isOpen) {
            isOpen = true
            layer.visibility = View.VISIBLE
            layer.alpha = 0f
            layer.animate().alpha(1f).setDuration(140).start()
        } else scroll.post { scroll.scrollTo(0, y) }
        if (animate && head != null) {
            head.alpha = 0f
            head.translationY = -a.resources.displayMetrics.density * 12
            head.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
    }

    fun top() { scroll.post { scroll.scrollTo(0, 0) } }

    fun back() {
        if (onBack?.invoke() == true) return
        close()
        a.render()
    }

    fun close(@Suppress("UNUSED_PARAMETER") animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        onBack = null
        val done = onClose
        onClose = null
        a.currentFocus?.let { a.hideKeyboard(it) }
        layer.visibility = View.GONE
        done?.invoke()
    }
}
