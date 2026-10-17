package com.makefunstuff.herdr.core.health

import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote

/**
 * The shell commands behind a machine's health screen, run over the app's SSH connection. POSIX sh,
 * awk and the usual tools only: nothing is installed on the machine.
 */
object HealthCommands {
    const val MARK = "@@herdr-health"

    /**
     * One look at the machine: memory, load, disks and the processes worth showing, with the CPU each
     * used over one second. On Linux the account's own processes also say where they run (their
     * working folder, the `PWD` they were started from, the herdr pane), and every process inside
     * one of [keep] (the worktrees of finished tasks, as `<repo>/<folder>` or the start of one, such as
     * `herdr-android/work-22-`) is listed however small. Everything is joined
     * on the machine, so only those processes travel, not the whole process table.
     */
    fun probe(keep: List<String> = emptyList()): String =
        "sh -c ${quote(PROBE)} herdr-health ${quote(keep.joinToString("\t"))}"

    /** Memory, load and disks only: the quick look of a machine's card and of the app's own looks while closed. */
    fun quick(): String = "sh -c ${quote(QUICK)} herdr-health"

    // Written without shell variables ($name would be Kotlin's): positional parameters hold what it keeps.
    private val HEAD = """
        LC_ALL=C; export LC_ALL
        echo "$MARK 1"
        echo "@os $(uname -s)"
        echo "@now $(date +%s)"
        echo "@uid $(id -u)"
        echo "@home $(cd && pwd)"
        echo "@cores $(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)"
        if [ -r /proc/meminfo ]; then
          echo @meminfo; grep -E '^(MemTotal|MemAvailable|MemFree|Buffers|Cached|SwapTotal|SwapFree):' /proc/meminfo
          echo "@loadavg $(cat /proc/loadavg)"
        else
          echo "@memsize $(sysctl -n hw.memsize 2>/dev/null)"
          echo "@pagesize $(sysctl -n hw.pagesize 2>/dev/null)"
          echo "@swapusage $(sysctl -n vm.swapusage 2>/dev/null)"
          echo "@loadavg $(sysctl -n vm.loadavg 2>/dev/null | tr -d '{}')"
          echo @vmstat; vm_stat 2>/dev/null
        fi
        echo @df; df -kPT 2>/dev/null || df -kP 2>/dev/null
        echo @end-df
    """.trimIndent()

    private val QUICK = "$HEAD\n" + """
        if [ -r /proc/stat ]; then
          set -- "$(head -n 1 /proc/stat)"; sleep 1
          echo "@cpu1 $1"; echo "@cpu2 $(head -n 1 /proc/stat)"
        fi
    """.trimIndent()

    /** Each pid's CPU ticks so far and its name, from /proc/<pid>/stat (the name may hold spaces and parentheses). */
    private const val TICKS = "t() { cat /proc/[0-9]*/stat 2>/dev/null | awk '{ s = \$0; if (!match(s, /\\(.*\\)/)) next; " +
        "c = substr(s, RSTART + 1, RLENGTH - 2); n = split(substr(s, RSTART + RLENGTH + 1), f, \" \"); " +
        "gsub(/\\t/, \" \", c); print \"T\\t\" \$1 \"\\t\" (f[12] + f[13]) \"\\t\" c }'; }"

