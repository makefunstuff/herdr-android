package com.makefunstuff.herdr.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/*
 * The app's shared components (docs/DESIGN.md). Screens build from these instead of stock
 * Material ones, so spacing, shapes and colours come from one place.
 */

object HerdrShapes {
    val Sheet = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)
    val Dialog = RoundedCornerShape(Radius.xl)
    val Card = RoundedCornerShape(Radius.l)
    val Field = RoundedCornerShape(Radius.m)
    val Tile = RoundedCornerShape(Radius.m)
    val Badge = RoundedCornerShape(Radius.s)
    val Pill = RoundedCornerShape(50)
}

/** Where a button sits in a dialog: a quiet button in the confirm slot draws as the primary pill. */
private enum class Slot { None, Confirm, Dismiss }
private val LocalSlot = compositionLocalOf { Slot.None }

// ---------------------------------------------------------------------------------------------
// Popups

@Composable
private fun Scrim(onDismiss: () -> Unit, align: Alignment, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
        contentAlignment = align,
    ) {
        ToastHost()
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 10 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 10 },
        ) { content() }
    }
}

private val NoClick: Modifier
    @Composable get() = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

private val FullScreen = DialogProperties(usePlatformDefaultWidth = false)

/** The colour sheets and dialogs sit on: a raised grey on black, white on white. */
val sheetColor: Color @Composable get() = if (Herdr.colors.dark) Herdr.colors.card else Herdr.colors.ground

/**
 * Centred dialog, only for confirmations and short forms: a title, a body, and its buttons at the
 * bottom right. A [QuietButton] in [confirmButton] draws as the primary pill.
 */
@OptIn(ExperimentalLayoutApi::class)
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
                color = sheetColor,
                shape = HerdrShapes.Dialog,
                modifier = Modifier.padding(Space.xl).widthIn(max = 420.dp).fillMaxWidth().imePadding().then(NoClick),
            ) {
                Column(Modifier.padding(top = Space.xl, start = Space.xl, end = Space.xl, bottom = Space.l)) {
                    if (title != null) Box(Modifier.padding(bottom = Space.s).semantics { heading() }) {
                        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleLarge) { title() }
                    }
                    if (text != null) Box(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                            LocalContentColor provides Herdr.colors.muted,
                        ) { text() }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = Space.xl),
                        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        CompositionLocalProvider(LocalSlot provides Slot.Dismiss) { dismissButton?.invoke() }
                        CompositionLocalProvider(LocalSlot provides Slot.Confirm) { confirmButton() }
                    }
                }
            }
        }
    }
}

/** Bottom sheet with a drag handle; scrolls when long, and drags away to dismiss. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = HerdrShapes.Sheet,
        containerColor = sheetColor,
        contentColor = Herdr.colors.text,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Box(
                Modifier.padding(top = Space.m, bottom = Space.s).width(32.dp).height(4.dp)
                    .clip(HerdrShapes.Pill).background(Herdr.colors.fieldFocused),
            )
        },
    ) {
        Box {
            // Scrolls when longer than the screen. Callers with a list inside bound its height (heightIn(max)).
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.s), content = content)
            ToastHost(Modifier.matchParentSize())
        }
    }
}

/** A sheet's title block: 20sp title, optional muted line under it, on the sheet's 24dp margin. */
@Composable
fun SheetTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.xs, bottom = Space.m)) {
        Text(
            title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) Text(
            subtitle, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A menu is an action sheet: a title, then big rows reachable with a thumb. Items are [HerdrMenuItem]s or [ActionRow]s. */
@Composable
fun HerdrMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    BackHandler(onBack = onDismissRequest)
    HerdrSheet(onDismissRequest) {
        if (title != null) SheetTitle(title, subtitle)
        Column(Modifier.padding(horizontal = Space.s), content = content)
    }
}

/** One row of a [HerdrMenu]: icon, label, the whole row the target. */
@Composable
fun HerdrMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val c = Herdr.colors
    val tint = when {
        !enabled -> c.off
        destructive -> c.red
        else -> c.text
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(HerdrShapes.Tile)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        if (leadingIcon != null) CompositionLocalProvider(LocalContentColor provides if (destructive || !enabled) tint else c.muted) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leadingIcon() }
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge, LocalContentColor provides tint) { text() }
        }
    }
}

