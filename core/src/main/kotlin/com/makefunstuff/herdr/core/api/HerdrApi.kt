package com.makefunstuff.herdr.core.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

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

    suspend fun pane(paneId: String): PaneInfo =
        HerdrJson.decodeFromJsonElement(connection.call("pane.get", params("pane_id" to paneId))["pane"] ?: JsonObject(emptyMap()))

    // ---- Scrollback, search, selection, links ------------------------------------------

    /** Scrolls the pane's viewport [offsetFromBottom] lines into its scrollback (0 = live screen). */
    suspend fun scrollPane(paneId: String, offsetFromBottom: Long): PaneInfo =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.scroll", params("pane_id" to paneId, "offset_from_bottom" to offsetFromBottom.coerceAtLeast(0)))["pane"]
                ?: JsonObject(emptyMap()),
        )

    /** Moves a copy-mode [cursor] by [motion]. Also the way to learn the pane's content revision. */
    suspend fun copyMotion(paneId: String, cursor: TextPoint, motion: CopyMotion, contentRevision: Long? = null): CopyMotionResult =
        decode(
            connection.call(
                "pane.copy_motion",
                params("pane_id" to paneId, "cursor" to point(cursor), "motion" to motion.wire, "content_revision" to contentRevision),
            ),
        )

    /** The pane's current content revision, which search and selection pin to. */
    suspend fun contentRevision(paneId: String): Long =
        copyMotion(paneId, TextPoint(0, 0), CopyMotion.LineEnd).contentRevision

    /**
     * Searches the scrollback for [query] from [cursor]. Pass the current match as [previous] to
     * step to the next one. Fails with code `stale_content` when the pane changed since [contentRevision].
     */
    suspend fun copySearch(
        paneId: String,
        query: String,
        backward: Boolean,
        cursor: TextPoint,
        contentRevision: Long,
        previous: TextRange? = null,
    ): CopySearchResult = decode(
        connection.call(
            "pane.copy_search",
            params(
                "pane_id" to paneId,
                "query" to query,
                "direction" to if (backward) "backward" else "forward",
                "cursor" to point(cursor),
                "content_revision" to contentRevision,
                "previous" to previous?.let { HerdrJson.encodeToJsonElement(it) },
            ),
        ),
    )

    /** The text between [anchor] and [cursor], inclusive, as herdr's copy mode would copy it. */
    suspend fun readSelection(paneId: String, anchor: TextPoint, cursor: TextPoint, contentRevision: Long? = null): String {
        val result = connection.call(
            "pane.selection.read",
            params("pane_id" to paneId, "anchor" to point(anchor), "cursor" to point(cursor), "content_revision" to contentRevision),
        )
        return (result["text"] as? JsonPrimitive)?.content ?: ""
    }

    /** The link regions under viewport cell ([viewportRow], [col]); empty when there's no link. */
    suspend fun resolveLink(paneId: String, viewportRow: Int, col: Int): List<LinkRegion> =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.link.resolve", params("pane_id" to paneId, "viewport_row" to viewportRow, "col" to col))["regions"]
                ?: JsonArray(emptyList()),
        )

    /** Activates the link under a viewport cell: a host plugin may handle it, otherwise its URL comes back. */
    suspend fun activateLink(paneId: String, viewportRow: Int, col: Int): LinkActivation =
        decode(connection.call("pane.link.activate", params("pane_id" to paneId, "viewport_row" to viewportRow, "col" to col)))

    // ---- Layout ------------------------------------------------------------------------

    /** Moves the split edge of [paneId] toward [direction] by [amount] (a fraction of the split; herdr's step when null). */
    suspend fun resizePane(paneId: String, direction: ResizeDirection, amount: Float? = null): PaneResizeResult =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.resize", params("pane_id" to paneId, "direction" to direction.wire, "amount" to amount))["resize"]
                ?: JsonObject(emptyMap()),
        )

    /** The pane's tab layout as rectangles: panes and splits. */
    suspend fun paneLayout(paneId: String): TabLayout =
        HerdrJson.decodeFromJsonElement(connection.call("pane.layout", params("pane_id" to paneId))["layout"] ?: JsonObject(emptyMap()))

    /** The tab's split tree. */
    suspend fun exportLayout(tabId: String): LayoutDescription =
        HerdrJson.decodeFromJsonElement(connection.call("layout.export", params("tab_id" to tabId))["layout"] ?: JsonObject(emptyMap()))

    /** Sets the ratio of the split at [path] in [tabId] (false = first child, true = second; empty = the root). */
    suspend fun setSplitRatio(tabId: String, path: List<Boolean>, ratio: Float): LayoutDescription =
        HerdrJson.decodeFromJsonElement(
            connection.call(
                "layout.set_split_ratio",
                params("tab_id" to tabId, "path" to JsonArray(path.map(::JsonPrimitive)), "ratio" to ratio),
            )["layout"] ?: JsonObject(emptyMap()),
        )

    /**
     * Builds [root] as a tab. With [replaceTabId], that tab's panes are closed and the layout
     * takes its place; otherwise it opens as a new tab in [workspaceId] named [label].
     */
    suspend fun applyLayout(
        root: LayoutNode,
        workspaceId: String? = null,
        replaceTabId: String? = null,
        label: String? = null,
    ): LayoutDescription =
        HerdrJson.decodeFromJsonElement(
            connection.call(
                "layout.apply",
                params(
                    "root" to HerdrJson.encodeToJsonElement(root),
                    "workspace_id" to workspaceId,
                    "tab_id" to replaceTabId,
                    "tab_label" to label,
                    "focus" to false,
                ),
                timeoutMs = 60_000,
            )["layout"] ?: JsonObject(emptyMap()),
        )

    private fun point(p: TextPoint) = params("row" to p.row, "col" to p.col)

    private inline fun <reified T> decode(obj: JsonObject): T = HerdrJson.decodeFromJsonElement(obj)

    companion object {
        /** Lifecycle events that change what the app shows. */
        val LIFECYCLE_EVENTS = listOf(
            "workspace.created", "workspace.updated", "workspace.renamed", "workspace.closed",
            "workspace.reordered", "tab.created", "tab.closed", "tab.renamed", "tab.moved",
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
