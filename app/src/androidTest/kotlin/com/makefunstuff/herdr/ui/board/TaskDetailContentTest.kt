package com.makefunstuff.herdr.ui.board

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.makefunstuff.herdr.core.board.Attempt
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.TaskDetails
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The plan and pick-one sections of the full-screen task detail, drawn from fixture data. */
class TaskDetailContentTest {
    @get:Rule val rule = createComposeRule()

    private fun task(stage: String, state: String) =
        BoardTask("r/7", "AND-7", "Add a retry button", state, stage, "", "pi", "", null, null, false, "", false, false, 0, null, false)

    private fun details(plan: String = "", attempts: List<Attempt> = emptyList()) = TaskDetails(
        "AND-7", "Add a retry button", "", "", "Desc", null, emptyList(), "pi", emptyList(), false, "", plan, "", "", "",
        null, null, "", attempts, "A is smaller; B has more tests.", emptyList(),
    )

    private fun shot(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun planReadyShowsPlanAndApprovePlan() {
        var got: BoardAction? = null
        rule.setContent {
            HerdrTheme {
                TaskDetailContent(
                    task("plan", "blocked"), details(plan = "## Steps\n1. Add the button\n2. Wire it to `retry`\n\n- [ ] tests"),
                    null, null, onBack = {}, onAction = { got = it }, onPick = {}, onMore = {},
                )
            }
        }
        rule.onNodeWithText("Needs you").assertIsDisplayed()
        rule.onNodeWithText("Plan").assertIsDisplayed()          // section is open for a plan waiting for approval
        rule.onNodeWithText("Add the button").assertIsDisplayed()
        shot("plan-ready")
        rule.onNodeWithText("Approve plan").performClick()
        assertEquals(BoardAction.ApprovePlan, got)
    }

    @Test fun pickOneListsAttemptsWithPickButtons() {
        var picked = ""
        rule.setContent {
            HerdrTheme {
                TaskDetailContent(
                    task("pick", "blocked"),
                    details(attempts = listOf(Attempt("A", "claude", "passed", "+40 -2", "", ""), Attempt("B", "codex", "passed", "+90 -10", "", ""))),
                    null, null, onBack = {}, onAction = {}, onPick = { picked = it }, onMore = {},
                )
            }
        }
        rule.onNodeWithText("Pick one of 2").assertIsDisplayed()
        rule.onNodeWithText("A is smaller; B has more tests.").assertIsDisplayed()
        shot("pick-one")
        rule.onNodeWithText("Pick B").performScrollTo().performClick()
        assertEquals("B", picked)
    }

    @Test fun planSectionIsCollapsedWhenNotWaitingForApproval() {
        rule.setContent {
            HerdrTheme {
                TaskDetailContent(task("work", "working"), details(plan = "Secret plan body"), null, null, {}, {}, {}, {})
            }
        }
        rule.onNodeWithText("Plan").assertIsDisplayed()
        rule.onNodeWithText("Secret plan body").assertDoesNotExist()
    }
}

