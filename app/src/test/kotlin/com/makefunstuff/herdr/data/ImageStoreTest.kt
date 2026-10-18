package com.makefunstuff.herdr.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A composer's pictures: kept through a restart, copied once, named in the message, and kept on the phone once sent. */
class ImageStoreTest {
    private val root = Files.createTempDirectory("images").toFile()
    private val key = DraftStore.agent("devbox", "w1:p1")
    private val up = "/home/demo/.cache/herdr-android/uploads/20261018-101204-3f2a"

    /** A new [ImageStore] on the same files: the app started again. */
    private fun store() = ImageStore(File(root, "images.json"), File(root, "tray"), File(root, "sent"))

    private fun picture(s: ImageStore, id: String, bytes: String = "png $id"): TrayImage {
        val f = s.newFile("png").apply { writeText(bytes) }
        return TrayImage(id, "$id.png", f.path, 540, 1200)
    }

    private fun tray(s: ImageStore) = runBlocking { s.tray(key).first() }

    @Test fun picturesWaitInTheComposerThroughARestart() {
        val a = store()
        assertTrue(a.add(key, picture(a, "one")))
        assertTrue(a.add(key, picture(a, "two")))
        val b = store()
        assertEquals(listOf("one", "two"), tray(b).map { it.id })
        // A picture whose file is gone (Android cleared it) isn't brought back broken.
        File(tray(b).first().path).delete()
        assertEquals(listOf("two"), tray(store()).map { it.id })
    }

    @Test fun theMessageNamesThePicturesInFrontOfTheWords() {
        val s = store()
        s.add(key, picture(s, "one"))
        s.add(key, picture(s, "two"))
        val (one, two) = s.current(key)
        assertTrue(s.uploaded(key, one.id, "devbox", "$up/one.png", one.path))
        assertTrue(s.uploaded(key, two.id, "devbox", "$up/two.png", two.path))
        assertEquals("@$up/one.png @$up/two.png why is this red?", s.message(key, "  why is this red? "))
        assertEquals("/review @$up/one.png @$up/two.png this", s.message(key, "/review this"))
        // Only pictures: the mentions alone.
        assertEquals("@$up/one.png @$up/two.png", s.message(key, ""))
    }

    @Test fun aSentPictureStaysOnThePhoneForTheChat() {
        val s = store()
        s.add(key, picture(s, "one", "the pixels"))
        val one = s.current(key).single()
        s.uploaded(key, one.id, "devbox", "$up/one.png", one.path)
        val sent = s.current(key)
        // Added while the message was on its way: stays for the next one.
        s.add(key, picture(s, "late"))
        s.sent(key, sent)
        assertEquals(listOf("late"), s.current(key).map { it.id })
        assertFalse(File(one.path).exists())
        assertEquals("the pixels", s.sentFile("devbox", "$up/one.png").readText())
        // The same path on another machine is another picture.
        assertFalse(s.sentFile("gpu-box", "$up/one.png").exists())
    }

    @Test fun anEditedPictureIsCopiedAgainAndAnOldCopyDoesNotCount() {
        val s = store()
        s.add(key, picture(s, "one"))
        val first = s.current(key).single()
        val edited = picture(s, "edited")
        s.replace(key, first.id, edited.copy(id = "ignored"))
        val now = s.current(key).single()
        assertEquals(first.id, now.id)
        assertEquals(edited.path, now.path)
        assertNull(now.remote)
        assertFalse(File(first.path).exists())
        // The copy of the old one finishing late doesn't mark the edited one as copied.
        assertFalse(s.uploaded(key, first.id, "devbox", "$up/one.png", first.path))
        assertNull(s.current(key).single().remote)
    }

    @Test fun aMessageTakesTenPicturesAtMost() {
        val s = store()
        repeat(ImageStore.MAX_IMAGES) { assertTrue(s.add(key, picture(s, "p$it"))) }
        val extra = picture(s, "extra")
        assertFalse(s.add(key, extra))
        assertFalse(File(extra.path).exists())
        s.remove(key, "p0")
        assertEquals(ImageStore.MAX_IMAGES - 1, s.current(key).size)
    }

    @Test fun aPictureReadBackIsKept() {
        val s = store()
        val f = s.keepSent("devbox", "~/shots/a.png", byteArrayOf(1, 2, 3))
        assertEquals(f, s.sentFile("devbox", "~/shots/a.png"))
        assertEquals(3L, f.length())
    }
}
