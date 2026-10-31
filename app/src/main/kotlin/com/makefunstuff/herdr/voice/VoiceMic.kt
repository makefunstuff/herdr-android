package com.makefunstuff.herdr.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for the microphone before the coordinator's voice comes on, then runs what was asked with
 * whether it was allowed: refused, the voice still reads aloud, it only can't hear you.
 */
@Composable
fun rememberVoiceMic(): ((allowed: Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val then = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        then.value?.invoke(granted)
        then.value = null
    }
    return { after ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) after(true)
        else {
            then.value = after
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
