package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.chat.HcAgentPort
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.hc.HcTerminalStream
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.Notice
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the screens do with herdr-core, on a real machine over SSH: two terminals this test starts
 * itself in a desktop-app space (an agent asking yes or no, and `cat`), shown on the Agents list and
 * in Needs you, answered as the card does, a notice when it finishes, and the terminal typed into
 * as the Terminal screen does. Only the test's own terminals are touched, and removed after. Runs
 * only with HERDR_LIVE_KEY and HERDR_LIVE_CORE (the machine, `user@host[:port]`) set.
 */
class LiveHcScreensTest {
    private val keyPath = System.getenv("HERDR_LIVE_KEY")
    private val target = System.getenv("HERDR_LIVE_CORE")

    /** An agent as the desktop app shows one: it reports blocked (OSC 7501), asks, and reports done once answered. */
    private val ask = "printf '\\033]7501;state=blocked:kind=permission:app=codex:msg=%s\\033\\\\' \"\$(printf 'Run ./gradlew test?' | base64)\"; " +
        "echo 'Allow ./gradlew test? [y/n]'; read a; echo \"got \$a\"; printf '\\033]7501;state=done:app=codex\\033\\\\'; sleep 100000"

    private fun start(name: String, tab: String, script: String) =
        "herdr-core run --bg --no-parent --name $name --label herdr.space=ha6sp --label herdr.tab=$tab " +
            "--label ${quote("where=ha6-check / $tab")} -- sh -c ${quote(script)}"

    @Test
    fun anHcAgentIsListedAnsweredAndItsTerminalTypedInto() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_CORE to run")
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target!!)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            val conn = run {
                session.start()
                session.awaitConnection(60_000)
            }
            try {
                withTimeout(60_000) { session.state.first { it.herdrCore != null } }
                val started = conn.run(start("ha6-ask", "review", ask) + "; " + start("ha6-cat", "shell", "exec cat"))
                assertEquals(0, started.exitStatus, started.stderr)

                // On the Agents list, in Needs you, where the desktop app put it.
                val s = withTimeout(30_000) {
                    session.state.first { st -> st.view?.agents?.any { it.name == "ha6-ask" && it.agentStatus == AgentStatus.Blocked } == true }
                }
                val agent = HomeAgent.of(listOf(s)).first { it.agent.name == "ha6-ask" }
                assertEquals("ha6-check › review", agent.location)
                assertTrue(Home.of(HomeAgent.of(listOf(s)), emptyList()).needsYou.any { it is Waiting.Question && it.agent.key == agent.key })
                val pane = agent.agent.paneId
                println("Needs you: ${agent.agent.title} (${agent.agent.agent}, $pane) @ ${agent.location}")

                // The card reads what it asks off its screen, then answers with its keys.
                val port = session.awaitAgent(pane)
                assertTrue(port is HcAgentPort)
                assertEquals(AgentStatus.Blocked, port.status(pane))
                val screen = port.read(pane, lines = 40, visible = true)
                assertTrue("Allow ./gradlew test? [y/n]" in screen, screen)
                AgentInput.keys(port, pane, listOf("y", "enter"), named = true)

                // It finishes: a notice, as for classic herdr's agents.
                val done = withTimeout(30_000) {
                    session.state.first { st -> st.notices.any { it.paneId == pane && it.kind == Notice.Kind.Done } }
                }
                println("notice: ${done.notices.first { it.paneId == pane }.title}")
                assertTrue("got y" in port.read(pane, lines = 40, visible = true))

                // Its terminal, as the Terminal screen opens it: typed into, and the program answers.
                val cat = session.state.value.view!!.panes.first { it.label == "ha6-cat" }
                val stream = HcTerminalStream(session.awaitHerdrCore(), HcMapping.address(cat.paneId)!!, 80, 24, scope)
                stream.open()
                delay(1_000)
                stream.send("hello from the phone\r")
                withTimeout(20_000) {
                    while ("hello from the phone" !in synchronized(stream.emulator.lock) { stream.emulator.screenText() }) delay(100)
                }
                println("terminal ${cat.paneId}:\n" + synchronized(stream.emulator.lock) { stream.emulator.screenText() }.lines().filter { it.isNotBlank() }.take(4).joinToString("\n"))
                // hc follows the size the phone attached with.
                withTimeout(10_000) { session.state.first { it.herdrCore?.sizes?.get(HcMapping.address(cat.paneId)!!) == (80 to 24) } }
                stream.close()
            } finally {
                runCatching { conn.run("for t in ha6-ask ha6-cat; do herdr-core kill \$t; herdr-core rm \$t; done >/dev/null 2>&1; true") }
                session.stop()
                scope.cancel()
            }
        }
    }
}
