package sg.bus.widget

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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

fun upsert(regs: List<Registration>, r: Registration): List<Registration> =
    if (regs.any { it.stop == r.stop }) regs.map { if (it.stop == r.stop) r else it } else regs + r
