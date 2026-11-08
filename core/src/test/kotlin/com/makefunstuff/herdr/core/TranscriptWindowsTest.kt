package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.chat.TranscriptFollower
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Finding a transcript from a pane's pid on a Windows machine, where `pgrep` doesn't exist. */
class TranscriptWindowsTest {
    private val session = "3c2c5876-a786-4c23-8ac5-471296257bc4"

    /**
     * Runs [TranscriptFollower.command] for the pane's pid 16744 with Git Bash's `uname` and a
     * `powershell.exe` that prints [table]; [sessionPid] has the Claude session file, if any.
     * Returns the first line it prints.
     */
    private fun run(table: String, sessionPid: String?, crlf: Boolean = false): String {
        val home = Files.createTempDirectory("transcript-win").toFile()
        try {
            val bin = File(home, "bin").apply { mkdirs() }
            File(bin, "uname").apply { writeText("#!/bin/sh\necho MINGW64_NT-10.0-26100\n"); setExecutable(true) }
            // The real one prints CRLF line endings, and reads nothing from its stdin.
            val eol = if (crlf) "\r\n" else "\n"
            val rows = table.trim().lines().joinToString("") { "$it$eol" }
            File(bin, "powershell.exe").apply { writeText("#!/bin/sh\nprintf '$rows'\n"); setExecutable(true) }
            File(home, ".claude/projects/C--Users-dev-proj").apply { mkdirs() }.resolve("$session.jsonl").writeText("{\"a\":1}\n")
            File(home, ".claude/sessions").mkdirs()
            if (sessionPid != null) File(home, ".claude/sessions/$sessionPid.json").writeText("""{"pid":$sessionPid,"sessionId":"$session"}""")
            val ref = AgentSessionRef(kind = "pid", value = "16744", agent = "claude")
            // Written to a file and run with `sh <file>`: on Windows, Java breaks the quotes of `sh -c`.
            val command = File(home, "follow.sh").apply { writeText(TranscriptFollower.command(ref, 100) + "\n") }
            val p = ProcessBuilder("sh", command.path)
                .apply { environment().apply { put("HOME", home.path); put("PATH", "${bin.path}:/usr/bin:/bin"); remove("CLAUDE_CONFIG_DIR") } }
                .redirectErrorStream(true)
                .start()
            // Closing stdin is how the app ends the stream.
            p.outputStream.close()
            val first = p.inputStream.bufferedReader().readLine() ?: ""
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
            return first
        } finally {
            home.deleteRecursively()
        }
    }

    private fun assertFound(first: String) {
        assertTrue(first.startsWith("@@file ") && first.endsWith("/$session.jsonl"), first)
    }

    @Test
    fun findsClaudeUnderTheWindowsShell() {
        assertFound(run("16744 900\n27492 16744\n31000 27492\n", sessionPid = "27492"))
    }

    @Test
    fun findsAGrandchild() {
        assertFound(run("16744 900\n27492 16744\n31000 27492\n", sessionPid = "31000"))
    }

    @Test
    fun readsPowershellsCrlfTable() {
        assertFound(run("16744 900\n27492 16744\n", sessionPid = "27492", crlf = true))
    }

    @Test
    fun missingWithoutASessionFile() {
        assertEquals("@@missing", run("16744 900\n27492 16744\n31000 27492\n", sessionPid = null))
    }

    @Test
    fun ignoresProcessesOutsideThePanesTree() {
        // 27492 belongs to another shell, so its session isn't this pane's.
        assertEquals("@@missing", run("16744 900\n27492 5000\n", sessionPid = "27492"))
    }

    @Test
    fun aStaleParentIdCannotLoopTheWalk() {
        // Windows reuses pids: 16744's own parent id points back down at its child.
        assertFound(run("16744 31000\n27492 16744\n31000 27492\n40000 31000\n", sessionPid = "40000"))
        assertEquals("@@missing", run("16744 16744\n27492 31000\n31000 27492\n", sessionPid = null))
    }
}
