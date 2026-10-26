package com.makefunstuff.herdr.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.chat.Recorder
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.VoiceClips
import com.makefunstuff.herdr.data.VoiceClip
import com.makefunstuff.herdr.data.VoiceStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.ComposerIconButton
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Plays voice messages, one at a time: the screen's own ([rememberClipPlayer]). */
interface ClipPlayer {
    /** What plays (or is paused) now; null when nothing does. */
    val now: StateFlow<Play?>

    /** Plays the recording at [path] on the phone, or pauses it (or goes on) when it is the one playing. */
    fun toggle(path: String)

    fun stop()

    /** [path] at [position] of [duration] ms; [failed] when the phone couldn't play it. */
    data class Play(val path: String, val playing: Boolean, val position: Long, val duration: Long, val failed: Boolean = false)
}

/**
 * The voice messages going with a chat's next message ([VoiceStore], under the chat's draft key):
 * each is copied to the chat's machine, into the agent's folder [cwd], as soon as it is recorded,
 * or once the machine connects, one at a time; the message names them when it is sent
 * ([message]). Send waits while one is still on its way ([waiting]); one that couldn't be copied
 * stays, says why, and is tried again with a tap.
 */
class VoiceTray internal constructor(
    val clips: List<VoiceClip>,
    /** The one being copied now. */
    val uploading: String?,
    /** Why one couldn't be copied, by its id. */
    val failed: Map<String, String>,
    /** The machine's name, for what the rows say. */
    val machine: String,
    private val key: String,
    private val store: VoiceStore,
    private val onRetry: (String) -> Unit,
) {
    /** Recordings not yet on the machine: Send waits for them. */
    val waiting: Boolean get() = clips.any { it.remote == null }
    val isEmpty: Boolean get() = clips.isEmpty()

    /** Adds a recording; false when the message has as many as it can take. */
    fun add(clip: Recorder.Clip): Boolean =
        store.add(key, VoiceClip(File(clip.path).nameWithoutExtension, clip.path, clip.millis))

    fun remove(id: String) = store.remove(key, id)
    fun retry(id: String) = onRetry(id)

    /** [typed] with the voice messages named in front, as the message goes. */
    fun message(typed: String): String = store.message(key, typed)

    /** The message with [sent] in it went: they leave the composer. */
    fun sent(sent: List<VoiceClip>) = store.sent(key, sent)
}

/**
 * The composer's voice messages for the chat under [key] on [machineId], copied there through
 * [session] while it is [online], into the agent's folder [cwd] (see [VoiceClips.uploadCommand]).
 */
@Composable
fun rememberVoiceTray(key: String, machineId: String, session: HostSession, online: Boolean, cwd: String?): VoiceTray {
    val store = LocalContainer.current.voice
    val clips by remember(key) { store.clips(key) }.collectAsState(store.current(key))
    var failed by remember(key) { mutableStateOf(emptyMap<String, String>()) }
    var uploading by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, machineId, online, cwd) {
        if (!online) return@LaunchedEffect
        while (true) {
            // The next one not yet on the machine (and not one that failed until it is tapped again).
            val (list, bad) = combine(store.clips(key), snapshotFlow { failed }) { l, f -> l to f }
                .first { (l, f) -> l.any { it.remote == null && it.id !in f } }
            val next = list.first { it.remote == null && it.id !in bad }
            uploading = next.id
            try {
                val stamp = Attachments.stamp()
                val name = VoiceClips.fileName(stamp, File(next.path).extension.ifEmpty { "ogg" })
                val remote = withContext(Dispatchers.IO) { File(next.path).inputStream().use { session.uploadVoice(cwd, name, it, stamp) } }
                withContext(Dispatchers.IO) { store.uploaded(key, next.id, machineId, remote) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed = failed + (next.id to (t.message ?: "Couldn't copy it"))
            } finally {
                uploading = null
            }
        }
    }
    return VoiceTray(clips, uploading, failed.filterKeys { id -> clips.any { it.id == id } }, session.machine.label, key, store) { id -> failed = failed - id }
}

/**
 * The composer's voice messages, over the message box, a row each: play it back, "Voice message ·
 * 0:07", where it is on its way to the machine (copying, waiting for it to connect, or, in red,
 * why it couldn't be copied, with a tap to try again), and an X that throws it away.
 */
@Composable
fun ComposerClips(tray: VoiceTray, player: ClipPlayer) {
    if (tray.isEmpty) return
    val play by player.now.collectAsState()
    Column(Modifier.fillMaxWidth().padding(top = Space.xs)) {
        tray.clips.forEach { clip ->
            val c = Herdr.colors
            val failed = tray.failed[clip.id]
            val mine = play?.takeIf { it.path == clip.path }
            val state = when {
                failed != null -> "Couldn't copy it: ${failed.trimEnd('.')}. Tap to try again"
                clip.remote != null -> null
                tray.uploading == clip.id -> "Copying to ${tray.machine}…"
                else -> "Waits for ${tray.machine}"
            }
            Row(
                Modifier.fillMaxWidth().padding(start = Space.xs)
                    .then(if (failed != null) Modifier.clickable(role = Role.Button, onClickLabel = "Try again") { tray.retry(clip.id) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(mine, small = true) { player.toggle(clip.path) }
                Column(Modifier.weight(1f).padding(vertical = Space.xxs)) {
                    Text(
                        "Voice message · " + time(clip.millis, mine),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.text,
                        maxLines = 1,
                    )
                    // Where it is on its way to the machine, under it: copying, waiting, or why it couldn't be.
                    if (state != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 2.dp).size(12.dp), contentAlignment = Alignment.Center) {
                            when {
                                failed != null -> Icon(HerdrIcons.CircleAlert, null, tint = c.red, modifier = Modifier.size(12.dp))
                                tray.uploading == clip.id -> Spinner(size = 10.dp)
                                else -> Icon(HerdrIcons.Clock, null, tint = c.muted, modifier = Modifier.size(12.dp))
                            }
                        }
                        Text(
                            state,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failed != null) c.red else c.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = Space.xs),
                        )
                    }
                }
                ComposerIconButton(HerdrIcons.X, "Discard voice message", onClick = {
                    if (mine != null) player.stop()
                    tray.remove(clip.id)
                })
            }
        }
    }
}

