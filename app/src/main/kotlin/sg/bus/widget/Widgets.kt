package sg.bus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.format.DateTimeFormatter
import sg.bus.core.Load
import sg.bus.core.SGT
import sg.bus.core.StopView
import sg.bus.core.WidgetState
import sg.bus.core.distanceLabel
import sg.bus.core.etaLabel
import sg.bus.core.upcoming

open class BusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ids.forEach { Widgets.render(ctx, it) }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = Widgets.render(ctx, id)
    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach { Store.forget(ctx, it) }
}

class BusWidgetLarge : BusWidgetProvider()
class BusWidgetSmall : BusWidgetProvider()

object Widgets {
    private val providers = listOf(BusWidgetLarge::class.java, BusWidgetSmall::class.java)
    private val ROWS = listOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5)
    private val NOS = listOf(R.id.no0, R.id.no1, R.id.no2, R.id.no3, R.id.no4, R.id.no5)
    private val ETAS = listOf(
        listOf(R.id.e0a, R.id.e0b, R.id.e0c), listOf(R.id.e1a, R.id.e1b, R.id.e1c), listOf(R.id.e2a, R.id.e2b, R.id.e2c),
        listOf(R.id.e3a, R.id.e3b, R.id.e3c), listOf(R.id.e4a, R.id.e4b, R.id.e4c), listOf(R.id.e5a, R.id.e5b, R.id.e5c),
    )
    // the launcher picks the largest of these that fits; every size is the same layout with less hidden
    // 76dp: a 1-row widget on Samsung is ~93dp tall, room for 2 buses (header ~18 + 2 × ~22 + padding 14)
    private val SIZES = listOf(SizeF(110f, 40f), SizeF(110f, TWO_ROW_DP), SizeF(110f, 100f), SizeF(180f, 40f), SizeF(180f, TWO_ROW_DP),
        SizeF(180f, 100f), SizeF(250f, 40f), SizeF(250f, TWO_ROW_DP), SizeF(250f, 100f), SizeF(250f, 150f), SizeF(250f, 200f), SizeF(250f, 260f))
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(SGT)

    /** Second-nearest stop only fits from about 4×3 up (portrait = minWidth × maxHeight, per sg-pm25-widget). */
    const val SECOND_STOP_MIN_DP = 200
    private const val TWO_ROW_DP = 76f

    fun ids(ctx: Context): List<Int> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers.flatMap { mgr.getAppWidgetIds(ComponentName(ctx, it)).toList() }
    }

    fun portraitHeightDp(ctx: Context, id: Int): Int =
        AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)

    /** The live loop and a tap can both render; one at a time. */
    @Synchronized fun renderAll(ctx: Context) = ids(ctx).forEach { render(ctx, it) }

    @Synchronized fun render(ctx: Context, id: Int) {
        val st0 = Store.state(ctx, id)
        val pin = Store.config(ctx, id).pinned
        val pinned = pin != null
        val hasRegs = pinned || Store.registrations(ctx).isNotEmpty()
        // a pinned widget knows its stop before any fetch, so it says which one rather than a bare "tap"
        val st = if (pin != null && st0.views.firstOrNull()?.stop != pin.stop) st0.copy(views = listOf(StopView(pin.stop, pin.services))) else st0
        val now = Instant.now()
        val tap = tapIntent(ctx, id)
        val views = RemoteViews(SIZES.associateWith { build(ctx, st, pinned, hasRegs, it.width, it.height, now).apply {
            setOnClickPendingIntent(R.id.root, tap)
        } })
        AppWidgetManager.getInstance(ctx).updateAppWidget(id, views)
    }

    /** Tap = start (or restart) this widget's live session. A foreground-service start is allowed from a widget tap. */
    private fun tapIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, LiveService::class.java).setAction(LiveService.ACTION_START)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("sgbus://widget/$id"))   // one PendingIntent per widget, not one shared
        return PendingIntent.getForegroundService(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun build(ctx: Context, st: WidgetState, pinned: Boolean, hasRegs: Boolean, w: Float, h: Float, now: Instant): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget)
        val live = st.sessionEndMs > now.toEpochMilli()
        val compact = h < 90
        val etaCols = if (w < 180) 2 else 3
        val first = st.views.firstOrNull()
        val second = st.views.getOrNull(1)?.takeIf { h >= SECOND_STOP_MIN_DP }
        // compact (no status line): 1 bus on a short widget, 2 once there's TWO_ROW_DP of height
        val rows = if (compact) (if (h >= TWO_ROW_DP) 2 else 1) else ((h - 50 - (if (second != null) 44 else 0)) / 22).toInt().coerceIn(1, ROWS.size)

        v.setTextViewText(R.id.stop, when {
            first != null -> Directory.name(ctx, first.stop)
            !hasRegs -> "Add a stop in the SG Bus app"
            else -> "Tap for live bus times"
        })
        val dist = listOfNotNull(
            if (compact && live) "●" else null,
            if (pinned) "📌" else null,
            first?.distanceM?.let(::distanceLabel),
        ).joinToString(" ")
        v.setTextViewText(R.id.dist, dist)
        v.setTextColor(R.id.dist, if (compact && live) LIVE else GREY)

        val services = first?.services.orEmpty()
        ROWS.forEachIndexed { i, row ->
            val no = services.getOrNull(i)
            if (no == null || i >= rows) { v.setViewVisibility(row, View.GONE); return@forEachIndexed }
            v.setViewVisibility(row, View.VISIBLE)
            v.setTextViewText(NOS[i], no)
            v.setTextColor(NOS[i], if (live) BUS_NO else GREY)
            val buses = upcoming(first!!.buses[no].orEmpty().map { it.bus() }, now)
            ETAS[i].forEachIndexed { c, cell ->
                if (c >= etaCols) { v.setViewVisibility(cell, View.GONE); return@forEachIndexed }
                v.setViewVisibility(cell, View.VISIBLE)
                val bus = buses.getOrNull(c)
                v.setTextViewText(cell, when {
                    // idle: clock times, since minutes frozen at the last fetch would be wrong by the time you look
                    bus != null && !live -> HHMM.format(bus.at)
                    bus != null -> etaLabel(bus, now).let { if (c == 0 && etaCols == 3 && it.last().isDigit()) "$it min" else it }
                    c == 0 && live && first.fetched(st) -> "no live info"
                    c == 0 -> "–"
                    else -> ""
                })
                v.setTextColor(cell, if (!live) GREY else when (bus?.load) { Load.SDA -> AMBER; Load.LSD -> RED; else -> TEXT })
            }
        }

        if (second != null) {
            v.setViewVisibility(R.id.stop2, View.VISIBLE)
            v.setViewVisibility(R.id.line2, View.VISIBLE)
            v.setTextViewText(R.id.stop2, Directory.name(ctx, second.stop) + (second.distanceM?.let { " · ${distanceLabel(it)}" } ?: ""))
            v.setTextViewText(R.id.line2, second.services.joinToString("   ") { no ->
                "$no " + (upcoming(second.buses[no].orEmpty().map { it.bus() }, now).firstOrNull()
                    ?.let { if (live) etaLabel(it, now) else HHMM.format(it.at) } ?: "–")
            })
            v.setTextColor(R.id.line2, if (live) TEXT else GREY)
        } else {
            v.setViewVisibility(R.id.stop2, View.GONE)
            v.setViewVisibility(R.id.line2, View.GONE)
        }

        // fail loudly: an error or a note (no location, …) always wins the status line
        val problem = first?.error ?: st.note
        v.setViewVisibility(R.id.status, if (compact) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.status, when {
            problem != null && live -> "⚠ $problem"
            live -> "● Live · ${(st.sessionEndMs - now.toEpochMilli() + 59_999) / 60_000} min left" +
                (if (st.fetchedAtMs > 0) " · ${HHMM.format(Instant.ofEpochMilli(st.fetchedAtMs))}" else "")
            st.fetchedAtMs > 0 -> "Tap for live times · last ${HHMM.format(Instant.ofEpochMilli(st.fetchedAtMs))}"
            else -> "Tap for live bus times"
        })
        v.setTextColor(R.id.status, when { problem != null && live -> RED; live -> LIVE; else -> GREY })
        return v
    }

    private fun StopView.fetched(st: WidgetState) = st.fetchedAtMs > 0 && error == null

    private const val TEXT = 0xFF212121.toInt()
    private const val GREY = 0xFF9E9E9E.toInt()
    private const val BUS_NO = 0xFF1B5E20.toInt()
    private const val LIVE = 0xFF2E7D32.toInt()
    private const val AMBER = 0xFFE65100.toInt()
    private const val RED = 0xFFC62828.toInt()
}
