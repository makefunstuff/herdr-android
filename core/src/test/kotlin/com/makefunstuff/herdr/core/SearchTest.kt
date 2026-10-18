package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.IntegrationInfo
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.api.WorktreeInfo
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.chat.ChatSearch
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.search.Search
import com.makefunstuff.herdr.core.search.SearchFilter
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.start.AgentLaunch
import com.makefunstuff.herdr.core.start.Folder
import com.makefunstuff.herdr.core.start.StartWork
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTest {
    private fun task(json: String, key: String): BoardTask = BoardTasks.task(Json.parseToJsonElement(json).jsonObject, key)!!

    private val asks = task("""{"id":"api-server/3","title":"Speed up /jobs on large tables","state":"blocked","stage":"working","kind":"question"}""", "API-3")
    private val queued = task("""{"id":"api-server/7","title":"Paginate /jobs in the CLI","state":"idle","stage":"queued"}""", "API-7")
    private val working = task("""{"id":"herdr-android/6","title":"Screenshots","state":"working","stage":"working"}""", "AND-6")
    private val done = task("""{"id":"website/2","title":"Install page","state":"done","stage":"done"}""", "WEB-2")

    private fun agent(pane: String, ws: String, status: String, title: String, name: String? = null, kind: String = "claude") =
        AgentInfo(name = name, agent = kind, title = title, agentStatusRaw = status, workspaceId = ws, tabId = "$ws:t1", paneId = pane)

    private val devbox = HostState(
        Machine("devbox", "devbox", "devbox.lan", user = "demo"),
        HostStatus.Online,
        SessionSnapshot(
            workspaces = listOf(
                WorkspaceInfo("w1", label = "herdr-android", worktree = WorktreeInfo(repoName = "herdr-android", checkoutPath = "/home/demo/src/herdr-android")),
                WorkspaceInfo("w2", label = "api-server"),
            ),
            panes = listOf(
                PaneInfo("w1:p1", workspaceId = "w1", tabId = "w1:t1", cwd = "/home/demo/src/herdr-android"),
                PaneInfo("w2:p1", workspaceId = "w2", tabId = "w2:t1", cwd = "/home/demo/src/api-server"),
            ),
            agents = listOf(
                agent("w1:p1", "w1", "working", "Show when each machine last answered"),
                agent("w2:p1", "w2", "blocked", "Speed up /jobs on large tables", name = "sw-api-server-3"),
            ),
        ),
        workspaceMeta = mapOf("w1" to mapOf("branch" to "work/9-last-answered")),
    )
    private val ci = HostState(
        Machine("ci", "ci-runner", "ci.lan", user = "demo"),
        HostStatus.Online,
        SessionSnapshot(
            workspaces = listOf(WorkspaceInfo("w1", label = "api-server")),
            agents = listOf(agent("w1:p1", "w1", "working", "Batch the /jobs export", kind = "codex")),
        ),
    )
    private val boards = listOf(HomeBoard("devbox", "devbox", listOf(asks, queued, working, done)))

    @Test
    fun everyWordHasToBeThereAndTheNameCountsMost() {
        assertEquals(0, Search.score(listOf("jobs", "export"), listOf("Speed up /jobs", "devbox")))
        assertTrue(Search.score(listOf("jobs"), listOf("jobs export")) > Search.score(listOf("jobs"), listOf("speed up /jobs")))
        assertTrue(Search.score(listOf("jobs"), listOf("speed up /jobs")) > Search.score(listOf("jobs"), listOf("x", "the /jobs")))
        assertTrue(Search.score(listOf("jobs"), listOf("x", "the /jobs")) > Search.score(listOf("obs"), listOf("x", "the /jobs")))
        assertEquals(1, Search.score(emptyList(), listOf("anything")))
    }

    @Test
    fun findsAgentsTasksMachinesAndWorkspaces() {
        val r = Search.of("jobs", listOf(devbox, ci), boards)
        // The agent asking first, then the one at work; an agent's task gives it its title and key.
        assertEquals(listOf("Speed up /jobs on large tables", "Batch the /jobs export"), r.agents.map { it.title })
        assertEquals("API-3", r.agents.first().task?.name)
        assertEquals(listOf("API-3", "API-7"), r.tasks.map { it.task.name })
        assertTrue(r.machines.isEmpty())
        assertEquals(4, r.counts[SearchFilter.All])
        assertEquals(2, r.counts[SearchFilter.NeedsYou])
        assertEquals(1, r.counts[SearchFilter.Working])

        val ws = Search.of("api", listOf(devbox, ci), boards).workspaces
        assertEquals(listOf("devbox" to "api-server", "ci" to "api-server"), ws.map { it.machineId to it.name })
        assertEquals("/home/demo/src/api-server", ws.first().folder)
        // By the branch its integration reports, and by its checkout.
        assertEquals(listOf("herdr-android"), Search.of("last-answered", listOf(devbox), boards).workspaces.map { it.name })
        assertEquals("/home/demo/src/herdr-android", Search.of("herdr-android", listOf(devbox), boards).workspaces.single().folder)
        assertEquals(listOf("ci"), Search.of("ci-run", listOf(devbox, ci), boards).machines.map { it.machineId })
    }

    @Test
    fun aChipShowsOnlyItsAgentsAndTasks() {
        val r = Search.of("jobs", listOf(devbox, ci), boards, SearchFilter.Working)
        assertEquals(listOf("Batch the /jobs export"), r.agents.map { it.title })
        assertTrue(r.tasks.isEmpty())
        assertTrue(r.workspaces.isEmpty())
        // The counts stay those of the query, so the chips don't change as one is chosen.
        assertEquals(4, r.counts[SearchFilter.All])
    }

    @Test
    fun fromAMachineOnlyItsOwn() {
        val r = Search.of("jobs", listOf(devbox, ci), boards, machineId = "ci")
        assertEquals(listOf("Batch the /jobs export"), r.agents.map { it.title })
        assertTrue(r.tasks.isEmpty())
    }

    @Test
    fun nothingTypedListsTheAgentsAndTheTasksThatWait() {
        val r = Search.of("  ", listOf(devbox, ci), boards)
        assertEquals("Speed up /jobs on large tables", r.agents.first().title)
        assertEquals(3, r.agents.size)
        assertEquals(listOf("API-3"), r.tasks.map { it.task.name })
        assertTrue(r.machines.isEmpty() && r.workspaces.isEmpty())
        // The chips count what is listed.
        assertEquals(4, r.counts[SearchFilter.All])
        assertEquals(2, r.counts[SearchFilter.NeedsYou])
    }
}