/** A quiet heading between groups of a menu's rows. */
@Composable
fun MenuSection(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = Herdr.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.xs).semantics { heading() },
    )
}

/** Convenience: a menu row from an icon and a label. */
@Composable
fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false) =
    HerdrMenuItem(text = { Text(label) }, onClick = onClick, leadingIcon = { Icon(icon, null, Modifier.size(22.dp)) }, enabled = enabled, destructive = destructive)

// ---------------------------------------------------------------------------------------------
// Messages and states

/** A styled error line with an optional retry; never a raw dump. */
@Composable
fun ErrorMessage(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = Herdr.colors
    Surface(color = c.tint(c.red), shape = HerdrShapes.Tile, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = Space.m, top = Space.s, bottom = Space.s, end = Space.xs).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HerdrIcons.Error, null, tint = c.red, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Text(text.lineSequence().first().take(240), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text)
            if (onRetry != null) QuietButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** A quiet notice: icon, sentence, on a tinted tile. [color] is a status colour; null is neutral. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, color: Color? = null, icon: ImageVector = HerdrIcons.Info) {
    val c = Herdr.colors
    Surface(color = color?.let { c.tint(it) } ?: c.field, shape = HerdrShapes.Tile, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Space.m, vertical = Space.m), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color ?: c.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
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
fun ToastHost(modifier: Modifier = Modifier.fillMaxSize()) {
    val m = Toasts.current
    LaunchedEffect(m?.id) {
        if (m != null) { kotlinx.coroutines.delay(if (m.error) 5000 else 3000); if (Toasts.current?.id == m.id) Toasts.dismiss() }
    }
    Box(modifier.statusBarsPadding().padding(Space.m), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(m != null, enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { -it }, exit = fadeOut(tween(150)) + slideOutVertically { -it }) {
            val last = remember { mutableStateOf(m) }
            if (m != null) last.value = m
            val msg = last.value
            val c = Herdr.colors
            if (msg != null) Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = HerdrShapes.Pill, shadowElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).clickable(onClickLabel = "Dismiss") { Toasts.dismiss() }
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(Modifier.padding(horizontal = Space.l, vertical = Space.m).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (msg.error) {
                        // The inverse surface flips the theme, so the red that reads on it is the other theme's.
                        Icon(HerdrIcons.Error, null, tint = if (c.dark) Color(0xFFB3342A) else Color(0xFFF0857A), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Space.s))
                    }
                    Text(msg.text.lineSequence().first().take(200), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Centred progress with a spoken label. */
@Composable
fun LoadingState(label: String = "Loading", modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Spinner(Modifier.semantics { contentDescription = label })
        Text(label + "…", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted)
    }
}

/** The one spinner: 24dp, thin, in the accent. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    CircularProgressIndicator(modifier.size(size), strokeWidth = 2.5.dp, color = Herdr.colors.accent, trackColor = Herdr.colors.field)
}

/** Full-area error with what happened and a way out. */
@Composable
fun ErrorState(title: String, body: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StateBlock(HerdrIcons.Error, title, body?.lineSequence()?.first()?.take(240), modifier, iconTint = Herdr.colors.red) {
        if (onRetry != null) SecondaryButton(onClick = onRetry) { Text("Try again") }
    }
}

/** An empty, error or waiting state: an icon on a round tile, a title, a muted sentence, maybe a button. */
@Composable
fun StateBlock(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Herdr.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).background(iconTint?.let { c.tint(it) } ?: c.field, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint ?: c.muted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(Space.l))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (body != null) {
            Spacer(Modifier.height(Space.xs))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(Space.l))
            action()
        }
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

/** This status colour as text on the current ground (the palette is made to read; this is a safety net for tints). */
@Composable
fun Color.readableText(): Color = readable(this, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)

// ---------------------------------------------------------------------------------------------
// Bars

/** Top-bar title: one ellipsised line, optional quieter second line. Keeps long names from pushing the bar open. */
@Composable
fun BarTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(
            subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted,
        )
    }
}

