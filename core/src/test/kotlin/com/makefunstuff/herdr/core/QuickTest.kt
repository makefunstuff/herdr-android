package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.quick.QuickArgs
import com.makefunstuff.herdr.core.quick.QuickClient
import com.makefunstuff.herdr.core.quick.QuickCommand
import com.makefunstuff.herdr.core.quick.QuickContext
import com.makefunstuff.herdr.core.quick.QuickHeader
import com.makefunstuff.herdr.core.quick.QuickKind
import com.makefunstuff.herdr.core.quick.QuickList
import com.makefunstuff.herdr.core.quick.QuickPrompt
import com.makefunstuff.herdr.core.quick.QuickReader
import com.makefunstuff.herdr.core.quick.QuickScope
import com.makefunstuff.herdr.core.quick.QuickWorker
import com.makefunstuff.herdr.core.quick.RunState
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickTest {
    // ---- headers ------------------------------------------------------------------------------------

    @Test
    fun scriptHeader() {
        val c = QuickHeader.parse(
            "/repo/.herdr/commands/deploy.sh", QuickScope.Project,
            "#!/bin/sh\n# name: Deploy staging\n# desc: Builds and pushes it\n# icon: Rocket\n# confirm: true\n# args: Which branch?\n#\n# Some words.\nset -e\n# name: not this\n",
        )
        assertEquals("Deploy staging", c.name)
        assertEquals("Builds and pushes it", c.description)
        assertEquals("rocket", c.icon)
        assertTrue(c.confirm)
        assertEquals("Which branch?", c.args)
        assertEquals(QuickKind.Script, c.kind)
        assertEquals("/repo/.herdr/commands", c.folder)
        assertEquals("Project:Deploy staging", c.key)
    }

    @Test
    fun noHeaderUsesTheFileName() {
        val c = QuickHeader.parse("/h/.config/herdr/commands/run-tests.sh", QuickScope.Global, "#!/bin/bash\nmake test\n")
        assertEquals("run-tests", c.name)
        assertEquals("", c.description)
        assertFalse(c.confirm)
        assertNull(c.args)
        assertEquals("cleanup", QuickHeader.parse("/x/cleanup", QuickScope.Global, "echo hi").name)
    }

    @Test
    fun promptHeaderAndBody() {
        val c = QuickHeader.parse(
            "/repo/.herdr/commands/todos.prompt.md", QuickScope.Project,
            "# name: Find TODOs\n# model: pi/deepseek/deepseek-flash\n# tools: read-only, web\n# timeout: 90s\n# tokens: 50k\n# args:\n\n# The task\n\nList every TODO in {{repo}}. {{args}}\n",
        )
        assertEquals("Find TODOs", c.name)
        assertEquals(QuickKind.Prompt, c.kind)
        assertEquals("pi/deepseek/deepseek-flash", c.model)
        assertEquals(listOf("read-only", "web"), c.tools)
        assertEquals(2, c.timeoutMinutes)
        assertEquals(50_000, c.maxTokens)
        assertEquals("Arguments", c.args)
        assertEquals("# The task\n\nList every TODO in {{repo}}. {{args}}", c.body)
        // Without a header: the whole file is the prompt, and the name is the file's.
        val bare = QuickHeader.parse("/x/explain-error.prompt.md", QuickScope.Global, "# Explain\nWhy does {{selection}} fail?")
        assertEquals("explain-error", bare.name)
        assertEquals("# Explain\nWhy does {{selection}} fail?", bare.body)
    }

    @Test
    fun projectCommandsComeFirstAndWinByName() {
        fun cmd(name: String, scope: QuickScope, path: String) = QuickCommand(name, QuickKind.Script, scope, path)
        val list = QuickList.of(
            listOf(
                cmd("Test", QuickScope.Project, "/wt/.herdr/commands/test"),
                cmd("build", QuickScope.Project, "/wt/.herdr/commands/build"),
                cmd("test", QuickScope.Project, "/main/.herdr/commands/test"),
                cmd("Lint", QuickScope.Project, "/main/.herdr/commands/lint"),
                cmd("BUILD", QuickScope.Global, "/h/.config/herdr/commands/build"),
                cmd("disk", QuickScope.Global, "/h/.config/herdr/commands/disk"),
            ),
        )
        assertEquals(listOf("/wt/.herdr/commands/build", "/main/.herdr/commands/lint", "/wt/.herdr/commands/test", "/h/.config/herdr/commands/disk"), list.map { it.path })
    }

    @Test
    fun placeholdersAndArguments() {
        val ctx = QuickContext(cwd = "/wt", repo = "/wt", branch = "work/7", clipboard = "boom", date = "2026-10-18")
        assertEquals("Explain boom on work/7 in /wt {{nope}}", QuickPrompt.fill("Explain {{selection}} on {{ branch }} in {{worktree}} {{nope}}", "", ctx))
        assertEquals("Summarise\n\nsince Monday", QuickPrompt.fill("Summarise", "since Monday", ctx))
        assertEquals("Summarise since Monday.", QuickPrompt.fill("Summarise {{args}}.", "since Monday", ctx))
        assertEquals(listOf("deploy", "two words", "it's", "a\"b"), QuickArgs.split("deploy \"two words\" 'it'\\''s' a\\\"b"))
        assertEquals(emptyList(), QuickArgs.split("   "))
    }

    // ---- the commands, run for real with sh ---------------------------------------------------------

    private fun sh(dir: File?, vararg cmd: String, env: Map<String, String> = emptyMap()): String {
        val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")}: $out" }
        return out
    }

    private fun git(dir: File, vararg args: String) = sh(dir, "git", "-c", "user.name=Demo", "-c", "user.email=demo@example.com", "-c", "init.defaultBranch=main", *args)

    /** A machine: a home folder with global commands, a repository with project ones, and a worktree. */
    private class Machine(val home: File, val main: File, val worktree: File)

    private fun machine(): Machine {
        val base = Files.createTempDirectory("herdr-quick").toFile().canonicalFile
        val home = File(base, "home").apply { mkdirs() }
        val global = File(home, ".config/herdr/commands").apply { mkdirs() }
        File(global, "disk").writeText("#!/bin/sh\n# desc: How full the disks are\n# icon: hard-drive\ndf -h .\n")
        File(global, "build").writeText("#!/bin/sh\n# name: Build\necho global build\n")
        File(global, "notes.md").writeText("# not a command\n")
        File(global, "summary.prompt.md").writeText("# name: Summarise today\n# model: pi/x/y\n\nWhat changed today in {{repo}}?\n")
        listOf("disk", "build", "notes.md").forEach { File(global, it).setExecutable(true) }
        File(global, "not-executable").writeText("#!/bin/sh\necho no\n")
        val main = File(base, "app").apply { mkdirs() }
        git(main, "init", "-q")
        File(main, "README.md").writeText("# App\n")
        val cmds = File(main, ".herdr/commands").apply { mkdirs() }
        File(cmds, "build").writeText("#!/bin/sh\n# name: Build\n# desc: The project's build\necho \"project build in \$(pwd) args=\$# [\$1] [\$2] repo=\$HERDR_REPO raw=\$HERDR_ARGS\"\necho to stderr >&2\nexit 3\n")
        File(cmds, "build").setExecutable(true)
        File(cmds, "slow").writeText("#!/bin/sh\n# confirm: yes\necho started\nsleep 30\necho never\n")
        File(cmds, "slow").setExecutable(true)
        git(main, "add", ".")
        git(main, "commit", "-q", "-m", "First")
        val wt = File(base, "app-7")
        git(main, "worktree", "add", "-q", "-b", "work/7", wt.absolutePath)
        // Only in the worktree: a command not committed yet.
        File(wt, ".herdr/commands/greet").apply { writeText("#!/bin/sh\n# name: Greet\necho hi\n"); setExecutable(true) }
        return Machine(home, main, wt)
    }

    private fun client(m: Machine, path: String? = null): QuickClient {
        fun env() = buildMap {
            put("HOME", m.home.path)
            put("XDG_CONFIG_HOME", File(m.home, ".config").path)
            put("XDG_STATE_HOME", File(m.home, ".local/state").path)
            if (path != null) put("PATH", path + ":" + System.getenv("PATH"))
        }
        fun exec(command: String, input: String?): CommandResult {
            val pb = ProcessBuilder("sh", "-c", command).directory(m.home)
            pb.environment().putAll(env())
            val p = pb.start()
            if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor(60, TimeUnit.SECONDS)
            return CommandResult(p.exitValue(), out, err)
        }
        return QuickClient({ c, _ -> exec(c, null) }, { c, input, _ -> exec(c, input) })
    }

    private suspend fun waitFor(what: String, done: suspend () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "Timed out waiting for $what" }
            kotlinx.coroutines.delay(100)
        }
    }

    @Test
    fun listsAWorktreesCommandsThenTheMachines() = runBlocking {
        val m = machine()
        val q = client(m)
        val listing = q.list(m.worktree.path)
        assertEquals(m.worktree.path, listing.repo)
        assertEquals(m.main.path, listing.mainRepo)
        assertEquals("work/7", listing.branch)
        assertEquals(listOf("Build", "Greet", "slow", "disk", "Summarise today"), listing.commands.map { it.name })
        val build = listing.commands.first()
        assertEquals(QuickScope.Project, build.scope)
        assertEquals("${m.worktree.path}/.herdr/commands/build", build.path)
        assertEquals("The project's build", build.description)
        assertTrue(listing.commands.first { it.name == "slow" }.confirm)
        val summary = listing.commands.last()
        assertEquals(QuickKind.Prompt, summary.kind)
        assertEquals("What changed today in {{repo}}?", summary.body)
        assertEquals("hard-drive", listing.commands.first { it.name == "disk" }.icon)
        assertEquals("${m.worktree.path}/.herdr/commands", listing.projectFolder)
        assertEquals("${m.home.path}/.config/herdr/commands", listing.globalFolder)
        // No folder: the machine's alone.
        assertEquals(listOf("Build", "disk", "Summarise today"), q.list("").commands.map { it.name })
        // A folder outside Git: its own .herdr/commands, if any.
        assertEquals("", q.list(m.home.path).repo.let { if (it == m.home.path) "" else it })
    }

    @Test
    fun runsAScriptDetachedAndReadsItsOutput() = runBlocking {
        val m = machine()
        val q = client(m)
        val listing = q.list(m.main.path)
        val build = listing.commands.first { it.name == "Build" }
        val ctx = QuickContext(cwd = m.main.path, repo = listing.repo)
        val id = QuickClient.newId(1_792_000_000_000, 0xbeef)
        assertEquals("q1792000000000-beef", id)
        q.start(id, build, ctx, "one \"two words\"", at = 1)
        waitFor("the build to end") { q.read(id).run?.state?.finished == true }
        val read = q.read(id)
        val run = read.run!!
        assertEquals(RunState.Failed, run.state)
        assertEquals(3, run.exit)
        assertEquals("Build", run.name)
        assertEquals(QuickKind.Script, run.kind)
        assertEquals(m.main.path, run.cwd)
        assertEquals("one \"two words\"", run.args)
        assertTrue(read.text.startsWith("project build in ${m.main.path} args=2 [one] [two words] repo=${m.main.path} raw=one \"two words\"\n"), read.text)
        assertTrue("to stderr" in read.text)
        assertEquals(read.text.toByteArray().size.toLong(), run.size)
        assertEquals("to stderr", run.preview)
        assertTrue(run.started > 0 && run.ended >= run.started)
        // Reading on from where it was.
        val more = q.read(id, offset = run.size - 10)
        assertEquals("to stderr\n", more.text)

        // A slow one, stopped.
        val slow = listing.commands.first { it.name == "slow" }
        val id2 = QuickClient.newId(1_792_000_001_000, 1)
        q.start(id2, slow, ctx, "", at = 2)
        waitFor("it to print") { q.read(id2).text.contains("started") }
        assertEquals(RunState.Running, q.read(id2).run!!.state)
        q.stop(id2)
        val stopped = q.read(id2).run!!
        assertEquals(RunState.Stopped, stopped.state)
        waitFor("the script to be gone") { q.runs().runs.first { it.id == id2 }.state == RunState.Stopped }

        // The list: newest first.
        val runs = q.runs()
        assertTrue(runs.now > 0)
        assertEquals(listOf(id2, id), runs.runs.map { it.id })
        q.forget(id)
        assertEquals(listOf(id2), q.runs().runs.map { it.id })
        assertNull(q.read(id).run)
    }

    @Test
    fun aLongOutputShowsItsEnd() = runBlocking {
        val m = machine()
        File(m.home, ".config/herdr/commands/long").apply {
            // 700 KB of lines, then the line that matters, with a multi-byte character or two on the way.
            writeText("#!/bin/sh\ni=0; while [ \$i -lt 10000 ]; do echo \"line \$i: héllo wörld, enough to fill seventy bytes ...\"; i=\$((i+1)); done\necho THE-END\n")
            setExecutable(true)
        }
        val q = client(m)
        val long = q.list("").commands.first { it.name == "long" }
        val id = QuickClient.newId(1_792_000_004_000, 4)
        q.start(id, long, QuickContext(cwd = m.home.path), "", at = 5)
        waitFor("it to end") { q.read(id).run?.state == RunState.Done }
        val size = q.read(id).run!!.size
        assertTrue(size > com.makefunstuff.herdr.core.quick.QuickCommands.READ_LIMIT, "$size")
        // Opened once it ended, as the sheet does: on until the end is in.
        val out = com.makefunstuff.herdr.core.quick.QuickOutput()
        var reads = 0
        while (true) {
            val read = out.read(q, id)
            reads++
            if (out.done(read.run!!, read)) break
            check(reads < 10) { "never caught up: ${out.offset} of $size" }
        }
        assertTrue(out.text.endsWith("THE-END\n"), out.text.takeLast(80))
        assertTrue(out.skipped > 0)
        assertEquals(size, out.skipped + out.text.toByteArray().size)
        assertTrue(out.text.startsWith("line "), out.text.take(40))
        assertTrue('\uFFFD' !in out.text)
    }

    @Test
    fun aCharacterCutByAReadWaitsForTheNext() {
        val e = "é".toByteArray() // 2 bytes
        fun read(bytes: ByteArray, offset: Long, size: Long, alive: Boolean) = QuickReader.read(
            "@@now\t1\n@@run\tq1\nmeta\t{\"kind\":\"script\",\"path\":\"/b\"}\nstart\t1\n" +
                (if (alive) "alive\t1\n" else "exit\t0\nend\t2\n") + "size\t$size\n@@out64\n" + java.util.Base64.getEncoder().encodeToString(bytes),
            offset,
        )
        // A read that ends halfway into é: "a" now, é whole on the next read.
        val first = read(byteArrayOf('a'.code.toByte(), e[0]), 0, 3, alive = true)
        assertEquals("a", first.text)
        assertEquals(1, first.bytes)
        val next = read(byteArrayOf(e[0], e[1]), 1, 3, alive = true)
        assertEquals("é", next.text)
        assertEquals(2, next.bytes)
        // A byte that isn't UTF-8 is taken as it is (a replacement character), and the reads go on past it.
        val bad = read(byteArrayOf(0xFF.toByte(), 'b'.code.toByte()), 0, 2, alive = true)
        assertEquals(2, bad.bytes)
        // The last bytes of an ended run are taken even when they don't make a whole character.
        val end = read(byteArrayOf(e[0]), 2, 3, alive = false)
        assertEquals(1, end.bytes)
        assertEquals(2, com.makefunstuff.herdr.core.quick.QuickText.utf8Prefix("ab€".toByteArray().copyOf(4)))
        assertEquals(5, com.makefunstuff.herdr.core.quick.QuickText.utf8Prefix("ab€".toByteArray()))
    }

    @Test
    fun promptRunsStartTheWorker() = runBlocking {
        val m = machine()
        // A node and an npm of our own: the worker "answers" at once.
        val bin = File(m.home, "bin").apply { mkdirs() }
        File(bin, "node").apply {
            writeText(
                "#!/bin/sh\ncase \"\$1\" in -e) exit 0;; --version) echo v22.1.0; exit 0;; esac\n" +
                    "d=\"\$4\"; grep -q prompt \"\$d/request.json\" || exit 1\n" +
                    "printf 'The answer\\n\\nwith more.\\n' > \"\$d/answer.md\"\n" +
                    "printf '{\"state\":\"done\",\"model\":\"pi/x/y\",\"tokens\":42,\"started\":%s,\"ended\":%s}\\n' \"\$(date +%s)\" \"\$(date +%s)\" > \"\$d/status.json\"\n",
            )
            setExecutable(true)
        }
        File(bin, "npm").apply { writeText("#!/bin/sh\nmkdir -p node_modules\necho installed\n"); setExecutable(true) }
        val q = client(m, path = bin.path)
        val listing = q.list(m.main.path)
        val summary = listing.commands.first { it.kind == QuickKind.Prompt }
        val ctx = QuickContext(cwd = m.main.path, repo = listing.repo)
        val id = QuickClient.newId(1_792_000_002_000, 2)
        q.ask(id, summary, ctx, "", QuickPrompt.fill(summary.body, "", ctx), at = 3)
        waitFor("the answer") { q.read(id).run?.state == RunState.Done }
        val read = q.read(id)
        assertEquals("The answer\n\nwith more.\n", read.text)
        val run = read.run!!
        assertEquals(42, run.tokens)
        assertEquals("pi/x/y", run.model)
        assertEquals("The answer", run.preview)
        assertEquals("7 TODOs and 2 FIXMEs, most in board", com.makefunstuff.herdr.core.quick.QuickText.line("```\n**7 TODOs** and `2 FIXMEs`, most in board\n"))
        assertEquals("BUILD FAILED", com.makefunstuff.herdr.core.quick.QuickText.line("a\nBUILD FAILED\n---\n", fromEnd = true))
        // The worker went to the machine, and its package.json beside it.
        val worker = File(m.home, ".local/share/herdr-android/quick")
        assertEquals(QuickWorker.script, File(worker, "herdr-quick.mjs").readText())
        assertEquals(QuickWorker.packageJson + "\n", File(worker, "package.json").readText())
        assertTrue(File(worker, "node_modules/.herdr-quick").exists())
        val request = File(m.home, ".local/state/herdr-android/quick/$id/request.json").readText()
        assertTrue("What changed today in ${m.main.path}?" in request, request)
        assertTrue("\"model\":\"pi/x/y\"" in request && "\"tools\":[\"read-only\"]" in request, request)
    }

    @Test
    fun aWorkerThatCantStartSaysWhy() = runBlocking {
        val m = machine()
        val bin = File(m.home, "bin").apply { mkdirs() }
        File(bin, "node").apply { writeText("#!/bin/sh\ncase \"\$1\" in -e) exit 1;; --version) echo v18.2.0;; esac\n"); setExecutable(true) }
        val q = client(m, path = bin.path)
        val summary = q.list("").commands.first { it.kind == QuickKind.Prompt }
        val id = QuickClient.newId(1_792_000_003_000, 3)
        q.ask(id, summary, QuickContext(cwd = m.home.path), "", summary.body, at = 4)
        waitFor("it to fail") { q.read(id).run?.state == RunState.Failed }
        assertEquals("Node.js v18.2.0 is too old here: AI quick commands need Node 22 or newer", q.read(id).run!!.error)
    }

    @Test
    fun readsRunsAsTheMachinePrintsThem() {
        val out = "@@now\t1000\n" +
            "@@run\tq2-b\nmeta\t{\"name\":\"Explain\",\"kind\":\"prompt\",\"path\":\"/c/explain.prompt.md\",\"cwd\":\"/r\"}\n" +
            "status\t{\"state\":\"running\",\"activity\":\"Reading\",\"tokens\":7,\"started\":900}\n" +
            "@@run\tq1-a\nmeta\t{\"name\":\"Build\",\"kind\":\"script\",\"path\":\"/c/build\"}\nstart\t800\nend\t810\nexit\t0\n" +
            "@@run\tq0-c\nmeta\t{\"kind\":\"script\",\"path\":\"/c/old-one.sh\"}\nstart\t700\n" +
            "@@run\tbroken\n"
        val list = QuickReader.runs(out)
        assertEquals(1000, list.now)
        assertEquals(listOf("q2-b", "q1-a", "q0-c"), list.runs.map { it.id })
        // Not alive: a prompt's worker that went away is lost, and so is a script without an end.
        assertEquals(RunState.Lost, list.runs[0].state)
        assertEquals("Reading", list.runs[0].activity)
        assertEquals(100, list.runs[0].seconds(1000))
        assertEquals(RunState.Done, list.runs[1].state)
        assertEquals(10, list.runs[1].seconds(1000))
        assertEquals(RunState.Lost, list.runs[2].state)
        assertEquals("old-one", list.runs[2].name)
        val alive = QuickReader.runs(out.replace("\"started\":900}\n", "\"started\":900}\nalive\t1\n"))
        assertEquals(RunState.Running, alive.runs[0].state)
        val read = QuickReader.read("@@now\t5\n@@gone\n")
        assertNull(read.run)
        val one = QuickReader.read("@@now\t5\n@@run\tq1\nmeta\t{\"kind\":\"script\",\"path\":\"/b\"}\nalive\t1\n@@out\nline 1\n@@out\nline 2")
        assertNotNull(one.run)
        assertEquals(RunState.Starting, one.run!!.state)
        assertEquals("line 1\n@@out\nline 2", one.text)
    }
}

