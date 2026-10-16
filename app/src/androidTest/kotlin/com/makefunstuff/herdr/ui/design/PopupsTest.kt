package com.makefunstuff.herdr.ui.design

import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PopupsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun dialogConfirmFires() {
        var n = 0
        rule.setContent {
            HerdrTheme {
                HerdrDialog(
                    onDismissRequest = {}, title = { Text("Remove?") }, text = { Text("Gone for good") },
                    confirmButton = { TextButton(onClick = { n++ }) { Text("Remove") } },
                )
            }
        }
        rule.onNodeWithText("Gone for good").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        assertEquals(1, n)
    }

    @Test fun menuItemClicksAndCloses() {
        var open by mutableStateOf(true)
        var hit = false
        rule.setContent {
            HerdrTheme {
                HerdrMenu(open, { open = false }) { HerdrMenuItem({ Text("Refresh") }, { hit = true; open = false }) }
            }
        }
        rule.onNodeWithText("Refresh").performClick()
        rule.waitForIdle()
        assertEquals(true, hit)
        rule.onNodeWithText("Refresh").assertDoesNotExistCompat()
    }

    /** A sheet longer than the screen scrolls, and a height-bounded list inside it doesn't crash (round 4 bug). */
    @Test fun longSheetScrollsAndHoldsABoundedList() {
        var hit = false
        rule.setContent {
            HerdrTheme {
                HerdrSheet(onDismissRequest = {}) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        androidx.compose.ui.Modifier.heightIn(max = 200.dp),
                    ) { items(30) { Text("Listed $it") } }
                    repeat(30) { i -> ActionRow("Row $i") }
                    ActionRow("Last row", onClick = { hit = true })
                }
            }
        }
        rule.onNodeWithText("Listed 0").assertIsDisplayed()
        rule.onNodeWithText("Last row").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(true, hit)
    }

    /** Manage rows are full-width targets of at least 48dp. */
    @Test fun actionRowIsATallEnoughTarget() {
        rule.setContent { HerdrTheme { ActionRow("Rename", detail = "Give it a name", chevron = true, onClick = {}) } }
        rule.onNodeWithText("Rename", useUnmergedTree = true).assertIsDisplayed()
        val h = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[0].fetchSemanticsNode().size.height
        val min = with(rule.density) { 48.dp.roundToPx() }
        assert(h >= min) { "row is $h px, needs $min" }
    }

    @Test fun toastShowsAndErrorStateRetries() {
        var retried = false
        rule.setContent {
            HerdrTheme {
                androidx.compose.foundation.layout.Box {
                    ErrorState("Couldn't load", "Connection refused", onRetry = { retried = true })
                    ToastHost()
                    StatusBadge("Needs you", androidx.compose.ui.graphics.Color.Red)
                }
            }
        }
        rule.runOnIdle { Toasts.show("Saved") }
        rule.onNodeWithText("Saved").assertIsDisplayed()
        rule.onNodeWithText("Needs you").assertIsDisplayed()
        // A tap on the toast dismisses it, so it no longer covers what is under it.
        rule.onNodeWithText("Saved").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Saved").assertDoesNotExist()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(true, retried)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = this.assertDoesNotExist()
