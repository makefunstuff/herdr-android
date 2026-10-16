package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ClaudeTranscript
import com.makefunstuff.herdr.core.chat.CodexTranscript
import com.makefunstuff.herdr.core.chat.PiTranscript
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.core.chat.TranscriptFollower
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockedPromptTest {
    private fun screen(name: String) = javaClass.getResource("/screens/$name")!!.readText()

    @Test
    fun claudePermission() {
        val p = assertNotNull(BlockedPrompt.parse(screen("claude-permission.txt")))
        assertEquals("Do you want to proceed?", p.question)
        assertEquals(listOf("Yes", "Yes, and always allow access to /tmp/claude-1000/-home-jpl--herdr-worktrees-herdr-android-work-2-replace-agent-terminals-with-n/518745e3-9052-4b3c-81ca-5ddd766812d8/scratchpad/proj from this project", "No"), p.options.map { it.label })
        assertEquals(listOf(1, 2, 3), p.options.map { it.number })
        assertEquals(0, p.selected)
        assertTrue("touch b.txt" in p.details, p.details.toString())
        assertTrue("Bash command" in p.details)
        assertEquals("Esc to cancel · Tab to amend", p.hint)
        assertEquals(listOf("down", "down", "enter"), p.keysFor(2))
        assertEquals(listOf("enter"), p.keysFor(0))
    }

    @Test
    fun claudeTrustFolder() {
        val p = assertNotNull(BlockedPrompt.parse(screen("claude-trust.txt")))
        assertEquals(listOf("No, exit", "Yes, I trust this folder"), p.options.map { it.label })
        assertEquals(0, p.selected)
        assertTrue(p.question.startsWith("Quick safety check: Is this a project you created or one you trust?"), p.question)
        assertEquals(listOf("down", "enter"), p.keysFor(1))
    }

    @Test
    fun codexPermission() {
        val p = assertNotNull(BlockedPrompt.parse(screen("codex-permission.txt")))
        assertEquals("Reason: Allow me to create c.txt containing hi in the current workspace?", p.question)
        assertEquals(3, p.options.size)
        assertEquals("Yes, proceed (y)", p.options[0].label)
        assertEquals("No, and tell Codex what to do differently (esc)", p.options[2].label)
        assertEquals("Yes, and don't ask again for commands that start with `printf 'hi' > c.txt` (p)", p.options[1].label)
        assertNull(p.options[1].description)
        assertTrue("$ printf 'hi' > c.txt" in p.details, p.details.toString())
        assertTrue(p.details.first().startsWith("Would you like to run"), p.details.toString())
        assertEquals("Press enter to confirm or esc to cancel", p.hint)
    }

    @Test
    fun codexTrust() {
        val p = assertNotNull(BlockedPrompt.parse(screen("codex-trust.txt")))
        assertEquals(listOf("Trust and continue", "Back to Agent Command Center"), p.options.map { it.label })
        assertTrue(p.question.startsWith("Trust this folder?"), p.question)
    }

    @Test
    fun noMenu() {
        assertNull(BlockedPrompt.parse("just some output\nand more\n"))
    }
}

class TranscriptTest {
    @Test
    fun claude() {
        val t = ClaudeTranscript()
        listOf(
            """{"type":"permission-mode","permissionMode":"auto"}""",
            """{"type":"user","uuid":"u1","isSidechain":false,"message":{"role":"user","content":"inspect my nas"}}""",
            """{"type":"user","uuid":"m1","isMeta":true,"message":{"role":"user","content":"<local-command-caveat>x</local-command-caveat>"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"thinking","thinking":""}]}}""",
            """{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"df -h","description":"Check space"}}]}}""",
            """{"type":"user","uuid":"r1","message":{"role":"user","content":[{"type":"tool_result","content":"97% used","is_error":false,"tool_use_id":"toolu_1"}]}}""",
            """{"type":"assistant","uuid":"a3","message":{"role":"assistant","content":[{"type":"text","text":"The volume is **97%** full."}]}}""",
            """{"type":"assistant","uuid":"a4","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"subagent"}]}}""",
            """{"type":"user","uuid":"u2","message":{"role":"user","content":"<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args>opus</command-args>"}}""",
            """{"type":"user","uuid":"u3","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}""",
            """{"type":"user","uuid":"u4","message":{"role":"user","content":"<task-notification>done</task-notification>"}}""",
            "not json",
        ).forEach(t::feed)
        val items = t.items()
        assertEquals(listOf("inspect my nas"), items.filterIsInstance<ChatItem.User>().take(1).map { it.text })
        val tool = items.filterIsInstance<ChatItem.Tool>().single()
        assertEquals("Check space", tool.summary)
        assertTrue(tool.input!!.contains("command: df -h"))
        assertEquals("97% used", tool.output)
        assertEquals(ToolState.Done, tool.state)
        assertEquals("The volume is **97%** full.", items.filterIsInstance<ChatItem.Agent>().single().text)
        assertEquals("/model opus", (items[3] as ChatItem.User).text)
        assertIs<ChatItem.Notice>(items[4])
        assertEquals(5, items.size)
    }

