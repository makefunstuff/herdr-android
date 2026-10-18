package com.makefunstuff.herdr.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.manage.ManageHeading
import com.makefunstuff.herdr.ui.manage.ManageSheet
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * Which agents something from a workspace (a quote from its terminal, a screenshot of its dev
 * server) can go to: the workspace's own agents, then the machine's others.
 */
data class ChatTargets(val here: List<AgentInfo>, val elsewhere: List<AgentInfo>) {
    val all: List<AgentInfo> get() = here + elsewhere

    /** The agent to go to without asking: the workspace's only one, or the machine's only one. */
    val only: AgentInfo? get() = here.singleOrNull() ?: all.singleOrNull()

    companion object {
        fun of(snapshot: SessionSnapshot?, workspaceId: String?): ChatTargets {
            val agents = snapshot?.agents.orEmpty()
            val here = agents.filter { it.workspaceId == workspaceId }
            return ChatTargets(here, agents - here.toSet())
        }
    }
}

/** Asks which agent's chat to put something in: [title] says what. */
@Composable
fun AgentPickSheet(title: String, subtitle: String, targets: ChatTargets, snapshot: SessionSnapshot?, onPick: (AgentInfo) -> Unit, onDismiss: () -> Unit) {
    ManageSheet(title, subtitle, onDismiss) {
        if (targets.here.isNotEmpty()) {
            ManageHeading("In this workspace")
            targets.here.forEach { AgentRow(it, snapshot) { onPick(it) } }
        }
        if (targets.elsewhere.isNotEmpty()) {
            ManageHeading(if (targets.here.isEmpty()) "On this machine" else "Elsewhere on this machine")
            targets.elsewhere.forEach { AgentRow(it, snapshot) { onPick(it) } }
        }
    }
}

/** An agent as the sheet's row: its mark, its name, where it is and how it stands. */
@Composable
private fun AgentRow(a: AgentInfo, snapshot: SessionSnapshot?, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(HerdrShapes.Field).clickable(onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentMark(a.agent ?: a.displayName, size = 22.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(a.displayName, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(snapshot?.location(a.workspaceId, a.tabId), a.agentStatus.name.lowercase()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