/**
 * The bar over a screen. With [onBack] it is a detail screen's bar (back arrow, 20sp title, muted
 * subtitle); without, a top-level screen's (28sp title). Actions are [BarIcon]s and [BarAdd].
 */
@Composable
fun HerdrTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().background(Herdr.colors.ground).statusBarsPadding()
            .heightIn(min = if (onBack == null) 72.dp else 64.dp)
            .padding(start = if (onBack == null) Space.screen else Space.xs, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) BarIcon(HerdrIcons.ArrowBack, backLabel, onBack)
        Box(Modifier.weight(1f).padding(start = if (onBack != null) Space.xs else 0.dp, end = Space.s)) {
            when {
                titleContent != null -> titleContent()
                onBack == null -> Text(
                    title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                else -> BarTitle(title, subtitle)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A 48dp icon button in a bar, with an optional count badge. */
@Composable
fun BarIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit, badge: Int = 0, enabled: Boolean = true) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Herdr.colors.text else Herdr.colors.off, modifier = Modifier.size(24.dp))
        if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp))
    }
}

/** The screen's one "add" action: a round inverse + in the bar (Puggie's "New bot"). */
@Composable
fun BarAdd(contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick,
        ).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(HerdrIcons.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
        }
    }
}

/** A small red count, for unread and needs-you. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Box(
        modifier.defaultMinSize(minWidth = 16.dp, minHeight = 16.dp).background(c.red, CircleShape).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = TextStyle(fontSize = MaterialTheme.typography.labelSmall.fontSize, fontWeight = FontWeight.SemiBold),
            color = if (c.dark) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/** The bar of primary actions a form keeps at the bottom, in thumb reach. */
@Composable
fun BottomActionBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Herdr.colors.ground).navigationBarsPadding().imePadding()
            .padding(horizontal = Space.screen, vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// Buttons: pills, 48dp tall. Primary is inverse (white on black, black on white), secondary a grey
// fill, quiet just text; danger is the red fill and only confirms something destructive.

private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)

@Composable
private fun PillButton(
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    body: @Composable RowScope.() -> Unit,
) {
    val c = Herdr.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = HerdrShapes.Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = if (container == Color.Transparent) Color.Transparent else c.field,
            disabledContentColor = c.off,
        ),
        elevation = null,
        contentPadding = contentPadding,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelLarge) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s), content = body)
        }
    }
}

@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = ButtonPadding, content: @Composable RowScope.() -> Unit) =
    PillButton(onClick, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, modifier, enabled, contentPadding, content)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    PrimaryButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }

@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = ButtonPadding, content: @Composable RowScope.() -> Unit) =
    PillButton(onClick, Herdr.colors.field, Herdr.colors.text, modifier, enabled, contentPadding, content)

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    SecondaryButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }

/** Text only. In a dialog's confirm slot it draws as the primary pill. [color] overrides the text colour (e.g. the accent for a link-like action). */
@Composable
fun QuietButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    when (LocalSlot.current) {
        Slot.Confirm -> if (color == Herdr.colors.red || color == MaterialTheme.colorScheme.error) {
            PillButton(onClick, Herdr.colors.red, MaterialTheme.colorScheme.onError, modifier, enabled, ButtonPadding, content)
        } else PrimaryButton(onClick, modifier, enabled, ButtonPadding, content)
        else -> PillButton(
            onClick, Color.Transparent, if (color == Color.Unspecified) Herdr.colors.text else color, modifier, enabled, contentPadding, content,
        )
    }
}

/** A destructive confirmation: the red fill. */
@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    PillButton(onClick, Herdr.colors.red, MaterialTheme.colorScheme.onError, modifier, enabled, ButtonPadding) { Text(text) }

