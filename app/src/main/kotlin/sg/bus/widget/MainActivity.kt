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
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.Instant
import java.time.format.DateTimeFormatter
import sg.bus.core.Registration
import sg.bus.core.SGT
import sg.bus.core.Stop
import sg.bus.core.distanceLabel
import sg.bus.core.nearbyStops
import sg.bus.core.searchStops

/** Setup + "My stops": register stops and the buses you care about at each; widgets pick from these. */
class MainActivity : Activity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(32)) }
        setContentView(ScrollView(this).apply { addView(col); fitsSystemWindows = true })
    }

    override fun onResume() {
        super.onResume()
        // opening the app re-attaches the widgets' taps (a force-stop cancels their PendingIntents)
        Widgets.renderAll(this)
        redraw()
    }

    private fun redraw() {
        col.removeAllViews()
        col.heading("My stops")
        val regs = Store.registrations(this)
        if (regs.isEmpty()) col.label("No stops yet. Add the stops you take buses from; a widget shows whichever is nearest.")
        regs.forEach { r ->
            col.label("🚏 ${stopLine(this, r.stop)}\n     ${r.services.joinToString(", ").ifEmpty { "(no buses ticked)" }}")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.button("Buses") { Directory.stops(this)[r.stop]?.let { s -> pickServices(this, s, r.services) { save(Registration(r.stop, it)) } } }
            row.button("Remove") {
                AlertDialog.Builder(this).setMessage("Remove ${stopLine(this, r.stop)}?")
                    .setPositiveButton("Remove") { _, _ -> Store.saveRegistrations(this, Store.registrations(this).filter { it.stop != r.stop }); redraw() }
                    .setNegativeButton("Cancel", null).show()
            }
            col.addView(row)
        }
        col.button("➕ Add stop near me") { addNearMe() }
        col.button("🔎 Add stop by code or name") { addBySearch() }
        if (regs.none { it.stop == "42139" }) col.button("Quick add: Avenue Villa (42139) · 970") {
            save(Registration("42139", listOf("970")))
        }

        col.heading("Setup")
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        col.label(if (fine) "✅ Location allowed (picks your nearest stop)" else "❌ Location not allowed — widgets can't tell which stop is nearest")
        if (!fine) col.button("Allow location") { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1) }
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

        col.heading("Widgets")
        col.label("Tap a widget for live times: refreshes every 20 s for 15 min. Long-press › settings to pin a widget to one stop.", 14f)
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            col.button("Add large widget (4×2)") { mgr.requestPinAppWidget(ComponentName(this, BusWidgetLarge::class.java), null, null) }
            col.button("Add small widget (2×1)") { mgr.requestPinAppWidget(ComponentName(this, BusWidgetSmall::class.java), null, null) }
        } else col.label("Long-press your home screen › Widgets › SG Bus.", 14f)

        col.heading("About")
        val ver = packageManager.getPackageInfo(packageName, 0)
        col.label("Version ${ver.versionName} (build ${ver.longVersionCode})", 13f)
        val ids = Widgets.ids(this)
        col.label("Widgets placed: ${ids.size} · live service: ${Store.diag(this, "fgs") ?: "not run yet"}", 13f)
        col.label("Last fix: ${Store.diag(this, "loc") ?: "–"}", 13f)
        ids.forEach { id ->
            val st = Store.state(this, id)
            val at = if (st.fetchedAtMs > 0) DateTimeFormatter.ofPattern("HH:mm:ss").withZone(SGT).format(Instant.ofEpochMilli(st.fetchedAtMs)) else "never"
            val mode = Store.config(this, id).pinned?.let { "📌 ${it.stop}" } ?: "nearest"
            col.label("#$id $mode · last fetch $at${st.views.firstOrNull()?.error?.let { " · ⚠ $it" } ?: ""}", 13f)
        }
        col.label("Arrival data: arrivelah (busrouter.sg), from LTA DataMall. Not affiliated with LTA.", 12f)
    }

    private fun save(r: Registration) {
        Store.saveRegistrations(this, upsert(Store.registrations(this), r))
        Widgets.renderAll(this)
        redraw()
    }

    private fun addStop(s: Stop) = pickServices(this, s, Store.registrations(this).firstOrNull { it.stop == s.code }?.services.orEmpty()) {
        save(Registration(s.code, it))
    }

    private fun addNearMe() {
        if (!Locator.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
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
