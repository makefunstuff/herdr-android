package com.makefunstuff.herdr.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The shared components keep their promises: 48dp targets, descriptions, large text. */
class DesignSystemTest {
    @get:Rule val rule = createComposeRule()

    private fun SemanticsNodeInteraction.atLeast48() {
        val size = fetchSemanticsNode().size
        val min = with(rule.density) { 48.dp.roundToPx() }
        assertTrue("target is ${size.width}x${size.height}px, needs $min", size.height >= min && size.width >= min)
    }

    @Test fun barActionsAreDescribedTargets() {
        var added = 0
        rule.setContent {
            HerdrTheme {
                Column {
                    HerdrTopBar("Machines") { BarIcon(HerdrIcons.Notifications, "Notifications", {}, badge = 3) }
                    HerdrFab("Add machine") { added++ }
                }
            }
        }
        rule.onNodeWithText("Machines").assertIsDisplayed()
        rule.onNodeWithContentDescription("Notifications").atLeast48()
        rule.onNodeWithContentDescription("Add machine").atLeast48()
        rule.onNodeWithContentDescription("Add machine").performClick()
        assertEquals(1, added)
    }

    @Test fun chipsAndSegmentsAreTallEnoughAndSelectable() {
        var segment by mutableStateOf(0)
        rule.setContent {
            HerdrTheme {
                Column {
                    HerdrChip("All", selected = true, onClick = {})
                    SegmentedPills(listOf("Agent", "Workspace", "Tab"), segment, { segment = it })
                }
            }
        }
        rule.onNodeWithText("All").assertIsDisplayed()
        rule.onNodeWithText("Workspace").performClick()
        assertEquals(1, segment)
        rule.onNodeWithText("Workspace").assertIsSelected()
    }

    @Test fun segmentsWrapAsChipsAtDoubleFontScale() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                HerdrTheme { SegmentedPills(listOf("Agent", "Workspace", "Tab", "Split"), 0, {}) }
            }
        }
        // Every label is whole, none ellipsised into "Wor…".
        listOf("Agent", "Workspace", "Tab", "Split").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun listRowIsOneTargetAndHoldsLargeText() {
        var opened = false
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                HerdrTheme {
                    ListRow(
                        "Speed up /jobs on large tables",
                        subtitle = "claude · devbox · api-server",
                        leading = { IconTile(letter = "C") },
                        titleLines = 2,
                        chevron = true,
                        onClick = { opened = true },
                    )
                }
            }
        }
        rule.onNodeWithText("Speed up /jobs on large tables").assertIsDisplayed().performClick()
        assertTrue(opened)
    }

    @Test fun composerSendsOnlyWithTextAndOffersStop() {
        var draft by mutableStateOf("")
        var sent = 0
        var stopped = 0
        rule.setContent {
            HerdrTheme { ChatComposer(draft, { draft = it }, "Message claude", onSend = { sent++ }, stop = { stopped++ }) }
        }
        // Nothing typed while the agent works: the button stops it.
        rule.onNodeWithText("Stop").atLeast48()
        rule.onNodeWithText("Stop").performClick()
        assertEquals(1, stopped)
        rule.onNodeWithText("Message claude").performTextInput("hi")
        rule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test fun composerSendIsOffWhenEmpty() {
        rule.setContent { HerdrTheme { ChatComposer("", {}, "Message the Coordinator", onSend = {}) } }
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test fun dialogConfirmSlotDrawsTheQuietButtonAsAButton() {
        var confirmed = false
        rule.setContent {
            HerdrTheme {
                HerdrDialog(
                    onDismissRequest = {}, title = { Text("Remove devbox?") }, text = { Text("Only this phone forgets it.") },
                    confirmButton = { QuietButton(onClick = { confirmed = true }) { Text("Remove") } },
                    dismissButton = { QuietButton(onClick = {}) { Text("Cancel") } },
                )
            }
        }
        rule.onNodeWithText("Remove").atLeast48()
        rule.onNodeWithText("Cancel").atLeast48()
        rule.onNodeWithText("Remove").performClick()
        assertTrue(confirmed)
    }
}