// ---------------------------------------------------------------------------------------------
// Chips, pills and status

/** A filter or choice: a filled pill, inverse when selected. 48dp target, 36dp drawn. */
@Composable
fun HerdrChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val c = Herdr.colors
    Box(
        modifier.heightIn(min = 48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick,
        ).semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).clip(HerdrShapes.Pill)
                .background(if (selected) scheme.primary else c.field)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides when { !enabled -> c.off; selected -> scheme.onPrimary; else -> c.text },
                LocalTextStyle provides MaterialTheme.typography.labelLarge,
            ) {
                if (leadingIcon != null) Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { leadingIcon() }
                label()
            }
        }
    }
}

@Composable
fun HerdrChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    HerdrChip(selected, onClick, { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier)

/** A row of mutually exclusive choices drawn as one pill track (replaces segmented buttons). */
@Composable
fun SegmentedPills(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Row(modifier.fillMaxWidth().clip(HerdrShapes.Pill).background(c.field).padding(Space.xs)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(HerdrShapes.Pill)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelect(i) })
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else c.text,
                    modifier = Modifier.padding(horizontal = Space.s),
                )
            }
        }
    }
}

/** Small status chip: coloured dot + label (never colour alone). */
@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Row(
        modifier.clip(HerdrShapes.Pill).background(c.tint(color)).padding(horizontal = 10.dp, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.text, maxLines = 1)
    }
}

/** Status as text: a dot and a muted label, for rows that already carry a lot. */
@Composable
fun StatusLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Fields

/**
 * A filled field (no outline): grey fill, label inside, 12dp corners. Same parameters as
 * OutlinedTextField for the ones the app uses, so forms swap it in directly.
 */
@Composable
fun HerdrField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = HerdrShapes.Field,
) {
    val c = Herdr.colors
    TextField(
        value = value, onValueChange = onValueChange, modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled, readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape,
        colors = fieldColors(),
    )
}

@Composable
fun fieldColors() = Herdr.colors.let { c ->
    TextFieldDefaults.colors(
        focusedContainerColor = c.fieldFocused, unfocusedContainerColor = c.field, disabledContainerColor = c.field,
        errorContainerColor = c.tint(c.red),
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
        focusedLabelColor = c.muted, unfocusedLabelColor = c.muted, errorLabelColor = c.red,
        focusedSupportingTextColor = c.muted, unfocusedSupportingTextColor = c.muted, errorSupportingTextColor = c.red,
        focusedPlaceholderColor = c.faint, unfocusedPlaceholderColor = c.faint,
        cursorColor = c.accent, errorCursorColor = c.red,
        focusedTrailingIconColor = c.muted, unfocusedTrailingIconColor = c.muted,
        focusedLeadingIconColor = c.muted, unfocusedLeadingIconColor = c.muted,
        disabledTextColor = c.muted, disabledLabelColor = c.off,
    )
}

/** A form field with a label, and a hint or an error under it. */
@Composable
fun HerdrTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    HerdrField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = singleLine, readOnly = readOnly,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------
// Surfaces and rows

/** The small label over a group of choices inside a form or sheet ("Workspace", "Agent"). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Herdr.colors.muted, modifier = modifier.padding(top = Space.xs).semantics { heading() })
}

/** A muted label that names a group of fields or rows, over the group. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, top: Dp = Space.xl, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = top, bottom = Space.s).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = Herdr.colors.muted,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** A card: the raised grey, 20dp corners, no border. Holds a group of rows (no padding) or, with [padded], content. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuietCard(
    modifier: Modifier = Modifier,
    padded: Boolean = false,
    color: Color = Herdr.colors.card,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(HerdrShapes.Card).background(color)
            .then(
                if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                    onClickLabel = onClickLabel, onClick = onClick ?: {}, onLongClick = onLongClick,
                ) else Modifier,
            )
            .then(if (padded) Modifier.padding(Space.card) else Modifier),
        content = content,
    )
}

/** A square-ish tile with an icon or a letter, the leading mark of a row. [dot] draws a status dot on its corner. */
@Composable
fun IconTile(
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    letter: String? = null,
    tint: Color? = null,
    background: Color? = null,
    dot: Color? = null,
    size: Dp = 40.dp,
) {
    val c = Herdr.colors
    Box(modifier.size(size)) {
        Box(
            Modifier.matchParentSize().clip(HerdrShapes.Tile).background(background ?: tint?.let { c.tint(it) } ?: c.field),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, tint = tint ?: c.text, modifier = Modifier.size(size / 2))
            else if (letter != null) Text(letter, style = MaterialTheme.typography.titleMedium, color = tint ?: c.text)
        }
        if (dot != null) Box(
            Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(12.dp)
                .background(LocalRowBackground.current.takeOrElse { c.ground }, CircleShape).padding(2.dp).background(dot, CircleShape),
        )
    }
}

