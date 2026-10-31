package com.makefunstuff.herdr.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: drives the coordinator's voice from adb, to try it on an emulator against the
 * voice server and time it (tag herdr-voice). Not in release builds; only the shell (which holds DUMP) may send it.
 *
 *     adb shell am broadcast -n com.makefunstuff.herdr.debug/com.makefunstuff.herdr.voice.DebugVoiceReceiver -a SET --es server http://… --es key …
 *     … -a START                       on, for a chat named "Debug"
 *     … -a SAY --es text "Markdown"     read as a new coordinator reply (--es b64 <base64> for code and quotes)
 *     … -a HEAR --es file /data/local/tmp/turn.wav   (16 kHz mono 16-bit) heard as if you said it
 *     … -a STOP
 */
class DebugVoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as HerdrApp).container
        val voice = container.coordinatorVoice as AndroidCoordinatorVoice
        when (intent.action) {
            "SET" -> {
                intent.getStringExtra("server")?.let(container.voiceSettings::setServer)
                intent.getStringExtra("key")?.let(container.voiceSettings::setKey)
                intent.getStringExtra("speed")?.toFloatOrNull()?.let(voice::setSpeed)
            }
            "START" -> voice.start("debug", "debug", "Debug")
            "SAY" -> {
                // --es b64 for text adb's shell would mangle (code, quotes).
                val text = intent.getStringExtra("b64")?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) } ?: intent.getStringExtra("text").orEmpty()
                voice.readAloud(SpaceChatItem.Words("say-" + System.nanoTime(), false, text))
            }
            "HEAR" -> {
                val bytes = File(intent.getStringExtra("file") ?: return).readBytes()
                val b = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                voice.hearRecording(FloatArray(b.remaining()) { b.get(it) / 32768f })
            }
            "HUSH" -> voice.hush()
            "STOP" -> voice.stop()
        }
    }
}
