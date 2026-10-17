package com.makefunstuff.herdr.core.quick

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/** Where a run stands. */
enum class RunState {
    /** Just started: nothing to say yet. */
    Starting,
    /** The machine sets up the AI worker (installs pi-durable). */
    Setup,
    Running,
    Done,
    Failed,
    Stopped,
    /** A prompt that ran into its time or token limit. */
    Limit,
    /** Not running and not finished: the machine restarted or the worker died. A prompt carries on with [QuickClient.resume]. */
    Lost;

    val finished: Boolean get() = this == Done || this == Failed || this == Stopped || this == Limit
    val running: Boolean get() = this == Starting || this == Setup || this == Running
}

/** One run of a quick command on a machine, as its folder there tells it. */
data class QuickRun(
    val id: String,
    val name: String,
    val kind: QuickKind,
    val state: RunState,
    val icon: String = "",
    /** The command's file. */
    val path: String = "",
    /** The folder it ran in, and its repository. */
    val cwd: String = "",
    val repo: String = "",
    /** What was typed in its form. */
    val args: String = "",
    val model: String = "",
    val scope: QuickScope = QuickScope.Global,
    /** A script's exit status, once it ended by itself. */
    val exit: Int? = null,
    /** When it started and ended, the machine's clock in seconds; 0 unknown. */
    val started: Long = 0,
    val ended: Long = 0,
    /** A prompt's worker: what it does now, the tokens used, and why it failed. */
    val activity: String = "",
    val tokens: Long = 0,
    val error: String = "",
    /** A script's output so far, in bytes. */
    val size: Long = 0,
    /** A line of what it said: a prompt's answer's start, a script's last output. */
    val preview: String = "",
) {
    /** How long it ran (or has run, at [now]), in seconds. */
    fun seconds(now: Long): Long = when {
        started <= 0 -> 0
        ended > 0 -> maxOf(0, ended - started)
        else -> maxOf(0, now - started)
    }

    /** It can be run again as it was: the command, the folder and the arguments are known. */
    val repeatable: Boolean get() = path.isNotEmpty()
}

/** The runs on a machine, newest first, and the machine's clock when it said so. */
data class RunList(val now: Long, val runs: List<QuickRun>)

/** One run as read: its facts, and its output from where the read began ([text]; a prompt's whole answer). */
data class RunRead(val now: Long, val run: QuickRun?, val text: String)

/** The commands a folder has, and where it is. */
data class QuickListing(
    val commands: List<QuickCommand>,
    /** The checkout's top folder, its repository's main checkout, its branch; empty outside Git. */
    val repo: String = "",
    val mainRepo: String = "",
    val branch: String = "",
    /** The commands folders that exist there, by scope. */
    val folders: Map<QuickScope, List<String>> = emptyMap(),
    /** The machine's home folder. */
    val home: String = "",
) {
    /** Where a new project command goes: the checkout's `.herdr/commands`. */
    val projectFolder: String get() = repo.takeIf { it.isNotEmpty() }?.let { "$it/.herdr/commands" }.orEmpty()

    /** Where a new command for everywhere goes. */
    val globalFolder: String get() = if (home.isNotEmpty()) "$home/.config/herdr/commands" else "~/.config/herdr/commands"
}

