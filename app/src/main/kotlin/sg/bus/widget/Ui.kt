package sg.bus.widget

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import sg.bus.core.Registration
import sg.bus.core.Stop

/** Small programmatic-view helpers shared by the two screens (no AndroidX, no XML for screens). */
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun LinearLayout.heading(text: String) = addView(TextView(context).apply {
    this.text = text; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, context.dp(20), 0, context.dp(6))
})

fun LinearLayout.label(text: CharSequence, size: Float = 15f): TextView = TextView(context).apply {
    this.text = text; textSize = size; setPadding(0, context.dp(4), 0, context.dp(4))
}.also(::addView)

fun LinearLayout.button(text: String, onClick: (View) -> Unit): Button = Button(context).apply {
    this.text = text; isAllCaps = false; setOnClickListener(onClick)
}.also(::addView)

fun stopLine(ctx: Context, code: String): String =
    Directory.stops(ctx)[code]?.let { "${it.name} · ${it.road} ($code)" } ?: code

/** Tick the services you care about at [stop]; only ones whose routes call there are offered. */
fun pickServices(ctx: Context, stop: Stop, preselected: List<String>, onDone: (List<String>) -> Unit) {
    val all = (Directory.servicesAt(ctx, stop.code) + preselected).distinct()
    if (all.isEmpty()) {
        AlertDialog.Builder(ctx).setTitle(stop.name).setMessage("No bus services are listed for stop ${stop.code}.")
            .setPositiveButton("OK", null).show()
        return
    }
    val checked = BooleanArray(all.size) { all[it] in preselected }
    AlertDialog.Builder(ctx)
        .setTitle("${stop.name} (${stop.code}) — which buses?")
        .setMultiChoiceItems(all.toTypedArray(), checked) { _, i, on -> checked[i] = on }
        .setPositiveButton("Save") { _, _ ->
            // keep the user's earlier order, then new ticks in bus-number order
            val ticked = all.filterIndexed { i, _ -> checked[i] }
            onDone(preselected.filter { it in ticked } + ticked.filter { it !in preselected })
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Drag the buses into order. Order matters: a small widget only has room for the first ones. */
fun reorderServices(ctx: Context, title: String, services: List<String>, onDone: (List<String>) -> Unit) {
    var order = services
    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), 0) }
    body.label(DRAG_HINT, 13f).setTextColor(ctx.themeColor(android.R.attr.textColorSecondary))
    body.addView(ctx.dragList(services, { no ->
        val handle = ctx.dragHandle()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ctx.dp(48) }
        row.addView(handle)
        row.addView(TextView(ctx).apply { text = no; textSize = 17f })
        row to handle
    }) { order = it })
    AlertDialog.Builder(ctx).setTitle(title).setView(ScrollView(ctx).apply { addView(body) })
        .setPositiveButton("Save") { _, _ -> onDone(order) }
        .setNegativeButton("Cancel", null)
        .show()
}

const val DRAG_HINT = "Hold ⠿ and drag to change the order."

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
}

private fun Context.rippleBorderless() = TypedValue().let {
    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true); getDrawable(it.resourceId)
}

/** A 48dp round-ripple icon button (e.g. the trash can). */
fun Context.iconButton(icon: Int, description: String, onClick: (View) -> Unit) = ImageButton(this).apply {
    setImageResource(icon); contentDescription = description; background = rippleBorderless()
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    setOnClickListener(onClick)
}

/** The ⠿ grip that tells people a row can be dragged. */
fun Context.dragHandle() = ImageView(this).apply {
    setImageResource(R.drawable.ic_drag); contentDescription = "Drag to reorder"
    scaleType = ImageView.ScaleType.CENTER
    layoutParams = LinearLayout.LayoutParams(dp(40), dp(48))
}

/**
 * A vertical list reordered by holding a row's handle (or long-pressing the row) and dragging it.
 * [row] builds each item's view and returns it with its handle; [onReorder] gets the new order on drop.
 * Platform drag-and-drop only: no AndroidX/RecyclerView in this app.
 */
fun <T> Context.dragList(items: List<T>, row: (T) -> Pair<View, View>, onReorder: (List<T>) -> Unit): LinearLayout {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var dragged: View? = null
    var current = items
    items.forEach { item ->
        val (v, handle) = row(item)
        v.tag = item
        fun start(): Boolean {
            dragged = v
            if (!v.startDragAndDrop(null, View.DragShadowBuilder(v), null, 0)) { dragged = null; return false }
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            v.alpha = 0.3f   // leaves a ghost where the row will land
            return true
        }
        handle.setOnTouchListener { _, e -> e.actionMasked == MotionEvent.ACTION_DOWN && start() }
        v.setOnLongClickListener { start() }
        list.addView(v)
    }
    list.setOnDragListener { _, e ->
        val d = dragged ?: return@setOnDragListener false
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                val i = list.indexOfChild(d)
                val up = list.getChildAt(i - 1)
                val down = list.getChildAt(i + 1)
                // Move the NEIGHBOUR across (the dragged view must stay attached), and only once the finger is inside the
                // slot the dragged row would take after the swap, so rows of different heights can't ping-pong.
                if (up != null && e.y < up.top + d.height) { list.removeView(up); list.addView(up, i) }
                else if (down != null && e.y > down.bottom - d.height) { list.removeView(down); list.addView(down, i) }
                autoScroll(list, e.y)
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                d.alpha = 1f
                dragged = null
                @Suppress("UNCHECKED_CAST")
                val order = (0 until list.childCount).map { list.getChildAt(it).tag as T }
                if (order != current) { current = order; onReorder(order) }
            }
        }
        true
    }
    return list
}

/** Nudge the enclosing ScrollView while a drag hovers near its top or bottom edge. */
private fun autoScroll(list: View, y: Float) {
    val sv = generateSequence(list.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull() ?: return
    val a = IntArray(2).also(list::getLocationInWindow)
    val b = IntArray(2).also(sv::getLocationInWindow)
    val yIn = a[1] - b[1] + y
    val edge = list.context.dp(56)
    if (yIn < edge) sv.scrollBy(0, -list.context.dp(16)) else if (yIn > sv.height - edge) sv.scrollBy(0, list.context.dp(16))
}

fun upsert(regs: List<Registration>, r: Registration): List<Registration> =
    if (regs.any { it.stop == r.stop }) regs.map { if (it.stop == r.stop) r else it } else regs + r
