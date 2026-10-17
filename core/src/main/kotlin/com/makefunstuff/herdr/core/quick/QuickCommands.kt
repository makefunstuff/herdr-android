package com.makefunstuff.herdr.core.quick

import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote

/**
 * The shell commands behind quick commands, run over the app's SSH connection: find the commands
 * of a folder's repository and of the machine, start one detached from the connection, read how
 * its runs went, stop one.
 *
 * A run lives in its own folder, `~/.local/state/herdr-android/quick/<id>/`, so it carries on when
 * the phone goes away and the app reads it again later: `meta.json` (what it is, written by the
 * app), and for a script `out` (what it printed), `pid`, `start`, `end` and `exit` (its status, or
 * `stopped`); for a prompt `request.json`, `status.json` and `answer.md` (host/herdr-quick).
 *
 * Each runs as `sh -c <script> herdr-quick-<op> <args>`, so `$0` names what it does (the previews'
 * fake machine answers by that name). A path may start with `~/`.
 */
object QuickCommands {
    const val MARK = "@@herdr-quick"

    /** Lines of a script read for its header; a prompt is read whole, up to [PROMPT_LIMIT] bytes. */
    const val HEADER_LINES = 60
    const val PROMPT_LIMIT = 32 * 1024

    /** What one read of a run's output brings at most; the next read goes on from there. */
    const val READ_LIMIT = 512 * 1024

    /** Runs kept on the machine; older finished ones go as new ones start. */
    const val KEEP = 80

    private const val PRELUDE = "R=\"\${XDG_STATE_HOME:-\$HOME/.local/state}/herdr-android/quick\"; Q=\"\$HOME/.local/share/herdr-android/quick\"; " +
        "home() { case \"\$1\" in '~') printf '%s' \"\$HOME\";; '~/'*) printf '%s/%s' \"\$HOME\" \"\${1#??}\";; *) printf '%s' \"\$1\";; esac; }; " +
        "valid() { case \"\$1\" in ''|*[!A-Za-z0-9_-]*) return 1;; esac; }; " +
        // Whether a run's process still runs: its pid, and it is still ours (not a pid used again since).
        "alive() { p=\$(cat \"\$1/pid\" 2>/dev/null); [ -n \"\$p\" ] && ps -p \"\$p\" -o args= 2>/dev/null | grep -q herdr-quick; }; " +
        "ended() { [ -f \"\$1/end\" ] || grep -q '\"ended\"' \"\$1/status.json\" 2>/dev/null; }; "

    /** Old finished runs go, so the folder keeps the newest [KEEP]. */
    private const val PRUNE = "ls -1 \"\$R\" 2>/dev/null | sort -r | tail -n +${KEEP + 1} | while IFS= read -r x; do " +
        "valid \"\$x\" && ended \"\$R/\$x\" && rm -rf \"\${R:?}/\$x\"; done; "

    /** Starts `sh -c "$J" <name> <args>` in its own session, cut off from the SSH connection. */
    private const val DETACH = "if command -v setsid >/dev/null 2>&1; then setsid sh -c \"\$J\" \"\$@\" </dev/null >/dev/null 2>&1 & " +
        "else nohup sh -c \"\$J\" \"\$@\" </dev/null >/dev/null 2>&1 & fi; "

    private fun script(op: String, body: String, vararg args: String): String =
        "sh -c ${quote(PRELUDE + body)} herdr-quick-$op " + args.joinToString(" ") { quote(it) }

