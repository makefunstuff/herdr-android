package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentChat
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives a real agent through the chat model over SSH. Runs only when HERDR_LIVE_KEY and
 * HERDR_LIVE_TARGET are set and HERDR_LIVE_CHAT_AGENT names a live claude agent (in
 * HERDR_LIVE_SESSION, default "default") started with `--permission-mode default`, so that a
 * shell command makes it ask first. HERDR_LIVE_CHAT_DIR is that agent's working directory.
 */
class LiveChatTest {
    private val keyPath = System.getenv("HERDR_LIVE_KEY")
    private val target = System.getenv("HERDR_LIVE_TARGET")
    private val agentName = System.getenv("HERDR_LIVE_CHAT_AGENT")
    private val dir = System.getenv("HERDR_LIVE_CHAT_DIR")

    @Test
    fun followsTranscriptAndAnswersAPermissionPrompt() {
        if (keyPath == null || target == null || agentName == null || dir == null) return
        val identity = KeyImport.fromPrivateKey(File(keyPath).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            session.start()
            val snap = withTimeout(20_000) { session.state.first { it.online }.snapshot!! }
            val agent = snap.agents.first { it.name == agentName }
            val chat = AgentChat(session, agent.paneId, scope)
            chat.start()

            val loaded = withTimeout(20_000) { chat.state.first { it.source == AgentChat.Source.Transcript && it.items.isNotEmpty() } }
            println("loaded ${loaded.items.size} items; last: ${loaded.items.last()}")

            val marker = "chat-check-${System.currentTimeMillis() % 100000}.txt"
            File(dir, marker).delete()
            session.awaitApi().prompt(agent.paneId, "Run the shell command: touch $marker")

            // The prompt shows up as a user message, then the agent asks before running the command.
            withTimeout(60_000) { chat.state.first { s -> s.items.any { it is ChatItem.User && it.text.contains(marker) } } }
            val asked = withTimeout(90_000) {
                chat.state.first { it.prompt != null && session.state.value.snapshot?.agents?.first { a -> a.paneId == agent.paneId }?.agentStatus == AgentStatus.Blocked }
            }
            val prompt = asked.prompt!!
            println("prompt: ${prompt.question} ${prompt.details} ${prompt.options}")
            assertTrue(prompt.details.any { it.contains(marker) }, "the prompt should show the command: ${prompt.details}")
            val yes = prompt.options.indexOfFirst { it.label == "Yes" }
            assertTrue(yes >= 0, "expected a Yes option: ${prompt.options}")
            session.awaitApi().sendKeys(agent.paneId, prompt.keysFor(yes))

            val done = withTimeout(90_000) {
                chat.state.first { s ->
                    s.items.any { it is ChatItem.Tool && it.input?.contains(marker) == true && it.state != com.makefunstuff.herdr.core.chat.ToolState.Running } &&
                        s.items.last() is ChatItem.Agent
                }
            }
            println("reply: ${done.items.last()}")
            assertTrue(File(dir, marker).exists(), "the agent should have run the command")
            chat.close()
            session.stop()
            scope.cancel()
        }
    }
}