object QuickReader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun listing(out: String): QuickListing {
        val found = ArrayList<QuickCommand>()
        val folders = LinkedHashMap<QuickScope, MutableList<String>>()
        var repo = ""; var main = ""; var branch = ""; var home = ""
        var file: Pair<QuickScope, String>? = null
        val text = StringBuilder()
        for (line in out.split('\n')) {
            if (file != null) {
                if (line == "${QuickCommands.MARK} end") {
                    val (scope, path) = file
                    found += QuickHeader.parse(path, scope, text.toString().removeSuffix("\n"))
                    file = null
                    text.clear()
                } else text.append(line).append('\n')
                continue
            }
            val f = line.split('\t')
            when (f[0]) {
                "@@repo" -> { repo = f.getOrElse(1) { "" }; main = f.getOrElse(2) { "" }; branch = f.getOrElse(3) { "" } }
                "@@dir" -> scope(f.getOrNull(1))?.let { s -> folders.getOrPut(s) { mutableListOf() } += f.getOrElse(2) { "" } }
                "@@file" -> { val s = scope(f.getOrNull(1)); val p = f.getOrNull(2); if (s != null && p != null) file = s to p }
                "@@home" -> home = f.getOrElse(1) { "" }
            }
        }
        return QuickListing(QuickList.of(found), repo, main.takeIf { it != repo }.orEmpty(), branch.takeIf { it != "HEAD" }.orEmpty(), folders, home)
    }

    private fun scope(s: String?) = when (s) { "project" -> QuickScope.Project; "global" -> QuickScope.Global; else -> null }

    fun runs(out: String): RunList {
        val (now, blocks) = blocks(out.split('\n'))
        return RunList(now, blocks.mapNotNull { run(it.first, it.second) })
    }

    fun read(out: String): RunRead {
        val head = out.substringBefore("\n@@out\n", out)
        val text = if ("\n@@out\n" in out) out.substringAfter("\n@@out\n") else ""
        val (now, blocks) = blocks(head.split('\n'))
        val b = blocks.firstOrNull()
        return RunRead(now, b?.let { run(it.first, it.second) }, text)
    }

    /** `@@now`, then each `@@run <id>` with its `key<TAB>value` lines. */
    private fun blocks(lines: List<String>): Pair<Long, List<Pair<String, Map<String, String>>>> {
        var now = 0L
        val out = ArrayList<Pair<String, Map<String, String>>>()
        var id: String? = null
        var keys = HashMap<String, String>()
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val k = line.substring(0, tab); val v = line.substring(tab + 1)
            when (k) {
                "@@now" -> now = v.trim().toLongOrNull() ?: 0
                "@@run" -> { id?.let { out += it to keys }; id = v.trim(); keys = HashMap() }
                else -> if (id != null) keys[k] = v
            }
        }
        id?.let { out += it to keys }
        return now to out
    }

    private fun run(id: String, k: Map<String, String>): QuickRun? {
        val meta = k["meta"]?.let(::obj) ?: return null
        val status = k["status"]?.let(::obj)
        val kind = if (meta.str("kind") == "prompt") QuickKind.Prompt else QuickKind.Script
        val alive = k["alive"] == "1"
        val exitText = k["exit"]?.trim().orEmpty()
        val started = status?.long("started")?.takeIf { it > 0 } ?: k["start"]?.trim()?.toLongOrNull() ?: 0
        val ended = status?.long("ended")?.takeIf { it > 0 } ?: k["end"]?.trim()?.toLongOrNull() ?: 0
        val state = when (kind) {
            QuickKind.Script -> when {
                exitText == "stopped" -> RunState.Stopped
                exitText.toIntOrNull() == 0 -> RunState.Done
                exitText.toIntOrNull() != null -> RunState.Failed
                alive -> if (started > 0) RunState.Running else RunState.Starting
                else -> RunState.Lost
            }
            QuickKind.Prompt -> when (status?.str("state")) {
                "done" -> RunState.Done
                "failed" -> RunState.Failed
                "stopped" -> RunState.Stopped
                "limit" -> RunState.Limit
                "setup" -> if (alive) RunState.Setup else RunState.Lost
                "running" -> if (alive) RunState.Running else RunState.Lost
                else -> if (alive) RunState.Starting else RunState.Lost
            }
        }
        val preview = (k["head"] ?: k["tail"])?.let { b64(it) }.orEmpty()
        return QuickRun(
            id = id,
            name = meta.str("name").ifEmpty { QuickHeader.defaultName(meta.str("path").substringAfterLast('/')) },
            kind = kind,
            state = state,
            icon = meta.str("icon"),
            path = meta.str("path"),
            cwd = meta.str("cwd"),
            repo = meta.str("repo"),
            args = meta.str("args"),
            model = status?.str("model")?.ifEmpty { null } ?: meta.str("model"),
            scope = if (meta.str("scope") == "project") QuickScope.Project else QuickScope.Global,
            exit = exitText.toIntOrNull(),
            started = started,
            ended = ended,
            activity = status?.str("activity").orEmpty(),
            tokens = status?.long("tokens") ?: 0,
            error = status?.str("error").orEmpty(),
            size = k["size"]?.trim()?.toLongOrNull() ?: 0,
            preview = previewLine(preview, fromEnd = k["head"] == null),
        )
    }

    /** One line of what it said: the first that says something (a prompt's), or the last (a script's). */
    private fun previewLine(text: String, fromEnd: Boolean): String {
        val lines = text.split('\n').map { it.trim().trimStart('#', '*', '-', '>', ' ').trimEnd('*').trim() }.filter { it.isNotEmpty() && it != "```" }
        return (if (fromEnd) lines.lastOrNull() else lines.firstOrNull()).orEmpty().take(200)
    }

    private fun b64(s: String): String =
        runCatching { String(Base64.getMimeDecoder().decode(s.trim()), Charsets.UTF_8) }.getOrDefault("").replace("�", "")

    private fun obj(s: String): JsonObject? = runCatching { json.parseToJsonElement(s.trim()).jsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0

    /** A run's `meta.json`: what it is, for the runs list and to run it again. */
    fun meta(command: QuickCommand, ctx: QuickContext, args: String, at: Long): String = buildJsonObject {
        put("name", command.name)
        put("kind", if (command.kind == QuickKind.Prompt) "prompt" else "script")
        put("icon", command.icon)
        put("scope", if (command.scope == QuickScope.Project) "project" else "global")
        put("path", command.path)
        put("cwd", ctx.cwd)
        put("repo", ctx.repo)
        put("args", args)
        if (command.model.isNotEmpty()) put("model", command.model)
        put("at", at)
    }.toString()

    /** A prompt run's `request.json`, which the worker reads. */
    fun request(command: QuickCommand, prompt: String, cwd: String): String = buildJsonObject {
        put("prompt", prompt)
        if (command.model.isNotEmpty()) put("model", command.model)
        put("tools", kotlinx.serialization.json.JsonArray(command.tools.ifEmpty { listOf("read-only") }.map { JsonPrimitive(it) }))
        put("cwd", cwd)
        command.timeoutMinutes?.let { put("timeoutSeconds", it * 60) }
        command.maxTokens?.let { put("maxTokens", it) }
    }.toString()
}
