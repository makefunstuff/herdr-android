package com.makefunstuff.herdr.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.chat.StickToBottom
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A chat list kept to its newest message by [StickToBottom]'s rules: [rememberStickToBottom]. */
@Stable
class ChatStick internal constructor(private val list: LazyListState, private val scope: CoroutineScope, atEnd: () -> Boolean) {
    internal var rules by mutableStateOf(StickToBottom())
    internal var messages by mutableStateOf(emptyList<String>())

    /** Within about 80 px of the newest. */
    val atEnd by derivedStateOf(atEnd)

    /** What came in since the reader left the end, for the ↓. */
    val fresh by derivedStateOf { rules.fresh(messages) }

    /** The message the New line goes above. */
    val unread: String? get() = rules.unread

    /** Down to the newest, as the ↓ does. */
    fun toEnd() {
        scope.launch { list.animateScrollToItem(0) }
    }

    /** You wrote: down to the newest, and what was new is behind you. */
    fun sent() {
        rules = rules.sent()
        toEnd()
    }

    /** Keeps the list to [messages] (see [rememberStickToBottom]). */
    @Composable
    fun Follow(messages: List<String>, content: Any? = null) {
        this.messages = messages
        val current by rememberUpdatedState(messages)
        LaunchedEffect(this, messages, content) {
            val r = rules.seen(messages, atEnd)
            rules = r.next
            if (r.toEnd) list.animateScrollToItem(0)
        }
        LaunchedEffect(this, atEnd) { rules = rules.moved(atEnd, current) }
    }
}

/**
 * The one way a chat stays at its newest, for the agent chat and both Coordinators, so they can't drift
 * apart: [list] is reversed (item 0 is the newest), [messages] are the keys that count as messages,
 * oldest first, and [content] changes whenever anything at the end may have grown (the working line,
 * an answer being written). A [list] remembered anew (another space) opens at the newest again.
 */
@Composable
fun rememberStickToBottom(list: LazyListState, messages: List<String>, content: Any? = null): ChatStick =
    rememberStickToBottom(list).also { it.Follow(messages, content) }

/** The [ChatStick] of [list], for a screen that sends before its messages are known: it then calls [ChatStick.Follow]. */
@Composable
fun rememberStickToBottom(list: LazyListState): ChatStick {
    val scope = rememberCoroutineScope()
    val slop = with(LocalDensity.current) { StickToBottom.SLOP_DP.dp.roundToPx() }
    return remember(list) {
        ChatStick(list, scope) { !list.canScrollBackward || StickToBottom.atEnd(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, slop) }
    }
}

/**
 * Over the composer while the reader is up the chat: "↓ 2 new messages" once more came in, else a
 * plain ↓. [bottom] is the composer's height.
 */
@Composable
fun BoxScope.NewestButton(stick: ChatStick, bottom: Dp) {
    if (stick.atEnd) return
    if (stick.fresh > 0) {
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + Space.xs)) {
            NewMessagesPill(stick.fresh, stick::toEnd)
        }
    } else {
        Box(Modifier.align(Alignment.BottomEnd).padding(end = Space.m, bottom = bottom + Space.xs)) {
            RoundAction(HerdrIcons.ArrowDown, "Newest", primary = false, onClick = stick::toEnd)
        }
    }
}

/** "↓ 2 new messages": over the composer while the reader is scrolled up and more came in. */
@Composable
fun NewMessagesPill(count: Int, onClick: () -> Unit) {
    val c = Herdr.colors
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 6.dp)
            .shadow(8.dp, shape).clip(shape).background(c.card).border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClickLabel = "Go to the newest", onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.s),
    ) {
        Icon(HerdrIcons.ArrowDown, null, Modifier.size(16.dp), tint = c.text)
        Spacer(Modifier.width(Space.s))
        Text(if (count == 1) "1 new message" else "$count new messages", style = MaterialTheme.typography.labelLarge, color = c.text)
    }
}

/** "── New ──" in amber, above the first message that came in while the reader was up the chat. */
@Composable
fun NewLine(modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.6f)))
        Text("New", style = MaterialTheme.typography.labelMedium, color = c.accent, modifier = Modifier.padding(horizontal = Space.s))
        Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.6f)))
    }
}
