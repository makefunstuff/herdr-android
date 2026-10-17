package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.health.Disk
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.Sizes
import kotlinx.serialization.Serializable

/**
 * A notification on the phone, worked out from what a machine said (a push, or the app's own look
 * while it is closed): words, where a tap goes, and the buttons. The app turns it into an Android
 * notification; everything here is plain data, so the rules are tested without a phone.
 */
@Serializable
data class Alert(
    /** One notification per agent or task: a newer state replaces the older one under the same tag. */
    val tag: String,
    /** The state it shows; the same signature again is not shown twice. Empty: always shown. */
    val sig: String,
    val kind: Kind,
    val machineId: String,
    val title: String,
    val text: String,
    /** The longer text when the notification is opened up. */
    val detail: String = "",
    /** What a tap opens: the agent's chat, or the task on the board. */
    val paneId: String = "",
    val taskId: String = "",
    /** The task's short key (`AND-7`), or its id where it has none. */
    val taskName: String = "",
    val actions: List<AlertAction> = emptyList(),
    /** A tap opens the machine's health screen (a machine low on RAM or disk). */
    val health: Boolean = false,
) {
    enum class Kind { NeedsYou, Finished, Test }
}

/** A notification's button. */
@Serializable
data class AlertAction(
    val type: Type,
    val label: String,
    /** [Type.Choose]: the menu option to pick, and what the screen must still show for it to be picked. */
    val option: Int = -1,
    val optionLabel: String = "",
    val question: String = "",
    /** [Type.Board]: the board command, a [BoardAction] name. */
    val board: String = "",
    /** The reply box's hint, for the reply types. */
    val hint: String = "",
) {
    enum class Type {
        /** Picks an option of the agent's question. */
        Choose,
        /** Words to the agent: its answer when blocked, its next prompt when done. */
        ReplyAgent,
        /** A board command about the task. */
        Board,
        /** Words to the task's agent (`tell`). */
        TellTask,
        /** Words to the board's Coordinator, about the task. */
        AskCoordinator,
    }

    val reply: Boolean get() = type == Type.ReplyAgent || type == Type.TellTask || type == Type.AskCoordinator
    val boardAction: BoardAction? get() = BoardAction.entries.firstOrNull { it.name == board }
}

/** What an event does to the notifications: show (or replace) one, or take one away. */
sealed interface AlertChange {
    val tag: String

    data class Show(val alert: Alert) : AlertChange {
        override val tag: String get() = alert.tag
    }

    data class Clear(override val tag: String) : AlertChange
}

object Alerts {
    fun agentTag(machineId: String, paneId: String) = "agent:$machineId:$paneId"
    fun taskTag(machineId: String, taskId: String) = "task:$machineId:$taskId"
    fun testTag(machineId: String) = "test:$machineId"
    fun healthTag(machineId: String) = "health:$machineId"

    /** The notifications [events] call for; what runs ([PushEvent.Live]) is a Live Update instead (LiveItems). */
    fun all(machineId: String, machineLabel: String, events: List<PushEvent>, host: String = ""): List<AlertChange> =
        events.filter { it !is PushEvent.Live }.map { of(machineId, machineLabel, it, host) }

    fun of(machineId: String, machineLabel: String, event: PushEvent, host: String = ""): AlertChange = when (event) {
        is PushEvent.Live -> throw IllegalArgumentException("What runs is a Live Update, not an alert")
        is PushEvent.Agent -> agent(machineId, machineLabel, event)
        is PushEvent.Task -> task(machineId, machineLabel, event.task)
        is PushEvent.Health -> health(machineId, machineLabel, event.report)
        PushEvent.Test -> AlertChange.Show(
            Alert(
                tag = testTag(machineId),
                sig = "",
                kind = Alert.Kind.Test,
                machineId = machineId,
                title = "Notifications from $machineLabel work",
                text = if (host.isNotEmpty()) "herdr-push on $host reached this phone." else "herdr-push reached this phone.",
            ),
        )
    }

    private fun agent(machineId: String, machineLabel: String, e: PushEvent.Agent): AlertChange {
        val a = e.agent
        val tag = agentTag(machineId, a.paneId)
        val name = a.name?.takeIf { it.isNotBlank() } ?: a.displayName
        val where = listOf(machineLabel, e.where).filter { it.isNotBlank() }.joinToString(" · ")
        return when (a.agentStatus) {
            AgentStatus.Blocked -> {
                val prompt = e.screen?.let { BlockedPrompt.parse(it) }
                val question = prompt?.question ?: "Waiting for your answer"
                AlertChange.Show(
                    Alert(
                        tag = tag,
                        sig = "blocked#${a.stateChangeSeq}",
                        kind = Alert.Kind.NeedsYou,
                        machineId = machineId,
                        title = "$name needs you",
                        text = question,
                        detail = (listOf(question) + prompt?.details.orEmpty().take(4) + where).joinToString("\n"),
                        paneId = a.paneId,
                        actions = choices(prompt) + AlertAction(AlertAction.Type.ReplyAgent, "Reply", hint = "Answer $name"),
                    ),
                )
            }
            AgentStatus.Done -> {
                val did = a.subtitle ?: "Ready for the next prompt"
                AlertChange.Show(
                    Alert(
                        tag = tag,
                        sig = "done#${a.stateChangeSeq}",
                        kind = Alert.Kind.Finished,
                        machineId = machineId,
                        title = "$name finished",
                        text = did,
                        detail = "$did\n$where",
                        paneId = a.paneId,
                        actions = listOf(AlertAction(AlertAction.Type.ReplyAgent, "Reply", hint = "Next prompt for $name")),
                    ),
                )
            }
            else -> AlertChange.Clear(tag)
        }
    }

