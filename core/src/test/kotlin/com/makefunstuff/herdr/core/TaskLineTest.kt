package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.TaskLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskLineTest {
    @Test
    fun anAgentsStatusBarIsAllNoise() {
        val bar = "~/.herdr/worktrees/herdr-android/work-50-clean-up ↑1.6M ↓807k R64M 73.6%/197k (auto) (strata) model • low"
        assertTrue(TaskLine.isStatusBar(bar))
        assertEquals("", TaskLine.clean(bar))
        assertEquals("", TaskLine.line(bar))
        assertEquals("", TaskLine.clean("↑12k ↓3.4k 41%/200k"))
        assertEquals("", TaskLine.clean("↑1.6M ↓807k R64M 73.6%/197k (auto) (anthropic) claude-opus-5-5 • low"))
        assertEquals("", TaskLine.clean("/home/demo/src/app ↑90k ↓12k 12%/200k gpt-6.1-sol • high"))
    }

    @Test
    fun wordsAreKept() {
        assertEquals("Reading the task and planning the card cleanup", TaskLine.line("Reading the task and planning the card cleanup"))
        assertEquals("Review found 2 problems", TaskLine.line("Review found 2 problems"))
        assertFalse(TaskLine.isStatusBar("Scored 73% of the 64k needle set"))
        // Arrows with numbers in a sentence are words about numbers, not a status bar.
        for (m in listOf(
            "Throughput ↑1.2k req/s, latency ↓30ms after the cache",
            "p95 latency ↓40% and errors ↓12 per hour",
            "Bundle size ↓120k • ready for review",
            "Context 73%/200k, tokens ↑1.2M so far, compacting next",
            "Errors ↓12, latency ↓40%",
            "Latency ↓30ms, errors ↓12",
            "Coverage ↑3% ↓0 flaky",
            "Tests ↑12 passing ↓0 failing",
            "Done ↑2 ↓1 merged",
            "Tokens ↑1.6M ↓807k so far",
        )) assertEquals(m, TaskLine.clean(m))
        // Labels a person writes stay with their words.
        assertEquals("Logs: nothing unusual, tests pass", TaskLine.clean("Logs: nothing unusual, tests pass"))
        assertEquals("Branch: main is green, merging next", TaskLine.clean("Branch: main is green, merging next"))
        assertEquals("Kept: the old API for one release", TaskLine.clean("Kept: the old API for one release"))
        // A count, a fraction and a relative path are words, not noise.
        assertEquals("file:// 4/27 pages clean, see app/…/MachinesScreen.kt", TaskLine.clean("file:// 4/27 pages clean, see app/…/MachinesScreen.kt"))
    }

    @Test
    fun whereTheWorkWasKeptIsLeftOut() {
        assertEquals(
            "Merged: PR #14.",
            TaskLine.clean("Merged: https://github.com/makefunstuff/herdr-android/pull/14. Kept: ~/.herdr/worktrees/herdr-android/work-15-better-markdown-and-ui-ux-for (uncommitted changes)"),
        )
        assertEquals(
            "Merged to main on git.jurip.xyz as 96ea0eb (squash, pushed directly).",
            TaskLine.clean("Merged to main on git.jurip.xyz as 96ea0eb (squash, pushed directly). Kept: ~/.herdr/worktrees/homelab/work-4-strata-with-full-expert-quants (unmerged work on work/4-strata-with-full-expert-quants)"),
        )
        assertEquals(
            "Per-game POLISH.md and a README table; Strata left running.",
            TaskLine.clean("Per-game POLISH.md and a README table; Strata left running. Report: ~/.herdr-projects/reports/strata-test/5/report.md. Kept: ~/.herdr/worktrees/strata-test/work-5-polish (unmerged work on work/5-polish)"),
        )
    }

    @Test
    fun pathsShrinkToTheirName() {
        assertEquals("Edited Main.kt and its test", TaskLine.clean("Edited /home/jpl/src/app/Main.kt and its test"))
        assertEquals("Wrote report.md", TaskLine.clean("Wrote ~/.herdr-projects/reports/x/5/report.md"))
        // A route is not a path on the machine, and a link's target stays whole.
        assertEquals("Moved from /api/v1/users to /api/v2/users", TaskLine.clean("Moved from /api/v1/users to /api/v2/users"))
        assertEquals(
            "See the [report](~/.herdr-projects/reports/x/5/report.md) for details",
            TaskLine.clean("See the [report](~/.herdr-projects/reports/x/5/report.md) for details"),
        )
        // A link stays a link.
        assertEquals("See https://example.com/a/b for more", TaskLine.clean("See https://example.com/a/b for more"))
    }

    @Test
    fun aCardTakesTheFirstLineInPlainWords() {
        assertEquals("pi-extras v0.1.0 is published.", TaskLine.line("**pi-extras v0.1.0 is published.**\n\n- one\n- two"))
    }

    @Test
    fun whoIsTheAgentWithItsModel() {
        val workers = listOf("claude/claude-opus-5-5", "codex/gpt-6.1-sol", "pi/deepseek/deepseek-flash")
        assertEquals("claude · Opus 5.5", TaskLine.who("claude", workers))
        assertEquals("codex · GPT-6.1-Sol", TaskLine.who("codex", workers))
        assertEquals("pi · deepseek/deepseek-flash", TaskLine.who("pi", workers))
        assertEquals("gemini", TaskLine.who("gemini", workers))
        assertEquals("claude", TaskLine.who("claude", emptyList()))
        assertEquals("", TaskLine.who("", emptyList()))
    }
}
