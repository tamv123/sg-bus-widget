package sg.bus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LookTest {
    @Test fun defaultIsSky30PercentWithDarkText() {
        val l = WidgetLook()
        assertEquals("Sky", WidgetLook.PALETTE[4].first)
        assertEquals(WidgetLook.PALETTE[4].second, l.rgb)
        assertEquals(179, l.alpha)
        assertFalse(l.dark)
        assertEquals(DARK_TEXT, l.text)
        assertEquals(1f, l.textScale)
    }

    @Test fun textSizeIsClamped() {
        assertEquals(0.7f, WidgetLook(textPct = 10).textScale)
        assertEquals(1.3f, WidgetLook(textPct = 500).textScale)
    }

    @Test fun samsungTwoByOneFitsTwoBusesAndStatusAtNormalText() {
        assertEquals(Fit(2, true), fitRows(93f, 1f, maxRows = 6))
    }

    @Test fun biggerTextDropsTheStatusBeforeTheSecondBus() {
        assertEquals(Fit(2, false), fitRows(93f, 1.15f, maxRows = 6))
        assertEquals(Fit(2, true), fitRows(93f, 1.15f * 0.85f, maxRows = 6))   // the text slider wins it back
    }

    @Test fun tinyWidgetsShowOneRow() {
        assertEquals(Fit(1, false), fitRows(40f, 1f, maxRows = 6))
        assertEquals(Fit(1, true), fitRows(70f, 1f, maxRows = 6))
    }

    @Test fun bigWidgetsAreCappedAndMakeRoomForTheSecondStop() {
        assertEquals(Fit(6, true), fitRows(400f, 1f, maxRows = 6))
        val withSecond = fitRows(200f, 1f, maxRows = 6, extraDp = secondStopDp(1f))
        assertEquals(Fit(4, true), withSecond)
    }

    @Test fun transparencyMapsToAlpha() {
        assertEquals(255, WidgetLook(transparencyPct = 0).alpha)
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
