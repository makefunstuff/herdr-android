package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.board.AgentChoices
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.usage.UsageRules
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.usage.levelColor

/** How old a machine's usage reading may be when agents are chosen; an older one is read again. */
private const val CHOOSE_FRESH_MS = 5 * 60_000L

/**
 * Starting an agent on a task: its own agent first, then the ones the board falls back to, each with
 * what its provider has left on this machine ("18% left · resets 17:00", "$7.63 left"), so a tight one
 * can be passed over. [onStart] gets the agent chosen instead of the task's own, or null for its own.
 */
@Composable
fun StartAgentPanel(task: BoardTask, session: HostSession, onDismiss: () -> Unit, onStart: (String?) -> Unit) {
    val machineId = session.machine.id
    val choices by produceState<Result<AgentChoices>?>(null, task.id) { value = runCatching { session.awaitBoard().agents(task.id) } }
    UsageFresh(session)
    var chosen by remember { mutableStateOf<String?>(null) }
    val c = choices?.getOrNull()
    BoardPanel(
        "Start an agent on ${task.name}", onDismiss,
        buttons = {
            PanelCancel(onDismiss)
            PrimaryButton("Start") { onStart(chosen?.takeIf { it != c?.worker }) }
        },
    ) {
        Text(task.title, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when {
            choices == null -> LoadingState("Reading its agents")
            c == null || c.workers.isEmpty() -> Text(
                "The board didn't say which agents it has${choices?.exceptionOrNull()?.message?.let { " ($it)" }.orEmpty()}. Start puts the task's own agent on it.",
                style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted,
            )
            else -> {
                FieldLabel("Agent")
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    c.workers.forEachIndexed { i, a ->
                        AgentChoiceRow(a, if (i == 0) "its agent" else "fallback $i", selected = (chosen ?: c.worker) == a, machineId = machineId) { chosen = a }
                    }
                }
                if (c.workerFallback.isNotEmpty()) Text(
                    "When the one at work runs out of its allowance, the board hands the task to the next one down the list.",
                    style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted,
                )
            }
        }
    }
}

/** Reads the machine's usage when it is older than a few minutes, while what shows it is on screen. */
@Composable
internal fun UsageFresh(session: HostSession) {
    val store = LocalContainer.current.usage
    LaunchedEffect(session.machine.id) { if (store.stale(session.machine.id, CHOOSE_FRESH_MS)) store.read(session) }
}

/** One agent to choose: its mark, its name, and what its provider has left, in amber near a limit and red when out. */
@Composable
private fun AgentChoiceRow(agent: String, role: String, selected: Boolean, machineId: String, onClick: () -> Unit) {
    val col = Herdr.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(HerdrShapes.Tile)
            .background(if (selected) col.accentTint else col.card)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentMark(agent.substringBefore('/'), size = 22.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent, style = MaterialTheme.typography.titleMedium, color = col.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(Space.s))
                Text(role, style = MaterialTheme.typography.labelMedium, color = col.muted, maxLines = 1)
            }
            AgentLeft(agent, machineId)
        }
        if (selected) {
            Spacer(Modifier.width(Space.s))
            Icon(HerdrIcons.Check, null, tint = col.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** What [agent]'s provider has left on [machineId], one dim line (amber near a limit); nothing while unknown. */
@Composable
fun AgentLeft(agent: String, machineId: String, prefix: String = "") {
    val store = LocalContainer.current.usage
    val latest by store.latest.collectAsStateWithLifecycle()
    // At the time it is shown: a limit whose reset passed since the reading counts as fresh.
    val left = UsageRules.leftFor(agent, latest[machineId]?.report, store.nowSeconds(), store.zone()) ?: return
    if (left.words.isEmpty()) return
    Text(
        prefix + "${left.provider.short}: ${left.words}",
        style = MaterialTheme.typography.bodySmall, color = levelColor(left.level), maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** The Add panel's line under "Start an agent now": which agent the board would put on it, and what it has left. */
@Composable
internal fun DefaultAgentLine(session: HostSession) {
    val choices by produceState<AgentChoices?>(null, session.machine.id) { value = runCatching { session.awaitBoard().agents() }.getOrNull() }
    UsageFresh(session)
    val c = choices ?: return
    if (c.worker.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(start = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        AgentMark(c.worker.substringBefore('/'), size = 16.dp)
        Spacer(Modifier.width(Space.s))
        Column {
            Text(c.worker, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AgentLeft(c.worker, session.machine.id)
        }
    }
}
