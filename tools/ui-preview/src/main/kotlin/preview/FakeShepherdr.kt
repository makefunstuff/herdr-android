// Shepherdr 2 on a fake machine: its API as the app reaches it without a port forward (the previews
// forward nothing), `curl` for the HTTP calls and the machine's `bun` for a chat (CurlTransport),
// answered from fixed demo data. What the app sends (a message, an answer) changes the chat, so a
// scene that taps Send or a choice shows what follows.
package preview

import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeShepherdr(
    var version: String = "2.0.0",
    /** `GET /api/snapshot`. */
    var snapshot: JsonObject = Json.parseToJsonElement(DemoV2.SNAPSHOT).jsonObject,
    /** Each chat as its live link sends it (`Chat`), by its key. */
    var chats: Map<String, JsonObject> = DemoV2.chats(),
) {
    /** The bodies of `POST /api/action`, as sent. */
    val actions = ArrayList<JsonObject>()

    /** A `curl` to its API or the `bun` that reads a chat; null for any other command. */
    fun run(command: String, stdin: String): CommandResult? {
        val w = Shell.words(command)
        if (w.firstOrNull() == "curl") return curl(w, stdin)
        if ("@@shepherdr-chat@@" in command) {
            val key = Regex("""const k=("(?:[^"\\]|\\.)*")""").find(command)?.groupValues?.get(1)
                ?.let { Json.parseToJsonElement(it).jsonPrimitive.content } ?: return CommandResult(3, "", "no chat")
            val chat = chats[key] ?: obj("key" to key, "messages" to JsonArray(emptyList()))
            return CommandResult(0, "@@shepherdr-chat@@" + obj("type" to "chat", "chat" to chat) + "\n", "")
        }
        return null
    }

    private fun curl(w: List<String>, stdin: String): CommandResult {
        val method = w.getOrNull(w.indexOf("-X") + 1) ?: "GET"
        val path = w.last().substringAfter("7787")
        val (status, body) = when {
            path == "/api/version" -> 200 to """{"root":"/home/demo/.shepherdr","version":"$version"}"""
            path == "/api/snapshot" -> 200 to snapshot.toString()
            path == "/api/waiting" -> 200 to """{"count":${(snapshot["asks"] as? JsonObject)?.size ?: 0},"tags":[]}"""
            path == "/api/action" && method == "POST" -> 200 to act(Json.parseToJsonElement(stdin).jsonObject)
            path == "/api/cli" -> 200 to """{"out":"Done (demo)\n","code":0}"""
            else -> 404 to """{"error":"Not found"}"""
        }
        return CommandResult(0, "$body\n@@shepherdr-status@@$status", "")
    }

    private fun act(a: JsonObject): String {
        actions += a
        val chat = a["chat"]?.jsonPrimitive?.content.orEmpty()
        when (a["type"]?.jsonPrimitive?.content) {
            // In at once, the coordinator at work on it.
            "send" -> change(chat) { c ->
                val m = obj("id" to "sent-${actions.size}", "who" to "you", "text" to a.getValue("text"), "at" to Demo.NOW) +
                    listOfNotNull(a["cid"]?.let { "cid" to it }, a["reply"]?.let { r -> "reply" to quote(c, r.jsonPrimitive.content) })
                JsonObject(c + mapOf("messages" to JsonArray(messages(c) + JsonObject(m)), "working" to JsonPrimitive(true)))
            }
            // The card shows the choice sent, here and on its copy in a space's chat.
            "answer" -> {
                val q = a.getValue("question").jsonPrimitive.content
                val choice = a.getValue("choice")
                chats.keys.forEach { key -> change(key) { c -> JsonObject(c + ("messages" to JsonArray(messages(c).map { answered(it.jsonObject, q, choice) }))) } }
            }
        }
        return """{"ok":true}"""
    }

    private fun change(key: String, f: (JsonObject) -> JsonObject) {
        chats[key]?.let { chats = chats + (key to f(it)) }
    }

    private fun messages(c: JsonObject) = c["messages"]?.jsonArray.orEmpty()

    private fun quote(c: JsonObject, id: String): JsonObject {
        val m = messages(c).firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == id }?.jsonObject
        return obj("id" to id, "who" to "agent", "text" to (m?.get("text") ?: JsonPrimitive("")))
    }

    private fun answered(m: JsonObject, q: String, choice: JsonElement): JsonObject {
        val question = m["question"]?.jsonObject
        if (question != null && question["id"]?.jsonPrimitive?.content == q) return JsonObject(m + ("question" to JsonObject(question + ("sent" to choice))))
        val card = m["card"]?.jsonObject ?: return m
        val ask = card["ask"]?.jsonObject ?: return m
        val qs = ask["questions"]?.jsonArray ?: return m
        if (qs.none { it.jsonObject["id"]?.jsonPrimitive?.content == q }) return m
        val label = qs.first { it.jsonObject["id"]?.jsonPrimitive?.content == q }.jsonObject["choices"]?.jsonArray
            ?.getOrNull(choice.jsonPrimitive.content.toIntOrNull() ?: -1)?.jsonObject?.get("label")?.jsonPrimitive?.content ?: "Answered"
        val now = JsonObject(ask + mapOf("open" to JsonPrimitive(false), "answered" to obj("where" to "here", "text" to label)))
        return JsonObject(m + ("card" to JsonObject(card + ("ask" to now))))
    }
}

/** devbox on Shepherdr 2: its home space, herdr-android and api-server, and their coordinators' chats at [Demo.NOW]. */
object DemoV2 {
    const val HOME = "p:devbox:~"
    const val APP = "p:devbox:herdr-android"
    const val API = "p:devbox:api-server"

