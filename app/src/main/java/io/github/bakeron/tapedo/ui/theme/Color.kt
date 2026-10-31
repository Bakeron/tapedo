package io.github.bakeron.tapedo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light theme: a pocket recorder lying on warm paper - graphite ink, red record dot.
internal object Paper {
    val desk = Color(0xFFF3ECDF)       // screen background, warm paper
    val card = Color(0xFFFBF7EF)       // index card
    val cardEdge = Color(0xFFE2D7C3)
    val rule = Color(0xFFD9CDB8)       // ruled / grid lines on cards
    val graphite = Color(0xFF2B2A28)   // main ink
    val pencil = Color(0xFF5E594F)     // secondary ink
    val record = Color(0xFFB8352A)     // record dot, destructive actions
    val stamp = Color(0xFF2F5D8A)      // "AI" stamp ink
}

// Dark theme: a graphite hi-fi panel with an amber display backlight.
internal object Panel {
    val desk = Color(0xFF1C1B19)
    val card = Color(0xFF292724)
    val cardEdge = Color(0xFF3A3733)
    val rule = Color(0xFF45413B)
    val amber = Color(0xFFF2A93B)      // display backlight, main accent
    val ink = Color(0xFFEDE4D3)
    val dimInk = Color(0xFFB0A795)
    val record = Color(0xFFEE6A55)      // 4.8:1 on the card - readable as text, not only as a dot
    val stamp = Color(0xFF8DB4DB)
}

/** Colours Material 3 has no slot for: paper details, record dot, stamp, priority tapes. */
@Immutable
data class TapedoColors(
    val card: Color,
    val cardEdge: Color,
    val rule: Color,
    val record: Color,
    val stamp: Color,
    val tapeHigh: Color,
    val tapeMedium: Color,
    val tapeLow: Color,
    val tapeInk: Color,
)

internal val LightTapedoColors = TapedoColors(
    card = Paper.card,
    cardEdge = Paper.cardEdge,
    rule = Paper.rule,
    record = Paper.record,
    stamp = Paper.stamp,
    tapeHigh = Color(0xFFE0614F),
    tapeMedium = Color(0xFFE8B04A),
    tapeLow = Color(0xFF9DBFA4),
    tapeInk = Paper.graphite,
)

internal val DarkTapedoColors = TapedoColors(
    card = Panel.card,
    cardEdge = Panel.cardEdge,
    rule = Panel.rule,
    record = Panel.record,
    stamp = Panel.stamp,
    tapeHigh = Color(0xFFD9604E),
    tapeMedium = Color(0xFFE0A440),
    tapeLow = Color(0xFF86A98D),
    tapeInk = Color(0xFF1C1B19),
)

val LocalTapedoColors = staticCompositionLocalOf { LightTapedoColors }
