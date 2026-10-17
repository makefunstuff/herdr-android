package com.makefunstuff.herdr.push

import android.app.Notification
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.push.LiveItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live Updates as Android sees them: ongoing, asking to be promoted, and promotable on Android 16. */
class AndroidLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val live = AndroidLive(context)

    private fun task(stage: String, round: Int = 0, progress: Int? = null) =
        BoardTask("herdr-android/24", "AND-24", "Live Updates", "working", stage, "", "claude", "Writing the notifier", progress, null, false, "", false, false, round, null, false)

    private val reviewing = LiveItems.task("m1", "devbox", task("reviewing", round = 2), reviews = 4, since = System.currentTimeMillis() - 60_000)!!
    private val working = LiveItems.task("m1", "devbox", task("working", progress = 40))!!

    /**
     * What Android 16 asks of a notification it promotes (Notification.hasPromotableCharacteristics on
     * 16 QPR1, where Live Updates are switched on; on 16.0 that answers false for every app).
     */
    private fun check(n: Notification) {
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("asks to be promoted", n.extras.getBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertTrue("has a title", !n.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty())
        assertTrue("not a group summary", n.flags and Notification.FLAG_GROUP_SUMMARY == 0)
        assertTrue("no custom views", n.contentView == null && n.bigContentView == null && n.headsUpContentView == null)
        assertTrue("not colorized", !n.extras.getBoolean(Notification.EXTRA_COLORIZED))
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals(Notification.ProgressStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
            if (Build.VERSION.SDK_INT_FULL > Build.VERSION_CODES_FULL.BAKLAVA) assertTrue("promotable", n.hasPromotableCharacteristics())
        }
    }

    @Test
    fun aTaskInReview() {
        val n = live.build(reviewing)
        check(n)
        assertEquals("AND-24 Live Updates", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Review 2 of 4 · Writing the notifier", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("Rev 2/4", n.shortCriticalText)
            assertEquals(75, n.extras.getInt(Notification.EXTRA_PROGRESS))
            assertTrue("shows the time in review", n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        }
        assertEquals(listOf("Stop"), n.actions.map { it.title.toString() })
        // A task's Stop asks first.
        assertEquals(listOf("Stop it", "Keep going"), live.build(reviewing, LiveLook.Ask).actions.map { it.title.toString() })
        assertEquals(0, live.build(reviewing, LiveLook.Busy, "Stopping…").actions?.size ?: 0)
    }

    @Test
    fun aWorkingTaskAndAnAgent() {
        val n = live.build(working)
        check(n)
        if (Build.VERSION.SDK_INT >= 36) assertEquals("40%", n.shortCriticalText)
        val agent = LiveItems.agent(
            "m1", "devbox",
            com.makefunstuff.herdr.core.api.AgentInfo(name = "api", agent = "claude", agentStatusRaw = "working", workspaceId = "w1", tabId = "t1", paneId = "w1:p1"),
            "api › agents", since = System.currentTimeMillis() - 5_000,
        )!!
        val a = live.build(agent)
        check(a)
        if (Build.VERSION.SDK_INT >= 36) assertTrue("going, no end known", a.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        // An agent at work can also float its chat over other apps, where Android has bubbles.
        assertEquals(if (Build.VERSION.SDK_INT >= 30) listOf("Stop", "Float chat") else listOf("Stop"), a.actions.map { it.title.toString() })
    }
}
