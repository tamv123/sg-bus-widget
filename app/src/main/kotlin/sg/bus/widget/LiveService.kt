package sg.bus.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.time.Instant
import sg.bus.core.Arrivals
import sg.bus.core.Pick
import sg.bus.core.Session
import sg.bus.core.StopView
import sg.bus.core.distanceM
import sg.bus.core.etaLabel
import sg.bus.core.parseArrivals
import sg.bus.core.rankStops
import sg.bus.core.saved
import sg.bus.core.upcoming

/**
 * The live session. A widget tap starts this foreground service; it refetches every 20 s until 15 min after that
 * widget's latest tap, then stops itself. JobScheduler can't run that often and OEMs drop queued jobs
 * (sg-pm25-widget's Vivo bug), so every fetch runs right here. One loop serves every live widget, and each stop
 * is fetched once per tick however many widgets show it.
 */
class LiveService : Service() {
    companion object {
        const val ACTION_START = "sg.bus.widget.START"
        const val ACTION_STOP = "sg.bus.widget.STOP"
        private const val CHANNEL = "live"
        private const val NOTIF_ID = 1
    }

    private lateinit var worker: Handler
    private val tick = Runnable { tick() }

    override fun onCreate() {
        super.onCreate()
        worker = Handler(HandlerThread("live").apply { start() }.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Live bus times", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // must be in the foreground within seconds of startForegroundService, before any slow work
        if (!goForeground(notification("Starting…", null))) {
            // e.g. a sticky restart from the background, which Android 12+ may refuse: end cleanly, widgets go idle
            worker.post { endAll(); stopSelf() }
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> worker.post { endAll(); finish() }
            ACTION_START -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) worker.post {
                    // a tap during a session refreshes now and restarts its 15 min; it never starts a second loop
                    val st = Store.state(this, id)
                    Store.saveState(this, id, st.copy(sessionEndMs = Session.endFrom(Instant.now()).toEpochMilli()))
                    Widgets.render(this, id)   // "● Live" straight away, before the first fetch lands
                }
                Directory.refreshIfDue(this)
                restartLoop()
            }
            else -> restartLoop()                // sticky restart after the process was killed: carry on if still live
        }
        return START_STICKY
    }

    private fun restartLoop() {
        worker.removeCallbacks(tick)
        worker.post(tick)
    }

    /** Location type first (gives the session while-in-use location); without it we still run, on the last stop. */
    private fun goForeground(n: Notification): Boolean {
        try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Store.setDiag(this, "fgs", "location+specialUse")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "location FGS refused (${e.javaClass.simpleName}: ${e.message}); specialUse only")
        }
        return try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Store.setDiag(this, "fgs", "specialUse only (no location from a tap)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "foreground refused: ${e.javaClass.simpleName}: ${e.message}")
            Store.setDiag(this, "fgs", "refused — ${e.javaClass.simpleName}")
            false
        }
    }

    private fun tick() {
        val now = Instant.now()
        val live = Widgets.ids(this).filter { Store.state(this, it).sessionEndMs > now.toEpochMilli() }
        if (live.isEmpty()) { Widgets.renderAll(this); finish(); return }

        val regs = Store.registrations(this)
        val stops = Directory.stops(this)
        val loc = Locator.current(this)
        Store.setDiag(this, "loc", loc?.let { "%.5f,%.5f ±%.0f m (%s)".format(it.latitude, it.longitude, it.accuracy, it.provider) } ?: "none")
        val fetched = mutableMapOf<String, Result<Arrivals>>()
        fun arrivals(stop: String) = fetched.getOrPut(stop) {
            runCatching { parseArrivals(Fetcher.arrivals(this, stop), stop) }
                .onFailure { Log.e(TAG, "arrivals $stop failed: ${Fetcher.describe(it)}", it) }
        }

        for (id in live) {
            val st = Store.state(this, id)
            val pinned = Store.config(this, id).pinned
            val picks = if (pinned != null) {
                listOf(Pick(pinned, loc?.let { l -> stops[pinned.stop]?.let { distanceM(l.latitude, l.longitude, it.lat, it.lon) } }))
            } else {
                rankStops(loc?.latitude, loc?.longitude, regs, stops, previous = st.views.firstOrNull()?.stop)
            }
            val wanted = picks.take(if (pinned == null && Widgets.portraitHeightDp(this, id) >= Widgets.SECOND_STOP_MIN_DP) 2 else 1)
            val views = wanted.map { p ->
                val r = arrivals(p.reg.stop)
                val old = st.views.firstOrNull { it.stop == p.reg.stop }
                StopView(
                    stop = p.reg.stop, services = p.reg.services, distanceM = p.distanceM,
                    // a failed fetch keeps the last buses (still anchored on their arrival times) and says why
                    buses = r.getOrNull()?.services?.filterKeys { it in p.reg.services }?.mapValues { (_, b) -> b.map { it.saved() } }
                        ?: old?.buses.orEmpty(),
                    error = r.exceptionOrNull()?.let { Fetcher.describe(it) },
                )
            }
            val note = when {
                pinned != null -> null
                regs.isEmpty() -> "no stops registered — open the app"
                loc == null && !Locator.hasPermission(this) -> "location not allowed — showing last stop"
                loc == null -> "no location fix — showing last stop"
                else -> null
            }
            Store.saveState(this, id, st.copy(views = views, fetchedAtMs = now.toEpochMilli(), note = note))
        }
        Widgets.renderAll(this)
        updateNotification(live, now)
        // every 20 s from this tick's START (the location wait + fetches would otherwise stretch it to ~23 s)
        val spent = java.time.Duration.between(now, Instant.now()).toMillis()
        worker.postDelayed(tick, (Session.INTERVAL.toMillis() - spent).coerceAtLeast(1_000))
    }

    private fun updateNotification(live: List<Int>, now: Instant) {
        val st = Store.state(this, live.first())
        val v = st.views.firstOrNull()
        val title = v?.let { Directory.name(this, it.stop) } ?: "Live bus times"
        val text = v?.services?.joinToString(" · ") { no ->
            "$no " + (upcoming(v.buses[no].orEmpty().map { it.bus() }, now).firstOrNull()?.let { etaLabel(it, now) } ?: "–")
        } ?: (st.note ?: "")
        val endsAt = live.maxOf { Store.state(this, it).sessionEndMs }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, text, endsAt))
    }

    private fun notification(title: String, text: String?, endsAtMs: Long? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .apply { if (endsAtMs != null) setSubText("live until ${java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(sg.bus.core.SGT).format(Instant.ofEpochMilli(endsAtMs))}") }
            .build()
    }

    private fun endAll() {
        for (id in Widgets.ids(this)) Store.saveState(this, id, Store.state(this, id).copy(sessionEndMs = 0))
        Widgets.renderAll(this)
    }

    private fun finish() {
        worker.removeCallbacks(tick)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        worker.looper.quitSafely()
        super.onDestroy()
    }
}