    @Test
    fun codexItems() {
        val t = CodexTranscript()
        listOf(
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions"}]}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"upgrade pi"}]}}}""",
            """{"type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\"cmd\":\"ls\"}","call_id":"c1"}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","id":"e1","command":["/usr/bin/bash","-lc","which pi"],"status":"completed","exit_code":0,"aggregated_output":"/usr/bin/pi\n"}}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","id":"f1","changes":{"/a/b.md":{"type":"add","content":"x"}},"status":"completed"}}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"m1","content":[{"type":"Text","text":"Pi is upgraded."}]}}}""",
            """{"type":"event_msg","payload":{"type":"turn_aborted","reason":"interrupted"}}""",
        ).forEach(t::feed)
        val items = t.items()
        assertEquals("upgrade pi", (items[0] as ChatItem.User).text)
        // Raw function calls are only used by older Codex versions that don't log items.
        assertEquals("which pi", (items[1] as ChatItem.Tool).summary)
        assertEquals("+ /a/b.md", (items[2] as ChatItem.Tool).summary)
        assertEquals("Pi is upgraded.", (items[3] as ChatItem.Agent).text)
        assertIs<ChatItem.Notice>(items[4])
        assertEquals(5, items.size)
    }

    @Test
    fun codexLegacy() {
        val t = CodexTranscript()
        listOf(
            """{"type":"event_msg","payload":{"type":"user_message","message":"fix the build"}}""",
            """{"type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"{\"command\":[\"bash\",\"-lc\",\"make\"]}","call_id":"c1"}}""",
            """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"{\"output\":\"ok\",\"metadata\":{}}"}}""",
            """{"type":"event_msg","payload":{"type":"agent_message","message":"Fixed."}}""",
        ).forEach(t::feed)
        val items = t.items()
        assertEquals(listOf("fix the build"), items.filterIsInstance<ChatItem.User>().map { it.text })
        val tool = items.filterIsInstance<ChatItem.Tool>().single()
        assertEquals("make", tool.summary)
        assertEquals("ok", tool.output)
        assertEquals("Fixed.", items.filterIsInstance<ChatItem.Agent>().single().text)
    }

    @Test
    fun pi() {
        val t = PiTranscript()
        listOf(
            """{"type":"session","version":3,"id":"s1","cwd":"/x"}""",
            """{"type":"message","id":"m0","message":{"role":"system","content":"sys"}}""",
            """{"type":"message","id":"m1","message":{"role":"user","content":[{"type":"text","text":"make it nicer"},{"type":"image","data":"blob"}]}}""",
            """{"type":"message","id":"m2","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hm"},{"type":"text","text":"Looking."},{"type":"toolCall","id":"t1","name":"read","arguments":{"path":"/x/game.js"}}]}}""",
            """{"type":"message","id":"m3","message":{"role":"toolResult","toolCallId":"t1","toolName":"read","content":[{"type":"text","text":"code"}],"isError":true}}""",
            """{"type":"message","id":"m4","message":{"role":"assistant","content":[],"stopReason":"aborted"}}""",
        ).forEach(t::feed)
        val items = t.items()
        val user = items[0] as ChatItem.User
        assertEquals("make it nicer", user.text)
        assertEquals(1, user.images)
        assertEquals("Looking.", (items[1] as ChatItem.Agent).text)
        val tool = items[2] as ChatItem.Tool
        assertEquals("/x/game.js", tool.summary)
        assertEquals(ToolState.Failed, tool.state)
        assertIs<ChatItem.Notice>(items[3])
    }
}

