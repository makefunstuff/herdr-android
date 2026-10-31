package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentStatus
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * What the agent chat and a notification's buttons send to an agent: keys, and words, through its
 * [AgentPort] (classic herdr's or herdr-core's).
 */
object AgentInput {
    /** Presses [keys] in the agent's pane ([named]: an agent herdr started itself). */
    suspend fun keys(api: AgentPort, paneId: String, keys: List<String>, named: Boolean) = api.keys(paneId, keys, named)

    /**
     * Sends [text]: as a prompt to a [named] agent at rest, typed with Enter into a [blocked] one (it
     * answers the question) or into a pane with no agent herdr knows.
     */
    suspend fun send(api: AgentPort, paneId: String, text: String, named: Boolean, blocked: Boolean) {
        if (!named || blocked) return api.type(paneId, text)
        // It became blocked between the snapshot and the send: answer the question instead.
        if (!api.prompt(paneId, text)) api.type(paneId, text)
    }

    /** The question on the agent's screen now, read again; null when it shows none. */
    suspend fun promptNow(api: AgentPort, paneId: String): BlockedPrompt? =
        BlockedPrompt.parse(api.read(paneId, lines = 80, visible = true))

    /**
     * Picks option [index] of [asked], once the agent's screen still shows that question with those
     * choices: an answer pressed on a question that changed in the meantime would go to another one.
     * The keys count from where the cursor is now. Throws [Changed] when it moved on.
     */
    suspend fun choose(api: AgentPort, paneId: String, asked: BlockedPrompt, index: Int, named: Boolean) {
        val now = current(api, paneId, asked)
        keys(api, paneId, now.keysFor(index), named)
    }

    /** Ticks exactly [wanted] of a multiple choice and sends it, on the same question as [asked]. */
    suspend fun tick(api: AgentPort, paneId: String, asked: BlockedPrompt, wanted: Set<Int>, named: Boolean) {
        val now = current(api, paneId, asked)
        keys(api, paneId, now.keysForTicks(wanted), named)
    }

    /** Your own words as the answer: the cursor to "Type something.", the words, Enter. */
    suspend fun typeAnswer(api: AgentPort, paneId: String, asked: BlockedPrompt, text: String, named: Boolean) {
        val now = current(api, paneId, asked)
        val move = now.keysToType() ?: throw IOException("This question takes only its choices.")
        if (move.isNotEmpty()) keys(api, paneId, move, named)
        api.type(paneId, text)
    }

    /**
     * "No, and tell it what to do differently" with your words: picks that option on the same question,
     * waits for the agent to take its input again (up to [waitMs]), then sends [text] as the next prompt.
     */
    suspend fun refuseAndTell(api: AgentPort, paneId: String, asked: BlockedPrompt, index: Int, text: String, named: Boolean, waitMs: Long = 4_000) {
        choose(api, paneId, asked, index, named)
        var blocked = true
        val until = System.currentTimeMillis() + waitMs
        while (blocked && System.currentTimeMillis() < until) {
            delay(250)
            blocked = api.status(paneId) == AgentStatus.Blocked
        }
        send(api, paneId, text, named, blocked = blocked)
    }

    private suspend fun current(api: AgentPort, paneId: String, asked: BlockedPrompt): BlockedPrompt {
        val now = promptNow(api, paneId)
        if (now == null || !asked.same(now)) throw Changed()
        return now
    }

    /** The question on screen is not the one answered any more. */
    class Changed : IOException("The question changed. Look at it again and answer.")

    /**
     * Picks option [index] ([label]) of [question], the one the person was shown: only while the
     * agent still waits and its screen, read again now, asks that same question with that option
     * there. A notification's button and a card's Yes and No both answer through this.
     */
    suspend fun choose(api: AgentPort, paneId: String, question: String, index: Int, label: String, details: List<String>) {
        if (api.status(paneId) != AgentStatus.Blocked) throw IOException("It isn't waiting any more.")
        val prompt = promptNow(api, paneId)
        // The same question can ask about another command: what it shows of it ([details]) must match too.
        if (prompt == null || prompt.question != question || prompt.details != details || prompt.options.getOrNull(index)?.label != label) {
            throw IOException("The question changed. Open the chat to answer it.")
        }
        keys(api, paneId, prompt.keysFor(index), named = true)
    }
}
