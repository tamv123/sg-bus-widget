package sg.bus.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import sg.bus.core.ApiException
import sg.bus.core.UsageLog
import sg.bus.core.appOpen
import sg.bus.core.configJson
import sg.bus.core.pruned
import sg.bus.core.sent
import sg.bus.core.syncWidgets
import sg.bus.core.tap
import sg.bus.core.usageDay
import sg.bus.core.usageDocId
import sg.bus.core.usageFirestoreJson

/**
 * Anonymous usage counts (see sg.bus.core.Usage for exactly what is sent). On by default, off switch in Setup.
 * Turning it off stops counting and sending at once and drops the counts not yet sent.
 * Uploads ride on moments the phone is already online (a widget tap, opening the app): no background jobs.
 */
object Usage {
    private const val ENDPOINT =
        "https://firestore.googleapis.com/v1/projects/ria-chatbot-52232f7f/databases/(default)/documents/sgbus_usage/"
    private val flushing = AtomicBoolean(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", true)

    @Synchronized fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("on", on).apply { if (!on) remove("log") }.commit()
    }

    /** Random, made on this phone, not derived from anything about you or the device. Gone on uninstall. */
    @Synchronized private fun install(ctx: Context): String =
        prefs(ctx).getString("install", null) ?: UUID.randomUUID().toString().also { prefs(ctx).edit().putString("install", it).commit() }

    @Synchronized private fun load(ctx: Context): UsageLog =
        prefs(ctx).getString("log", null)?.let { runCatching { configJson.decodeFromString<UsageLog>(it) }.getOrNull() } ?: UsageLog()

    @Synchronized private fun update(ctx: Context, f: (UsageLog, String) -> UsageLog) {
        if (!enabled(ctx)) return
        val today = usageDay(Instant.now())
        prefs(ctx).edit().putString("log", configJson.encodeToString(f(load(ctx), today).pruned(today))).commit()
    }

    fun tap(ctx: Context) {
        update(ctx) { log, day -> log.tap(day) }
        syncWidgets(ctx)
        flush(ctx)
    }

    fun appOpen(ctx: Context) {
        update(ctx) { log, day -> log.appOpen(day) }
        syncWidgets(ctx)
        flush(ctx)
    }

    /** Called whenever widgets may have come or gone; counts are sent with the next tap or app open. */
    fun syncWidgets(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        fun ids(c: Class<*>) = mgr.getAppWidgetIds(ComponentName(ctx, c)).toSet()
        val small = ids(BusWidgetSmall::class.java)
        val large = ids(BusWidgetLarge::class.java)
        update(ctx) { log, day -> log.syncWidgets(day, small, large) }
    }

    /** Send every day with unsent changes (normally just today). Failures stay queued for the next try. */
    fun flush(ctx: Context) {
        if (!enabled(ctx) || !flushing.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try {
                val id = install(app)
                val log = load(app)
                for (day in log.dirty.sorted()) {
                    val d = log.days[day] ?: continue
                    post(usageDocId(id, day), usageFirestoreJson(id, d, BuildConfig.VERSION_NAME, BuildConfig.DEBUG).toString())
                    update(app) { l, _ -> l.sent(day, d) }
                }
                Store.setDiag(app, "usage", "sent ${java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")
                    .withZone(sg.bus.core.SGT).format(Instant.now())}")
            } catch (e: Exception) {
                Store.setDiag(app, "usage", "not sent: ${Fetcher.describe(e)}")
                Log.w(TAG, "usage counts not sent, will retry: ${Fetcher.describe(e)}")
            } finally {
                flushing.set(false)
            }
        }.start()
    }

    private fun post(docId: String, body: String) {
        val c = URL(ENDPOINT + docId).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")   // HttpURLConnection can't send PATCH itself
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "sg-bus-widget/${BuildConfig.VERSION_NAME}")
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw ApiException("HTTP $code ${err.take(80)}".trim())
            }
        } finally {
            c.disconnect()
        }
    }
}