    /**
     * The commands for [cwd] (empty: only the machine's). First `@@repo <top> <main checkout> <branch>`
     * when it is in Git (or `@@repo <folder>` when not); then for each folder with commands (the
     * checkout's `.herdr/commands`, its main checkout's, the machine's `~/.config/herdr/commands`)
     * `@@dir <scope> <folder>`, and per command `@@file <scope> <path>`, the top of the file and
     * `@@herdr-quick end`. Last, `@@home <home folder>`.
     */
    fun list(cwd: String): String = script(
        "list",
        "G=\"\${XDG_CONFIG_HOME:-\$HOME/.config}/herdr/commands\"; " +
            "show() { [ -d \"\$2\" ] || return 0; printf '@@dir\\t%s\\t%s\\n' \"\$1\" \"\$2\"; for f in \"\$2\"/*; do [ -f \"\$f\" ] || continue; " +
            "case \"\$f\" in *.prompt.md) printf '@@file\\t%s\\t%s\\n' \"\$1\" \"\$f\"; head -c $PROMPT_LIMIT \"\$f\";; " +
            "*~|*.bak|*.swp|*.orig|*.md|*.txt) continue;; " +
            "*) [ -x \"\$f\" ] || continue; printf '@@file\\t%s\\t%s\\n' \"\$1\" \"\$f\"; head -n $HEADER_LINES \"\$f\" | cut -c1-400;; esac; " +
            "printf '\\n$MARK end\\n'; done; }; " +
            "c=\$(home \"\$1\"); if [ -n \"\$c\" ] && [ -d \"\$c\" ]; then " +
            "t=\$(git -C \"\$c\" rev-parse --show-toplevel 2>/dev/null); " +
            "if [ -n \"\$t\" ]; then b=\$(git -C \"\$t\" rev-parse --abbrev-ref HEAD 2>/dev/null); " +
            "m=\$(cd \"\$t\" && cd \"\$(git rev-parse --git-common-dir 2>/dev/null)/..\" 2>/dev/null && pwd -P); " +
            "printf '@@repo\\t%s\\t%s\\t%s\\n' \"\$t\" \"\$m\" \"\$b\"; show project \"\$t/.herdr/commands\"; " +
            "if [ -n \"\$m\" ] && [ \"\$m\" != \"\$t\" ]; then show project \"\$m/.herdr/commands\"; fi; " +
            "else printf '@@repo\\t%s\\t\\t\\n' \"\$c\"; show project \"\$c/.herdr/commands\"; fi; fi; " +
            "show global \"\$G\"; printf '@@home\\t%s\\n' \"\$HOME\"; exit 0",
        cwd,
    )

    /**
     * Runs the script [path] in [cwd] as run [id], with [words] as its arguments: detached, its
     * output into the run's `out`. [meta] is the run's `meta.json`. The script also gets
     * `HERDR_REPO`, `HERDR_CWD`, `HERDR_ARGS` (what was typed, as it was) and `HERDR_COMMAND`.
     * Prints `@@started <id>`.
     */
    fun start(id: String, cwd: String, path: String, meta: String, repo: String, args: String, words: List<String>): String = script(
        "start",
        "valid \"\$1\" || { echo 'Not a run id' >&2; exit 2; }; id=\$1; d=\"\$R/\$1\"; mkdir -p \"\$d\" || exit 1; " +
            "printf '%s\\n' \"\$4\" > \"\$d/meta.json\"; c=\$(home \"\$2\"); f=\$(home \"\$3\"); " +
            "export HERDR_REPO=\"\$5\" HERDR_ARGS=\"\$6\" HERDR_CWD=\"\$c\" HERDR_COMMAND=\"\$f\"; shift 6; " +
            "cd \"\$c\" 2>/dev/null || cd \"\$HOME\"; $PRUNE" +
            "J='d=\$1; shift; echo \$\$ > \"\$d/pid\"; date +%s > \"\$d/start\"; \"\$@\" </dev/null >\"\$d/out\" 2>&1; s=\$?; " +
            "date +%s > \"\$d/end\"; echo \$s > \"\$d/exit\"'; " +
            "set -- herdr-quick-job \"\$d\" \"\$f\" \"\$@\"; $DETACH" +
            "echo \$! > \"\$d/pid\"; echo \"@@started \$id\"",
        id, cwd, path, meta, repo, args, *words.toTypedArray(),
    )

    /**
     * Starts the AI worker (host/herdr-quick) for run [id] in [cwd]: stdin is the worker's script,
     * written to the machine when it changed, with [packageJson] beside it; the first run there
     * installs pi-durable with npm. [meta] and [request] are the run's `meta.json` and
     * `request.json`; empty ones start it again where it stopped ([resume]). Prints `@@started <id>`.
     */
    fun ask(id: String, cwd: String, meta: String, request: String, packageJson: String): String = script(
        "ask",
        "valid \"\$1\" || { echo 'Not a run id' >&2; exit 2; }; id=\$1; d=\"\$R/\$1\"; mkdir -p \"\$d\" \"\$Q\" || exit 1; " +
            "same() { cmp -s \"\$1.new\" \"\$1\" && rm -f \"\$1.new\" || mv -f \"\$1.new\" \"\$1\"; }; " +
            "cat > \"\$Q/herdr-quick.mjs.new\" && same \"\$Q/herdr-quick.mjs\"; printf '%s\\n' \"\$5\" > \"\$Q/package.json.new\" && same \"\$Q/package.json\"; " +
            "if [ -n \"\$4\" ]; then printf '%s\\n' \"\$3\" > \"\$d/meta.json\"; printf '%s\\n' \"\$4\" > \"\$d/request.json\"; fi; " +
            "[ -f \"\$d/request.json\" ] || { echo 'There is no such run' >&2; exit 3; }; " +
            "if alive \"\$d\"; then echo \"@@started \$id\"; exit 0; fi; rm -f \"\$d/stop\"; " +
            "c=\$(home \"\$2\"); cd \"\$c\" 2>/dev/null || cd \"\$HOME\"; $PRUNE" +
            "n=\$(command -v node 2>/dev/null); [ -n \"\$n\" ] || n=\$(\"\${SHELL:-/bin/sh}\" -lc 'command -v node' </dev/null 2>/dev/null | tail -n 1); " +
            "for x in \"\$HOME/.local/share/mise/shims/node\" \"\$HOME/.volta/bin/node\" /opt/homebrew/bin/node /usr/local/bin/node; do " +
            "[ -n \"\$n\" ] && break; [ -x \"\$x\" ] && n=\$x; done; " +
            "J=${quote(WORKER_JOB)}; set -- herdr-quick-worker \"\$d\" \"\$Q\" \"\$n\"; $DETACH" +
            "echo \$! > \"\$d/pid\"; echo \"@@started \$id\"",
        id, cwd, meta, request, packageJson,
    )

