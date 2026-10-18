package com.makefunstuff.herdr.core.home

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.push.LiveItems

/** An agent as the home list sees it: the machine it runs on, where in herdr, and its workspace's name. */
data class HomeAgent(
    val machineId: String,
    val machineLabel: String,
    val agent: AgentInfo,
    /** `workspace › tab`, as the app shows it. */
    val location: String,
    /** The workspace's name alone: a board task's agent works in one named after the task. */
    val workspace: String = location.substringBefore(" › "),
) {
    val key: String get() = "$machineId/${agent.paneId}"

    /** Which of its finishes this is: herdr's completion_seq, or on an older herdr the state change that ended it. */
    val finish: Long get() = agent.completionSeq ?: agent.stateChangeSeq
}

/** A board's tasks as the app last read them on one machine. */
data class HomeBoard(val machineId: String, val machineLabel: String, val tasks: List<BoardTask>)

/** Something on the Needs you list. [key] stays the same while it waits, whatever it waits for. */
sealed interface Waiting {
    val key: String
    val machineId: String
    /** The board task, when there is one: its key and title name the item. */
    val task: BoardTask?

    /** An agent asking a question, with the board task it works on, when it works on one. */
    data class Question(val agent: HomeAgent, override val task: BoardTask?) : Waiting {
        override val key: String get() = "agent/${agent.key}"
        override val machineId: String get() = agent.machineId
    }

    /** A board task waiting for the human, with its agent when one is running on that machine. */
    data class Task(override val machineId: String, val machineLabel: String, override val task: BoardTask, val agent: HomeAgent?) : Waiting {
        override val key: String get() = "task/$machineId/${task.id}"
    }
}

/** An agent on the list below Needs you: its board task when it has one; [new] for a finish not looked at yet. */
data class AgentRow(val agent: HomeAgent, val task: BoardTask?, val new: Boolean = false)

/**
 * The Agents screen's list, across machines: what waits for you first (blocked agents and board
 * tasks waiting for a yes, a fix or an answer, an agent and its task shown once), then the agents
 * at work, the ones that finished (those not looked at yet first), and the rest.
 */
