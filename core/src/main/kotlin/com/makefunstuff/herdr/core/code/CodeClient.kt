package com.makefunstuff.herdr.core.code

import com.makefunstuff.herdr.core.ssh.CommandResult
import java.io.IOException

/**
 * A machine's code over its SSH connection: [run] executes one remote shell command, with a timeout
 * in seconds. Everything here only reads (see [CodeCommands]).
 */
class CodeClient(private val run: suspend (command: String, timeoutSeconds: Long) -> CommandResult) {
    suspend fun discover(folders: List<String>): Discovery =
        if (folders.isEmpty()) Discovery(emptyList(), emptyMap()) else CodeReader.discovery(out(CodeCommands.discover(folders.distinct()), 40))

    suspend fun info(folder: String, base: String = ""): CheckoutInfo = CodeReader.info(out(CodeCommands.info(folder, base), 20))

    suspend fun files(folder: String): List<String> = CodeReader.files(out(CodeCommands.files(folder), 60))

    suspend fun read(folder: String, path: String): FileText = CodeReader.file(out(CodeCommands.read(folder, path), 60, quiet = true))

    suspend fun search(folder: String, query: String, regex: Boolean = false, matchCase: Boolean = false): List<SearchHit> =
        CodeReader.search(out(CodeCommands.search(folder, query, regex, matchCase), 60, quiet = true))

    suspend fun status(folder: String): GitStatus = CodeReader.status(out(CodeCommands.status(folder), 30))

    suspend fun changes(folder: String, base: String, scope: DiffScope): ChangeSet =
        CodeReader.changes(out(CodeCommands.diff(folder, base, scope), 90))

    suspend fun log(folder: String, base: String): List<Commit> = CodeReader.log(out(CodeCommands.log(folder, base), 30))

    suspend fun show(folder: String, sha: String): CommitDiff = CodeReader.show(out(CodeCommands.show(folder, sha), 60))

    /**
     * What the command printed. One that timed out is an error; so is one that printed nothing but
     * a complaint, unless it is [quiet] (a search that finds nothing, a file that is empty).
     */
    private suspend fun out(command: String, timeout: Long, quiet: Boolean = false): String {
        val r = run(command, timeout)
        val why = r.stderr.trim().lines().lastOrNull { it.isNotBlank() }?.removePrefix("fatal: ")?.removePrefix("error: ")
        if (r.exitStatus == null && r.stdout.isEmpty()) throw IOException("The machine took too long to answer")
        if (r.stdout.isBlank() && why != null && !quiet) throw IOException(why)
        return r.stdout
    }
}
