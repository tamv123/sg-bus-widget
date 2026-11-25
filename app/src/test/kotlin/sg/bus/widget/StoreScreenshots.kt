package sg.bus.widget

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import java.io.File
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sg.bus.core.Registration
import sg.bus.core.WidgetLook

/**
 * Renders the Play Store / README app screenshots (1080×1920) with real graphics, using public example stops.
 * Skipped in normal test runs; run with -Pshots=<output dir>.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshots {
    private val out = System.getProperty("sgbus.shots").orEmpty()
    private val app get() = RuntimeEnvironment.getApplication()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))

    private fun shot(a: Activity, name: String) {
        settle()
        val v = a.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out).mkdirs()
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun call(a: Activity, method: String, vararg args: Any) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == method && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(a, *args)
    }

    private fun tab(name: String): Any =
        Class.forName("sg.bus.widget.MainActivity\$Tab").enumConstants!!.first { it.toString() == name }

    private fun seed(stops: Boolean, tourDone: Boolean) {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        Store.saveLook(app, WidgetLook())   // default: Sky, 30 % transparent
        if (stops) Store.saveRegistrations(app, listOf(
            Registration("09047", listOf("7", "14", "16")),
            Registration("03031", listOf("10", "75")),
            Registration("75009", listOf("3", "15")),
        ))
        if (tourDone) Store.setDiag(app, "tour.done", "screenshots")
    }

    @Test fun render() {
        assumeTrue("run with -Pshots=<dir>", out.isNotEmpty())

        // 1. the setup guide pointing at "Add stop near me" (fresh install)
        seed(stops = false, tourDone = false)
        Robolectric.buildActivity(MainActivity::class.java).setup().get().let { a ->
            settle()
            call(a, "endTour", false)
            settle()
            call(a, "startTour", 3)
            shot(a, "app-1-setup-guide.png")
        }
    }

    @Test fun renderStopsAndLook() {
        assumeTrue("run with -Pshots=<dir>", out.isNotEmpty())
        seed(stops = true, tourDone = true)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(a, "app-2-my-stops.png")
        call(a, "go", tab("SETUP"))
        shot(a, "app-3-widget-look.png")
        call(a, "go", tab("MAIN"))
        call(a, "openDrawer")
        shot(a, "app-4-menu.png")
    }
}