data class Home(
    val needsYou: List<Waiting> = emptyList(),
    val working: List<AgentRow> = emptyList(),
    val done: List<AgentRow> = emptyList(),
    val idle: List<AgentRow> = emptyList(),
) {
    /** Finished and not looked at: herdr still says `done`, and the phone hasn't opened it since. */
    val fresh: Int get() = done.count { it.new }

    val isEmpty: Boolean get() = needsYou.isEmpty() && working.isEmpty() && done.isEmpty() && idle.isEmpty()

    companion object {
        /** The order tasks wait in: ready to merge first, then what failed, plans and choices, questions, the rest. */
        val ORDER = listOf(
            BoardStatus.Approval, BoardStatus.ReviewFailed, BoardStatus.Error, BoardStatus.PlanReady, BoardStatus.PickOne,
            BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin, BoardStatus.Stopped,
        )

        /**
         * The list from every machine's [agents] (in the machines' order) and [boards]. [seen] holds,
         * by [HomeAgent.key], the finish the phone last showed of an agent ([HomeAgent.finish]).
         */
        fun of(agents: List<HomeAgent>, boards: List<HomeBoard>, seen: Map<String, Long> = emptyMap()): Home {
            val tasks = own(boards)
            // Each agent's task: one of its own machine's board, by the names herdr-projects gives them.
            val taskOf = agents.associate { a ->
                a.key to tasks[a.machineId].orEmpty().firstOrNull { t ->
                    t.machine.isEmpty() && !t.archived && LiveItems.belongs(a.agent.name.orEmpty(), a.workspace, t.id, t.name)
                }
            }
            val questions = agents.filter { it.agent.agentStatus == AgentStatus.Blocked }
            val asked = questions.mapNotNull { taskOf[it.key]?.let { t -> it.machineId to t.id } }.toSet()
            val waiting = boards.flatMap { b ->
                tasks[b.machineId].orEmpty()
                    .filter { it.needsYou && (b.machineId to it.id) !in asked }
                    .sortedBy { ORDER.indexOf(it.status).let { i -> if (i < 0) ORDER.size else i } }
                    .map { t ->
                        val agent = agents.firstOrNull { it.machineId == b.machineId && taskOf[it.key]?.id == t.id }
                        Waiting.Task(b.machineId, b.machineLabel, t, agent)
                    }
            }
            // An agent whose task waits is that task's card; it isn't listed again below.
            val inCards = waiting.mapNotNull { it.agent?.key }.toSet()
            val rest = agents.filter { it.agent.agentStatus != AgentStatus.Blocked && it.key !in inCards }
            fun row(a: HomeAgent) = AgentRow(a, taskOf[a.key], new = a.agent.agentStatus == AgentStatus.Done && seen[a.key] != a.finish)
            val bySeq = compareByDescending<HomeAgent> { it.agent.stateChangeSeq }
            return Home(
                needsYou = questions.sortedWith(bySeq).map { Waiting.Question(it, taskOf[it.key]) } + waiting,
                working = rest.filter { it.agent.agentStatus == AgentStatus.Working }.sortedWith(bySeq).map(::row),
                done = rest.filter { it.agent.agentStatus == AgentStatus.Done }.sortedWith(bySeq).map(::row).sortedByDescending { it.new },
                idle = rest.filter { it.agent.agentStatus == AgentStatus.Idle || it.agent.agentStatus == AgentStatus.Unknown }.sortedWith(bySeq).map(::row),
            )
        }

        /**
         * Each machine's board, without another machine's task (`vm:repo/3`) when that machine's own
         * board, read here too, lists it: a task waits on one card, not two.
         */
        fun own(boards: List<HomeBoard>): Map<String, List<BoardTask>> {
            val own = boards.flatMap { b -> b.tasks.filter { it.machine.isEmpty() }.map { it.id.substringAfter(':') to it.title } }.toSet()
            return boards.associate { b ->
                b.machineId to b.tasks.filter { it.machine.isEmpty() || (it.id.substringAfter(':') to it.title) !in own }
            }
        }

        /** The marks to keep: [marks] with every finished agent in [home] marked seen now, and agents gone dropped. */
        fun seenAll(marks: Map<String, Long>, home: Home, agents: List<HomeAgent>): Map<String, Long> =
            keep(marks + home.done.associate { it.agent.key to it.agent.finish }, agents)

        /** [marks] for the agents still there, so the store doesn't grow with every pane that ever finished. */
        fun keep(marks: Map<String, Long>, agents: List<HomeAgent>): Map<String, Long> {
            val there = agents.map { it.key }.toSet()
            return marks.filterKeys { it in there }
        }
    }
}

/** One machine's part of the Board tab: what waits for you, what is at work, what is to do, and how many are done. */
data class BoardGroup(
    val machineId: String,
    val machineLabel: String,
    val needsYou: List<BoardTask>,
    val working: List<BoardTask>,
    val todo: List<BoardTask>,
    val done: Int,
) {
    val tasks: List<BoardTask> get() = needsYou + working + todo

    companion object {
        private val WORKING = setOf(BoardStatus.Working, BoardStatus.Reviewing, BoardStatus.Queued)

        /** Every machine's board in one list, a group each (in the machines' order), [machine] alone when given. */
        fun of(boards: List<HomeBoard>, machine: String? = null): List<BoardGroup> {
            val tasks = Home.own(boards)
            return boards.filter { machine == null || it.machineId == machine }.map { b ->
                val list = tasks[b.machineId].orEmpty().filter { !it.archived }
                BoardGroup(
                    b.machineId, b.machineLabel,
                    needsYou = list.filter { it.needsYou }.sortedBy { Home.ORDER.indexOf(it.status).let { i -> if (i < 0) Home.ORDER.size else i } },
                    working = list.filter { !it.needsYou && it.status in WORKING },
                    todo = list.filter { !it.needsYou && it.status == BoardStatus.Todo },
                    done = list.count { !it.needsYou && it.status == BoardStatus.Done },
                )
            }
        }

        /** How many tasks wait for you across [boards], each once: the Board tab's badge. */
        fun waiting(boards: List<HomeBoard>): Int = Home.own(boards).values.sumOf { l -> l.count { it.needsYou } }
    }
}
