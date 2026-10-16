package com.makefunstuff.herdr.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** What runs in a pane: its shell and the foreground process group (pane.process_info). */
@Serializable
data class PaneProcessInfo(
    @SerialName("pane_id") val paneId: String = "",
    @SerialName("shell_pid") val shellPid: Long? = null,
    @SerialName("foreground_process_group_id") val foregroundGroupId: Long? = null,
    @SerialName("foreground_processes") val processes: List<PaneProcess> = emptyList(),
    val tty: String? = null,
)

@Serializable
data class PaneProcess(
    val pid: Long = 0,
    val name: String = "",
    val argv: List<String>? = null,
    val cmdline: String? = null,
    val cwd: String? = null,
) {
    val command: String get() = cmdline?.ifBlank { null } ?: argv?.joinToString(" ")?.ifBlank { null } ?: name
}

/** Where pane.move puts a pane. */
sealed interface PaneDestination {
    /** Into an existing tab, split next to [targetPaneId] (or the tab's focused pane). */
    data class Tab(val tabId: String, val split: String = "right", val targetPaneId: String? = null) : PaneDestination

    /** Into a new tab of [workspaceId] (the pane's own workspace when null). */
    data class NewTab(val workspaceId: String? = null, val label: String? = null) : PaneDestination

    data class NewWorkspace(val label: String? = null) : PaneDestination

    fun toJson(): JsonObject = when (this) {
        is Tab -> params("type" to "tab", "tab_id" to tabId, "split" to split, "target_pane_id" to targetPaneId)
        is NewTab -> params("type" to "new_tab", "workspace_id" to workspaceId, "label" to label)
        is NewWorkspace -> params("type" to "new_workspace", "label" to label)
    }
}

/**
 * The answer to a layout call that herdr may decline without an error (pane.move, swap, zoom,
 * focus_direction): whether anything changed, and if not, why in plain words.
 */
data class LayoutChange(val changed: Boolean, val reason: String?, val zoomed: Boolean? = null) {
    /** A sentence for the person when nothing happened, null when the call did what was asked. */
    val refusal: String?
        get() = if (changed) null else when (reason) {
            null -> "Nothing to change."
            "same_tab" -> "The pane is already in that tab."
            "zoomed_tab" -> "That tab has a zoomed pane. Unzoom it first."
            "no_neighbor" -> "There's no pane in that direction."
            "same_pane" -> "That's the same pane."
            "not_found" -> "That pane is gone."
            "cross_tab" -> "Panes can only swap within one tab."
            "single_pane" -> "The tab has only one pane, so there's nothing to zoom."
            "already_zoomed" -> "The pane is already zoomed."
            "already_unzoomed" -> "The pane isn't zoomed."
            else -> "herdr didn't change anything (${reason.replace('_', ' ')})."
        }

    companion object {
        internal fun of(obj: JsonObject?): LayoutChange = LayoutChange(
            changed = (obj?.get("changed") as? JsonPrimitive)?.booleanOrNull ?: true,
            reason = (obj?.get("reason") as? JsonPrimitive)?.contentOrNull,
            zoomed = (obj?.get("zoomed") as? JsonPrimitive)?.booleanOrNull,
        )
    }
}

/** The answer to notification.show. */
@Serializable
data class NotificationResult(val shown: Boolean = false, val reason: String = "") {
    val message: String
        get() = when {
            shown -> "Shown in herdr."
            reason == "disabled" -> "herdr has notifications turned off (ui.toast.delivery in its config)."
            reason == "rate_limited" -> "herdr is showing notifications too fast; try again in a moment."
            reason == "no_foreground_client" -> "No herdr window is open on the machine to show it."
            reason == "busy" -> "herdr is busy and didn't show it."
            else -> "herdr didn't show it${if (reason.isNotEmpty()) " (${reason.replace('_', ' ')})" else ""}."
        }
}

/**
 * agent.explain: how herdr decided an agent's state from its screen and its integration. Kept
 * mostly raw, since its shape is herdr's diagnostics; [summary] and [rules] are what the app shows.
 */
data class AgentExplanation(val raw: JsonObject) {
    private fun str(key: String) = (raw[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun bool(key: String) = (raw[key] as? JsonPrimitive)?.booleanOrNull == true

    val state: String get() = str("state") ?: "unknown"
    val agent: String? get() = str("agent")
    val matchedRule: String? get() = str("matched_rule")
    val fallbackReason: String? get() = str("fallback_reason")
    val warning: String? get() = str("warning")
    val manifest: String?
        get() = listOfNotNull(str("manifest_version"), str("manifest_source")).joinToString(" · ").ifEmpty { null }

    data class Rule(val id: String, val state: String, val matched: Boolean, val region: String, val preview: String)

    val rules: List<Rule>
        get() = (raw["evaluated_rules"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
            Rule(
                id = s(o, "id"),
                state = s(o, "state"),
                matched = (o["matched"] as? JsonPrimitive)?.booleanOrNull == true,
                region = s(o, "region"),
                preview = s(o["evidence"] as? JsonObject, "region_preview"),
            )
        }

    /** One or two sentences on why the agent is in its state. */
    val summary: String
        get() = buildString {
            append("herdr reads ${agent ?: "this agent"} as ").append(state).append(". ")
            val rule = matchedRule
            when {
                rule != null -> append("Its screen matched the rule \"${rule.replace('_', ' ')}\".")
                bool("screen_detection_skipped") -> append("Screen detection is skipped; the state comes from the agent's integration.")
                fallbackReason != null -> append("No screen rule matched, so herdr fell back (${fallbackReason!!.replace('_', ' ')}).")
                else -> append("No screen rule matched.")
            }
            val seen = listOfNotNull(
                "a question".takeIf { bool("visible_blocker") },
                "work in progress".takeIf { bool("visible_working") },
                "an idle prompt".takeIf { bool("visible_idle") },
            )
            if (seen.isNotEmpty()) append(" The screen shows ").append(seen.joinToString(" and ")).append('.')
            str("skipped_update_reason")?.let { append(" The last update was skipped (${it.replace('_', ' ')}).") }
        }.trim()

    companion object {
        internal fun of(e: JsonElement?): AgentExplanation = AgentExplanation(e as? JsonObject ?: JsonObject(emptyMap()))
    }
}

/**
 * The insert_index that makes [id] end up at position [to] of [order], or null when it is already
 * there. herdr's workspace.move and tab.move insert before the item at insert_index in the current
 * order (0..size), so moving later needs one past the target.
 */
fun moveIndex(order: List<String>, id: String, to: Int): Int? {
    val from = order.indexOf(id)
    if (from < 0) return null
    val target = to.coerceIn(0, order.size - 1)
    return when {
        target == from -> null
        target > from -> target + 1
        else -> target
    }
}
