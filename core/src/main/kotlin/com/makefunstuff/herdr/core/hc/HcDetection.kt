package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** One herdr-core executable found on a machine. */
data class HcBinary(val path: String, val version: String?, val source: String)

/**
 * What [HcDetection.probe] found: the default core's [socket] (null: none there), whether a core
 * process runs, the executables, and the running core's own.
 */
data class HcProbe(val socket: String?, val running: Boolean, val binaries: List<HcBinary>, val coreExes: List<String> = emptyList()) {
    /**
     * The executable to run `connect` with: a running core's own build that is also installed where
     * people install it (not a stray dev build), else any running core's, else the first found.
     */
    val binary: HcBinary?
        get() = binaries.firstOrNull { b -> b.path in coreExes && binaries.any { it.path == b.path && it.source != "core" } }
            ?: binaries.firstOrNull { it.path in coreExes }
            ?: binaries.firstOrNull()
}

/** herdr-core on a machine, connected: one `hc connect` the machine's screens share. */
class HerdrCore(val binary: HcBinary, val connection: HcConnection) {
    val client = HcClient(connection)
    val version: String get() = connection.hello.version
    val core: HcCore get() = connection.hello.core
}

/**
 * Finds herdr-core (`hc`, the Herdr desktop app's runtime) on a machine, next to classic herdr.
 *
 * Only a core that already runs counts: `hc connect` starts one when none listens on its socket,
 * and the app mustn't start a runtime on a machine whose owner doesn't use it. So the probe finds
 * the default session's socket the way hc does (the running core writes it to `core.lock` in its
 * state folder), checks that it exists and that a `herdr-core serve` runs, and `connect` is pinned
 * to that socket (`HC_SOCKET`). Left: a core that stops in the moment between the probe and
 * `connect`, or crashed leaving its socket while another session's core runs, gets a new one.
 */
object HcDetection {
    private val SEARCH = listOf(
        "\"\$HOME/.local/bin/herdr-core\"",
        "\"\$(command -v herdr-core 2>/dev/null)\"",
        "/opt/homebrew/bin/herdr-core",
        "/usr/local/bin/herdr-core",
        "/usr/bin/herdr-core",
        "\"\$HOME/.nix-profile/bin/herdr-core\"",
        "\"/etc/profiles/per-user/\$USER/bin/herdr-core\"",
        "/run/current-system/sw/bin/herdr-core",
        "\"\$HOME/.local/share/mise/shims/herdr-core\"",
        "/home/linuxbrew/.linuxbrew/bin/herdr-core",
    )

    /**
     * The default session's socket as hc finds it: `HC_SOCKET`, else what the running core wrote
     * in `core.lock`, else the runtime folder (`XDG_RUNTIME_DIR`, `/run/user/<uid>`), else the state
     * folder. Sets `s`.
     */
    private const val SOCKET = "sess=\"\${HC_SESSION:-default}\"; st=\"\${XDG_STATE_HOME:-\$HOME/.local/state}/herdr-core/\$sess\"; " +
        "if [ -n \"\$HC_SOCKET\" ]; then s=\"\$HC_SOCKET\"; " +
        "else s=\"\$(head -n 1 \"\$st/core.lock\" 2>/dev/null)\"; " +
        "if [ ! -S \"\$s\" ]; then " +
        "if [ -n \"\$XDG_RUNTIME_DIR\" ]; then s=\"\$XDG_RUNTIME_DIR/herdr-core/\$sess.sock\"; " +
        "elif [ -d \"/run/user/\$(id -u)\" ]; then s=\"/run/user/\$(id -u)/herdr-core/\$sess.sock\"; " +
        "else s=\"\$st/\$sess.sock\"; fi; fi; fi; "

    /**
     * Prints `socket<TAB>path` when the default core's socket exists (and nothing more when it
     * doesn't: no core, so no search), then `core<TAB>exe<TAB>pid` for each running
     * `herdr-core serve` (`hc serve`), or `nopgrep`, then `bin<TAB>path<TAB>--version<TAB>source`
     * for each herdr-core executable, the running cores' first; the login shell's PATH only when
     * nothing else had one. Only `herdr-core` by that name: an `hc` alone may be another program
     * (herbstluftwm ships one).
     */
    val probe: String get() = "sh -c ${quote(PROBE)} hc"

