package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.ui.design.ComposerCursor
import com.makefunstuff.herdr.ui.design.ComposerVoice

/**
 * Voice messages for the composer of the chat under [key]: the words heard go into [draft] where
 * its cursor stands ([Dictation.insert]), marked as spoken (how long, and the words) until they are
 * sent or all gone. Never sent by themselves: they wait in the box like typed words.
 */
class VoiceDraft internal constructor(
    private val dictation: Dictation?,
    private val take: MutableState<Long?>,
    private val drop: () -> Unit,
    private val cursor: ComposerCursor,
) {
    /** Whether the draft holds words heard: it goes as a voice message. */
    val spoken: Boolean get() = take.value != null

    /** The draft went: what is left in the box is typed. */
    fun sent() { take.value = null }

    /** For [com.makefunstuff.herdr.ui.design.ChatComposer]; null on a phone without speech recognition (no mic). */
    val composer: ComposerVoice? get() = dictation?.let { ComposerVoice(it, take.value, cursor, drop) }
}

@Composable
fun rememberVoiceDraft(key: String, draft: String, onDraft: (String) -> Unit): VoiceDraft {
    val current = rememberUpdatedState(draft)
    val set = rememberUpdatedState(onDraft)
    val take = rememberSaveable(key) { mutableStateOf<Long?>(null) }
    val words = rememberSaveable(key) { mutableStateOf("") }
    // The draft as it was before the first words went in: what the X on the voice message leaves.
    val before = rememberSaveable(key) { mutableStateOf("") }
    val cursor = remember(key) { ComposerCursor() }
    val dictation = rememberDictation { text, millis ->
        val now = current.value
        if (take.value == null) {
            before.value = now
            words.value = ""
        }
        val inserted = Dictation.insert(now, if (cursor.at < 0) now.length else cursor.at, text)
        cursor.place(inserted)
        set.value(inserted.text)
        take.value = (take.value ?: 0L) + millis
        words.value = Dictation.join(words.value, text)
    }
    // The words all gone (sent, or deleted by hand): no longer a voice message.
    LaunchedEffect(draft.isBlank()) { if (draft.isBlank()) { take.value = null; words.value = "" } }
    return remember(key, dictation) {
        VoiceDraft(dictation, take, drop = {
            // Taken out again, only while they stand as heard in the draft: changed, they are yours now.
            Dictation.drop(current.value, before.value, words.value)?.let { set.value(it) }
            take.value = null
            words.value = ""
        }, cursor)
    }
}
