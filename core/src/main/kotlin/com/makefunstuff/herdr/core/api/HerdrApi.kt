package com.makefunstuff.herdr.core.api

import kotlinx.serialization.json.JsonArray
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

    suspend fun closeWorkspace(workspaceId: String) {
        connection.call("workspace.close", params("workspace_id" to workspaceId))
    }

    /** Names the repository by workspace or folder; trust is only sent when granted. */
    private fun repoParams(workspaceId: String?, cwd: String?, trust: Boolean, vararg more: Pair<String, Any?>): JsonObject =
        params("workspace_id" to workspaceId, "cwd" to cwd, "trust_repository" to trust.takeIf { it }, *more)

    private inline fun <reified T> decode(obj: JsonObject): T = HerdrJson.decodeFromJsonElement(obj)

    companion object {
        /** Lifecycle events that change what the app shows. */
        val LIFECYCLE_EVENTS = listOf(
            "workspace.created", "workspace.updated", "workspace.renamed", "workspace.closed",
            "workspace.reordered", "worktree.created", "worktree.opened", "worktree.removed", "tab.created", "tab.closed", "tab.renamed", "tab.moved",
            "pane.created", "pane.closed", "pane.updated", "pane.moved", "pane.exited",
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
