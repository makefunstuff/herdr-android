// Stand-in for app/…/voice/VoiceMic.kt (Android's permission dialog): the microphone is allowed.
package com.makefunstuff.herdr.voice

import androidx.compose.runtime.Composable

@Composable
fun rememberVoiceMic(): ((allowed: Boolean) -> Unit) -> Unit = { after -> after(true) }
