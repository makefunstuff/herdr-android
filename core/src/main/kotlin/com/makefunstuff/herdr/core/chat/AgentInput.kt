package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException

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
}
