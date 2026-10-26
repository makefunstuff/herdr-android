package com.makefunstuff.herdr.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.chat.Recorder
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/** Whether the composer records now: the box gives way to how loud you are. */
val Recorder.State.recording: Boolean get() = this is Recorder.State.Recording

/**
 * The mic in the composer's row of controls, beside Send. Held, it records until the finger lets go
 * (sliding [CANCEL_SLIDE] to the left cancels); a quick tap starts it, and the next tap finishes.
 * Idle it is a dim glyph like the attach button; recording, a red circle that swells with your
 * voice (a square in it while a tap's recording waits for the next tap). One place for both, so a
 * holding finger never loses it.
 */
@Composable
fun ComposerMic(recorder: Recorder, state: Recorder.State, enabled: Boolean) {
    val c = Herdr.colors
    val view = LocalView.current
    val rec = state as? Recorder.State.Recording
    val halo by animateDpAsState(if (rec != null) 32.dp + 14.dp * rec.level else 32.dp, tween(120), label = "halo")
    val description = when {
        rec != null && rec.held -> "Recording, let go to finish"
        rec != null -> "Finish voice message"
        else -> "Record a voice message"
    }
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .pointerInput(recorder, enabled) {
                if (!enabled) return@pointerInput
                val cancelAt = CANCEL_SLIDE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    view.confirmTap()
                    recorder.down()
                    var upAt: Long? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            upAt = change.uptimeMillis
                            change.consume()
                            break
                        }
                        recorder.slide(cancel = change.position.x - down.position.x < -cancelAt)
                        change.consume()
                    }
                    // A gesture taken away (another window) counts as a long hold: what was said is kept.
                    recorder.up(heldFor = upAt?.let { it - down.uptimeMillis } ?: Long.MAX_VALUE)
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                if (state.recording) stateDescription = "Recording"
                // TalkBack's double tap: a tap, which starts it and, the next time, finishes it.
                if (enabled) onClick(label = if (rec != null) "Finish" else "Record") { recorder.down(); recorder.up(0); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            rec != null -> {
                // How loud you are: a soft ring round the circle.
                Box(Modifier.size(halo).background(c.red.copy(alpha = 0.22f), CircleShape))
                Box(Modifier.size(32.dp).background(c.red, CircleShape), contentAlignment = Alignment.Center) {
                    if (rec.held) Icon(HerdrIcons.Mic, null, tint = c.ground, modifier = Modifier.size(16.dp))
                    else Box(Modifier.size(10.dp).background(c.ground, RoundedCornerShape(2.dp)))
                }
            }
            else -> Icon(HerdrIcons.Mic, null, tint = if (enabled) c.muted else c.off, modifier = Modifier.size(20.dp))
        }
    }
}

/** How far to the left a holding finger slides to cancel. */
val CANCEL_SLIDE = 96.dp

/**
 * How loud you are, in the message box's place while it records: a row of bars that run from right
 * to left, the newest on the right, each as loud as you were then.
 */
@Composable
fun RecordingLevel(level: Float) {
    val c = Herdr.colors
    var history by remember { mutableStateOf(List(LEVEL_BARS) { 0f }) }
    val latest by rememberUpdatedState(level)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(90)
            history = history.drop(1) + latest
        }
    }
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = Space.l, end = Space.l, top = Space.m, bottom = Space.xs)
            .semantics { contentDescription = "Recording" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        history.forEach { l ->
            Box(Modifier.weight(1f).height(3.dp + 26.dp * l).background(if (l > 0.02f) c.red.copy(alpha = 0.55f + 0.45f * l) else c.line, RoundedCornerShape(2.dp)))
        }
    }
}

private const val LEVEL_BARS = 36

/**
 * The left of the controls while it records: a pulsing red dot and how long you've spoken, then what
 * letting go does ("‹ Slide to cancel", "Let go to cancel" in red), or, for a tapped recording, an X
 * to cancel first. Ticks the [recorder] along ([Recorder.tick]).
 */
@Composable
fun RowScope.RecordingInfo(recorder: Recorder, state: Recorder.State) {
    val c = Herdr.colors
    val rec = state as? Recorder.State.Recording
    var now by remember { mutableLongStateOf(recorder.clock()) }
    LaunchedEffect(recorder) {
        while (true) {
            now = recorder.clock()
            recorder.tick()
            kotlinx.coroutines.delay(100)
        }
    }
    val started = rec?.startedAt ?: now
    val until = now
    if (rec != null && !rec.held) ComposerIconButton(HerdrIcons.X, "Cancel voice message", onClick = recorder::cancel)
    else Spacer(Modifier.width(Space.l))
    val pulse = rememberInfiniteTransition(label = "recording")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Box(Modifier.size(8.dp).alpha(if (rec != null) alpha else 0.5f).background(c.red, CircleShape))
    Spacer(Modifier.width(Space.s))
    Text(Recorder.elapsed(until - started), style = CodeStyle, color = c.strong, maxLines = 1)
    val hint = when {
        rec == null -> null
        rec.cancelling -> "Let go to cancel"
        rec.held -> "‹ Slide to cancel"
        else -> "Tap the mic to finish"
    }
    if (hint != null) Text(
        hint,
        style = MaterialTheme.typography.labelMedium,
        color = if (rec?.cancelling == true) c.red else c.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = Space.m).semantics { liveRegion = LiveRegionMode.Polite },
    ) else Spacer(Modifier.weight(1f))
}

/**
 * Over the composer when the mic can't be used: the microphone refused (why it is needed, and Allow,
 * or Open settings once Android won't ask again), or what went wrong while it heard.
 */
@Composable
fun MicNotice(recorder: Recorder, state: Recorder.State) {
    val c = Herdr.colors
    val shape = RoundedCornerShape(Radius.xl)
    val noMic = state as? Recorder.State.NoMic
    Surface(
        color = c.card,
        border = BorderStroke(1.dp, c.line),
        shape = shape,
        modifier = Modifier.fillMaxWidth().shadow(6.dp, shape, ambientColor = c.shadow, spotColor = c.shadow),
    ) {
        Column(Modifier.padding(start = Space.l, end = Space.s, top = Space.m)) {
            Row(Modifier.padding(end = Space.s)) {
                Icon(
                    if (noMic != null) HerdrIcons.MicOff else HerdrIcons.CircleAlert, null,
                    tint = if (noMic != null) c.muted else c.notice,
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                )
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    if (noMic != null) {
                        Text("The microphone is off", style = MaterialTheme.typography.bodyLarge, color = c.strong)
                        Text(
                            if (noMic.forever) "Voice messages need it to record you. Allow it under Permissions in the phone's settings for herdr."
                            else "Voice messages need it to record you. The recording goes to the agent as an audio file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                        )
                    } else {
                        Text((state as? Recorder.State.Failed)?.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.text)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when {
                    noMic == null -> QuietButton(onClick = recorder::dismiss) { Text("OK") }
                    noMic.forever -> {
                        QuietButton(onClick = recorder::dismiss) { Text("Not now") }
                        QuietButton(onClick = recorder::settings, color = c.accent) { Text("Open settings") }
                    }
                    else -> {
                        QuietButton(onClick = recorder::dismiss) { Text("Not now") }
                        QuietButton(onClick = recorder::askAgain, color = c.accent) { Text("Allow") }
                    }
                }
            }
        }
    }
}