class QuickNewTest {
    @Test
    fun asksForTheRightFile() {
        assertEquals("deploy-staging.sh", com.makefunstuff.herdr.core.quick.QuickNew.fileName("Deploy staging!", QuickKind.Script))
        assertEquals("why-did-ci-fail.prompt.md", com.makefunstuff.herdr.core.quick.QuickNew.fileName("Why did CI fail?", QuickKind.Prompt))
        val m = com.makefunstuff.herdr.core.quick.QuickNew.message(QuickKind.Script, "run the tests ", "/r/.herdr/commands", "Tests")
        assertTrue("`/r/.herdr/commands/tests.sh`" in m, m)
        assertTrue("It should: run the tests\n" in m && "# name: Tests" in m && "chmod +x" in m, m)
        val p = com.makefunstuff.herdr.core.quick.QuickNew.message(QuickKind.Prompt, "explain the error", "~/.config/herdr/commands")
        assertTrue("`~/.config/herdr/commands/<short-name>.prompt.md`" in p && "{{selection}}" in p && "# model:" in p, p)
    }
}

/**
 * An AI quick command for real, through the app's own commands: the worker copied to a home folder of
 * its own, pi-durable installed there (or [HERDR_QUICK_MODULES] linked in), a real model answering.
 * Only with `HERDR_QUICK_LIVE=1` (it needs Node 22 and a model pi knows: `HERDR_QUICK_MODEL`, by default
 * pi/deepseek/deepseek-flash, with ~/.pi/agent's keys).
 */