/** What a row is drawn on, so a tile's status dot can cut itself out of it. */
val LocalRowBackground = compositionLocalOf { Color.Unspecified }

/**
 * A list row: optional leading tile, a title, up to two quieter lines, and trailing content or a
 * chevron. At least 56dp and the whole row is the target. Used on the ground (lists) and in cards
 * (grouped settings), and by the sheets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    subtitleColor: Color = Color.Unspecified,
    subtitleLines: Int = 1,
    below: (@Composable () -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Herdr.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(
                if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                    enabled = enabled, onClickLabel = onClickLabel, onClick = onClick ?: {}, onLongClick = onLongClick,
                ) else Modifier,
            )
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (!enabled) c.off else if (titleColor == Color.Unspecified) c.text else titleColor,
                    modifier = Modifier.weight(1f),
                )
                if (meta != null) {
                    Spacer(Modifier.width(Space.s))
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = c.faint, maxLines = 1)
                }
            }
            if (subtitle != null) Text(
                subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis,
                color = if (!enabled) c.off else if (subtitleColor == Color.Unspecified) c.muted else subtitleColor,
            )
            below?.invoke()
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.m))
            trailing()
        } else if (chevron) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.ChevronRight, null, tint = c.faint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * One tappable action row, as the sheets and forms use it: an icon, a title, an optional second
 * line, an optional chevron or trailing content. 56dp tall at the least; the whole row is the target.
 */
@Composable
fun ActionRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    chevron: Boolean = false,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Herdr.colors
    val accent = when {
        !enabled -> c.off
        destructive -> c.red
        selected -> c.text
        iconTint != null -> iconTint
        else -> c.muted
    }
    val titleColor = when {
        !enabled -> c.off
        destructive -> c.red
        else -> c.text
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(HerdrShapes.Tile)
            .then(if (selected) Modifier.background(c.field) else Modifier)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.l, vertical = Space.s),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) c.muted else c.off,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.m))
            trailing()
        } else if (selected) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(20.dp))
        } else if (chevron) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.ChevronRight, null, tint = c.faint, modifier = Modifier.size(20.dp))
        }
    }
}

/** A setting that is on or off: the whole 56dp row toggles; a muted line under the label when it needs one. */
@Composable
fun SwitchRow(label: String, detail: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(HerdrShapes.Tile)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Herdr.colors.text else Herdr.colors.off)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
        }
        Spacer(Modifier.width(Space.m))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors())
    }
}

@Composable
fun switchColors() = Herdr.colors.let { c ->
    androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = c.muted, uncheckedTrackColor = c.field, uncheckedBorderColor = Color.Transparent,
    )
}

/** A label/value line inside a card (Server: "Session  default"). */
@Composable
fun InfoLine(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.weight(1f))
        Text(
            value, style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A hairline between rows of one card, inset past the leading tile. */
@Composable
fun RowDivider(inset: Dp = Space.l) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(Herdr.colors.line))
}

/** Monospaced text on a field-coloured block: keys, paths, fingerprints. */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        color = Herdr.colors.text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().clip(HerdrShapes.Tile).background(Herdr.colors.field).padding(Space.m),
    )
}
