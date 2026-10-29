package com.makefunstuff.herdr.ui.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.WorkOverview
import com.makefunstuff.herdr.core.home.HomeBoard
import com.makefunstuff.herdr.ui.common.LocalContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** The boards of the connected machines that have one, as last read, in the machines' order. */
@Composable
fun rememberBoards(): List<HomeBoard> {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val lists by container.boards.lists.collectAsStateWithLifecycle()
    return remember(hosts, lists) {
        hosts.filter { it.online && it.hasWorkBoard }.mapNotNull { h -> lists[h.machine.id]?.let { HomeBoard(h.machine.id, h.machine.label, it.tasks) } }
    }
}

/** The Shepherdr 2 boards of the connected machines, as last read, by machine id: their spaces and tasks. */
@Composable
fun rememberWorkBoards(): Map<String, WorkOverview> {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val lists by container.boards.lists.collectAsStateWithLifecycle()
    return remember(hosts, lists) {
        hosts.filter { it.online && it.onShepherdr2 }.mapNotNull { h -> lists[h.machine.id]?.work?.let { h.machine.id to it } }.toMap()
    }
}

/** Machines whose board didn't answer the last read, by label, with what went wrong. */
@Composable
fun rememberBoardProblems(): List<Pair<String, String>> {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val lists by container.boards.lists.collectAsStateWithLifecycle()
    return remember(hosts, lists) {
        hosts.filter { it.online && it.hasWorkBoard }.mapNotNull { h -> lists[h.machine.id]?.problem?.let { h.machine.label to it } }
    }
}

/**
 * Reads every connected machine's board while the screen is in front: at once, then every
 * [everyMs], and again as soon as [readNow] is sent (after an action). Nothing while it's backgrounded.
 */
@Composable
fun ReadBoards(readNow: Channel<Unit>, everyMs: Long = 15_000) {
    val container = LocalContainer.current
    val owner = LocalLifecycleOwner.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val ids = hosts.filter { it.online && it.hasWorkBoard }.joinToString(",") { it.machine.id }
    LaunchedEffect(ids) {
        if (ids.isEmpty()) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var asked = false
            while (true) {
                // Asked for (after an action): read now, even if another screen just did.
                container.boards.readAll(freshMs = if (asked) 0 else 3_000)
                asked = withTimeoutOrNull(everyMs) { readNow.receive() } != null
            }
        }
    }
}
