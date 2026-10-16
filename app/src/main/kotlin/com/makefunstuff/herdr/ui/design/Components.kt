package com.makefunstuff.herdr.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The app's own popups. Same call shapes as the stock Material ones (AlertDialog, ModalBottomSheet,
 * DropdownMenu), so screens use them without stock styling: scrim, rounded surface, 48dp targets.
 */
object HerdrShapes {
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Dialog = RoundedCornerShape(28.dp)
}

@Composable
private fun Scrim(onDismiss: () -> Unit, align: Alignment, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
        contentAlignment = align,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 6 },
            exit = fadeOut(tween(120)) + slideOutVertically { it / 6 },
        ) { content() }
    }
}

private val NoClick: Modifier
    @Composable get() = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

private val FullScreen = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

/** Centered dialog; only for confirmations and short forms. */
@Composable
fun HerdrDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = FullScreen) {
        Scrim(onDismissRequest, Alignment.Center) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = HerdrShapes.Dialog,
                tonalElevation = 6.dp,
                modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().imePadding().then(NoClick),
            ) {
                Column(Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                    if (title != null) Box(Modifier.padding(bottom = 12.dp).semantics { heading() }) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.titleLarge,
                        ) { title() }
                    }
                    if (text != null) Box(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                        ) { text() }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/** Bottom sheet with a drag handle; scrolls when long. */
@Composable
fun HerdrSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = FullScreen) {
        Scrim(onDismissRequest, Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = HerdrShapes.Sheet,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).heightIn(max = 680.dp).imePadding().then(NoClick),
            ) {
                Column(Modifier.navigationBarsPadding()) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp)
                            .width(36.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
                    Spacer(Modifier.size(8.dp))
                }
            }
        }
    }
}

/** A menu is an action sheet: big rows, reachable with a thumb. Items are [HerdrMenuItem]s. */
@Composable
fun HerdrMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    BackHandler(onBack = onDismissRequest)
    HerdrSheet(onDismissRequest) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), content = content)
    }
}

@Composable
fun HerdrMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leadingIcon != null) leadingIcon()
        Box(Modifier.weight(1f).then(if (enabled) Modifier else Modifier.background(Color.Transparent))) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyLarge,
                androidx.compose.material3.LocalContentColor provides
                    MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
            ) { text() }
        }
    }
}

/** Plain styled error line with an optional retry; never a raw dump. */
@Composable
fun ErrorMessage(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text.lineSequence().first().take(240), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
    }
}
