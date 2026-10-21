package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.Thinking
import com.makefunstuff.herdr.core.chat.Transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun onlyPiSplitsThinkTagsAndATagInWordsStaysWords() {
        val text = "<thinking> is a tag some models use. It wraps reasoning."
        val claude = read(
            "claude",
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"what is <thinking>?"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}""",
            """{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":[{"type":"text","text":"<think>\nnot claude's\n</think>\nWords."}]}}""",
        )
        assertEquals(listOf(text, "<think>\nnot claude's\n</think>\nWords."), claude.filterIsInstance<ChatItem.Agent>().map { it.text })
        assertTrue(thoughts(claude).isEmpty())
        val codex = read("codex", """{"type":"event_msg","payload":{"type":"agent_message","message":"$text"}}""")
        assertEquals(listOf(text), codex.filterIsInstance<ChatItem.Agent>().map { it.text })
        val pi = read("pi", """{"type":"message","id":"m1","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}""")
        assertEquals(listOf(text), pi.filterIsInstance<ChatItem.Agent>().map { it.text })
    }

    @Test
    fun piDrawsItsThoughtOnScreenBeforeItWritesIt() {
        fun screen(name: String) = javaClass.getResource("/screens/$name")!!.readText()
        // A real pi screen (llama.cpp), cut while its thought was the newest thing over the editor.
        assertEquals(
            "Everything is running and building. Final summary for the user:\n\n" +
                "- Creatures are now \"live\": bigger (0.42 base radius), bobbing with sine motion, glowing amber with eyes, soft bloom.\n" +
                "- Build passes, dev server running at http://localhost:5173/.\n\n" +
                "Keep it concise.",
            Thinking.onScreen(screen("pi-thinking.ansi")),
        )
        // The whole screen: its answer and an error came after the thought, so nothing is thinking now.
        assertNull(Thinking.onScreen(screen("pi-done.ansi")))
        // pi 1.3 at work (deepseek, thinking high), read once a second as the chat reads it: its spinner
        // sits in the editor's top border (`── ⠴ Working ───`).
        val thinking = assertNotNull(Thinking.onScreen(screen("pi-working-thinking.ansi")))
        assertTrue(thinking.startsWith("day trips base. Weigh: A wins for train efficiency"))
        assertTrue(thinking.endsWith("and finish Days 6–7 in Osaka with a 45-minute ¥580 day trip to Nara; budget about ¥20"))
        assertTrue("Two sentences only, no lists." in thinking)
        assertEquals("1001 = 7 × 11 × 13. Not prime. Check divisors up to sqrt", Thinking.onScreen(screen("pi-working-short.ansi")))
        // Only your prompt so far, or its answer streaming after the thought: nothing is thinking.
        assertNull(Thinking.onScreen(screen("pi-working-asked.ansi")))
        assertNull(Thinking.onScreen(screen("pi-working-answering.ansi")))
        // Plain text has no italics: nothing to read.
        assertNull(Thinking.onScreen("just words\n────────────\n> \n────────────\nfooter"))
    }

    @Test
    fun longThoughtsAlwaysCutIntoSmallPieces() {
        // No paragraph breaks at all, and a fence never closed: still pieces of at most three times the size, and nothing lost.
        val flat = "word ".repeat(20_000)
        val pieces = Thinking.chunks(flat, 1_000)
        assertTrue(pieces.size > 20 && pieces.all { it.length <= 3_000 })
        assertEquals(flat.split(" ").count { it.isNotEmpty() }, pieces.sumOf { p -> p.split(Regex("\\s+")).count { it.isNotEmpty() } })
        val drafted = "Plan:\n\n```kotlin\n" + (1..2_000).joinToString("\n") { "val x$it = $it" } + "\n\nmore"
        val parts = Thinking.chunks(drafted, 1_000)
        assertTrue(parts.size > 5 && parts.all { it.length <= 3_100 })
        // Each piece cut inside the fence closes it and the next opens it again.
        assertTrue(parts.drop(1).dropLast(1).all { it.startsWith("```kotlin") && it.endsWith("```") })
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
        assertEquals("Reasoning." to "Hi", Thinking.split("<think>\nReasoning.\n</think>\n\nHi"))
        assertEquals(null to "Hi", Thinking.split("<think>\n\n</think>\n\nHi"))
        // Words that only start with a tag, or never close it, are words.
        assertNull(Thinking.split("<think> tags wrap reasoning; close them with </think>.").first)
        assertNull(Thinking.split("<think>\nstill going").first)
        assertNull(Thinking.split("<thinking> is a tag some models use.").first)
        assertEquals("Assessing the build", Thinking.preview("\n**Assessing the build**\nmore"))
        assertEquals("b c", Thinking.tail("a\n\nb\nc", 2))
        assertEquals("HostState has updatedAt", Thinking.tail("- `HostState` has **updatedAt**", 1))
        val newest = Thinking.tail("word ".repeat(100) + "the newest", 3, chars = 40)
        assertTrue(newest.startsWith("…word") && newest.endsWith("the newest") && newest.length <= 41)
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
