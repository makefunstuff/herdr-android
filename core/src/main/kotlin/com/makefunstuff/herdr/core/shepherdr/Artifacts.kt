package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.board.WorkSpace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Shepherdr 2's artifacts as the app shows them: which a space keeps, and what a page may send its keeper. */
object Artifacts {
    /**
     * [space]'s artifacts, newest first, as Shepherdr's own Artifacts list has them: a space's own; a home
     * space's ([WorkSpace.level] 1) its machine's; the main home's (0) every machine's. [spaces] says where each lives.
     */
    fun inSpace(all: List<V2Artifact>, space: String, spaces: List<WorkSpace> = emptyList()): List<V2Artifact> {
        val at = spaces.firstOrNull { it.id == space }
        val machineOf = spaces.associate { it.id to it.machine }
        return all.filter { a ->
            when (at?.level) {
                0 -> true
                1 -> a.place == space || machineOf[a.place] == at.machine
                else -> a.place == space
            }
        }.sortedByDescending { it.at }
    }

    /** What a page sends, as its keeper gets it: on one line (it can't write a chat line of its own), at most 2000 characters. */
    fun line(text: String): String = text.replace(Regex("""\s*[\r\n]+\s*"""), " ").trim().take(2000)

    /** How a version reads in its list: "v3 · latest · bars by week", "v2 · new data". */
    fun versionLine(a: V2Artifact, v: V2ArtifactVersion): String = buildString {
        append("v").append(v.n)
        if (v.n == a.v) append(" · latest")
        val what = v.note?.takeIf { it.isNotBlank() } ?: when (v.what) {
            "data" -> "new data"
            "title" -> "new title"
            else -> null
        }
        what?.let { append(" · ").append(it) }
    }
}

/**
 * What a page may say for you: only right after you tapped or typed in it ([touched], which only the
 * app's own view marks, never the page), at most once every two seconds, as Shepherdr's web UI lets it.
 */
class ArtifactSendGate(private val clock: () -> Long) {
    private var touchedAt = Long.MIN_VALUE / 2
    private var sentAt = Long.MIN_VALUE / 2

    /** A finger lifted on the page, or a key or a word typed into it. */
    fun touched() {
        touchedAt = clock()
    }

    /** Whether you just touched the page: what a link it opens needs too. */
    fun fresh(): Boolean = clock() - touchedAt <= TOUCH_MS

    /** [text] on one line ([Artifacts.line]) when it may go now (then it counts as sent), else null. */
    fun admit(text: String): String? {
        val now = clock()
        if (!fresh() || now - sentAt <= EVERY_MS) return null
        val line = Artifacts.line(text).ifEmpty { return null }
        sentAt = now
        return line
    }

    companion object {
        /** How long a touch lets a page act, as long as a browser's own mark of one lasts. */
        const val TOUCH_MS = 5_000L
        const val EVERY_MS = 2_000L
    }
}

/** What an artifact's page posts to the app through its bridge (`server/frame.ts`). */
sealed interface ArtifactMessage {
    /** A form's Send, or `window.shepherdr.send(text)`. */
    data class Send(val text: String) : ArtifactMessage

    /** A web link tapped in it. */
    data class Open(val url: String) : ArtifactMessage

    /** In Mark up, what was tapped: its words, a CSS path to it and its component. */
    data class Pick(val text: String, val selector: String, val component: String? = null) : ArtifactMessage {
        /** How the note names it to its keeper, as the web UI does: `"Send" (button.primary in Board)`. */
        fun target(): String {
            val last = selector.split(" > ").last()
            val words = text.take(60).takeIf { it.isNotEmpty() }?.let { "\"$it\" " }.orEmpty()
            return words + "(" + last + (component?.let { " in $it" }.orEmpty()) + ")"
        }
    }

    companion object {
        /** One post as the bridge sends it (JSON with a `shepherdr` kind); null for one the app doesn't take. */
        fun parse(json: String): ArtifactMessage? {
            val o = runCatching { ShepherdrJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return when (str("shepherdr")) {
                "send" -> str("text")?.takeIf { it.isNotBlank() }?.let(::Send)
                "open" -> str("url")?.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.let(::Open)
                "pick" -> Pick(str("text").orEmpty().trim(), str("selector").orEmpty(), str("component")?.takeIf { it.isNotEmpty() })
                else -> null
            }
        }
    }
}

/** Shepherdr didn't serve the artifact: it was deleted, or its artifacts are turned off there. Asking again won't help. */
class ArtifactGone(message: String) : java.io.IOException(message)

/**
 * An artifact's page sealed for the app's WebView, as Shepherdr's frame is for its browser: Shepherdr
 * wraps it (its colours, the bridge, its data, [ShepherdrClient.artifactPage]); the app serves it at
 * [PAGE] with the headers Shepherdr's frame has ([HEADERS]: a sandbox, so an opaque origin with no
 * storage or dialogs, and the sealed policy before a byte of it is read), and passes the bridge's posts on.
 */
object ArtifactPage {
    /** Where the page seems to come from: nowhere real. Only its font is served there ([FONT]); the rest is refused. */
    const val BASE = "https://artifact.herdr.invalid/api/artifacts/"

