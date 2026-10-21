package com.makefunstuff.herdr.core.chat

/** One entry in an agent conversation, as the chat view shows it. */
sealed interface ChatItem {
    val id: String

    /** Something the human typed. [images] counts attached pictures, which the chat only mentions. */
    data class User(override val id: String, val text: String, val images: Int = 0) : ChatItem

    /** The agent's reply text, in Markdown. */
    data class Agent(override val id: String, val text: String) : ChatItem

    /**
     * What the agent thought before it acted or answered, as far as its transcript keeps it: Claude's
     * thinking, Codex's reasoning summary (or the raw reasoning of an open model), pi's thinking from
     * any provider. Never in the transcript when the model keeps it to itself, so it is then not shown.
     */
    data class Reasoning(override val id: String, val text: String) : ChatItem

    /** A tool call or action: [summary] is the one line worth showing (a command, a path, a query). */
    data class Tool(
        override val id: String,
        val name: String,
        val summary: String,
        val input: String? = null,
        val output: String? = null,
        val state: ToolState = ToolState.Running,
    ) : ChatItem

    /** A quiet marker in the timeline: interrupted, compacted, a slash command. */
    data class Notice(override val id: String, val text: String) : ChatItem
}

enum class ToolState { Running, Done, Failed }
