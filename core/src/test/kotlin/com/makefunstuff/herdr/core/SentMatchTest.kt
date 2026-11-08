package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.SentMatch
import com.makefunstuff.herdr.core.chat.SentMatch.Pending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentMatchTest {
    private val msg = "I still don't get where why and how I can use pi durable"
    private fun open(p: List<Pending>, seen: List<String>) = SentMatch.unconfirmed(p) { SentMatch.count(it.text, seen) }

    @Test fun transcriptConfirms() {
        assertEquals(1, open(listOf(Pending(msg, 0)), emptyList()).size)
        assertEquals(0, open(listOf(Pending(msg, 0)), listOf(msg)).size)
    }

    @Test fun whitespaceMarkdownAndAppendedBoardLine() {
        assertTrue(SentMatch.same("fix  the\nbug in *login* flow please", "fix the bug in login flow please\n\n[sent with tab]"))
        assertTrue(SentMatch.same(msg, "$msg\n[sent with tab]"))
    }

    @Test fun truncation() {
        val long = "word ".repeat(100)
        assertTrue(SentMatch.same(long, long.take(300)))
    }

    @Test fun shortDifferentMessagesDontMatch() {
        assertFalse(SentMatch.same("ok", "ok thanks a lot for that"))
        assertFalse(SentMatch.same("yes", "no"))
    }

    @Test fun identicalMessagesStayTwo() {
        val two = listOf(Pending(msg, 0), Pending(msg, 0))
        assertEquals(2, open(two, emptyList()).size)
        assertEquals(1, open(two, listOf(msg)).size)
        assertEquals(0, open(two, listOf(msg, msg)).size)
    }

    @Test fun earlierIdenticalMessageDoesNotConfirm() {
        // Same text was said before; only a second copy confirms the new one.
        assertEquals(1, open(listOf(Pending(msg, 1)), listOf(msg)).size)
        assertEquals(0, open(listOf(Pending(msg, 1)), listOf(msg, msg)).size)
    }

    @Test fun reconnectReplayDoesNotConfirmOrDuplicate() {
        // A reconnect re-reads the same transcript: the count is unchanged, nothing is added or lost.
        val seen = listOf(msg)
        assertEquals(1, open(listOf(Pending(msg, 1)), seen).size)
        assertEquals(1, open(open(listOf(Pending(msg, 1)), seen), seen).size)
    }

    @Test fun screenSource() {
        val screen = "> I still don't get where why and\n  how I can use pi durable"
        assertEquals(1, SentMatch.countOnScreen(msg, screen.replace("\n  ", " ")))
        assertEquals(0, open(listOf(Pending(msg, 0)).also { }, emptyList()).size - 1)
        assertEquals(0, SentMatch.unconfirmed(listOf(Pending(msg, 0))) { SentMatch.countOnScreen(it.text, "> $msg\nreply") }.size)
        assertEquals(1, SentMatch.unconfirmed(listOf(Pending(msg, 1))) { SentMatch.countOnScreen(it.text, "> $msg\nreply") }.size)
    }

    @Test fun reviewProbes() {
        assertTrue(SentMatch.same("yes go ahead", "yes go ahead\n[sent with tab]"))
        assertFalse(SentMatch.same("Please look at task 5 now", "Please look at task 5 now and also task 6"))
        assertEquals(1, SentMatch.countOnScreen(msg, "> I still don't get where why an\nd how I can use pi durable"))
    }

    @Test fun slidingWindowStillConfirms() {
        val p = Pending("yes", 3, sentAt = 1_000)
        // An old copy scrolled out as the new one came in: 3 before, 3 now, but never fewer-and-stuck.
        assertEquals(Int.MAX_VALUE, SentMatch.slid(p, 2, now = 2_000))
        assertEquals(4, SentMatch.slid(p, 4, now = 2_000))
        assertEquals(3, SentMatch.slid(p, 3, now = 2_000))
        // Same count a while later (one in, one out): confirmed by age.
        assertEquals(Int.MAX_VALUE, SentMatch.slid(p, 3, now = 40_000))
        assertEquals(0, SentMatch.unconfirmed(listOf(p)) { SentMatch.slid(it, 3, 40_000) }.size)
        assertEquals(1, SentMatch.unconfirmed(listOf(p)) { SentMatch.slid(it, 3, 2_000) }.size)
    }

    // Issue #100: a message with pictures stayed on "Sending…" when the agent recorded its pictures its own way.
    private val up = "/home/demo/.cache/herdr-android/uploads/20261108-101204-a1b2"
    private val words = "The Board card jumps when a machine reconnects. Keep it where it was?"

    @Test fun pictureMessageAsTheMentionsSent() {
        val sent = "@$up/shot.png $words"
        assertEquals(0, open(listOf(Pending(sent, 0)), listOf("hi", sent)).size)
    }

    @Test fun pictureMessageAsClaudeCodesImagePlaceholders() {
        val sent = "@$up/one.png @$up/two.png $words"
        assertTrue(SentMatch.same(sent, "[Image #1] [Image #2] $words"))
        assertTrue(SentMatch.same(sent, "$words [Image #3]"))
        assertEquals(0, open(listOf(Pending(sent, 0)), listOf("[Image #1] [Image #2] $words")).size)
    }

    @Test fun pictureMessageAsWordsBesideImageBlocks() {
        // Codex and pi keep the pictures as image blocks: the transcript's text is only the words.
        val sent = "@$up/one.png $words"
        assertTrue(SentMatch.same(sent, words))
        assertEquals(0, open(listOf(Pending(sent, 0)), listOf(words)).size)
        assertTrue(SentMatch.same(sent, "[image 1 1024x768 PNG] $words"))
    }

    @Test fun picturesOnly() {
        val sent = "@$up/one.png @$up/two.png"
        assertTrue(SentMatch.same(sent, "[Image #1] [Image #2]"))
        assertTrue(SentMatch.same(sent, ""))
        assertTrue(SentMatch.same(sent, sent))
        // Said before: a picture message already there doesn't confirm a new one, a second one does.
        assertEquals(1, open(listOf(Pending(sent, 1)), listOf("[Image #1]")).size)
        assertEquals(0, open(listOf(Pending(sent, 1)), listOf("[Image #1]", "[Image #2]")).size)
    }

    @Test fun picturesAfterACommand() {
        val sent = "/review @$up/one.png look at this"
        assertTrue(SentMatch.same(sent, "/review [Image #1] look at this"))
    }

    @Test fun otherWordsWithPicturesDontMatch() {
        val sent = "@$up/one.png $words"
        assertFalse(SentMatch.same(sent, "[Image #1] something else entirely"))
        assertFalse(SentMatch.same(sent, "[Image #1]"))
        assertFalse(SentMatch.same("@$up/one.png", "a message without pictures"))
        // Without pictures nothing changes: a bracketed word is not a picture.
        assertFalse(SentMatch.same("look", "[Image #1] look"))
    }

    @Test fun pictureMessageOnScreen() {
        val sent = "@$up/one.png $words"
        assertEquals(1, SentMatch.countOnScreen(sent, "> [Image #1] $words\n\n● Looking."))
        assertEquals(1, SentMatch.countOnScreen(sent, "> $sent"))
    }
}
