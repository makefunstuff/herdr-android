package com.makefunstuff.herdr.core.ports

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import java.io.IOException

/**
 * A TCP port something listens on, on a machine: [address] as the listener gave it (`127.0.0.1`,
 * `0.0.0.0`, `::`, `*`), and when it is the account's own process, its [pid], name ([comm]), command
 * line, the herdr workspace and pane it was started from and its working folder. [web]: it answered
 * an HTTP request; null when the machine couldn't ask (no curl).
 */
data class ListeningPort(
    val port: Int,
    val address: String,
    val pid: Int? = null,
    val comm: String = "",
    val args: String = "",
    val workspaceId: String? = null,
    val paneId: String? = null,
    val cwd: String? = null,
    val web: Boolean? = null,
) {
    /** What it is, in a word: `vite`, `uvicorn`, `storybook`, or the program's name. */
    val name: String get() = DevServers.name(comm, args)

    /** Where on the machine to reach it: its own address, or the loopback one a wildcard listener has. */
    val connectHost: String
        get() = when (address) {
            "", "*", "0.0.0.0", "127.0.0.1" -> "127.0.0.1"
            "::", "::1" -> "::1"
            else -> address
        }

    /** Only on the machine itself (loopback), not on its network. */
    val local: Boolean get() = address.startsWith("127.") || address == "::1"
}

/**
 * The dev servers of a machine: what listens on its TCP ports, read with `ss` (Linux; `lsof` on
 * macOS) over the app's SSH connection, the processes tied to herdr's workspaces by the
 * `HERDR_WORKSPACE_ID` they inherit, as the health probe ties them.
 */
object DevServers {
    const val MARK = "@@herdr-ports"

    /** The command that lists the ports; [read] reads what it prints. */
    fun command(): String = "sh -c ${quote(SCRIPT)} herdr-ports"

    // One line a port: `L port address pid comm workspace pane cwd http args`, tab-separated, then the
    // HTTP check for each of the account's own ports (curl, one second each).
    private val SCRIPT = """
        LC_ALL=C; export LC_ALL
        echo "$MARK 1"
        list() {
          if command -v ss >/dev/null 2>&1; then
            ss -ltnp 2>/dev/null | awk 'NR > 1 {
              a = ${'$'}4; i = match(a, /:[0-9]+${'$'}/); if (!i) next
              port = substr(a, i + 1); addr = substr(a, 1, i - 1); gsub(/[][]/, "", addr); sub(/%.*/, "", addr)
              pid = ""; comm = ""
              if (match(${'$'}0, /pid=[0-9]+/)) pid = substr(${'$'}0, RSTART + 4, RLENGTH - 4)
              if (match(${'$'}0, /\(\("[^"]*"/)) comm = substr(${'$'}0, RSTART + 3, RLENGTH - 4)
              print port "\t" addr "\t" pid "\t" comm
            }'
          elif command -v lsof >/dev/null 2>&1; then
            lsof -nP -iTCP -sTCP:LISTEN -Fpcn 2>/dev/null | awk '
              /^p/ { pid = substr(${'$'}0, 2) } /^c/ { comm = substr(${'$'}0, 2) }
              /^n/ { a = substr(${'$'}0, 2); i = match(a, /:[0-9]+${'$'}/); if (!i) next
                     addr = substr(a, 1, i - 1); gsub(/[][]/, "", addr); print substr(a, i + 1) "\t" addr "\t" pid "\t" comm }'
          fi
        }
        list | sort -u | while IFS='	' read -r port addr pid comm; do
          ws=""; pane=""; cwd=""; args=""; http=""
          if [ -n "${'$'}pid" ]; then
            if [ -r "/proc/${'$'}pid/environ" ]; then
              ws="${'$'}(tr '\000' '\n' < "/proc/${'$'}pid/environ" 2>/dev/null | sed -n 's/^HERDR_WORKSPACE_ID=//p' | head -n 1)"
              pane="${'$'}(tr '\000' '\n' < "/proc/${'$'}pid/environ" 2>/dev/null | sed -n 's/^HERDR_PANE_ID=//p' | head -n 1)"
              cwd="${'$'}(readlink "/proc/${'$'}pid/cwd" 2>/dev/null)"
            fi
            args="${'$'}(ps -o args= -p "${'$'}pid" 2>/dev/null | tr '\t' ' ' | cut -c1-300)"
            if command -v curl >/dev/null 2>&1; then
              case "${'$'}addr" in ""|"*"|0.0.0.0|127.0.0.1) h=127.0.0.1 ;; ::|::1) h="[::1]" ;; *:*) h="[${'$'}addr]" ;; *) h="${'$'}addr" ;; esac
              code="${'$'}(curl -s -o /dev/null -m 1 -w '%{http_code}' "http://${'$'}h:${'$'}port/" 2>/dev/null)"
              case "${'$'}code" in ""|000) http=0 ;; *) http=1 ;; esac
            fi
          fi
          printf 'L\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}port" "${'$'}addr" "${'$'}pid" "${'$'}comm" "${'$'}ws" "${'$'}pane" "${'$'}cwd" "${'$'}http" "${'$'}args"
        done
        exit 0
    """.trimIndent()