class TranscriptFollowerTest {
    private fun run(home: File, ref: AgentSessionRef, bytes: Int = 1_000_000): List<String> {
        val pb = ProcessBuilder("sh", "-c", TranscriptFollower.command(ref, bytes))
        pb.environment()["HOME"] = home.absolutePath
        pb.environment().remove("CLAUDE_CONFIG_DIR")
        pb.environment().remove("CODEX_HOME")
        val p = pb.start()
        val reader = p.inputStream.bufferedReader()
        val lines = mutableListOf<String>()
        val header = reader.readLine()
        lines += header
        if (!header.startsWith("@@missing")) {
            repeat(2) { lines += reader.readLine() }
        }
        // Closing stdin is how the app ends the stream; tail must stop with it.
        p.outputStream.close()
        assertTrue(p.waitFor(5, TimeUnit.SECONDS), "follower kept running after its input closed")
        return lines
    }

    @Test
    fun findsTranscriptsAndStopsWithTheChannel() {
        val home = kotlin.io.path.createTempDirectory("home").toFile()
        val claude = File(home, ".claude/projects/-data-x").apply { mkdirs() }
        File(claude, "abc-123.jsonl").writeText("{\"a\":1}\n{\"b\":2}\n")
        val lines = run(home, AgentSessionRef("claude", "id", "abc-123"))
        assertTrue(lines[0].startsWith("@@file 16 ") && lines[0].endsWith("abc-123.jsonl"), lines[0])
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), lines.drop(1))

        val codex = File(home, ".codex/sessions/2026/10/16").apply { mkdirs() }
        File(codex, "rollout-2026-10-16T08-00-00-0199-aaa.jsonl").writeText("1\n2\n")
        assertEquals(listOf("1", "2"), run(home, AgentSessionRef("codex", "id", "0199-aaa")).drop(1))

        val pi = File(home, ".pi/agent/sessions/--x--").apply { mkdirs() }
        val piFile = File(pi, "2026-10-13T08-18-43-915Z_0199-bbb.jsonl").apply { writeText("p\nq\n") }
        assertEquals(listOf("p", "q"), run(home, AgentSessionRef("pi", "id", "0199-bbb")).drop(1))
        assertEquals(listOf("p", "q"), run(home, AgentSessionRef("pi", "path", piFile.absolutePath)).drop(1))

        assertEquals(listOf("@@missing"), run(home, AgentSessionRef("claude", "id", "nope")))
    }
}

class MarkdownTest {
    @Test
    fun blocks() {
        val md = """
            ## Result
            The volume is **97%** full,
            with 165 GB free.

            - first `cmd`
              continued
              - nested
            1. one
            2. two

            ```bash
            df -h
            ```

            | Share | Size |
            |---|---:|
            | **homes** | 1 TB |

            > quoted
            ---
        """.trimIndent()
        val b = com.makefunstuff.herdr.core.chat.Markdown.parse(md)
        val types = b.map { it::class.simpleName }
        assertEquals(listOf("Heading", "Paragraph", "ListItem", "ListItem", "ListItem", "ListItem", "Code", "Table", "Quote", "Rule"), types)
        val para = b[1] as com.makefunstuff.herdr.core.chat.Markdown.Block.Paragraph
        assertEquals("The volume is 97% full, with 165 GB free.", para.text.joinToString("") { it.text })
        assertTrue(para.text.single { it.text == "97%" }.bold)
        val item = b[2] as com.makefunstuff.herdr.core.chat.Markdown.Block.ListItem
        assertEquals("first cmd continued", item.text.joinToString("") { it.text })
        assertEquals(1, (b[3] as com.makefunstuff.herdr.core.chat.Markdown.Block.ListItem).depth)
        assertEquals("2.", (b[5] as com.makefunstuff.herdr.core.chat.Markdown.Block.ListItem).marker)
        assertEquals("df -h", (b[6] as com.makefunstuff.herdr.core.chat.Markdown.Block.Code).text)
        assertEquals(listOf(listOf("Share", "Size"), listOf("homes", "1 TB")), (b[7] as com.makefunstuff.herdr.core.chat.Markdown.Block.Table).rows)
    }

    @Test
    fun inline() {
        val spans = com.makefunstuff.herdr.core.chat.Markdown.inline("see [the launcher](/home/x/pi) and snake_case_name, *really* https://herdr.dev.")
        assertEquals("see the launcher and snake_case_name, really https://herdr.dev.", spans.joinToString("") { it.text })
        assertEquals("/home/x/pi", spans.single { it.text == "the launcher" }.link)
        assertTrue(spans.single { it.text == "really" }.italic)
        assertEquals("https://herdr.dev", spans.single { it.text == "https://herdr.dev" }.link)
        assertEquals("2 * 3 = 6", com.makefunstuff.herdr.core.chat.Markdown.inline("2 * 3 = 6").joinToString("") { it.text })
    }
}