    /** Approve and Deny for a yes-or-no question; a short menu's own options; nothing for a longer one (a tap opens it). */
    private fun choices(prompt: BlockedPrompt?): List<AlertAction> {
        if (prompt == null) return emptyList()
        fun choose(i: Int, label: String) =
            AlertAction(AlertAction.Type.Choose, label, option = i, optionLabel = prompt.options[i].label, question = prompt.question)
        if (prompt.yesNo) return listOf(choose(prompt.approveIndex!!, "Approve"), choose(prompt.denyIndex!!, "Deny"))
        if (prompt.options.size <= 2) return prompt.options.indices.map { choose(it, short(prompt.options[it].label)) }
        return emptyList()
    }

    private fun short(label: String) = if (label.length <= 24) label else label.take(23).trimEnd() + "…"

    private fun task(machineId: String, machineLabel: String, t: BoardTask): AlertChange {
        val tag = taskTag(machineId, t.id)
        if (!t.needsYou) return AlertChange.Clear(tag)
        val s = t.status
        val coordinator = AlertAction(AlertAction.Type.AskCoordinator, "Ask Coordinator", hint = "About ${t.name}, to the Coordinator")
        fun board(action: BoardAction, label: String) = AlertAction(AlertAction.Type.Board, label, board = action.name)
        val actions = when (s) {
            BoardStatus.Approval -> listOf(board(BoardAction.Approve, "Approve and merge"), coordinator)
            BoardStatus.ReviewFailed -> listOf(board(BoardAction.Review, "Send findings back"), coordinator)
            BoardStatus.PlanReady -> listOf(
                board(BoardAction.ApprovePlan, "Approve plan"),
                AlertAction(AlertAction.Type.TellTask, "Send back", hint = "What to change in the plan"),
            )
            BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin ->
                listOf(AlertAction(AlertAction.Type.TellTask, "Reply", hint = "Answer for ${t.name}'s agent"))
            BoardStatus.Stopped -> listOf(board(BoardAction.Retry, "Continue"), coordinator)
            BoardStatus.Error -> listOf(board(BoardAction.Retry, "Retry"), coordinator)
            else -> listOf(coordinator)
        }
        return AlertChange.Show(
            Alert(
                tag = tag,
                sig = listOf(t.state, t.stage, t.kind, t.reviewRound, t.reviewPassed, t.approvalBlocked).joinToString("|"),
                kind = Alert.Kind.NeedsYou,
                machineId = machineId,
                title = "${t.name} ${s.label}",
                text = t.title,
                detail = listOf(t.title, t.msg, machineLabel).filter { it.isNotBlank() }.joinToString("\n"),
                taskId = t.id,
                taskName = t.name,
                actions = actions,
            ),
        )
    }

    /**
     * A machine low on RAM or disk: what is low, and what uses the most memory. It needs you (a full
     * machine stops agents), so it comes through quiet hours; it comes down once there is room again.
     */
    fun health(machineId: String, machineLabel: String, r: HealthReport): AlertChange {
        val tag = healthTag(machineId)
        if (r.low.isEmpty()) return AlertChange.Clear(tag)
        val words = HealthWords.words(machineLabel, r)
        return AlertChange.Show(
            Alert(
                tag = tag,
                sig = "low:" + r.low.sorted().joinToString(","),
                kind = Alert.Kind.NeedsYou,
                machineId = machineId,
                title = words.first,
                text = words.second,
                detail = words.third,
                health = true,
            ),
        )
    }

    /** What the Coordinator is sent for a reply to a task's notification. */
    fun aboutTask(taskName: String, title: String, text: String): String = "About $taskName (\"$title\"): $text"
}

/** How a machine running low is put in words, for the notification and the health screen's warning. */
object HealthWords {
    /** Title, one line, and the longer text. */
    fun words(machineLabel: String, r: HealthReport): Triple<String, String, String> {
        val disks = r.disks.ifEmpty { r.low.filter { it.startsWith("disk:") }.map { Disk(it.removePrefix("disk:"), "", "", 0, 0, 0) } }
        val title = when {
            r.ram && disks.isNotEmpty() -> "$machineLabel is low on memory and disk"
            r.ram -> "$machineLabel is low on memory"
            else -> "$machineLabel is low on disk space"
        }
        val parts = buildList {
            if (r.ram) add(r.memory?.let { "${Sizes.percent(it.availablePercent)} of ${Sizes.kb(it.totalKb)} RAM free" } ?: "RAM almost full")
            disks.forEach { d -> add(if (d.totalKb > 0) "${Sizes.kb(d.availKb)} free on ${d.mount}" else "${d.mount} almost full") }
        }
        val top = r.top.take(3).joinToString(", ") { "${it.first} (${Sizes.kb(it.second)})" }
        val line = parts.joinToString(" · ")
        val detail = listOfNotNull(line, top.ifEmpty { null }?.let { "Most memory: $it" }).joinToString("\n")
        return Triple(title, if (top.isNotEmpty() && r.ram) "$line · most: ${r.top.first().first}" else line, detail)
    }
}
