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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardTone
import com.makefunstuff.herdr.core.terminal.TerminalPalette

/*
 * The app's look, as docs/DESIGN.md describes it: the herdr web UI's design tokens (its
 * src/styles.css `:root` and `[data-theme="light"]` blocks, amber palette; MIT, by devswha),
 * carried over value for value. "A warm terminal": amber-phosphor accent on lamp-lit graphite
 * (dark) or ledger paper (light). Amber is the one chrome colour (focus, the chosen lens, the
 * cursor, the user's own primary action); the agent states carry the other saturated colours and
 * none of them is amber. Screens read these through MaterialTheme and [Herdr]; nothing else
 * defines a colour.
 */

/** The tokens, named as herdr-android's screens use them; each one's web UI token is beside it. */
@Immutable
class HerdrColors(
    /** `--bg`: the screen's ground, the chat canvas. */
    val ground: Color,
    /** `--bg-panel`: the bars, the nav bar, sheets, dialogs and the terminal. */
    val panel: Color,
    /** `--bg-elevated`: cards (with a [line] hairline), chips, secondary buttons, tracks, your chat turns. */
    val card: Color,
    /** `--bg-hover`: the one neutral fill of a pressed or chosen row, chip or segment, and icon tiles. */
    val hover: Color,
    /** `--bg-input`: text fields. */
    val input: Color,
    /** `--border`: hairlines (cards, fields, bars, buttons). */
    val line: Color,
    /** `--border-strong`: a hairline that has to show over a fill, the prompt card's edge. */
    val lineStrong: Color,
    /** `--text`: body and row titles. */
    val text: Color,
    /** `--text-strong`: screen, sheet and dialog titles, a chosen segment, emphasis. */
    val strong: Color,
    /** `--text-dim`: secondary lines, icons, section heads, meta. */
    val muted: Color,
    /** Disabled controls and marks: the web UI draws them at 55% opacity; this is that, opaque. Not for text that must be read. */
    val off: Color,
    /** `--accent`: amber that reads as text (links, the chosen lens's glyph, focus, the cursor). Never a state. */
    val accent: Color,
    /** `--primary`: the fill of your own action (Send, primary buttons, the FAB). */
    val primary: Color,
    /** `--primary-text`: ink on [primary]. */
    val onPrimary: Color,
    /** `--status-blocked`: needs you, blocked, failed, destructive. */
    val red: Color,
    /** `--status-working`: an agent or a task at work. */
    val working: Color,
    /** `--status-done`: finished, connected, passed, ready to approve. */
    val done: Color,
    /** `--status-idle`: idle, to do, stopped, unknown. */
    val idle: Color,
    /** `--notice`: a warning that something is partial or stale (offline, outdated, minor). */
    val notice: Color,
    /** `--danger-text`: text on the danger tint (the destructive button). */
    val dangerText: Color,
    /** `--scrim`, under sheets and dialogs. */
    val scrim: Color,
    /** `--bubble-border`: the edge of your chat turns (none in dark, where the fill alone parts them). */
    val bubbleBorder: Color,
    /** `--term-selection`. */
    val termSelection: Color,
    val dark: Boolean,
) {
    /** `--bg-elevated` under its old name: chips, tiles and secondary buttons. */
    val field: Color get() = card
    /** `--bg-hover` under its old name: a focused field, a pressed key, a sheet's handle. */
    val fieldFocused: Color get() = hover
    /** Tertiary text: the web UI has three text strengths, so meta is [muted] too. */
    val faint: Color get() = muted

    /** A state's quiet background (`--status-*-tint`): 14% in dark, 12% in light. */
    fun tint(c: Color): Color = c.copy(alpha = if (dark) 0.14f else 0.12f)

    /** `--accent-tint`: a chosen pick, a marked line. */
    val accentTint: Color get() = accent.copy(alpha = if (dark) 0.13f else 0.10f)

    /** `--shadow-card`'s colour: black at 35% on graphite, warm ink at a tenth on paper. */
    val shadow: Color get() = if (dark) Color.Black.copy(alpha = 0.35f) else Color(0xFF282016).copy(alpha = 0.10f)

    /** `--danger-tint`: the destructive button's fill. */
    val dangerTint: Color get() = red.copy(alpha = if (dark) 0.12f else 0.10f)
}

