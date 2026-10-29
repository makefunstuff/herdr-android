package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus

/**
 * A space's coordinator chat on Shepherdr 2 as the Coordinator screen draws it, oldest first: your
 * words and its replies, Shepherdr's update cards about tasks, question cards, files, artifacts.
 * Shepherdr's own tagged lines are no one's words, so they never show as text.
 */
sealed interface SpaceChatItem {
    /** The message's id: unique in the chat, so a list can key on it. */
    val id: String

    /** Words: yours on the right, the coordinator's (markdown) on the left. */
    data class Words(
        override val id: String,
        val you: Boolean,
        val text: String,
        /** What it answers, quoted. */
        val reply: V2ReplyQuote? = null,
        val reactions: List<V2Reaction> = emptyList(),
        /** Yours, not in yet: the coordinator shows a menu or is starting; or sent from here and not seen back yet. */
        val waiting: Boolean = false,
        /** Yours that couldn't go in, and why. */
        val failed: String? = null,
    ) : SpaceChatItem

    /** Shepherdr's card about a task ("HA-1 is ready to merge"), with the questions of its that wait here. */
    data class Update(
        override val id: String,
        val task: String,
        val status: WorkStatus?,
        /** What happened, after the task's name: "is ready to merge". */
        val text: String,
        val pr: V2Pr? = null,
        /** The base branch went red ("main"). */
        val red: String? = null,
        /** Its task's questions copied here, still open: answered from here. */
        val asks: List<WorkCard> = emptyList(),
        /** Its task's question, answered: where, in words. */
        val answered: String? = null,
    ) : SpaceChatItem

    /** A question card, with the words that came with it ([intro]); [answer] once answered. */
    data class Question(
        override val id: String,
        val card: WorkCard,
        val intro: String = "",
        val answer: String? = null,
    ) : SpaceChatItem

    /** A file sent along: yours, or one the coordinator shared. */
    data class File(override val id: String, val you: Boolean, val name: String, val path: String) : SpaceChatItem

    /** An artifact the coordinator posted: shown in Shepherdr itself, the app names it. */
    data class Artifact(override val id: String, val artifact: String, val version: Int, val intro: String = "") : SpaceChatItem

    /** A thin "New session" line before the message after it. */
    data class NewSession(override val id: String) : SpaceChatItem

    /** "Answered “Rerun CI on main?”": your answer to a card, as the chat keeps it. */
    data class Note(override val id: String, val text: String) : SpaceChatItem
}

/** What you sent from the screen and the chat hasn't shown yet: it shows as waiting until a message carries its [cid]. */
data class OutgoingLine(val cid: String, val text: String, val reply: V2ReplyQuote? = null)

object SpaceChat {
    /** [chat] (a space's coordinator chat, [space] its id) as the screen's items; [outgoing] not yet in it go last. */
    fun items(space: String, chat: V2Chat, outgoing: List<OutgoingLine> = emptyList()): List<SpaceChatItem> {
        val failed = chat.failed.orEmpty().associate { it.cid to it.error }
        val out = ArrayList<SpaceChatItem>()
        for (m in chat.messages) {
            if (m.newSession) out += SpaceChatItem.NewSession("${m.id}#new")
            val text = ShepherdrText.clean(m.text.orEmpty())
            val card = m.card
            val q = m.question
            val post = m.artifact
            when {
                card != null -> out += update(space, m.id, card, text)
                q != null -> out += SpaceChatItem.Question(m.id, WorkCard(chat.key, q), intro = text, answer = answered(q))
                post != null -> out += SpaceChatItem.Artifact(m.id, post.id, post.v, intro = text)
                else -> {
                    if (text.isNotEmpty()) {
                        out += SpaceChatItem.Words(
                            m.id, m.you, text, m.reply, m.reactions.orEmpty(),
                            waiting = m.waiting, failed = m.cid?.let { failed[it] },
                        )
                    }
                    m.file?.takeIf { it.name.isNotBlank() }?.let { f -> out += SpaceChatItem.File("${m.id}#file", m.you, f.name, f.path) }
                    m.answered?.takeIf { it.isNotBlank() }?.let { a -> out += SpaceChatItem.Note("${m.id}#answered", a) }
                }
            }
        }
        val seen = chat.messages.mapNotNullTo(HashSet()) { it.cid }
        outgoing.filter { it.cid !in seen }.forEach { o ->
            out += SpaceChatItem.Words("cid:${o.cid}", true, o.text, o.reply, waiting = failed[o.cid] == null, failed = failed[o.cid])
        }
        return out
    }

    /** The outgoing lines the chat doesn't show yet: one that came back (its [OutgoingLine.cid]) is dropped. */
    fun stillOutgoing(chat: V2Chat, outgoing: List<OutgoingLine>): List<OutgoingLine> {
        val seen = chat.messages.mapNotNullTo(HashSet()) { it.cid }
        return outgoing.filter { it.cid !in seen }
    }

    /** A question's answer in words: the choice sent, the words typed, or where it was answered; null while open. */
    fun answered(q: V2Question): String? = when {
        q.open -> null
        q.sent != null && q.sent >= 0 -> q.choices.getOrNull(q.sent)?.label ?: "Answered"
        q.via == "terminal" -> "Answered in its terminal"
        !q.said.isNullOrBlank() -> q.said
        q.via == "chat" -> "Answered in the chat"
        else -> "Answered"
    }

    private fun update(space: String, id: String, c: V2UpdateCard, text: String): SpaceChatItem.Update {
        val task = c.task.orEmpty()
        val ask = c.ask
        val asks = buildList {
            if (ask == null || !ask.open) return@buildList
            ask.questions.orEmpty().filter { it.open }.forEach { add(WorkCard(ask.chat, it, task, space = space)) }
            ask.reply?.let { r ->
                add(WorkCard(ask.chat, V2Question(id = r.id, title = r.text, kind = "Reply", other = true, to = task), task, space = space, replyTo = r.id))
            }
        }
        val answered = ask?.answered?.let { a -> a.text?.takeIf { it.isNotBlank() } ?: a.where.takeIf { it.isNotBlank() }?.let { "Answered in $it" } }
        // The card's own words come after the task's name; a card without them says its message's.
        val words = ShepherdrText.clean(c.text).ifEmpty { text.removePrefix(task).trim() }
        return SpaceChatItem.Update(id, task, c.status?.let(V2Board::status), words, c.pr, c.red, asks, answered)
    }
}

/** Shepherdr's own lines in agents' words, and the chat-line tags an agent writes, taken out of what people read. */
object ShepherdrText {
    private val hidden = Regex("""<shepherdr(?:\s+[\w-]+="[^"]*")*\s*>[\s\S]*?</shepherdr>\s*""")
    private val noReply = Regex("""^`?(<no-reply>[\s\S]*?</no-reply>|<no-reply\s*/>)`?$""", RegexOption.IGNORE_CASE)
    private val agentLine = Regex("""^\s*<(react|reply)(\s+[\w-]+="[^"]*")*\s*/>\s*$""", RegexOption.MULTILINE)

    fun clean(s: String): String {
        val t = s.replace(hidden, "").trim()
        if (noReply.matches(t)) return ""
        return t.replace(agentLine, "").trim()
    }
}
