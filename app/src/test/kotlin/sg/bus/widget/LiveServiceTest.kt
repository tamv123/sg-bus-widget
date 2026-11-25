package sg.bus.widget

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The live session must go foreground on Android 13 (no specialUse type there) as well as 14+ (2026-11-26, S20+). */
@RunWith(RobolectricTestRunner::class)
class LiveServiceTest {
    private val ctx = RuntimeEnvironment.getApplication()

    private fun start() {
        Robolectric.buildService(LiveService::class.java, Intent(ctx, LiveService::class.java).setAction(LiveService.ACTION_STOP))
            .create().startCommand(0, 1)
    }

    @Test @Config(sdk = [33])
    fun android13_isPlainLocationService() {
        start()
        assertEquals("location (Android 13)", Store.diag(ctx, "fgs"))
    }

    @Test @Config(sdk = [34])
    fun android14_keepsLocationPlusSpecialUse() {
        start()
        assertEquals("location+specialUse", Store.diag(ctx, "fgs"))
    }
}
