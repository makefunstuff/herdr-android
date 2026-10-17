package com.makefunstuff.herdr.core.quick

import com.makefunstuff.herdr.core.ssh.CommandResult
import java.io.IOException

/** The AI worker the app carries (core's resources, copied from host/herdr-quick) and puts on a machine as it runs a prompt. */
object QuickWorker {
    val script: String by lazy { resource("/herdr-quick/herdr-quick.mjs") }
    val packageJson: String by lazy { resource("/herdr-quick/package.json").trim() }

    private fun resource(name: String): String =
        QuickWorker::class.java.getResourceAsStream(name)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("$name isn't bundled")
}

/**
 * Quick commands on one machine, over its SSH connection: [run] executes a remote shell command
 * with a timeout in seconds, [runWithInput] one that reads [String] on its stdin.
 */
class QuickClient(
    private val run: suspend (command: String, timeoutSeconds: Long) -> CommandResult,
    private val runWithInput: suspend (command: String, input: String, timeoutSeconds: Long) -> CommandResult,
) {
    /** The commands for [cwd] (empty: the machine's alone), project ones first. */
    suspend fun list(cwd: String = ""): QuickListing = QuickReader.listing(out(QuickCommands.list(cwd), 30))

    /** Starts [command], a script, as run [id]; [args] is what was typed in its form. */
    suspend fun start(id: String, command: QuickCommand, ctx: QuickContext, args: String, at: Long) {
        val r = run(
            QuickCommands.start(id, ctx.cwd, command.path, QuickReader.meta(command, ctx, args, at), ctx.repo, args, QuickArgs.split(args)),
            30,
        )
        started(r, id)
    }

    /** Starts [command], a prompt, as run [id]: [prompt] is its body with the placeholders filled. */
    suspend fun ask(id: String, command: QuickCommand, ctx: QuickContext, args: String, prompt: String, at: Long) {
        val request = QuickReader.request(command, prompt, ctx.cwd)
        val r = runWithInput(QuickCommands.ask(id, ctx.cwd, QuickReader.meta(command, ctx, args, at), request, QuickWorker.packageJson), QuickWorker.script, 60)
        started(r, id)
    }

    /** Starts a prompt's worker again where it stopped. */
    suspend fun resume(run: QuickRun) {
        val r = runWithInput(QuickCommands.resume(run.id, run.cwd, QuickWorker.packageJson), QuickWorker.script, 60)
        started(r, run.id)
    }

    suspend fun runs(limit: Int = 40): RunList = QuickReader.runs(out(QuickCommands.runs(limit), 30))

    /** Run [id] and its output from byte [offset] (a prompt's answer whole); its run is null when it is gone. */
    suspend fun read(id: String, offset: Long = 0): RunRead = QuickReader.read(out(QuickCommands.read(id, offset), 30, quiet = true))

    suspend fun stop(id: String) {
        val r = run(QuickCommands.stop(id), 20)
        if (r.exitStatus != 0 || "@@stopped" !in r.stdout) throw IOException(why(r) ?: "Couldn't stop it")
    }

    suspend fun forget(id: String) {
        val r = run(QuickCommands.forget(id), 20)
        if (r.exitStatus != 0) throw IOException(why(r) ?: "Couldn't remove it")
    }

    private fun started(r: CommandResult, id: String) {
        if ("@@started $id" !in r.stdout) throw IOException(why(r) ?: "The machine didn't start it")
    }

    private fun why(r: CommandResult): String? = r.stderr.trim().lines().lastOrNull { it.isNotBlank() }

    private suspend fun out(command: String, timeout: Long, quiet: Boolean = false): String {
        val r = run(command, timeout)
        if (r.exitStatus == null && r.stdout.isEmpty()) throw IOException("The machine took too long to answer")
        if (r.stdout.isBlank() && !quiet) why(r)?.let { throw IOException(it) }
        return r.stdout
    }

    companion object {
        /** A new run's id: when it started (the phone's clock) and a few random letters, so they sort by time. */
        fun newId(nowMs: Long, random: Int = (0..0xffff).random()): String = "q$nowMs-${"%04x".format(random and 0xffff)}"
    }
}