internal val DarkHerdr = HerdrColors(
    ground = Color(0xFF12100E), panel = Color(0xFF181613), card = Color(0xFF211E1A), hover = Color(0xFF2A2621),
    input = Color(0xFF1C1916), line = Color(0xFF2D2924), lineStrong = Color(0xFF3E3830),
    text = Color(0xFFD8D0C3), strong = Color(0xFFF2EBDF), muted = Color(0xFF9B9183), off = Color(0xFF615A51),
    accent = Color(0xFFF0A830), primary = Color(0xFFF0A830), onPrimary = Color(0xFF1B1407),
    red = Color(0xFFFF7B70), working = Color(0xFF6CB8D6), done = Color(0xFF93C36B), idle = Color(0xFF9B9183),
    notice = Color(0xFFE8C55C), dangerText = Color(0xFFFFD9D4),
    scrim = Color(0x8C080604), bubbleBorder = Color.Transparent, termSelection = Color(0xFF4A3D26),
    dark = true,
)

internal val LightHerdr = HerdrColors(
    ground = Color(0xFFEEEAE2), panel = Color(0xFFFAF8F3), card = Color(0xFFF2EEE6), hover = Color(0xFFE8E2D6),
    input = Color(0xFFFFFDF9), line = Color(0xFFDCD4C6), lineStrong = Color(0xFFC5BAA8),
    text = Color(0xFF2A251F), strong = Color(0xFF16120D), muted = Color(0xFF685E52), off = Color(0xFFADA496),
    accent = Color(0xFF8C5000), primary = Color(0xFFC57D12), onPrimary = Color(0xFF1B1407),
    red = Color(0xFFA82323), working = Color(0xFF155A72), done = Color(0xFF2F6317), idle = Color(0xFF685E52),
    notice = Color(0xFF7A5A00), dangerText = Color(0xFF8F1D1D),
    scrim = Color(0x59281F16), bubbleBorder = Color(0xFFDCD4C6), termSelection = Color(0xFFF0D9AE),
    dark = false,
)

val LocalHerdrColors = staticCompositionLocalOf { DarkHerdr }

/** The theme's extra tokens: `Herdr.colors.card`, `Herdr.colors.red`… */
object Herdr {
    val colors: HerdrColors
        @Composable @ReadOnlyComposable get() = LocalHerdrColors.current
}

/** The 4dp grid (`--space-1`…`--space-8`). Screens space with these, not with their own numbers. */
object Space {
    val xxs = 2.dp
    /** `--space-1` */
    val xs = 4.dp
    /** `--space-2` */
    val s = 8.dp
    /** `--space-3` */
    val m = 12.dp
    /** `--space-4` */
    val l = 16.dp
    /** `--space-5` */
    val xl = 20.dp
    /** `--space-6` */
    val xxl = 24.dp
    /** `--space-8` */
    val xxxl = 32.dp
    /** A screen's side margin (`--space-4`). */
    val screen = 16.dp
    /** Inside a card (`--space-4`). */
    val card = 16.dp
}

/** `--radius-*`: [s] chips and badges, [m] buttons, fields and rows, [l] cards and menus, [xl] sheets and dialogs, [xxl] the composer. */
object Radius {
    val s = 6.dp
    val m = 8.dp
    val l = 12.dp
    val xl = 16.dp
    val xxl = 24.dp
}

/** Pretendard (OFL), the web UI's interface face, Latin subset; other scripts fall back to the system's. */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    // The web UI's `strong` is semibold; bold text gets the same face rather than a synthesized one.
    Font(R.font.pretendard_semibold, FontWeight.Bold),
)