class ChatSearchTest {
    private val transcript = """
        {"type": "user", "uuid": "u1", "message": {"role": "user", "content": "The /jobs endpoint takes 4 s on the big tenant. Find out why and fix it."}, "timestamp": "2026-10-17T07:31:05.000Z"}
        {"type": "assistant", "uuid": "u2", "message": {"role": "assistant", "content": [{"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": "psql -c 'SELECT * FROM jobs'"}}]}, "timestamp": "2026-10-17T07:31:14.000Z"}
        {"type": "assistant", "uuid": "u3", "message": {"role": "assistant", "content": [{"type": "text", "text": "It's a sequential scan over 1.2 M rows: `jobs` has no index on `created_at`. I wrote a migration that adds one concurrently, so the table stays writable."}]}, "timestamp": "2026-10-17T07:31:39.100Z"}
        {"type": "assistant", "uuid": "u4", "message": {"role": "assistant", "content": [{"type": "text", "text": "Nothing about that here."}]}, "timestamp": "2026-10-17T07:32:00.000Z"}
    """.trimIndent()

    @Test
    fun findsWhatWasSaidInTheChatsOnTheMachine() {
        val dir = Files.createTempDirectory("chats")
        val file = dir.resolve("api.jsonl").toFile().apply { writeText(transcript + "\n") }
        val command = ChatSearch.command(
            "JOBS",
            listOf(
                ChatSearch.Chat("w2:p1", AgentSessionRef(agent = "claude", kind = "path", value = file.path)),
                ChatSearch.Chat("w9:p1", AgentSessionRef(agent = "claude", kind = "path", value = dir.resolve("gone.jsonl").toString())),
            ),
        )!!
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        val hits = ChatSearch.parse(out, "JOBS", mapOf("w2:p1" to "claude"))
        // The newest first; the tool call's words don't count.
        assertEquals(listOf(false, true), hits.map { it.you })
        assertEquals("w2:p1", hits.first().paneId)
        assertTrue(hits.first().snippet.contains("jobs has no index"), hits.first().snippet)
        assertEquals(java.time.Instant.parse("2026-10-17T07:31:39.100Z").toEpochMilli(), hits.first().at)
    }

