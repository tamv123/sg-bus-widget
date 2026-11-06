package sg.bus.widget

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Walks the first-run setup guide end to end on the JVM (Victor's S25+ crashed on "Let's go", 2026-11-06). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupGuideTest {
    private fun buttons(v: View): List<Button> = when (v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun walksEveryStepWithoutCrashing() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val root = activity.window.decorView
        // a fresh install starts the guide on its own
        val labels = mutableListOf<String>()
        repeat(6) {
            val next = buttons(root).firstOrNull { it.isShown && it.text.toString() in setOf("Let's go", "Next", "Done") }
                ?: error("no Next button after ${labels}")
            labels += next.text.toString()
            next.performClick()
            idle()
        }
        check(labels == listOf("Let's go", "Next", "Next", "Next", "Next", "Done")) { labels }
    }
}
