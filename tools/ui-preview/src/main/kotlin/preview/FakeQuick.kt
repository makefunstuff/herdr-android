// A fake machine's quick commands: its commands folders and the runs in its runs folder, answering
// the app's quick-command scripts (QuickCommands: `sh -c <script> herdr-quick-<op> <args>`) as they
// print on a real machine. A command started in a scene runs at once with fixed output.
package preview

import com.makefunstuff.herdr.core.quick.QuickCommands
import com.makefunstuff.herdr.core.ssh.CommandResult
import java.util.Base64

class FakeQuick(
    /** The commands folders: the folder → its files (name → text). */
    val folders: Map<String, Map<String, String>> = devbox(),
    /** The runs, newest first. */
    val runs: MutableList<Run> = devboxRuns(),
    /** The repositories: checkout → (main checkout, branch). */
    val repos: Map<String, Pair<String, String>> = mapOf(
        FakeCode.MAIN to (FakeCode.MAIN to "main"),
        FakeCode.TASK to (FakeCode.MAIN to "work/9-last-answered"),
        "/home/demo/src/api-server" to ("/home/demo/src/api-server" to "main"),
        "/home/demo/src/website" to ("/home/demo/src/website" to "main"),
    ),
    val home: String = "/home/demo",
) {
    /** One run's folder: its `meta.json`, a prompt's `status.json` and `answer.md`, a script's `out`, `exit`, `start`, `end`. */
    class Run(
        val id: String,
        val meta: String,
        var status: String? = null,
        var exit: String? = null,
        var start: Long = 0,
        var end: Long = 0,
        var alive: Boolean = false,
        var out: String = "",
        var answer: String? = null,
    )

    private val now get() = Demo.NOW / 1000

    fun run(command: String): CommandResult? {
        val w = Shell.words(command)
        val at = w.indexOfFirst { it.startsWith("herdr-quick-") }
        if (at < 0) return null
        val args = w.drop(at + 1)
        return when (w[at].removePrefix("herdr-quick-")) {
            "list" -> ok(list(args.firstOrNull().orEmpty()))
            "runs" -> ok("@@now\t$now\n" + runs.joinToString("") { info(it) })
            "read" -> {
                val r = runs.firstOrNull { it.id == args[0] }
                val offset = args.getOrNull(1)?.toIntOrNull() ?: 0
                ok(
                    "@@now\t$now\n" + (
                        r?.let {
                            info(it) + (it.answer?.let { a -> "@@out\n$a" }
                                ?: ("@@out64\n" + Base64.getEncoder().encodeToString(it.out.toByteArray().drop(offset).toByteArray())))
                        } ?: "@@gone\n"
                        ),
                )
            }
            "start" -> {
                // id, cwd, path, meta, repo, args…
                val name = Regex("\"name\":\"([^\"]*)\"").find(args[3])?.groupValues?.get(1).orEmpty()
                runs.add(0, Run(args[0], args[3], start = now - 3, alive = true, out = started[name] ?: "Working…\n"))
                ok("@@started ${args[0]}\n")
            }
            "ask" -> {
                // id, cwd, meta, request, package.json; an empty request starts one again.
                val r = runs.firstOrNull { it.id == args[0] }
                if (r != null) { r.alive = true; r.status = r.status?.replace("\"state\":\"running\"", "\"state\":\"running\"") }
                else runs.add(0, Run(args[0], args[2], status = """{"state":"running","activity":"Thinking","started":${now - 4},"model":"pi/deepseek/deepseek-flash","tokens":0}""", alive = true))
                ok("@@started ${args[0]}\n")
            }
            "stop" -> {
                runs.firstOrNull { it.id == args[0] }?.let { r ->
                    r.alive = false
                    if (r.answer == null && r.status == null) { r.exit = "stopped"; r.end = now }
                    else r.status = """{"state":"stopped","ended":$now,"started":${now - 60}}"""
                }
                ok("@@stopped\n")
            }
            "forget" -> { runs.removeAll { it.id == args[0] }; ok("@@forgotten\n") }
            else -> CommandResult(127, "", "unknown")
        }
    }

    private fun ok(out: String) = CommandResult(0, out, "")

    private fun list(cwd: String): String = buildString {
        fun show(scope: String, folder: String) {
            val files = folders[folder] ?: return
            append("@@dir\t$scope\t$folder\n")
            files.forEach { (name, text) -> append("@@file\t$scope\t$folder/$name\n").append(text).append("\n${QuickCommands.MARK} end\n") }
        }
        val c = cwd.replace(Regex("^~"), home)
        val top = repos.keys.filter { c == it || c.startsWith("$it/") }.maxByOrNull { it.length }
        if (c.isNotEmpty()) {
            if (top != null) {
                val (main, branch) = repos.getValue(top)
                append("@@repo\t$top\t$main\t$branch\n")
                show("project", "$top/.herdr/commands")
                if (main != top) show("project", "$main/.herdr/commands")
            } else {
                append("@@repo\t$c\t\t\n")
                show("project", "$c/.herdr/commands")
            }
        }
        show("global", "$home/.config/herdr/commands")
        append("@@home\t$home\n")
    }

    private fun info(r: Run): String = buildString {
        append("@@run\t${r.id}\nmeta\t${r.meta}\n")
        r.status?.let { append("status\t$it\n") }
        r.exit?.let { append("exit\t$it\n") }
        if (r.start > 0) append("start\t${r.start}\n")
        if (r.end > 0) append("end\t${r.end}\n")
        if (r.alive) append("alive\t1\n")
        if (r.answer == null) append("size\t${r.out.toByteArray().size}\n")
        val b64 = { s: String -> Base64.getEncoder().encodeToString(s.toByteArray()) }
        if (r.answer != null) append("head\t${b64(r.answer!!.take(300))}\n") else if (r.out.isNotEmpty()) append("tail\t${b64(r.out.takeLast(300))}\n")
    }

    companion object {
        private const val PROJECT = "${FakeCode.MAIN}/.herdr/commands"
        private const val GLOBAL = "/home/demo/.config/herdr/commands"

        /** What a script started in a scene has printed so far. */
        private val started = mapOf(
            "Run unit tests" to "> Task :core:compileKotlin UP-TO-DATE\n> Task :core:test\n\nQuickTest > runsAScriptDetachedAndReadsItsOutput() PASSED\n",
            "Disk usage" to "Filesystem      Size  Used Avail Use% Mounted on\n/dev/nvme0n1p2  932G  611G  321G  66% /\n",
        )

        fun devbox(): Map<String, Map<String, String>> = mapOf(
            PROJECT to linkedMapOf(
                "test" to "#!/usr/bin/env bash\n# name: Run unit tests\n# desc: core and the app's JVM tests\n# icon: flask-conical\nset -e\n./gradlew -p core test",
                "previews" to "#!/usr/bin/env bash\n# name: Render the screens\n# desc: Every scene, dark and light, about 4 minutes\n# icon: layers\n# pane: true\n./gradlew -p tools/ui-preview render",
                "install-apk.sh" to "#!/usr/bin/env bash\n# name: Install on my phone\n# desc: Builds the debug APK and installs it over adb\n# icon: smartphone\n# confirm: true\n./gradlew :app:installDebug",
                "todos.prompt.md" to "# name: Find TODOs\n# desc: Every TODO and FIXME, by area\n# icon: list-todo\n# model: pi/deepseek/deepseek-flash\n\nFind every TODO and FIXME in {{repo}} and list them by area.",
                "pr.prompt.md" to "# name: Draft a PR description\n# desc: From this branch's commits and diff\n# icon: git-pull-request\n# model: claude/claude-opus-5-5\n# args: Anything to point out?\n\nWrite a pull request description for {{branch}} against main. {{args}}",
            ),
            GLOBAL to linkedMapOf(
                "disk-usage" to "#!/bin/sh\n# name: Disk usage\n# desc: How full the disks are\n# icon: hard-drive\ndf -h",
                "gradle-stop" to "#!/bin/sh\n# name: Stop Gradle daemons\n# desc: Every Gradle and Kotlin daemon of yours\n# icon: power\n# confirm: true\n./gradlew --stop",
                "changed-today.prompt.md" to "# name: What changed today\n# desc: Today's commits in this repo, summed up\n# icon: newspaper\n# model: pi/deepseek/deepseek-flash\n\nSummarise what changed today ({{date}}) in {{repo}}.",
                "explain-error.prompt.md" to "# name: Explain this error\n# desc: What it means and how to fix it\n# icon: circle-alert\n# model: claude/claude-opus-5-5\n# args: Paste the error\n\nExplain this error, in {{repo}}: {{args}}",
                "ci.prompt.md" to "# name: Why did CI fail?\n# desc: Reads the last failed run and says why\n# icon: bug\n# tools: read-only, web\n\nFind out why the last CI run of {{branch}} failed.",
            ),
        )

        private fun meta(name: String, kind: String, icon: String, file: String, cwd: String = FakeCode.MAIN, args: String = "", scope: String = "project") =
            """{"name":"$name","kind":"$kind","icon":"$icon","scope":"$scope","path":"${if (scope == "project") PROJECT else GLOBAL}/$file","cwd":"$cwd","repo":"$cwd","args":"$args"}"""

        private val NOW_S get() = Demo.NOW / 1000

        const val TODOS = "q1792222540000-0a1f"
        const val TESTS = "q1792221100000-77c2"
        const val TODAY = "q1792222900000-5b3e"
        const val DISK = "q1792215700000-19d0"
        const val EXPLAIN = "q1792209000000-c4a2"
        const val BUILD = "q1792208000000-e01b"

        /** A long build's log: some 300 KB of tasks, then how it failed. */
        private fun longLog(): String = buildString {
            var i = 0
            while (length < 300_000) { append("> Task :app:compileDebugKotlin step ${i++}: compiling ui/board/BoardScreen.kt and its neighbours\n") }
            append("e: file:///home/demo/src/herdr-android/app/src/main/kotlin/demo/herdr/ui/machines/MachineCard.kt:37:21 Unresolved reference 'lastAnswered'.\n\n")
            append("FAILURE: Build failed with an exception.\n\n* What went wrong:\nExecution failed for task ':app:compileDebugKotlin'.\n> Compilation error. See log for more details\n\nBUILD FAILED in 4m 3s\n")
        }

        fun devboxRuns(): MutableList<Run> = mutableListOf(
            Run(
                TODAY, meta("What changed today", "prompt", "newspaper", "changed-today.prompt.md", scope = "global"),
                status = """{"state":"running","activity":"Asking Git","started":${NOW_S - 42},"model":"pi/deepseek/deepseek-flash","tokens":6210}""",
                alive = true, answer = "",
            ),
            Run(
                TODOS, meta("Find TODOs", "prompt", "list-todo", "todos.prompt.md"),
                status = """{"state":"done","started":${NOW_S - 280},"ended":${NOW_S - 242},"model":"pi/deepseek/deepseek-flash","tokens":15120}""",
                answer = Demo.resource("quick-todos.md"),
            ),
            Run(
                TESTS, meta("Run unit tests", "script", "flask-conical", "test"),
                exit = "1", start = NOW_S - 1572, end = NOW_S - 1500, out = Demo.resource("quick-tests.txt"),
            ),
            Run(
                DISK, meta("Disk usage", "script", "hard-drive", "disk-usage", cwd = "/home/demo", scope = "global"),
                exit = "0", start = NOW_S - 7202, end = NOW_S - 7201,
                out = "Filesystem      Size  Used Avail Use% Mounted on\n/dev/nvme0n1p2  932G  611G  321G  66% /\n/dev/nvme1n1p1  1.8T  1.2T  540G  70% /data\ntmpfs            32G  2.1G   30G   7% /tmp\n",
            ),
            Run(
                BUILD, meta("Build the APK", "script", "hammer", "build"),
                exit = "1", start = NOW_S - 14500, end = NOW_S - 14257, out = longLog(),
            ),
            Run(
                EXPLAIN, meta("Explain this error", "prompt", "circle-alert", "explain-error.prompt.md", args = "Unresolved reference: lastAnswered", scope = "global"),
                status = """{"state":"failed","started":${NOW_S - 13700},"ended":${NOW_S - 13698},"model":"claude/claude-opus-5-5","error":"No Anthropic key: set ANTHROPIC_API_KEY or log in to anthropic in pi"}""",
                answer = "",
            ),
        )
    }
}
