package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.LayoutNode
import com.makefunstuff.herdr.core.api.LayoutTemplates
import com.makefunstuff.herdr.core.api.PaneScroll
import com.makefunstuff.herdr.core.api.ResizeDirection
import com.makefunstuff.herdr.core.api.ScrollbackSearch
import com.makefunstuff.herdr.core.api.TextPoint
import com.makefunstuff.herdr.core.api.urlAt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers each method with canned JSON (or a herdr error) and records what was asked. */
private class FakeCaller(private val answer: (method: String, params: JsonObject) -> String) : ApiCaller {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        calls += method to params
        val reply = Json.parseToJsonElement(answer(method, params)).jsonObject
        (reply["error"] as? JsonObject)?.let {
            throw HerdrApiException(it["code"].toString().trim('"'), it["message"].toString().trim('"'))
        }
        return reply
    }
}

private val NESTED_EXPORT = """
    {"type":"layout_export","layout":{"workspace_id":"w1","tab_id":"w1:t6","zoomed":false,"focused_pane_id":"w1:pB",
     "root":{"type":"split","direction":"down","ratio":0.3,
       "first":{"type":"pane","pane_id":"w1:pB","cwd":"/home/u"},
       "second":{"type":"split","direction":"right","ratio":0.8,
         "first":{"type":"pane","pane_id":"w1:pC","cwd":"/home/u","label":"logs"},
         "second":{"type":"pane","pane_id":"w1:pD","cwd":"/home/u","command":["top"]}}}}}
""".trimIndent()

class ScrollMathTest {
    // 200 lines of scrollback over a 40-row screen, looking 50 lines back.
    private val scroll = PaneScroll(offsetFromBottom = 50, maxOffsetFromBottom = 164, viewportRows = 40)

    @Test
    fun mapsViewportRowsToAbsoluteRows() {
        assertEquals(204, scroll.totalRows)
        assertEquals(114, scroll.viewportTop)
        assertEquals(120, scroll.absoluteRow(6))
        assertEquals(6, scroll.viewportRow(120))
        assertNull(scroll.viewportRow(113))
        assertNull(scroll.viewportRow(154))
    }

    @Test
    fun offsetToShowKeepsVisibleRowsAndClamps() {
        assertEquals(50, scroll.offsetToShow(130))
        // A row further back lands a third of the way down the screen.
        assertEquals(77, scroll.offsetToShow(100))
        assertEquals(13, scroll.copy(offsetFromBottom = 77).viewportRow(100))
        // Near either end the offset stops at the scrollback's limits.
        assertEquals(164, scroll.offsetToShow(2))
        assertEquals(0, scroll.offsetToShow(203))
        assertEquals(39, scroll.copy(offsetFromBottom = 0).viewportRow(203))
    }
}

class TerminalExtrasApiTest {
    @Test
    fun scrollSearchSelectionAndLinksSendHerdrsParams() = runBlocking {
        val fake = FakeCaller { method, _ ->
            when (method) {
                "pane.scroll" -> """{"type":"pane_info","pane":{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1",
                    "scroll":{"offset_from_bottom":30,"max_offset_from_bottom":196,"viewport_rows":10}}}"""
                "pane.copy_motion" -> """{"type":"pane_copy_motion","pane_id":"w1:p1","cursor":{"row":5,"col":17},"content_revision":294}"""
                "pane.selection.read" -> """{"type":"pane_selection","pane_id":"w1:p1","text":"line 4\nline 5"}"""
                "pane.link.resolve" -> """{"type":"pane_link_resolved","regions":[{"row":38,"start_col":4,"end_col":31}]}"""
                "pane.link.activate" -> """{"type":"pane_link_activated","url":"https://example.com/path?q=1","handled":false}"""
                else -> error(method)
            }
        }
        val api = HerdrApi(fake)
        assertEquals(30, api.scrollPane("w1:p1", 30).scroll!!.offsetFromBottom)
        assertEquals("""{"pane_id":"w1:p1","offset_from_bottom":30}""", fake.calls.last().second.toString())

        val moved = api.copyMotion("w1:p1", TextPoint(5, 0), com.makefunstuff.herdr.core.api.CopyMotion.LineEnd)
        assertEquals(TextPoint(5, 17), moved.cursor)
        assertEquals(294, moved.contentRevision)
        assertEquals("""{"pane_id":"w1:p1","cursor":{"row":5,"col":0},"motion":"line_end"}""", fake.calls.last().second.toString())

        assertEquals("line 4\nline 5", api.readSelection("w1:p1", TextPoint(5, 0), TextPoint(6, 5), 294))
        assertEquals(
            """{"pane_id":"w1:p1","anchor":{"row":5,"col":0},"cursor":{"row":6,"col":5},"content_revision":294}""",
            fake.calls.last().second.toString(),
        )

        val region = api.resolveLink("w1:p1", 38, 8).single()
        assertEquals(4, region.startCol)
        assertEquals(31, region.endCol)
        val link = api.activateLink("w1:p1", 38, 8)
        assertEquals("https://example.com/path?q=1", link.url)
        assertEquals(false, link.handled)
        assertEquals("""{"pane_id":"w1:p1","viewport_row":38,"col":8}""", fake.calls.last().second.toString())
    }