/** JetBrains Mono (OFL), the web UI's code face: code, paths, keys, the terminal. */
val Mono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

internal val Dark = darkColorScheme(
    primary = DarkHerdr.primary, onPrimary = DarkHerdr.onPrimary,
    primaryContainer = DarkHerdr.hover, onPrimaryContainer = DarkHerdr.strong,
    inversePrimary = LightHerdr.accent,
    secondary = DarkHerdr.muted, onSecondary = DarkHerdr.ground,
    secondaryContainer = DarkHerdr.card, onSecondaryContainer = DarkHerdr.text,
    tertiary = DarkHerdr.accent, onTertiary = DarkHerdr.onPrimary,
    tertiaryContainer = DarkHerdr.hover, onTertiaryContainer = DarkHerdr.strong,
    background = DarkHerdr.ground, onBackground = DarkHerdr.text,
    surface = DarkHerdr.ground, onSurface = DarkHerdr.text,
    surfaceVariant = DarkHerdr.card, onSurfaceVariant = DarkHerdr.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = DarkHerdr.strong, inverseOnSurface = DarkHerdr.ground,
    error = DarkHerdr.red, onError = DarkHerdr.ground,
    errorContainer = Color(0xFF3A1D19), onErrorContainer = DarkHerdr.dangerText,
    outline = DarkHerdr.lineStrong, outlineVariant = DarkHerdr.line,
    scrim = Color(0xFF080604),
    surfaceBright = DarkHerdr.hover, surfaceDim = DarkHerdr.ground,
    surfaceContainerLowest = DarkHerdr.ground, surfaceContainerLow = DarkHerdr.panel,
    surfaceContainer = DarkHerdr.panel, surfaceContainerHigh = DarkHerdr.card,
    surfaceContainerHighest = DarkHerdr.hover,
)

internal val Light = lightColorScheme(
    primary = LightHerdr.primary, onPrimary = LightHerdr.onPrimary,
    primaryContainer = LightHerdr.hover, onPrimaryContainer = LightHerdr.strong,
    inversePrimary = DarkHerdr.accent,
    secondary = LightHerdr.muted, onSecondary = LightHerdr.panel,
    secondaryContainer = LightHerdr.card, onSecondaryContainer = LightHerdr.text,
    tertiary = LightHerdr.accent, onTertiary = LightHerdr.panel,
    tertiaryContainer = LightHerdr.hover, onTertiaryContainer = LightHerdr.strong,
    background = LightHerdr.ground, onBackground = LightHerdr.text,
    surface = LightHerdr.ground, onSurface = LightHerdr.text,
    surfaceVariant = LightHerdr.card, onSurfaceVariant = LightHerdr.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = LightHerdr.strong, inverseOnSurface = LightHerdr.panel,
    error = LightHerdr.red, onError = LightHerdr.panel,
    errorContainer = Color(0xFFF3DCD6), onErrorContainer = LightHerdr.dangerText,
    outline = LightHerdr.lineStrong, outlineVariant = LightHerdr.line,
    scrim = Color(0xFF281F16),
    surfaceBright = LightHerdr.panel, surfaceDim = LightHerdr.hover,
    surfaceContainerLowest = LightHerdr.input, surfaceContainerLow = LightHerdr.panel,
    surfaceContainer = LightHerdr.panel, surfaceContainerHigh = LightHerdr.card,
    surfaceContainerHighest = LightHerdr.hover,
)

private fun style(size: Int, line: Double, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
    fontFamily = Pretendard, fontSize = size.sp, lineHeight = (size * line).sp, fontWeight = weight,
    letterSpacing = if (tracking == 0.0) TextStyle.Default.letterSpacing else tracking.em,
)

/**
 * The web UI's type scale (`--fs-*`, comfortable density) in sp, so it follows the phone's font
 * size: 22 display, 18 title, 16 large, 15 reading, 14 body, 13 small, 12 meta, 11 micro. Weights
 * 400 body, 500 controls, 600 labels and titles. Line heights 1.2 titles, 1.5 body, 1.65 prose.
 */
