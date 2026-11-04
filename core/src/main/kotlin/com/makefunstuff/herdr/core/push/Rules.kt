package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import kotlinx.serialization.Serializable

/**
 * Which notifications reach the phone: a switch per [Alert.Kind] (what finished is off unless you
 * turn it on: a ping for every finished turn teaches you to ignore the ones that matter), and whether
 * they stay silent while you are at one of your machines. A test and a watch you set always come.
 * Plain data, so the rules are tested without a phone.
 */
@Serializable
data class AlertRules(
    /** An agent asks something, a machine runs low. */
    val needsYou: Boolean = true,
    /** An agent finished its turn, an AI quick command is done. */
    val finished: Boolean = false,
    /** A quick command failed. */
    val wentWrong: Boolean = true,
    /** An AI provider near (80%, 95%) or at a limit. */
    val limits: Boolean = true,
    /** No sound or pop-up while you are at a machine ([Presence]): you see it there. */
    val holdAtMachine: Boolean = true,
) {
    fun allows(alert: Alert): Boolean = when (alert.kind) {
        Alert.Kind.NeedsYou -> needsYou
        Alert.Kind.Finished -> finished
        Alert.Kind.WentWrong -> wentWrong
        Alert.Kind.Limits -> limits
        Alert.Kind.Test, Alert.Kind.Watch -> true
    }

    companion object {
        /** Everything on, nothing held: where no rules apply. */
        val ALL = AlertRules(needsYou = true, finished = true, wentWrong = true, limits = true, holdAtMachine = false)
    }

    /** As the settings show it: "Needs you and Went wrong", "Only your watches", "Needs you, Finished and Went wrong". */
    val label: String get() {
        val on = listOfNotNull("Needs you".takeIf { needsYou }, "Finished".takeIf { finished }, "Went wrong".takeIf { wentWrong }, "Limits".takeIf { limits })
        return when (on.size) {
            0 -> "Only your watches"
            1, 2 -> on.joinToString(" and ")
            else -> on.dropLast(1).joinToString(", ") + " and " + on.last()
        }
    }
}

/**
 * What a machine says about the person at it, measured as it sent a push (or as the app asked):
 * how long ago someone last typed in a herdr client there (or touched herdr-push's presence file),
 * and whether a desktop session there is unlocked. Null: it couldn't tell (no client, no desktop).
 */
@Serializable
data class Presence(val typedAgo: Long? = null, val unlocked: Boolean? = null)

/**
 * Where the person was last seen at a machine, from what each machine said and when: they are "at
 * the machine" while one typed in herdr in the last two minutes, or said so of its unlocked screen in
 * the last two minutes. Any machine counts, as you are at your desk then, whichever one pings.
 */
class PresenceBook {
    private data class Seen(val label: String, val at: Long, val typedAt: Long?, val unlocked: Boolean)

    private val seen = HashMap<String, Seen>()

    /** [machineId] ([label]) said [p] at [at] (the phone's clock, ms; a push's own time, when it is older than now). */
    @Synchronized
    fun record(machineId: String, label: String, p: Presence, at: Long) {
        seen[machineId] = Seen(label, at, p.typedAgo?.let { at - it.coerceAtLeast(0) * 1000 }, p.unlocked == true)
    }

    /** Whether the person is at a machine at [now], and which: its label, or null when at none. */
    @Synchronized
    fun where(now: Long): String? = seen.values
        .filter { s -> (s.typedAt != null && now - s.typedAt in -FUTURE_MS..WINDOW_MS) || (s.unlocked && now - s.at in -FUTURE_MS..WINDOW_MS) }
        .maxByOrNull { maxOf(it.typedAt ?: 0, if (it.unlocked) it.at else 0) }
        ?.label

    fun present(now: Long): Boolean = where(now) != null

    companion object {
        /** Typed this recently, or a screen unlocked this recently: at the machine. */
        const val WINDOW_MS = 2L * 60 * 1000
        /** A machine's clock a little ahead of the phone's still counts. */
        private const val FUTURE_MS = 30_000L
    }
}

