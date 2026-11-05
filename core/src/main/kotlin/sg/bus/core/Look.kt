package sg.bus.core

/**
 * Widget background colour + transparency, chosen in Setup and applied to every widget.
 * Text colours follow the background: dark text on light colours, light text on dark ones.
 */
data class WidgetLook(val rgb: Int = WHITE, val transparencyPct: Int = 0) {
    /** 0–255 for ImageView.setImageAlpha; 0 % transparency = fully solid. */
    val alpha: Int get() = ((100 - transparencyPct.coerceIn(0, 100)) * 255 + 50) / 100
    val dark: Boolean get() = luminance(rgb) < 0.5
    val text: TextColors get() = if (dark) LIGHT_TEXT else DARK_TEXT

    companion object {
        const val WHITE = 0xFFFFFF
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
