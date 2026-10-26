package com.makefunstuff.herdr.ui.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.material3.Text
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.ui.coordinator.CoordinatorComposer
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The chat composer both chats share: Send, Stop, sending, keys, drafts, and the Coordinator's two ways to send. */
@OptIn(ExperimentalTestApi::class)
class ComposerTest {
    @get:Rule val rule = createComposeRule()

    private fun SemanticsNodeInteraction.atLeast48() {
        val size = fetchSemanticsNode().size
        val min = with(rule.density) { 48.dp.roundToPx() }
        assertTrue("target is ${size.width}x${size.height}px, needs $min", size.height >= min && size.width >= min)
    }

    private val field get() = rule.onNode(hasSetTextAction())

    @Test fun coordinatorSendsOrSendsAndStartsAgents() {
        var draft by mutableStateOf("")
        var start by mutableStateOf(false)
        val sent = mutableListOf<Pair<String, Boolean>>()
        rule.setContent {
            HerdrTheme {
                CoordinatorComposer(
                    draft = draft, onDraft = { draft = it }, enabled = true, hint = "Tell the Coordinator…",
                    startAgents = start, onStartAgents = { start = it },
                    onSend = { delegate -> sent += draft to delegate; draft = ""; start = false },
                )
            }
        }
        // Plain Send: the message goes to the Coordinator, no agents started.
        field.performTextInput("Add a dark mode toggle")
        rule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(listOf("Add a dark mode toggle" to false), sent)

        // "Start agents" on: Send says so, and sends with the board's Tab.
        field.performTextInput("Fix the flaky test")
        rule.onNodeWithText("Start agents").assertIsOff().performClick()
        rule.onNodeWithText("Start agents").assertIsOn()
        rule.onNodeWithContentDescription("Send and start agents").assertIsEnabled().performClick()
        assertEquals("Fix the flaky test" to true, sent.last())

        // It is one message's choice: off again for the next one.
        rule.onNodeWithText("Start agents").assertIsOff()
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test fun startAgentsIsATouchTarget() {
        rule.setContent {
            HerdrTheme { CoordinatorComposer("hi", {}, true, "Tell the Coordinator…", false, {}, {}) }
        }
        rule.onNodeWithText("Start agents").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").atLeast48()
    }

    @Test fun attachIsOnTheLeftOfBothChats() {
        var picks = 0
        var uploading by mutableStateOf(false)
        rule.setContent {
            HerdrTheme {
                CoordinatorComposer(
                    "look at this", {}, true, "Tell the Coordinator…", false, {}, {},
                    onAttach = { picks++ }, uploading = uploading,
                )
            }
        }
        rule.onNodeWithContentDescription("Attach files").atLeast48()
        rule.onNodeWithContentDescription("Attach files").assertIsEnabled().performClick()
        assertEquals(1, picks)
        // While a file is copied, Send waits and the text stays.
        uploading = true
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Attach files").assertIsNotEnabled()
        rule.onNodeWithText("look at this").assertIsDisplayed()
    }

    @Test fun agentComposerTakesTheAttachButton() {
        var picks = 0
        rule.setContent {
            HerdrTheme {
                ChatComposer("", {}, "Message claude…", onSend = {}, leading = { ComposerIconButton(HerdrIcons.Plus, "Attach files") { picks++ } })
            }
        }
        rule.onNodeWithContentDescription("Attach files").performClick()
        assertEquals(1, picks)
    }

    @Test fun typingGoesAfterAnAttachedFilesMention() {
        var draft by mutableStateOf("")
        rule.setContent { HerdrTheme { ChatComposer(draft, { draft = it }, "Message claude…", onSend = {}) } }
        field.performTextInput("")
        draft = com.makefunstuff.herdr.core.host.Attachments.withMention(draft, "/h/a.png")
        rule.waitForIdle()
        field.performTextInput("what is this?")
        assertEquals("@/h/a.png what is this?", draft)
    }

    @Test fun sendingKeepsTheTextAndSendsOnce() {
        var sent = 0
        rule.setContent {
            HerdrTheme { ChatComposer("Fix the build", {}, "Message claude…", onSend = { sent++ }, sending = true, status = "Sending…") }
        }
        rule.onNodeWithText("Fix the build").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sending…").assertIsNotEnabled().atLeast48()
        rule.onNodeWithText("Sending…").assertIsDisplayed()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        assertEquals(0, sent)
    }

    /** A phone that records to /phone/voice/1.ogg. */
    private class FakeMic(val allowed: Boolean = true) : com.makefunstuff.herdr.core.chat.Recorder.Mic {
        var asked = 0
        var cancelled = 0
        override fun allowed() = allowed
        override fun ask() { asked++ }
        override fun settings() {}
        override fun start() = true
        override fun level() = 0.5f
        override fun stop() = "/phone/voice/1.ogg"
        override fun cancel() { cancelled++ }
    }

    @Test fun aVoiceMessageWaitsInTheComposerAndGoesWithSend() {
        var clip by mutableStateOf<com.makefunstuff.herdr.core.chat.Recorder.Clip?>(null)
        var sent = 0
        val mic = FakeMic()
        var now = 0L
        val recorder = com.makefunstuff.herdr.core.chat.Recorder(mic, { now }) { clip = it }
        rule.setContent {
            HerdrTheme {
                ChatComposer(
                    "", {}, "Message claude…", onSend = { sent++ },
                    // As the agent's chat does: the recording waits over the box until sent.
                    attachments = { clip?.let { Text("Voice message · " + com.makefunstuff.herdr.core.chat.Recorder.elapsed(it.millis)) } },
                    attached = clip != null,
                    recorder = recorder,
                )
            }
        }
        // A tap starts it: the box gives way to how loud you are, Send waits, and an X cancels.
        rule.onNodeWithContentDescription("Record a voice message").atLeast48()
        rule.onNodeWithContentDescription("Record a voice message").performClick()
        rule.onNodeWithContentDescription("Recording").assertIsDisplayed()
        rule.onNodeWithText("Tap the mic to finish").assertIsDisplayed()
        rule.onNodeWithContentDescription("Cancel voice message").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        // Its X throws it away.
        rule.onNodeWithContentDescription("Cancel voice message").performClick()
        rule.waitForIdle()
        assertEquals(1, mic.cancelled)
        assertEquals(null, clip)
        // Again, and the next tap finishes: the recording waits, and Send takes it without words.
        rule.onNodeWithContentDescription("Record a voice message").performClick()
        now = 4_000
        rule.onNodeWithContentDescription("Finish voice message").performClick()
        rule.waitForIdle()
        assertEquals(com.makefunstuff.herdr.core.chat.Recorder.Clip("/phone/voice/1.ogg", 4_000), clip)
        rule.onNodeWithText("Voice message · 0:04").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test fun aRefusedMicrophoneIsExplained() {
        val mic = FakeMic(allowed = false)
        val recorder = com.makefunstuff.herdr.core.chat.Recorder(mic, { 0L }) {}
        rule.setContent { HerdrTheme { ChatComposer("", {}, "Message claude…", onSend = {}, recorder = recorder) } }
        rule.onNodeWithContentDescription("Record a voice message").performClick()
        assertEquals(1, mic.asked)
        recorder.answered(granted = false, forever = true)
        rule.onNodeWithText("The microphone is off").assertIsDisplayed()
        rule.onNodeWithText("Open settings").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("The microphone is off").assertDoesNotExist()
    }

    @Test fun stopOnlyWhileNothingIsTyped() {
        var draft by mutableStateOf("")
        rule.setContent {
            HerdrTheme { ChatComposer(draft, { draft = it }, "Message claude…", onSend = {}, stop = {}) }
        }
        rule.onNodeWithContentDescription("Stop").atLeast48()
        field.performTextInput("wait")
        rule.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test fun enterIsANewLineAndCtrlEnterSends() {
        var draft by mutableStateOf("")
        var sent = 0
        rule.setContent {
            HerdrTheme { ChatComposer(draft, { draft = it }, "Message claude…", onSend = { sent++ }) }
        }
        field.performTextInput("first line")
        field.performKeyInput { pressKey(Key.Enter) }
        field.performTextInput("second line")
        assertEquals("first line\nsecond line", draft)
        assertEquals(0, sent)
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        assertEquals(1, sent)
        assertEquals("first line\nsecond line", draft)
    }

    @Test fun longDraftAtDoubleFontScaleKeepsSendInReach() {
        val long = (1..30).joinToString("\n") { "line $it of a long pasted log" }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                HerdrTheme { ChatComposer(long, {}, "Message claude…", onSend = {}) }
            }
        }
        rule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled().atLeast48()
    }

    @Test fun draftOutlivesLeavingTheChat() {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HerdrApp).container
        val key = DraftStore.agent("test-machine", "w9:p9")
        container.drafts.put(key, "")
        var open by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                HerdrTheme {
                    if (open) {
                        var draft by rememberDraft(key)
                        ChatComposer(draft, { draft = it }, "Message claude…", onSend = {})
                    }
                }
            }
        }
        field.performTextInput("half a thought")
        open = false
        rule.waitForIdle()
        assertEquals("half a thought", container.drafts.get(key))
        open = true
        rule.onNodeWithText("half a thought").assertIsDisplayed()
        container.drafts.put(key, "")
    }
}