    /**
     * Joins the two tick passes, `ps`, the working folders and the environments into one line per
     * process worth showing: `P pid ppid uid rssKb cpu ageSeconds comm cwd pwd pane workspace args`.
     */
    private val JOIN = """
        BEGIN { FS = "\t"; nk = split(keep, K, "\t"); tot1 = tot2 = idle1 = idle2 = 0 }
        /^C1 / { n = split(${'$'}0, x, " "); for (i = 3; i <= n && i <= 10; i++) tot1 += x[i]; idle1 = x[6] + x[7]; next }
        /^C2 / { n = split(${'$'}0, x, " "); for (i = 3; i <= n && i <= 10; i++) tot2 += x[i]; idle2 = x[6] + x[7]; next }
        /^A\t/ { a[${'$'}2] = ${'$'}3; next }
        /^T\t/ { t[${'$'}2] = ${'$'}3; comm[${'$'}2] = ${'$'}4; next }
        /^D\t/ { split(${'$'}2, y, "/"); cwd[y[3]] = ${'$'}3; next }
        /^E\t/ {
          s = ${'$'}2; split(s, y, "/"); v = substr(s, index(s, ":") + 1); e = index(v, "=")
          name = substr(v, 1, e - 1); val = substr(v, e + 1); gsub(/\t/, " ", val)
          if (name == "PWD") pwd[y[3]] = val; else if (name == "HERDR_PANE_ID") pane[y[3]] = val; else if (name == "HERDR_WORKSPACE_ID") ws[y[3]] = val
          next
        }
        /^P\t/ {
          s = substr(${'$'}0, 3); split(s, f, " ")
          p = f[1]; pids[p] = 1; ppid[p] = f[2]; uid[p] = f[3]; rss[p] = f[4]; age[p] = f[5]
          sub(/^ *[0-9]+ +[0-9]+ +[0-9]+ +[0-9]+ +[0-9]+ ?/, "", s); gsub(/\t/, " ", s); args[p] = substr(s, 1, 240)
          # What names a long command line (a JVM's main class after its classpath) is kept past the cut.
          if (length(s) > 240 && match(s, /[A-Za-z.]*(GradleDaemon|GradleWorkerMain|GradleWrapperMain|KotlinCompileDaemon)( [0-9][^ ]*)?| -avd [^ ]+| -name [^ ]+/))
            args[p] = substr(s, 1, 200) " … " substr(s, RSTART, RLENGTH)
          next
        }
        function inside(path, k) { return path != "" && index(path, "/.herdr/worktrees/" k) > 0 }
        END {
          dt = tot2 - tot1
          if (dt > 0) printf "@cpu %.1f\n", 100 * (dt - (idle2 - idle1)) / dt
          # Only elements that exist are read (`p in x` first): gawk 5.2.1 (Ubuntu 24.04, Debian 12)
          # corrupts its empty string when missing ones are, and then crashes.
          for (p in pids) {
            cpu = 0
            if (dt > 0 && (p in a) && (p in t)) cpu = (t[p] - a[p]) * 100 * cores / dt
            if (cpu < 0) cpu = 0
            c = (p in comm) ? comm[p] : ""
            if (c == "") { c = args[p]; sub(/ .*/, "", c); sub(/.*\//, "", c) }
            d = (p in cwd) ? cwd[p] : ""
            w = (p in pwd) ? pwd[p] : ""
            pn = (p in pane) ? pane[p] : ""
            sp = (p in ws) ? ws[p] : ""
            fin = 0; for (i = 1; i <= nk; i++) if (K[i] != "" && (inside(d, K[i]) || inside(w, K[i]))) fin = 1
            wt = index(d, "/.herdr/worktrees/") || index(w, "/.herdr/worktrees/")
            special = args[p] ~ /GradleDaemon|KotlinCompileDaemon|qemu-system|emulator/
            if (rss[p] >= 65536 || cpu >= 2 || fin || (uid[p] == me && (special || (wt && rss[p] >= 20480))))
              printf "P\t%s\t%s\t%s\t%s\t%.1f\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", p, ppid[p], uid[p], rss[p], cpu, age[p], c, d, w, pn, sp, args[p]
          }
        }
    """.trimIndent()

    private val PROBE = "$HEAD\n$TICKS\n" + """
        if [ -r /proc/stat ]; then
          set -- "$1" "$(head -n 1 /proc/stat)" "$(t)"; sleep 1
          {
            echo "C1 $2"; echo "C2 $(head -n 1 /proc/stat)"
            printf '%s\n' "$3" | sed 's/^T/A/'
            t
            ps -eo pid=,ppid=,uid=,rss=,etimes=,args= 2>/dev/null | sed 's/^/P	/'
            find /proc -mindepth 2 -maxdepth 2 -name cwd -user "$(id -u)" -printf 'D\t%h\t%l\n' 2>/dev/null
            grep -a -s -z -H -E '^(PWD|HERDR_PANE_ID|HERDR_WORKSPACE_ID)=' /proc/[0-9]*/environ 2>/dev/null | tr '\000' '\n' | sed 's/^/E	/'
          } | awk -v keep="$1" -v me="$(id -u)" -v cores="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)" ${quote(JOIN)}
        else
          echo @ps
          ps -axo pid=,ppid=,uid=,rss=,%cpu=,etime=,args= 2>/dev/null | awk -v me="$(id -u)" '${'$'}4 >= 65536 || ${'$'}5 >= 2 || (${'$'}3 == me && /GradleDaemon|KotlinCompileDaemon|qemu-system|emulator/)' | cut -c1-300
        fi
    """.trimIndent()

