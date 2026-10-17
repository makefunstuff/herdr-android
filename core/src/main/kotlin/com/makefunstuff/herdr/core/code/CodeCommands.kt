package com.makefunstuff.herdr.core.code

import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote

/**
 * The shell commands that read a machine's code over the app's SSH connection: its Git checkouts,
 * their files, a text search, status, diffs and history. Every one only reads: Git runs with
 * `GIT_OPTIONAL_LOCKS=0`, so not even `git status` refreshes the index, and nothing is written.
 *
 * Each runs as `sh -c <script> herdr-code-<op> <args>`, so `$0` names what it does (the previews'
 * fake machine answers by that name). A path may start with `~/`, as the board writes them.
 */
object CodeCommands {
    /** The most a file read takes; a bigger file shows its first part. */
    const val READ_LIMIT = 4L * 1024 * 1024

    /** The most a diff or a commit brings; the rest is cut, and said so. */
    const val DIFF_LIMIT = 8L * 1024 * 1024

    /** The most of a file list (bytes of names): a monorepo's hundreds of thousands of names fit. */
    const val FILES_LIMIT = 16L * 1024 * 1024

    /** Matches a search brings, at most. */
    const val SEARCH_LIMIT = 400

    /** Commits the history lists. */
    const val LOG_LIMIT = 80

    const val MARK = "@@herdr-code"

    private const val PRELUDE = "export GIT_OPTIONAL_LOCKS=0 GIT_PAGER=cat GIT_TERMINAL_PROMPT=0; " +
        "g() { git -c core.quotepath=off -c color.ui=false -c diff.noprefix=false -c diff.mnemonicPrefix=false \"\$@\"; }; " +
        "home() { case \"\$1\" in '~') printf '%s' \"\$HOME\";; '~/'*) printf '%s/%s' \"\$HOME\" \"\${1#??}\";; *) printf '%s' \"\$1\";; esac; }; "

    /** Goes into the folder given first, or says it is missing. */
    private const val INTO = "d=\$(home \"\$1\"); cd \"\$d\" 2>/dev/null || { echo '$MARK missing'; exit 0; }; "

    private fun script(op: String, body: String, vararg args: String): String =
        "sh -c ${quote(PRELUDE + body)} herdr-code-$op " + args.joinToString(" ") { quote(it) }

    /**
     * For each folder: the checkout it is in (`P <folder> <top>`), and once per repository its
     * `git worktree list --porcelain` (between `W <top>` and `E`).
     */
    fun discover(folders: List<String>): String = script(
        "discover",
        "seen=' '; for p in \"\$@\"; do q=\$(home \"\$p\"); [ -d \"\$q\" ] || continue; " +
            "t=\$(g -C \"\$q\" rev-parse --show-toplevel 2>/dev/null) || continue; printf 'P\\t%s\\t%s\\n' \"\$p\" \"\$t\"; " +
            "c=\$(cd \"\$t\" && cd \"\$(g rev-parse --git-common-dir)\" 2>/dev/null && pwd -P) || continue; " +
            "case \"\$seen\" in *\" \$c \"*) continue;; esac; seen=\"\$seen\$c \"; " +
            "printf 'W\\t%s\\n' \"\$t\"; g -C \"\$t\" worktree list --porcelain 2>/dev/null; printf 'E\\n'; done; exit 0",
        *folders.toTypedArray(),
    )

    /**
     * What a checkout is: `git <top> <branch> <head> <base> <main checkout>`, or `nogit <folder>`.
     * The base is [base] when given, else the branch `origin/HEAD` names (the local one when there
     * is one), else main, master or trunk.
     */
    fun info(folder: String, base: String = ""): String = script(
        "info",
        INTO + "t=\$(g rev-parse --show-toplevel 2>/dev/null) || { printf 'nogit\\t%s\\n' \"\$(pwd -P)\"; exit 0; }; cd \"\$t\"; " +
            "b=\$(g rev-parse --abbrev-ref HEAD 2>/dev/null); h=\$(g rev-parse --short HEAD 2>/dev/null); base=\$2; " +
            "if [ -z \"\$base\" ]; then o=\$(g symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null); " +
            "if [ -n \"\$o\" ]; then n=\${o#origin/}; if g show-ref --verify --quiet \"refs/heads/\$n\"; then base=\$n; else base=\$o; fi; fi; fi; " +
            "if [ -z \"\$base\" ]; then for c in main master trunk origin/main origin/master; do " +
            "if g rev-parse --verify --quiet \"\$c^{commit}\" >/dev/null; then base=\$c; break; fi; done; fi; " +
            "m=\$(g worktree list --porcelain 2>/dev/null | sed -n '1s/^worktree //p'); " +
            "printf 'git\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$t\" \"\$b\" \"\$h\" \"\$base\" \"\$m\"",
        folder, base,
    )

