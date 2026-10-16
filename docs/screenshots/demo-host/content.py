#!/usr/bin/env python3
"""Writes the demo transcripts and agent screens into the demo user's home (run inside a demo host)."""
import json, os, sys, uuid

HOME = os.path.expanduser("~")
role = sys.argv[1]


def w(path, text, mode=0o644):
    path = os.path.join(HOME, path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    os.chmod(path, mode)
    return path


def jsonl(path, rows):
    return w(path, "".join(json.dumps(r) + "\n" for r in rows))


# ---------- Claude Code transcripts ----------
class Claude:
    def __init__(self):
        self.rows = []
        self.n = 0

    def _id(self):
        self.n += 1
        return f"00000000-0000-4000-8000-{self.n:012d}"

    def user(self, text):
        self.rows.append({"type": "user", "uuid": self._id(), "message": {"role": "user", "content": text}})

    def say(self, text):
        self.rows.append({"type": "assistant", "uuid": self._id(), "message": {"role": "assistant", "content": [{"type": "text", "text": text}]}})

    def tool(self, name, inp, result=None, error=False):
        tid = "toolu_" + self._id()[-8:]
        self.rows.append({"type": "assistant", "uuid": self._id(), "message": {"role": "assistant", "content": [{"type": "tool_use", "id": tid, "name": name, "input": inp}]}})
        if result is not None:
            self.rows.append({"type": "user", "uuid": self._id(), "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid, "content": result, "is_error": error}]}})


def claude_app():
    c = Claude()
    p = HOME + "/src/herdr-android/app/src/main/kotlin/com/makefunstuff/herdr/ui/machines/MachinesScreen.kt"
    c.user("The Machines list only refreshes when the app is reopened. Add pull-to-refresh that reconnects every machine, and keep the last known state on screen while it does.")
    c.say("I'll look at how the Machines screen gets its state first.")
    c.tool("Read", {"file_path": p}, "156 lines")
    c.tool("Grep", {"pattern": "fun retry", "path": HOME + "/src/herdr-android/app"}, "app/src/main/kotlin/com/makefunstuff/herdr/data/HostManager.kt:62:    fun retryAll()")
    c.say("`HostManager.retryAll()` already reconnects every session, so the screen only needs a `PullToRefreshBox` around the list. Adding it now.")
    c.tool("Edit", {"file_path": p, "old_string": "LazyColumn(", "new_string": "PullToRefreshBox(isRefreshing = refreshing, onRefresh = { hosts.retryAll() }) {\n    LazyColumn("}, "The file has been updated.")
    c.tool("Bash", {"command": "./gradlew :app:testDebugUnitTest", "description": "Run the app's unit tests"}, "BUILD SUCCESSFUL in 38s\n27 actionable tasks: 6 executed, 21 up-to-date")
    c.say(
        "Pull-to-refresh is in.\n\n"
        "**What changed**\n"
        "- `MachinesScreen` wraps the list in a `PullToRefreshBox` that calls `retryAll()`.\n"
        "- The spinner stays until every machine has answered or failed, so a slow host doesn't end it early.\n"
        "- Cards keep the last snapshot and show *Showing the last known state* while they reconnect.\n\n"
        "| Check | Result |\n|---|---|\n| Unit tests | 44 passed |\n| Emulator, 3 machines | refreshes in ~1 s |"
    )
    c.user("Nice. Also show when each machine last answered, like \"2 min ago\".")
    c.say("Sure — `HostState` has no timestamp yet, so I'll add one where a snapshot arrives.")
    c.tool("Read", {"file_path": HOME + "/src/herdr-android/core/src/main/kotlin/com/makefunstuff/herdr/core/host/HostSession.kt"}, "320 lines")
    c.tool("Edit", {"file_path": HOME + "/src/herdr-android/core/src/main/kotlin/com/makefunstuff/herdr/core/host/HostSession.kt", "old_string": "val snapshot", "new_string": "val snapshot: Snapshot?,\n    val updatedAt: Long?"})
    return c.rows


def claude_api():
    c = Claude()
    c.user("The /jobs endpoint takes 4 s on the big tenant. Find out why and fix it.")
    c.tool("Bash", {"command": "psql -c 'EXPLAIN ANALYZE SELECT * FROM jobs ORDER BY created_at DESC LIMIT 50'", "description": "Explain the slow jobs query"}, "Seq Scan on jobs  (cost=0.00..48211.20 rows=1201480)\nExecution Time: 3912.448 ms")
    c.say("It's a sequential scan over 1.2 M rows: `jobs` has no index on `created_at`. I wrote a migration that adds one concurrently, so the table stays writable.")
    c.tool("Write", {"file_path": HOME + "/src/api-server/migrations/0042_jobs_created_at.sql", "content": "CREATE INDEX CONCURRENTLY jobs_created_at_idx ON jobs (created_at DESC);"}, "File created successfully")
    c.tool("Bash", {"command": "npm run db:migrate", "description": "Apply the new migration"})
    return c.rows


def codex_site():
    rows = []
    def item(i):
        rows.append({"type": "event_msg", "payload": {"type": "item_completed", "item": i}})
    item({"type": "UserMessage", "id": "u1", "content": [{"type": "text", "text": "Rewrite the install page so it starts with the one-line installer, and check every link on it."}]})
    item({"type": "AgentMessage", "id": "a1", "content": [{"type": "text", "text": "Reading the current page and the link checker config."}]})
    item({"type": "CommandExecution", "id": "c1", "command": ["bash", "-lc", "rg -n 'install' content/docs/install.md | head"], "aggregated_output": "3:## Install\n12:curl -fsSL https://example.com/install.sh | sh", "exit_code": 0, "status": "completed"})
    item({"type": "FileChange", "id": "f1", "changes": {"content/docs/install.md": {"type": "update"}}, "status": "completed"})
    item({"type": "CommandExecution", "id": "c2", "command": ["bash", "-lc", "npm run check-links"], "aggregated_output": "Checked 38 links, 0 broken", "exit_code": 0, "status": "completed"})
    item({"type": "AgentMessage", "id": "a2", "content": [{"type": "text", "text": "Done. The page now opens with the installer one-liner, then *Manual install* and *Updating*. All 38 links resolve."}]})
    return rows


def codex_eval():
    rows = []
    def item(i):
        rows.append({"type": "event_msg", "payload": {"type": "item_completed", "item": i}})
    item({"type": "UserMessage", "id": "u1", "content": [{"type": "text", "text": "Run the long-context eval on the new checkpoint and compare it with last week's."}]})
    item({"type": "AgentMessage", "id": "a1", "content": [{"type": "text", "text": "Starting the 128k suite on both GPUs. It takes about 40 minutes; I'll compare against `ckpt-0605` when it finishes."}]})
    item({"type": "CommandExecution", "id": "c1", "command": ["bash", "-lc", "nvidia-smi --query-gpu=name,memory.used --format=csv"], "aggregated_output": "name, memory.used\nGPU 0, 61 GiB\nGPU 1, 58 GiB", "exit_code": 0, "status": "completed"})
    return rows



SCREEN_CLAUDE_APP = """\033[38;5;174m╭───────────────────────────────────────────────╮\033[0m
\033[38;5;174m│\033[0m \033[38;5;174m✻\033[0m Welcome to \033[1mClaude Code\033[0m!                       \033[38;5;174m│\033[0m
\033[38;5;174m│\033[0m                                               \033[38;5;174m│\033[0m
\033[38;5;174m│\033[0m   \033[2mcwd: ~/src/herdr-android\033[0m                    \033[38;5;174m│\033[0m
\033[38;5;174m╰───────────────────────────────────────────────╯\033[0m

\033[2m>\033[0m The Machines list only refreshes when the app is
  reopened. Add pull-to-refresh that reconnects every
  machine, and keep the last known state on screen.

\033[32m●\033[0m \033[1mRead\033[0m(app/…/machines/MachinesScreen.kt)
  ⎿  Read \033[1m156\033[0m lines

\033[32m●\033[0m \033[1mUpdate\033[0m(app/…/machines/MachinesScreen.kt)
  ⎿  Updated with \033[1m14\033[0m additions and \033[1m3\033[0m removals

\033[32m●\033[0m \033[1mBash\033[0m(./gradlew :app:testDebugUnitTest)
  ⎿  BUILD SUCCESSFUL in 38s

\033[32m●\033[0m Pull-to-refresh is in. The spinner stays until
  every machine has answered or failed.

\033[2m>\033[0m Nice. Also show when each machine last answered.

\033[32m●\033[0m \033[1mUpdate\033[0m(core/…/host/HostSession.kt)

\033[38;5;174m✻ Adding a timestamp… \033[2m(12s · esc to interrupt)\033[0m

\033[2m╭───────────────────────────────────────────────╮\033[0m
\033[2m│\033[0m >                                             \033[2m│\033[0m
\033[2m╰───────────────────────────────────────────────╯\033[0m
  \033[2m⏵⏵ accept edits on\033[0m
"""

SCREEN_CLAUDE_API = """\033[32m●\033[0m \033[1mWrite\033[0m(migrations/0042_jobs_created_at.sql)
  ⎿  Wrote 1 line

────────────────────────────────────────────────────────
 Bash command
   npm run db:migrate
   Apply the new migration
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
 Do you want to proceed?
 ❯ 1. Yes
   2. Yes, and don't ask again for npm run commands
   3. No, and tell Claude what to do differently

 Esc to cancel · Tab to amend
"""

SCREEN_CODEX = """\033[1m>_ OpenAI Codex\033[0m  \033[2m~/src/website\033[0m

\033[1muser\033[0m
Rewrite the install page so it starts with the one-line
installer, and check every link on it.

\033[32m✓\033[0m rg -n 'install' content/docs/install.md
\033[32m✓\033[0m Edited content/docs/install.md
\033[32m✓\033[0m npm run check-links  \033[2m38 links, 0 broken\033[0m

\033[1mcodex\033[0m
Done. The page now opens with the installer one-liner.

\033[2m› Ask Codex to do anything\033[0m
"""

SCREEN_CODEX_EVAL = """\033[1m>_ OpenAI Codex\033[0m  \033[2m~/llm-eval\033[0m

Run the long-context eval on the new checkpoint and
compare it with last week's.

Starting the 128k suite on both GPUs.
\033[33m$ python eval.py --suite long-context --ckpt ckpt-0612\033[0m
  suite long-context  ████████████░░░░░░░░  61%  (734/1200)

• Working \033[2m(2m 14s • esc to interrupt)\033[0m

\033[2m› Ask Codex to do anything\033[0m
"""

if role == "devbox":
    s1 = "3f6c2a1e-8b7d-4c2a-9e51-0a1b2c3d4e5f"
    jsonl(f".claude/projects/-home-demo-src-herdr-android/{s1}.jsonl", claude_app())
    s2 = "9d2e4b7a-1c3f-4e6a-8b90-5f4e3d2c1b0a"
    jsonl(f".claude/projects/-home-demo-src-api-server/{s2}.jsonl", claude_api())
    s3 = "0199a3b2-7c41-7e10-9a2b-3c4d5e6f7a8b"
    jsonl(f".codex/sessions/2026/10/16/rollout-2026-10-16T09-12-40-{s3}.jsonl", codex_site())
    w(".demo/screen-claude-app", SCREEN_CLAUDE_APP)
    w(".demo/screen-claude-api", SCREEN_CLAUDE_API)
    w(".demo/screen-codex", SCREEN_CODEX)
elif role == "gpu-box":
    s4 = "0199a3c4-1d2e-7f30-8a4b-5c6d7e8f9a0b"
    jsonl(f".codex/sessions/2026/10/16/rollout-2026-10-16T08-02-11-{s4}.jsonl", codex_eval())
    w(".demo/screen-codex-eval", SCREEN_CODEX_EVAL)
