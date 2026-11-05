package sg.bus.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import sg.bus.core.ARRIVALS_URL
import sg.bus.core.ApiException
import sg.bus.core.DirectoryRefresh
import sg.bus.core.Registration
import sg.bus.core.Stop
import sg.bus.core.WidgetConfig
import sg.bus.core.WidgetLook
import sg.bus.core.WidgetState
import sg.bus.core.configJson
import sg.bus.core.parseStopServices
import sg.bus.core.parseStops

const val TAG = "SGBUS"

/** Everything the app remembers, in one SharedPreferences file: registered stops, and per widget its config + last state. */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bus", Context.MODE_PRIVATE)

    fun registrations(ctx: Context): List<Registration> =
        prefs(ctx).getString("regs", null)?.let { runCatching { configJson.decodeFromString<List<Registration>>(it) }.getOrNull() } ?: emptyList()

    fun saveRegistrations(ctx: Context, regs: List<Registration>) =
        prefs(ctx).edit().putString("regs", configJson.encodeToString(regs)).apply()

    fun config(ctx: Context, id: Int): WidgetConfig =
        prefs(ctx).getString("cfg.$id", null)?.let { runCatching { configJson.decodeFromString<WidgetConfig>(it) }.getOrNull() } ?: WidgetConfig()

    fun saveConfig(ctx: Context, id: Int, c: WidgetConfig) =
        prefs(ctx).edit().putString("cfg.$id", configJson.encodeToString(c)).apply()

    fun state(ctx: Context, id: Int): WidgetState =
        prefs(ctx).getString("st.$id", null)?.let { runCatching { configJson.decodeFromString<WidgetState>(it) }.getOrNull() } ?: WidgetState()

    // commit, not apply: the service renders straight after and a widget redraw reads it back
    fun saveState(ctx: Context, id: Int, s: WidgetState) =
        prefs(ctx).edit().putString("st.$id", configJson.encodeToString(s)).commit()

    fun forget(ctx: Context, id: Int) = prefs(ctx).edit().remove("cfg.$id").remove("st.$id").apply()

    fun look(ctx: Context): WidgetLook = prefs(ctx).run {
        WidgetLook(getInt("look.rgb", WidgetLook.WHITE), getInt("look.transparency", 0))
    }

    fun saveLook(ctx: Context, l: WidgetLook) =
        prefs(ctx).edit().putInt("look.rgb", l.rgb).putInt("look.transparency", l.transparencyPct).apply()

    fun diag(ctx: Context, key: String): String? = prefs(ctx).getString("diag.$key", null)
    fun setDiag(ctx: Context, key: String, value: String) = prefs(ctx).edit().putString("diag.$key", value).apply()
}

/**
 * The stops directory and which services call where. Starts from the busrouter snapshots bundled in assets; a monthly
 * download replaces them in files/directory/. A failed or implausible download keeps what we already have.
 */
object Directory {
    private const val STOPS = "stops.min.json"
    private const val SERVICES = "services.min.json"
    private const val BASE = "https://data.busrouter.sg/v1/"

    @Volatile private var stops: Map<String, Stop>? = null
    @Volatile private var services: Map<String, List<String>>? = null
    private val refreshing = AtomicBoolean(false)

    private fun dir(ctx: Context) = File(ctx.filesDir, "directory")

    private fun read(ctx: Context, name: String): String =
        File(dir(ctx), name).takeIf { it.exists() }?.readText()
            ?: ctx.assets.open(name).bufferedReader().use { it.readText() }

    fun stops(ctx: Context): Map<String, Stop> = stops ?: synchronized(this) {
        stops ?: parseStops(read(ctx, STOPS)).also { stops = it }
    }

    fun servicesAt(ctx: Context, stop: String): List<String> = (services ?: synchronized(this) {
        services ?: parseStopServices(read(ctx, SERVICES)).also { services = it }
    })[stop] ?: emptyList()

    fun name(ctx: Context, code: String): String = stops(ctx)[code]?.name ?: code

