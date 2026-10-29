package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.shepherdr.OutgoingLine
import com.makefunstuff.herdr.core.shepherdr.ShepherdrText
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpaceChatTest {
    private fun fixture(name: String) = javaClass.getResource("/shepherdr/$name")!!.readText()

    private fun chat(json: String) = (V2Events.parse("""{"type":"chat","chat":$json}""") as V2Event.Chat).chat

    @Test
    fun shepherdrsLinesAndAgentTagsAreNoOnesWords() {
        assertEquals("", ShepherdrText.clean("""<shepherdr><news task="HA-1" event="merged">HA-1 merged</news></shepherdr>"""))
        assertEquals("Merged it.", ShepherdrText.clean("""<shepherdr><go>Go</go></shepherdr>""" + "\nMerged it."))
        assertEquals("", ShepherdrText.clean("<no-reply>heartbeat: nothing to do</no-reply>"))
        assertEquals("", ShepherdrText.clean("`<no-reply>x</no-reply>`"))
        assertEquals("Yes, on it.", ShepherdrText.clean("""<react emoji="👀" to="Can you?"/>""" + "\n" + """<reply to="Can you?"/>""" + "\nYes, on it."))
        // Words that only mention a tag stay.
        assertEquals("Use <reply to=\"…\"/> to quote", ShepherdrText.clean("Use <reply to=\"…\"/> to quote"))
    }

    @Test
    fun theLiveChatAsTheScreenShowsIt() {
        val c = (V2Events.parse(fixture("chat-event.json")) as V2Event.Chat).chat
        val items = SpaceChat.items(c.key, c)
        // The turn's tool steps show nothing.
        assertEquals(3, items.size)
        assertTrue(items.all { it is SpaceChatItem.Words })
        assertEquals(listOf(true, false, false), items.map { (it as SpaceChatItem.Words).you })
    }

    @Test
    fun cardsQuestionsFilesAndArtifacts() {
        val c = chat(
            """{"key":"p:m:~","messages":[
              {"id":"c1","who":"agent","text":"HA-1 is ready to merge","card":{"id":"c1","task":"HA-1","status":"ready","text":"is ready to merge","pr":{"number":59,"url":"https://x/59"}}},
              {"id":"c2","who":"agent","text":"HA-9 needs you","card":{"id":"c2","task":"HA-9","status":"need","text":"needs you: Plan to approve",
                "ask":{"chat":"HA-9/Agent","open":true,"questions":[{"id":"q9","title":"Plan for HA-9","kind":"Plan","steps":["a","b"],"choices":[{"label":"Approve"},{"label":"Send back"}],"noteRequiredFor":1,"to":"HA-9"}]}}},
              {"id":"c3","who":"agent","text":"main is red","card":{"id":"c3","red":"main","text":"main is red on GitHub"}},
              {"id":"m4","who":"agent","text":"CI failed once.","question":{"id":"f1","title":"Rerun CI?","choices":[{"label":"Rerun it"},{"label":"Skip"}],"to":"x"}},
              {"id":"m5","who":"agent","question":{"id":"f0","title":"Old?","choices":[{"label":"Yes"},{"label":"No"}],"to":"x","sent":1}},
              {"id":"m6","who":"you","answered":"Answered “Old?”"},
              {"id":"m7","who":"you","text":"Here's the log","file":{"name":"log.txt","path":"~/.shepherdr/inbox/log.txt"},"newSession":true},
              {"id":"m8","who":"agent","text":"The board","artifact":{"id":"wk3n","v":2}},
              {"id":"m9","who":"agent","text":"<no-reply>news</no-reply>"},
              {"id":"m10","who":"you","text":"Yes","reply":{"id":"m4","who":"agent","text":"CI failed once."},"reactions":[{"emoji":"👍","by":"Coordinator"}]}
            ]}""",
        )
        val items = SpaceChat.items(c.key, c)
        val ready = items[0] as SpaceChatItem.Update
        assertEquals("HA-1", ready.task)
        assertEquals(WorkStatus.Ready, ready.status)
        assertEquals("is ready to merge", ready.text)
        assertEquals(59, ready.pr!!.number)
        val need = items[1] as SpaceChatItem.Update
        // Its task's plan, answered from this space.
        assertEquals("q9", need.asks.single().question.id)
        assertEquals("HA-9/Agent", need.asks.single().chat)
        assertEquals("p:m:~", need.asks.single().space)
        val red = items[2] as SpaceChatItem.Update
        assertEquals("main", red.red)
        assertNull(red.status)
        val q = items[3] as SpaceChatItem.Question
        assertEquals("CI failed once.", q.intro)
        assertEquals("p:m:~", q.card.chat)
        assertNull(q.answer)
        assertEquals("No", (items[4] as SpaceChatItem.Question).answer)
        assertEquals("Answered “Old?”", (items[5] as SpaceChatItem.Note).text)
        assertTrue(items[6] is SpaceChatItem.NewSession)
        assertEquals("Here's the log", (items[7] as SpaceChatItem.Words).text)
        assertEquals("log.txt", (items[8] as SpaceChatItem.File).name)
        assertEquals("wk3n", (items[9] as SpaceChatItem.Artifact).artifact)
        // m9 says nothing.
        val yes = items[10] as SpaceChatItem.Words
        assertEquals("m4", yes.reply!!.id)
        assertEquals("👍", yes.reactions.single().emoji)
        assertEquals(11, items.size)
        // The thread's lines leave the hidden reply out too.
        assertTrue(V2Board.thread(c.key, c).lines.none { it.text.contains("no-reply") })
    }

    @Test
    fun whatYouSentWaitsUntilItsMessageComesBack() {
        val before = chat("""{"key":"k","messages":[{"id":"m1","who":"agent","text":"Hi"}]}""")
        val out = listOf(OutgoingLine("c1", "Add a task"))
        val waiting = SpaceChat.items("k", before, out).last() as SpaceChatItem.Words
        assertEquals("cid:c1", waiting.id)
        assertTrue(waiting.waiting && waiting.you)
        assertEquals(out, SpaceChat.stillOutgoing(before, out))

        val after = chat("""{"key":"k","messages":[{"id":"m1","who":"agent","text":"Hi"},{"id":"m2","who":"you","text":"Add a task","cid":"c1"}]}""")
        assertEquals(2, SpaceChat.items("k", after, out).size)
        assertTrue(SpaceChat.stillOutgoing(after, out).isEmpty())

        val failed = chat("""{"key":"k","messages":[],"failed":[{"cid":"c1","error":"The coordinator isn't running"}]}""")
        val f = SpaceChat.items("k", failed, out).single() as SpaceChatItem.Words
        assertEquals("The coordinator isn't running", f.failed)
        assertTrue(!f.waiting)
    }
}
