package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.chat.Markdown
import com.makefunstuff.herdr.core.chat.Markdown.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedMarkdownTest {
    private fun res(n: String) = javaClass.getResource("/board/$n")!!.readText()

    @Test
    fun taskListsAndNesting() {
        val b = Markdown.parse("- [ ] todo\n- [x] done **bold**\n  - [X] nested\n    1. deep")
        val items = b.filterIsInstance<Block.ListItem>()
        assertEquals(listOf(false, true, true, null), items.map { it.checked })
        assertEquals(listOf(0, 0, 1, 2), items.map { it.depth })
        assertEquals("done bold", items[1].text.joinToString("") { it.text })
    }

    @Test
    fun autolinksAndBareUrls() {
        val spans = Markdown.inline("see <https://github.com/a/b/pull/3> and https://x.dev/r.")
        assertEquals(listOf("https://github.com/a/b/pull/3", "https://x.dev/r"), spans.mapNotNull { it.link })
    }

    @Test
    fun plainFirstLine() {
        assertEquals("Fix the **thing**".replace("**", ""), Markdown.plain("## Fix the **thing**\nmore"))
        assertEquals("a task", Markdown.plain("\n- [ ] a task"))
        assertEquals("code", Markdown.plain("```\nx\n```\n`code`"))
    }

    @Test
    fun unclosedFenceAndLongLinesSurvive() {
        val long = "/very/long/path/".repeat(60)
        val b = Markdown.parse("```\n$long\nno close")
        assertEquals(1, b.size)
        assertTrue((b[0] as Block.Code).text.contains(long))
    }

    @Test
    fun realBoardDescriptionsParse() {
        for (f in listOf("details-working.json", "details-reviewed.json", "details-failed.json")) {
            val d = BoardTasks.details(res(f))
            val blocks = Markdown.parse(d.description)
            assertTrue(blocks.isNotEmpty(), f)
            assertTrue(blocks.any { it is Block.ListItem || it is Block.Paragraph || it is Block.Heading }, f)
            for (text in listOf(d.msg, d.plan, d.failure?.detail.orEmpty()) + d.reviews.flatMap { r -> listOf(r.text) + r.findings.flatMap { listOf(it.subject, it.where, it.proof) } }) {
                Markdown.parse(text) // must not throw
            }
        }
    }

    @Test
    fun headingsInDescriptionAndBoldMessage() {
        val d = BoardTasks.details(res("details-working.json"))
        val blocks = Markdown.parse(d.description)
        assertNotNull(blocks.firstOrNull { it is Block.Heading })
        val reviewed = BoardTasks.details(res("details-reviewed.json"))
        val link = Markdown.inline(reviewed.msg).first { it.link != null }
        assertEquals("https://claude.ai/artifact/9jyu8NtQZ1uudnnFLgwfwx", link.link)
        assertTrue(Markdown.inline(reviewed.msg).any { it.bold })
    }
}
