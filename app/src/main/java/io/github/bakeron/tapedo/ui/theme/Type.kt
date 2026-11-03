package io.github.bakeron.tapedo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.bakeron.tapedo.R

// Bricolage Grotesque is a variable font; each weight is one instance of the same file.
@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: FontWeight) = Font(
    resId = R.font.bricolage,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Labels, headings, buttons - a grotesque with a bit of hand-cut character. */
val Bricolage = FontFamily(
    bricolage(FontWeight.Normal),
    bricolage(FontWeight.Medium),
    bricolage(FontWeight.SemiBold),
    bricolage(FontWeight.Bold),
)

/** Typewriter face for transcribed speech and card bodies. */
val Typewriter = FontFamily(
    Font(R.font.courier_prime, FontWeight.Normal),
    Font(R.font.courier_prime_bold, FontWeight.Bold),
)

private val base = Typography()

val TapedoTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Bricolage, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = Bricolage, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Bricolage, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontFamily = Bricolage, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Typewriter, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Typewriter, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Typewriter, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    labelMedium = base.labelMedium.copy(fontFamily = Bricolage, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    // 12 sp, not Material's 11: this style carries the card's type and date, not decoration.
    labelSmall = base.labelSmall.copy(fontFamily = Bricolage, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.sp),
)
