package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
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