    @Test
    fun aShortQueryOrAChatItCantReadAsksNothing() {
        val chat = ChatSearch.Chat("w1:p1", AgentSessionRef(agent = "claude", kind = "id", value = "abc"))
        assertNull(ChatSearch.command("jo", listOf(chat)))
        assertNull(ChatSearch.command("jobs", listOf(chat.copy(session = chat.session.copy(agent = "gemini")))))
        assertNull(ChatSearch.command("jobs", emptyList()))
    }

    @Test
    fun aSnippetIsALineAroundTheWords() {
        val text = "First a long run of words that say nothing much at all before it. Then **the jobs** table, and much more after that, on and on until the end."
        val s = ChatSearch.snippet(text, "jobs", width = 50)
        assertTrue(s.startsWith("…") && s.endsWith("…"), s)
        assertTrue("the jobs table" in s, s)
        assertTrue(s.length <= 52, s)
        assertEquals("Short jobs", ChatSearch.snippet("Short\n\njobs", "jobs"))
    }
}

class StartWorkTest {
    @Test
    fun aSentenceBecomesATitleAndTheRestItsDescription() {
        assertEquals("Add a last answered line" to "", StartWork.task("  Add a last answered line  "))
        val long = "Add a “last answered 2 min ago” line to each machine card, and a test for it, and make the widget show it too"
        val (title, desc) = StartWork.task(long)
        assertTrue(title.length <= StartWork.TITLE + 1 && title.endsWith("…"), title)
        assertEquals(long, desc)
        assertEquals("Fix login" to "Fix login\nIt fails on the second try", StartWork.task("Fix login\nIt fails on the second try"))
    }

    @Test
    fun anAgentIsNamedForItsFirstWords() {
        assertEquals("add-last-answered", StartWork.agentName("Add a “last answered” line", "claude", emptyList()))
        assertEquals("add-last-answered-3", StartWork.agentName("Add the last answered", "claude", listOf("add-last-answered", "add-last-answered-2")))
        assertEquals("codex", StartWork.agentName("", "codex", emptyList()))
    }

    @Test
    fun onlyTheAgentsInstalledAreOffered() {
        val list = listOf(
            IntegrationInfo("grok", available = true),
            IntegrationInfo("codex", available = true, state = "current"),
            IntegrationInfo("devin", available = false),
            IntegrationInfo("antigravity_cli", available = true),
            IntegrationInfo("claude", available = true, state = "current"),
        )
        assertEquals(listOf("claude", "codex", "grok", "agy"), StartWork.installed(list))
    }

    @Test
    fun recentFoldersAreTheOnesUsedThenTheWorkspaces() {
        val snap = SessionSnapshot(
            workspaces = listOf(
                WorkspaceInfo("w1", worktree = WorktreeInfo(checkoutPath = "/home/demo/src/herdr-android")),
                WorkspaceInfo("w2"),
                WorkspaceInfo("w3"),
                WorkspaceInfo("w4", worktree = WorktreeInfo(repoRoot = "/home/demo/src/herdr-android", checkoutPath = "/home/demo/.worktrees/work-6", isLinkedWorktree = true)),
                WorkspaceInfo("w5"),
            ),
            panes = listOf(
                PaneInfo("w2:p1", workspaceId = "w2", tabId = "t", cwd = "/home/demo/src/api-server/"), PaneInfo("w3:p1", workspaceId = "w3", tabId = "t"),
                PaneInfo("w5:p1", workspaceId = "w5", tabId = "t", cwd = "/home/demo/.herdr/worktrees/website/work-4"),
            ),
        )
        assertEquals(
            listOf("/home/demo/notes", "/home/demo/src/api-server", "/home/demo/src/herdr-android"),
            StartWork.recentFolders(snap, listOf("/home/demo/notes", "/home/demo/src/api-server")),
        )
        assertEquals("w2", StartWork.workspaceIn(snap, "/home/demo/src/api-server"))
        assertNull(StartWork.workspaceIn(snap, "/home/demo/notes"))
        assertEquals("api-server", StartWork.folderName("/home/demo/src/api-server/"))
    }

