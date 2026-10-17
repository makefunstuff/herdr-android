package com.makefunstuff.herdr.bubble

import android.content.Context
import android.content.Intent
import android.content.LocusId
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.makefunstuff.herdr.HerdrApp

/** A floating chat's window: the chat in its bubble ([BubbleRoot]), over whatever app is open. */
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val chat = AndroidBubbles.chatOf(intent.data) ?: return finish()
        // The chat is on screen: Android takes the conversation's notification as seen.
        if (Build.VERSION.SDK_INT >= 30) setLocusContext(LocusId(chat.id), null)
        val container = (application as HerdrApp).container
        setContent { BubbleRoot(container, chat) }
    }
}

/**
 * Float from a notification (an agent's, or its Live Update): floats that agent's chat and closes. It
 * shows nothing; started as an activity, it has the app in front for a moment, so the bubble opens.
 * Where Android doesn't let it float, it opens the setting that does.
 */
class FloatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return finish()
        val container = (application as HerdrApp).container
        val chat = AndroidBubbles.chatOf(intent.data)
        if (chat != null) {
            val title = intent.getStringExtra(TITLE)?.takeIf { it.isNotBlank() } ?: "Agent"
            val machine = container.machines.machines.value.firstOrNull { it.id == chat.machineId }?.label.orEmpty()
            when (container.bubbles.float(chat, title, machine)) {
                Floated.Yes -> {}
                Floated.BubblesOff -> {
                    Toast.makeText(this, "Allow Herdr's bubbles, then tap Float again", Toast.LENGTH_LONG).show()
                    container.bubbles.openSettings()
                }
                Floated.NotificationsOff -> container.bubbles.openNotificationSettings()
            }
        }
        finish()
    }

    companion object {
        private const val TITLE = "title"

        /** Floats [chat], named [title], when a notification's Float is tapped. */
        fun intent(context: Context, chat: BubbleChat, title: String): Intent =
            Intent(context, FloatActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(AndroidBubbles.uri(chat))
                .putExtra(TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
