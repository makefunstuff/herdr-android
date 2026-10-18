// The work board (herdr-projects) on a fake machine: the demo host's board stand-in
// (docs/screenshots/demo-host/herdr-projects) ported to Kotlin, over the same made-up tasks
// (src/main/resources/demo/board, written by that script).
package preview

import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        args.firstOrNull() == "details" -> Demo.resource("board/details-${args[1].replace('/', '-')}.json")
        else -> "Done (demo board)\n"
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
