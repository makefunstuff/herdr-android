package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import kotlinx.coroutines.delay
import java.io.IOException

/** What the agent chat and a notification's buttons send to an agent: keys, and words. */
object AgentInput {
    /**
     * Presses [keys] in the agent's pane. agent.send_keys reaches agents herdr started itself; one an
     * integration reported answers "not an active named agent", so its keys go to the pane instead.
     */
    suspend fun keys(api: HerdrApi, paneId: String, keys: List<String>, named: Boolean) {
        val sent = if (named) runCatching { api.agentSendKeys(paneId, keys) } else null
        if (sent == null || sent.isFailure) api.sendKeys(paneId, keys)
    }

    /**
     * Sends [text]: as a prompt to a [named] agent at rest, typed with Enter into a [blocked] one (it
     * answers the question) or into a pane with no agent herdr knows.
     */
    suspend fun send(api: HerdrApi, paneId: String, text: String, named: Boolean, blocked: Boolean) {
        suspend fun typeIt() {
            api.sendText(paneId, text)
            api.sendKeys(paneId, listOf("enter"))
        }
        if (!named || blocked) return typeIt()
        try {
            api.prompt(paneId, text)
        } catch (e: HerdrApiException) {
            // It became blocked between the snapshot and the send: answer the question instead.
            if (e.code == "agent_blocked") typeIt() else throw e
        }
    }

    /** The question on the agent's screen now, read again; null when it shows none. */
    suspend fun promptNow(api: HerdrApi, paneId: String): BlockedPrompt? =
        BlockedPrompt.parse(api.readPane(paneId, source = "visible", lines = 80).text)

    /**
     * Picks option [index] of [asked], once the agent's screen still shows that question with those
     * choices: an answer pressed on a question that changed in the meantime would go to another one.
     * The keys count from where the cursor is now. Throws [Changed] when it moved on.
     */
    suspend fun choose(api: HerdrApi, paneId: String, asked: BlockedPrompt, index: Int, named: Boolean) {
        val now = current(api, paneId, asked)
        keys(api, paneId, now.keysFor(index), named)
    }

    /** Ticks exactly [wanted] of a multiple choice and sends it, on the same question as [asked]. */
    suspend fun tick(api: HerdrApi, paneId: String, asked: BlockedPrompt, wanted: Set<Int>, named: Boolean) {
        val now = current(api, paneId, asked)
        keys(api, paneId, now.keysForTicks(wanted), named)
    }

    /** Your own words as the answer: the cursor to "Type something.", the words, Enter. */
    suspend fun typeAnswer(api: HerdrApi, paneId: String, asked: BlockedPrompt, text: String, named: Boolean) {
        val now = current(api, paneId, asked)
        val move = now.keysToType() ?: throw IOException("This question takes only its choices.")
        if (move.isNotEmpty()) keys(api, paneId, move, named)
        api.sendText(paneId, text)
        api.sendKeys(paneId, listOf("enter"))
    }

    /**
     * "No, and tell it what to do differently" with your words: picks that option on the same question,
     * waits for the agent to take its input again (up to [waitMs]), then sends [text] as the next prompt.
     */
    suspend fun refuseAndTell(api: HerdrApi, paneId: String, asked: BlockedPrompt, index: Int, text: String, named: Boolean, waitMs: Long = 4_000) {
        choose(api, paneId, asked, index, named)
        var blocked = true
        val until = System.currentTimeMillis() + waitMs
        while (blocked && System.currentTimeMillis() < until) {
            delay(250)
            blocked = api.snapshot().agents.firstOrNull { it.paneId == paneId }?.agentStatus == AgentStatus.Blocked
        }
        send(api, paneId, text, named, blocked = blocked)
    }

    private suspend fun current(api: HerdrApi, paneId: String, asked: BlockedPrompt): BlockedPrompt {
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
    suspend fun choose(api: HerdrApi, paneId: String, question: String, index: Int, label: String) {
        val agent = api.snapshot().agents.firstOrNull { it.paneId == paneId }
        if (agent?.agentStatus != AgentStatus.Blocked) throw IOException("It isn't waiting any more.")
        val prompt = BlockedPrompt.parse(api.readPane(paneId, source = "visible", lines = 80).text)
        if (prompt == null || prompt.question != question || prompt.options.getOrNull(index)?.label != label) {
            throw IOException("The question changed. Open the chat to answer it.")
        }
        keys(api, paneId, prompt.keysFor(index), named = true)
    }
}
