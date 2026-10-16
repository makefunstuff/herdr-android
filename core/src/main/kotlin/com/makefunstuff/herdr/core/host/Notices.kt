package com.makefunstuff.herdr.core.host

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot

/**
 * One of the session's notifications: what herdr pops up on the desktop when an agent finishes or
 * starts waiting for the human. herdr's API has no list of them, so they are derived from the
 * agent state changes the app already follows.
 */
data class Notice(
    val id: String,
    val at: Long,
    val kind: Kind,
    val paneId: String,
    val workspaceId: String,
    val tabId: String,
    val title: String,
    val body: String,
) {
    enum class Kind { Done, NeedsYou }
}

object Notices {
    const val LIMIT = 50

    /** Notices for agents that became done or blocked between [old] and [new]. */
    fun between(old: SessionSnapshot?, new: SessionSnapshot, now: Long): List<Notice> {
        if (old == null) return emptyList()
        return new.agents.mapNotNull { agent ->
            val before = old.agents.firstOrNull { it.paneId == agent.paneId } ?: return@mapNotNull null
            val status = agent.agentStatus
            if (status == before.agentStatus) return@mapNotNull null
            val kind = when (status) {
                AgentStatus.Done -> Notice.Kind.Done
                AgentStatus.Blocked -> Notice.Kind.NeedsYou
                else -> return@mapNotNull null
            }
            val name = agent.name?.takeIf { it.isNotBlank() } ?: agent.displayName
            Notice(
                id = "${agent.paneId}#${agent.stateChangeSeq}#$now",
                at = now,
                kind = kind,
                paneId = agent.paneId,
                workspaceId = agent.workspaceId,
                tabId = agent.tabId,
                title = if (kind == Notice.Kind.Done) "$name finished" else "$name needs you",
                body = listOfNotNull(agent.subtitle, new.location(agent.workspaceId, agent.tabId)).joinToString(" · "),
            )
        }
    }

    /** [current] with [added] in front, newest first, capped at [LIMIT]. */
    fun merge(current: List<Notice>, added: List<Notice>): List<Notice> =
        if (added.isEmpty()) current else (added.sortedByDescending { it.at } + current).take(LIMIT)
}
