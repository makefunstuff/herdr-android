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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/**
 * A voice message in [ChatComposer]: [dictation] hears you, and [take] is how long you spoke (ms)
 * while its words wait in the draft for you to check them; [onDropTake] takes those words out again.
 */
class ComposerVoice(val dictation: Dictation, val take: Long?, val onDropTake: () -> Unit)

/** Whether the composer hears you now (or makes text of the last words): the box gives way to what it hears. */
val Dictation.State.hearing: Boolean get() = this is Dictation.State.Recording || this is Dictation.State.Finishing

/**
 * The mic in the composer's row of controls, beside Send. Held, it hears until the finger lets go
 * (sliding [CANCEL_SLIDE] to the left cancels); a quick tap starts it, and the next tap finishes.
 * Idle it is a dim glyph like the attach button; hearing, a red circle that swells with your
 * voice (a square in it while a tap's recording waits for the next tap), and a ring while the last
 * words become text. One place for all of these, so a holding finger never loses it.
 */
@Composable
fun ComposerMic(dictation: Dictation, state: Dictation.State, enabled: Boolean) {
    val c = Herdr.colors
    val view = LocalView.current
    val rec = state as? Dictation.State.Recording
    val halo by animateDpAsState(if (rec != null) 32.dp + 14.dp * rec.level else 32.dp, tween(120), label = "halo")
    val description = when {
        rec != null && rec.held -> "Recording, let go to finish"
        rec != null -> "Finish voice message"
        state is Dictation.State.Finishing -> "Transcribing"
        else -> "Record a voice message"
    }
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .pointerInput(dictation, enabled) {
                if (!enabled) return@pointerInput
                val cancelAt = CANCEL_SLIDE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    view.confirmTap()
                    dictation.down()
                    var upAt: Long? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            upAt = change.uptimeMillis
                            change.consume()
                            break
                        }
                        dictation.slide(cancel = change.position.x - down.position.x < -cancelAt)
                        change.consume()
                    }
                    // A gesture taken away (another window) counts as a long hold: what was said is kept.
                    dictation.up(heldFor = upAt?.let { it - down.uptimeMillis } ?: Long.MAX_VALUE)
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                if (state.hearing) stateDescription = "Recording"
                // TalkBack's double tap: a tap, which starts it and, the next time, finishes it.
                if (enabled) onClick(label = if (rec != null) "Finish" else "Record") { dictation.down(); dictation.up(0); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            state is Dictation.State.Finishing -> Box(Modifier.size(32.dp).background(c.red, CircleShape), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(14.dp), color = c.ground, strokeWidth = 2.dp, trackColor = c.ground.copy(alpha = 0.25f))
            }
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
 * What the composer hears, in the message box's place: the words as they come (the newest end of a
 * long stretch), or "Listening…" before the first.
 */
@Composable
fun HeardText(text: String) {
    val c = Herdr.colors
    val shown = if (text.length > HEARD_MAX) "…" + text.takeLast(HEARD_MAX).trimStart() else text
    Box(
        Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.m + Space.xxs, bottom = Space.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            shown.ifEmpty { "Listening…" },
            style = MaterialTheme.typography.bodyLarge,
            color = if (shown.isEmpty()) c.muted else c.text,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val HEARD_MAX = 280

/**
 * The left of the controls while it hears: a pulsing red dot and how long you've spoken, then what
 * letting go does ("‹ Slide to cancel", "Let go to cancel" in red), or, for a tapped recording, an X
 * to cancel first. Ticks the [dictation] along ([Dictation.tick]).
 */
@Composable
fun RowScope.RecordingInfo(dictation: Dictation, state: Dictation.State) {
    val c = Herdr.colors
    val rec = state as? Dictation.State.Recording
    var now by remember { mutableLongStateOf(dictation.clock()) }
    LaunchedEffect(dictation) {
        while (true) {
            now = dictation.clock()
            dictation.tick()
            kotlinx.coroutines.delay(250)
        }
    }
    val started = when (state) {
        is Dictation.State.Recording -> state.startedAt
        is Dictation.State.Finishing -> state.startedAt
        else -> now
    }
    val until = (state as? Dictation.State.Finishing)?.stoppedAt ?: now
    if (rec != null && !rec.held) ComposerIconButton(HerdrIcons.X, "Cancel voice message", onClick = dictation::cancel)
    else Spacer(Modifier.width(Space.l))
    val pulse = rememberInfiniteTransition(label = "recording")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Box(Modifier.size(8.dp).alpha(if (rec != null) alpha else 0.5f).background(c.red, CircleShape))
    Spacer(Modifier.width(Space.s))
    Text(Dictation.elapsed(until - started), style = CodeStyle, color = c.strong, maxLines = 1)
    val hint = when {
        state is Dictation.State.Finishing -> "Transcribing…"
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
 * Over the message box while a voice message's words wait in it: a mic, "Voice message · 0:07" and
 * "Check the words, then send", and an X that takes the words out again.
 */
@Composable
fun VoiceTake(millis: Long, onDrop: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().padding(start = Space.l, top = Space.xs).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HerdrIcons.Mic, null, tint = c.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Space.s))
        Text(
            "Voice message · ${Dictation.elapsed(millis)}",
            style = MaterialTheme.typography.labelMedium,
            color = c.text,
            maxLines = 1,
        )
        Text(
            "Check the words, then send",
            style = MaterialTheme.typography.labelMedium,
            color = c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Space.s),
        )
        ComposerIconButton(HerdrIcons.X, "Discard voice message", onClick = onDrop)
    }
}

/**
 * Over the composer when the mic can't be used: the microphone refused (why it is needed, and Allow,
 * or Open settings once Android won't ask again), or what went wrong while it heard.
 */
@Composable
fun MicNotice(dictation: Dictation, state: Dictation.State) {
    val c = Herdr.colors
    val shape = RoundedCornerShape(Radius.xl)
    val noMic = state as? Dictation.State.NoMic
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
                            if (noMic.forever) "Voice messages need it to hear you. Allow it under Permissions in the phone's settings for herdr."
                            else "Voice messages need it to hear you. What you say becomes text, and only the text is sent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                        )
                    } else {
                        Text((state as? Dictation.State.Failed)?.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.text)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when {
                    noMic == null -> QuietButton(onClick = dictation::dismiss) { Text("OK") }
                    noMic.forever -> {
                        QuietButton(onClick = dictation::dismiss) { Text("Not now") }
                        QuietButton(onClick = dictation::settings, color = c.accent) { Text("Open settings") }
                    }
                    else -> {
                        QuietButton(onClick = dictation::dismiss) { Text("Not now") }
                        QuietButton(onClick = dictation::askAgain, color = c.accent) { Text("Allow") }
                    }
                }
            }
        }
    }
}