    /** Safe to call often: returns at once, and only downloads when due. Runs off the caller's thread. */
    fun refreshIfDue(ctx: Context) {
        val app = ctx.applicationContext
        val lastOk = Store.diag(app, "dir.ok")?.toLongOrNull() ?: 0
        val lastTry = Store.diag(app, "dir.try")?.toLongOrNull() ?: 0
        if (!DirectoryRefresh.due(lastOk, lastTry, System.currentTimeMillis())) return
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            try {
                Store.setDiag(app, "dir.try", System.currentTimeMillis().toString())
                val stopsBody = Fetcher.get(app, BASE + STOPS, STOPS, timeoutMs = 15_000)
                val servicesBody = Fetcher.get(app, BASE + SERVICES, SERVICES, timeoutMs = 15_000)
                DirectoryRefresh.validate(stopsBody, servicesBody)
                dir(app).mkdirs()
                // the pair is swapped only after both are safely on disk, and the rename is atomic per file
                listOf(STOPS to stopsBody, SERVICES to servicesBody).map { (name, body) ->
                    File(dir(app), "$name.new").apply { writeText(body) } to File(dir(app), name)
                }.forEach { (tmp, final) -> check(tmp.renameTo(final)) { "rename ${tmp.name}" } }
                synchronized(this) { stops = null; services = null }
                Store.setDiag(app, "dir.ok", System.currentTimeMillis().toString())
                Store.setDiag(app, "dir.err", "")
                Log.i(TAG, "stops directory refreshed: ${stops(app).size} stops")
            } catch (e: Exception) {
                Store.setDiag(app, "dir.err", Fetcher.describe(e))
                Log.w(TAG, "stops directory refresh failed, keeping the current one: ${Fetcher.describe(e)}")
            } finally {
                refreshing.set(false)
            }
        }.start()
    }
}

object Fetcher {
    /** arrivelah for one stop. 5 s timeouts so one slow fetch can't push the next 20 s tick back. */
    fun arrivals(ctx: Context, stop: String): String =
        get(ctx, ARRIVALS_URL + stop, "fixture-$stop.json", "fixture.json", timeoutMs = 5_000)

    fun get(ctx: Context, url: String, vararg fixtures: String, timeoutMs: Int): String {
        // Emulator-only seam (corporate proxy breaks TLS there): `adb shell run-as` a fixture into files/
        if (BuildConfig.DEBUG) fixtures.map { File(ctx.filesDir, it) }.firstOrNull { it.exists() }?.let {
            Log.w(TAG, "using ${it.path} instead of $url")
            return it.readText()
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "sg-bus-widget/${BuildConfig.VERSION_NAME}")
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException("HTTP $code ${body.take(60)}".trim())
            return body
        } finally {
            c.disconnect()
        }
    }

    fun describe(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "bad response"
        is java.net.UnknownHostException -> "no network"
        is java.net.SocketTimeoutException -> "timed out"
        is javax.net.ssl.SSLException -> "TLS error (network proxy?)"
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }
}

object Locator {
    // stops can be 200 m apart, so an old fix is worse than none (PM2.5's widget tolerates an hour; this can't)
    private const val LAST_KNOWN_MAX_AGE_MS = 10 * 60 * 1000L

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Fresh fix (≤[waitS] s), else the newest last-known fix under 10 min old, else null. Never throws. */
    fun current(ctx: Context, waitS: Long = 3): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) return null
        return try {
            val provider = if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
            val latch = CountDownLatch(1)
            var fix: Location? = null
            val cancel = CancellationSignal()
            lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { fix = it; latch.countDown() }
            if (!latch.await(waitS, TimeUnit.SECONDS)) cancel.cancel()
            val oldest = System.currentTimeMillis() - LAST_KNOWN_MAX_AGE_MS
            fix ?: lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.filter { it.time >= oldest }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            Log.w(TAG, "location denied: ${e.message}")
            null
        }
    }
}