class QuickLiveTest {
    @Test
    fun answersAndCarriesOnAfterBeingKilled() = runBlocking {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("HERDR_QUICK_LIVE") == "1", "HERDR_QUICK_LIVE isn't set")
        val home = Files.createTempDirectory("herdr-quick-live").toFile()
        System.getenv("HERDR_QUICK_MODULES")?.let { modules ->
            val q = File(home, ".local/share/herdr-android/quick").apply { mkdirs() }
            Files.createSymbolicLink(File(q, "node_modules").toPath(), File(modules).toPath())
        }
        val pi = System.getenv("PI_AGENT_DIR") ?: File(System.getProperty("user.home"), ".pi/agent").path
        fun exec(command: String, input: String?): CommandResult {
            val pb = ProcessBuilder("sh", "-c", command).directory(home)
            pb.environment().putAll(mapOf("HOME" to home.path, "PI_AGENT_DIR" to pi))
            pb.environment().remove("XDG_STATE_HOME")
            val p = pb.start()
            if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor(300, TimeUnit.SECONDS)
            return CommandResult(p.exitValue(), out, err)
        }
        val q = QuickClient({ c, _ -> exec(c, null) }, { c, i, _ -> exec(c, i) })
        // Mark the linked modules installed, as a first run would.
        System.getenv("HERDR_QUICK_MODULES")?.let {
            File(home, ".local/share/herdr-android/quick/package.json").writeText(QuickWorker.packageJson + "\n")
            File(home, ".local/share/herdr-android/quick/node_modules/.herdr-quick").let { m -> if (!m.exists()) m.writeText("") }
            File(home, ".local/share/herdr-android/quick/node_modules/.herdr-quick").setLastModified(System.currentTimeMillis() + 60_000)
        }
        val repo = File(System.getProperty("user.dir")).parentFile.canonicalPath
        val command = QuickCommand(
            "Last commits", QuickKind.Prompt, QuickScope.Global, "/x/last.prompt.md",
            model = System.getenv("HERDR_QUICK_MODEL") ?: "pi/deepseek/deepseek-flash", timeoutMinutes = 4,
            body = "Using git, list the 3 most recent commits in {{repo}} as one line each, then say in one sentence what the newest did.",
        )
        val ctx = QuickContext(cwd = repo, repo = repo)
        val id = QuickClient.newId(System.currentTimeMillis())
        q.ask(id, command, ctx, "", QuickPrompt.fill(command.body, "", ctx), System.currentTimeMillis())
        // Kill the worker as soon as it is at work; started again, it carries on where it was.
        val until = System.currentTimeMillis() + 240_000
        var killed = false
        while (true) {
            val r = q.read(id).run!!
            if (!killed && r.state == RunState.Running) {
                exec("kill -9 \$(cat \"\$HOME/.local/state/herdr-android/quick/$id/worker.pid\")", null)
                kotlinx.coroutines.delay(1500)
                val lost = q.read(id).run!!
                if (lost.state == RunState.Lost) { q.resume(lost); killed = true }
            }
            if (r.state.finished) break
            check(System.currentTimeMillis() < until) { "No answer in time: ${q.read(id)}" }
            kotlinx.coroutines.delay(1000)
        }
        val read = q.read(id)
        println("LIVE: killed=$killed ${read.run}\n${read.text}")
        assertEquals(RunState.Done, read.run!!.state, read.run!!.error)
        assertTrue(read.text.isNotBlank())
        assertTrue(read.run!!.tokens > 0)
    }
}