/** "0:07", or while it plays "0:03 / 0:07". */
private fun time(millis: Long?, play: ClipPlayer.Play?): String {
    val total = millis?.takeIf { it > 0 } ?: play?.duration?.takeIf { it > 0 }
    val length = total?.let(Recorder::elapsed) ?: "–:––"
    return if (play != null && !play.failed && (play.playing || play.position > 0)) Recorder.elapsed(play.position) + " / " + length else length
}

/** A round play button: amber with the Play glyph, Pause while it plays; [busy] a spinner, [enabled] false a dim one. */
@Composable
private fun PlayButton(play: ClipPlayer.Play?, small: Boolean = false, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Herdr.colors
    val playing = play?.playing == true
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause voice message" else "Play voice message" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (small) 28.dp else 36.dp).background(if (enabled) c.primary else c.hover, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> Spinner(size = 14.dp)
                else -> Icon(
                    if (playing) HerdrIcons.Pause else HerdrIcons.Play, null,
                    tint = if (enabled) c.onPrimary else c.muted,
                    // The triangle sits a hair right of centre, as it looks centred there.
                    modifier = Modifier.size(if (small) 12.dp else 14.dp).padding(start = if (playing) 0.dp else 1.dp),
                )
            }
        }
    }
}

/**
 * Where a chat's voice messages come from: the machine its messages went to. One sent from this
 * phone is kept on it ([VoiceStore.sentFile]); any other one named in a message is read back from
 * the machine once and kept as well. [player] plays them.
 */
class ChatVoice(val machineId: String, val session: HostSession, val store: VoiceStore, val online: Boolean, val player: ClipPlayer)

/** The chat being shown, for its messages' voice messages; null shows them without playback. */
val LocalChatVoice = staticCompositionLocalOf<ChatVoice?> { null }

private sealed interface Found {
    data object Looking : Found
    data class Here(val file: File) : Found
    data class Gone(val why: String) : Found
}

@Composable
private fun rememberSent(voice: ChatVoice?, remote: String): Found {
    val found by produceState<Found>(Found.Looking, voice?.machineId, remote, voice?.online) {
        if (voice == null) { value = Found.Gone("Not on this phone"); return@produceState }
        val kept = voice.store.sentFile(voice.machineId, remote)
        if (kept.exists()) { value = Found.Here(kept); return@produceState }
        if (!voice.online) { value = Found.Gone("Plays once ${voice.session.machine.label} connects"); return@produceState }
        value = try {
            val bytes = voice.session.fetchVoice(remote)
            Found.Here(withContext(Dispatchers.IO) { voice.store.keepSent(voice.machineId, remote, bytes) })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Found.Gone(t.message ?: "Couldn't read it")
        }
    }
    return found
}

/**
 * The voice messages a message names ([paths] on the chat's machine), in your chat turn, one under
 * the other: a round play button, a line that fills as it plays, "Voice message" and how long it is.
 * The agent got the file itself; there are no words to show for it.
 */
@Composable
fun MessageVoice(paths: List<String>, modifier: Modifier = Modifier) {
    if (paths.isEmpty()) return
    val voice = LocalChatVoice.current
    val play by (voice?.player?.now ?: remember { MutableStateFlow<ClipPlayer.Play?>(null) }).collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        paths.forEach { remote ->
            val c = Herdr.colors
            val found = rememberSent(voice, remote)
            val file = (found as? Found.Here)?.file
            val mine = play?.takeIf { file != null && it.path == file.path }
            val millis = voice?.store?.millis(voice.machineId, remote)
            val total = millis?.takeIf { it > 0 } ?: mine?.duration?.takeIf { it > 0 }
            val progress = if (mine != null && total != null) (mine.position.toFloat() / total).coerceIn(0f, 1f) else 0f
            val why = when {
                mine?.failed == true -> "The phone can't play it"
                found is Found.Gone -> found.why
                else -> null
            }
            Row(
                Modifier.widthIn(min = 200.dp, max = 260.dp).semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "Voice message, " + (total?.let(Recorder::elapsed) ?: "length unknown")
                    if (mine?.playing == true) stateDescription = "Playing"
                    why?.let { stateDescription = it }
                    if (file != null) onClick(label = if (mine?.playing == true) "Pause" else "Play") { voice?.player?.toggle(file.path); true }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(mine, busy = found is Found.Looking, enabled = file != null) { file?.let { voice?.player?.toggle(it.path) } }
                Column(Modifier.weight(1f)) {
                    // How far it has played, on a quiet track.
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
                        if (progress > 0f) Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent))
                    }
                    Spacer(Modifier.height(Space.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(HerdrIcons.Mic, null, tint = c.muted, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            why ?: "Voice message",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(time(millis, mine), style = CodeStyle, color = c.muted, maxLines = 1, modifier = Modifier.heightIn(min = 16.dp))
                    }
                }
            }
        }
    }
}
