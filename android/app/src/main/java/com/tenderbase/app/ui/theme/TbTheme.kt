@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tenderbase.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tenderbase.app.DeadlineTier

// ----------------------------------------------------------------------------
// TenderBase brand palette. Blue carries actions/navigation/active states;
// semantic colours carry deadline urgency. Dark mode is its own deep-navy
// neutral palette — not an inversion.
// ----------------------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A56C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF082B5E),
    secondary = Color(0xFF12304F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E2F2),
    onSecondaryContainer = Color(0xFF0C2340),
    tertiary = Color(0xFF1B5E20),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFE9EEF5),
    onBackground = Color(0xFF0F1520),
    surface = Color(0xFFF9FBFE),
    onSurface = Color(0xFF0F1520),
    surfaceVariant = Color(0xFFE2E8F1),
    onSurfaceVariant = Color(0xFF37414F),
    surfaceContainer = Color(0xFFF2F5FA),
    surfaceContainerHigh = Color(0xFFEAEFF6),
    outline = Color(0xFF5D6A7A),
    outlineVariant = Color(0xFFD5DDE8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF05070A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC3FF),
    onPrimary = Color(0xFF06234F),
    primaryContainer = Color(0xFF133764),
    onPrimaryContainer = Color(0xFFCCE0FF),
    secondary = Color(0xFFB7C7DC),
    onSecondary = Color(0xFF0B1A2C),
    secondaryContainer = Color(0xFF1E2D40),
    onSecondaryContainer = Color(0xFFD6E2F2),
    tertiary = Color(0xFF8BE3B4),
    onTertiary = Color(0xFF06281A),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE9EEF5),
    surface = Color(0xFF151B24),
    onSurface = Color(0xFFE9EEF5),
    surfaceVariant = Color(0xFF1E2733),
    onSurfaceVariant = Color(0xFFAAB6C5),
    surfaceContainer = Color(0xFF1A222D),
    surfaceContainerHigh = Color(0xFF222C39),
    outline = Color(0xFF8494A8),
    outlineVariant = Color(0xFF2C3948),
    error = Color(0xFFFFB4AC),
    onError = Color(0xFF410E0B),
    errorContainer = Color(0xFF5C1F1A),
    onErrorContainer = Color(0xFFF9DEDC),
    scrim = Color(0xFF000000),
)

/**
 * Deadline urgency tokens: text + icon + colour, never colour alone.
 * Contrast is tuned to be comfortably AA on the paired containers in both
 * themes (the UI always renders the label text on top of [bg]).
 */
data class UrgencyStyle(
    val bg: Color,
    val fg: Color,
    /** Accent bar/line colour used by deadline cards. */
    val accent: Color,
)

data class TbUrgency(
    val safe: UrgencyStyle,
    val upcoming: UrgencyStyle,
    val closingSoon: UrgencyStyle,
    val urgent: UrgencyStyle,
    val closed: UrgencyStyle,
    val none: UrgencyStyle,
) {
    fun styleOf(tier: DeadlineTier): UrgencyStyle = when (tier) {
        DeadlineTier.SAFE -> safe
        DeadlineTier.UPCOMING -> upcoming
        DeadlineTier.CLOSING_SOON -> closingSoon
        DeadlineTier.URGENT -> urgent
        DeadlineTier.CLOSED -> closed
        DeadlineTier.NONE -> none
    }
}

private val LightUrgency = TbUrgency(
    safe = UrgencyStyle(Color(0xFFDFF1E3), Color(0xFF14562F), Color(0xFF1F7A45)),
    upcoming = UrgencyStyle(Color(0xFFFCEBC8), Color(0xFF6F4400), Color(0xFFB45309)),
    closingSoon = UrgencyStyle(Color(0xFFFFE1CE), Color(0xFF8A3B00), Color(0xFFC2410C)),
    urgent = UrgencyStyle(Color(0xFFFBDCD8), Color(0xFF8F1D14), Color(0xFFD93B3B)),
    closed = UrgencyStyle(Color(0xFFE1E7EF), Color(0xFF475364), Color(0xFF8A97A6)),
    none = UrgencyStyle(Color(0xFFE9EEF5), Color(0xFF516072), Color(0xFF8A97A6)),
)

private val DarkUrgency = TbUrgency(
    safe = UrgencyStyle(Color(0xFF123123), Color(0xFF92E5B4), Color(0xFF3EBA74)),
    upcoming = UrgencyStyle(Color(0xFF392B10), Color(0xFFFFD08A), Color(0xFFE0A147)),
    closingSoon = UrgencyStyle(Color(0xFF3E2614), Color(0xFFFFB68C), Color(0xFFFF9A5C)),
    urgent = UrgencyStyle(Color(0xFF451A16), Color(0xFFFFB4AC), Color(0xFFFF6B5E)),
    closed = UrgencyStyle(Color(0xFF232C37), Color(0xFF9AA6B4), Color(0xFF5A6776)),
    none = UrgencyStyle(Color(0xFF1A222D), Color(0xFF8B99AA), Color(0xFF5A6776)),
)

val LocalTbUrgency: ProvidableCompositionLocal<TbUrgency> =
    staticCompositionLocalOf { LightUrgency }

// ----------------------------------------------------------------------------
// Spacing / corner radius / icon sizes — the 8dp system (4dp half-steps for
// dense meta only).
// ----------------------------------------------------------------------------

object TbDimens {
    val spaceNone = 0.dp
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 20.dp
    val spaceXxl = 24.dp
    val spaceXxxl = 32.dp

    val screenHMargin = 16.dp

    val iconSm = 18.dp
    val iconMd = 22.dp
    val iconLg = 26.dp

    val touchMin = 48.dp
    val cardCorner = 18.dp
    val cardPaddingH = 16.dp
    val cardPaddingV = 14.dp
}

private val TbShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/**
 * Type scale (1.2 design system). Sized for information density on small
 * phones; all styles respect dynamic font scaling.
 */
private val TbTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)

/**
 * The one app theme. `@Preview`-free by design — screens are verified in
 * light + dark on real API levels via instrumentation-free layout review.
 */
@Composable
fun TenderBaseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val urgency = if (darkTheme) DarkUrgency else LightUrgency
    androidx.compose.runtime.CompositionLocalProvider(LocalTbUrgency provides urgency) {
        MaterialTheme(
            colorScheme = colors,
            typography = TbTypography,
            shapes = TbShapes,
            content = content,
        )
    }
}
