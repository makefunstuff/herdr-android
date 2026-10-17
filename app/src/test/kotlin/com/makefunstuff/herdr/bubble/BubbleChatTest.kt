package com.makefunstuff.herdr.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A floating chat's id names it again: its bubble, shortcut and notification are found by it. */
class BubbleChatTest {
    @Test fun idsRoundTrip() {
        listOf(
            BubbleChat.Agent("a1b2c3d4", "w1:p1"),
            BubbleChat.Agent("a1b2c3d4", "p 3/with:colons"),
            BubbleChat.Coordinator("a1b2c3d4"),
        ).forEach { assertEquals(it, BubbleChat.parse(it.id)) }
    }

    @Test fun eachChatHasItsOwnId() {
        assertEquals("agent:m1:w1:p1", BubbleChat.Agent("m1", "w1:p1").id)
        assertEquals("coordinator:m1", BubbleChat.Coordinator("m1").id)
    }

    @Test fun anythingElseIsNoChat() {
        listOf(null, "", "agent", "agent:m1", "agent::p1", "coordinator:", "coordinator:m1:x", "board:m1").forEach {
            assertNull(it, BubbleChat.parse(it))
        }
    }
}
