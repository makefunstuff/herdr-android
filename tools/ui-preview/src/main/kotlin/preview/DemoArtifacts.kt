// Shepherdr 2.1's artifacts on devbox: three pages agents made in herdr-android and api-server, the
// pictures its check took of them (drawn here as blocks, so every run gives the same pixels), and a post
// of one in a task's chat. FakeShepherdr serves them as `/api/artifacts/…` does.
package preview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

object DemoArtifacts {
    /** The snapshot's `artifacts`: newest first by `at`, release notes posted in herdr-android's coordinator chat (`m6`). */
    val LIST = """
        [{"id":"rn09","title":"Release notes for 0.9","place":"${DemoV2.APP}","v":2,"at":1793229000000,"when":"3m","by":"herdr-android","keeper":"herdr-android","thumb":true,
          "chats":["${DemoV2.APP}"],"versions":[{"n":2,"when":"3m","by":"herdr-android","html":2,"note":"grouped by area"},{"n":1,"when":"1h","by":"herdr-android","html":1}]},
         {"id":"bl3x","title":"Board load times","place":"${DemoV2.APP}","v":3,"at":1793228000000,"when":"20m","by":"AND-19 agent","keeper":"AND-19 agent","thumb":true,
          "chats":["AND-19/Agent"],"versions":[{"n":3,"when":"20m","by":"AND-19 agent","html":2,"what":"data"},{"n":2,"when":"1h","by":"AND-19 agent","html":2,"note":"by screen"},{"n":1,"when":"2h","by":"AND-19 agent","html":1}]},
         {"id":"sq7k","title":"Slow queries on /jobs","place":"${DemoV2.API}","v":1,"at":1793227000000,"when":"40m","by":"API-7 agent","keeper":"API-7 agent","thumb":true,
          "chats":["API-7/Agent"],"versions":[{"n":1,"when":"40m","by":"API-7 agent","html":1}]}]
    """.trimIndent()

    /** Each page as Shepherdr would wrap it (its own head and bridge left out: the stand-in runs no script). */
    val PAGES = mapOf(
        "rn09" to """
            <!doctype html><html class="full phone"><head><meta charset="utf-8"><title>Release notes</title></head><body>
            <h1>Herdr for Android 0.9</h1>
            <p>Out on Friday. Every build installs over the last one and keeps its machines and SSH key.</p>
            <h2>Chats</h2>
            <ul><li>Artifacts open full screen, in your light or dark</li><li>Voice messages in task chats</li><li>A task chat keeps its whole composer</li></ul>
            <h2>Board</h2>
            <ul><li>Shepherdr 2.1's agents and tasks</li><li>Merge from the task's own card</li></ul>
            <h2>Fixed</h2>
            <ul><li>The Board card no longer jumps on reconnect</li></ul>
            </body></html>
        """.trimIndent(),
        "bl3x" to """<!doctype html><html><head></head><body><h1>Board load times</h1><p>Median time to the first card, by screen, this week.</p></body></html>""",
        "sq7k" to """<!doctype html><html><head></head><body><h1>Slow queries on /jobs</h1><p>The five slowest, before and after the index.</p></body></html>""",
    )

    /** A version's data alone, as `/api/artifacts/<id>/data` answers. */
    fun data(id: String): String = if (id == "bl3x") """{"data":{"board":420,"agents":310,"task":260}}""" else """{"data":null}"""

    /** The check's picture of [id] at a phone's width, in [dark] or light: its heading, lines and, for a chart, its bars. */
    fun thumb(id: String, dark: Boolean): ByteArray {
        val w = 780
        val h = 960
        val surface = Surface.makeRasterN32Premul(w, h)
        val c = surface.canvas
        // Shepherdr's --bg, --fg, --fg3, --well and --accent (server/frame.ts).
        c.clear(if (dark) 0xFF0B0B0D.toInt() else 0xFFFFFFFF.toInt())
        val fg = Paint().apply { color = if (dark) 0xFFFAFAFA.toInt() else 0xFF09090B.toInt() }
        val dim = Paint().apply { color = if (dark) 0xFF3F3F46.toInt() else 0xFFD4D4D8.toInt() }
        val well = Paint().apply { color = if (dark) 0xFF1C1C20.toInt() else 0xFFF4F4F5.toInt() }
        val accent = Paint().apply { color = if (dark) 0xFFFB923C.toInt() else 0xFFC2410C.toInt() }
        val blue = Paint().apply { color = if (dark) 0xFF60A5FA.toInt() else 0xFF2563EB.toInt() }
        c.drawRRect(RRect.makeXYWH(40f, 48f, if (id == "rn09") 520f else 440f, 44f, 8f), fg)
        c.drawRRect(RRect.makeXYWH(40f, 124f, 640f, 18f, 9f), dim)
        c.drawRRect(RRect.makeXYWH(40f, 156f, 480f, 18f, 9f), dim)
        if (id == "rn09") {
            var y = 220f
            listOf(3, 2, 1).forEach { items ->
                c.drawRRect(RRect.makeXYWH(40f, y, 160f, 26f, 6f), accent)
                y += 50f
                repeat(items) { i ->
                    c.drawCircle(52f, y + 9f, 6f, fg)
                    c.drawRRect(RRect.makeXYWH(76f, y, (if (i % 2 == 0) 520f else 400f), 18f, 9f), dim)
                    y += 40f
                }
                y += 24f
            }
        } else {
            c.drawRRect(RRect.makeXYWH(40f, 220f, 700f, 520f, 16f), well)
            listOf(0.8f, 0.6f, 0.5f, 0.35f, 0.3f).forEachIndexed { i, f ->
                val bar = 380f * f
                c.drawRect(Rect.makeXYWH(100f + i * 125f, 700f - bar, 80f, bar), if (i == 0) accent else blue)
            }
        }
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}

/** devbox on Shepherdr 2.1 with artifacts: the release notes in herdr-android's chat, and the load-time chart posted in AND-19's. */
fun SceneData.artifacts() {
    val v2 = DemoV2.board().apply { version = "2.1.0" }
    v2.snapshot = JsonObject(v2.snapshot + ("artifacts" to Json.parseToJsonElement(DemoArtifacts.LIST).jsonArray))
    v2.chats["AND-19/Agent"]?.let { c ->
        val post = obj("id" to "art1", "who" to "agent", "at" to 1793229180869L, "text" to "The load times by screen, before and after:", "artifact" to obj("id" to "bl3x", "v" to 3))
        v2.chats = v2.chats + ("AND-19/Agent" to JsonObject(c + ("messages" to JsonArray(c.getValue("messages").jsonArray + post))))
    }
    host(Demo.DEVBOX).shepherdr = v2
    host(Demo.DEVBOX).coreSessions = mapOf("shepherdr" to DemoCore.shepherdr())
}
