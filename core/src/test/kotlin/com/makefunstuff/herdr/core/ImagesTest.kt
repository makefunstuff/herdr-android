package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.host.ImageSizing
import com.makefunstuff.herdr.core.host.Images
import com.makefunstuff.herdr.core.host.Markup
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImagesTest {
    private val up = "/home/demo/.cache/herdr-android/uploads/20261018-101500-3f2a"

    @Test fun picturesAreTakenOutOfTheWords() {
        assertEquals(
            Images.Split(listOf("$up/a.png", "$up/b.jpg"), "why is this red?"),
            Images.split("@$up/a.png @$up/b.jpg why is this red?"),
        )
        // Only pictures by their full path: other files and relative paths stay mentioned.
        assertEquals(
            Images.Split(listOf("$up/a.png"), "@$up/log.txt see @src/icon.png"),
            Images.split("@$up/a.png @$up/log.txt see @src/icon.png"),
        )
        // Quoted paths with spaces, a slash command in front, lines kept.
        assertEquals(
            Images.Split(listOf("/home/my user/shot one.PNG"), "/review this\nand that"),
            Images.split("/review @\"/home/my user/shot one.PNG\" this\nand that"),
        )
        assertEquals(Images.Split(listOf("~/Pictures/x.webp"), ""), Images.split("@~/Pictures/x.webp "))
        // An address is not a mention, and a message without pictures is left as it is.
        val plain = "mail me@example.com about /tmp/x.png  please"
        assertEquals(Images.Split(emptyList(), plain), Images.split(plain))
    }

    @Test fun onlyPicturesCount() {
        assertTrue(Images.isImage("/a/b/IMG_2041.HEIC"))
        assertTrue(Images.isImage("shot.jpeg"))
        assertTrue(!Images.isImage("notes.txt"))
        assertTrue(!Images.isImage("/a/png"))
    }

    @Test fun fetchCommandPrintsTheFileAsBase64AndRefusesWhatIsTooBig() {
        val home = Files.createTempDirectory("fetch-home").toFile()
        try {
            val bytes = ByteArray(3000) { (it * 7).toByte() }
            java.io.File(home, "pics it's").mkdirs()
            java.io.File(home, "pics it's/a.png").writeBytes(bytes)
            fun run(command: String): Pair<Int, String> {
                val p = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = home.path }.start()
                val out = p.inputStream.readBytes().decodeToString()
                return p.waitFor() to out
            }
            val (status, out) = run(Images.fetchCommand("~/pics it's/a.png"))
            assertEquals(0, status)
            assertContentEquals(bytes, java.util.Base64.getMimeDecoder().decode(out.trim()))
            assertEquals(0, run(Images.fetchCommand("${home.path}/pics it's/a.png")).first)
            assertNotEquals(0, run(Images.fetchCommand("~/pics it's/a.png", maxBytes = 100)).first)
            assertNotEquals(0, run(Images.fetchCommand("~/pics it's/gone.png")).first)
            assertNotEquals(0, run(Images.fetchCommand("~/pics it's")).first)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun bigPhotosComeDownToTheLongSide() {
        assertEquals(2048 to 1536, ImageSizing.fit(4032, 3024))
        assertEquals(922 to 2048, ImageSizing.fit(1080, 2400))
        assertEquals(800 to 600, ImageSizing.fit(800, 600))
        // A 48 MP photo is read at half its size, still over 2048 on its long side.
        assertEquals(2, ImageSizing.sampleSize(8064, 6048))
        assertEquals(2, ImageSizing.sampleSize(4096, 3072))
        assertEquals(1, ImageSizing.sampleSize(4095, 3000))
        assertEquals(1, ImageSizing.sampleSize(1080, 2400))
        assertTrue(8064 / ImageSizing.sampleSize(8064, 6048) >= ImageSizing.MAX_EDGE)
    }

    @Test fun screenshotsStayPngAndPhotosBecomeJpeg() {
        assertEquals(ImageSizing.Format.Png, ImageSizing.format("image/png", "Screenshot.png"))
        assertEquals(ImageSizing.Format.Png, ImageSizing.format(null, "diagram.webp"))
        assertEquals(ImageSizing.Format.Jpeg, ImageSizing.format("image/heic", "IMG_2041.HEIC"))
        assertEquals(ImageSizing.Format.Jpeg, ImageSizing.format("image/jpeg", "x"))
        assertEquals(ImageSizing.Format.Jpeg, ImageSizing.format(null, "camera"))
        assertEquals("IMG_2041.jpg", ImageSizing.rename("IMG_2041.HEIC", ImageSizing.Format.Jpeg))
        assertEquals("camera.jpg", ImageSizing.rename("camera", ImageSizing.Format.Jpeg))
        assertEquals("v1.2-shot.png", ImageSizing.rename("v1.2-shot.webp", ImageSizing.Format.Png))
        assertEquals(".hidden.png", ImageSizing.rename(".hidden", ImageSizing.Format.Png))
    }

    @Test fun theCropFrameStaysOnThePicture() {
        val whole = Markup.Crop()
        assertTrue(whole.isWhole)
        val c = Markup.drag(whole, Markup.Handle.TopLeft, 0.2f, 0.1f)
        assertEquals(Markup.Crop(0.2f, 0.1f, 1f, 1f), c)
        // Moved whole, it stops at the edge.
        assertEquals(Markup.Crop(0f, 0f, 0.8f, 0.9f), Markup.drag(c, Markup.Handle.Inside, -0.5f, -0.5f))
        // An edge can't pass the one across from it.
        val thin = Markup.drag(c, Markup.Handle.Right, -2f, 0f)
        assertEquals(0.2f + Markup.MIN_CROP, thin.right, 1e-6f)
        assertEquals(Markup.Crop(0.2f, 0.1f, 1f, 1f), Markup.drag(c, Markup.Handle.Bottom, 0f, 3f))
    }

    @Test fun aTouchTakesTheNearestPartOfTheFrame() {
        val c = Markup.Crop(0.1f, 0.1f, 0.9f, 0.9f)
        // Shown at 1000×2000 px: corners at (100, 200) and (900, 1800).
        assertEquals(Markup.Handle.TopLeft, Markup.handleAt(c, 1000f, 2000f, 110f, 190f, 40f))
        assertEquals(Markup.Handle.BottomRight, Markup.handleAt(c, 1000f, 2000f, 880f, 1820f, 40f))
        assertEquals(Markup.Handle.Top, Markup.handleAt(c, 1000f, 2000f, 500f, 210f, 40f))
        assertEquals(Markup.Handle.Left, Markup.handleAt(c, 1000f, 2000f, 70f, 1000f, 40f))
        assertEquals(Markup.Handle.Inside, Markup.handleAt(c, 1000f, 2000f, 500f, 1000f, 40f))
        assertNull(Markup.handleAt(c, 1000f, 2000f, 20f, 20f, 40f))
    }

    @Test fun anArrowPointsWhereItWasDrawnTo() {
        val (a, b) = assertNotNull(Markup.arrowHead(0f, 0f, 100f, 0f, 20f))
        // Both wings behind the point, one each side of the shaft.
        assertTrue(a.x < 100f && b.x < 100f)
        assertTrue(a.y * b.y < 0)
        assertEquals(20f, kotlin.math.hypot(100f - a.x, a.y), 1e-3f)
        assertNull(Markup.arrowHead(0f, 0f, 2f, 0f, 20f))
    }

    @Test fun strokesSkipJitterAndCropsAreWholePixels() {
        var pts = Markup.extend(emptyList(), Markup.Spot(0f, 0f), 0.01f)
        pts = Markup.extend(pts, Markup.Spot(0.005f, 0f), 0.01f)
        pts = Markup.extend(pts, Markup.Spot(0.02f, 0f), 0.01f)
        assertEquals(listOf(Markup.Spot(0f, 0f), Markup.Spot(0.02f, 0f)), pts)
        assertContentEquals(intArrayOf(0, 0, 1080, 2400), Markup.cropPixels(Markup.Crop(), 1080, 2400))
        assertContentEquals(intArrayOf(108, 240, 864, 1920), Markup.cropPixels(Markup.Crop(0.1f, 0.1f, 0.9f, 0.9f), 1080, 2400))
    }
}
