package com.makefunstuff.herdr.bubble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.R

/**
 * [Bubbles] on Android. A bubble is a conversation notification: MessagingStyle with the chat as a
 * [Person], tied to a long-lived shortcut, with [NotificationCompat.BubbleMetadata] that opens
 * [BubbleActivity] on the chat. Asked for from the app, it opens expanded and its notification stays
 * out of the shade; where Android doesn't let it bubble, the notification shows instead, and its
 * bubble button (or marking the conversation as priority) floats it from there.
 */
class AndroidBubbles(private val context: Context) : Bubbles {
    private val system = context.getSystemService(NotificationManager::class.java)
    private val nm = NotificationManagerCompat.from(context)

    init {
        if (supported) {
            system.createNotificationChannel(
                // High, as bubbles are; silent, since a bubble is only ever asked for by the person looking.
                NotificationChannel(CHANNEL, "Floating chats", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "An agent's chat or the Coordinator floating over other apps as a bubble"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
    }

    override val supported: Boolean get() = Build.VERSION.SDK_INT >= 30

    private fun notificationsAllowed(): Boolean = nm.areNotificationsEnabled() && permitted()

    private fun permitted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun allowed(chat: BubbleChat): Boolean = supported && notificationsAllowed() && bubblesAllowed(chat)

    private fun bubblesAllowed(chat: BubbleChat): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        // The chat's own conversation, once Android has one for it: "bubble this conversation" turns it on there.
        val conversation = runCatching { system.getNotificationChannel(CHANNEL, chat.id) }.getOrNull()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!system.areBubblesEnabled()) return false
            return when (system.bubblePreference) {
                NotificationManager.BUBBLE_PREFERENCE_ALL -> true
                NotificationManager.BUBBLE_PREFERENCE_SELECTED -> conversation?.canBubble() == true
                else -> false
            }
        }
        @Suppress("DEPRECATION")
        return system.areBubblesAllowed() || conversation?.canBubble() == true
    }

    override fun float(chat: BubbleChat, title: String, machine: String): Floated {
        if (!supported) return Floated.BubblesOff
        if (!nm.areNotificationsEnabled()) return Floated.NotificationsOff
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Floated.NotificationsOff
        val allowed = bubblesAllowed(chat)
        val icon = icon(chat, title)
        val name = if (machine.isEmpty()) title else "$title · $machine"
        val person = Person.Builder().setName(name).setKey(chat.id).setIcon(icon).setBot(true).build()
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtras(openExtras(chat))
        ShortcutManagerCompat.pushDynamicShortcut(
            context,
            ShortcutInfoCompat.Builder(context, chat.id)
                .setShortLabel(title)
                .setLongLabel(name)
                .setIcon(icon)
                .setPerson(person)
                .setLongLived(true)
                .setLocusId(LocusIdCompat(chat.id))
                .setIntent(open)
                .build(),
        )
        // A bubble's intent must be mutable: Android adds to it as it opens the bubble.
        val bubble = PendingIntent.getActivity(
            context, chat.id.hashCode(),
            Intent(context, BubbleActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri(chat)),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val tap = PendingIntent.getActivity(
            context, chat.id.hashCode(),
            Intent(open).setData(Uri.parse("herdr://open/${Uri.encode(chat.id)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val words = if (allowed) "Floating over your apps" else
            "Tap the bubble button, or allow Herdr's bubbles in Android's settings, to float this chat over your apps"
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .addMessage(words, System.currentTimeMillis(), person)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(AMBER)
            .setContentTitle(name)
            .setContentText(words)
            .setStyle(style)
            .setShortcutId(chat.id)
            .setLocusId(LocusIdCompat(chat.id))
            .addPerson(person)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setBubbleMetadata(
                NotificationCompat.BubbleMetadata.Builder(bubble, icon)
                    .setDesiredHeight(BUBBLE_HEIGHT_DP)
                    // Asked for in the app: open at once, and keep it out of the shade (only once it does bubble).
                    .setAutoExpandBubble(true)
                    .setSuppressNotification(true)
                    .build(),
            )
        runCatching { nm.notify(chat.id, ID, b.build()) }.onFailure { return Floated.NotificationsOff }
        return if (allowed) Floated.Yes else Floated.BubblesOff
    }

    override fun openSettings() {
        if (Build.VERSION.SDK_INT < 30) return openNotificationSettings()
        val bubbles = Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(bubbles.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { openNotificationSettings() }
    }

    override fun openNotificationSettings() {
        val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun openApp(chat: BubbleChat) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(OPEN_APP)
            .putExtras(openExtras(chat))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
    }

    /** What makes MainActivity open [chat]: as a tapped notification does. */
    private fun openExtras(chat: BubbleChat) = android.os.Bundle().apply {
        putString(MainActivity.OPEN_MACHINE, chat.machineId)
        when (chat) {
            is BubbleChat.Agent -> putString(MainActivity.OPEN_PANE, chat.paneId)
            is BubbleChat.Coordinator -> putBoolean(MainActivity.OPEN_COORDINATOR, true)
        }
    }

    /**
     * The bubble's face, an adaptive bitmap: the Coordinator's icon, or the agent's initial, in graphite on
     * the app's amber. Only the middle two thirds show, as a circle.
     */
    private fun icon(chat: BubbleChat, title: String): IconCompat {
        val px = (ICON_DP * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AMBER)
        val ink = GRAPHITE
        when (chat) {
            is BubbleChat.Coordinator -> ContextCompat.getDrawable(context, R.drawable.ic_coordinator)?.mutate()?.let { d ->
                val side = px * 40 / ICON_DP
                val at = (px - side) / 2
                d.setTint(ink)
                d.setBounds(at, at, at + side, at + side)
                d.draw(canvas)
            }
            is BubbleChat.Agent -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ink
                    textSize = px * 0.4f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val letter = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
                canvas.drawText(letter, px / 2f, px / 2f - (paint.descent() + paint.ascent()) / 2, paint)
            }
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    companion object {
        const val CHANNEL = "floating"
        /** Opens a chat in the app from its bubble. */
        const val OPEN_APP = "com.makefunstuff.herdr.OPEN_CHAT"
        private const val ID = 4
        private const val ICON_DP = 108
        private const val BUBBLE_HEIGHT_DP = 640
        private const val AMBER = 0xFFF0A830.toInt()
        private const val GRAPHITE = 0xFF16120D.toInt()

        /** The chat a bubble opens on, as its intent's data; one per chat, so each has its own bubble. */
        fun uri(chat: BubbleChat): Uri = Uri.parse("herdr://bubble/${Uri.encode(chat.id)}")

        fun chatOf(uri: Uri?): BubbleChat? =
            uri?.takeIf { it.scheme == "herdr" && it.host == "bubble" }?.lastPathSegment?.let(BubbleChat::parse)
    }
}