    /** Starts a prompt's worker again where it stopped (the machine restarted, or the worker died). */
    fun resume(id: String, cwd: String, packageJson: String): String = ask(id, cwd, "", "", packageJson)

    /**
     * The worker's job, detached: install pi-durable the first time (or when its package.json
     * changed), then run the worker; a worker that failed before it said so is marked failed, and
     * one that was killed is left to carry on ([resume]).
     */
    private val WORKER_JOB = "d=\$1; q=\$2; n=\$3; echo \$\$ > \"\$d/pid\"; t=\$(date +%s); " +
        "esc() { printf '%s' \"\$1\" | tr '\\n\\t\\r' '   ' | sed 's/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g' | cut -c1-400; }; " +
        "say() { printf '{\"state\":\"%s\",\"error\":\"%s\",\"started\":%s,\"updated\":%s%s}\\n' \"\$1\" \"\$(esc \"\$2\")\" \"\$t\" \"\$(date +%s)\" \"\$3\" > \"\$d/status.json\"; }; " +
        "fail() { say failed \"\$1\" \",\\\"ended\\\":\$(date +%s)\"; exit 0; }; " +
        "[ -n \"\$n\" ] || fail 'Node.js is not installed on this machine: AI quick commands need Node 22 or newer'; " +
        "\"\$n\" -e 'process.exit(+process.versions.node.split(\".\")[0] >= 22 ? 0 : 1)' 2>/dev/null || " +
        "fail \"Node.js \$(\"\$n\" --version 2>/dev/null) is too old here: AI quick commands need Node 22 or newer\"; " +
        "if [ ! -f \"\$q/node_modules/.herdr-quick\" ] || [ \"\$q/package.json\" -nt \"\$q/node_modules/.herdr-quick\" ]; then " +
        "say setup '' ',\"activity\":\"Installing pi-durable\"'; i=0; while ! mkdir \"\$q/.installing\" 2>/dev/null; do i=\$((i+1)); [ \$i -gt 300 ] && break; sleep 1; done; " +
        "if [ ! -f \"\$q/node_modules/.herdr-quick\" ] || [ \"\$q/package.json\" -nt \"\$q/node_modules/.herdr-quick\" ]; then " +
        "m=\"\$(dirname \"\$n\")/npm\"; [ -x \"\$m\" ] || m=\$(command -v npm); " +
        "if (cd \"\$q\" && PATH=\"\$(dirname \"\$n\"):\$PATH\" \"\$m\" install --no-audit --no-fund --omit=dev --loglevel=error) > \"\$d/setup.log\" 2>&1; " +
        "then : > \"\$q/node_modules/.herdr-quick\"; else rmdir \"\$q/.installing\" 2>/dev/null; " +
        "fail \"Couldn't install pi-durable with npm: \$(tail -n 1 \"\$d/setup.log\")\"; fi; fi; rmdir \"\$q/.installing\" 2>/dev/null; fi; " +
        "\"\$n\" --no-warnings \"\$q/herdr-quick.mjs\" run \"\$d\" >> \"\$d/worker.log\" 2>&1; s=\$?; " +
        // Killed (the OOM killer, a shutdown): left as it was, to carry on. Failed by itself without saying why: said here.
        "[ \$s -gt 128 ] && exit 0; grep -q '\"ended\"' \"\$d/status.json\" 2>/dev/null || fail \"The worker stopped: \$(tail -n 1 \"\$d/worker.log\")\""

