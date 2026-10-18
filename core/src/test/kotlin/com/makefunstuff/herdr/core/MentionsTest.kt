package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardGlance
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.CoordinatorAsk
import com.makefunstuff.herdr.core.board.TaskMentions
import com.makefunstuff.herdr.core.board.TaskMentions.Part
import kotlin.test.Test
import kotlin.test.assertEquals

class MentionsTest {
    private fun task(key: String, state: String = "idle", stage: String = "", kind: String = "", archived: Boolean = false) = BoardTask(
        id = "repo/${key.substringAfter('-')}", key = key, title = "Task $key", state = state, stage = stage, kind = kind, agent = "",
        msg = "", progress = null, eta = null, research = false, pr = "", archived = archived, pinned = false,
        reviewRound = 0, reviewPassed = null, approvalBlocked = false,
    )

    @Test
    fun findsKeysOnceInOrderAndLeavesLookalikesAlone() {
        assertEquals(listOf("API-3", "AND-6", "GPT-4"), TaskMentions.keys("API-3 waits for AND-6 (and API-3 again), not GPT-4o; GPT-4 is a key"))
        assertEquals(emptyList(), TaskMentions.keys("x-1, AND-, -6, and-6, AND-6x, re-AND-6"))
    }

    @Test
    fun listItemsThatStartWithAKeyBecomeTasksInPlace() {
        val answer = "Added two tasks:\n\n- **AND-6** Add app screenshots to the README\n- **API-3** Speed up `/jobs` on large tables\n\nAPI-3 has an agent now. AND-6 waits for AND-2."
        assertEquals(
            listOf(
                Part.Words("Added two tasks:"),
                Part.Task("AND-6", "Add app screenshots to the README"),
                Part.Task("API-3", "Speed up `/jobs` on large tables"),
                Part.Words("API-3 has an agent now. AND-6 waits for AND-2."),
                Part.Task("AND-2"),
            ),
            TaskMentions.split(answer, setOf("AND-6", "API-3", "AND-2")),
        )
    }

    @Test
    fun unknownKeysAndCodeStayWords() {
        val answer = "1. WEB-9: not on this board\n```\n- AND-6 in a code block\n```\nRun `API-3` by hand."
        assertEquals(listOf(Part.Words(answer)), TaskMentions.split(answer, setOf("API-3")))
        assertEquals(listOf(Part.Words(answer)), TaskMentions.split(answer, emptySet()))
        // In code it is the code's: no row, in its place or after.
        assertEquals(listOf(Part.Words(answer)), TaskMentions.split(answer, setOf("AND-6")))
    }

    @Test
    fun anItemKeepsItsOwnWords() {
        val answer = "Two need you:\n\n- **AND-9**: approve the push to main, it touches CI\n- API-3: asks which index to use (btree or gin)\n  It waits since 09:12.\n\nThe rest work."
        assertEquals(
            listOf(
                Part.Words("Two need you:"),
                Part.Task("AND-9", "approve the push to main, it touches CI"),
                Part.Task("API-3", "asks which index to use (btree or gin)\nIt waits since 09:12."),
                Part.Words("The rest work."),
            ),
            TaskMentions.split(answer, setOf("AND-9", "API-3")),
        )
        assertEquals(listOf(Part.Task("AND-9")), TaskMentions.split("- AND-9", setOf("AND-9")))
    }

    @Test
    fun aBoldKeyLeavesNoMarkBehind() {
        assertEquals(
            listOf(Part.Task("AND-9", "ready to merge, review passed")),
            TaskMentions.split("- **AND-9:** ready to merge, review passed", setOf("AND-9")),
        )
        assertEquals(
            listOf(Part.Task("AND-9", "**Fix login**: ready to merge")),
            TaskMentions.split("- **AND-9 Fix login**: ready to merge", setOf("AND-9")),
        )
        assertEquals(listOf(Part.Task("AND-9", "ready")), TaskMentions.split("- `AND-9`: ready", setOf("AND-9")))
    }

    @Test
    fun subItemsStayWithTheirTask() {
        val answer = "- **API-3** Speed up /jobs\n  - asks about the lock\n  - waits since 09:12\n- **AND-9** Fix login\n - ready to merge"
        assertEquals(
            listOf(
                Part.Task("API-3", "Speed up /jobs\n- asks about the lock\n- waits since 09:12"),
                Part.Task("AND-9", "Fix login\n- ready to merge"),
            ),
            TaskMentions.split(answer, setOf("API-3", "AND-9")),
        )
    }

