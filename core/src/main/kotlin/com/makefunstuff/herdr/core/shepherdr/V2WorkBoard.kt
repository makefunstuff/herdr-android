package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.ChatLine
import com.makefunstuff.herdr.core.board.CoordinatorThread
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.board.WorkSpace
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTaskDetails
import com.makefunstuff.herdr.core.board.WorkTasks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Shepherdr 2 as a [WorkBoard], over [client]. */
class V2WorkBoard(private val client: ShepherdrClient, private val version: String = "") : WorkBoard {
    override val api: BoardApi get() = BoardApi.V2(version)

    override suspend fun overview(): WorkOverview = V2Board.overview(client.snapshot())

    /** From the live link's snapshots where it can hold one; else read every [everyMs]. */
    override fun overviews(everyMs: Long): Flow<WorkOverview> =
        if (!client.canStream) super.overviews(everyMs)
        else flow {
            client.live().events().filterIsInstance<V2Event.Snapshot>().map { V2Board.overview(it.snapshot) }.collect { emit(it) }
        }

    override suspend fun details(task: WorkTask): WorkTaskDetails {
        val t = client.snapshot().task(task.id) ?: throw IOException("${task.name} isn't on the board any more")
        val now = V2Board.task(t)
        // Its cards are in its agent's chat, which only the live link sends.
        val cards = if (now.chat.isNotEmpty() && client.canStream) {
            client.chat(now.chat).openQuestions.map { WorkCard(now.chat, it, now.id) }
        } else emptyList()
        return WorkTaskDetails(
            task = now,
            brief = t.desc.orEmpty(),
            summary = t.said.orEmpty(),
            facts = t.facts.orEmpty(),
            plan = t.plan.orEmpty(),
            branch = t.branch.orEmpty(),
            cards = cards,
        )
    }

    /** `shepherdr merge <id>`: merging is what a yes to a ready task means on Shepherdr 2. */
    override suspend fun approve(task: WorkTask): String = cli(listOf("merge", task.id))

    override suspend fun answer(answer: CardAnswer): String {
        val c = answer.card
        val space = c.space.ifEmpty { null }
        if (c.replyTo.isNotEmpty()) {
            val text = answer.other?.takeIf { it.isNotBlank() } ?: answer.note?.takeIf { it.isNotBlank() } ?: throw IOException("Write an answer first")
            client.act(V2Actions.send(c.chat, text, reply = c.replyTo, space = space))
        } else {
            client.act(V2Actions.answer(c.chat, c.question.id, answer.choice, answer.choices, answer.note, answer.other, space))
        }
        return ""
    }

    override suspend fun coordinator(space: String): CoordinatorThread =
        V2Board.thread(space, client.chat(chatOf(space)))

    override fun coordinatorThreads(space: String, everyMs: Long): Flow<CoordinatorThread> =
        if (!client.canStream) super.coordinatorThreads(space, everyMs)
        else flow {
            val key = chatOf(space)
            val link = client.live()
            link.watch(listOf(key))
            link.events().filterIsInstance<V2Event.Chat>().filter { it.chat.key == key }.map { V2Board.thread(space, it.chat) }.collect { emit(it) }
        }

    override suspend fun say(space: String, text: String): String {
        client.send(chatOf(space), text)
        return ""
    }

    /** Runs the `shepherdr` command; its last line, or its own reason in the exception. */
    suspend fun cli(args: List<String>): String {
        val r = client.cli(args)
        if (!r.ok) throw IOException(r.said.ifEmpty { "shepherdr ${args.firstOrNull().orEmpty()} failed" })
        return r.said
    }

    /** A space's coordinator chat key is its id (`Place.chat`, the same in every snapshot). */
    private fun chatOf(space: String) = space
}

/** Shepherdr 2's snapshot and chats in the [WorkBoard]'s shapes. */
object V2Board {
    fun overview(s: V2Snapshot): WorkOverview {
        val tasks = WorkTasks.ordered(s.tasks.filter { it.archived == null }.map(::task))
        val spaces = s.places.map { p ->
            WorkSpace(
                id = p.id,
                name = p.name,
                machine = p.machine,
                home = p.home,
                chat = p.chat.ifEmpty { p.id },
                needsYou = p.status == V2Status.Need || s.asks.containsKey(p.id) || tasks.any { it.space == p.id && it.status.needsYou },
                unreachable = p.unreachableSince != null,
            )
        }
        return WorkOverview(spaces, tasks)
    }

    fun task(t: V2Task) = WorkTask(
        id = t.id,
        name = t.id,
        title = t.name,
        space = t.place,
        status = status(t.status),
        line = t.report?.msg?.takeIf { it.isNotBlank() } ?: t.sub.orEmpty(),
        pr = t.pr?.url.orEmpty(),
        progress = t.report?.progress,
        chat = t.agent?.chat.orEmpty(),
        pinned = t.pinned != null,
    )

    fun status(s: V2TaskStatus): WorkStatus = when (s) {
        V2TaskStatus.Need -> WorkStatus.Need
        V2TaskStatus.Ready -> WorkStatus.Ready
        V2TaskStatus.Merging -> WorkStatus.Merging
        V2TaskStatus.Work -> WorkStatus.Work
        V2TaskStatus.Queued -> WorkStatus.Queued
        V2TaskStatus.Todo -> WorkStatus.Todo
        V2TaskStatus.Done -> WorkStatus.Done
    }

    /** A coordinator's chat as bubbles: words, cards and questions as their one line; tool steps left out. */
    fun thread(space: String, chat: V2Chat): CoordinatorThread {
        val lines = chat.messages.mapNotNull { m ->
            val text = words(m) ?: return@mapNotNull null
            ChatLine(m.you, text, pending = m.waiting)
        }
        val cards = chat.messages.flatMap { m ->
            buildList {
                m.question?.takeIf { it.open }?.let { add(WorkCard(chat.key, it)) }
                val ask = m.card?.ask?.takeIf { it.open } ?: return@buildList
                val task = m.card.task.orEmpty()
                // A task's ask copied here: answered from this space, so its copy here is settled too.
                ask.questions.orEmpty().filter { it.open }.forEach { add(WorkCard(ask.chat, it, task, space = space)) }
                ask.reply?.let { r ->
                    val q = V2Question(id = r.id, title = r.text, kind = "Reply", other = true, to = task)
                    add(WorkCard(ask.chat, q, task, space = space, replyTo = r.id))
                }
            }
        }
        return CoordinatorThread(space, lines, working = chat.working && !chat.background, cards = cards, v2 = chat)
    }

    /** A message's words as one bubble shows them; null for one with none (a turn's tool steps). */
    fun words(m: V2Message): String? = m.text?.takeIf { it.isNotBlank() }
        ?: m.question?.title?.takeIf { it.isNotBlank() }
        ?: m.file?.name?.takeIf { it.isNotBlank() }
        ?: m.answered?.takeIf { it.isNotBlank() }
}
