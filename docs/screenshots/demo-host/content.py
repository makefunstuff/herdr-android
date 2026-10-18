#!/usr/bin/env python3
"""Writes the demo transcripts and agent screens into the demo user's home (run inside a demo host)."""
import json, os, sys, uuid
from datetime import datetime, timedelta, timezone

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


# The demo's clock: the app's previews look at it on 17 October 2026, 07:42 UTC (Demo.NOW).
def at(text):
    return datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


def iso(t):
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"


# ---------- Claude Code transcripts ----------
class Claude:
    """Rows of a Claude Code session; each row `after` seconds past the one before, from `start`."""

    def __init__(self, start, cwd):
        self.rows = []
        self.n = 0
        self.t = at(start)
        self.cwd = cwd

    def _id(self):
        self.n += 1
        return f"00000000-0000-4000-8000-{self.n:012d}"

    def _row(self, row, after):
        self.t += timedelta(seconds=after)
        self.rows.append({**row, "timestamp": iso(self.t), "cwd": self.cwd})

    def user(self, text, after=0):
        self._row({"type": "user", "uuid": self._id(), "message": {"role": "user", "content": text}}, after)

    def say(self, text, after=0):
        self._row({"type": "assistant", "uuid": self._id(), "message": {"role": "assistant", "content": [{"type": "text", "text": text}]}}, after)

    def tool(self, name, inp, result=None, error=False, after=0, took=0.2):
        tid = "toolu_" + self._id()[-8:]
        self._row({"type": "assistant", "uuid": self._id(), "message": {"role": "assistant", "content": [{"type": "tool_use", "id": tid, "name": name, "input": inp}]}}, after)
        if result is not None:
            self._row({"type": "user", "uuid": self._id(), "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid, "content": result, "is_error": error}]}}, took)


def claude_app():
    # The first ask took 2m 36s; the second came 38 minutes later and is still at work at 07:42.
    c = Claude("2026-10-17T06:58:10", HOME + "/src/herdr-android")
    p = HOME + "/src/herdr-android/app/src/main/kotlin/com/makefunstuff/herdr/ui/machines/MachinesScreen.kt"
    c.user("The Machines list only refreshes when the app is reopened. Add pull-to-refresh that reconnects every machine, and keep the last known state on screen while it does.")
    c.say("I'll look at how the Machines screen gets its state first.", after=6)
    c.tool("Read", {"file_path": p}, "156 lines", after=2)
    c.tool("Grep", {"pattern": "fun retry", "path": HOME + "/src/herdr-android/app"}, "app/src/main/kotlin/com/makefunstuff/herdr/data/HostManager.kt:62:    fun retryAll()", after=4.8)
    c.say("`HostManager.retryAll()` already reconnects every session, so the screen only needs a `PullToRefreshBox` around the list. Adding it now.", after=19.8)
    c.tool("Edit", {"file_path": p, "old_string": "LazyColumn(", "new_string": "PullToRefreshBox(isRefreshing = refreshing, onRefresh = { hosts.retryAll() }) {\n    LazyColumn("}, "The file has been updated.", after=39.8)
    c.tool("Bash", {"command": "./gradlew :app:testDebugUnitTest", "description": "Run the app's unit tests"}, "BUILD SUCCESSFUL in 38s\n27 actionable tasks: 6 executed, 21 up-to-date", after=4.8, took=38.4)
    c.say(after=40, text=
        "Pull-to-refresh is in.\n\n"
        "**What changed**\n"
        "- `MachinesScreen` wraps the list in a `PullToRefreshBox` that calls `retryAll()`.\n"
        "- The spinner stays until every machine has answered or failed, so a slow host doesn't end it early.\n"
        "- Cards keep the last snapshot and show *Showing the last known state* while they reconnect.\n\n"
        "| Check | Result |\n|---|---|\n| Unit tests | 44 passed |\n| Emulator, 3 machines | refreshes in ~1 s |"
    )
    c.user("Nice. Also show when each machine last answered, like \"2 min ago\".", after=38 * 60 + 16)
    c.say("Sure — `HostState` has no timestamp yet, so I'll add one where a snapshot arrives.", after=7)
    c.tool("Read", {"file_path": HOME + "/src/herdr-android/core/src/main/kotlin/com/makefunstuff/herdr/core/host/HostSession.kt"}, "320 lines", after=3)
    c.tool("Edit", {"file_path": HOME + "/src/herdr-android/core/src/main/kotlin/com/makefunstuff/herdr/core/host/HostSession.kt", "old_string": "val snapshot", "new_string": "val snapshot: Snapshot?,\n    val updatedAt: Long?"}, after=29.8)
    return c.rows


def claude_api():
    c = Claude("2026-10-17T07:31:05", HOME + "/src/api-server")
    c.user("The /jobs endpoint takes 4 s on the big tenant. Find out why and fix it.")
    c.tool("Bash", {"command": "psql -c 'EXPLAIN ANALYZE SELECT * FROM jobs ORDER BY created_at DESC LIMIT 50'", "description": "Explain the slow jobs query"}, "Seq Scan on jobs  (cost=0.00..48211.20 rows=1201480)\nExecution Time: 3912.448 ms", after=9, took=4.1)
    c.say("It's a sequential scan over 1.2 M rows: `jobs` has no index on `created_at`. I wrote a migration that adds one concurrently, so the table stays writable.", after=21)
    c.tool("Write", {"file_path": HOME + "/src/api-server/migrations/0042_jobs_created_at.sql", "content": "CREATE INDEX CONCURRENTLY jobs_created_at_idx ON jobs (created_at DESC);"}, "File created successfully", after=12)
    c.tool("Bash", {"command": "npm run db:migrate", "description": "Apply the new migration"}, after=6)
    return c.rows


def codex_rows(start):
    """A codex rollout's completed items, each `after` seconds past the one before, from `start`."""
    rows = []
    clock = [at(start)]
    def item(i, after=0):
        clock[0] += timedelta(seconds=after)
        rows.append({"timestamp": iso(clock[0]), "type": "event_msg", "payload": {"type": "item_completed", "item": i}})
    return rows, item


def codex_site():
    rows, item = codex_rows("2026-10-16T09:12:40")
    item({"type": "UserMessage", "id": "u1", "content": [{"type": "text", "text": "Rewrite the install page so it starts with the one-line installer, and check every link on it."}]})
    item({"type": "AgentMessage", "id": "a1", "content": [{"type": "text", "text": "Reading the current page and the link checker config."}]}, after=5)
    item({"type": "CommandExecution", "id": "c1", "command": ["bash", "-lc", "rg -n 'install' content/docs/install.md | head"], "aggregated_output": "3:## Install\n12:curl -fsSL https://example.com/install.sh | sh", "exit_code": 0, "status": "completed"}, after=3)
    item({"type": "FileChange", "id": "f1", "changes": {"content/docs/install.md": {"type": "update", "unified_diff": "@@ -1,4 +1,9 @@\n # Install\n-Download the archive for your platform and unpack it.\n+\n+```sh\n+curl -fsSL https://example.com/install.sh | sh\n+```\n \n-## Install\n+## Manual install\n+\n+## Updating\n"}}, "status": "completed"}, after=48)
    item({"type": "CommandExecution", "id": "c2", "command": ["bash", "-lc", "npm run check-links"], "aggregated_output": "Checked 38 links, 0 broken", "exit_code": 0, "status": "completed"}, after=21)
    item({"type": "AgentMessage", "id": "a2", "content": [{"type": "text", "text": "Done. The page now opens with the installer one-liner, then *Manual install* and *Updating*. All 38 links resolve."}]}, after=12)
    return rows


def codex_eval():
    rows, item = codex_rows("2026-10-17T07:05:31")
    item({"type": "UserMessage", "id": "u1", "content": [{"type": "text", "text": "Run the long-context eval on the new checkpoint and compare it with last week's."}]})
    item({"type": "AgentMessage", "id": "a1", "content": [{"type": "text", "text": "Starting the 128k suite on both GPUs. It takes about 40 minutes; I'll compare against `ckpt-0605` when it finishes."}]}, after=8)
    item({"type": "CommandExecution", "id": "c1", "command": ["bash", "-lc", "nvidia-smi --query-gpu=name,memory.used --format=csv"], "aggregated_output": "name, memory.used\nGPU 0, 61 GiB\nGPU 1, 58 GiB", "exit_code": 0, "status": "completed"}, after=4)
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