    private val PROBE: String
        get() = SOCKET +
            "[ -S \"\$s\" ] || exit 0; printf 'socket\\t%s\\n' \"\$s\"; " +
            "seen=' '; found=''; " +
            "emit() { [ -n \"\$2\" ] && [ -f \"\$2\" ] && [ -x \"\$2\" ] || return 0; " +
            "k=\"\$1:\$2\"; case \"\$seen\" in *\" \$k \"*) return 0;; esac; seen=\"\$seen\$k \"; found=1; " +
            "v=\"\$(\"\$2\" --version </dev/null 2>/dev/null | head -n 1)\"; printf 'bin\\t%s\\t%s\\t%s\\n' \"\$2\" \"\$v\" \"\$1\"; }; " +
            "exes=''; " +
            "if command -v pgrep >/dev/null 2>&1; then " +
            "for p in \$(pgrep -u \"\$(id -u)\" -f '^(.*/)?([h]erdr-core|[h]c) serve( |$)' 2>/dev/null); do " +
            "if [ -d /proc/\$p ]; then e=\"\$(readlink /proc/\$p/exe 2>/dev/null)\"; e=\"\${e% (deleted)}\"; " +
            "else e=\"\$(lsof -a -p \$p -d txt -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)\"; fi; " +
            "printf 'core\\t%s\\t%s\\n' \"\$e\" \"\$p\"; exes=\"\$exes \$e\"; done; " +
            "[ -n \"\$exes\" ] || exit 0; " +
            "else echo nopgrep; fi; " +
            "for e in \$exes; do emit core \"\$e\"; done; " +
            "for c in ${SEARCH.joinToString(" ")}; do emit path \"\$c\"; done; " +
            "[ -n \"\$found\" ] || emit login \"\$(\"\${SHELL:-/bin/sh}\" -lc 'command -v herdr-core' </dev/null 2>/dev/null | tail -n 1)\"; exit 0"

    private val VERSION = Regex("""\d+\.\d+\.\d+[0-9A-Za-z.+-]*""")

    /** Reads [probe]'s output; shell noise around it is skipped. */
    fun parse(out: String): HcProbe {
        val rows = out.lines().map { it.split('\t') }
        val socket = rows.firstOrNull { it.size >= 2 && it[0] == "socket" && it[1].startsWith("/") }?.get(1)
        // A process named like a core whose executable isn't one (`exec -a`) is never run.
        val cores = rows.filter { it.size >= 2 && it[0] == "core" }.map { it[1] }
            .filter { it.startsWith("/") && it.substringAfterLast('/').let { n -> n.startsWith("herdr-core") || n == "hc" } }
        val noPgrep = rows.any { it.singleOrNull() == "nopgrep" }
        val binaries = rows.filter { it.size >= 4 && it[0] == "bin" && it[1].startsWith("/") && (it[3] != "core" || it[1] in cores) }
            .map { HcBinary(it[1], VERSION.find(it[2])?.value, it[3]) }
        val coreRows = rows.count { it.size >= 2 && it[0] == "core" }
        return HcProbe(socket, running = socket != null && (coreRows > 0 || noPgrep), binaries = binaries.distinctBy { it.path to it.source }, coreExes = cores)
    }

    /** `<binary> connect` on [socket]: the core's API on the channel's stdin/stdout, never a new core elsewhere. */
    fun connectCommand(binary: HcBinary, socket: String): String =
        "sh -c ${quote("[ -S \"\$1\" ] || { echo 'herdr-core stopped' >&2; exit 3; }; HC_SOCKET=\"\$1\" exec \"\$2\" connect")} hc ${quote(socket)} ${quote(binary.path)}"

    /**
     * herdr-core on [conn]'s machine when one runs there, connected over one exec channel that the
     * caller keeps (one per machine) and closes with the SSH connection; null where none runs.
     */
    suspend fun open(conn: MachineConnection, scope: CoroutineScope): HerdrCore? {
        val probe = try {
            parse(conn.run(probe).stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!probe.running) return null
        val socket = probe.socket ?: return null
        val binary = probe.binary ?: return null
        val process = conn.start(connectCommand(binary, socket))
        return HerdrCore(binary, HcConnection.open(process, scope))
    }
}
