package com.makefunstuff.herdr.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** The bottom bar of the three top-level screens: on the card surface, the selected icon on a mint pill. */
@Composable
fun HerdrNavBar(content: @Composable RowScope.() -> Unit) {
    val c = Herdr.colors
    Column(Modifier.fillMaxWidth().background(c.card)) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 72.dp).padding(horizontal = Space.s),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val c = Herdr.colors
    val pill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(180), label = "nav")
    Column(
        Modifier.weight(1f).heightIn(min = 64.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = if (badge > 0) "$label, $badge need you" else label
                this.selected = selected
                role = Role.Tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.width(56.dp).height(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(pill), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else c.muted, modifier = Modifier.size(24.dp))
            }
            if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = (-2).dp))
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) c.text else c.muted,
        )
    }
}