    /** The ports in what [command] printed, one each (a port on IPv4 and IPv6 is one), by number. */
    fun read(output: String): List<ListeningPort> {
        val all = output.lineSequence().filter { it.startsWith("L\t") }.mapNotNull { line ->
            val f = line.split('\t')
            fun at(i: Int) = f.getOrElse(i) { "" }
            val port = at(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            ListeningPort(
                port = port,
                // A socket of both families bound to 127.0.0.1 shows as ::ffff:127.0.0.1.
                address = at(2).removePrefix("::ffff:"),
                pid = at(3).toIntOrNull(),
                comm = at(4),
                workspaceId = at(5).ifBlank { null },
                paneId = at(6).ifBlank { null },
                cwd = at(7).ifBlank { null },
                web = when (at(8)) { "1" -> true; "0" -> false; else -> null },
                args = f.drop(9).joinToString("\t").trim(),
            )
        }.toList()
        // The same port twice (IPv4 and IPv6, or two workers): the one that says the most.
        return all.groupBy { it.port }.map { (_, same) ->
            same.maxWith(compareBy<ListeningPort>({ it.pid != null }, { it.workspaceId != null }, { it.web == true }, { !it.address.contains(':') }))
        }.sortedBy { it.port }
    }

    /** Runs [command] with [run] and reads it. */
    suspend fun list(run: suspend (command: String, timeoutSeconds: Long) -> CommandResult): List<ListeningPort> {
        val r = run(command(), 60)
        if (MARK !in r.stdout) {
            throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "The machine didn't say what listens on its ports")
        }
        return read(r.stdout)
    }

    /**
     * The ports of a workspace: its processes' (by the workspace they were started from), else the
     * account's own that run in one of its [folders]. Ports that don't answer HTTP go last.
     */
    fun inWorkspace(ports: List<ListeningPort>, workspaceId: String, folders: List<String> = emptyList()): List<ListeningPort> {
        val dirs = folders.filter { it.isNotBlank() }.map { it.trimEnd('/') }
        return ports.filter { p ->
            p.workspaceId == workspaceId ||
                (p.workspaceId == null && p.cwd != null && dirs.any { d -> p.cwd == d || p.cwd.startsWith("$d/") })
        }.let(::webFirst)
    }

    /** The account's own ports anywhere on the machine (with a process it can see). */
    fun own(ports: List<ListeningPort>): List<ListeningPort> = webFirst(ports.filter { it.pid != null })

    private fun webFirst(ports: List<ListeningPort>) = ports.sortedWith(compareBy({ it.web == false }, { it.port }))

    /** The name a server goes by, from its program and command line. */
    fun name(comm: String, args: String): String {
        val a = args.lowercase()
        KNOWN.firstOrNull { (pattern, _) -> pattern.containsMatchIn(a) }?.let { return it.second }
        val program = comm.ifBlank { args.substringBefore(' ').substringAfterLast('/') }
        // A runtime running a script: the script's name says more ("server" for node server.js).
        if (program.lowercase().let { p -> RUNTIMES.any { p == it || p.startsWith("$it.") || p.matches(Regex("$it[0-9.]+")) } }) {
            val script = args.split(' ').drop(1).firstOrNull { it.isNotBlank() && !it.startsWith("-") }
            if (script != null) return script.substringAfterLast('/').substringBeforeLast('.').ifBlank { program }
        }
        return program.ifBlank { "server" }
    }

    private val RUNTIMES = listOf("node", "python", "ruby", "bun", "deno", "java", "php")

    private val KNOWN: List<Pair<Regex, String>> = listOf(
        Regex("storybook") to "storybook",
        Regex("\\bvite(\\b|st)") to "vite",
        Regex("\\bnext(-server|\\.js|/dist| dev| start)") to "next",
        Regex("\\bnuxt") to "nuxt",
        Regex("\\bastro\\b") to "astro",
        Regex("svelte-kit") to "sveltekit",
        Regex("\\bremix\\b") to "remix",
        Regex("webpack") to "webpack",
        Regex("\\bparcel\\b") to "parcel",
        Regex("docusaurus") to "docusaurus",
        Regex("\\buvicorn\\b") to "uvicorn",
        Regex("\\bgunicorn\\b") to "gunicorn",
        Regex("\\bhypercorn\\b") to "hypercorn",
        Regex("\\bflask\\b") to "flask",
        Regex("manage\\.py runserver") to "django",
        Regex("\\bstreamlit\\b") to "streamlit",
        Regex("\\bjupyter") to "jupyter",
        Regex("http\\.server") to "http.server",
        Regex("\\bmkdocs\\b") to "mkdocs",
        Regex("\\bhugo\\b") to "hugo",
        Regex("\\bjekyll\\b") to "jekyll",
        Regex("\\b(rails|puma)\\b") to "rails",
        Regex("\\bphp\\b.* -s ") to "php",
        Regex("gradledaemon") to "gradle",
        Regex("kotlincompiledaemon") to "kotlin daemon",
    )
}
