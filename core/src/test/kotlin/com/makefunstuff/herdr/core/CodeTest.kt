package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.code.CodeClient
import com.makefunstuff.herdr.core.code.CodeCommands
import com.makefunstuff.herdr.core.code.CodeReader
import com.makefunstuff.herdr.core.code.DiffLine
import com.makefunstuff.herdr.core.code.DiffScope
import com.makefunstuff.herdr.core.code.FileChange
import com.makefunstuff.herdr.core.code.FileText
import com.makefunstuff.herdr.core.code.FileTree
import com.makefunstuff.herdr.core.code.QuickOpen
import com.makefunstuff.herdr.core.code.ReviewNote
import com.makefunstuff.herdr.core.code.ReviewNotes
import com.makefunstuff.herdr.core.code.Role
import com.makefunstuff.herdr.core.code.Syntax
import com.makefunstuff.herdr.core.code.UnifiedDiff
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeTest {
    // ---- the commands, run for real on a repository made here ---------------------------------------

    private fun sh(dir: File?, vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")}: $out" }
    }

    private fun git(dir: File, vararg args: String) = sh(dir, "git", "-c", "user.name=Demo", "-c", "user.email=demo@example.com", "-c", "init.defaultBranch=main", *args)

    /** A repository with a main branch, and a worktree on a branch with a commit, a change not committed and a new file. */
    private fun repo(): Pair<File, File> {
        val base = Files.createTempDirectory("herdr-code").toFile()
        val main = File(base, "app").apply { mkdirs() }
        git(main, "init", "-q")
        File(main, "src").mkdirs()
        File(main, "src/Main.kt").writeText("fun main() {\n    println(\"hello\")\n}\n")
        File(main, "README.md").writeText("# App\n\nSays hello.\n")
        File(main, ".gitignore").writeText("build/\n")
        git(main, "add", ".")
        git(main, "commit", "-q", "-m", "First")
        val wt = File(base, "app-7")
        git(main, "worktree", "add", "-q", "-b", "work/7-greet", wt.absolutePath)
        File(wt, "src/Main.kt").writeText("fun main() {\n    println(\"hello, world\")\n}\n")
        git(wt, "commit", "-q", "-am", "Greet the world\n\nWith a body.")
        File(wt, "README.md").writeText("# App\n\nSays hello to the world.\n")
        File(wt, "src/New file.kt").writeText("val x = 1\n")
        File(wt, "build").mkdirs()
        File(wt, "build/out.txt").writeText("ignored\n")
        return main to wt
    }

    private val local = CodeClient { command, timeout ->
        val p = ProcessBuilder("sh", "-c", command).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(timeout, TimeUnit.SECONDS)
        CommandResult(p.exitValue(), out, err)
    }

    @Test fun readsARepositoryForReal() = runBlocking {
        val (main, wt) = repo()
        val found = local.discover(listOf(wt.absolutePath + "/src", main.absolutePath, "/nonexistent"))
        assertEquals(1, found.repos.size)
        val r = found.repos.single()
        assertEquals(main.canonicalPath, File(r.main.path).canonicalPath)
        assertTrue(r.main.main)
        assertEquals("main", r.main.branch)
        assertEquals("work/7-greet", r.checkouts[1].branch)
        assertEquals(wt.canonicalPath, File(found.topOf.getValue(wt.absolutePath + "/src")).canonicalPath)

        val info = local.info(wt.absolutePath)
        assertTrue(info.git)
        assertEquals("work/7-greet", info.branch)
        assertEquals("main", info.base)
        assertTrue(info.linked)

        assertEquals(listOf(".gitignore", "README.md", "src/Main.kt", "src/New file.kt"), local.files(wt.absolutePath))

        val file = local.read(wt.absolutePath, "src/Main.kt")
        assertEquals(listOf("fun main() {", "    println(\"hello, world\")", "}"), file.lines)
        assertFalse(file.binary)
        assertFalse(file.truncated)
        assertFailsWith<IOException> { local.read(wt.absolutePath, "nope.kt") }

        val hits = local.search(wt.absolutePath, "HELLO")
        assertEquals(setOf("src/Main.kt" to 2, "README.md" to 3), hits.map { it.path to it.line }.toSet())
        assertTrue(local.search(wt.absolutePath, "HELLO", matchCase = true).isEmpty())
        assertEquals(listOf("src/Main.kt"), local.search(wt.absolutePath, "hel+o,", regex = true).map { it.path })

        val status = local.status(wt.absolutePath)
        assertEquals("work/7-greet", status.branch)
        assertEquals(setOf("README.md", "src/New file.kt"), status.entries.map { it.path }.toSet())
        assertTrue(status.entries.first { it.path == "src/New file.kt" }.untracked)

        // Against main: the commit, the change not committed and the new file.
        val changes = local.changes(wt.absolutePath, info.base, DiffScope.Base)
        assertEquals(setOf("README.md", "src/Main.kt", "src/New file.kt"), changes.files.map { it.path }.toSet())
        assertEquals(FileChange.Added, changes.files.first { it.path == "src/New file.kt" }.change)
        assertEquals(3, changes.added)
        // Only what isn't committed yet.
        val uncommitted = local.changes(wt.absolutePath, info.base, DiffScope.Uncommitted)
        assertEquals(setOf("README.md", "src/New file.kt"), uncommitted.files.map { it.path }.toSet())

        val log = local.log(wt.absolutePath, info.base)
        assertEquals(listOf("Greet the world", "First"), log.map { it.subject })
        assertEquals(listOf(true, false), log.map { it.ahead })

        val shown = local.show(wt.absolutePath, log.first().sha)
        assertEquals("Greet the world", shown.commit.subject)
        assertEquals("With a body.", shown.commit.body)
        val hunk = shown.files.single().hunks.single()
        assertEquals(listOf(DiffLine.Kind.Context, DiffLine.Kind.Removed, DiffLine.Kind.Added, DiffLine.Kind.Context), hunk.lines.map { it.kind })
        assertEquals(2, hunk.lines[2].new)

        // A folder gone, and one outside Git.
        assertFailsWith<IOException> { local.files("/nonexistent/folder") }
        val plain = Files.createTempDirectory("herdr-plain").toFile().apply { File(this, "a.txt").writeText("x\n") }
        assertFalse(local.info(plain.absolutePath).git)
        assertEquals(listOf("a.txt"), local.files(plain.absolutePath))
        assertEquals(1, local.search(plain.absolutePath, "x").size)
    }

    @Test fun aTildePathIsTheHomeFolder() = runBlocking {
        val home = Files.createTempDirectory("herdr-home").toFile()
        File(home, "src/proj").mkdirs()
        File(home, "src/proj/a.txt").writeText("hi\n")
        val client = CodeClient { command, _ ->
            val p = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = home.absolutePath }.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor()
            CommandResult(p.exitValue(), out, "")
        }
        assertEquals(listOf("a.txt"), client.files("~/src/proj"))
    }

    // ---- reading what the commands print ----------------------------------------------------------

    @Test fun parsesADiffWithGitsHeaders() {
        val diff = """
            diff --git a/old name.kt b/new name.kt
            similarity index 90%
            rename from old name.kt
            rename to new name.kt
            index 1111111..2222222 100644
            --- a/old name.kt
            +++ b/new name.kt
            @@ -1,4 +1,4 @@ fun main()
             a
            --- not a header
            +++ not one either
             c
            \ No newline at end of file
            diff --git a/gone.txt b/gone.txt
            deleted file mode 100644
            index 3333333..0000000
            --- a/gone.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
            diff --git a/img.png b/img.png
            new file mode 100644
            index 0000000..4444444
            Binary files /dev/null and b/img.png differ
            diff --git a/run.sh b/run.sh
            old mode 100644
            new mode 100755
        """.trimIndent()
        val files = UnifiedDiff.parse(diff)
        assertEquals(4, files.size)
        val renamed = files[0]
        assertEquals(FileChange.Renamed, renamed.change)
        assertEquals("old name.kt", renamed.oldPath)
        assertEquals("new name.kt", renamed.path)
        val lines = renamed.hunks.single().lines
        assertEquals(listOf(DiffLine.Kind.Context, DiffLine.Kind.Removed, DiffLine.Kind.Added, DiffLine.Kind.Context, DiffLine.Kind.Note), lines.map { it.kind })
        assertEquals("-- not a header", lines[1].text)
        assertEquals(listOf(1, 2, null, 3), lines.take(4).map { it.old })
        assertEquals(listOf(1, null, 2, 3), lines.take(4).map { it.new })
        assertEquals("fun main()", renamed.hunks.single().section)
        assertEquals(1, renamed.added)
        assertEquals(1, renamed.removed)

        assertEquals(FileChange.Deleted, files[1].change)
        assertEquals("gone.txt", files[1].path)
        assertNull(files[1].newPath)
        assertTrue(files[2].binary)
        assertEquals(FileChange.Added, files[2].change)
        assertTrue(files[3].modeOnly)
        assertEquals("run.sh", files[3].path)
    }

    @Test fun unquotesGitsPaths() {
        assertEquals("café \"x\".txt", CodeReader.unquote("\"caf\\303\\251 \\\"x\\\".txt\""))
        assertEquals("plain.txt", CodeReader.unquote("plain.txt"))
    }

    @Test fun readsStatusLogAndFiles() {
        val status = CodeReader.status("## work/7...origin/main [ahead 2, behind 1]\n M a.kt\nR  old.kt -> new.kt\n?? n.txt\nUU c.kt\n")
        assertEquals("work/7", status.branch)
        assertEquals("origin/main", status.upstream)
        assertEquals(2 to 1, status.ahead to status.behind)
        assertEquals(listOf("a.kt", "new.kt", "n.txt", "c.kt"), status.entries.map { it.path })
        assertEquals("old.kt", status.entries[1].from)
        assertEquals("modified", status.entries[0].label)
        assertEquals("renamed, staged", status.entries[1].label)
        assertTrue(status.entries[3].conflicted)

        val file = CodeReader.file("@@size 10\nabc\u0000def\n")
        assertTrue(file.binary)
        val cut = CodeReader.file("@@size 5000000\nfirst part")
        assertTrue(cut.truncated)
        assertFailsWith<IOException> { CodeReader.file("@@herdr-code missing\n") }

        assertEquals(listOf("a", "b/c"), CodeReader.files("b/c\u0000a\u0000a\u0000"))
        assertEquals(listOf("only"), FileText("only", 4, false, false).lines)
        assertEquals(listOf("a", "", "b"), FileText("a\r\n\nb\n", 6, false, false).lines)
    }

    // ---- highlighting -----------------------------------------------------------------------------

    private fun roles(lang: com.makefunstuff.herdr.core.code.Language, line: String, state: Int = 0) =
        Syntax.line(lang, line, state).spans.map { line.substring(it.start, it.end) to it.role }

    @Test fun highlightsKotlin() {
        val r = roles(Syntax.kotlin, "    @Composable fun Foo(x: Int = 42) = println(\"hi \$x\") // done")
        assertTrue("@Composable" to Role.Meta in r)
        assertTrue("fun" to Role.Keyword in r)
        assertTrue("Foo" to Role.Function in r)
        assertTrue("Int" to Role.Type in r)
        assertTrue("42" to Role.Number in r)
        assertTrue("\"hi \$x\"" to Role.Str in r)
        assertTrue("// done" to Role.Comment in r)
        assertNotNull(Syntax.language("app/build.gradle.kts"))
        assertEquals("kotlin", Syntax.language("a/B.kt")?.name)
        assertEquals("python", Syntax.language("tool", "#!/usr/bin/env python3")?.name)
        assertNull(Syntax.language("notes.txt"))
    }

    @Test fun carriesBlockCommentsAndStringsAcrossLines() {
        val lines = listOf("val a = 1 /* starts", "still a comment", "ends */ val b = \"\"\"raw", "text\"\"\" + 2")
        val states = Syntax.states(Syntax.kotlin, lines)
        assertEquals(0, states[0])
        assertEquals(listOf("still a comment" to Role.Comment), roles(Syntax.kotlin, lines[1], states[1]))
        assertTrue("ends */" to Role.Comment in roles(Syntax.kotlin, lines[2], states[2]))
        assertTrue("text\"\"\"" to Role.Str in roles(Syntax.kotlin, lines[3], states[3]))
        assertTrue("2" to Role.Number in roles(Syntax.kotlin, lines[3], states[3]))

        val py = Syntax.states(Syntax.python, listOf("def f():", "    '''doc", "    more'''", "    return None  # x"))
        assertEquals(listOf("    more'''" to Role.Str), roles(Syntax.python, "    more'''", py[2]))
        assertTrue("None" to Role.Number in roles(Syntax.python, "    return None  # x", py[3]))
    }

    @Test fun highlightsDataMarkupAndMarkdown() {
        assertEquals(
            listOf("\"name\"" to Role.Variable, "\"herdr\"" to Role.Str),
            roles(Syntax.json, """  "name": "herdr","""),
        )
        assertTrue("version" to Role.Variable in roles(Syntax.yaml, "  version: 1.2"))
        assertTrue("[tool.x]" to Role.Type in roles(Syntax.toml, "[tool.x]"))
        val xml = roles(Syntax.markup, """<string name="app">Herdr &amp; co</string>""")
        assertTrue("string" to Role.Keyword in xml)
        assertTrue("name" to Role.Variable in xml)
        assertTrue("\"app\"" to Role.Str in xml)
        assertTrue("&amp;" to Role.Meta in xml)
        val md = Syntax.states(Syntax.markdown, listOf("# Title", "```kotlin", "val x = 1", "```", "Use `x`."))
        assertEquals(listOf("val x = 1" to Role.Str), roles(Syntax.markdown, "val x = 1", md[2]))
        assertEquals(listOf("# Title" to Role.Keyword), roles(Syntax.markdown, "# Title"))
        assertTrue("`x`" to Role.Str in roles(Syntax.markdown, "Use `x`.", md[4]))
        assertEquals(emptyList(), Syntax.line(Syntax.kotlin, "x".repeat(Syntax.LONGEST + 1)).spans)
    }

    // ---- the tree, "go to file" and review notes ---------------------------------------------------

    @Test fun buildsAFileTree() {
        val tree = FileTree(listOf("b.txt", "src/a/X.kt", "src/a/Y.kt", "src/z.kt", "A.md"))
        assertEquals(listOf("src", "A.md", "b.txt"), tree.list("").map { it.name })
        assertEquals(3, tree.list("").first().count)
        assertEquals(listOf("a", "z.kt"), tree.list("src").map { it.name })
        assertEquals(listOf("src/a/X.kt", "src/a/Y.kt"), tree.list("src/a").map { it.path })
        assertTrue(tree.isFolder("src/a"))
        assertFalse(tree.isFolder("src/z.kt"))
    }

    @Test fun goToFileRanksTheNameFirst() {
        val paths = listOf(
            "app/src/main/kotlin/ui/machines/MachineScreen.kt",
            "app/src/main/kotlin/ui/machines/MachinesScreen.kt",
            "core/src/main/kotlin/ssh/Machine.kt",
            "docs/machines/screen.md",
            "README.md",
        )
        assertEquals("app/src/main/kotlin/ui/machines/MachineScreen.kt", QuickOpen.find(paths, "MachineScreen").first().path)
        assertEquals("core/src/main/kotlin/ssh/Machine.kt", QuickOpen.find(paths, "machine.kt").first().path)
        assertEquals("app/src/main/kotlin/ui/machines/MachinesScreen.kt", QuickOpen.find(paths, "machinesscr").first().path)
        assertEquals(listOf("docs/machines/screen.md"), QuickOpen.find(paths, "docs/scr").map { it.path })
        assertTrue(QuickOpen.find(paths, "zzz").isEmpty())
        val m = QuickOpen.find(paths, "rdme").single()
        assertContentEquals(listOf(0, 3, 4, 5), m.at)
    }

    @Test fun writesReviewNotesAsAMessage() {
        val one = ReviewNote(
            "1", "devbox", "/home/demo/app-7", "work/7-greet", "src/Main.kt", 2, 3, quote = "    println(\"hi\")\n}",
            language = "kotlin", text = "Say hello to the world here.",
        )
        assertEquals(
            """
            Review note on /home/demo/app-7 (branch work/7-greet):

            `src/Main.kt`, lines 2–3:
            ```kotlin
                println("hi")
            }
            ```

            Say hello to the world here.
            """.trimIndent(),
            ReviewNotes.message(listOf(one)),
        )
        val two = one.copy(id = "2", path = "README.md", start = 1, end = 1, quote = "-# App\n+# Hello", language = "diff", source = "the diff against main", text = "Keep the title.")
        val both = ReviewNotes.message(listOf(one, two))
        assertTrue(both.startsWith("2 review notes on /home/demo/app-7 (branch work/7-greet):\n\n1. `src/Main.kt`, lines 2–3:\n```kotlin\n"))
        assertTrue("2. `README.md`, line 1 (the diff against main):\n```diff\n-# App\n+# Hello\n```\nKeep the title." in both)
        val long = one.copy(quote = (1..50).joinToString("\n") { "line $it" }, oldSide = true, start = 1, end = 50)
        assertTrue("old lines 1–50" in ReviewNotes.message(listOf(long)))
        assertTrue("… 10 more lines" in ReviewNotes.fenced(long))
        assertTrue(ReviewNotes.fenced(one.copy(quote = "```\nx\n```")).startsWith("~~~~kotlin"))
    }

    @Test fun commandsQuoteWhatTheyAreGiven() {
        // A search for something with quotes and a dash is one argument, never a flag or shell code.
        val cmd = CodeCommands.search("/tmp/x y", "-rf 'it' \$(boom)", regex = false, matchCase = true)
        assertTrue(cmd.startsWith("sh -c "))
        assertTrue(cmd.endsWith("'/tmp/x y' '-rf '\\''it'\\'' \$(boom)' '-F' '400'"))
    }
}
