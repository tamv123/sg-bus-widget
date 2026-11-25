package sg.bus.widget

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Setup's usage-count explainer is folded by default and unfolds on tap (2026-11-26). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34])
class UsageDetailsTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun buttons(v: View): List<Button> = when (v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun detailsFoldedThenUnfold() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val root = activity.window.decorView
        // the first-run guide's first step opens Setup
        buttons(root).first { it.text.toString() == "Let's go" }.performClick()
        idle()
        val details = root.findViewWithTag<TextView>("usage:details") ?: error("no usage details on Setup")
        val toggle = root.findViewWithTag<TextView>("usage:toggle")
        assertEquals(View.GONE, details.visibility)
        assertEquals("Show details ▸", toggle.text.toString())
        assertTrue(root.indexIn(toggle) < root.indexIn(details))

        toggle.performClick(); idle()
        assertEquals(View.VISIBLE, details.visibility)
        assertEquals("Hide details ▾", toggle.text.toString())

        toggle.performClick(); idle()
        assertEquals(View.GONE, details.visibility)
    }

    /** Position of [v] among its parent's children (toggle must sit above the text it unfolds). */
    private fun View.indexIn(v: View): Int = (v.parent as ViewGroup).indexOfChild(v)
}
