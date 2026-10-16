package com.makefunstuff.herdr.ui.design

import androidx.compose.material3.Text
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
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = this.assertDoesNotExist()
