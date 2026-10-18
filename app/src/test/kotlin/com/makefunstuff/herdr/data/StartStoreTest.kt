package com.makefunstuff.herdr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class StartStoreTest {
    @Test fun remembersTheFoldersAndHowWorkWasStartedAcrossARestart() {
        val file = Files.createTempDirectory("starts").resolve("starts.json").toFile()
        val store = StartStore(file)
        store.started("devbox", "/home/demo/src/api-server/", board = true, startNow = false)
        store.started("devbox", "/home/demo/notes", board = false, startNow = true, kind = "codex")
        store.started("devbox", "/home/demo/src/api-server", board = false, startNow = true, kind = "claude")
        store.started("gpu-box", null, board = false, startNow = true)

        val again = StartStore(file)
        assertEquals(listOf("/home/demo/src/api-server", "/home/demo/notes"), again.folders("devbox"))
        assertEquals(emptyList<String>(), again.folders("gpu-box"))
        assertEquals("gpu-box", again.state.value.machine)
        assertFalse(again.state.value.board)
        // An agent now leaves the board task's own choice as it was.
        assertFalse(again.state.value.startNow)
        assertEquals("claude", again.state.value.kinds["devbox"])

        again.keep(setOf("devbox"))
        assertNull(again.state.value.machine)
        assertEquals(2, again.folders("devbox").size)
    }
}
