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

/** Menu layout (2026-11-26): Setup = My stops, then Permissions at the bottom; Display = Widgets, look, usage counts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34])
class TabsTest {
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
    /** Section headings on the current page, top to bottom (headings are the 18sp bold labels). */
    private fun headings(root: View) = texts(root).filter { it !is Button && it.isShown && it.textSize == 18f * it.resources.displayMetrics.scaledDensity }
        .map { it.text.toString() }

    @Test fun setupAndDisplayHoldTheRightSections() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val root = a.window.decorView
        texts(root).first { it is Button && it.text.toString() == "Skip guide" }.performClick(); idle()

        val setup = headings(root)
        assertTrue(setup.toString(), setup.containsAll(listOf("My stops", "Permissions")))
        assertTrue(setup.toString(), "Widgets" !in setup && "Widget look" !in setup)
        assertTrue(setup.toString(), setup.indexOf("Permissions") > setup.indexOf("My stops"))
        assertEquals("Permissions", setup.last())

        tapNav(root, "Display"); idle()
        val display = headings(root)
        assertEquals(listOf("Widgets", "Widget look", "Anonymous usage counts"), display)

        // bottom bar, no ☰ drawer
        val labels = listOf("Setup", "Display", "About").map { l ->
            ArrayList<View>().also { root.findViewsWithText(it, l, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) }.count { it.contentDescription == l }
        }
        assertEquals(listOf(1, 1, 1), labels)
        assertTrue(ArrayList<View>().also { root.findViewsWithText(it, "Menu", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) }.isEmpty())
    }
}
