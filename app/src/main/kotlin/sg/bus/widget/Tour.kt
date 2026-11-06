package sg.bus.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One step of the first-run guide. [target] finds the view to point at (null = a centred bubble). */
class TourStep(
    val title: String,
    val text: () -> String,
    val target: () -> View?,
    val next: String = "Next",
    /** called before the step shows, e.g. to switch to the tab its target lives on */
    val prepare: () -> Unit = {},
)

/**
 * First-run coach marks: dims the screen, cuts a highlighted hole around the target, and shows a speech bubble
 * with an arrow pointing at it. Taps inside the hole reach the real button underneath, so people do each step for
 * real; everything else is blocked. Platform views only (no AndroidX), like the rest of the app.
 */
class TourOverlay(ctx: Context, private val steps: List<TourStep>, private val onEnd: (finished: Boolean) -> Unit) :
    FrameLayout(ctx) {

    var index = 0
        private set
    private val hole = RectF()
    private var hasHole = false
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); color = ctx.themeColor(android.R.attr.colorAccent)
    }
    private val surface = ctx.themeColor(android.R.attr.colorBackground)
    private val arrow = Arrow(ctx, surface)
    private val counter = TextView(ctx).apply { textSize = 12f; setTextColor(ctx.themeColor(android.R.attr.textColorSecondary)) }
    private val title = TextView(ctx).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, ctx.dp(2), 0, ctx.dp(4)) }
    private val body = TextView(ctx).apply { textSize = 15f; setLineSpacing(0f, 1.15f) }
    private val skip = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply { text = "Skip guide"; isAllCaps = false }
    private val next = Button(ctx).apply { isAllCaps = false }
    private val bubble = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(8))
        background = GradientDrawable().apply { setColor(surface); cornerRadius = ctx.dp(18).toFloat() }
        elevation = ctx.dp(8).toFloat()
        addView(counter); addView(title); addView(body)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(6), 0, 0)
            addView(skip); addView(next)
        })
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        addView(arrow, LayoutParams(ctx.dp(24), ctx.dp(12)))
        addView(bubble, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ctx.dp(16); rightMargin = ctx.dp(16)
        })
        skip.setOnClickListener { onEnd(false) }
        next.setOnClickListener { if (index == steps.lastIndex) onEnd(true) else show(index + 1) }
        alpha = 0f
        animate().alpha(1f).setDuration(200).start()
    }

    fun show(i: Int) {
        index = i.coerceIn(0, steps.lastIndex)
        val s = steps[index]
        s.prepare()
        counter.text = if (index == 0) "Setup guide" else "Step $index of ${steps.size - 1}"
        skip.visibility = if (index == steps.lastIndex) View.GONE else View.VISIBLE
        next.text = s.next
        refresh()
    }

    /** Re-find the target and re-place the bubble: call after the screen under the guide is redrawn. */
    fun refresh() {
        val s = steps[index]
        title.text = s.title
        body.text = s.text()
        bubble.contentDescription = "${counter.text}. ${s.title}. ${body.text}"
        // wait for the screen under us to lay out (tab switch), scroll the target into view, then measure it
        post {
            val t = s.target()
            t?.requestRectangleOnScreen(android.graphics.Rect(0, 0, t.width, t.height), true)
            post { place(t) }
        }
    }

    private fun place(target: View?) {
        if (width == 0 || height == 0) { post { place(target) }; return }   // not laid out yet (first step)
        val me = IntArray(2).also(::getLocationInWindow)
        if (target == null || !target.isShown) {
            hasHole = false
            arrow.visibility = View.GONE
            bubble.layoutParams = (bubble.layoutParams as LayoutParams).apply { gravity = Gravity.CENTER_VERTICAL; topMargin = 0 }
        } else {
            val t = IntArray(2).also(target::getLocationInWindow)
            val pad = context.dp(6).toFloat()
            hole.set(t[0] - me[0] - pad, t[1] - me[1] - pad, t[0] - me[0] + target.width + pad, t[1] - me[1] + target.height + pad)
            hasHole = true
            bubble.measure(MeasureSpec.makeMeasureSpec(width - context.dp(32), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
            val gap = context.dp(12)
            val below = hole.bottom + gap + bubble.measuredHeight <= height - context.dp(8)
            val top = if (below) hole.bottom + gap else (hole.top - gap - bubble.measuredHeight).coerceAtLeast(context.dp(8).toFloat())
            bubble.layoutParams = (bubble.layoutParams as LayoutParams).apply { gravity = Gravity.TOP; topMargin = top.toInt() }
            arrow.up = below
            arrow.visibility = View.VISIBLE
            // overlay width read OUTSIDE the apply: inside it, `width` is the arrow LayoutParams' 24dp (crashed on "Let's go")
            val overlayW = width
            arrow.layoutParams = (arrow.layoutParams as LayoutParams).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (hole.centerX() - context.dp(12)).toInt()
                    .coerceIn(context.dp(28), maxOf(context.dp(28), overlayW - context.dp(52)))
                topMargin = (if (below) top - context.dp(11) else top + bubble.measuredHeight - context.dp(1)).toInt()
            }
        }
        requestLayout()
        invalidate()
        bubble.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    override fun onDraw(canvas: Canvas) {
        val r = context.dp(12).toFloat()
        val p = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (hasHole) addRoundRect(hole, r, r, Path.Direction.CW)
        }
        canvas.drawPath(p, scrimPaint)
        if (hasHole) canvas.drawRoundRect(hole, r, r, ringPaint)
    }

    /** Taps in the hole go to the real button underneath; everything else stops here. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (hasHole && hole.contains(ev.x, ev.y) && ev.actionMasked == MotionEvent.ACTION_DOWN) return false
        super.dispatchTouchEvent(ev)
        return true
    }

    /** The little triangle joining the bubble to its target. */
    private class Arrow(ctx: Context, color: Int) : View(ctx) {
        var up = true
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        init { elevation = ctx.dp(8).toFloat() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawPath(Path().apply {
                if (up) { moveTo(0f, h); lineTo(w / 2, 0f); lineTo(w, h) } else { moveTo(0f, 0f); lineTo(w / 2, h); lineTo(w, 0f) }
                close()
            }, paint)
        }
    }
}
