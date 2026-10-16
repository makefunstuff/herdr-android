package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.api.ApiClient
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.WorktreeErrors
import com.makefunstuff.herdr.core.ssh.Duplex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers each method with a canned `result`, and remembers what was asked. */
private class FakeCaller(private val replies: Map<String, String>) : ApiCaller {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        calls += method to params
        val reply = Json.parseToJsonElement(replies[method] ?: error("unexpected $method")).jsonObject
        return ApiConnection.unwrap(reply)
    }
}

class WorktreeApiTest {
    // Shapes copied from herdr 0.9.5 replies.
    private val list = """{"id":"1","result":{"type":"worktree_list","source":{"repo_key":"/src/repo/.git","repo_name":"repo","repo_root":"/src/repo","source_checkout_path":"/src/repo","source_workspace_id":"w4"},"worktrees":[
        {"branch":"main","is_bare":false,"is_detached":false,"is_linked_worktree":false,"is_prunable":false,"label":"repo","open_workspace_id":"w4","path":"/src/repo"},
        {"branch":"feat-x","is_bare":false,"is_detached":false,"is_linked_worktree":true,"is_prunable":false,"label":"repo","path":"/home/u/.herdr/worktrees/repo/feat-x"},
        {"is_bare":false,"is_detached":true,"is_linked_worktree":true,"is_prunable":true,"label":"repo","path":"/tmp/gone"}]}}"""
    private val opened = """{"id":"2","result":{"type":"worktree_created","root_pane":{"pane_id":"w5:p1","tab_id":"w5:t1","workspace_id":"w5"},"tab":{"tab_id":"w5:t1","workspace_id":"w5"},
        "workspace":{"active_tab_id":"w5:t1","agent_status":"unknown","label":"feat-x","number":5,"pane_count":1,"tab_count":1,"workspace_id":"w5","worktree":{"checkout_path":"/home/u/.herdr/worktrees/repo/feat-x","is_linked_worktree":true,"repo_key":"/src/repo/.git","repo_name":"repo","repo_root":"/src/repo"}},
        "worktree":{"branch":"feat-x","is_bare":false,"is_detached":false,"is_linked_worktree":true,"is_prunable":false,"label":"repo","open_workspace_id":"w5","path":"/home/u/.herdr/worktrees/repo/feat-x"}}}"""
    private val reopened = opened.replace("worktree_created", "worktree_opened").replace("\"root_pane\"", "\"already_open\":true,\"root_pane\"")
    private val removed = """{"id":"3","result":{"type":"worktree_removed","forced":true,"path":"/home/u/.herdr/worktrees/repo/feat-x","workspace_id":"w5"}}"""
    private val dirty = """{"id":"4","error":{"code":"dirty_worktree_requires_force","message":"fatal: '/x' contains modified or untracked files, use --force to delete it"}}"""

    @Test
    fun listsWorktrees() = runBlocking {
        val fake = FakeCaller(mapOf("worktree.list" to list))
        val result = HerdrApi(fake).worktrees(workspaceId = "w4")
        assertEquals("repo", result.source.repoName)
        assertEquals("w4", result.source.sourceWorkspaceId)
        val (main, feat, gone) = result.worktrees
        assertEquals("main", main.title)
        assertTrue(main.isOpen)
        assertFalse(main.isLinkedWorktree)
        assertEquals("feat-x", feat.title)
        assertFalse(feat.isOpen)
        assertNull(gone.branch)
        assertEquals("detached HEAD", gone.title)
        assertTrue(gone.isPrunable)
        // Untrusted by default: the flag is only sent when the user grants it.
        assertEquals("""{"workspace_id":"w4"}""", fake.calls.single().second.toString())
    }

    @Test
    fun createsOpensAndRemoves() = runBlocking {
        val fake = FakeCaller(mapOf("worktree.create" to opened, "worktree.open" to reopened, "worktree.remove" to removed))
        val api = HerdrApi(fake)

        val created = api.createWorktree(cwd = "/src/repo", branch = "feat-x", base = "main")
        assertEquals("w5", created.workspace.workspaceId)
        assertEquals("/src/repo", created.workspace.worktree?.repoRoot)
        assertEquals("w5", created.worktree.openWorkspaceId)
        assertFalse(created.alreadyOpen)
        assertEquals("""{"cwd":"/src/repo","branch":"feat-x","base":"main","focus":false}""", fake.calls.last().second.toString())

        val again = api.openWorktree(workspaceId = "w4", path = "/home/u/.herdr/worktrees/repo/feat-x", trustRepository = true)
        assertTrue(again.alreadyOpen)
        assertEquals(
            """{"workspace_id":"w4","trust_repository":true,"path":"/home/u/.herdr/worktrees/repo/feat-x","focus":false}""",
            fake.calls.last().second.toString(),
        )

        val gone = api.removeWorktree("w5", force = true)
        assertTrue(gone.forced)
        assertEquals("""{"workspace_id":"w5","force":true}""", fake.calls.last().second.toString())
    }

    @Test
    fun worktreeEventsRefreshTheSnapshot() {
        val types = HerdrApi.subscriptionParams(emptyList()).toString()
        listOf("worktree.created", "worktree.opened", "worktree.removed").forEach { assertTrue(it in types, it) }
    }

