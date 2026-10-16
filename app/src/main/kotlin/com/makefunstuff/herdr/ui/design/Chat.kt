package com.makefunstuff.herdr.ui.design

import androidx.compose.foundation.background
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

/** A message you sent: a grey bubble on the right, its last corner tucked in. Replies sit on the ground without one. */
val BubbleShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)

/**
 * The box you type into at the bottom of a chat (Puggie's composer): a rounded grey card with the
 * text and one round inverse button, send or stop. [below] holds a second action under the field.
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
    Column(modifier.fillMaxWidth().background(c.ground).padding(horizontal = Space.m, vertical = Space.s)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.card).padding(end = Space.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = draft,
                onValueChange = onDraft,
                enabled = enabled,
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedPlaceholderColor = c.faint, unfocusedPlaceholderColor = c.faint, disabledPlaceholderColor = c.off,
                    cursorColor = c.accent,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
            val showStop = stop != null && draft.isBlank()
            Box(Modifier.padding(bottom = Space.xs)) {
                if (showStop) RoundAction(HerdrIcons.StopCircle, "Stop", onClick = stop!!)
                else RoundAction(HerdrIcons.ArrowUpward, "Send", enabled = enabled && draft.isNotBlank(), onClick = onSend)
            }
        }
        if (below != null) Box(Modifier.padding(top = Space.s)) { below() }
    }
}

/** A 48dp target with a 40dp inverse circle: the composer's send and stop. */
@Composable
fun RoundAction(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val c = Herdr.colors
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).background(if (enabled) scheme.primary else c.field, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (enabled) scheme.onPrimary else c.off, modifier = Modifier.size(22.dp)) }
    }
}

/** A key the agent's screen understands (Enter, Esc, 1…): a small grey pill, 48dp tall to tap. */
@Composable
fun KeyChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Herdr.colors.text,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Herdr.colors.field).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
