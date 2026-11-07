package com.rawwave.designsystem

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface

/** Semantic colors for the lightweight RawWave design system. */
data class RwColors(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val onBackground: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val primaryGradientStart: Int,
    val primaryGradientEnd: Int,
    val secondaryGradientStart: Int,
    val secondaryGradientEnd: Int,
    val onSecondaryGradient: Int,
    val titleGradientStart: Int,
    val titleGradientEnd: Int,
    val outline: Int,
    val outlineVariant: Int,
    val error: Int,
    val success: Int,
    val warning: Int,
    val ripple: Int,
)

object RwTheme {

    /** The watch deliberately keeps the dark health layout even if the system is in light mode. */
    @Volatile
    var forceDark: Boolean = false

    fun isDark(context: Context): Boolean = forceDark ||
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun colors(context: Context): RwColors = if (isDark(context)) dark() else light()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** The whole product uses a heavy sans face; there is no light-weight body copy. */
    fun boldTypeface(): Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** Large numbers and screen titles. */
    fun displayTypeface(): Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    /** Kept for call sites written before the bold theme; now returns the bold face. */
    fun mediumTypeface(): Typeface = boldTypeface()

    private fun light(): RwColors = RwColors(
        background = Color.rgb(243, 248, 245),
        surface = Color.rgb(255, 255, 255),
        surfaceVariant = Color.rgb(230, 240, 233),
        onBackground = Color.rgb(14, 25, 18),
        onSurface = Color.rgb(14, 25, 18),
        onSurfaceVariant = Color.rgb(78, 96, 85),
        primary = Color.rgb(12, 122, 72),
        onPrimary = Color.WHITE,
        primaryContainer = Color.rgb(205, 239, 217),
        onPrimaryContainer = Color.rgb(6, 58, 34),
        primaryGradientStart = Color.rgb(14, 138, 82),
        primaryGradientEnd = Color.rgb(7, 95, 56),
        secondaryGradientStart = Color.rgb(62, 119, 90),
        secondaryGradientEnd = Color.rgb(39, 86, 64),
        onSecondaryGradient = Color.WHITE,
        titleGradientStart = Color.rgb(11, 107, 64),
        titleGradientEnd = Color.rgb(31, 169, 104),
        outline = Color.rgb(124, 148, 133),
        outlineVariant = Color.rgb(210, 226, 215),
        error = Color.rgb(179, 38, 30),
        success = Color.rgb(27, 122, 67),
        warning = Color.rgb(138, 90, 0),
        ripple = Color.argb(51, 12, 122, 72),
    )

    private fun dark(): RwColors = RwColors(
        background = Color.rgb(6, 16, 9),
        surface = Color.rgb(13, 26, 18),
        surfaceVariant = Color.rgb(20, 39, 27),
        onBackground = Color.rgb(230, 241, 233),
        onSurface = Color.rgb(230, 241, 233),
        onSurfaceVariant = Color.rgb(175, 196, 182),
        primary = Color.rgb(87, 214, 149),
        onPrimary = Color.rgb(4, 32, 19),
        primaryContainer = Color.rgb(16, 60, 36),
        onPrimaryContainer = Color.rgb(201, 245, 218),
        primaryGradientStart = Color.rgb(22, 131, 79),
        primaryGradientEnd = Color.rgb(7, 86, 52),
        secondaryGradientStart = Color.rgb(36, 80, 58),
        secondaryGradientEnd = Color.rgb(21, 52, 37),
        onSecondaryGradient = Color.rgb(241, 248, 243),
        titleGradientStart = Color.rgb(164, 244, 196),
        titleGradientEnd = Color.rgb(69, 212, 140),
        outline = Color.rgb(125, 154, 135),
        outlineVariant = Color.rgb(34, 56, 42),
        error = Color.rgb(255, 180, 171),
        success = Color.rgb(123, 224, 165),
        warning = Color.rgb(255, 196, 107),
        ripple = Color.argb(64, 255, 255, 255),
    )
}

object RwTokens {
    const val SPACE_1 = 4
    const val SPACE_2 = 8
    const val SPACE_3 = 12
    const val SPACE_4 = 16
    const val SPACE_5 = 24
    const val SPACE_6 = 32
    const val SCREEN_PADDING = 20
    const val CARD_RADIUS = 22
    const val BUTTON_RADIUS = 24
    const val BUTTON_HEIGHT = 52
    const val TOUCH_MIN = 48
    const val WATCH_SAFE_FRACTION = 0.14f
}
