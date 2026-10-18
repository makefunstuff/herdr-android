package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.chat.QuickReplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ComposerStoreTest {
    @Test fun keepsHeldMessagesPerChatAcrossARestart() {
        val file = Files.createTempDirectory("composer").resolve("composer.json").toFile()
        val store = ComposerStore(file)
        val a = store.hold("agent/devbox/w1:p1", "Also add it to the widget", 1_000)
        val b = store.hold("agent/devbox/w1:p1", "and the tile", 1_000)
        store.hold("agent/devbox/w2:p1", "check the index", 2_000)
        assertTrue(a.id != b.id)

        val again = ComposerStore(file)
        assertEquals(listOf("Also add it to the widget", "and the tile"), again.held("agent/devbox/w1:p1").map { it.text })
        again.drop("agent/devbox/w1:p1", a.id)
        again.drop("agent/devbox/w2:p1", again.held("agent/devbox/w2:p1").single().id)
        assertEquals(listOf("and the tile"), ComposerStore(file).held("agent/devbox/w1:p1").map { it.text })
        assertFalse("agent/devbox/w2:p1" in ComposerStore(file).state.value.held)
    }

    @Test fun quickRepliesStartOffAndKeepWhatWasTyped() {
        val file = Files.createTempDirectory("composer").resolve("composer.json").toFile()
        val store = ComposerStore(file)
        assertFalse(store.state.value.replies.on)
        assertEquals(QuickReplies.DEFAULT, store.state.value.replies.shown)
        // An empty one being written stays in the list; it never shows as a chip.
        store.setReplies(QuickReplies(on = true, replies = listOf("continue", "")))
        val again = ComposerStore(file).state.value.replies
        assertTrue(again.on)
        assertEquals(listOf("continue", ""), again.replies)
        assertEquals(listOf("continue"), again.shown)
    }
}
