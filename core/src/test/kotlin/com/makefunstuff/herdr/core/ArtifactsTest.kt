package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.shepherdr.ArtifactMessage
import com.makefunstuff.herdr.core.shepherdr.ArtifactPage
import com.makefunstuff.herdr.core.shepherdr.ArtifactSendGate
import com.makefunstuff.herdr.core.shepherdr.Artifacts
import com.makefunstuff.herdr.core.shepherdr.BytesReply
import com.makefunstuff.herdr.core.shepherdr.HttpReply
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.ShepherdrTransport
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Chat
import com.makefunstuff.herdr.core.shepherdr.V2Snapshot
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.shepherdr.WebSocketLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactsTest {
    /** A 2.1 snapshot with two artifacts in herdr-android and one at home, as `GET /api/snapshot` sends them. */
    private val snapshot = ShepherdrJson.decodeFromString(
        V2Snapshot.serializer(),
        """
        {"places":[{"id":"p:a","name":"app"},{"id":"p:h","name":"home","home":true}],"tasks":[],
         "artifacts":[
          {"id":"wk3n","title":"Weekly board","place":"p:a","v":3,"at":300,"when":"2m","by":"HA-1 agent","keeper":"HA-1 agent","thumb":true,
           "chats":["HA-1/Agent"],"versions":[{"n":3,"when":"2m","by":"HA-1 agent","html":2,"what":"data"},{"n":2,"when":"1h","by":"HA-1 agent","html":2,"note":"bars by week"},{"n":1,"when":"2h","by":"HA-1 agent","html":1}]},
          {"id":"rn09","title":"Release notes","place":"p:a","v":1,"at":500,"when":"now","by":"app","keeper":"app","versions":[{"n":1,"when":"now","by":"app","html":1}]},
          {"id":"bk","title":"Backups","place":"p:h","v":1,"at":900,"versions":[{"n":1,"html":1}]}
         ]}
        """.trimIndent(),
    )

    @Test
    fun aSpaceListsItsOwnArtifactsNewestFirst() {
        val all = snapshot.artifacts!!
        assertEquals(listOf("rn09", "wk3n"), Artifacts.inSpace(all, "p:a").map { it.id })
        val wk = all.first { it.id == "wk3n" }
        // A version that only brought data shows the page before it.
        assertEquals(2, wk.htmlOf(3))
        assertEquals(1, wk.htmlOf(1))
        assertEquals("v3 · latest · new data", Artifacts.versionLine(wk, wk.versions[0]))
        assertEquals("v2 · bars by week", Artifacts.versionLine(wk, wk.versions[1]))
        // The overview carries them; a Shepherdr that keeps none has none, so the app shows no artifacts.
        assertEquals(3, V2Board.overview(snapshot).artifacts!!.size)
        assertNull(V2Board.overview(V2Snapshot()).artifacts)
    }

    @Test
    fun anArtifactPostedAgainFoldsItsEarlierCard() {
        val chat = ShepherdrJson.decodeFromString(
            V2Chat.serializer(),
            """{"key":"p:a","messages":[
              {"id":"m1","who":"agent","text":"First go","artifact":{"id":"wk3n","v":1}},
              {"id":"m2","who":"agent","artifact":{"id":"rn09","v":1}},
              {"id":"m3","who":"agent","text":"With bars by week","artifact":{"id":"wk3n","v":2}}
            ]}""",
        )
        val cards = SpaceChat.items("p:a", chat).filterIsInstance<SpaceChatItem.Artifact>()
        assertEquals(listOf(true, false, false), cards.map { it.later })
        assertEquals("With bars by week", cards[2].intro)
        assertEquals(2, cards[2].version)
    }

    @Test
    fun thePageIsSealedBeforeAnythingOfItsOwnRuns() {
        val wrapped = """<!doctype html><html class="full phone shepherdr-dark"><head><meta charset="utf-8"><script>/*bridge*/</script></head><body><header>Hi</header></body></html>"""
        val sealed = ArtifactPage.seal(wrapped)
        val csp = sealed.indexOf("Content-Security-Policy")
        assertTrue(csp in 0 until sealed.indexOf("/*bridge*/"))
        assertTrue("connect-src 'none'" in sealed && "default-src 'none'" in sealed)
        assertTrue(sealed.indexOf(ArtifactPage.BRIDGE) < sealed.indexOf("/*bridge*/"))
        // <header> is not <head>; a page without a head still gets the policy first.
        assertTrue(ArtifactPage.seal("<p>Hi</p>").startsWith("<meta http-equiv=\"Content-Security-Policy\""))
        // What goes into the page: data as JSON, the theme, what was sent.
        assertEquals("window.postMessage({shepherdr:'data',data:{\"rows\":[1,2]}},'*');", ArtifactPage.data(ArtifactPage.dataOf("""{"data":{"rows":[1,2]}}""")))
        assertEquals("null", ArtifactPage.dataOf("""{"data":null}"""))
        assertEquals("window.postMessage({shepherdr:'theme',theme:'light'},'*');", ArtifactPage.theme(false))
        assertEquals("window.postMessage({shepherdr:'sent',text:\"a \\\"b\\\"\",to:\"HA-1 agent\"},'*');", ArtifactPage.sent("a \"b\"", "HA-1 agent"))
    }

    @Test
    fun thePagePostsOnlyWhatTheAppTakes() {
        assertEquals(ArtifactMessage.Send("colour: Accent"), ArtifactMessage.parse("""{"shepherdr":"send","text":"colour: Accent"}"""))
        assertEquals(ArtifactMessage.Open("https://example.com/a"), ArtifactMessage.parse("""{"shepherdr":"open","url":"https://example.com/a"}"""))
        assertNull(ArtifactMessage.parse("""{"shepherdr":"open","url":"javascript:alert(1)"}"""))
        assertNull(ArtifactMessage.parse("""{"shepherdr":"send","text":"  "}"""))
        assertNull(ArtifactMessage.parse("""{"shepherdr":"height","h":300}"""))
        assertNull(ArtifactMessage.parse("not json"))
        val pick = ArtifactMessage.parse("""{"shepherdr":"pick","text":"Send","selector":"div#board > form > button.primary","component":"Board"}""") as ArtifactMessage.Pick
        assertEquals("\"Send\" (button.primary in Board)", pick.target())
        assertEquals("(svg)", ArtifactMessage.Pick("", "div > svg").target())
    }

    @Test
    fun aPageSpeaksOnlyRightAfterATouchAndNotTooOften() {
        var now = 10_000L
        val gate = ArtifactSendGate { now }
        // Nothing touched it: a script alone can't send.
        assertNull(gate.admit("hello"))
        gate.touched()
        now += 1_000
        assertEquals("colour: Accent, size: big", gate.admit("colour: Accent,\n  size: big"))
        // Again within two seconds: refused, even right after another tap.
        gate.touched()
        now += 1_500
        assertNull(gate.admit("again"))
        now += 1_000
        assertEquals("again", gate.admit("again"))
        // A touch counts for five seconds, as a browser's does.
        now += 6_000
        assertFalse(gate.fresh())
        assertNull(gate.admit("late"))
        gate.touched()
        assertEquals("x".repeat(2000), gate.admit("x".repeat(3000)))
    }

    @Test
    fun theClientReadsPagesDataAndPicturesAndSendsToTheKeeper() = runBlocking {
        val seen = ArrayList<String>()
        val bodies = ArrayList<String>()
        val t = object : ShepherdrTransport {
            override val canStream = false
            override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply {
                seen += "$method $path"
                body?.let { bodies += it }
                return when {
                    path.startsWith("/api/artifacts/gone") -> HttpReply(404, "no such artifact")
                    path.startsWith("/api/artifacts/wk3n/data") -> HttpReply(200, """{"data":{"rows":[3]}}""")
                    path.startsWith("/api/artifacts/wk3n") -> HttpReply(200, "<html><head></head><body>board</body></html>")
                    path == "/api/action" -> HttpReply(200, """{"to":"HA-1 agent"}""")
                    else -> HttpReply(404, """{"error":"Not found"}""")
                }
            }
            override suspend fun bytes(method: String, path: String, bytes: ByteArray?, type: String?, timeoutMs: Int): BytesReply {
                seen += "$method $path"
                return if (path.contains("thumb")) BytesReply(200, byteArrayOf(1, 2, 3)) else BytesReply(404, ByteArray(0))
            }
            override suspend fun webSocket(path: String): WebSocketLink = throw IOException("none")
            override fun close() {}
        }
        val c = ShepherdrClient(t)
        assertEquals("<html><head></head><body>board</body></html>", c.artifactPage("wk3n", 2, dark = true))
        assertEquals("""{"rows":[3]}""", c.artifactData("wk3n", 3))
        assertEquals(3, c.artifactThumb("wk3n", dark = false)!!.size)
        assertNull(c.artifactFont())
        val err = runCatching { c.artifactPage("gone", 1, dark = false) }.exceptionOrNull()
        assertEquals("This artifact was deleted", err?.message)
        assertEquals(
            listOf(
                "GET /api/artifacts/wk3n?v=2&full=1&device=phone&theme=dark",
                "GET /api/artifacts/wk3n/data?v=3",
                "GET /api/artifacts/wk3n/thumb",
                "GET /api/font/InterVariable.woff2",
                "GET /api/artifacts/gone?v=1&full=1&device=phone&theme=light",
            ),
            seen,
        )
        // What you send reaches its keeper by its space, as the web UI's actions do.
        val board = V2WorkBoard(c, "2.1.0")
        val wk = snapshot.artifacts!!.first { it.id == "wk3n" }
        assertEquals("HA-1 agent", board.artifactSay(wk, 3, "size: big", page = true))
        board.artifactSay(wk, 2, "Make the bars blue")
        board.artifactSay(wk, 3, "Say Save", pick = ArtifactMessage.Pick("Send", "form > button.primary"))
        val sent = bodies.map { ShepherdrJson.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("artifact-send", "artifact-say", "artifact-note"), sent.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("p:a", sent[0]["place"]!!.jsonPrimitive.content)
        assertEquals("size: big", sent[0]["text"]!!.jsonPrimitive.content)
        assertEquals("2", sent[1]["v"]!!.jsonPrimitive.content)
        assertEquals("Say Save", sent[2]["note"]!!.jsonPrimitive.content)
        assertEquals("\"Send\" (button.primary)", sent[2]["target"]!!.jsonPrimitive.content)
    }
}
