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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Shepherdr 2 as a [WorkBoard]. [connect] gives the machine's client each time (HostSession's
 * `awaitShepherdr`), so a link lost with the SSH connection comes back on the new one.
 */
class V2WorkBoard(private val connect: suspend () -> ShepherdrClient, private val version: String = "") : WorkBoard {
    constructor(client: ShepherdrClient, version: String = "") : this({ client }, version)

    override val api: BoardApi get() = BoardApi.V2(version)

    override suspend fun overview(): WorkOverview = V2Board.overview(connect().snapshot())

    /** From the live link's snapshots, linked again when it drops; read every [everyMs] where there is no live link. */
    override fun overviews(everyMs: Long): Flow<WorkOverview> =
        following(everyMs, emptyList(), { overview() }) { e -> (e as? V2Event.Snapshot)?.let { V2Board.overview(it.snapshot) } }

    override suspend fun details(task: WorkTask): WorkTaskDetails {
        val client = connect()
        val t = client.snapshot().task(task.id) ?: throw IOException("${task.name} isn't on the board any more")
        val now = V2Board.task(t)
        // Its cards are in its agent's chat.
        val cards = if (now.chat.isNotEmpty()) client.chat(now.chat).openQuestions.map { WorkCard(now.chat, it, now.id) } else emptyList()
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

    /** `close` (`shepherdr task close`): what it held back is named when it refuses for unmerged work. */
    override suspend fun close(task: WorkTask): String {
        val o = connect().actRaw(V2Actions.close(task.id))
        if (o.ok) return "${task.name} is done, nothing merged"
        throw IOException(V2Board.closeRefusal(task, o.json))
    }

    /** A message to [space]'s coordinator, which makes the task: Shepherdr 2 makes tasks no other way. */
    override suspend fun addTask(space: String, title: String, brief: String): String {
        connect().send(chatOf(space), V2Board.addRequest(title, brief))
        return "Sent to the coordinator: it adds the task"
    }

    override suspend fun answer(answer: CardAnswer): String {
        val c = answer.card
        val space = c.space.ifEmpty { null }
        if (c.replyTo.isNotEmpty()) {
            val text = answer.other?.takeIf { it.isNotBlank() } ?: answer.note?.takeIf { it.isNotBlank() } ?: throw IOException("Write an answer first")
            connect().act(V2Actions.send(c.chat, text, reply = c.replyTo, space = space))
        } else {
            connect().act(V2Actions.answer(c.chat, c.question.id, answer.choice, answer.choices, answer.note, answer.other, space))
        }
        return ""
    }

    override suspend fun coordinator(space: String): CoordinatorThread =
        V2Board.thread(space, connect().chat(chatOf(space)))

    /** From the live link as the chat changes, linked again when it drops; read every [everyMs] where there is no live link. */
    override fun coordinatorThreads(space: String, everyMs: Long): Flow<CoordinatorThread> {
        val key = chatOf(space)
        return following(everyMs, listOf(key), { coordinator(space) }) { e ->
            (e as? V2Event.Chat)?.takeIf { it.chat.key == key }?.let { V2Board.thread(space, it.chat) }
        }
    }

    override suspend fun say(space: String, text: String, cid: String?, reply: String?): String {
        connect().act(V2Actions.send(chatOf(space), text, reply = reply, cid = cid))
        return ""
    }

    override suspend fun upload(space: String, name: String, data: ByteArray, type: String): String =
        connect().upload(chatOf(space), name, data, type)

    override suspend fun sayFiles(space: String, paths: List<String>, cid: String?): String {
        connect().act(V2Actions.send(chatOf(space), "", cid = cid, attachments = paths))
        return ""
    }

    override suspend fun file(space: String, path: String): ByteArray = connect().file(chatOf(space), path)

    /** Its agent's chat from the live link, as [coordinatorThreads] follows a space's. */
    override fun taskThreads(task: WorkTask, everyMs: Long): Flow<CoordinatorThread> {
        val key = agentChat(task)
        return following(everyMs, listOf(key), { V2Board.thread(task.space, connect().chat(key)) }) { e ->
            (e as? V2Event.Chat)?.takeIf { it.chat.key == key }?.let { V2Board.thread(task.space, it.chat) }
        }
    }

    override suspend fun tell(task: WorkTask, text: String, cid: String?, reply: String?): String {
        connect().act(V2Actions.send(agentChat(task), text, reply = reply, cid = cid))
        return ""
    }

    private fun agentChat(task: WorkTask) = task.chat.ifEmpty { throw IOException("${task.name} has no agent yet") }

    /** Runs the `shepherdr` command; its last line, or its own reason in the exception. */
    suspend fun cli(args: List<String>): String {
        val r = connect().cli(args)
        if (!r.ok) throw IOException(r.said.ifEmpty { "shepherdr ${args.firstOrNull().orEmpty()} failed" })
        return r.said
    }

    /** A space's coordinator chat key is its id (`Place.chat`, the same in every snapshot). */
    private fun chatOf(space: String) = space

    /**
     * What [pick] takes from the live link (watching [chats]), as the web screen follows it: linked
     * again when it drops (a restart, a lost connection), sooner after it last worked. Where there is no
     * live link, [read] every [everyMs]. The first failure is thrown, so a screen can say why; once it
     * has worked, failures are waited out.
     */
    private fun <T> following(everyMs: Long, chats: List<String>, read: suspend () -> T, pick: (V2Event) -> T?): Flow<T> = flow {
        var worked = false
        var wait = RETRY_MS
        while (true) {
            try {
                val client = connect()
                if (!client.canStream) {
                    emit(read())
                    worked = true
                    delay(everyMs)
                    continue
                }
                val link = client.live()
                if (chats.isNotEmpty()) link.watch(chats)
                link.events().collect { e ->
                    pick(e)?.let {
                        worked = true
                        wait = RETRY_MS
                        emit(it)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!worked) throw e
            }
            delay(wait)
            wait = minOf(wait * 2, MAX_RETRY_MS)
        }
    }

    private companion object {
        const val RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
    }
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
                asks = s.asks.containsKey(p.id),
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
        eta = t.report?.eta,
        age = t.age,
        chat = t.agent?.chat.orEmpty(),
        agent = t.agent?.cli.orEmpty(),
        pinned = t.pinned != null,
    )

    /** What a space's coordinator is asked, to add a task: its title, then the brief. */
    fun addRequest(title: String, brief: String): String = buildString {
        append("Please add a task: ").append(title.trim())
        brief.trim().takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
    }

    /** Why `close` refused, from its answer: the work it holds (`held`), or its own error. */
    fun closeRefusal(task: WorkTask, o: JsonObject): String {
        val held = (o["held"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
        if (held.isNotEmpty()) return "${task.name} holds work main lacks: ${held.joinToString("; ")}. Merge or drop it first."
        return (o["error"] as? JsonPrimitive)?.contentOrNull ?: "Shepherdr refused to close ${task.name}"
    }

    /** An agent's kind from its CLI's name, as the agents' marks are keyed: "Claude Code" is `claude`, "Codex" `codex`. */
    fun agentKind(cli: String): String = cli.trim().substringBefore(' ').lowercase()

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
    fun words(m: V2Message): String? = m.text?.let(ShepherdrText::clean)?.takeIf { it.isNotBlank() }
        ?: m.question?.title?.takeIf { it.isNotBlank() }
        ?: m.file?.name?.takeIf { it.isNotBlank() }
        ?: m.answered?.takeIf { it.isNotBlank() }
}
