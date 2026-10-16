package com.makefunstuff.herdr.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Agent state as herdr reports it. Unrecognised values map to [Unknown]. */
enum class AgentStatus(val wire: String, val attention: Int) {
    Blocked("blocked", 4),
    Done("done", 3),
    Working("working", 2),
    Idle("idle", 1),
    Unknown("unknown", 0);

    companion object {
        fun of(value: String?): AgentStatus = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

@Serializable
data class WorkspaceInfo(
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
    @SerialName("active_tab_id") val activeTabId: String = "",
    @SerialName("agent_status") val agentStatusRaw: String = "unknown",
    val worktree: WorktreeInfo? = null,
    /** Metadata that integrations report for the workspace (branch, PR, …), as key → value. */
    val tokens: Map<String, String> = emptyMap(),
) {
    val agentStatus: AgentStatus get() = AgentStatus.of(agentStatusRaw)
}

@Serializable
data class WorktreeInfo(
    @SerialName("repo_name") val repoName: String = "",
    @SerialName("checkout_path") val checkoutPath: String = "",
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean = false,
)

@Serializable
data class TabInfo(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("agent_status") val agentStatusRaw: String = "unknown",
) {
    val agentStatus: AgentStatus get() = AgentStatus.of(agentStatusRaw)
}

@Serializable
data class PaneInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String = "",
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean = false,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    val label: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitle: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatusRaw: String = "unknown",
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    val revision: Long = 0,
) {
    val agentStatus: AgentStatus get() = AgentStatus.of(agentStatusRaw)

    /** The name shown for this pane: agent, label, or terminal title. */
    val displayName: String
        get() = displayAgent ?: agent ?: label ?: terminalTitle?.takeIf { it.isNotBlank() } ?: paneId
}

@Serializable
data class AgentInfo(
    @SerialName("terminal_id") val terminalId: String = "",
    val name: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitle: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatusRaw: String = "unknown",
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean = false,
    @SerialName("state_change_seq") val stateChangeSeq: Long = 0,
    val cwd: String? = null,
    @SerialName("agent_session") val agentSession: AgentSessionRef? = null,
) {
    val agentStatus: AgentStatus get() = AgentStatus.of(agentStatusRaw)

    val displayName: String get() = displayAgent ?: agent ?: name ?: paneId

    /** A short description of what the agent is doing, if herdr knows one. */
    val subtitle: String?
        get() = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it != agent && it.isNotBlank() }
            ?: terminalTitle?.takeIf { it.isNotBlank() }

    val statusLabel: String get() = stateLabels[agentStatusRaw] ?: agentStatusRaw
}

/**
 * Where an agent keeps its conversation, as its herdr integration reports it: a transcript file
 * path, or a session id that the agent's own storage layout turns into one.
 */
@Serializable
data class AgentSessionRef(
    val agent: String = "",
    val kind: String = "",
    val value: String = "",
    val source: String = "",
)

@Serializable
data class LayoutRect(val x: Int = 0, val y: Int = 0, val width: Int = 0, val height: Int = 0)

@Serializable
data class LayoutPane(
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean = false,
    val rect: LayoutRect = LayoutRect(),
)

@Serializable
data class TabLayout(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val zoomed: Boolean = false,
    val area: LayoutRect = LayoutRect(),
    @SerialName("focused_pane_id") val focusedPaneId: String = "",
    val panes: List<LayoutPane> = emptyList(),
)

@Serializable
data class SessionSnapshot(
    val version: String = "",
    val protocol: Int = 0,
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val tabs: List<TabInfo> = emptyList(),
    val panes: List<PaneInfo> = emptyList(),
    val layouts: List<TabLayout> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
) {
    fun workspace(id: String): WorkspaceInfo? = workspaces.firstOrNull { it.workspaceId == id }
    fun tab(id: String): TabInfo? = tabs.firstOrNull { it.tabId == id }
    fun pane(id: String): PaneInfo? = panes.firstOrNull { it.paneId == id }
    fun layout(tabId: String): TabLayout? = layouts.firstOrNull { it.tabId == tabId }
    fun paneRect(paneId: String): LayoutRect? =
        layouts.firstNotNullOfOrNull { l -> l.panes.firstOrNull { it.paneId == paneId }?.rect }

    /** "workspace › tab" for a pane, using labels where herdr has them. */
    fun location(workspaceId: String, tabId: String): String {
        val ws = workspace(workspaceId)?.label?.ifBlank { null } ?: workspaceId
        val tab = tab(tabId)?.label?.ifBlank { null } ?: tabId
        return "$ws › $tab"
    }
}

@Serializable
data class PaneRead(
    @SerialName("pane_id") val paneId: String = "",
    val text: String = "",
    val revision: Long = 0,
    val truncated: Boolean = false,
)

@Serializable
data class SessionEntry(
    val name: String,
    val default: Boolean = false,
    val running: Boolean = false,
    @SerialName("socket_path") val socketPath: String = "",
)

@Serializable
data class SavedMachineEntry(
    val id: String = "",
    val label: String = "",
    val target: String = "",
    val session: String = "default",
    val enabled: Boolean = true,
)

@Serializable
data class PongInfo(
    val version: String = "",
    val protocol: Int = 0,
)

internal val HerdrJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}
