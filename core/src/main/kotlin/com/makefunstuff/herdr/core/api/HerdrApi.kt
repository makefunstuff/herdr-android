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

    // Workspaces

    suspend fun workspace(workspaceId: String): WorkspaceInfo =
        HerdrJson.decodeFromJsonElement(connection.call("workspace.get", params("workspace_id" to workspaceId))["workspace"]!!)

    suspend fun renameWorkspace(workspaceId: String, label: String) {
        connection.call("workspace.rename", params("workspace_id" to workspaceId, "label" to label))
    }

    suspend fun closeWorkspace(workspaceId: String) {
        connection.call("workspace.close", params("workspace_id" to workspaceId))
    }

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
        LayoutChange.of(connection.call("pane.zoom", params("pane_id" to paneId, "mode" to mode))["zoom"] as? JsonObject)

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