    /**
     * The worktrees under `~/.herdr/worktrees/<repo>/` (where herdr and the board make them): `W path
     * sizeKb dirty` each, the size empty when counting took too long.
     */
    fun worktrees(): String = "sh -c ${quote(WORKTREES)} herdr-health"

    private val WORKTREES = """
        LC_ALL=C; export LC_ALL
        echo "$MARK 1"
        for d in "$(cd && pwd)"/.herdr/worktrees/*/*/; do
          [ -d "${'$'}d" ] || continue
          set -- "${'$'}{d%/}"
          s=""; if command -v timeout >/dev/null 2>&1; then s="$(timeout 20 du -sk "$1" 2>/dev/null | cut -f1)"; else s="$(du -sk "$1" 2>/dev/null | cut -f1)"; fi
          if [ -n "$(git -C "$1" status --porcelain 2>/dev/null | head -n 1)" ]; then x=1; else x=0; fi
          printf 'W\t%s\t%s\t%s\n' "$1" "${'$'}s" "${'$'}x"
        done
        exit 0
    """.trimIndent()

    /**
     * Stops processes: each one only while it is still the program it was ([Target.comm]), so a pid
     * reused since the look is left alone. SIGTERM first; what still runs after 5 seconds gets SIGKILL.
     * Prints `sent|gone|fail|forced <pid>` per process.
     */
    fun kill(targets: List<Target>): String {
        val args = targets.joinToString(" ") { quote("${it.pid}\t${it.comm}") }
        return "sh -c ${quote(KILL)} herdr-kill $args"
    }

    data class Target(val pid: Int, val comm: String)

    private val KILL = """
        LC_ALL=C; export LC_ALL
        sent=""
        for x in "$@"; do
          p="${'$'}{x%%	*}"; c="${'$'}{x#*	}"
          now="$(ps -o comm= -p "${'$'}p" 2>/dev/null | sed 's/^ *//')"
          case "${'$'}now" in
            "${'$'}c"|*/"${'$'}c") if kill -TERM "${'$'}p" 2>/dev/null; then sent="${'$'}sent ${'$'}p"; echo "sent ${'$'}p"; else echo "fail ${'$'}p"; fi ;;
            *) echo "gone ${'$'}p" ;;
          esac
        done
        i=0
        while [ -n "${'$'}sent" ] && [ ${'$'}i -lt 5 ]; do
          sleep 1; i=${'$'}((i + 1)); left=""
          for p in ${'$'}sent; do kill -0 "${'$'}p" 2>/dev/null && left="${'$'}left ${'$'}p"; done
          sent="${'$'}left"
        done
        for p in ${'$'}sent; do kill -KILL "${'$'}p" 2>/dev/null && echo "forced ${'$'}p"; done
        exit 0
    """.trimIndent()

    /**
     * Removes a worktree checkout with git, from the repository it belongs to; [force] also when it has
     * changes. The branch stays.
     */
    fun removeWorktree(path: String, force: Boolean): String {
        val f = if (force) " --force" else ""
        return "sh -c ${quote(REMOVE.replace("@FORCE@", f))} herdr-worktree ${quote(path)}"
    }

    private val REMOVE = """
        cd "$1" 2>/dev/null || { echo "That worktree is already gone." >&2; exit 3; }
        set -- "$1" "$(cd "$(git rev-parse --git-common-dir 2>/dev/null)" 2>/dev/null && pwd)"
        [ -n "$2" ] || { echo "That folder isn't a git worktree." >&2; exit 4; }
        cd / && git --git-dir="$2" worktree remove@FORCE@ "$1"
    """.trimIndent()
}
