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
    val controlEdge = Color(0xFF8A8275) // edges of buttons, tracks, strips - 3.5:1 on the card
    val track = Color(0xFFDDD1BA)      // recessed switch track - a shade deeper than the card edge
    val display = Color(0xFFE6DCC9)    // recessed counter window
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
    val controlEdge = Color(0xFF857D6E) // 3.7:1 on the card
    val track = Color(0xFF181715)      // the track sits deeper than the card...
    val knob = Color(0xFF4A4640)       // ...and the knob catches light - on dark gear relief shows by light, not shadow
    val knobLight = Color(0xFF5E5850)
    val display = Color(0xFF121110)    // unlit display glass behind amber digits
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
    /** Edges of controls (buttons, switch tracks, card strips) - at least 3:1 against the card. */
    val controlEdge: Color,
    val track: Color,
    val knob: Color,
    /** 1 px light along the knob's top edge. */
    val knobLight: Color,
    /** An unlit LED socket on a switch track - visible on its own (3:1), not only next to the lit one. */
    val ledOff: Color,
    /** Recessed counter window and the ink of its digits (amber in Panel, like an old hi-fi display). */
    val display: Color,
    val displayInk: Color,
    /** Seismograph pen and paper - in Panel a dark oscilloscope screen with an amber trace. */
    val tracePaper: Color,
    val traceGrid: Color,
    val traceInk: Color,
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
    controlEdge = Paper.controlEdge,
    track = Paper.track,
    knob = Paper.card,
    knobLight = Color.White,
    ledOff = Paper.controlEdge,
    display = Paper.display,
    displayInk = Paper.graphite,
    tracePaper = Paper.card,
    traceGrid = Paper.rule,
    traceInk = Paper.graphite,
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
    controlEdge = Panel.controlEdge,
    track = Panel.track,
    knob = Panel.knob,
    knobLight = Panel.knobLight,
    ledOff = Panel.rule,
    display = Panel.display,
    displayInk = Panel.amber,
    tracePaper = Panel.display,
    traceGrid = Color(0xFF3A2F1F),
    traceInk = Panel.amber,
)

val LocalTapedoColors = staticCompositionLocalOf { LightTapedoColors }
