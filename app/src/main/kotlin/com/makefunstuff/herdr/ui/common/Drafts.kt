package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * A chat's draft under [key] (see [com.makefunstuff.herdr.data.DraftStore]): read once when the
 * chat opens, written a moment after you stop typing and again when you leave the chat, so it is
 * there after navigation, rotation and an app restart.
 */
@Composable
fun rememberDraft(key: String): MutableState<String> {
    val store = LocalContainer.current.drafts
    val draft = remember(key) { mutableStateOf(store.get(key)) }
    LaunchedEffect(key) {
        snapshotFlow { draft.value }.collectLatest { text ->
            // An emptied box (sent, or erased) is saved at once; typing is saved when it pauses.
            if (text.isNotEmpty()) delay(300)
            store.put(key, text)
        }
    }
    DisposableEffect(key) { onDispose { store.put(key, draft.value) } }
    return draft
}
