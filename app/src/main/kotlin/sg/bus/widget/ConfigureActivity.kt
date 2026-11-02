package sg.bus.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import sg.bus.core.Registration
import sg.bus.core.WidgetConfig

/**
 * Per-widget setup, shown when a widget is added and from long-press › settings: follow the nearest registered
 * stop (default), or pin this widget to one stop + its own buses (e.g. widget 1 = stop A · 123, widget 2 = stop B · 234).
 */
class ConfigureActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pinned: Registration? = null
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setResult(RESULT_CANCELED, result())
        pinned = Store.config(this, id).pinned
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(32)) }
        setContentView(ScrollView(this).apply { addView(col); fitsSystemWindows = true })
    }

    override fun onResume() {
        super.onResume()
        redraw()
    }

    private fun redraw() {
        col.removeAllViews()
        col.heading("This widget shows…")
        val regs = Store.registrations(this)
        val group = RadioGroup(this)
        val nearest = RadioButton(this).apply { text = "Nearest of my stops (follows your location)"; textSize = 16f; id = 1 }
        group.addView(nearest)
        // a pinned stop that was since removed from My stops still shows, so it can be kept
        val choices = (regs + listOfNotNull(pinned)).distinctBy { it.stop }
        choices.forEachIndexed { i, r ->
            group.addView(RadioButton(this).apply {
                text = "📌 Always ${stopLine(this@ConfigureActivity, r.stop)}"; textSize = 16f; id = 100 + i
            })
        }
        group.check(pinned?.let { p -> 100 + choices.indexOfFirst { it.stop == p.stop } } ?: 1)
        group.setOnCheckedChangeListener { _, checked ->
            pinned = if (checked == 1) null else choices[checked - 100].let { r -> if (pinned?.stop == r.stop) pinned else r }
            redraw()
        }
        col.addView(group)
        if (regs.isEmpty()) col.label("You haven't added any stops yet — add them in the app first.", 14f)
        col.button("Manage my stops") { startActivity(Intent(this, MainActivity::class.java)) }

        pinned?.let { p ->
            col.heading("Buses on this widget")
            col.label(p.services.joinToString(", ").ifEmpty { "(none ticked)" })
            col.button("Choose buses for this widget") {
                Directory.stops(this)[p.stop]?.let { s -> pickServices(this, s, p.services) { pinned = Registration(p.stop, it); redraw() } }
            }
            if (p.services.size > 1) col.button("Change the order") {
                reorderServices(this, "Bus order on this widget", p.services) { pinned = Registration(p.stop, it); redraw() }
            }
        }

        col.button("✅ Done") {
            Store.saveConfig(this, id, WidgetConfig(pinned))
            // the shown stop may change, so drop the old one; the next tap fetches the new one
            Store.saveState(this, id, Store.state(this, id).copy(views = emptyList(), fetchedAtMs = 0))
            Widgets.render(this, id)
            setResult(RESULT_OK, result())
            finish()
        }
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
}