    @Test
    fun theFolderBrowserListsFoldersWithTheRepositoriesFirst() {
        val home = Files.createTempDirectory("home").toFile()
        java.io.File(home, "notes").mkdirs()
        java.io.File(home, "src/api-server/.git").mkdirs()
        java.io.File(home, ".cache").mkdirs()
        java.io.File(home, "file.txt").writeText("x")
        java.io.File(home, "Zed/.git").mkdirs()
        fun list(path: String?): Folder? {
            val p = ProcessBuilder("sh", "-c", Folder.command(path)).redirectErrorStream(true).apply { environment()["HOME"] = home.path }.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            return Folder.parse(out)
        }
        val top = list(null)!!
        assertEquals(home.canonicalPath, java.io.File(top.path).canonicalPath)
        assertEquals(listOf("Zed" to true, "notes" to false, "src" to false), top.folders.map { it.name to it.repo })
        val src = list("~/src")!!
        assertEquals(listOf("api-server" to true), src.folders.map { it.name to it.repo })
        assertEquals(java.io.File(top.path).canonicalPath, java.io.File(src.parent!!).canonicalPath)
        assertTrue(list("~/src/api-server")!!.folders.isEmpty())
        assertNull(list("/no/such/folder"))
        assertEquals("/", Folder("/home", emptyList()).parent)
        assertNull(Folder("/", emptyList()).parent)
        assertEquals("/home/demo", Folder("/home", emptyList()).child("demo"))
    }

    /** herdr as an agent launch sees it: the calls made, in order. */
    private class Herdr : ApiCaller {
        val calls = mutableListOf<String>()
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
            calls += "$method $params"
            val result = when (method) {
                "workspace.create" -> """{"type":"workspace_created","workspace":{"workspace_id":"w9"},"root_pane":{"pane_id":"w9:p1"}}"""
                "tab.create" -> """{"type":"tab_created","tab":{"tab_id":"w2:t2"},"root_pane":{"pane_id":"w2:p5"}}"""
                "pane.split" -> """{"type":"pane_info","pane":{"pane_id":"w1:p3","workspace_id":"w1","tab_id":"w1:t1"}}"""
                "session.snapshot" -> """{"type":"session_snapshot","snapshot":{"workspaces":[],"tabs":[],"layouts":[],"agents":[],"panes":[
                    {"pane_id":"w9:p1","workspace_id":"w9","tab_id":"w9:t1"},{"pane_id":"w2:p5","workspace_id":"w2","tab_id":"w2:t2"}]}}"""
                else -> """{"type":"ok"}"""
            }
            return ApiConnection.unwrap(Json.parseToJsonElement("""{"id":"1","result":$result}""").jsonObject)
        }
    }

    @Test
    fun anAgentNowOpensWhereTheFolderIsAndIsAskedFirst() = runBlocking {
        val snap = SessionSnapshot(
            workspaces = listOf(WorkspaceInfo("w2")),
            panes = listOf(PaneInfo("w2:p1", workspaceId = "w2", tabId = "w2:t1", cwd = "/home/demo/src/api-server")),
        )
        val herdr = Herdr()
        assertEquals("w2:p5", AgentLaunch.inFolder(HerdrApi(herdr), snap, "/home/demo/src/api-server", "claude", "speed-up-jobs", " Speed up /jobs "))
        assertTrue(herdr.calls[0].startsWith("tab.create") && "\"workspace_id\":\"w2\"" in herdr.calls[0], herdr.calls[0])
        val start = herdr.calls.first { it.startsWith("agent.start") }
        assertEquals("""agent.start {"name":"speed-up-jobs","kind":"claude","pane_id":"w2:p5"}""", start)
        assertEquals("""agent.prompt {"target":"w2:p5","text":"Speed up /jobs"}""", herdr.calls.last())

        // A folder no workspace sits in: a workspace of its own, named after it; nothing said, nothing sent.
        val other = Herdr()
        assertEquals("w9:p1", AgentLaunch.inFolder(HerdrApi(other), snap, "/home/demo/notes", "codex", "codex", ""))
        assertTrue(other.calls[0].startsWith("workspace.create") && "\"label\":\"notes\"" in other.calls[0], other.calls[0])
        assertTrue(other.calls.none { it.startsWith("agent.prompt") })

        // From a machine's sheet: a split beside the focused pane.
        val beside = Herdr()
        assertEquals("w1:p3", AgentLaunch.beside(HerdrApi(beside), "w1:p1", "claude", "reviewer", "Review the last commit"))
        assertTrue(beside.calls.any { it.startsWith("pane.split") && "\"target_pane_id\":\"w1:p1\"" in it })
        assertTrue(beside.calls.any { it == """agent.start {"name":"reviewer","kind":"claude","pane_id":"w1:p3"}""" })
    }
}
