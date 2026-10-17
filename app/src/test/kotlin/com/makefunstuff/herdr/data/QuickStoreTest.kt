package com.makefunstuff.herdr.data

import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class QuickStoreTest {
    @Test
    fun pinsAreKeptPerMachineInOrder() {
        val file = Files.createTempDirectory("quick").resolve("quick.json").toFile()
        val store = QuickStore(file)
        store.setPinned("devbox", "Project:Run unit tests", true)
        store.setPinned("devbox", "Global:Disk usage", true)
        store.setPinned("gpu-box", "Global:Disk usage", true)
        store.setPinned("gpu-box", "Global:Disk usage", false)
        assertTrue(store.pinned("devbox", "Global:Disk usage"))
        assertFalse(store.pinned("gpu-box", "Global:Disk usage"))
        // Read again, as after a restart: the same pins, in the order they were pinned; a machine with none is gone.
        val again = QuickStore(file)
        assertEquals(mapOf("devbox" to listOf("Project:Run unit tests", "Global:Disk usage")), again.pins.value)
    }
}
