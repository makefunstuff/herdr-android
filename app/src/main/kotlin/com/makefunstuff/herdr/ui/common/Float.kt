package com.makefunstuff.herdr.ui.common

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.bubble.Floated
import com.makefunstuff.herdr.bubble.LocalBubble
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton

/**
 * Floats [chat] over other apps as a bubble, named [title] on [machine]; null where it can't (a phone
 * before Android 11, or already in its bubble). When Android doesn't let it float, a dialog says why
 * and opens the setting, and the chat floats as soon as the person comes back with it allowed.
 */
@Composable
fun rememberFloat(chat: BubbleChat, title: String, machine: String): (() -> Unit)? {
    val bubbles = LocalContainer.current.bubbles
    var problem by remember { mutableStateOf<Floated?>(null) }
    /** Gone to Android's settings for it: float on the way back, if it is allowed by then. */
    var waiting by remember { mutableStateOf(false) }
    val name by rememberUpdatedState(title to machine)
    fun float() {
        problem = bubbles.float(chat, name.first, name.second).takeIf { it != Floated.Yes }
    }
    LifecycleResumeEffect(chat) {
        if (waiting) {
            waiting = false
            if (bubbles.allowed(chat)) float()
        }
        onPauseOrDispose {}
    }
    // Asked for the permission: granted, it floats; refused, or notifications off for the app anyway, Android's settings are next.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val floated = if (granted) bubbles.float(chat, name.first, name.second) else Floated.NotificationsOff
        if (floated == Floated.NotificationsOff) { waiting = true; bubbles.openNotificationSettings() }
        else problem = floated.takeIf { it != Floated.Yes }
    }

    when (problem) {
        Floated.BubblesOff -> FloatDialog(
            "Let chats float",
            "Android floats Herdr's chats only once you allow it. In Herdr's bubble settings, choose " +
                "\"All conversations can bubble\" and come back: this chat floats then.",
            onDismiss = { problem = null },
        ) { problem = null; waiting = true; bubbles.openSettings() }
        Floated.NotificationsOff -> FloatDialog(
            "Notifications are off",
            "A floating chat is one of Herdr's notifications, so Herdr needs to be allowed to notify. " +
                "Allow it and this chat floats.",
            onDismiss = { problem = null },
        ) {
            problem = null
            if (Build.VERSION.SDK_INT >= 33) ask.launch(POST_NOTIFICATIONS) else { waiting = true; bubbles.openNotificationSettings() }
        }
        else -> {}
    }
    if (!bubbles.supported || LocalBubble.current != null) return null
    return ::float
}

private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

@Composable
private fun FloatDialog(title: String, body: String, onDismiss: () -> Unit, onAllow: () -> Unit) {
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { PrimaryButton("Open settings", onClick = onAllow) },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Not now") } },
    )
}
