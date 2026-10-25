package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.activity.ActivityLog
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.EditCalls
import com.makefunstuff.herdr.core.chat.Transcript
import com.makefunstuff.herdr.core.chat.TranscriptJson
import com.makefunstuff.herdr.core.code.DiffLine
import com.makefunstuff.herdr.core.code.FileChange
import com.makefunstuff.herdr.core.code.FileDiff
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Edit calls as diffs in the chat, from the agents' own lines (the `.jsonl` files in `edits`: the shapes recorded
 * from Claude Code, Codex, pi and omp sessions, their file contents swapped for demo code).
 */
class EditDiffsTest {
    private fun read(agent: String, file: String, activity: Boolean = false): Transcript =
        Transcript.forAgent(agent)!!.also { t ->
            if (activity) t.activity = ActivityLog.forAgent(agent)
            javaClass.getResource("/edits/$file")!!.readText().lines().forEach(t::feed)
        }

    private fun tool(t: Transcript, id: String) = t.items().filterIsInstance<ChatItem.Tool>().single { it.id == id }

    /** A diff's lines as `+text`, `-text` and ` text`, its hunks apart by `@@`. */
    private fun lines(f: FileDiff): List<String> = f.hunks.flatMapIndexed { i, h ->
        (if (i > 0) listOf("@@") else emptyList()) + h.lines.map {
            when (it.kind) { DiffLine.Kind.Added -> "+"; DiffLine.Kind.Removed -> "-"; else -> " " } + it.text
        }
    }

    @Test
    fun claudeEditIsItsOldAndNewText() {
        val edit = tool(read("claude", "claude-edit.jsonl"), "toolu_edit")
        val f = edit.diffs.single()
        assertEquals("/home/demo/src/herdr-android/app/src/main/kotlin/demo/MachinesScreen.kt", f.path)
        assertEquals(FileChange.Modified, f.change)
        assertEquals(
            listOf(
                "-    LazyColumn(", "-        state = list,",
                "+    PullToRefreshBox(refreshing, onRefresh = { hosts.retryAll() }) {", "+    LazyColumn(", "+        state = list,",
            ),
            lines(f),
        )
    }

    @Test
    fun theResultsDiffWinsWithItsLineNumbers() {
        val t = read("claude", "claude-edit.jsonl", activity = true)
        val line = ChatTurns.line(tool(t, "toolu_edit"), ChatTurns.Steps(t.activity!!.turns()))
        val f = line.diffs.single()
        assertEquals(41, f.hunks.single().newStart)
        assertEquals(listOf(41, 42, 43, 44), f.hunks.single().lines.map { it.new })
        assertEquals(1 to 0, line.added to line.removed)
    }

    @Test
    fun whileItRunsItsPathIsFromTheRepo() {
        val t = read("claude", "claude-edit.jsonl", activity = true)
        val running = tool(t, "toolu_edit").copy(state = com.makefunstuff.herdr.core.chat.ToolState.Running)
        val steps = ChatTurns.Steps(t.activity!!.turns().map { turn -> turn.copy(steps = turn.steps.map { it.copy(diff = null) }) })
        assertEquals("app/src/main/kotlin/demo/MachinesScreen.kt", ChatTurns.line(running, steps).diffs.single().path)
    }

    @Test
    fun claudeMultiEditIsAHunkPerEdit() {
        val f = tool(read("claude", "claude-multiedit.jsonl"), "toolu_multi").diffs.single()
        assertEquals(
            listOf(
                "-data class HostState(", "+data class HostState(", "+    val updatedAt: Long = 0,",
                "@@",
                "-fun stale() = false", "+fun stale(now: Long) = now - updatedAt > STALE_MS",
            ),
            lines(f),
        )
    }

    @Test
    fun claudeWriteIsTheWholeFileAdded() {
        val t = read("claude", "claude-write.jsonl")
        val f = tool(t, "toolu_write").diffs.single()
        // It may replace a file: its whole text, not said to be new.
        assertEquals(FileChange.Modified, f.change)
        assertEquals(0, f.removed)
        assertEquals(4, f.added)
        assertEquals("+package demo", lines(f).first())
        // Counted on its row while it runs, before any result says so.
        val line = ChatTurns.line(tool(t, "toolu_write"), ChatTurns.Steps(emptyList()))
        assertEquals(4 to 0, line.added to line.removed)
    }

    @Test
    fun claudeNotebookAndTextEditorCalls() {
        fun of(name: String, json: String) = EditCalls.of(name, TranscriptJson.parseToJsonElement(json).jsonObject)
        val cell = of("NotebookEdit", """{"notebook_path":"/home/demo/n.ipynb","cell_id":"c1","new_source":"print(1)\nprint(2)","edit_mode":"replace"}""")!!.single()
        assertEquals(listOf("+print(1)", "+print(2)"), lines(cell))
        assertEquals(null, of("NotebookEdit", """{"notebook_path":"/home/demo/n.ipynb","cell_id":"c1","edit_mode":"delete"}"""))
        val replace = of("str_replace_based_edit_tool", """{"command":"str_replace","path":"/repo/a.py","old_str":"x = 1","new_str":"x = 2"}""")!!.single()
        assertEquals(listOf("-x = 1", "+x = 2"), lines(replace))
        val create = of("str_replace_based_edit_tool", """{"command":"create","path":"/repo/b.py","file_text":"y = 3\n"}""")!!.single()
        assertEquals(listOf("+y = 3"), lines(create))
        assertEquals(FileChange.Added, create.change)
    }

