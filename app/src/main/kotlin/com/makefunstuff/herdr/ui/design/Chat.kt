package com.makefunstuff.herdr.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Radius
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** Your chat turns (`.chat-turn-user`): `--radius-lg` on all four corners. */
val BubbleShape = RoundedCornerShape(12.dp)
/** The Coordinator's replies, in the same shape. */
val ReplyBubbleShape = RoundedCornerShape(12.dp)

/**
 * The box you type into at the bottom of a chat, as herdr-android lays it out (a field, and beside
 * it the round Send button, or Stop while the agent works and nothing is typed), drawn as the web
 * UI's input card: `--bg-elevated`, a hairline that turns amber while you type, `--radius-2xl`,
 * `--shadow-card`. Send is the amber circle with an up arrow; Stop the same circle in strong text
 * with a square. [below] holds a second action under the field (the Coordinator's "Send and start
 * agents").
 */
@Composable
fun ChatComposer(
    draft: String,
    onDraft: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stop: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val c = Herdr.colors
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val card = RoundedCornerShape(Radius.xxl)
    Column(modifier.fillMaxWidth().background(c.ground).padding(start = Space.m, end = Space.m, top = Space.s, bottom = Space.m)) {
        Row(
            Modifier.fillMaxWidth().shadow(6.dp, card, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(card).background(c.card).border(1.dp, if (focused) c.accent else c.line, card)
                .padding(start = Space.xs, end = Space.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = draft,
                onValueChange = onDraft,
                enabled = enabled,
                interactionSource = focus,
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = c.strong, unfocusedTextColor = c.strong,
                    focusedPlaceholderColor = c.muted, unfocusedPlaceholderColor = c.muted, disabledPlaceholderColor = c.off,
                    cursorColor = c.accent,
                    selectionColors = TextSelectionColors(c.accent, c.accentTint),
                ),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            )
            Box(Modifier.padding(bottom = 2.dp)) {
                if (stop != null && draft.isBlank()) RoundAction(HerdrIcons.Square, "Stop", stop = true, onClick = stop)
                else RoundAction(HerdrIcons.ArrowUp, "Send", enabled = enabled && draft.isNotBlank(), onClick = onSend)
            }
        }
        if (below != null) Box(Modifier.padding(top = Space.s, end = Space.xs)) { below() }
    }
}

/**
 * The web UI's round button (`.composer-action`): a 36dp circle in a 48dp target. Primary (Send,
 * Run) is amber with ink; [stop] is strong text with a filled square; [primary] false is the
 * neutral one that floats over content ("Newest"), `--bg-elevated` with a hairline and a shadow.
 */
@Composable
fun RoundAction(icon: ImageVector, description: String, enabled: Boolean = true, primary: Boolean = true, stop: Boolean = false, onClick: () -> Unit) {
    val c = Herdr.colors
    val (fill, glyph) = when {
        !enabled -> c.card to c.off
        stop -> c.strong to c.ground
        primary -> c.primary to c.onPrimary
        else -> c.card to c.text
    }
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp)
                .then(if (!primary && !stop) Modifier.shadow(8.dp, CircleShape) else Modifier)
                .background(fill, CircleShape)
                .then(if (!enabled || (!primary && !stop)) Modifier.border(1.dp, c.line, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (stop) Box(Modifier.size(12.dp).background(glyph, RoundedCornerShape(2.dp)))
            else Icon(icon, null, tint = glyph, modifier = Modifier.size(18.dp))
        }
    }
}

/** A key the agent's screen understands (Enter, Esc, 1…): a keycap (`.kbd`): mono, a strong edge, 48dp tall to tap. */
@Composable
fun KeyChip(label: String, onClick: () -> Unit) {
    val c = Herdr.colors
    val shape = RoundedCornerShape(Radius.m)
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = CodeStyle,
            color = c.text,
            modifier = Modifier.clip(shape).background(c.card).border(1.dp, c.lineStrong, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