/**
 * A one-shot watch: "tell me when claude finishes". Set from an agent's long-press sheet; it fires once,
 * with a notification, and is removed. It fires also when the agent is gone instead, so you hear how it
 * ended either way.
 */
@Serializable
data class Watch(
    val machineId: String,
    val what: What,
    /** The agent watched. */
    val paneId: String = "",
    /** As the sheet named it: the agent's name. */
    val name: String,
    /** The agent's state change when the watch was set: a finish is a later one. */
    val seq: Long = 0,
    /** When it was set (ms). */
    val at: Long = 0,
) {
    enum class What { AgentDone }

    /** One watch per thing and question: setting it again replaces it. */
    val id: String get() = listOf(machineId, what.name, paneId).joinToString(":")

    /** As the settings and the sheet say it: "When claude finishes". */
    val label: String get() = when (what) {
        What.AgentDone -> "When $name finishes"
    }

    val tag: String get() = "watch:$id"
}

/** A watch that ended: its notification. */
data class WatchFired(val watch: Watch, val alert: Alert?)

/** When a [Watch] fires, from what a push, a look or the open app saw. */
object Watches {
    /** The watches an agent's sheet offers: when it finishes, while it works or asks. */
    fun offers(machineId: String, a: AgentInfo, now: Long = 0): List<Watch> =
        if (a.agentStatus == AgentStatus.Working || a.agentStatus == AgentStatus.Blocked) {
            listOf(Watch(machineId, Watch.What.AgentDone, paneId = a.paneId, name = name(a), seq = a.stateChangeSeq, at = now))
        } else emptyList()

    /** What [events] (one push from [machineId]) end of [watches]. */
    fun events(watches: List<Watch>, machineId: String, label: String, events: List<PushEvent>): List<WatchFired> {
        val mine = watches.filter { it.machineId == machineId }
        if (mine.isEmpty()) return emptyList()
        val fired = LinkedHashMap<String, WatchFired>()
        for (e in events) {
            when (e) {
                is PushEvent.Agent -> mine.filter { it.paneId == e.agent.paneId }.forEach { w ->
                    agent(w, e.agent.takeIf { it.agentStatusRaw != "gone" }, label)?.let { fired.putIfAbsent(w.id, it) }
                }
                else -> {}
            }
        }
        return fired.values.toList()
    }

    /** What a whole look at [machineId] ends: every agent there ([agents]; one not among them is gone). */
    fun look(watches: List<Watch>, machineId: String, label: String, agents: List<AgentInfo>): List<WatchFired> =
        watches.filter { it.machineId == machineId }.mapNotNull { w -> agent(w, agents.firstOrNull { it.paneId == w.paneId }, label) }

    /** [w] on agent [a] as it is now (null: its pane is gone); null while it still works or asks. */
    fun agent(w: Watch, a: AgentInfo?, label: String): WatchFired? {
        if (w.what != Watch.What.AgentDone) return null
        val base = Alert(
            tag = w.tag, sig = "", kind = Alert.Kind.Watch, machineId = w.machineId,
            title = "", text = "", paneId = w.paneId, agentName = a?.let(::name) ?: w.name,
        )
        if (a == null) {
            return WatchFired(w, base.copy(title = "${w.name} is gone", text = "Its pane closed before it finished", detail = "Its pane closed before it finished\n$label", paneId = ""))
        }
        val finished = (a.agentStatus == AgentStatus.Done || a.agentStatus == AgentStatus.Idle) && (w.seq == 0L || a.stateChangeSeq != w.seq)
        if (!finished) return null
        val did = a.subtitle ?: "Ready for the next prompt"
        return WatchFired(
            w,
            base.copy(
                title = "${name(a)} finished", text = did, detail = "$did\n$label",
                actions = listOf(AlertAction(AlertAction.Type.ReplyAgent, "Reply", hint = "Next prompt for ${name(a)}")),
            ),
        )
    }

    private fun name(a: AgentInfo) = a.name?.takeIf { it.isNotBlank() } ?: a.displayName
}
