package sg.bus.widget

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sg.bus.core.Bus
import sg.bus.core.StopView
import sg.bus.core.WidgetState
import sg.bus.core.saved

/** Once the live session ends the widget must show only the tap prompt, never the last session's times (2026-11-11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34])
class IdleWidgetTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val now = Instant.now()

    private fun state(sessionEndMs: Long) = WidgetState(
        views = listOf(StopView("42139", listOf("77", "970"), distanceM = 120.0,
            buses = mapOf("77" to listOf(Bus(now.plusSeconds(300), true, null).saved()), "970" to listOf(Bus(now.plusSeconds(600), true, null).saved())))),
        fetchedAtMs = now.minusSeconds(60).toEpochMilli(),
        sessionEndMs = sessionEndMs,
    )

    private fun render(st: WidgetState, pinned: Boolean, h: Float): View {
        val parent = FrameLayout(ctx)
        return Widgets.build(ctx, st, pinned, hasRegs = true, w = 180f, h = h, now = now).apply(ctx, parent)
    }

    private fun shownTexts(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text.toString()).filter { it.isNotBlank() }
        v is ViewGroup -> (0 until v.childCount).flatMap { shownTexts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun idleNearestShowsOnlyThePrompt() {
        for (h in listOf(40f, 76f, 100f, 260f)) {
            val texts = shownTexts(render(state(0), pinned = false, h))
            check(texts.any { it == Widgets.TAP_PROMPT }) { "h=$h $texts" }
            check(texts.none { it.contains(Regex("\\d")) }) { "stale numbers at h=$h: $texts" }
        }
    }

    @Test fun idlePinnedKeepsItsStopName() {
        val texts = shownTexts(render(state(0), pinned = true, 100f))
        check(Widgets.TAP_PROMPT in texts && "📌" in texts && texts.none { it.contains(Regex("\\d")) }) { texts }
    }

    @Test fun liveStillShowsTimes() {
        val texts = shownTexts(render(state(now.plusSeconds(600).toEpochMilli()), pinned = false, 100f))
        check(texts.any { it.startsWith("● Live") } && "77" in texts) { texts }
    }
}