    @Test
    fun searchStartsAtTheBottomStepsFromTheCurrentMatchAndRetriesStaleContent() = runBlocking {
        var revision = 294
        var stale = false
        val fake = FakeCaller { method, params ->
            when (method) {
                "pane.get" -> """{"type":"pane_info","pane":{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1",
                    "scroll":{"offset_from_bottom":0,"max_offset_from_bottom":164,"viewport_rows":40}}}"""
                "pane.copy_motion" -> """{"type":"pane_copy_motion","pane_id":"w1:p1","cursor":{"row":0,"col":6},"content_revision":$revision}"""
                "pane.copy_search" ->
                    if (stale || params["content_revision"].toString() != "$revision") {
                        stale = false
                        """{"error":{"code":"stale_content","message":"pane content changed"}}"""
                    } else {
                        val from = params["cursor"]!!.jsonObject["row"].toString().toInt()
                        val row = if (from == 203) 122 else 121
                        """{"type":"pane_copy_search","pane_id":"w1:p1","content_revision":$revision,
                          "matches":[{"start":{"row":13,"col":0},"end":{"row":13,"col":6}},{"start":{"row":$row,"col":0},"end":{"row":$row,"col":6}}],
                          "total":11,"current":1,"current_global":${if (row == 122) 10 else 9}}"""
                    }
                else -> error(method)
            }
        }
        val search = ScrollbackSearch(HerdrApi(fake), "w1:p1")
        val first = search.find("line 12")
        assertEquals(122, first.currentMatch!!.start.row)
        assertEquals(10, first.currentGlobal)
        val firstSearch = fake.calls.first { it.first == "pane.copy_search" }.second
        assertEquals("""{"row":203,"col":0}""", firstSearch["cursor"].toString())
        assertEquals("\"backward\"", firstSearch["direction"].toString())

        // New output arrives: the pinned revision goes stale and the search is retried.
        revision = 300
        stale = true
        val next = search.find("line 12")
        assertEquals(121, next.currentMatch!!.start.row)
        val stepped = fake.calls.last().second
        assertEquals("""{"row":122,"col":0}""", stepped["cursor"].toString())
        assertEquals("""{"start":{"row":122,"col":0},"end":{"row":122,"col":6}}""", stepped["previous"].toString())
        assertEquals("300", stepped["content_revision"].toString())
    }

    @Test
    fun otherSearchErrorsReachTheCaller() = runBlocking {
        val fake = FakeCaller { method, _ ->
            when (method) {
                "pane.copy_motion" -> """{"error":{"code":"pane_not_found","message":"pane w1:p9 not found"}}"""
                else -> error(method)
            }
        }
        val e = assertFailsWith<HerdrApiException> { ScrollbackSearch(HerdrApi(fake), "w1:p9").find("x") }
        assertEquals("pane_not_found", e.code)
    }

    @Test
    fun findsTheUrlUnderAColumn() {
        val line = "see https://example.com/x?q=1 and www.herdr.dev/docs for more"
        assertEquals("https://example.com/x?q=1", urlAt(line, 20))
        assertEquals("https://www.herdr.dev/docs", urlAt(line, 40))
        assertNull(urlAt(line, 3))
        assertNull(urlAt("no links here", 0))
        assertEquals("https://a.b/c", urlAt("open (https://a.b/c) now", 10))
    }

    @Test
    fun offsetToShowPutsARowAboutAThirdDown() {
        val scroll = PaneScroll(offsetFromBottom = 0, maxOffsetFromBottom = 500, viewportRows = 24)
        assertTrue(scroll.atBottom)
        assertEquals(0L, scroll.offsetToShow(510)) // on the live screen already
        assertEquals(208L, scroll.offsetToShow(300))
        assertEquals(485L, scroll.offsetToShow(23)) // far above: scroll back to it
    }
}

class LayoutTest {
    @Test
    fun exportDecodesTheSplitTree() = runBlocking {
        val api = HerdrApi(FakeCaller { _, _ -> NESTED_EXPORT })
        val layout = api.exportLayout("w1:t6")
        val root = assertIs<LayoutNode.Split>(layout.root)
        assertEquals("down", root.direction)
        assertEquals(0.3f, root.ratio)
        assertEquals(listOf("w1:pB", "w1:pC", "w1:pD"), root.panes().map { it.paneId })
        assertEquals(listOf("top"), root.panes()[2].command)
        assertEquals(listOf(true, false), root.pathTo("w1:pC"))
        assertEquals(0.8f, (root.at(listOf(true)) as LayoutNode.Split).ratio)
        assertNull(root.at(listOf(false, true)))
        assertEquals(0.5f, (root.withRatio(listOf(true), 0.5f).at(listOf(true)) as LayoutNode.Split).ratio)
    }

