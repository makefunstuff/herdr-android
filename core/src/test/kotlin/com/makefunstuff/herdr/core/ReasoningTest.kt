package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.Thinking
import com.makefunstuff.herdr.core.chat.Transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasoningTest {
    private fun read(agent: String, vararg lines: String): List<ChatItem> =
        Transcript.forAgent(agent)!!.also { t -> lines.forEach(t::feed) }.items()

    private fun thoughts(items: List<ChatItem>) = items.filterIsInstance<ChatItem.Reasoning>().map { it.text }

    @Test
    fun claudeKeepsItsThinkingAndSkipsTheEmptyOnes() {
        val items = read(
            "claude",
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"why is it slow"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"thinking","thinking":"The query scans the whole table.","signature":"x"}]}}""",
            """{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":[{"type":"thinking","thinking":"","signature":"y"}]}}""",
            """{"type":"assistant","uuid":"a3","message":{"role":"assistant","content":[{"type":"redacted_thinking","data":"zzz"}]}}""",
            """{"type":"assistant","uuid":"a4","message":{"role":"assistant","content":[{"type":"text","text":"It has no index."}]}}""",
        )
        assertEquals(listOf("The query scans the whole table."), thoughts(items))
        assertEquals(listOf("u1", "a1/0", "a4/0"), items.map { it.id })
    }

    @Test
    fun codexItemsGiveTheRawReasoningOrTheSummary() {
        val items = read(
            "codex",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"go"}]}}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"Reasoning","id":"r1","summary_text":["**Assessing the build**","Checking Gradle first."],"raw_content":[]}}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"Reasoning","id":"r2","summary_text":[],"raw_content":[]}}}""",
            """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"Reasoning","id":"r3","summary_text":["short"],"raw_content":[{"type":"reasoning_text","text":"The full chain."}]}}}""",
            // The same reasoning as a raw response item: only read by older Codex versions.
            """{"type":"response_item","payload":{"type":"reasoning","id":"rs1","summary":[{"type":"summary_text","text":"dup"}]}}""",
        )
        assertEquals(listOf("**Assessing the build**\n\nChecking Gradle first.", "The full chain."), thoughts(items))
    }

    @Test
    fun codexLegacyReasoning() {
        val items = read(
            "codex",
            """{"type":"event_msg","payload":{"type":"user_message","message":"go"}}""",
            """{"type":"response_item","payload":{"type":"reasoning","id":"rs1","summary":[{"type":"summary_text","text":"Planning"}],"content":null,"encrypted_content":"gAAA"}}""",
            """{"type":"response_item","payload":{"type":"reasoning","id":"rs2","summary":[],"encrypted_content":"gAAA"}}""",
            """{"type":"event_msg","payload":{"type":"agent_message","message":"Done."}}""",
        )
        assertEquals(listOf("Planning"), thoughts(items))
    }

    @Test
    fun piKeepsAnyProvidersThinkingAndThinkTags() {
        val items = read(
            "pi",
            """{"type":"message","id":"m1","message":{"role":"user","content":"hey"}}""",
            // An OpenAI-style provider's reasoning_content (DeepSeek, a local model), as pi stores it.
            """{"type":"message","id":"m2","message":{"role":"assistant","content":[{"type":"thinking","thinking":"A greeting.\n","thinkingSignature":"reasoning_content"},{"type":"text","text":"Hi!"}]}}""",
            """{"type":"message","id":"m3","message":{"role":"assistant","content":[{"type":"thinking","thinking":"secret","redacted":true},{"type":"text","text":"<think>\nInline, from a server that doesn't split it.\n</think>\n\nHello again."}]}}""",
        )
        assertEquals(listOf("A greeting.", "Inline, from a server that doesn't split it."), thoughts(items))
        assertEquals(listOf("Hi!", "Hello again."), items.filterIsInstance<ChatItem.Agent>().map { it.text })
    }

    @Test
    fun theThoughtBeforeTheAnswerStaysWithTheAnswer() {
        val items = read(
            "claude",
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"fix it"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Read the file first."}]}}""",
            """{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/x/a.kt"}}]}}""",
            """{"type":"assistant","uuid":"a3","message":{"role":"assistant","content":[{"type":"thinking","thinking":"It's a typo."}]}}""",
            """{"type":"assistant","uuid":"a4","message":{"role":"assistant","content":[{"type":"text","text":"Fixed the typo."}]}}""",
        )
        val turn = ChatTurns.of(items, emptyList()).single()
        assertEquals(listOf("a1/0", "t1"), turn.work.map { it.id })
        assertEquals(listOf("a3/0", "a4/0"), turn.answer.map { it.id })
        assertTrue(turn.answered)
        assertFalse(turn.keepsOpen)
        // Only thinking after the last call: it hasn't answered yet.
        assertFalse(ChatTurns.of(items.dropLast(1), emptyList()).single().answered)
    }

    @Test
    fun thinkingText() {
        assertEquals(null to "plain", Thinking.split("plain"))
        assertEquals("still going" to "", Thinking.split("<think>still going"))
        assertNull(Thinking.split("<think>\n\n</think>\n\nHi").first)
        assertEquals("Assessing the build", Thinking.preview("\n**Assessing the build**\nmore"))
        assertEquals("b\nc", Thinking.tail("a\n\nb\nc", 2))
        assertEquals("HostState has updatedAt", Thinking.tail("- `HostState` has **updatedAt**", 1))
        assertEquals("3 words", Thinking.size("one two  three"))
        assertEquals("1.2k words", Thinking.size("w ".repeat(1_234)))
        assertEquals("2k words", Thinking.size("w ".repeat(2_000)))
        val long = (1..40).joinToString("\n\n") { "Paragraph $it " + "x".repeat(100) }
        val parts = Thinking.chunks(long, 1_000)
        assertTrue(parts.size in 4..6)
        assertEquals(long.replace("\n\n", ""), parts.joinToString("").replace("\n\n", ""))
        // A code fence is never cut, whatever its length.
        val fenced = "Intro " + "y".repeat(1_000) + "\n\n```\n" + "a\n\nb\n".repeat(400) + "```\n\nEnd"
        assertTrue(Thinking.chunks(fenced, 1_000).none { it.count { c -> c == '`' } % 6 != 0 })
    }
}