    val SNAPSHOT = """
        {"name":"Shepherdr","host":"devbox","user":"Sam",
         "machines":[{"id":"m:devbox","machine":"devbox","name":"devbox","status":"idle"}],
         "places":[
           {"id":"$HOME","name":"home","machine":"devbox","home":true,"level":1,"status":"idle","chat":"$HOME"},
           {"id":"$APP","name":"herdr-android","machine":"devbox","status":"need","chat":"$APP","githubRemote":true},
           {"id":"$API","name":"api-server","machine":"devbox","status":"work","chat":"$API","githubRemote":true}
         ],
         "tasks":[
           {"id":"AND-13","name":"Keep the Board card in place on reconnect","status":"ready","place":"$APP","agent":{"chat":"AND-13/Agent","status":"ready"},"pr":{"number":212,"url":"https://github.com/example/herdr-android/pull/212"}},
           {"id":"AND-14","name":"Fix the flaky upload test","status":"need","place":"$APP","agent":{"chat":"AND-14/Agent","status":"need"},"report":{"msg":"Plan to approve","progress":10}},
           {"id":"AND-15","name":"Voice input in the task composer","status":"work","place":"$APP","agent":{"chat":"AND-15/Agent","status":"work"},"report":{"msg":"Wiring the recognizer","progress":40}},
           {"id":"API-7","name":"Speed up /jobs on large tables","status":"work","place":"$API","agent":{"chat":"API-7/Agent","status":"work"},"report":{"msg":"Adding the index","progress":60}}
         ],
         "asks":{"$APP":"ask:merge-and-13"}}
    """.trimIndent()

    private val APP_CHAT = """
        {"key":"$APP","cli":"Claude Code","working":false,"messages":[
          {"id":"m1","who":"you","at":1,"text":"The Board card jumps when a machine reconnects, and the upload test is flaky again. Can you get agents on both?"},
          {"id":"m2","who":"agent","at":2,"text":"I added two tasks and started an agent on each:\n\n- **AND-13** Keep the Board card in place on reconnect\n- **AND-14** Fix the flaky upload test\n\nEach opens a pull request and waits for you before merging."},
          {"id":"c3","who":"agent","at":3,"text":"AND-13 is ready to merge","card":{"id":"c3","task":"AND-13","status":"ready","text":"is ready to merge","pr":{"number":212,"url":"https://github.com/example/herdr-android/pull/212"}}},
          {"id":"c4","who":"agent","at":4,"text":"AND-14 needs you: Plan to approve","card":{"id":"c4","task":"AND-14","status":"need","text":"needs you: Plan to approve",
            "ask":{"chat":"AND-14/Agent","open":true,"questions":[{"id":"plan-and-14","title":"Plan for AND-14","kind":"Plan",
              "steps":["Run the upload test 50 times to catch the failure","Wait for the temp file to close before reading it back","Add a test that uploads while the connection drops"],
              "choices":[{"label":"Approve"},{"label":"Send it back"}],"noteRequiredFor":1,"to":"AND-14 agent"}]}}},
          {"id":"m5","who":"agent","at":5,"text":"AND-13 passed its checks and its review. The card stays put now when a machine reconnects; I checked it on the Board and Agents screens.",
            "question":{"id":"ask:merge-and-13","title":"Merge AND-13 (PR #212)?","kind":"Question","choices":[{"label":"Merge it","description":"AND-15 then starts on top of it"},{"label":"Let me look first"}],"to":"herdr-android"}},
          {"id":"m6","who":"agent","at":6,"text":"The release notes for 0.9","artifact":{"id":"rn09","v":2}}
        ]}
    """.trimIndent()

    private val HOME_CHAT = """
        {"key":"$HOME","cli":"Claude Code","working":false,"messages":[
          {"id":"h1","who":"agent","at":1,"text":"Morning. Nothing on devbox needs you from here: herdr-android has one merge waiting, api-server is at work on API-7."},
          {"id":"h2","who":"you","at":2,"text":"Thanks. Is the backup to the NAS still running at night?"},
          {"id":"h3","who":"agent","at":3,"text":"Yes, it ran at 02:00 and finished in 14 minutes."}
        ]}
    """.trimIndent()

    private val API_CHAT = """
        {"key":"$API","cli":"Codex","working":true,"messages":[
          {"id":"a1","who":"you","at":1,"text":"/jobs is slow on the big tenant again. Look into it?"},
          {"id":"a2","who":"agent","at":2,"text":"API-7 is on it: it's adding an index on (tenant, created_at)."}
        ]}
    """.trimIndent()

    /**
     * devbox's board on Shepherdr 2 for the Board and task scenes (`demo/shepherdr/`): home, herdr-android and
     * website, a task in every status, two tasks whose cards ask (one choice or your own words; several), and
     * website's coordinator with a question open; the coordinators' chats above too.
     */
    fun board() = FakeShepherdr(
        snapshot = Json.parseToJsonElement(Demo.resource("shepherdr/snapshot.json")).jsonObject,
        chats = chats() + Json.parseToJsonElement(Demo.resource("shepherdr/chats.json")).jsonObject.mapValues { it.value.jsonObject },
    )

    fun chats(): Map<String, JsonObject> =
        listOf(APP_CHAT, HOME_CHAT, API_CHAT).associate { Json.parseToJsonElement(it).jsonObject.let { c -> c.getValue("key").jsonPrimitive.content to c } }
}
