package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.home.BoardGroup
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.core.home.Waiting
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeTest {
    private fun task(json: String, key: String = ""): BoardTask = BoardTasks.task(Json.parseToJsonElement(json).jsonObject, key)!!

    private val approve = task("""{"id":"herdr-android/9","title":"Last answered","state":"blocked","stage":"approval","kind":"permission","review":{"pass":true}}""", "AND-9")
    private val asks = task("""{"id":"api-server/3","title":"Speed up /jobs","state":"blocked","stage":"working","kind":"question"}""", "API-3")
    private val failed = task("""{"id":"website/4","title":"Install page","state":"blocked","stage":"approval","kind":"permission","review":{"pass":false}}""", "WEB-4")
    private val working = task("""{"id":"herdr-android/6","title":"Screenshots","state":"working","stage":"working","progress":3}""", "AND-6")
    private val todo = task("""{"id":"herdr-android/10","title":"Haptics","state":"idle","stage":""}""", "AND-10")
    private val done = task("""{"id":"herdr-android/2","title":"Native chat","state":"done","stage":"done"}""", "AND-2")

    private fun agent(
        pane: String, status: String, name: String = "claude", workspace: String = "src", seq: Long = 1, completion: Long? = null, machine: String = "devbox",
    ) = HomeAgent(
        machine, machine,
        AgentInfo(name = name, agent = "claude", agentStatusRaw = status, workspaceId = pane.substringBefore(':'), tabId = "t", paneId = pane, stateChangeSeq = seq, completionSeq = completion),
        "$workspace › main",
    )

    private fun board(vararg tasks: BoardTask, machine: String = "devbox") = HomeBoard(machine, machine, tasks.toList())

    @Test
    fun anAgentAndItsTaskShowOnce() {
        val home = Home.of(
            listOf(
                agent("w2:p1", "blocked", name = "sw-api-server-3", seq = 5),
                agent("w1:p1", "idle", name = "sw-herdr-android-9"),
                agent("w4:p1", "working", name = "sw-herdr-android-6"),
            ),
            listOf(board(approve, asks, failed, working, todo, done)),
        )
        // The blocked agent stands for its asking task; the approval carries its (idle) agent, which isn't listed again.
        assertEquals(listOf("agent/devbox/w2:p1", "task/devbox/herdr-android/9", "task/devbox/website/4"), home.needsYou.map { it.key })
        val question = assertIs<Waiting.Question>(home.needsYou[0])
        assertEquals("API-3", question.task?.name)
        assertEquals("w1:p1", assertIs<Waiting.Task>(home.needsYou[1]).agent?.agent?.paneId)
        assertNull(assertIs<Waiting.Task>(home.needsYou[2]).agent)
        assertTrue(home.idle.isEmpty())
        // A working agent keeps its task, for its key and progress.
        assertEquals("AND-6", home.working.single().task?.name)
    }

    @Test
    fun aTasksWorkspaceTiesItsAgentToIt() {
        val home = Home.of(listOf(agent("w1:p1", "blocked", name = "claude", workspace = "API-3 Speed up /jobs")), listOf(board(asks)))
        assertEquals("API-3", home.needsYou.single().task?.name)
    }

    @Test
    fun waitingTasksComeInTheBoardsOrder() {
        val home = Home.of(emptyList(), listOf(board(asks, failed, approve)))
        assertEquals(listOf(BoardStatus.Approval, BoardStatus.ReviewFailed, BoardStatus.Asks), home.needsYou.map { it.task?.status })
    }

    @Test
    fun anotherMachinesTaskWaitsOnce() {
        val shown = task("""{"id":"vm:herdr-android/9","title":"Last answered","state":"blocked","stage":"approval","kind":"permission","review":{"pass":true}}""")
        val home = Home.of(emptyList(), listOf(board(shown), board(approve, machine = "vm")))
        assertEquals(listOf("task/vm/herdr-android/9"), home.needsYou.map { it.key })
        assertEquals(1, BoardGroup.waiting(listOf(board(shown), board(approve, machine = "vm"))))
    }

    @Test
    fun finishedIsNewUntilSeen() {
        val agents = listOf(
            agent("w1:p1", "done", seq = 7, completion = 7),
            agent("w2:p1", "done", seq = 3, completion = 3),
            // Seen at herdr's terminal: herdr says idle again.
            agent("w3:p1", "idle", seq = 9, completion = 9),
        )
        val home = Home.of(agents, emptyList(), seen = mapOf("devbox/w2:p1" to 3L))
        assertEquals(listOf("w1:p1" to true, "w2:p1" to false), home.done.map { it.agent.agent.paneId to it.new })
        assertEquals(1, home.fresh)
        assertEquals(listOf("w3:p1"), home.idle.map { it.agent.agent.paneId })
        // A later finish of a pane seen before is new again.
        val again = Home.of(listOf(agent("w2:p1", "done", seq = 12, completion = 12)), emptyList(), seen = mapOf("devbox/w2:p1" to 3L))
        assertTrue(again.done.single().new)
        // Mark seen: every finish marked, and panes gone dropped.
        val marks = Home.seenAll(mapOf("devbox/gone" to 1L), home, agents)
        assertEquals(mapOf("devbox/w1:p1" to 7L, "devbox/w2:p1" to 3L), marks)
        assertEquals(0, Home.of(agents, emptyList(), marks).fresh)
    }

    @Test
    fun anOlderHerdrUsesTheStateChange() {
        val a = agent("w1:p1", "done", seq = 4, completion = null)
        assertEquals(4L, a.finish)
        assertFalse(Home.of(listOf(a), emptyList(), mapOf(a.key to 4L)).done.single().new)
    }

    @Test
    fun boardGroupsByMachine() {
        val groups = BoardGroup.of(listOf(board(working, asks, todo, done, approve), board(failed, machine = "gpu")))
        assertEquals(listOf("devbox", "gpu"), groups.map { it.machineId })
        assertEquals(listOf("herdr-android/9", "api-server/3"), groups[0].needsYou.map { it.id })
        assertEquals(listOf("herdr-android/6"), groups[0].working.map { it.id })
        assertEquals(listOf("herdr-android/10"), groups[0].todo.map { it.id })
        assertEquals(1, groups[0].done)
        assertEquals(listOf("gpu"), BoardGroup.of(listOf(board(working), board(failed, machine = "gpu")), machine = "gpu").map { it.machineId })
    }

    @Test
    fun completionSeqIsRead() {
        val a = Json { ignoreUnknownKeys = true }.decodeFromString(
            AgentInfo.serializer(),
            """{"agent_status":"done","workspace_id":"w","tab_id":"t","pane_id":"p","state_change_seq":1227,"completion_seq":1227,"revision":3,"terminal_id":"x","focused":false}""",
        )
        assertEquals(1227L, a.completionSeq)
    }

    /** A machine whose agent asks [screen]'s question while [status]; keys pressed are kept. */
    private class Screen(var status: String, var screen: String) : ApiCaller {
        val keys = mutableListOf<String>()
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
            val result = when (method) {
                "session.snapshot" -> """{"type":"session_snapshot","snapshot":{"version":"0.9.5","protocol":22,"workspaces":[],"tabs":[],"panes":[],"layouts":[],"agents":[
                    {"terminal_id":"t","agent":"claude","agent_status":"$status","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":false,"revision":1}]}}"""
                "pane.read" -> """{"type":"pane_read","read":{"pane_id":"w1:p1","source":"visible","text":${JsonPrimitive(screen)},"revision":1}}"""
                "agent.send_keys", "pane.send_keys" -> {
                    keys += params.toString(); """{"type":"ok"}"""
                }
                else -> """{"type":"ok"}"""
            }
            return ApiConnection.unwrap(Json.parseToJsonElement("""{"id":"1","result":$result}""").jsonObject)
        }
    }

    private val menu = """
        |Bash command
        |  npm run db:migrate
        |Do you want to proceed?
        |❯ 1. Yes
        |  2. Yes, and don't ask again for npm run commands
        |  3. No, and tell Claude what to do differently
        |Esc to cancel · Tab to amend
    """.trimMargin()

    @Test
    fun anAnswerReadsTheScreenAgainFirst() = runBlocking {
        val fake = Screen("blocked", menu)
        AgentInput.choose(HerdrApi(fake), "w1:p1", "Do you want to proceed?", 2, "No, and tell Claude what to do differently")
        assertTrue(fake.keys.single().contains("down") && fake.keys.single().contains("enter"), fake.keys.toString())

        // Another question by now: nothing is pressed.
        val changed = Screen("blocked", menu.replace("Do you want to proceed?", "Do you want to delete the table?"))
        val e = assertFailsWith<IOException> { AgentInput.choose(HerdrApi(changed), "w1:p1", "Do you want to proceed?", 0, "Yes") }
        assertEquals("The question changed. Open the chat to answer it.", e.message)
        assertTrue(changed.keys.isEmpty())

        // Answered meanwhile at the desk.
        val gone = Screen("working", menu)
        assertFailsWith<IOException> { AgentInput.choose(HerdrApi(gone), "w1:p1", "Do you want to proceed?", 0, "Yes") }
        assertTrue(gone.keys.isEmpty())
    }
}
