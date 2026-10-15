package com.makefunstuff.herdr.core.ssh

import kotlinx.serialization.Serializable

/**
 * A herdr machine: an SSH target plus one herdr session, matching herdr's own saved machines.
 */
@Serializable
data class Machine(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val session: String = "default",
    /** SHA256 host key fingerprint pinned on first connect. */
    val hostKey: String? = null,
    /** Another machine to use as an SSH jump host, for hosts only reachable from it. */
    val viaMachineId: String? = null,
    /** Explicit herdr binary path on the host; empty means search the usual places. */
    val herdrPath: String? = null,
    val enabled: Boolean = true,
) {
    val target: String get() = if (port == 22) "$user@$host" else "$user@$host:$port"

    companion object {
        /** Parses `user@host`, `user@host:port`, or `ssh://user@host:port`. */
        fun parseTarget(raw: String): Triple<String?, String, Int>? {
            var t = raw.trim().removePrefix("ssh://").trimEnd('/')
            if (t.isEmpty()) return null
            var user: String? = null
            val at = t.lastIndexOf('@')
            if (at >= 0) {
                user = t.substring(0, at).ifEmpty { null }
                t = t.substring(at + 1)
            }
            var port = 22
            if (t.startsWith("[")) {
                val end = t.indexOf(']')
                if (end < 0) return null
                val host = t.substring(1, end)
                val rest = t.substring(end + 1)
                if (rest.startsWith(":")) port = rest.drop(1).toIntOrNull() ?: return null
                return Triple(user, host, port)
            }
            val colon = t.lastIndexOf(':')
            if (colon >= 0 && t.count { it == ':' } == 1) {
                port = t.substring(colon + 1).toIntOrNull() ?: return null
                t = t.substring(0, colon)
            }
            if (t.isEmpty()) return null
            return Triple(user, t, port)
        }
    }
}

/** Builds the remote shell commands the app runs on a machine. */
object HerdrCommands {
    private val SEARCH = listOf(
        "\"\$h\"",
        "\"\$(command -v herdr 2>/dev/null)\"",
        "\"\$HOME/.local/bin/herdr\"",
        "/opt/homebrew/bin/herdr",
        "/usr/local/bin/herdr",
        "\"\$HOME/.nix-profile/bin/herdr\"",
        "\"/etc/profiles/per-user/\$USER/bin/herdr\"",
        "/run/current-system/sw/bin/herdr",
        "\"\$HOME/.local/share/mise/shims/herdr\"",
        "/home/linuxbrew/.linuxbrew/bin/herdr",
    )

    const val NOT_FOUND_EXIT = 127
    const val NOT_FOUND_MESSAGE = "herdr was not found on this machine"

    private val SCRIPT = "h=\"\$1\"; shift; for c in ${SEARCH.joinToString(" ")}; do " +
        "if [ -n \"\$c\" ] && [ -x \"\$c\" ]; then exec \"\$c\" \"\$@\"; fi; done; " +
        "echo '$NOT_FOUND_MESSAGE' >&2; exit $NOT_FOUND_EXIT"

    /** Runs herdr with [args], finding the binary even when the SSH shell has a minimal PATH. */
    fun herdr(machine: Machine, vararg args: String): String =
        "sh -c ${quote(SCRIPT)} herdr ${quote(machine.herdrPath ?: "")} " +
            (listOf("--session", machine.session) + args).joinToString(" ") { quote(it) }

    /** Like [herdr] but without selecting a session (for commands such as `session list`). */
    fun herdrNoSession(machine: Machine, vararg args: String): String =
        "sh -c ${quote(SCRIPT)} herdr ${quote(machine.herdrPath ?: "")} " + args.joinToString(" ") { quote(it) }

    fun apiBridge(machine: Machine) = herdr(machine, "remote-api-bridge")
    fun apiBridgeCheck(machine: Machine) = herdr(machine, "remote-api-bridge", "--check")
    fun sessionList(machine: Machine) = herdrNoSession(machine, "session", "list", "--json")
    fun machineList(machine: Machine) = herdrNoSession(machine, "machine", "list", "--json")

    fun terminalControl(machine: Machine, target: String, cols: Int, rows: Int, takeover: Boolean = true): String {
        val args = mutableListOf("terminal", "session", "control", target)
        if (takeover) args += "--takeover"
        args += listOf("--cols", cols.toString(), "--rows", rows.toString())
        return herdr(machine, *args.toTypedArray())
    }

    /** `ssh -G` prints the effective config for an alias, so the phone can resolve ~/.ssh/config names. */
    fun resolveSshAlias(alias: String) = "ssh -G ${quote(alias)}"

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