    /** One run's facts, as [runs] and [read] print them: `@@run <id>`, then `key<TAB>value` lines. */
    private const val INFO = "info() { printf '@@run\\t%s\\n' \"\$2\"; printf 'meta\\t'; head -c 8192 \"\$1/meta.json\" | tr -d '\\n\\r'; echo; " +
        "if [ -f \"\$1/status.json\" ]; then printf 'status\\t'; head -c 8192 \"\$1/status.json\" | tr -d '\\n\\r'; echo; fi; " +
        "for k in exit start end; do [ -f \"\$1/\$k\" ] && printf '%s\\t%s\\n' \"\$k\" \"\$(head -c 40 \"\$1/\$k\" | tr -d '\\n\\r')\"; done; " +
        "alive \"\$1\" && printf 'alive\\t1\\n'; [ -f \"\$1/out\" ] && printf 'size\\t%s\\n' \"\$(wc -c < \"\$1/out\" | tr -d ' ')\"; " +
        "if [ -f \"\$1/answer.md\" ]; then printf 'head\\t%s\\n' \"\$(head -c 300 \"\$1/answer.md\" | base64 | tr -d '\\n')\"; " +
        "elif [ -f \"\$1/out\" ]; then printf 'tail\\t%s\\n' \"\$(tail -c 300 \"\$1/out\" | base64 | tr -d '\\n')\"; fi; }; "

    /** The newest [limit] runs, newest first, after `@@now <the machine's clock>`. */
    fun runs(limit: Int = 40): String = script(
        "runs",
        INFO + "printf '@@now\\t%s\\n' \"\$(date +%s)\"; [ -d \"\$R\" ] || exit 0; " +
            "ls -1 \"\$R\" | sort -r | head -n \"\$1\" | while IFS= read -r id; do valid \"\$id\" || continue; " +
            "[ -f \"\$R/\$id/meta.json\" ] && info \"\$R/\$id\" \"\$id\"; done; exit 0",
        limit.toString(),
    )

    /**
     * Run [id] ([runs]' lines, or `@@gone`), then `@@out` and what it printed from byte [offset]
     * on (at most [READ_LIMIT]); for a prompt, its answer so far, whole.
     */
    fun read(id: String, offset: Long): String = script(
        "read",
        INFO + "valid \"\$1\" || exit 2; d=\"\$R/\$1\"; printf '@@now\\t%s\\n' \"\$(date +%s)\"; " +
            "[ -f \"\$d/meta.json\" ] || { echo '@@gone'; exit 0; }; info \"\$d\" \"\$1\"; echo '@@out'; " +
            "if [ -f \"\$d/request.json\" ]; then head -c $READ_LIMIT \"\$d/answer.md\" 2>/dev/null; " +
            "else tail -c +\$((\$2 + 1)) \"\$d/out\" 2>/dev/null | head -c $READ_LIMIT; fi; exit 0",
        id, offset.toString(),
    )

    /**
     * Stops run [id]: SIGTERM to its process group (a prompt's worker ends the conversation, says
     * so and leaves), SIGKILL a few seconds later to what is left of a script. A script, and a
     * worker that isn't running any more, are marked stopped here.
     */
    fun stop(id: String): String = script(
        "stop",
        "valid \"\$1\" || exit 2; d=\"\$R/\$1\"; [ -d \"\$d\" ] || exit 0; : > \"\$d/stop\"; " +
            "if alive \"\$d\"; then up=1; kill -TERM -- \"-\$p\" 2>/dev/null || kill -TERM \"\$p\" 2>/dev/null; pkill -TERM -P \"\$p\" 2>/dev/null; " +
            "if [ ! -f \"\$d/request.json\" ]; then (sleep 4; kill -KILL -- \"-\$p\" 2>/dev/null) </dev/null >/dev/null 2>&1 & fi; fi; " +
            "if [ ! -f \"\$d/request.json\" ]; then [ -f \"\$d/exit\" ] || { echo stopped > \"\$d/exit\"; date +%s > \"\$d/end\"; }; " +
            "elif [ -z \"\$up\" ] && ! ended \"\$d\"; then printf '{\"state\":\"stopped\",\"ended\":%s,\"updated\":%s}\\n' \"\$(date +%s)\" \"\$(date +%s)\" > \"\$d/status.json\"; fi; " +
            "echo '@@stopped'",
        id,
    )

    /** Removes run [id] from the machine, unless it still runs. */
    fun forget(id: String): String = script(
        "forget",
        "valid \"\$1\" || exit 2; d=\"\$R/\$1\"; [ -d \"\$d\" ] || exit 0; " +
            "if alive \"\$d\"; then echo 'It is still running' >&2; exit 4; fi; rm -rf \"\${R:?}/\$1\"; echo '@@forgotten'",
        id,
    )
}
