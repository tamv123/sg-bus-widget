package sg.bus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LookTest {
    @Test fun defaultIsSolidWhiteWithDarkText() {
        val l = WidgetLook()
        assertEquals(255, l.alpha)
        assertFalse(l.dark)
        assertEquals(DARK_TEXT, l.text)
    }

    @Test fun transparencyMapsToAlpha() {
        assertEquals(128, WidgetLook(transparencyPct = 50).alpha)
        assertEquals(0, WidgetLook(transparencyPct = 100).alpha)
        assertEquals(255, WidgetLook(transparencyPct = -5).alpha)   // clamped
    }

    @Test fun darkSwatchesGetLightText() {
        val dark = WidgetLook.PALETTE.filter { WidgetLook(it.second).dark }.map { it.first }
        assertEquals(listOf("Green", "Navy", "Charcoal", "Black"), dark)
        assertEquals(LIGHT_TEXT, WidgetLook(0x000000).text)
    }

    @Test fun luminanceEnds() {
        assertEquals(0.0, luminance(0x000000))
        assertTrue(luminance(0xFFFFFF) > 0.99)
    }
}
