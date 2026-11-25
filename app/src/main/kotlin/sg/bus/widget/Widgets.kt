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
import android.util.TypedValue
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
import sg.bus.core.fitRows
import sg.bus.core.secondStopDp
import sg.bus.core.upcoming

open class BusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { Widgets.render(ctx, it) }
        Usage.syncWidgets(ctx)
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = Widgets.render(ctx, id)
    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { Store.forget(ctx, it) }
        Usage.syncWidgets(ctx)
    }
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
    // Fallback sizes; the launcher picks the largest that fits. The widget's REAL sizes (OPTION_APPWIDGET_SIZES) are
    // added per widget, so on launchers that report them the layout is fitted to the exact height (the 2×1's status
    // line was clipped on an S25+ when it was laid out for a 90dp step). RemoteViews takes at most 16 sizes.
    private val FALLBACK_SIZES = listOf(110f, 180f).flatMap { w -> listOf(40f, 76f, 100f).map { SizeF(w, it) } } +
        listOf(SizeF(180f, 150f), SizeF(180f, 200f), SizeF(180f, 260f))
    private val TEXT_SP = mapOf(R.id.stop to 13f, R.id.dist to 12f, R.id.stop2 to 12f, R.id.line2 to 13f, R.id.status to 11f) +
        NOS.associateWith { 15f } + ETAS.flatten().associateWith { 15f }
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(SGT)
    const val TAP_PROMPT = "Tap for live update"

    /** Second-nearest stop only fits from about 4×3 up (portrait = minWidth × maxHeight, per sg-pm25-widget). */
    const val SECOND_STOP_MIN_DP = 200

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
        val real = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
            .getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java).orEmpty()
        val sizes = (real + FALLBACK_SIZES).distinct().take(16)
        val views = RemoteViews(sizes.associateWith { build(ctx, st, pinned, hasRegs, it.width, it.height, now).apply {
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

    internal fun build(ctx: Context, st: WidgetState, pinned: Boolean, hasRegs: Boolean, w: Float, h: Float, now: Instant): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget)
        val look = Store.look(ctx)
        val tc = look.text
        v.setInt(R.id.bg, "setColorFilter", 0xFF000000.toInt() or look.rgb)
        v.setInt(R.id.bg, "setImageAlpha", look.alpha)
        v.setTextColor(R.id.stop, tc.main)
        v.setTextColor(R.id.stop2, tc.secondary)
        // user's text size; sp already includes Android's own font-size setting, so the fit multiplies both
        val scale = look.textScale
        TEXT_SP.forEach { (id, sp) -> v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * scale) }
        NOS.forEach { v.setViewLayoutWidth(it, 52f * scale, TypedValue.COMPLEX_UNIT_DIP) }
        val fontScale = ctx.resources.configuration.fontScale * scale
        val live = st.sessionEndMs > now.toEpochMilli()
        val etaCols = if (w < 180) 2 else 3
        val first = st.views.firstOrNull()
        // Idle (no session, or the 15 min ran out): show NO bus times, distance or nearest stop. They are from the
        // last session, so old clock times (17:0x) read as if they were live. Only what can't go stale stays: a pinned
        // widget's own stop. The tap prompt takes their place.
        val second = st.views.getOrNull(1)?.takeIf { live && h >= SECOND_STOP_MIN_DP }
        val fit = fitRows(h, fontScale, ROWS.size, if (second != null) secondStopDp(fontScale) else 0f)
        val rows = if (live) fit.rows else 0
        val compact = !fit.status   // no room for the status line: a ● in the header shows "live" instead
        // idle has no bus rows, so the prompt fits on the status line unless the widget is one bare row high
        val idleStatus = !live && hasRegs && !(fit.rows == 1 && !fit.status)

        v.setTextViewText(R.id.stop, when {
            !hasRegs -> "Add a stop in the SG Bus app"
            live && first != null -> Directory.name(ctx, first.stop)
            live -> "Finding your stop…"
            pinned && idleStatus -> Directory.name(ctx, first!!.stop)
            idleStatus -> "Nearest stop"
            else -> TAP_PROMPT
        })
        val dist = listOfNotNull(
            if (compact && live) "●" else null,
            if (pinned) "📌" else null,
            if (live) first?.distanceM?.let(::distanceLabel) else null,
        ).joinToString(" ")
        v.setTextViewText(R.id.dist, dist)
        v.setTextColor(R.id.dist, if (compact && live) tc.live else tc.grey)

        val services = first?.services.orEmpty()
        ROWS.forEachIndexed { i, row ->
            val no = services.getOrNull(i)
            if (no == null || i >= rows) { v.setViewVisibility(row, View.GONE); return@forEachIndexed }
            v.setViewVisibility(row, View.VISIBLE)
            v.setTextViewText(NOS[i], no)
            v.setTextColor(NOS[i], tc.busNo)
            val buses = upcoming(first!!.buses[no].orEmpty().map { it.bus() }, now)
            ETAS[i].forEachIndexed { c, cell ->
                if (c >= etaCols) { v.setViewVisibility(cell, View.GONE); return@forEachIndexed }
                v.setViewVisibility(cell, View.VISIBLE)
                val bus = buses.getOrNull(c)
                v.setTextViewText(cell, when {
                    bus != null -> etaLabel(bus, now).let { if (c == 0 && etaCols == 3 && it.last().isDigit()) "$it min" else it }
                    c == 0 && live && first.fetched(st) -> "no live info"
                    c == 0 -> "–"
                    else -> ""
                })
                v.setTextColor(cell, when (bus?.load) { Load.SDA -> tc.amber; Load.LSD -> tc.red; else -> tc.main })
            }
        }

        if (second != null) {
            v.setViewVisibility(R.id.stop2, View.VISIBLE)
            v.setViewVisibility(R.id.line2, View.VISIBLE)
            v.setTextViewText(R.id.stop2, Directory.name(ctx, second.stop) + (second.distanceM?.let { " · ${distanceLabel(it)}" } ?: ""))
            v.setTextViewText(R.id.line2, second.services.joinToString("   ") { no ->
                "$no " + (upcoming(second.buses[no].orEmpty().map { it.bus() }, now).firstOrNull()
                    ?.let { etaLabel(it, now) } ?: "–")
            })
            v.setTextColor(R.id.line2, tc.main)
        } else {
            v.setViewVisibility(R.id.stop2, View.GONE)
            v.setViewVisibility(R.id.line2, View.GONE)
        }

        // fail loudly: an error or a note (no location, …) always wins the status line
        val problem = first?.error ?: st.note
        v.setViewVisibility(R.id.status, if ((live && !compact) || idleStatus) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.status, when {
            problem != null && live -> "⚠ $problem"
            live -> "● Live · ${(st.sessionEndMs - now.toEpochMilli() + 59_999) / 60_000} min left" +
                (if (st.fetchedAtMs > 0) " · ${HHMM.format(Instant.ofEpochMilli(st.fetchedAtMs))}" else "")
            else -> TAP_PROMPT
        })
        v.setTextColor(R.id.status, when { problem != null && live -> tc.red; live -> tc.live; else -> tc.grey })
        return v
    }

    private fun StopView.fetched(st: WidgetState) = st.fetchedAtMs > 0 && error == null
}
