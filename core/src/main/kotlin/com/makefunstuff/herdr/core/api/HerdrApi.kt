package com.makefunstuff.herdr.core.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Typed calls on top of an [ApiCaller]. Method names follow herdr's socket API. */
class HerdrApi(private val connection: ApiCaller) {

    suspend fun ping(): PongInfo = decode(connection.call("ping"))

    suspend fun snapshot(): SessionSnapshot =
        HerdrJson.decodeFromJsonElement(connection.call("session.snapshot")["snapshot"] ?: JsonObject(emptyMap()))

    suspend fun agents(): List<AgentInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("agent.list")["agents"] ?: JsonArray(emptyList()))

    suspend fun readPane(paneId: String, source: String = "recent", lines: Int = 80): PaneRead =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.read", params("pane_id" to paneId, "source" to source, "lines" to lines))["read"]
                ?: JsonObject(emptyMap()),
        )

    /** Types [text] into an agent and submits it. */
    suspend fun prompt(target: String, text: String) {
        connection.call("agent.prompt", params("target" to target, "text" to text))
    }

    /** Sends herdr key-combo strings such as `enter`, `esc`, `ctrl+c`, `1`. */
    suspend fun sendKeys(paneId: String, keys: List<String>) {
        connection.call("pane.send_keys", params("pane_id" to paneId, "keys" to JsonArray(keys.map(::JsonPrimitive))))
    }

    suspend fun sendText(paneId: String, text: String) {
        connection.call("pane.send_text", params("pane_id" to paneId, "text" to text))
    }

    suspend fun createWorkspace(label: String?, cwd: String?) {
        connection.call("workspace.create", params("label" to label, "cwd" to cwd, "focus" to false))
    }

    suspend fun createTab(workspaceId: String, label: String?) {
        connection.call("tab.create", params("workspace_id" to workspaceId, "label" to label, "focus" to false))
    }

    suspend fun splitPane(paneId: String, direction: String) {
        connection.call("pane.split", params("target_pane_id" to paneId, "direction" to direction, "focus" to false))
    }

    suspend fun closePane(paneId: String) {
        connection.call("pane.close", params("pane_id" to paneId))
    }

    /** Starts an agent of [kind] (claude, codex, pi…) named [name] in a new split next to [paneId]. */
    suspend fun startAgent(name: String, kind: String, paneId: String) {
        connection.call(
            "agent.start",
            params("name" to name, "kind" to kind, "pane_id" to paneId),
            timeoutMs = 60_000,
        )
    }

    /**
     * Lists the checkouts of the repository that a workspace ([workspaceId]) or a folder ([cwd]) is in.
     * [trustRepository] grants Git trust for this one request; only after the user has vouched for the repository.
     */
    suspend fun worktrees(workspaceId: String? = null, cwd: String? = null, trustRepository: Boolean = false): WorktreeList =
        decode(connection.call("worktree.list", repoParams(workspaceId, cwd, trustRepository)))

    /**
     * Creates a worktree of the repository a workspace or folder is in and opens it as a new workspace.
     * Leaving [branch] out lets herdr pick a name; [base] is the ref the new branch starts from.
     */
    suspend fun createWorktree(
        workspaceId: String? = null,
        cwd: String? = null,
        branch: String? = null,
        base: String? = null,
        path: String? = null,
        label: String? = null,
        trustRepository: Boolean = false,
    ): WorktreeOpened = decode(
        connection.call(
            "worktree.create",
            repoParams(workspaceId, cwd, trustRepository, "branch" to branch, "base" to base, "path" to path, "label" to label, "focus" to false),
            timeoutMs = 60_000,
        ),
    )

    /** Opens an existing worktree, by [path] or [branch], as a workspace (or finds the one it's already open in). */
    suspend fun openWorktree(
        workspaceId: String? = null,
        cwd: String? = null,
        path: String? = null,
        branch: String? = null,
        label: String? = null,
        trustRepository: Boolean = false,
    ): WorktreeOpened = decode(
        connection.call(
            "worktree.open",
            repoParams(workspaceId, cwd, trustRepository, "path" to path, "branch" to branch, "label" to label, "focus" to false),
            timeoutMs = 60_000,
        ),
    )

    /**
     * Deletes the linked worktree checkout that [workspaceId] is open on, and closes the workspace.
     * herdr refuses a checkout with changes (`dirty_worktree_requires_force`) unless [force].
     */
    suspend fun removeWorktree(workspaceId: String, force: Boolean = false, trustRepository: Boolean = false): WorktreeRemoved =
        decode(
            connection.call(
                "worktree.remove",
                params("workspace_id" to workspaceId, "force" to force, "trust_repository" to trustRepository.takeIf { it }),
                timeoutMs = 60_000,
            ),
        )
    // Workspaces

    suspend fun workspace(workspaceId: String): WorkspaceInfo =
        HerdrJson.decodeFromJsonElement(connection.call("workspace.get", params("workspace_id" to workspaceId))["workspace"]!!)

    suspend fun renameWorkspace(workspaceId: String, label: String) {
        connection.call("workspace.rename", params("workspace_id" to workspaceId, "label" to label))
    }

    suspend fun closeWorkspace(workspaceId: String) {
        connection.call("workspace.close", params("workspace_id" to workspaceId))
    }

    /** Names the repository by workspace or folder; trust is only sent when granted. */
    private fun repoParams(workspaceId: String?, cwd: String?, trust: Boolean, vararg more: Pair<String, Any?>): JsonObject =
        params("workspace_id" to workspaceId, "cwd" to cwd, "trust_repository" to trust.takeIf { it }, *more)
    suspend fun focusWorkspace(workspaceId: String) {
        connection.call("workspace.focus", params("workspace_id" to workspaceId))
    }

    /** Moves a workspace to [insertIndex]: before the workspace now at that index, or last at the list's size. */
    suspend fun moveWorkspace(workspaceId: String, insertIndex: Int) {
        connection.call("workspace.move", params("workspace_id" to workspaceId, "insert_index" to insertIndex))
    }

    /** Moves [workspaceIds] together, in that order, before [beforeWorkspaceId] (to the end when null). */
    suspend fun moveWorkspaces(workspaceIds: List<String>, beforeWorkspaceId: String?) {
        connection.call(
            "workspace.move_block",
            params("workspace_ids" to JsonArray(workspaceIds.map(::JsonPrimitive)), "before_workspace_id" to beforeWorkspaceId),
        )
    }

    // Tabs

    suspend fun renameTab(tabId: String, label: String) {
        connection.call("tab.rename", params("tab_id" to tabId, "label" to label))
    }

    suspend fun closeTab(tabId: String) {
        connection.call("tab.close", params("tab_id" to tabId))
    }

    suspend fun focusTab(tabId: String) {
        connection.call("tab.focus", params("tab_id" to tabId))
    }

    /** Moves a tab within its workspace; [insertIndex] works as in [moveWorkspace]. */
    suspend fun moveTab(tabId: String, insertIndex: Int) {
        connection.call("tab.move", params("tab_id" to tabId, "insert_index" to insertIndex))
    }

    // Panes

    /** Names a pane; a null or blank [label] clears the name. */
    suspend fun renamePane(paneId: String, label: String?) {
        connection.call("pane.rename", params("pane_id" to paneId, "label" to label?.ifBlank { null }))
    }

    suspend fun movePane(paneId: String, destination: PaneDestination): LayoutChange =
        LayoutChange.of(
            connection.call("pane.move", params("pane_id" to paneId, "destination" to destination.toJson(), "focus" to false))["move_result"] as? JsonObject,
        )

    /** Swaps a pane with [targetPaneId], or with its neighbour in [direction] (left, right, up, down). */
    suspend fun swapPane(paneId: String, targetPaneId: String? = null, direction: String? = null): LayoutChange =
        LayoutChange.of(
            connection.call(
                "pane.swap",
                // herdr takes either pane_id + direction, or source_pane_id + target_pane_id.
                if (direction != null) params("pane_id" to paneId, "direction" to direction)
                else params("source_pane_id" to paneId, "target_pane_id" to targetPaneId),
            )["swap"] as? JsonObject,
        )

    /** [mode] is toggle, on or off. */
    suspend fun zoomPane(paneId: String, mode: String = "toggle"): LayoutChange =
        LayoutChange.of(
            connection.call("pane.zoom", params("pane_id" to paneId, "mode" to mode))["zoom"] as? JsonObject,
            changedKey = "zoom_changed",
        )

    suspend fun focusPane(paneId: String) {
        connection.call("pane.focus", params("pane_id" to paneId))
    }

    suspend fun focusDirection(paneId: String, direction: String): LayoutChange =
        LayoutChange.of(
            connection.call("pane.focus_direction", params("pane_id" to paneId, "direction" to direction))["focus"] as? JsonObject,
        )

    /** Clears the pane's screen and scrollback. */
    suspend fun clearPane(paneId: String) {
        connection.call("pane.clear", params("pane_id" to paneId))
    }

    suspend fun processInfo(paneId: String): PaneProcessInfo =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.process_info", params("pane_id" to paneId))["process_info"] ?: JsonObject(emptyMap()),
        )

    // Agents. [target] is an agent name, a pane id or a terminal id.

    suspend fun agent(target: String): AgentInfo =
        HerdrJson.decodeFromJsonElement(connection.call("agent.get", params("target" to target))["agent"]!!)

    /** Names an agent; a null or blank [name] clears it. */
    suspend fun renameAgent(target: String, name: String?) {
        connection.call("agent.rename", params("target" to target, "name" to (name?.ifBlank { null } ?: JsonNull)))
    }

    suspend fun focusAgent(target: String) {
        connection.call("agent.focus", params("target" to target))
    }

    suspend fun explainAgent(target: String): AgentExplanation =
        AgentExplanation.of(connection.call("agent.explain", params("target" to target))["explain"])

    /** Sends key combos to a named agent (see [sendKeys] for panes). */
    suspend fun agentSendKeys(target: String, keys: List<String>) {
        connection.call("agent.send_keys", params("target" to target, "keys" to JsonArray(keys.map(::JsonPrimitive))))
    }

    // Notifications

    /** Shows a notification in herdr on the machine. [sound] is none, done or request. */
    suspend fun showNotification(title: String, body: String? = null, sound: String = "none"): NotificationResult =
        decode(connection.call("notification.show", params("title" to title, "body" to body?.ifBlank { null }, "sound" to sound)))

    private inline fun <reified T> decode(obj: JsonObject): T = HerdrJson.decodeFromJsonElement(obj)

    companion object {
        /** Lifecycle events that change what the app shows. */
        val LIFECYCLE_EVENTS = listOf(
            "workspace.created", "workspace.updated", "workspace.renamed", "workspace.closed",
                        "worktree.created", "worktree.opened", "worktree.removed",
            "workspace.reordered", "workspace.moved", "workspace.focused", "workspace.metadata_updated",
            "tab.created", "tab.closed", "tab.renamed", "tab.moved", "tab.focused",
            "pane.created", "pane.closed", "pane.updated", "pane.moved", "pane.focused", "pane.exited",
            "pane.agent_detected", "layout.updated",
        )

        fun subscriptionParams(paneIds: List<String>): JsonObject {
            val entries = LIFECYCLE_EVENTS.map { JsonObject(mapOf("type" to JsonPrimitive(it))) } +
                paneIds.map {
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("pane.agent_status_changed"),
                            "pane_id" to JsonPrimitive(it),
                        ),
                    )
                }
            return params("subscriptions" to JsonArray(entries))
        }
    }
}