internal val HerdrTypography = Typography(
    // Top-level screen titles: the display size (the web UI's greeting), tight tracking.
    headlineLarge = style(22, 1.25, FontWeight.SemiBold, -0.02),
    headlineMedium = style(22, 1.25, FontWeight.SemiBold, -0.02),
    // Markdown h1, a task's title.
    headlineSmall = style(18, 1.3, FontWeight.SemiBold, -0.01),
    // `--fs-lg`: a detail bar's title, sheet and dialog titles.
    titleLarge = style(16, 1.35, FontWeight.SemiBold, -0.01),
    // Row and card titles: body size, medium.
    titleMedium = style(14, 1.5, FontWeight.Medium),
    // Section heads and captions: small, semibold, drawn dim.
    titleSmall = style(13, 1.4, FontWeight.SemiBold),
    // `--fs-chat` on `--lh-prose`: chat prose, your turns, menu rows, form values.
    bodyLarge = style(15, 1.6),
    // `--fs-md`: body and dialog copy, secondary lines.
    bodyMedium = style(14, 1.5),
    // `--fs-xs`: subtitles, hints, meta lines.
    bodySmall = style(12, 1.5),
    // `--fs-sm` medium: buttons, chips, segments.
    labelLarge = style(13, 1.4, FontWeight.Medium),
    // `--fs-xs` medium: counts, pills, nav labels.
    labelMedium = style(12, 1.35, FontWeight.Medium),
    // `--fs-2xs` semibold, caps tracking: badges and field labels (written in capitals).
    labelSmall = style(11, 1.3, FontWeight.SemiBold, 0.06),
)

/** Code and terminal text: JetBrains Mono at `--fs-sm` on `--lh-code`. */
val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 20.sp)

internal val HerdrShapesM3 = Shapes(
    extraSmall = RoundedCornerShape(Radius.s), small = RoundedCornerShape(Radius.m), medium = RoundedCornerShape(Radius.l),
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

/**
 * A board status's colour: the board's tones folded onto the web UI's four agent states, so a
 * task and an agent in the same state look the same. Working and reviewing are at work; ready to
 * approve and done are done; to do, queued and archived are idle; everything that needs you is red.
 * The written status always goes with it.
 */
fun HerdrColors.tone(t: BoardTone): Color = when (t) {
    BoardTone.Amber, BoardTone.Blue -> working
    BoardTone.Red -> red
    BoardTone.Green, BoardTone.Teal -> done
    BoardTone.Pink, BoardTone.Grey -> idle
}

/** The web UI's agent state colours. */
fun HerdrColors.status(s: AgentStatus): Color = when (s) {
    AgentStatus.Blocked -> red
    AgentStatus.Working -> working
    AgentStatus.Done -> done
    AgentStatus.Idle, AgentStatus.Unknown -> idle
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

/**
 * The terminal's colours: the web UI's `--term-*` tokens (panel ground, text, amber cursor) over
 * xterm.js's default ANSI colours, which the web UI keeps; any cell's text is held at 4.5:1 on its
 * background, as the web UI's xterm does (`minimumContrastRatio`).
 */
fun HerdrColors.terminalPalette(): TerminalPalette = TerminalPalette(
    foreground = text.toArgb(),
    background = panel.toArgb(),
    cursor = accent.toArgb(),
    ansi = XTERM_ANSI,
    minimumContrast = 4.5,
)

private val XTERM_ANSI = longArrayOf(
    0xFF2E3436, 0xFFCC0000, 0xFF4E9A06, 0xFFC4A000, 0xFF3465A4, 0xFF75507B, 0xFF06989A, 0xFFD3D7CF,
    0xFF555753, 0xFFEF2929, 0xFF8AE234, 0xFFFCE94F, 0xFF729FCF, 0xFFAD7FA8, 0xFF34E2E2, 0xFFEEEEEC,
).map { it.toInt() }.toIntArray()