    /** Where the app serves the page itself, from its WebView's request handler (never the network). */
    const val PAGE = BASE + "page"

    /** The font the page names (`../font/InterVariable.woff2` from [BASE]). */
    const val FONT = "https://artifact.herdr.invalid/api/font/InterVariable.woff2"

    /** The JavaScript interface the bridge's posts reach. */
    const val BRIDGE = "HerdrArtifact"

    /** Shepherdr's frame policy (`SEALED` in `server/frame.ts`): its own inline code and styles, pictures as data, nothing loaded. */
    const val SEALED = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; " +
        "font-src data: https://artifact.herdr.invalid; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-src 'none'; object-src 'none'"

    /** The page's response headers, as `frameResponse` in `server/frame.ts` sends them. */
    val HEADERS = mapOf(
        "Content-Security-Policy" to "sandbox allow-scripts allow-forms; $SEALED",
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to "no-store",
    )

    /**
     * The page's posts to its parent come back to its own window in a WebView: the ones for the app go on to
     * [BRIDGE]. Peer connections (WebRTC), which no policy covers, are taken off its window before its code runs; a
     * blank frame of its own still has them, as in Shepherdr's web UI.
     */
    private val RELAY = "(function(){['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','RTCIceCandidate'].forEach(function(n){" +
        "try{Object.defineProperty(window,n,{value:undefined,writable:false,configurable:false})}catch(x){}});" +
        "var k={send:1,open:1,pick:1};addEventListener('message',function(e){var d=e.data;" +
        "if(e.source!==window||!d||typeof d!=='object'||!k[d.shepherdr])return;try{$BRIDGE.post(JSON.stringify(d))}catch(x){}})})();"

    private val DOCTYPE = Regex("""^\s*<!doctype[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * [html] as Shepherdr served it, with the policy again (beside [HEADERS]) and the relay at its very start,
     * after only its doctype: the parser puts them in the head before anything of the page's, whatever it holds.
     */
    fun seal(html: String): String {
        val ours = """<meta http-equiv="Content-Security-Policy" content="$SEALED"><script>$RELAY</script>"""
        val doctype = DOCTYPE.find(html) ?: return "<!doctype html>$ours$html"
        return html.substring(0, doctype.range.last + 1) + ours + html.substring(doctype.range.last + 1)
    }

    /** Hands the page [data] (its JSON) without a reload: `window.shepherdr.data` and a `shepherdr:data` event. */
    fun data(data: String): String = post("{shepherdr:'data',data:${data.ifBlank { "null" }}}")

    /** Tells the page the app's light or dark changed. */
    fun theme(dark: Boolean): String = post("{shepherdr:'theme',theme:'${if (dark) "dark" else "light"}'}")

    /** Tells the page what it sent went, and to whom. */
    fun sent(text: String, to: String?): String =
        post("{shepherdr:'sent',text:${JsonPrimitive(text)}" + (to?.let { ",to:${JsonPrimitive(it)}" }.orEmpty()) + "}")

    /** Mark up on or off: in it a tap picks what it touched instead of doing it. */
    fun markup(on: Boolean): String = post("{shepherdr:'markup',on:$on}")

    private fun post(message: String) = "window.postMessage($message,'*');"

    /** The `data` of `GET /api/artifacts/<id>/data`'s answer, as JSON text. */
    fun dataOf(answer: String): String =
        runCatching { ShepherdrJson.parseToJsonElement(answer).jsonObject["data"]?.toString() }.getOrNull() ?: "null"
}

/** The bodies of `POST /api/action` for an artifact: a page's Send, a word to its keeper, a note in Mark up. */
object V2ArtifactActions {
    fun send(a: V2Artifact, v: Int, text: String): JsonObject = action("artifact-send", a, v, "text" to text)

    fun say(a: V2Artifact, v: Int, text: String): JsonObject = action("artifact-say", a, v, "text" to text)

    fun note(a: V2Artifact, v: Int, note: String, target: String?): JsonObject =
        action("artifact-note", a, v, "note" to note, *listOfNotNull(target?.let { "target" to it }).toTypedArray())

    private fun action(type: String, a: V2Artifact, v: Int, vararg words: Pair<String, String>) = JsonObject(
        mapOf("type" to JsonPrimitive(type), "place" to JsonPrimitive(a.place), "artifact" to JsonPrimitive(a.id), "v" to JsonPrimitive(v)) +
            words.associate { it.first to JsonPrimitive(it.second) },
    )
}
