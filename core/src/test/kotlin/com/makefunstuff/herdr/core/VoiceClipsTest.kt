package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.host.Images
import com.makefunstuff.herdr.core.host.VoiceClips
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VoiceClipsTest {
    private val dir = "/home/demo/src/herdr-android/.herdr-voice"

    @Test fun voiceMessagesAreTakenOutOfTheWords() {
        assertEquals(
            VoiceClips.Split(listOf("$dir/voice-message-1.ogg"), "and the widget too"),
            VoiceClips.split("@$dir/voice-message-1.ogg and the widget too"),
        )
        // A voice message alone, quoted, under ~/, and pictures left for Images.split.
        assertEquals(VoiceClips.Split(listOf("~/a b/v.m4a"), ""), VoiceClips.split("@\"~/a b/v.m4a\" "))
        val both = "@/up/shot.png @$dir/v.aac look"
        val voice = VoiceClips.split(both)
        assertEquals(listOf("$dir/v.aac"), voice.paths)
        assertEquals(Images.Split(listOf("/up/shot.png"), "look"), Images.split(voice.text))
        // Only full paths: a relative one stays in the words, as does a message without any.
        assertEquals(VoiceClips.Split(emptyList(), "see @notes/v.ogg"), VoiceClips.split("see @notes/v.ogg"))
    }

    @Test fun onlyAudioCounts() {
        assertTrue(VoiceClips.isAudio("/x/voice-message-1.OGG"))
        assertTrue(VoiceClips.isAudio("a.opus"))
        assertTrue(!VoiceClips.isAudio("a.png"))
        assertTrue(!VoiceClips.isAudio("/x/ogg"))
        assertEquals("voice-message-20261026-091204-3f2a.ogg", VoiceClips.fileName("20261026-091204-3f2a", "ogg"))
    }

    private fun run(home: File, command: String, input: ByteArray = ByteArray(0)): Pair<Int, String> {
        val p = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = home.path }.start()
        p.outputStream.use { it.write(input) }
        val out = p.inputStream.readBytes().decodeToString()
        return p.waitFor() to out
    }

    @Test fun theRecordingGoesIntoTheAgentsFolderWhereGitLeavesItOut() {
        val home = Files.createTempDirectory("voice-home").toFile()
        try {
            val repo = File(home, "src/it's mine").apply { mkdirs() }
            check(ProcessBuilder("git", "init", "-q", repo.path).start().waitFor() == 0)
            val bytes = ByteArray(5000) { (it * 13).toByte() }
            val (status, path) = run(home, VoiceClips.uploadCommand(repo.path, "voice-message-1.ogg", "s1"), bytes)
            assertEquals(0, status)
            assertEquals("${repo.path}/.herdr-voice/voice-message-1.ogg", path)
            assertContentEquals(bytes, File(path).readBytes())
            assertEquals("*\n", File(repo, ".herdr-voice/.gitignore").readText())
            val git = ProcessBuilder("git", "-C", repo.path, "status", "--porcelain").start()
            assertEquals("", git.inputStream.readBytes().decodeToString())
            // The second one beside it; the .gitignore is left as it is.
            File(repo, ".herdr-voice/.gitignore").writeText("*\n# kept\n")
            assertEquals(0, run(home, VoiceClips.uploadCommand(repo.path, "voice-message-2.ogg", "s2"), bytes).first)
            assertEquals("*\n# kept\n", File(repo, ".herdr-voice/.gitignore").readText())
            // Read back to play it.
            val (fetched, out) = run(home, VoiceClips.fetchCommand(path))
            assertEquals(0, fetched)
            assertContentEquals(bytes, java.util.Base64.getMimeDecoder().decode(out.trim()))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun withoutTheAgentsFolderItGoesToTheUploads() {
        val home = Files.createTempDirectory("voice-home").toFile()
        try {
            val gone = File(home, "gone").path
            val (status, path) = run(home, VoiceClips.uploadCommand(gone, "voice-message-1.ogg", "s1"), byteArrayOf(1, 2, 3))
            assertEquals(0, status)
            assertEquals("${home.path}/.cache/herdr-android/uploads/s1/voice-message-1.ogg", path)
            // A folder that is gone isn't made again.
            assertTrue(!File(gone).exists())
            val (none, other) = run(home, VoiceClips.uploadCommand(null, "voice-message-2.ogg", "s2"), byteArrayOf(4))
            assertEquals(0, none)
            assertEquals("${home.path}/.cache/herdr-android/uploads/s2/voice-message-2.ogg", other)
            assertNotEquals(0, run(home, VoiceClips.fetchCommand("~/nothing.ogg")).first)
        } finally {
            home.deleteRecursively()
        }
    }
}
