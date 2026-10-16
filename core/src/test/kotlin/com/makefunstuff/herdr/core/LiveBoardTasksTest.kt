package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardFilter
import com.makefunstuff.herdr.core.board.BoardStatus
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The board client against a real machine with herdr-projects. Runs only with HERDR_LIVE_KEY and
 * HERDR_LIVE_TARGET set; HERDR_LIVE_BOARD_REPO names a repo on that board where a throwaway task may be
 * added, archived and cleared again.
 */
class LiveBoardTasksTest {
    private val keyPath = System.getenv("HERDR_LIVE_KEY")
    private val target = System.getenv("HERDR_LIVE_TARGET")
    private val repo = System.getenv("HERDR_LIVE_BOARD_REPO")

    @Test
    fun readsAndChangesARealBoard() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_TARGET to run")
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target!!)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            session.start()
            withTimeout(30_000) { session.state.first { it.online && it.boardPath != null } }
            val board = session.awaitBoard()
            val tasks = board.tasks()
            println("tasks: " + tasks.joinToString { "${it.name}:${it.status}" })
            assertTrue(tasks.all { it.key.isNotEmpty() }, "keys should come from the plain list")
            val first = tasks.first()
            val d = board.details(first.id)
            assertEquals(first.title.take(20), d.title.take(20))
            assertTrue(board.tasks(BoardFilter(query = first.title.take(12))).any { it.id == first.id })

            // A refused command shows the board's own reason.
            val e = assertFailsWith<java.io.IOException> { board.act(BoardAction.Retry, "$repo/9999") }
            println("refused: ${e.message}")
            assertTrue(e.message!!.contains("9999"))

            if (repo != null) {
                val said = board.add("Live board test", repo, "Made by LiveBoardTest; safe to remove.")
                println("add: $said")
                val mine = board.tasks().first { it.title == "Live board test" }
                assertEquals(BoardStatus.Todo, mine.status)
                board.rename(mine.id, "Live board test renamed")
                board.describe(mine.id, "New words")
                assertEquals("New words", board.details(mine.id).description)
                board.act(BoardAction.Archive, mine.id)
                assertTrue(board.tasks(BoardFilter(archived = true)).any { it.id == mine.id && it.status == BoardStatus.Archived })
                board.act(BoardAction.Restore, mine.id)
                board.act(BoardAction.Clear, mine.id)
                assertTrue(board.tasks().none { it.id == mine.id })
            }
            scope.cancel()
        }
    }
}
