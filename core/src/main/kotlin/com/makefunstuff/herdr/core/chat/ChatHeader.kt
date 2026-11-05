package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.shepherdr.V2Chat

/**
 * Whose a chat is, as its header says it in 3.1's way (`docs/parity/header.md`: "Assistant · Claude Code" over its
 * folder, `~` for home): one line of who, space, machine (when there are several) and folder or branch.
 */
object ChatHeader {
    private val HOME = Regex("""^(/home/[^/]+|/Users/[^/]+|/root)(?=/|$)""")

    /** [path] as 3.1 shows a folder: the home folder as `~`; null for none. */
    fun folder(path: String?): String? = path?.trim()?.ifEmpty { null }?.let { HOME.replace(it, "~") }

    /** The parts that say something, each once, as one line. */
    fun line(vararg parts: String?): String =
        parts.mapNotNull { it?.trim()?.ifEmpty { null } }.distinctBy { it.lowercase() }.joinToString(" · ")

    /** [machine] when the phone has more than one, so a header names it only where it tells chats apart. */
    fun machine(machine: String, machines: Int): String? = machine.takeIf { machines > 1 }

    /** A task agent's header title: its ID and title (`HERD-30 · Chats show…`). */
    fun task(name: String, title: String): String = line(name, title)
}

/**
 * Which herdr-core agent a Shepherdr chat belongs to, and when the app reads that agent's own transcript in its
 * place: Shepherdr answers "Can't find this conversation" (`notice: none`) for agents whose log it misses, while
 * the app finds it from the agent's process (seen on 3.1.2 for a task's worker and Assistants started before the
 * desktop app showed them).
 */
object ChatOwner {
    /** The live agent working on task [taskId] (`HERD-30`; `vm:HERD-8` on a machine hc added); not its reviewer. */
    fun taskAgent(agents: List<AgentInfo>, taskId: String): AgentInfo? = agents.firstOrNull { it.shepherdrTask == taskId }

    /** The live Assistant of space [spaceId]. */
    fun assistant(agents: List<AgentInfo>, spaceId: String): AgentInfo? = agents.firstOrNull { it.shepherdrSpace == spaceId }

    /** Show [agent]'s own transcript instead of [chat]: Shepherdr found no log, and the app knows where it is. */
    fun readsOwn(chat: V2Chat?, agent: AgentInfo?): Boolean =
        chat != null && chat.notice == "none" && agent?.agentSession?.value?.isNotEmpty() == true
}
