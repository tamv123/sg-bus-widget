package sg.bus.widget

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import sg.bus.core.Registration
import sg.bus.core.Stop
import sg.bus.core.moved

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

/** Up/down arrows per bus. Order matters: a small widget only has room for the first ones. */
fun reorderServices(ctx: Context, title: String, services: List<String>, onDone: (List<String>) -> Unit) {
    var order = services
    val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(16), 0) }
    fun draw() {
        list.removeAllViews()
        order.forEachIndexed { i, no ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(ctx).apply { text = no; textSize = 17f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.button("▲") { order = order.moved(i, -1); draw() }.isEnabled = i > 0
            row.button("▼") { order = order.moved(i, 1); draw() }.isEnabled = i < order.lastIndex
            list.addView(row)
        }
    }
    draw()
    AlertDialog.Builder(ctx).setTitle(title).setView(ScrollView(ctx).apply { addView(list) })
        .setPositiveButton("Save") { _, _ -> onDone(order) }
        .setNegativeButton("Cancel", null)
        .show()
}

fun upsert(regs: List<Registration>, r: Registration): List<Registration> =
    if (regs.any { it.stop == r.stop }) regs.map { if (it.stop == r.stop) r else it } else regs + r
