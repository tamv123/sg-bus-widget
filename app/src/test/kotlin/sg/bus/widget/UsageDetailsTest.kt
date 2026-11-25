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
    /** Tap a bottom-bar tab (each item's content description is its label). */
    private fun tapNav(root: View, label: String) {
        val found = ArrayList<View>(); root.findViewsWithText(found, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        found.first { it.contentDescription == label }.performClick(); idle()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun texts(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun buttons(v: View): List<Button> = when (v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun detailsFoldedThenUnfold() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val root = activity.window.decorView
        // skip the first-run guide, then open About in the bottom bar (where the usage switch lives)
        buttons(root).first { it.text.toString() == "Skip guide" }.performClick()
        idle()
        tapNav(root, "About")
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
