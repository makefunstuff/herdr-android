package com.makefunstuff.herdr.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A tab's split tree, as `layout.export` gives it and `layout.apply` takes it. Splits divide
 * their area between [Split.first] and [Split.second]: side by side for "right", stacked for "down".
 */
@Serializable
sealed interface LayoutNode {
    @Serializable
    @SerialName("pane")
    data class Pane(
        @SerialName("pane_id") val paneId: String? = null,
        val label: String? = null,
        val cwd: String? = null,
        val command: List<String>? = null,
        val env: Map<String, String> = emptyMap(),
    ) : LayoutNode

    @Serializable
    @SerialName("split")
    data class Split(
        val direction: String,
        val ratio: Float,
        val first: LayoutNode,
        val second: LayoutNode,
    ) : LayoutNode {
        /** True when the children sit side by side. */
        val sideBySide: Boolean get() = direction == "right"
    }

    /** The panes in reading order. */
    fun panes(): List<Pane> = when (this) {
        is Pane -> listOf(this)
        is Split -> first.panes() + second.panes()
    }

    /**
     * The same tree without live pane ids, for saving and applying elsewhere: herdr starts new
     * panes for it. Commands are kept, so a saved layout starts the same programs.
     */
    fun template(): LayoutNode = when (this) {
        is Pane -> copy(paneId = null)
        is Split -> copy(first = first.template(), second = second.template())
    }

    /** The node at [path] (false = first child, true = second), or null. */
    fun at(path: List<Boolean>): LayoutNode? {
        var node: LayoutNode = this
        for (second in path) node = (node as? Split)?.let { if (second) it.second else it.first } ?: return null
        return node
    }

    /** This tree with the split at [path] set to [ratio]. */
    fun withRatio(path: List<Boolean>, ratio: Float): LayoutNode {
        if (this !is Split) return this
        if (path.isEmpty()) return copy(ratio = ratio)
        val rest = path.drop(1)
        return if (path[0]) copy(second = second.withRatio(rest, ratio)) else copy(first = first.withRatio(rest, ratio))
    }

    /** The path to the pane with [paneId], or null. */
    fun pathTo(paneId: String): List<Boolean>? = when (this) {
        is Pane -> if (this.paneId == paneId) emptyList() else null
        is Split -> first.pathTo(paneId)?.let { listOf(false) + it } ?: second.pathTo(paneId)?.let { listOf(true) + it }
    }

    /** Every split in reading order, with the path `layout.set_split_ratio` takes. */
    fun splits(): List<SplitRef> {
        val out = mutableListOf<SplitRef>()
        fun walk(node: LayoutNode, path: List<Boolean>) {
            if (node !is Split) return
            out += SplitRef(path, node.direction, node.ratio)
            walk(node.first, path + false)
            walk(node.second, path + true)
        }
        walk(this, emptyList())
        return out
    }
}

/** One split of a layout tree, addressed by its [path] (false = first child, true = second). */
data class SplitRef(val path: List<Boolean>, val direction: String, val ratio: Float) {
    val sideBySide: Boolean get() = direction == "right"
}

/**
 * JSON for [LayoutNode] templates, in the same shape as `layout.export` and `layout.apply`.
 * The phone uses it to save layouts, copy them to the clipboard and read them back.
 */
object LayoutTemplates {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        coerceInputValues = true
    }

    fun encode(node: LayoutNode): String = json.encodeToString(LayoutNode.serializer(), node)

    fun decode(text: String): LayoutNode = json.decodeFromString(LayoutNode.serializer(), text)
}

@Serializable
data class LayoutDescription(
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    val zoomed: Boolean = false,
    @SerialName("focused_pane_id") val focusedPaneId: String = "",
    val root: LayoutNode,
)

/** Which way `pane.resize` moves the pane's split edge. */
enum class ResizeDirection(val wire: String) { Left("left"), Right("right"), Up("up"), Down("down") }

@Serializable
data class PaneResizeResult(
    val changed: Boolean = false,
    /** Why nothing changed, such as "unchanged" when no split edge can move that way. */
    val reason: String? = null,
    @SerialName("pane_id") val paneId: String = "",
    val layout: TabLayout? = null,
)