    @Test
    fun theNoteSaysWhatTheTitleDoesNot() {
        val title = "Speed up /jobs on large tables"
        // The title again on the first line: the lines under it stay.
        val note = TaskMentions.split("- **API-3** Speed up /jobs on large tables\n Asks whether the migration may lock the jobs table.", setOf("API-3")).single() as Part.Task
        assertEquals("Asks whether the migration may lock the jobs table.", TaskMentions.beyondTitle(note.note, title))
        assertEquals("", TaskMentions.beyondTitle("Speed up `/jobs` on large tables.", title))
        assertEquals("", TaskMentions.beyondTitle("", title))
        // The title it starts with goes, the words after it stay.
        assertEquals("ready to merge", TaskMentions.beyondTitle("**Fix login**: ready to merge", "Fix login"))
        assertEquals("touches `auth.kt`\nsecond line", TaskMentions.beyondTitle("Fix login — touches `auth.kt`\nsecond line", "Fix login"))
        assertEquals("ready to merge", TaskMentions.beyondTitle("Speed up `/jobs` on large tables: ready to merge", title))
        // Only a whole title counts.
        assertEquals("Fix logins everywhere", TaskMentions.beyondTitle("Fix logins everywhere", "Fix login"))
        assertEquals("asks which index to use", TaskMentions.beyondTitle("asks which index to use", title))
    }

    @Test
    fun aListThatMixesTasksAndOtherItemsStaysWords() {
        val answer = "1. AND-6 first\n2. Something else\n3. API-3 last"
        assertEquals(listOf(Part.Words(answer), Part.Task("AND-6"), Part.Task("API-3")), TaskMentions.split(answer, setOf("AND-6", "API-3")))
        // A key the board doesn't have makes it a mixed list too.
        val other = "- AND-6 first\n- WEB-9 elsewhere"
        assertEquals(listOf(Part.Words(other), Part.Task("AND-6")), TaskMentions.split(other, setOf("AND-6")))
    }

    @Test
    fun aKeyNamedInPassingComesAfterTheWordsOnce() {
        assertEquals(
            listOf(Part.Words("Started an agent on AND-6. AND-6 runs the app."), Part.Task("AND-6")),
            TaskMentions.split("Started an agent on AND-6. AND-6 runs the app.", setOf("AND-6")),
        )
    }

    @Test
    fun theGlanceCountsAsTheBoardTabDoes() {
        val tasks = listOf(
            task("AND-9", "blocked", "approval", "permission"),
            task("API-3", "blocked", "working", "question"),
            task("AND-6", "working", "working"),
            task("API-5", "working", "reviewing"),
            task("WEB-5", "idle", "queued"),
            task("AND-10"),
            task("AND-2", "done", "done"),
            task("OLD-1", "blocked", "working", "question", archived = true),
        )
        assertEquals(BoardGlance(needYou = 2, working = 2, queued = 1), BoardGlance.of(tasks))
        assertEquals(true, BoardGlance.of(listOf(task("AND-2", "done", "done"))).quiet)
    }

    @Test
    fun asksAreOnlyNextSteps() {
        val asking = task("API-3", "blocked", "working", "question")
        val working = task("AND-6", "working", "working")
        val done = task("AND-2", "done", "done")
        val queued = task("WEB-5", "idle", "queued")
        // The open task named last in the conversation; a done one is passed over.
        assertEquals(
            listOf("What needs me?", "Start the queued ones", "Status of AND-6"),
            CoordinatorAsk.of(listOf(asking, working, done, queued), listOf("Added API-3 and AND-6", "AND-2 is merged, start AND-6 too.", "AND-2 is done.")).map { it.label },
        )
        assertEquals("What's the status of AND-6?", CoordinatorAsk.of(listOf(working), emptyList()).single().text)
        // Nothing named: what waits for you first.
        assertEquals(listOf("What needs me?", "Status of API-3"), CoordinatorAsk.of(listOf(working, asking), emptyList()).map { it.label })
        assertEquals(emptyList(), CoordinatorAsk.of(listOf(done), listOf("AND-2 is done")))
    }
}