    @Test
    fun ratioAndApplySendTreesAndPaths() = runBlocking {
        val fake = FakeCaller { _, _ -> NESTED_EXPORT.replace("layout_export", "layout_split_ratio_set") }
        val api = HerdrApi(fake)
        api.setSplitRatio("w1:t6", listOf(true), 0.8f)
        assertEquals("""{"tab_id":"w1:t6","path":[true],"ratio":0.8}""", fake.calls.last().second.toString())

        val template = api.exportLayout("w1:t6").root.template()
        assertTrue(template.panes().all { it.paneId == null })
        api.applyLayout(template, workspaceId = "w1", label = "dev")
        assertEquals(
            """{"root":{"type":"split","direction":"down","ratio":0.3,"first":{"type":"pane","cwd":"/home/u"},""" +
                """"second":{"type":"split","direction":"right","ratio":0.8,"first":{"type":"pane","label":"logs","cwd":"/home/u"},""" +
                """"second":{"type":"pane","cwd":"/home/u","command":["top"]}}},"workspace_id":"w1","tab_label":"dev","focus":false}""",
            fake.calls.last().second.toString(),
        )
        api.applyLayout(template, replaceTabId = "w1:t6")
        assertEquals("\"w1:t6\"", fake.calls.last().second["tab_id"].toString())
    }

    @Test
    fun templatesRoundTripThroughJson() {
        val root = HerdrApi(FakeCaller { _, _ -> NESTED_EXPORT }).let { runBlocking { it.exportLayout("w1:t6").root } }
        val text = LayoutTemplates.encode(root.template())
        assertTrue("pane_id" !in text, text)
        assertEquals(root.template(), LayoutTemplates.decode(text))
        // Tolerates the extra fields herdr sends and the shape a pasted template may carry.
        val pasted = """{"type":"split","direction":"right","ratio":0.5,"first":{"type":"pane"},"second":{"type":"pane","command":["htop"]},"unknown":1}"""
        val node = assertIs<LayoutNode.Split>(LayoutTemplates.decode(pasted))
        assertEquals(0.5f, node.ratio)
        assertEquals(listOf("htop"), node.panes()[1].command)
    }

    @Test
    fun resizeAndPaneLayout() = runBlocking {
        val layout = """{"workspace_id":"w1","tab_id":"w1:t5","area":{"x":0,"y":0,"width":120,"height":40},"focused_pane_id":"w1:p9",
            "panes":[{"pane_id":"w1:p9","focused":true,"rect":{"x":0,"y":0,"width":60,"height":40}},
                     {"pane_id":"w1:pA","rect":{"x":60,"y":0,"width":60,"height":40}}],
            "splits":[{"id":"split_0_root","direction":"right","ratio":0.5,"rect":{"x":0,"y":0,"width":120,"height":40}}]}"""
        val fake = FakeCaller { method, _ ->
            when (method) {
                "pane.resize" -> """{"type":"pane_resize","resize":{"changed":false,"reason":"unchanged","pane_id":"w1:p9","focused_pane_id":"w1:p9","layout":$layout}}"""
                "pane.layout" -> """{"type":"pane_layout","layout":$layout}"""
                else -> error(method)
            }
        }
        val api = HerdrApi(fake)
        val resized = api.resizePane("w1:p9", ResizeDirection.Right, 0.1f)
        assertEquals(false, resized.changed)
        assertEquals("unchanged", resized.reason)
        assertEquals("""{"pane_id":"w1:p9","direction":"right","amount":0.1}""", fake.calls.last().second.toString())
        val tab = api.paneLayout("w1:p9")
        assertEquals(0.5f, tab.splits.single().ratio)
        assertEquals(2, tab.panes.size)
    }

    @Test
    fun listsEverySplitWithItsPath() {
        val root = HerdrApi(FakeCaller { _, _ -> NESTED_EXPORT }).let { runBlocking { it.exportLayout("w1:t6").root } }
        val splits = root.splits()
        assertEquals(2, splits.size)
        assertEquals(emptyList(), splits[0].path)
        assertEquals("down", splits[0].direction)
        assertEquals(false, splits[0].sideBySide)
        assertEquals(0.3f, splits[0].ratio)
        assertEquals(listOf(true), splits[1].path)
        assertEquals("right", splits[1].direction)
        assertTrue(splits[1].sideBySide)
    }
}
