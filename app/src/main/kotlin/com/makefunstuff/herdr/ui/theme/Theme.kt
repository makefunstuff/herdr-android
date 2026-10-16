package com.makefunstuff.herdr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardTone

/*
 * The app's look, as docs/DESIGN.md describes it: herdr-android's own green-tinted neutrals,
 * soft surfaces instead of outlines, herdr's mint as the primary and only accent, and the board's
 * status colours for state. Screens read these through MaterialTheme and [Herdr]; nothing else
 * defines a colour.
 */

/** The tokens Material's scheme has no slot for. */
@Immutable
class HerdrColors(
    /** The screen's ground. */
    val ground: Color,
    /** Cards, grouped lists, sheets and dialogs. */
    val card: Color,
    /** Fields, chips, secondary buttons and icon tiles: one step up from a card. */
    val field: Color,
    /** A field being typed in, a pressed tile, a sheet's handle. */
    val fieldFocused: Color,
    /** Hairlines between rows of one group. Rare. */
    val line: Color,
    val text: Color,
    /** Secondary text and icons. */
    val muted: Color,
    /** Tertiary text (timestamps, counts); still 4.5:1 on a card. */
    val faint: Color,
    /** Something switched off or out of reach (not text). */
    val off: Color,
    /** herdr's mint: links, selection marks, progress, the cursor. Never state. */
    val accent: Color,
    val onAccent: Color,
    val red: Color,
    val amber: Color,
    val green: Color,
    val blue: Color,
    val teal: Color,
    val pink: Color,
    val grey: Color,
    val dark: Boolean,
) {
    /** A status colour's quiet background: the colour at low alpha over a card. */
    fun tint(c: Color): Color = c.copy(alpha = if (dark) 0.16f else 0.12f)
}

internal val DarkHerdr = HerdrColors(
    ground = Color(0xFF0F1412), card = Color(0xFF171D1A), field = Color(0xFF222926), fieldFocused = Color(0xFF2B3330),
    line = Color(0xFF232A27), text = Color(0xFFE4EAE6), muted = Color(0xFFA7B1AC), faint = Color(0xFF8D9792), off = Color(0xFF56605B),
    accent = Color(0xFF7DD3B6), onAccent = Color(0xFF00382B),
    red = Color(0xFFF0857A), amber = Color(0xFFE2B64A), green = Color(0xFF6CC993), blue = Color(0xFF7AA2F7),
    teal = Color(0xFF4DC4B8), pink = Color(0xFFE58FC0), grey = Color(0xFF8E9692), dark = true,
)

internal val LightHerdr = HerdrColors(
    ground = Color(0xFFF4F7F5), card = Color(0xFFFFFFFF), field = Color(0xFFE7EDE9), fieldFocused = Color(0xFFDCE4DF),
    line = Color(0xFFE5EBE7), text = Color(0xFF141A17), muted = Color(0xFF55605A), faint = Color(0xFF5F6964), off = Color(0xFFB2BBB6),
    accent = Color(0xFF1E6B58), onAccent = Color.White,
    red = Color(0xFFB3342A), amber = Color(0xFF8A5800), green = Color(0xFF22744A), blue = Color(0xFF3260C2),
    teal = Color(0xFF0B7268), pink = Color(0xFFA0407A), grey = Color(0xFF5C6461), dark = false,
)

val LocalHerdrColors = staticCompositionLocalOf { DarkHerdr }

/** The theme's extra tokens: `Herdr.colors.card`, `Herdr.colors.red`… */
object Herdr {
    val colors: HerdrColors
        @Composable @ReadOnlyComposable get() = LocalHerdrColors.current
}

/** The 4/8 dp grid (docs/DESIGN.md). Screens space with these, not with their own numbers. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** A screen's side margin. */
    val screen = 16.dp
    /** Inside a card. */
    val card = 16.dp
}

/** Four corners: [s] for badges and code, [m] for fields and tiles, [l] for cards, [xl] for sheets and dialogs. Buttons and chips are pills. */
object Radius {
    val s = 8.dp
    val m = 12.dp
    val l = 20.dp
    val xl = 28.dp
}