    @Test
    fun codexFileChangeIsEachFile() {
        val t = read("codex", "codex-filechange.jsonl")
        val (update, add) = tool(t, "exec-change-1").diffs
        assertEquals("/home/demo/site/src/pages/index.astro", update.path)
        assertEquals(listOf(" import Layout from '../layouts/Layout.astro';", "+import Hero from '../components/Hero.astro';", " const title = 'Home';"), lines(update))
        assertEquals(listOf(5, 6, 7), update.hunks.single().lines.map { it.new })
        assertEquals(FileChange.Added, add.change)
        assertEquals(3, add.added)
        val deleted = tool(t, "exec-change-2").diffs.single()
        assertEquals(FileChange.Deleted, deleted.change)
        assertEquals("/home/demo/site/src/old.css", deleted.path)
        assertEquals(listOf("-body { margin: 0; }"), lines(deleted))
    }

    @Test
    fun codexApplyPatchTextIsItsFiles() {
        val patch = tool(read("codex", "codex-apply-patch.jsonl"), "call_patch")
        val (moved, added, deleted) = patch.diffs
        assertEquals(FileChange.Renamed, moved.change)
        assertEquals("/home/demo/site/astro.config.mjs", moved.oldPath)
        assertEquals("/home/demo/site/astro.config.ts", moved.newPath)
        assertEquals("export default defineConfig({", moved.hunks.single().section)
        assertEquals(listOf("   site: 'https://example.dev',", "-  output: 'static',", "+  output: 'server',", " ", "   integrations: [],"), lines(moved))
        assertEquals(FileChange.Added, added.change)
        assertEquals(listOf("+22"), lines(added))
        assertEquals(FileChange.Deleted, deleted.change)
        assertEquals("/home/demo/site/netlify.toml", deleted.path)
        // The row names the files, not the patch's first line.
        assertEquals("/home/demo/site/astro.config.ts, /home/demo/site/.nvmrc, /home/demo/site/netlify.toml", patch.summary)
    }

    @Test
    fun codexPatchInAScriptIsReadFromItsString() {
        val exec = tool(read("codex", "codex-exec-patch.jsonl"), "call_exec")
        val f = exec.diffs.single()
        assertEquals("/home/demo/site/README.md", f.path)
        assertEquals(listOf("-Run `npm start`.", "+Run `npm run dev`, then open \"localhost:4321\"."), lines(f))
    }

    @Test
    fun codexPatchThroughTheShell() {
        val shell = tool(read("codex", "codex-shell-patch.jsonl"), "call_shell")
        val f = shell.diffs.single()
        assertEquals("src/main.rs", f.path)
        assertEquals(listOf("-    println!(\"hi\");", "+    println!(\"hello\");", " }"), lines(f))
    }

    @Test
    fun piEditIsAHunkPerEditThenPisOwnDiff() {
        val t = read("pi", "pi-edit.jsonl", activity = true)
        val edit = tool(t, "call_pi_edit")
        val f = edit.diffs.single()
        assertEquals("server/routes.ts", f.path)
        assertEquals(2, f.hunks.size)
        assertEquals("+api.get('/runs', (c) => c.json(listRuns()));", lines(f).last())
        // Once it came back, its result's diff with the line numbers.
        val line = ChatTurns.line(edit, ChatTurns.Steps(t.activity!!.turns()))
        assertEquals(listOf(9, 10, 10, 11), line.diffs.single().hunks.first().lines.map { it.new ?: it.old })
        assertEquals(2 to 2, line.added to line.removed)
    }

    @Test
    fun piWriteIsTheWholeFile() {
        val f = tool(read("pi", "pi-write.jsonl"), "call_pi_write").diffs.single()
        assertEquals("exercises/bit-manipulation/expected.txt", f.path)
        assertEquals(listOf("+checksum ece844ed"), lines(f))
    }

    @Test
    fun ompEditAndTheShapesLeftRaw() {
        val t = read("omp", "omp-edit.jsonl")
        val f = tool(t, "call_omp_edit").diffs.single()
        assertEquals("README.md", f.path)
        assertEquals(listOf("-`src/` is 2.7k lines:", "+`src/` is 2.8k lines:"), lines(f))
        // Line anchors without the old text, and a plan handed in on a virtual path: their raw arguments.
        val anchors = tool(t, "call_omp_hashline")
        assertTrue(anchors.diffs.isEmpty())
        assertTrue("CUT 422.=423" in anchors.input.orEmpty(), anchors.input)
        assertTrue(tool(t, "call_omp_propose").diffs.isEmpty())
    }

    @Test
    fun otherToolsHaveNoDiff() {
        fun of(name: String, json: String) = EditCalls.of(name, TranscriptJson.parseToJsonElement(json).jsonObject)
        assertEquals(null, of("Bash", """{"command":"ls","description":"List files"}"""))
        assertEquals(null, of("slack_send", """{"path":"#general","content":"hi"}"""))
        // A Write whose text mentions a patch writes that text; it isn't read as one.
        val write = of("Write", """{"file_path":"/repo/notes.md","content":"*** Begin Patch\n*** Add File: x\n+y\n*** End Patch\n"}""")!!.single()
        assertEquals("/repo/notes.md", write.path)
        assertEquals(4, write.added)
        // A patch of omp's line anchors names no file.
        assertEquals(null, EditCalls.patch("*** Begin Patch\n[retire-old-pod.tf#3755]\nREM\n*** End Patch\n"))
    }
}