    @Test
    fun errorsReadAsPlainMessages() = runBlocking {
        val e = assertFailsWith<HerdrApiException> { HerdrApi(FakeCaller(mapOf("worktree.remove" to dirty))).removeWorktree("w5") }
        assertTrue(WorktreeErrors.needsForce(e))
        assertFalse(WorktreeErrors.needsTrust(e))
        assertEquals("This worktree has changes that aren't committed. Removing it deletes them.", WorktreeErrors.describe(e))

        assertEquals(
            "That folder isn't inside a Git repository.",
            WorktreeErrors.describe(HerdrApiException("not_git_worktree", "Herdr worktree actions require a path inside a Git work tree")),
        )
        val untrusted = HerdrApiException(
            "worktree_list_failed",
            "fatal: detected dubious ownership in repository at '/srv/repo'\n" +
                "To add an exception for this directory, call:\n\n\tgit config --global --add safe.directory /srv/repo",
        )
        assertTrue(WorktreeErrors.needsTrust(untrusted))
        assertEquals("Git doesn't trust this repository yet, usually because another user owns the folder.", WorktreeErrors.describe(untrusted))
        assertTrue(WorktreeErrors.needsTrust(HerdrApiException("worktree_list_failed", "fatal: unsafe repository ('/srv/repo' is owned by someone else)")))
        // A branch, folder or word that merely contains "trust" is not an ownership problem.
        assertFalse(WorktreeErrors.needsTrust(HerdrApiException("worktree_create_failed", "fatal: a branch named 'trust-review' already exists")))
        assertFalse(WorktreeErrors.needsTrust(HerdrApiException("worktree_create_failed", "fatal: cannot create directory '/home/u/trust me/wt': File exists")))
        assertEquals("Branch already exists", WorktreeErrors.describe(HerdrApiException("worktree_create_failed", "fatal: branch already exists")))
        assertEquals("timed out", WorktreeErrors.describe(IOException("timed out")))
    }
}

/**
 * Drives worktree.* against a real herdr server's socket, in a throwaway repository. Runs only when
 * HERDR_LIVE_SOCKET names the socket of a herdr session that may get (and lose) a few workspaces.
 */
class LiveWorktreeTest {
    private val socket = System.getenv("HERDR_LIVE_SOCKET")

    private fun open(): Duplex {
        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))
        return object : Duplex {
            override val input: InputStream = Channels.newInputStream(channel)
            override val output: OutputStream = Channels.newOutputStream(channel)
            override fun close() = channel.close()
        }
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder("git", "-C", dir.path, *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
    }

    @Test
    fun createsListsOpensAndRemoves() {
        if (socket == null) return
        val repo = Files.createTempDirectory("herdr-wt").toFile()
        git(repo, "init", "-q", "-b", "main")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "init")
        val api = HerdrApi(ApiClient { open() })
        val opened = mutableListOf<String>()
        runBlocking {
            try {
                val err = assertFailsWith<HerdrApiException> { api.worktrees(cwd = repo.parentFile.path) }
                assertEquals(WorktreeErrors.NOT_GIT, err.code)

                val created = api.createWorktree(cwd = repo.path, branch = "phone-test", path = File(repo.parentFile, "${repo.name}-phone").path)
                opened += created.workspace.workspaceId
                assertEquals("phone-test", created.worktree.branch)
                assertTrue(created.worktree.isLinkedWorktree)

                val list = api.worktrees(cwd = repo.path)
                assertEquals(repo.canonicalPath, File(list.source.repoRoot).canonicalPath)
                val mine = list.worktrees.first { it.branch == "phone-test" }
                assertEquals(created.workspace.workspaceId, mine.openWorkspaceId)
                // herdr opens the main checkout as a workspace too when it isn't open yet.
                list.worktrees.mapNotNull { it.openWorkspaceId }.forEach { if (it !in opened) opened += it }

                val again = api.openWorktree(cwd = repo.path, branch = "phone-test")
                assertTrue(again.alreadyOpen)

                File(mine.path, "scratch.txt").writeText("dirty")
                val dirty = assertFailsWith<HerdrApiException> { api.removeWorktree(created.workspace.workspaceId) }
                assertTrue(WorktreeErrors.needsForce(dirty), dirty.toString())
                val gone = api.removeWorktree(created.workspace.workspaceId, force = true)
                assertTrue(gone.forced)
                opened -= created.workspace.workspaceId
                assertNull(api.worktrees(cwd = repo.path).worktrees.firstOrNull { it.branch == "phone-test" })
                assertFalse(File(mine.path).exists())

                // The main checkout can't be removed.
                val main = api.worktrees(cwd = repo.path).worktrees.first { !it.isLinkedWorktree }
                val notLinked = assertFailsWith<HerdrApiException> { api.removeWorktree(assertNotNull(main.openWorkspaceId)) }
                assertEquals(WorktreeErrors.NOT_LINKED, notLinked.code)
            } finally {
                opened.forEach { runCatching { api.closeWorkspace(it) } }
                repo.parentFile.listFiles { f -> f.name.startsWith(repo.name) }?.forEach { it.deleteRecursively() }
            }
        }
    }
}