internal val Dark = darkColorScheme(
    primary = DarkHerdr.accent, onPrimary = DarkHerdr.onAccent,
    primaryContainer = Color(0xFF173F33), onPrimaryContainer = Color(0xFFBDEFDC),
    inversePrimary = Color(0xFF1E6B58),
    secondary = Color(0xFFB2CCC2), onSecondary = Color(0xFF1D352D),
    secondaryContainer = DarkHerdr.field, onSecondaryContainer = DarkHerdr.text,
    tertiary = DarkHerdr.accent, onTertiary = DarkHerdr.onAccent,
    tertiaryContainer = Color(0xFF173F33), onTertiaryContainer = Color(0xFFBDEFDC),
    background = DarkHerdr.ground, onBackground = DarkHerdr.text,
    surface = DarkHerdr.ground, onSurface = DarkHerdr.text,
    surfaceVariant = DarkHerdr.field, onSurfaceVariant = DarkHerdr.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = DarkHerdr.text, inverseOnSurface = Color(0xFF141A17),
    error = DarkHerdr.red, onError = Color(0xFF3A0A06),
    errorContainer = Color(0xFF3A1A17), onErrorContainer = Color(0xFFFFDAD5),
    outline = Color(0xFF4A5450), outlineVariant = Color(0xFF2B3330),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B3330), surfaceDim = DarkHerdr.ground,
    surfaceContainerLowest = Color(0xFF0B0F0D), surfaceContainerLow = Color(0xFF131815),
    surfaceContainer = DarkHerdr.card, surfaceContainerHigh = Color(0xFF1C2220),
    surfaceContainerHighest = DarkHerdr.field,
)

internal val Light = lightColorScheme(
    primary = LightHerdr.accent, onPrimary = LightHerdr.onAccent,
    primaryContainer = Color(0xFFCDEDE0), onPrimaryContainer = Color(0xFF0B3328),
    inversePrimary = Color(0xFF7DD3B6),
    secondary = Color(0xFF4B635A), onSecondary = Color.White,
    secondaryContainer = LightHerdr.field, onSecondaryContainer = LightHerdr.text,
    tertiary = LightHerdr.accent, onTertiary = LightHerdr.onAccent,
    tertiaryContainer = Color(0xFFCDEDE0), onTertiaryContainer = Color(0xFF0B3328),
    background = LightHerdr.ground, onBackground = LightHerdr.text,
    surface = LightHerdr.ground, onSurface = LightHerdr.text,
    surfaceVariant = LightHerdr.field, onSurfaceVariant = LightHerdr.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF26302B), inverseOnSurface = Color(0xFFEFF4F1),
    error = LightHerdr.red, onError = Color.White,
    errorContainer = Color(0xFFFBE4E1), onErrorContainer = Color(0xFF5C0F0A),
    outline = Color(0xFFB9C4BE), outlineVariant = Color(0xFFDCE4DF),
    scrim = Color(0xFF000000),
    surfaceBright = LightHerdr.card, surfaceDim = Color(0xFFDCE4DF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9FBFA),
    surfaceContainer = LightHerdr.card, surfaceContainerHigh = Color(0xFFEEF3F0),
    surfaceContainerHighest = LightHerdr.field,
)

/**
 * Six sizes (24, 20, 16, 14, 13, 12) and three weights (regular, medium, semibold).
 * headline: a top-level screen's title. title: bars, sheets, dialogs and row titles. body: text.
 * label: buttons, chips, section headings and meta.
 */
internal val HerdrTypography = Typography(
    headlineLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

internal val HerdrShapesM3 = Shapes(
    extraSmall = RoundedCornerShape(Radius.s), small = RoundedCornerShape(Radius.m), medium = RoundedCornerShape(Radius.m),
    large = RoundedCornerShape(Radius.l), extraLarge = RoundedCornerShape(Radius.xl),
)

@Composable
fun HerdrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHerdrColors provides if (dark) DarkHerdr else LightHerdr) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            shapes = HerdrShapesM3,
            typography = HerdrTypography,
            content = content,
        )
    }
}

/** A board tone in this theme (readable as text on the ground and on cards). */
fun HerdrColors.tone(t: BoardTone): Color = when (t) {
    BoardTone.Amber -> amber
    BoardTone.Red -> red
    BoardTone.Green -> green
    BoardTone.Blue -> blue
    BoardTone.Teal -> teal
    BoardTone.Pink -> pink
    BoardTone.Grey -> grey
}

/** herdr's sidebar colours for agent states, the same tones the board uses. */
fun HerdrColors.status(s: AgentStatus): Color = when (s) {
    AgentStatus.Blocked -> red
    AgentStatus.Working -> amber
    AgentStatus.Done -> blue
    AgentStatus.Idle -> green
    AgentStatus.Unknown -> grey
}

@Composable
@ReadOnlyComposable
fun AgentStatus.color(): Color = LocalHerdrColors.current.status(this)

fun AgentStatus.title(): String = when (this) {
    AgentStatus.Blocked -> "Needs you"
    AgentStatus.Working -> "Working"
    AgentStatus.Done -> "Done"
    AgentStatus.Idle -> "Idle"
    AgentStatus.Unknown -> "Other"
}
