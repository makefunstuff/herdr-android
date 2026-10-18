package com.makefunstuff.herdr.share

/**
 * The launcher's shortcuts (a long press on the app's icon): Needs you with its count, the
 * Coordinator used last, Start work, and the agents whose chats were opened last.
 */
interface Shortcuts {
    /** What the fixed ones say now: how many things [needYou], and [coordinator]'s machine (its label), when one was used. */
    fun update(needYou: Int, coordinator: String?)

    /** [paneId]'s chat on [machineId] was opened: it goes first among the recent agents, named [title] on [machine] (its label). */
    fun opened(machineId: String, paneId: String, title: String, machine: String)

    /** Keeps only the recent agents of [machines] (by id), when one is taken out of the app. */
    fun keep(machines: Set<String>)

    companion object {
        /** How many recent agents the launcher gets, after the three fixed ones. */
        const val RECENT = 2

        /** The shortcut ids of the fixed ones. */
        const val NEEDS_YOU = "needs-you"
        const val COORDINATOR = "coordinator"
        const val START = "start-work"
    }
}

/** Where there is no launcher to tell (the screen previews). */
object NoShortcuts : Shortcuts {
    override fun update(needYou: Int, coordinator: String?) = Unit
    override fun opened(machineId: String, paneId: String, title: String, machine: String) = Unit
    override fun keep(machines: Set<String>) = Unit
}
