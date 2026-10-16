package com.makefunstuff.herdr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.makefunstuff.herdr.core.api.AgentStatus

internal val Dark = darkColorScheme(
    primary = Color(0xFF7DD3B6),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF0F5141),
    onPrimaryContainer = Color(0xFF9BF0D1),
    secondary = Color(0xFFB2CCC2),
    onSecondary = Color(0xFF1D352D),
    secondaryContainer = Color(0xFF344C43),
    onSecondaryContainer = Color(0xFFCEE9DD),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE3E8E4),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE3E8E4),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF181D1B),
    surfaceContainer = Color(0xFF1A201D),
    surfaceContainerHigh = Color(0xFF232A26),
    surfaceContainerHighest = Color(0xFF2E3531),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3A4440),
    error = Color(0xFFFFB4AB),
)

internal val Light = lightColorScheme(
    primary = Color(0xFF1E6B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2D4),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4B635A),
    secondaryContainer = Color(0xFFCDE9DD),
    background = Color(0xFFF5FBF7),
    surface = Color(0xFFF5FBF7),
    surfaceContainerLow = Color(0xFFEFF5F1),
    surfaceContainer = Color(0xFFE9EFEB),
    surfaceContainerHigh = Color(0xFFE3EAE6),
    surfaceContainerHighest = Color(0xFFDEE4E0),
    outlineVariant = Color(0xFFBFC9C3),
)

@Composable
fun HerdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        typography = Typography().let {
            it.copy(
                titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                labelSmall = it.labelSmall.copy(fontSize = 12.sp),
            )
        },
        content = content,
    )
}

/** herdr's own sidebar colours for agent states. */
fun AgentStatus.color(): Color = when (this) {
    AgentStatus.Blocked -> Color(0xFFEF7B70)
    AgentStatus.Working -> Color(0xFFE2B64A)
    AgentStatus.Done -> Color(0xFF79A8F2)
    AgentStatus.Idle -> Color(0xFF6CC993)
    AgentStatus.Unknown -> Color(0xFF7A847F)
}

fun AgentStatus.title(): String = when (this) {
    AgentStatus.Blocked -> "Needs you"
    AgentStatus.Working -> "Working"
    AgentStatus.Done -> "Done"
    AgentStatus.Idle -> "Idle"
    AgentStatus.Unknown -> "Other"
}
