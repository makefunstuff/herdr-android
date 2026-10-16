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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.luminance
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
        ToastHost()
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
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { dismissButton?.invoke() }
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { confirmButton() }
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
fun HerdrMenu(expanded: Boolean, onDismissRequest: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    BackHandler(onBack = onDismissRequest)
    HerdrSheet(onDismissRequest) {
        if (title != null) Text(
            title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp), content = content)
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

/** App-wide, styled replacement for system toasts. [com.makefunstuff.herdr.ui.common.toast] feeds it. */
object Toasts {
    class Msg(val text: String, val error: Boolean, val id: Long)
    var current by mutableStateOf<Msg?>(null)
        private set
    private var next = 0L
    private val errorWords = Regex("""(?i)\b(couldn't|can't|didn't|isn't|refused|failed|error|not |no app|unable)""")
    fun show(text: String, error: Boolean = errorWords.containsMatchIn(text)) {
        current = Msg(text, error, next++)
    }
    fun dismiss() { current = null }
}

/** Shows the current toast at the top of whatever it is placed in; dismisses itself. */
@Composable
fun ToastHost() {
    val m = Toasts.current
    LaunchedEffect(m?.id) {
        if (m != null) { kotlinx.coroutines.delay(if (m.error) 5000 else 3000); if (Toasts.current?.id == m.id) Toasts.dismiss() }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(m != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            val last = remember { mutableStateOf(m) }
            if (m != null) last.value = m
            val msg = last.value
            if (msg != null) Surface(
                color = if (msg.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
                contentColor = if (msg.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).clickable(onClickLabel = "Dismiss") { Toasts.dismiss() }
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(msg.text.lineSequence().first().take(200), Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 24.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Rounded outlined field with consistent error handling for forms. */
@Composable
fun HerdrTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = singleLine,
        isError = error != null, shape = RoundedCornerShape(14.dp),
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

/** Small status chip: coloured dot + label (never colour alone). */
@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(color, androidx.compose.foundation.shape.CircleShape))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

/** Centered progress with a spoken label. */
@Composable
fun LoadingState(label: String = "Loading", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
        Text(label + "…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Full-area error with what happened and a way out. */
@Composable
fun ErrorState(title: String, body: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        if (body != null) Text(body.lineSequence().first().take(240), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onRetry != null) androidx.compose.material3.FilledTonalButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
    }
}

fun contrastRatio(a: Color, b: Color): Double {
    val x = a.luminance().toDouble() + 0.05; val y = b.luminance().toDouble() + 0.05
    return maxOf(x, y) / minOf(x, y)
}

/** [fg] nudged toward [towards] (usually onSurface) until it reads at 4.5:1 on [bg]; unchanged if it already does. */
fun readable(fg: Color, bg: Color, towards: Color): Color {
    var c = fg
    var t = 0f
    while (contrastRatio(c, bg) < 4.5 && t < 1f) { t += 0.05f; c = androidx.compose.ui.graphics.lerp(fg, towards, t) }
    return c
}

/** Top-bar title: one ellipsised line, optional quieter second line. Keeps long machine names from pushing the bar open. */
@Composable
fun BarTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(
            subtitle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** This accent colour as text on the current surface (≥4.5:1). */
@Composable
fun Color.readableText(): Color = readable(this, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