    /** Every file in the checkout, NUL-separated: Git's tracked and untracked ones (not ignored), or a `find` outside Git. */
    fun files(folder: String): String = script(
        "files",
        INTO + "if g rev-parse --git-dir >/dev/null 2>&1; then g ls-files -co --exclude-standard -z; " +
            "else find . \\( -name .git -o -name node_modules -o -name .gradle -o -name build -o -name target \\) -prune -o -type f -print " +
            "| sed 's|^\\./||' | head -n 200000 | tr '\\n' '\\000'; fi | head -c $FILES_LIMIT",
        folder,
    )

    /** `@@size <bytes>` and then at most [limit] bytes of [path] (relative to the checkout), or `@@nofile`. */
    fun read(folder: String, path: String, limit: Long = READ_LIMIT): String = script(
        "read",
        INTO + "f=\$2; if [ ! -f \"\$f\" ]; then echo '@@nofile'; exit 0; fi; " +
            "printf '@@size %s\\n' \"\$(wc -c < \"\$f\" | tr -d ' ')\"; head -c \"\$3\" \"\$f\"",
        folder, path, limit.toString(),
    )

    /**
     * Lines that match [query], as `path:line:text` (cut at 600 characters): `git grep` over the
     * tracked and untracked files, or `grep -r` outside Git. Binary files are left out.
     */
    fun search(folder: String, query: String, regex: Boolean, matchCase: Boolean, limit: Int = SEARCH_LIMIT): String {
        val flags = listOfNotNull(if (regex) "-E" else "-F", if (matchCase) null else "-i").joinToString(" ")
        return script(
            "search",
            INTO + "if g rev-parse --git-dir >/dev/null 2>&1; then g grep -n -I --full-name --untracked \$3 -e \"\$2\" -- . 2>/dev/null; " +
                "else grep -rnI \$3 --exclude-dir=.git --exclude-dir=node_modules -e \"\$2\" . 2>/dev/null | sed 's|^\\./||'; fi " +
                "| head -n \"\$4\" | cut -c1-600",
            folder, query, flags, limit.toString(),
        )
    }

    /** `git status` with the branch line, every untracked file named. */
    fun status(folder: String): String = script("status", INTO + "g status --porcelain=v1 -b --untracked-files=all", folder)

    /**
     * A diff of the checkout as it is now (committed or not, and its untracked files as new ones):
     * against where it left [base] ([Scope.Base]), or against its last commit ([Scope.Uncommitted]).
     * Starts with `@@from <commit>`, or `@@nobase` when the two share no history.
     */
    fun diff(folder: String, base: String, scope: DiffScope): String = script(
        "diff",
        INTO + "if [ \"\$3\" = head ]; then from=\$(g rev-parse --verify --quiet HEAD) || from=4b825dc642cb6eb9a060e54bf8d69288fbee4904; " +
            "else from=\$(g merge-base HEAD \"\$2\" 2>/dev/null) || { echo '@@nobase'; exit 0; }; fi; printf '@@from %s\\n' \"\$from\"; " +
            "{ g diff --no-ext-diff --no-textconv -M \"\$from\" --; g ls-files -o --exclude-standard | head -n 200 | " +
            "while IFS= read -r f; do g diff --no-ext-diff --no-index -- /dev/null \"\$f\"; done; } | head -c $DIFF_LIMIT",
        folder, base, if (scope == DiffScope.Uncommitted) "head" else "base",
    )

    /**
     * The last [limit] commits, one record each (fields split by 0x1F, records by 0x1E), after an
     * `@@ahead <sha>` line for each commit that [base] doesn't have yet.
     */
    fun log(folder: String, base: String, limit: Int = LOG_LIMIT): String = script(
        "log",
        INTO + "if [ -n \"\$2\" ]; then g rev-list --max-count=500 \"\$2..HEAD\" 2>/dev/null | sed 's/^/@@ahead /'; fi; echo '$MARK log'; " +
            "g log -n \"\$3\" --format='%H%x1f%h%x1f%an%x1f%at%x1f%D%x1f%s%x1e' HEAD 2>/dev/null",
        folder, base, limit.toString(),
    )

    /** One commit: its header record (as [log]'s, with the parents and the whole message) and its diff against its first parent. */
    fun show(folder: String, sha: String): String = script(
        "show",
        INTO + "g show --no-ext-diff --no-textconv -M -m --first-parent --format='%H%x1f%h%x1f%an%x1f%at%x1f%P%x1f%B%x1e' \"\$2\" -- | head -c $DIFF_LIMIT",
        folder, sha,
    )
}

/** What a diff of a checkout is against. */
enum class DiffScope { Base, Uncommitted }
