package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.host.Attachments
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentsTest {
    @Test fun namesAreSafeForTheShellAndMentions() {
        assertEquals("Screenshot-2026-10-17-at-09.41.png", Attachments.safeName("Screenshot 2026-10-17 at 09.41.png"))
        assertEquals("passwd", Attachments.safeName("../../etc/passwd"))
        assertEquals("it-s-a-test-.txt", Attachments.safeName("it's \$a test;.txt"))
        assertEquals("file", Attachments.safeName("..."))
        assertEquals("file", Attachments.safeName("日本語"))
        val long = Attachments.safeName("x".repeat(200) + ".log")
        assertEquals(80, long.length)
        assertTrue(long.endsWith(".log"))
    }

    @Test fun stampIsAFolderName() {
        assertEquals("20261017-094100-00ff", Attachments.stamp(LocalDateTime.of(2026, 10, 17, 9, 41, 0), object : Random() {
            override fun nextBits(bitCount: Int) = 0xff
        }))
    }

    @Test fun mentionGoesFirstAfterACommandAndEarlierMentions() {
        assertEquals("@/h/a.png ", Attachments.withMention("", "/h/a.png"))
        assertEquals("@/h/a.png what is wrong here?", Attachments.withMention("what is wrong here?", "/h/a.png"))
        assertEquals("@/h/a.png @/h/b.png look", Attachments.withMention("@/h/a.png look", "/h/b.png"))
        assertEquals("/review @/h/a.png this diff", Attachments.withMention("/review this diff", "/h/a.png"))
        assertEquals("@\"/home/my user/a.png\" hi", Attachments.withMention("hi", "/home/my user/a.png"))
    }

    @Test fun uploadCommandWritesTheFileAndPrintsItsPath() {
        val home = Files.createTempDirectory("attach-home").toFile()
        try {
            val name = Attachments.safeName("notes it's.txt")
            val process = ProcessBuilder("sh", "-c", Attachments.uploadCommand(name, "20261017-094100-00ff"))
                .also { it.environment()["HOME"] = home.absolutePath }
                .start()
            process.outputStream.use { it.write("hello\nphone".toByteArray()) }
            val printed = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.waitFor())
            assertEquals("${home.absolutePath}/.cache/herdr-android/uploads/20261017-094100-00ff/$name", printed)
            assertEquals("hello\nphone", File(printed).readText())
        } finally {
            home.deleteRecursively()
        }
    }
}
