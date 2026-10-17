package com.makefunstuff.herdr.brief

import com.makefunstuff.herdr.core.brief.Baseline
import com.makefunstuff.herdr.core.brief.Brief
import com.makefunstuff.herdr.core.brief.BriefSchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** When the person last looked, and what they saw, as the app leaves the screen and briefs are read. */
class BriefStoreTest {
    private val seen = mapOf("devbox" to Baseline(tasks = mapOf("herdr-android/7" to "Done"), tasksAt = 100))

    @Test
    fun leavingTheScreenIsLooking() {
        val st = BriefState(seenAt = 50, baselines = mapOf("gpu" to Baseline(agents = emptyMap(), agentsAt = 40))).left(100, seen)
        assertEquals(100, st.seenAt)
        // A machine not looked at this time keeps what was seen of it before.
        assertEquals(setOf("gpu", "devbox"), st.baselines.keys)
    }

    @Test
    fun aBriefReadToItsEndCountsAsSeen() {
        val brief = Brief(at = 200, since = 50, machines = emptyList())
        val made = BriefState(seenAt = 50).made(brief, seen)
        val read = made.read(200)
        assertEquals(200, read.seenAt)
        assertEquals(seen, read.baselines)
        // Another brief than the last one made, or one older than the last look: nothing changes.
        assertEquals(made, made.read(199))
        assertEquals(made.copy(seenAt = 300), made.copy(seenAt = 300).read(200))
    }

    @Test
    fun keptInAFile() {
        val file = Files.createTempDirectory("brief").resolve("brief.json").toFile()
        BriefStore(file).schedule { it.copy(on = true).withTime(17 * 60 + 30) }
        val again = BriefStore(file).state.value.schedule
        assertEquals(BriefSchedule(on = true, times = listOf(8 * 60, 17 * 60 + 30)), again)
    }
}
