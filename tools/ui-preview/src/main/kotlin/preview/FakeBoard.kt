// The work board (herdr-projects) on a fake machine: the demo host's board stand-in
// (docs/screenshots/demo-host/herdr-projects) ported to Kotlin, over the same made-up tasks
// (src/main/resources/demo/board, written by that script).
package preview

import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeBoard(
    /** `list --json`: the tasks, as the board prints them. */
    var tasks: JsonArray = Json.parseToJsonElement(Demo.resource("board/list.json")) as JsonArray,
    /** The plain `list`, which gives each task's short key. */
    var plain: String = Demo.resource("board/list.txt"),
    /** `remote list`: the Coordinator and its conversation. */
    var remote: JsonObject = Json.parseToJsonElement(Demo.resource("board/remote-list.json")).jsonObject,
    /** Commands the board refuses (`approve` without `--anyway`, say), with the reason it prints. */
    var refuse: Map<String, String> = emptyMap(),
    /** `automations --json`: the routines, as the board prints them (devbox's are [Demo.routines]). */
    var automations: JsonArray = JsonArray(emptyList()),
    /** Each automation's firings, newest first, by its id (`automations show <id> --json`); else its last one. */
    var firings: Map<String, JsonArray> = emptyMap(),
) {
    fun run(command: String): CommandResult {
        // sh -c LOGIN sh '<what the login shell runs>'
        val inner = Shell.words(Shell.words(command).last())
        // The review cards' read of a task's record (ApprovalCommands.facts): sh -c <script> herdr-board-facts <launcher> <id> <pr>.
        val facts = inner.indexOf("herdr-board-facts")
        if (facts >= 0) return CommandResult(0, facts(inner[facts + 2]), "")
        val out = StringBuilder()
        var status = 0
        var segment = ArrayList<String>()
        for (w in inner + ";") {
            if (w != ";") { segment += w; continue }
            val at = segment.indexOf(FakeMachine.BOARD_PATH)
            when {
                at >= 0 -> {
                    val args = segment.drop(at + 1)
                    val no = refuse[args.firstOrNull()]?.takeIf { "--anyway" !in args }
                    if (no != null) { out.append(no).append('\n'); status = 1 } else out.append(cli(args))
                }
                segment.firstOrNull() == "echo" -> out.append(segment.drop(1).joinToString(" ")).append('\n')
            }
            segment = ArrayList()
        }
        return CommandResult(status, out.toString(), "")
    }

    private fun cli(args: List<String>): String = when {
        args.take(2) == listOf("remote", "list") -> "$remote\n"
        args.take(2) == listOf("remote", "dispatch") -> """{"ok": true, "msg": "Sent to the Coordinator"}""" + "\n"
        args.firstOrNull() == "list" -> {
            val archived = "--archived" in args
            val query = args.getOrNull(args.indexOf("--query") + 1)?.takeIf { "--query" in args }?.lowercase()
            val keys = plain.lines().filter { it.isNotBlank() }
            val shown = if (archived) emptyList() else tasks.indices.filter { i ->
                query == null || query in title(i).lowercase() || query in keys.getOrElse(i) { "" }.substringBefore(' ').lowercase()
            }
            if ("--json" in args) pretty.encodeToString(JsonArray.serializer(), JsonArray(shown.map { tasks[it] })) + "\n"
            else shown.joinToString("") { keys.getOrElse(it) { "" } + "\n" }
        }
        // A task's settings (or the board's own): the agents it gets, as `show` lists them.
        args.firstOrNull() == "show" -> """
            |* worker.agent              claude/claude-opus-5-5                              you
            |* worker.fallback           codex/gpt-6.1-sol, pi/deepseek/deepseek-flash, pi/llama.cpp/qwen3.8-27b  you
            |* reviewer.agent            codex/gpt-6.1-sol                                   you
            |  reviewer.fallback         none                                                built in
            |  board.agents              4                                                   built in
            |""".trimMargin()
        args.firstOrNull() == "automations" -> automations(args.drop(1).filter { it != "--json" })
        args.firstOrNull() == "details" -> Demo.resource("board/details-${args[1].replace('/', '-')}.json")
        else -> "Done (demo board)\n"
    }

    /** `automations …`: the list, one with its firings, and the board's sentence for each change (on and off are kept). */
    private fun automations(args: List<String>): String {
        val id = args.getOrNull(1).orEmpty()
        val one = automations.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == id }?.jsonObject
        val name = one?.get("name")?.jsonPrimitive?.content ?: args.getOrNull(1).orEmpty()
        return when (args.firstOrNull()) {
            null, "list" -> pretty.encodeToString(JsonArray.serializer(), automations) + "\n"
            // Without firings of its own, its last one is all it has.
            "show" -> one?.let { a ->
                val all = firings[id] ?: JsonArray(listOfNotNull((a["last"] as? JsonObject)))
                pretty.encodeToString(JsonObject.serializer(), JsonObject(a + ("firings" to all))) + "\n"
            }
                ?: "No automation \"$id\".\n"
            "on", "off" -> {
                automations = JsonArray(automations.map { a ->
                    if (a.jsonObject["id"]?.jsonPrimitive?.content == id) JsonObject(a.jsonObject + ("on" to JsonPrimitive(args[0] == "on"))) else a
                })
                "\"$name\" is ${args[0]}.\n"
            }
            "run" -> "\"$name\" fired: api-server/6 was added and an agent is on it.\n"
            "add" -> "Added the automation \"$name\". Each firing adds a task and puts an agent on it.\n"
            "change" -> "Changed \"$name\". It applies from its next firing; tasks already made keep what they had.\n"
            "remove" -> "Removed the automation \"$name\". The tasks it made stay.\n"
            else -> "Unknown: automations ${args[0]}.\n"
        }
    }

    /** What the board's record of [id] holds, as the facts command prints it; a task without one prints its parts empty. */
    private fun facts(id: String): String =
        runCatching { Demo.resource("board/facts-${id.replace('/', '-')}.txt") }.getOrNull()
            ?: "@@herdr-facts task\n\n@@herdr-facts note\n\n@@herdr-facts end\n"

    private fun title(i: Int) = tasks[i].jsonObject["title"]?.jsonPrimitive?.content.orEmpty()

    private companion object {
        /** The board prints its JSON over many lines, which is how the app finds it among a login shell's noise. */
        val pretty = Json { prettyPrint = true }
    }
}
