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
) {
    fun run(command: String): CommandResult {
        // sh -c LOGIN sh '<what the login shell runs>'
        val inner = Shell.words(Shell.words(command).last())
        val out = StringBuilder()
        var segment = ArrayList<String>()
        for (w in inner + ";") {
            if (w != ";") { segment += w; continue }
            val at = segment.indexOf(FakeMachine.BOARD_PATH)
            when {
                at >= 0 -> out.append(cli(segment.drop(at + 1)))
                segment.firstOrNull() == "echo" -> out.append(segment.drop(1).joinToString(" ")).append('\n')
            }
            segment = ArrayList()
        }
        return CommandResult(0, out.toString(), "")
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

    private fun title(i: Int) = tasks[i].jsonObject["title"]?.jsonPrimitive?.content.orEmpty()

    private companion object {
        /** The board prints its JSON over many lines, which is how the app finds it among a login shell's noise. */
        val pretty = Json { prettyPrint = true }
    }
}
