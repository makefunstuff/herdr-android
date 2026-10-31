package com.makefunstuff.herdr.voice

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.core.voice.VoiceState
import com.makefunstuff.herdr.core.voice.VoiceState.Phase
import com.makefunstuff.herdr.push.AndroidAlerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the coordinator's voice going with the screen off or the app closed: a foreground service
 * (media playback and the microphone) with a media notification (Talk, Mute, Stop) and a media
 * session, so a headset's or a car's play button stops the voice or talks.
 */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private val voice get() = (application as HerdrApp).container.coordinatorVoice

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "herdr-voice").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val key = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    if (key?.action != KeyEvent.ACTION_DOWN) return true
                    when (key.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> button()
                        KeyEvent.KEYCODE_MEDIA_STOP -> voice.stop()
                        else -> return false
                    }
                    return true
                }
                override fun onPlay() = button()
                override fun onPause() = button()
                override fun onStop() = voice.stop()
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        MUTE -> voice.setMuted(!voice.state.value.muted)
                        STOP -> voice.stop()
                    }
                }
            })
            isActive = true
        }
        scope.launch {
            voice.state.map { listOf(it.on, it.phase, it.muted, it.handsFree, it.engine, it.line, it.spaceName) }.distinctUntilChanged().collect {
                val s = voice.state.value
                if (!s.on) {
                    ServiceCompat.stopForeground(this@VoiceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    show(s)
                    getSystemService(NotificationManager::class.java).notify(ID, note(s))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = voice.state.value
        show(s)
        val types = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        // Android wants the foreground at once, even for a button press after voice went off.
        runCatching { ServiceCompat.startForeground(this, ID, note(s), types) }
            .onFailure { ServiceCompat.startForeground(this, ID, note(s), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) }
        when (intent?.action) {
            TALK -> button()
            MUTE -> voice.setMuted(!s.muted)
            STOP -> voice.stop()
        }
        if (!voice.state.value.on) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * The session as Android 13 and later draw the media notification from it: the words in its
     * metadata, play/pause (talk, or stop the voice), and Mute and Stop as its own buttons.
     */
    private fun show(s: VoiceState) {
        val session = session ?: return
        session.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Coordinator · ${s.spaceName.ifEmpty { "voice" }}")
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, listOfNotNull(s.words, s.line.takeIf { it.isNotBlank() }?.take(120)).joinToString(": "))
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(if (s.phase == Phase.Speaking || s.phase == Phase.Listening) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f)
            .addCustomAction(PlaybackState.CustomAction.Builder(MUTE, if (s.muted) "Unmute" else "Mute", R.drawable.ic_notification).build())
            .addCustomAction(PlaybackState.CustomAction.Builder(STOP, "Stop", R.drawable.ic_notification).build())
            .build())
    }

    /** A headset's button, or Talk on the notification: stop the voice, else talk (a second press sends). */
    private fun button() {
        when (voice.state.value.phase) {
            Phase.Speaking -> voice.hush()
            Phase.Listening -> voice.talk(false)
            Phase.Ready -> voice.talk(true)
            else -> {}
        }
    }

    private fun note(s: VoiceState): Notification {
        fun action(what: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, VoiceService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val talk = when (s.phase) {
            Phase.Speaking -> "Stop talking"
            Phase.Listening -> "Send"
            else -> "Talk"
        }
        val builder = Notification.Builder(this, AndroidAlerts.WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Coordinator · ${s.spaceName.ifEmpty { "voice" }}")
            .setContentText(listOfNotNull(s.words, s.line.takeIf { it.isNotBlank() }?.let { "“${it.take(120)}”" }).joinToString(": "))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, talk, action(TALK, 51)).build())
            .addAction(Notification.Action.Builder(null, if (s.muted) "Unmute" else "Mute", action(MUTE, 52)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(STOP, 53)).build())
        session?.let { builder.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2)) }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val TALK = "com.makefunstuff.herdr.voice.TALK"
        const val MUTE = "com.makefunstuff.herdr.voice.MUTE"
        const val STOP = "com.makefunstuff.herdr.voice.STOP"
        private const val ID = 12
    }
}
