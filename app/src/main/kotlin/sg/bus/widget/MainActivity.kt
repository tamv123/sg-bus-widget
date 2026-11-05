package sg.bus.widget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.time.Instant
import java.time.format.DateTimeFormatter
import sg.bus.core.Registration
import sg.bus.core.SGT
import sg.bus.core.Stop
import sg.bus.core.WidgetLook
import sg.bus.core.distanceLabel
import sg.bus.core.nearbyStops
import sg.bus.core.searchStops

/**
 * Three tabs behind a ☰ drawer: Main (My stops + Widgets), Setup (permissions), About (version + diagnostics).
 * Platform views only, so the drawer is a hand-rolled overlay rather than AndroidX DrawerLayout.
 */
class MainActivity : Activity() {
    private enum class Tab(val label: String, val title: String) {
        MAIN("🚏  Main", "SG Bus"), SETUP("⚙️  Setup", "Setup"), ABOUT("ℹ️  About", "About")
    }

    private var tab = Tab.MAIN
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var scrim: View
    private lateinit var drawer: LinearLayout
    private lateinit var titleView: TextView
    private val closeOnBack = OnBackInvokedCallback { closeDrawer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getString("tab")?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.MAIN
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(32)) }
        scroll = ScrollView(this).apply { addView(col) }
        scrim = View(this).apply {
            setBackgroundColor(0x66000000); visibility = View.GONE; alpha = 0f; setOnClickListener { closeDrawer() }
        }
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12))
            setBackgroundColor(themeColor(android.R.attr.colorBackground)); elevation = dp(16).toFloat()
            visibility = View.GONE; translationX = -dp(DRAWER_DP).toFloat(); isClickable = true   // swallow taps behind it
        }
        titleView = TextView(this).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56); setPadding(dp(4), 0, dp(16), 0)
            addView(iconButton(R.drawable.ic_menu, "Menu") { if (drawer.visibility == View.VISIBLE) closeDrawer() else openDrawer() })
            addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        val body = FrameLayout(this).apply {
            addView(scroll)
            addView(scrim)
            addView(drawer, FrameLayout.LayoutParams(dp(DRAWER_DP), FrameLayout.LayoutParams.MATCH_PARENT))
        }
        actionBar?.hide()   // our own bar: the ☰ bubble sits top-left whatever the theme does
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
            addView(bar)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tab", tab.name)
    }

    override fun onResume() {
        super.onResume()
        // opening the app re-attaches the widgets' taps (a force-stop cancels their PendingIntents)
        Widgets.renderAll(this)
        Directory.refreshIfDue(this)
        redraw()
    }

    private fun openDrawer() {
        drawMenu()
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        drawer.animate().translationX(0f).setDuration(200).start()
        scrim.animate().alpha(1f).setDuration(200).start()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeOnBack)
    }

    private fun closeDrawer() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeOnBack)
        drawer.animate().translationX(-dp(DRAWER_DP).toFloat()).setDuration(180).withEndAction { drawer.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
    }

    private fun go(t: Tab) {
        tab = t
        redraw()
        scroll.scrollTo(0, 0)
    }

    private fun drawMenu() {
        drawer.removeAllViews()
        drawer.addView(TextView(this).apply {
            text = "SG Bus"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(24), dp(8), dp(24), dp(16))
        })
        val accent = themeColor(android.R.attr.colorAccent)
        Tab.entries.forEach { t ->
            val warn = if (t == Tab.SETUP && !Locator.hasPermission(this)) "  ⚠" else ""
            drawer.addView(TextView(this).apply {
                text = t.label + warn; textSize = 16f; gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(52); setPadding(dp(24), 0, dp(24), 0)
                if (t == tab) {
                    setTypeface(typeface, Typeface.BOLD); setTextColor(accent)
                    setBackgroundColor(accent and 0x00FFFFFF or 0x22000000)
                }
                setOnClickListener { closeDrawer(); go(t) }
            })
        }
    }

    private fun redraw() {
        titleView.text = tab.title
        col.removeAllViews()
        when (tab) {
            Tab.MAIN -> drawMain()
            Tab.SETUP -> drawSetup()
            Tab.ABOUT -> drawAbout()
        }
        if (drawer.visibility == View.VISIBLE) drawMenu()
    }

    private fun drawMain() {
        if (!Locator.hasPermission(this)) {
            col.label("⚠ Location is off, so widgets can't tell which stop is nearest.")
            col.button("Fix in Setup") { go(Tab.SETUP) }
        }
        col.heading("My stops")
        val regs = Store.registrations(this)
        if (regs.isEmpty()) col.label("No stops yet. Add the stops you take buses from; a widget shows whichever is nearest.")
        if (regs.size > 1) col.label("$DRAG_HINT The top stop is shown when your location can't be found.", 13f)
            .setTextColor(themeColor(android.R.attr.textColorSecondary))
        col.addView(dragList(regs, ::stopRow) { order ->
            Store.saveRegistrations(this, order)
            Widgets.renderAll(this)
        })
        col.button("➕ Add stop near me") { addNearMe() }
        col.button("🔎 Add stop by code or name") { addBySearch() }

        col.heading("Widgets")
        col.label("Tap a widget for live times: refreshes every 20 s for 15 min. Long-press › settings to pin a widget to one stop.", 14f)
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            col.button("Add large widget (4×2)") { mgr.requestPinAppWidget(ComponentName(this, BusWidgetLarge::class.java), null, null) }
            col.button("Add small widget (2×1)") { mgr.requestPinAppWidget(ComponentName(this, BusWidgetSmall::class.java), null, null) }
        } else col.label("Long-press your home screen › Widgets › SG Bus.", 14f)
    }

    /** One My-stops row: ⠿ handle · stop + its buses + [Buses] [Bus order] · 🗑. */
    private fun stopRow(r: Registration): Pair<View, View> {
        val handle = dragHandle()
        val gone = if (Directory.stops(this)[r.stop] == null) " ⚠ no longer in LTA's stop list" else ""
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply { this.text = "${stopLine(this@MainActivity, r.stop)}$gone"; textSize = 16f })
        text.addView(TextView(this).apply {
            this.text = r.services.joinToString(", ").ifEmpty { "(no buses ticked)" }; textSize = 14f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.button("Buses") { Directory.stops(this)[r.stop]?.let { s -> pickServices(this, s, r.services) { save(Registration(r.stop, it)) } } }
        if (r.services.size > 1) buttons.button("Bus order") {
            reorderServices(this, "Bus order at ${Directory.name(this, r.stop)}", r.services) { save(Registration(r.stop, it)) }
        }
        text.addView(buttons)
        val trash = iconButton(R.drawable.ic_delete, "Delete ${r.stop}") {
            AlertDialog.Builder(this).setMessage("Delete ${stopLine(this, r.stop)}?")
                .setPositiveButton("Delete") { _, _ ->
                    Store.saveRegistrations(this, Store.registrations(this).filter { it.stop != r.stop })
                    Widgets.renderAll(this)
                    redraw()
                }
                .setNegativeButton("Cancel", null).show()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(handle)
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(trash)
        return row to handle
    }

    private fun drawSetup() {
        drawLook()
        col.heading("Permissions")
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        col.label(if (fine) "✅ Location allowed (picks your nearest stop)" else "❌ Location not allowed — widgets can't tell which stop is nearest")
        if (!fine) col.button("Allow location") { askLocation() }
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        col.label(if (notif) "✅ Notifications allowed (live times in the shade)" else "⚠ Notifications off — live times still update the widget")
        if (!notif) col.button("Allow notifications") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
        val bg = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine && !bg && Store.diag(this, "fgs")?.startsWith("specialUse") == true) {
            col.label("⚠ Your phone didn't give the widget location from a tap. Set location to \"Allow all the time\" so it can pick the nearest stop.")
            col.button("Open location settings") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    /** Widget look: colour swatches + transparency, with a live preview over a wallpaper-like gradient. */
    private fun drawLook() {
        col.heading("Widget look")
        var look = Store.look(this)
        val preview = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4A6FA5.toInt(), 0xFFE8A87C.toInt()))
                .apply { cornerRadius = dp(16).toFloat() }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val stop = TextView(this).apply { text = "Avenue Villa"; textSize = 13f; setTypeface(typeface, Typeface.BOLD) }
        val no = TextView(this).apply { text = "970"; textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
        val eta = TextView(this).apply { text = "3 min    14    27"; textSize = 15f }
        val status = TextView(this).apply { text = "Tap for live times · last 08:12"; textSize = 11f }
        card.addView(stop)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(no, LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(eta)
        })
        card.addView(status)
        preview.addView(card)
        fun paint() {
            card.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor((look.alpha shl 24) or look.rgb)
            }
            val c = look.text
            stop.setTextColor(c.main); no.setTextColor(c.busNo); eta.setTextColor(c.main); status.setTextColor(c.grey)
        }
        fun apply() { Store.saveLook(this, look); Widgets.renderAll(this) }

        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(8)) }
        fun drawSwatches() {
            swatches.removeAllViews()
            WidgetLook.PALETTE.forEach { (name, rgb) ->
                swatches.addView(View(this).apply {
                    contentDescription = name + if (rgb == look.rgb) ", selected" else ""
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(0xFF000000.toInt() or rgb)
                        val sel = rgb == look.rgb
                        setStroke(dp(if (sel) 3 else 1), if (sel) themeColor(android.R.attr.colorAccent) else 0x55888888)
                    }
                    setOnClickListener { look = look.copy(rgb = rgb); paint(); drawSwatches(); apply() }
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
            }
        }
        drawSwatches()
        col.label("Background colour", 14f)
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(swatches) })

        val pct = col.label("Transparency: ${look.transparencyPct}%", 14f)
        col.addView(android.widget.SeekBar(this).apply {
            max = 20; progress = look.transparencyPct / 5   // 5 % steps
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    look = look.copy(transparencyPct = p * 5); pct.text = "Transparency: ${look.transparencyPct}%"; paint()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) = apply()   // redraw widgets once, on release
            })
        })
        col.label("Text turns light on dark colours. The more transparent, the more your wallpaper decides how readable it is.", 12f)
            .setTextColor(themeColor(android.R.attr.textColorSecondary))
        paint()
        col.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
    }

    private fun drawAbout() {
        col.heading("SG Bus")
        val ver = packageManager.getPackageInfo(packageName, 0)
        col.label("Version ${ver.versionName} (build ${ver.longVersionCode})", 13f)
        val ids = Widgets.ids(this)
        col.label("Widgets placed: ${ids.size} · live service: ${Store.diag(this, "fgs") ?: "not run yet"}", 13f)
        col.label("Last fix: ${Store.diag(this, "loc") ?: "–"}", 13f)
        val dirAt = Store.diag(this, "dir.ok")?.toLongOrNull()?.let { DateTimeFormatter.ofPattern("d MMM yyyy").withZone(SGT).format(Instant.ofEpochMilli(it)) }
        val dirErr = Store.diag(this, "dir.err")?.takeIf { it.isNotEmpty() }?.let { " · last update failed: $it" } ?: ""
        col.label("Stops list: ${Directory.stops(this).size} stops, ${dirAt?.let { "updated $it" } ?: "built in"}$dirErr", 13f)
        ids.forEach { id ->
            val st = Store.state(this, id)
            val at = if (st.fetchedAtMs > 0) DateTimeFormatter.ofPattern("HH:mm:ss").withZone(SGT).format(Instant.ofEpochMilli(st.fetchedAtMs)) else "never"
            val mode = Store.config(this, id).pinned?.let { "📌 ${it.stop}" } ?: "nearest"
            col.label("#$id $mode · last fetch $at${st.views.firstOrNull()?.error?.let { " · ⚠ $it" } ?: ""}", 13f)
        }
        col.label("Arrival data: arrivelah (busrouter.sg), from LTA DataMall. Not affiliated with LTA.", 12f)
    }

    private companion object { const val DRAWER_DP = 280 }

    private fun save(r: Registration) {
        Store.saveRegistrations(this, upsert(Store.registrations(this), r))
        Widgets.renderAll(this)
        redraw()
    }

    private fun addStop(s: Stop) = pickServices(this, s, Store.registrations(this).firstOrNull { it.stop == s.code }?.services.orEmpty()) {
        save(Registration(s.code, it))
    }

    /** Plain-language disclosure before Android's own prompt (Play's prominent-disclosure rule). */
    private fun askLocation() {
        AlertDialog.Builder(this).setTitle("Location")
            .setMessage("SG Bus uses your location to find which of your stops is nearest. It reads it when you tap a widget " +
                "and during the 15-minute live session that follows, even if the app is closed.\n\n" +
                "Your location stays on your phone. Only the bus stop code is sent, to fetch arrival times.")
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            }
            .setNegativeButton("Not now", null).show()
    }

    private fun addNearMe() {
        if (!Locator.hasPermission(this)) {
            askLocation()
            return
        }
        val wait = AlertDialog.Builder(this).setMessage("Finding stops near you…").show()
        Thread {
            val loc = Locator.current(this, waitS = 8)
            runOnUiThread {
                wait.dismiss()
                if (loc == null) {
                    AlertDialog.Builder(this).setMessage("Couldn't get your location. Is location on? You can add by code instead.")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val near = nearbyStops(loc.latitude, loc.longitude, Directory.stops(this).values, 12)
                chooseStop("Stops near you", near.map { (s, d) -> s to "${s.name} · ${s.road} (${s.code}) — ${distanceLabel(d)}" })
            }
        }.start()
    }

    private fun addBySearch() {
        val input = EditText(this).apply { hint = "e.g. 42139 or avenue villa"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Find a stop").setView(input)
            .setPositiveButton("Search") { _, _ ->
                val hits = searchStops(input.text.toString(), Directory.stops(this))
                if (hits.isEmpty()) AlertDialog.Builder(this).setMessage("No stop matches \"${input.text}\".").setPositiveButton("OK", null).show()
                else chooseStop("Matching stops", hits.map { it to "${it.name} · ${it.road} (${it.code})" })
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseStop(title: String, items: List<Pair<Stop, String>>) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.map { it.second }.toTypedArray()) { _, i -> addStop(items[i].first) }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = redraw()
}
