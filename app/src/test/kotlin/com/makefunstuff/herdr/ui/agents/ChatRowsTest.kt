package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ChatTurn
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.core.chat.WorkCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {
    private val steps = ChatTurns.Steps(emptyList())

    private fun turn(key: String, at: Long, end: Long, answer: Boolean = true, failed: Int = 0) = ChatTurn(
        key = key,
        user = ChatItem.User(key, "Do $key"),
        work = listOf(ChatItem.Agent("$key/n", "Looking."), ChatItem.Tool("$key/t", "Bash", "ls", state = ToolState.Done)),
        answer = if (answer) listOf(ChatItem.Agent("$key/a", "Done.")) else emptyList(),
        at = at,
        end = end,
        counts = WorkCounts(commands = 1),
        failed = failed,
        keepsOpen = !answer,
    )

    private fun rows(turns: List<ChatTurn>, live: ChatRow.Live? = null, opened: Map<String, Boolean> = emptyMap()) =
        chatRows(turns, steps, live, opened, now = 200_000) { "t$it" }

    @Test fun aSettledTurnFoldsItsWorkAndKeepsItsAnswerOut() {
        val r = rows(listOf(turn("u1", 1_000, 157_000, failed = 1)))
        assertEquals(listOf("u1", "head/u1", "u1/a"), r.map { it.key })
        val head = r[1] as ChatRow.Head
        assertEquals("Worked for 2m 36s", head.title)
        assertEquals("1 command", head.summary)
        assertEquals(1, head.failed)
        assertEquals("t1000", head.time)
        assertFalse(head.open)
    }

    @Test fun theRunningTurnIsOpenAndTimedFromNow() {
        val r = rows(listOf(turn("u1", 1_000, 2_000), turn("u2", 20_000, 30_000, answer = false)), live = ChatRow.Live.Working)
        val head = r.filterIsInstance<ChatRow.Head>().last()
        assertEquals("Working for 3m", head.title)
        assertTrue(head.open)
        // Its narration speaks in the agent's voice while it runs.
        assertEquals(listOf("head/u2", "u2/n", "tools/u2/t"), r.dropWhile { it.key != "head/u2" }.map { it.key })
        assertFalse((r.first { it.key == "u2/n" } as ChatRow.Agent).quiet)
        assertEquals("Needs you", rows(listOf(turn("u1", 1_000, 2_000, answer = false)), live = ChatRow.Live.Blocked).filterIsInstance<ChatRow.Head>().single().title)
    }

    @Test fun anAnsweredTurnDoesNotReopenForWorkTheTranscriptHasntShown() {
        // Working, but the prompt it works on (typed in the terminal) isn't in the transcript yet.
        val old = rows(listOf(turn("u1", 1_000, 8_000)), live = ChatRow.Live.Working).filterIsInstance<ChatRow.Head>().single()
        assertEquals("Worked for 7s", old.title)
        assertFalse(old.open)
        // Words just now are narration on the way to its next call.
        assertEquals("Working for 3m 19s", rows(listOf(turn("u1", 1_000, 190_000)), live = ChatRow.Live.Working).filterIsInstance<ChatRow.Head>().single().title)
        // A question waits for you however long ago it came.
        assertEquals("Needs you", rows(listOf(turn("u1", 1_000, 8_000)), live = ChatRow.Live.Blocked).filterIsInstance<ChatRow.Head>().single().title)
    }

    @Test fun aTurnOpenedByHandShowsItsWorkQuietly() {
        val r = rows(listOf(turn("u1", 1_000, 8_000)), opened = mapOf("u1" to true))
        assertEquals(listOf("u1", "head/u1", "u1/n", "tools/u1/t", "u1/a"), r.map { it.key })
        assertTrue((r.first { it.key == "u1/n" } as ChatRow.Agent).quiet)
        assertEquals("Worked for 7s", (r[1] as ChatRow.Head).title)
    }

    @Test fun onlyMessagesCountAsNew() {
        val r = rows(listOf(turn("u1", 1_000, 8_000)), opened = mapOf("u1" to true))
        assertEquals(listOf("u1", "u1/a"), r.filter { it.isMessage }.map { it.key })
    }
}
