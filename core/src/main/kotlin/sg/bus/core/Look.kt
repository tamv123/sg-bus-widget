package sg.bus.core

/**
 * Widget background colour + transparency, chosen in Setup and applied to every widget.
 * Text colours follow the background: dark text on light colours, light text on dark ones.
 */
data class WidgetLook(
    val rgb: Int = DEFAULT_RGB,
    val transparencyPct: Int = DEFAULT_TRANSPARENCY,
    /** widget text size, % of normal; Android's own font-size setting applies on top */
    val textPct: Int = 100,
) {
    val textScale: Float get() = textPct.coerceIn(MIN_TEXT_PCT, MAX_TEXT_PCT) / 100f
    /** 0–255 for ImageView.setImageAlpha; 0 % transparency = fully solid. */
    val alpha: Int get() = ((100 - transparencyPct.coerceIn(0, 100)) * 255 + 50) / 100
    val dark: Boolean get() = luminance(rgb) < 0.5
    val text: TextColors get() = if (dark) LIGHT_TEXT else DARK_TEXT

    companion object {
        const val WHITE = 0xFFFFFF
        /** Victor 2026-11-05: Sky, 30 % transparent */
        const val DEFAULT_RGB = 0xE3F2FD
        const val DEFAULT_TRANSPARENCY = 30
        const val MIN_TEXT_PCT = 70
        const val MAX_TEXT_PCT = 130
        /** Swatches offered in Setup: name to RGB. White first (the default). */
        val PALETTE = listOf(
            "White" to 0xFFFFFF, "Mist" to 0xECEFF1, "Sand" to 0xFFF3E0, "Mint" to 0xE8F5E9,
            "Sky" to 0xE3F2FD, "Green" to 0x2E7D32, "Navy" to 0x1A237E, "Charcoal" to 0x263238, "Black" to 0x000000,
        )
    }
}

/** Every colour the widget draws text in. ARGB ints, ready for RemoteViews.setTextColor. */
data class TextColors(
    val main: Int, val secondary: Int, val grey: Int, val busNo: Int, val live: Int, val amber: Int, val red: Int,
)

val DARK_TEXT = TextColors(
    main = 0xFF212121.toInt(), secondary = 0xFF424242.toInt(), grey = 0xFF9E9E9E.toInt(),
    busNo = 0xFF1B5E20.toInt(), live = 0xFF2E7D32.toInt(), amber = 0xFFE65100.toInt(), red = 0xFFC62828.toInt(),
)
val LIGHT_TEXT = TextColors(
    main = 0xFFFFFFFF.toInt(), secondary = 0xFFE0E0E0.toInt(), grey = 0xFFB0BEC5.toInt(),
    busNo = 0xFFA5D6A7.toInt(), live = 0xFF81C784.toInt(), amber = 0xFFFFB74D.toInt(), red = 0xFFEF9A9A.toInt(),
)

/** Relative luminance (WCAG), 0 = black, 1 = white. */
fun luminance(rgb: Int): Double {
    fun ch(shift: Int): Double {
        val c = ((rgb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
}

/** How many bus rows fit, and whether the status line ("Tap for live times") fits under them. */
data class Fit(val rows: Int, val status: Boolean)

/** Height in dp of one line of [sp] text: Roboto is ~1.33 × its size with Android's font padding, ~1.17 × without. */
fun lineDp(sp: Float, fontScale: Float, fontPadding: Boolean = true) = sp * fontScale * (if (fontPadding) 1.33f else 1.17f)

/** widget.xml: the stop name and bus rows have includeFontPadding=false and a 3dp gap per row (~32 % tighter than
 *  font padding + 2dp); the status line keeps its padding so ● / ⚠ aren't clipped. */
const val ROW_GAP_DP = 3f

/**
 * Lay out for the widget's real height. [fontScale] = Android's font-size setting × the user's text size.
 * Prefers two bus rows with the status line, then two rows without it, then one row.
 * [extraDp] is anything else shown (the second stop on big widgets).
 */
fun fitRows(heightDp: Float, fontScale: Float, maxRows: Int, extraDp: Float = 0f): Fit {
    val padding = 14f                                   // widget.xml: 8dp top + 6dp bottom
    val avail = heightDp - padding - lineDp(13f, fontScale, fontPadding = false) - extraDp
    val row = lineDp(15f, fontScale, fontPadding = false) + ROW_GAP_DP
    val status = lineDp(11f, fontScale)
    val withStatus = ((avail - status) / row).toInt()
    val without = (avail / row).toInt()
    return when {
        withStatus >= 2 -> Fit(withStatus.coerceAtMost(maxRows), true)
        without >= 2 -> Fit(2, false)
        withStatus >= 1 -> Fit(1, true)
        else -> Fit(1, false)
    }
}

/** Height the second-nearest stop takes on big widgets: its name (12sp + 6dp gap) and up to 2 lines of times (13sp). */
fun secondStopDp(fontScale: Float) = lineDp(12f, fontScale) + 6f + 2 * lineDp(13f, fontScale)
