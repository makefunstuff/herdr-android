// The made-up world every scene starts from: the README screenshots' demo host
// (docs/screenshots/demo-host) as fixed data. devbox runs three agents and the work board;
// gpu-box and ci-runner are reached through it; mac-mini refuses the app's key.
package preview

import com.makefunstuff.herdr.brief.BriefState
import com.makefunstuff.herdr.core.brief.Baseline
import com.makefunstuff.herdr.core.brief.BriefSchedule
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.push.MachinePush
import com.makefunstuff.herdr.push.PushState
import com.makefunstuff.herdr.core.ssh.ConnectError
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneOffset

object Demo {
    const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHd2Zm9sZGVkLXByZXZpZXcta2V5LWZvci1oZXJkcg herdr-android@Pixel-7"

    const val DEVBOX = "devbox"
    const val GPU = "gpu-box"
    const val CI = "ci-runner"
    const val MINI = "mac-mini"

    /** Panes the scenes open. */
    const val APP_AGENT = "w1:p1"
    const val APP_SHELL = "w1:p2"
    const val API_AGENT = "w2:p1"
    const val SITE_AGENT = "w3:p1"

    /** The brief's "now": Saturday 17 October 2026, 07:42 (UTC), the morning after. */
    val NOW: Long = LocalDateTime.of(2026, 10, 17, 7, 42).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** 23:10 the night before: when the app was last on screen. */
    val SEEN: Long = LocalDateTime.of(2026, 10, 16, 23, 10).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** A time of that night or morning, on the demo machines' clock (seconds): 12:00 and later is the 16th. */
    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, if (hour >= 12) 16 else 17, hour, minute).toEpochSecond(ZoneOffset.UTC)

    /**
     * The brief's part: you last looked at 23:10, a brief comes at 07:30 and 17:30 on weekdays (read
     * aloud in the car), and the app had seen ci-runner's agents then (it keeps no journal).
     */
    fun brief() = BriefState(
        schedule = BriefSchedule(on = true, times = listOf(7 * 60 + 30, 17 * 60 + 30), days = (1..5).toSet()),
        seenAt = SEEN,
        baselines = mapOf(CI to Baseline(tasks = null, agents = emptyMap(), agentsAt = SEEN)),
    )

    /** herdr-push's journal on a machine, as `herdr-push journal` answers: kept since a week ago, up to now. */
    private fun journal(vararg entries: JsonObject) = obj(
        "ok" to true, "version" to "4", "now" to NOW / 1000, "from" to NOW / 1000 - 7 * 24 * 3600, "upto" to NOW / 1000 - 3,
        "running" to true, "entries" to entries.toList(),
    )

    private fun taskEntry(hour: Int, minute: Int, key: String, was: String, vararg task: Pair<String, Any?>) =
        obj("at" to at(hour, minute), "type" to "task", "key" to key, "task" to obj(*task), "was" to was)

    private fun agentEntry(hour: Int, minute: Int, pane: String, kind: String, status: String, title: String, where: String) = obj(
        "at" to at(hour, minute), "type" to "agent", "where" to where, "workspace" to where.substringBefore(" › "),
        "agent" to obj(
            "pane_id" to pane, "workspace_id" to pane.substringBefore(':'), "tab_id" to "${pane.substringBefore(':')}:t1",
            "agent" to kind, "name" to kind, "display_agent" to kind, "title" to title, "agent_status" to status, "state_change_seq" to 2,
        ),
    )

    /** What devbox's tasks did that night: two merged, a research report, a review that failed, two that wait for you, one that started. */
    fun devboxJournal() = journal(
        taskEntry(0, 52, "AND-8", "needs", "id" to "herdr-android/8", "title" to "Swipe triage: seen, pin, snooze, mute", "state" to "done", "stage" to "done",
            "pr" to "https://github.com/demo/herdr-android/pull/8", "msg" to "Merged", "archivedAt" to null),
        taskEntry(1, 20, "AND-2", "needs", "id" to "herdr-android/2", "title" to "Replace agent terminals with native chat views", "state" to "done",
            "stage" to "done", "pr" to "https://github.com/demo/herdr-android/pull/3", "msg" to "Merged"),
        agentEntry(2, 10, SITE_AGENT, "codex", "done", "Rewrite the install page", "website › main"),
        taskEntry(2, 14, "WEB-4", "working", "id" to "website/4", "title" to "Rewrite the install page", "state" to "blocked", "stage" to "approval",
            "kind" to "permission", "msg" to "Review found 2 problems", "review" to obj("pass" to false)),
        taskEntry(4, 30, "EVAL-1", "working", "id" to "llm-eval/1", "title" to "Compare long-context benchmarks", "state" to "done", "stage" to "done",
            "research" to true, "msg" to "The report is ready"),
        taskEntry(5, 40, "API-3", "working", "id" to "api-server/3", "title" to "Speed up /jobs on large tables", "state" to "blocked", "stage" to "working",
            "kind" to "question", "msg" to "Asks whether the migration may lock the jobs table for a few seconds"),
        taskEntry(6, 30, "AND-6", "queued", "id" to "herdr-android/6", "title" to "Add app screenshots to the README", "state" to "working", "stage" to "working"),
        taskEntry(6, 58, "AND-9", "working", "id" to "herdr-android/9", "title" to "Show when each machine last answered", "state" to "blocked",
            "stage" to "approval", "kind" to "permission", "msg" to "Review passed. Ready to merge.", "review" to obj("pass" to true)),
    )

    /** gpu-box has no board: one of your agents finished there in the night. */
    fun gpuJournal() = journal(
        agentEntry(3, 40, "w3:p1", "codex", "done", "Profile the data loader", "notebooks › main"),
    )

    fun resource(path: String): String =
        Demo::class.java.classLoader.getResourceAsStream("demo/$path")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("missing demo resource $path")

    /** The saved machines, in the order the app lists them. */
    fun machines(): List<Machine> = listOf(
        machine(DEVBOX, "devbox", port = 2201),
        machine(GPU, "gpu-box", via = DEVBOX),
        machine(CI, "ci-runner", via = DEVBOX),
        machine(MINI, "mac-mini", host = "192.168.1.40", user = "jpl"),
    )

    private fun machine(id: String, label: String, host: String = label, port: Int = 22, user: String = "demo", via: String? = null) =
        Machine(id = id, label = label, host = host, port = port, user = user, viaMachineId = via, hostKey = FakeMachine(id).fingerprint)

    /** Notifications on: devbox and gpu-box send, ci-runner is left out, mac-mini has no python3. */
    fun push(): PushState {
        val endpoint = "https://ntfy.sh/upDemoTopic?up=1"
        return PushState(
            on = true, phoneId = "demo-phone", endpoint = endpoint, distributor = "io.heckel.ntfy",
            machines = mapOf(
                DEVBOX to MachinePush(key = "demo", sentTo = endpoint, version = "1"),
                GPU to MachinePush(key = "demo", sentTo = endpoint, version = "1", note = "it stops when you log out (loginctl enable-linger keeps it running)"),
                CI to MachinePush(off = true),
                MINI to MachinePush(key = "demo", problem = "herdr-push needs python3 on mac-mini, and it isn't installed"),
            ),
        )
    }

    /** Quiet hours as someone might set them: with the phone's modes, and weeknights from 22:00 to 07:00. */
    fun quiet() = QuietHours(followPhone = true, schedule = true, start = 22 * 60, end = 7 * 60, days = (1..5).toSet())

    /** Two agents that finished while it was quiet. */
    fun held() = listOf(
        Alert(Alerts.agentTag(DEVBOX, APP_AGENT), "done#12", Alert.Kind.Finished, DEVBOX, "app finished", "Speed up /jobs", paneId = APP_AGENT),
        Alert(Alerts.agentTag(DEVBOX, SITE_AGENT), "done#7", Alert.Kind.Finished, DEVBOX, "site finished", "Rewrite the install page", paneId = SITE_AGENT),
    )

    /** A failing CI run shared from the browser, with a screenshot of it. */
    fun shared() = com.makefunstuff.herdr.core.share.Shared(
        text = "CI is red on main: test_jobs_pagination times out\nhttps://ci.example.dev/api-server/runs/1482",
        files = listOf(com.makefunstuff.herdr.core.share.SharedFile("Screenshot_20261017_0912.png", "/nonexistent/Screenshot_20261017_0912.png", 482_133)),
    )

    /** Two comments on task AND-9's code, kept to send together: one on the card's text, one on its diff. */
    fun notes() = listOf(
        com.makefunstuff.herdr.core.code.ReviewNote(
            id = "1", machineId = DEVBOX, root = FakeCode.TASK, branch = "work/9-last-answered",
            path = "app/src/main/kotlin/demo/herdr/ui/machines/MachineCard.kt", start = 37, end = 38,
            quote = "        // Offline: the last known state, and how old it is.\n        else -> \"Answered \" + LastAnswered.words(state.answeredAt, now)",
            language = "kotlin", text = "Show this in the notice colour when it is older than an hour, so a stale card stands out.", at = NOW - 600_000,
        ),
        com.makefunstuff.herdr.core.code.ReviewNote(
            id = "2", machineId = DEVBOX, root = FakeCode.TASK, branch = "work/9-last-answered",
            path = "core/src/main/kotlin/demo/herdr/core/host/HostSession.kt", start = 22, end = 22, source = "the diff against main",
            quote = "+        _state.update { it.copy(online = true, agents = agents, answeredAt = clock()) }", language = "diff",
            text = "Also note the time when a snapshot arrives, not only on the first answer.", at = NOW - 300_000,
        ),
    )

    /** A file to share for real: the app copies it to the machine, then deletes it. */
    fun sharedFile(): java.io.File = kotlin.io.path.createTempFile("shared", ".png").toFile().apply { writeBytes(ByteArray(1024)) }

    /** What each machine has and answers. */
    fun hosts(): Map<String, FakeMachine> = mapOf(
        DEVBOX to FakeMachine(
            DEVBOX,
            snapshot = devbox(),
            screens = mapOf(
                APP_AGENT to resource("screen-claude-app.txt"),
                APP_SHELL to SHELL,
                API_AGENT to resource("screen-claude-api.txt"),
                SITE_AGENT to resource("screen-codex.txt"),
            ),
            transcripts = mapOf(
                APP_SESSION to resource("claude-app.jsonl"),
                API_SESSION to resource("claude-api.jsonl"),
                SITE_SESSION to resource("codex-site.jsonl"),
            ),
            board = FakeBoard(),
            workspaceTokens = mapOf("w1" to mapOf("branch" to "work/9-last-answered", "pr" to "#9")),
            journal = devboxJournal(),
            code = FakeCode(),
        ),
        GPU to FakeMachine(
            GPU,
            snapshot = snapshot(
                Ws("w1", "llm-eval", "/home/demo/llm-eval", listOf(Pane("w1:p1", agent = Agent("codex", "working", EVAL_SESSION, "Long-context eval on ckpt-0612")))),
                Ws("w2", "notebooks", "/home/demo/notebooks", listOf(Pane("w2:p1", label = "shell"))),
            ),
            screens = mapOf("w1:p1" to resource("screen-codex-eval.txt")),
            transcripts = mapOf(EVAL_SESSION to resource("codex-eval.jsonl")),
            journal = gpuJournal(),
        ),
        CI to FakeMachine(
            CI,
            snapshot = snapshot(
                Ws("w1", "runners", "/home/demo/runners", listOf(Pane("w1:p1", label = "shell"))),
                Ws("w2", "deploy", "/home/demo/deploy", listOf(Pane("w2:p1", label = "shell"))),
            ),
        ),
        MINI to FakeMachine(MINI, refuse = ConnectError.Auth(null)),
    )

    private const val APP_SESSION = "3f6c2a1e-8b7d-4c2a-9e51-0a1b2c3d4e5f"
    private const val API_SESSION = "9d2e4b7a-1c3f-4e6a-8b90-5f4e3d2c1b0a"
    private const val SITE_SESSION = "0199a3b2-7c41-7e10-9a2b-3c4d5e6f7a8b"
    private const val EVAL_SESSION = "0199a3c4-1d2e-7f30-8a4b-5c6d7e8f9a0b"

    fun devbox(appStatus: String = "working"): JsonObject = snapshot(
        Ws(
            "w1", "herdr-android", "/home/demo/src/herdr-android",
            listOf(
                Pane(APP_AGENT, agent = Agent("claude", appStatus, APP_SESSION, "Show when each machine last answered")),
                Pane(APP_SHELL, label = "shell"),
            ),
            repo = "herdr-android",
        ),
        Ws("w2", "api-server", "/home/demo/src/api-server", listOf(Pane(API_AGENT, agent = Agent("claude", "blocked", API_SESSION, "Speed up /jobs on large tables"))), repo = "api-server"),
        Ws("w3", "website", "/home/demo/src/website", listOf(Pane(SITE_AGENT, agent = Agent("codex", "done", SITE_SESSION, "Rewrite the install page"))), repo = "website"),
    )

    class Agent(val kind: String, val status: String, val session: String, val title: String)
    class Pane(val id: String, val label: String? = null, val agent: Agent? = null)
    class Ws(val id: String, val label: String, val cwd: String, val panes: List<Pane>, val repo: String? = null)

    /** A herdr `session.snapshot`: one tab per workspace, its panes side by side. */
    fun snapshot(vararg workspaces: Ws): JsonObject {
        val all = workspaces.toList()
        fun status(list: List<Pane>) = list.mapNotNull { it.agent?.status }.maxByOrNull { listOf("idle", "done", "working", "blocked").indexOf(it) } ?: "unknown"
        return obj(
            "version" to "0.9.0",
            "protocol" to 4,
            "workspaces" to all.mapIndexed { i, ws ->
                obj(
                    "workspace_id" to ws.id, "number" to i + 1, "label" to ws.label, "focused" to (i == 0),
                    "pane_count" to ws.panes.size, "tab_count" to 1, "active_tab_id" to "${ws.id}:t1", "agent_status" to status(ws.panes),
                    "worktree" to ws.repo?.let { obj("repo_key" to it, "repo_name" to it, "repo_root" to ws.cwd, "checkout_path" to ws.cwd) },
                )
            },
            "tabs" to all.map { ws ->
                obj(
                    "tab_id" to "${ws.id}:t1", "workspace_id" to ws.id, "number" to 1, "label" to "main", "focused" to true,
                    "pane_count" to ws.panes.size, "agent_status" to status(ws.panes),
                )
            },
            "panes" to all.flatMap { ws ->
                ws.panes.mapIndexed { i, p ->
                    obj(
                        "pane_id" to p.id, "terminal_id" to "t-${p.id}", "workspace_id" to ws.id, "tab_id" to "${ws.id}:t1",
                        "focused" to (i == 0), "cwd" to ws.cwd, "label" to p.label, "agent" to p.agent?.kind,
                        "display_agent" to p.agent?.kind, "agent_status" to (p.agent?.status ?: "unknown"),
                        "terminal_title_stripped" to (p.agent?.title ?: "bash"),
                    )
                }
            },
            "layouts" to all.map { ws ->
                val w = 200 / ws.panes.size
                obj(
                    "workspace_id" to ws.id, "tab_id" to "${ws.id}:t1", "area" to rect(0, 0, 200, 50), "focused_pane_id" to ws.panes.first().id,
                    "panes" to ws.panes.mapIndexed { i, p -> obj("pane_id" to p.id, "focused" to (i == 0), "rect" to rect(i * w, 0, w, 50)) },
                    "splits" to if (ws.panes.size > 1) listOf(obj("id" to "s1", "direction" to "right", "ratio" to 0.5, "rect" to rect(0, 0, 200, 50))) else emptyList<Any>(),
                )
            },
            "agents" to all.flatMap { ws ->
                ws.panes.mapNotNull { p ->
                    val a = p.agent ?: return@mapNotNull null
                    obj(
                        "terminal_id" to "t-${p.id}", "name" to a.kind, "agent" to a.kind, "display_agent" to a.kind, "title" to a.title,
                        "agent_status" to a.status, "workspace_id" to ws.id, "tab_id" to "${ws.id}:t1", "pane_id" to p.id,
                        "state_change_seq" to 1, "cwd" to ws.cwd,
                        "agent_session" to obj("agent" to a.kind, "kind" to "id", "value" to a.session, "source" to "herdr:${a.kind}"),
                    )
                }
            },
        )
    }

    private fun rect(x: Int, y: Int, w: Int, h: Int) = obj("x" to x, "y" to y, "width" to w, "height" to h)

    /** The shell beside the herdr-android agent, as `demo-session` leaves it. */
    private val SHELL = run {
        val p = "\u001b[1;32mdemo@devbox\u001b[0m:\u001b[1;34m~/src/herdr-android\u001b[0m$ "
        val y = "\u001b[33m"; val g = "\u001b[32m"; val c = "\u001b[36m"; val r = "\u001b[31m"; val b = "\u001b[1m"; val o = "\u001b[0m"
        """
        |${p}git log --oneline --graph -7
        |* ${y}ae3c0e4$o ($c${b}HEAD -> $o${g}${b}main$o) Better chat composer
        |* ${y}cda5a86$o Adopt the herdr web UI look
        |* ${y}acfc414$o Visual design pass
        |* ${y}355be7c$o Replace native popups with a designed UI
        |* ${y}f0abe66$o Better Markdown for the native board
        |* ${y}2c4e1b9$o Coordinator chat: attach files
        |* ${y}9a07d3e$o Board: approve from the task screen
        |${p}git status -sb
        |## ${g}main$o...${r}origin/main$o
        | ${r}M$o app/src/main/kotlin/com/makefunstuff/herdr/ui/machines/MachinesScreen.kt
        |${p}./gradlew -p core test
        |> Task :compileKotlin UP-TO-DATE
        |> Task :test
        |
        |BoardTest > coordinatorChatMerges ${g}PASSED$o
        |ChatTest > claudeTranscriptReadsTools ${g}PASSED$o
        |ChatTest > blockedPromptParsesMenu ${g}PASSED$o
        |PainterTest > drawsWideCharacters ${g}PASSED$o
        |
        |$b${g}BUILD SUCCESSFUL$o in 21s
        |4 actionable tasks: 1 executed, 3 up-to-date
        |$p
        """.trimMargin()
    }
}
