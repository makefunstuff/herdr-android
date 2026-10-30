package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** One herdr-core executable found on a machine. */
data class HcBinary(val path: String, val version: String?, val source: String)

/** What [HcDetection.probe] found: the executables, and the running core's own (null: none runs). */
data class HcProbe(val binaries: List<HcBinary>, val running: Boolean, val coreExe: String?) {
    /** The executable to run `connect` with: the running core's own build, else the first found. */
    val binary: HcBinary? get() = coreExe?.let { exe -> binaries.firstOrNull { it.path == exe } } ?: binaries.firstOrNull()
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
 * Only a core that already runs counts: `hc connect` starts one when none is listening, and the
 * app mustn't start a runtime on a machine whose owner doesn't use it. So the probe looks for the
 * `herdr-core serve` process (or, without `pgrep`, its socket) before anything connects.
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
     * Prints `core<TAB>exe<TAB>pid` for each running `herdr-core serve` (`hc serve`), `socket<TAB>path`
     * where there's no `pgrep` and a core's socket exists, then `bin<TAB>path<TAB>--version output`
     * for each herdr-core executable, the running core's first. Only `herdr-core` by that name: an
     * `hc` alone may be another program (herbstluftwm ships one).
     */
    val probe: String = "sh -c ${quote(PROBE)} hc"

    private val PROBE: String
        get() = "seen=' '; " +
            "emit() { [ -n \"\$2\" ] && [ -f \"\$2\" ] && [ -x \"\$2\" ] || return 0; " +
            "case \"\$seen\" in *\" \$2 \"*) return 0;; esac; seen=\"\$seen\$2 \"; " +
            "v=\"\$(\"\$2\" --version </dev/null 2>/dev/null | head -n 1)\"; printf 'bin\\t%s\\t%s\\t%s\\n' \"\$2\" \"\$v\" \"\$1\"; }; " +
            "exes=''; " +
            "if command -v pgrep >/dev/null 2>&1; then " +
            "for p in \$(pgrep -u \"\$(id -u)\" -f '^(.*/)?([h]erdr-core|[h]c) serve( |$)' 2>/dev/null); do " +
            "if [ -d /proc/\$p ]; then e=\"\$(readlink /proc/\$p/exe 2>/dev/null)\"; e=\"\${e% (deleted)}\"; " +
            "else e=\"\$(lsof -a -p \$p -d txt -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)\"; fi; " +
            "printf 'core\\t%s\\t%s\\n' \"\$e\" \"\$p\"; exes=\"\$exes \$e\"; done; " +
            "else " +
            "for s in \"\${XDG_RUNTIME_DIR:-/nonexistent}/herdr-core/default.sock\" \"/run/user/\$(id -u)/herdr-core/default.sock\" " +
            "\"\${XDG_STATE_HOME:-\$HOME/.local/state}/herdr-core/default/default.sock\"; do " +
            "[ -S \"\$s\" ] && printf 'socket\\t%s\\n' \"\$s\"; done; fi; " +
            "for e in \$exes; do emit core \"\$e\"; done; " +
            "for c in ${SEARCH.joinToString(" ")}; do emit path \"\$c\"; done; " +
            "emit login \"\$(\"\${SHELL:-/bin/sh}\" -lc 'command -v herdr-core' </dev/null 2>/dev/null | tail -n 1)\"; exit 0"

    private val VERSION = Regex("""\d+\.\d+\.\d+[0-9A-Za-z.+-]*""")

    /** Reads [probe]'s output; shell noise around it is skipped. */
    fun parse(out: String): HcProbe {
        val rows = out.lines().map { it.split('\t') }
        val cores = rows.filter { it.size >= 2 && it[0] == "core" }
        val sockets = rows.filter { it.size >= 2 && it[0] == "socket" && it[1].startsWith("/") }
        val binaries = rows.filter { it.size >= 4 && it[0] == "bin" && it[1].startsWith("/") }
            .map { HcBinary(it[1], VERSION.find(it[2])?.value, it[3]) }
            .distinctBy { it.path }
        return HcProbe(binaries, running = cores.isNotEmpty() || sockets.isNotEmpty(), coreExe = cores.firstOrNull()?.get(1)?.takeIf { it.startsWith("/") })
    }

    /** `<binary> connect`: the core's API on the channel's stdin/stdout. */
    fun connectCommand(binary: HcBinary): String = "${quote(binary.path)} connect"

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
        val binary = probe.binary ?: return null
        val process = conn.start(connectCommand(binary))
        return HerdrCore(binary, HcConnection.open(process, scope))
    }
}
