package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.share.Shared
import com.makefunstuff.herdr.core.share.SharedFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What was shared outlives the app's process: a share waiting for a machine, and files waiting for their machine. */
class SharesTest {
    private val root = Files.createTempDirectory("shares").toFile()
    private val store = File(root, "shares.json")
    private val dir = File(root, "shared")
    private var clock = 1_790_000_000_000L
    private val day = 24L * 3600 * 1000

    /** A new [Shares] on the same file: the app started again after Android ended its process. */
    private fun shares() = Shares(store, dir) { clock }

    private fun shareFile(s: Shares, name: String = "shot.png"): SharedFile {
        val f = File(File(s.newDir(), "0").apply { mkdirs() }, name).apply { writeText("png") }
        return SharedFile(name, f.path, 3)
    }

    private fun files(s: Shares, machine: String) = runBlocking { s.files(machine).first() }

    @Test fun aShareWaitingForAMachineOutlivesTheProcess() {
        val a = shares()
        val file = shareFile(a)
        a.share(Shared("CI is red", listOf(file)))
        val b = shares()
        assertEquals(Shared("CI is red", listOf(file)), b.pending.value)
        // Chosen after the restart: the words to the draft, the file waits to be copied.
        var draft = ""
        b.deliver("m1") { draft = it }
        assertEquals("CI is red", draft)
        assertNull(b.pending.value)
        assertEquals(listOf(file), files(b, "m1"))
    }

    @Test fun filesWaitingForAnOfflineMachineOutliveTheProcess() {
        val a = shares()
        val file = shareFile(a)
        a.share(Shared("", listOf(file)))
        a.deliver("m1") {}
        a.used("m1")
        // The machine is offline; the process ends; the app comes back.
        val b = shares()
        assertNull(b.pending.value)
        assertEquals(listOf(file), files(b, "m1"))
        assertEquals("m1", b.last.value)
        // Copied once it is online: off the list, off the phone, and still so after another restart.
        b.done("m1", file)
        assertFalse(File(file.path).exists())
        assertEquals(emptyList<SharedFile>(), files(shares(), "m1"))
    }

    @Test fun aShareLeftForDaysIsNotBroughtUpAgain() {
        val a = shares()
        val file = shareFile(a)
        a.share(Shared("old", listOf(file)))
        clock += 3 * day
        assertNull(shares().pending.value)
        assertFalse(File(file.path).exists())
    }

    @Test fun cancelledShareLeavesNoFiles() {
        val a = shares()
        val file = shareFile(a)
        a.share(Shared("x", listOf(file)))
        a.cancel()
        assertNull(shares().pending.value)
        assertFalse(File(file.path).exists())
    }

    @Test fun oldFoldersGoUnlessAFileInThemStillWaits() {
        val a = shares()
        val waiting = shareFile(a, "waits.png")
        a.share(Shared("", listOf(waiting)))
        a.deliver("m1") {}
        clock += 1
        val stale = shareFile(a, "stale.png")
        val waitingDir = File(waiting.path).parentFile.parentFile
        val staleDir = File(stale.path).parentFile.parentFile
        waitingDir.setLastModified(clock - 3 * day)
        staleDir.setLastModified(clock - 3 * day)
        a.newDir()
        assertTrue(File(waiting.path).exists())
        assertFalse(File(stale.path).exists())
    }

    @Test fun aMachineTakenOutTakesItsWaitingFiles() {
        val a = shares()
        val file = shareFile(a)
        a.share(Shared("", listOf(file)))
        a.deliver("gone") {}
        a.keep(setOf("m1"))
        assertEquals(emptyList<SharedFile>(), files(shares(), "gone"))
        assertFalse(File(file.path).exists())
    }
}
