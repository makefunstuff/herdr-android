// Stand-in for app/…/ui/common/Attach.kt, which reads picked files, the camera and the phone's
// screenshots through Android's ContentResolver. Scenes put pictures in a composer with `images`.
package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.data.TrayImage

class Attacher(
    val uploading: Boolean,
    val pick: () -> Unit,
    val photos: () -> Unit = pick,
    val camera: () -> Unit = pick,
    val screenshot: () -> Unit = pick,
)

@Composable
fun rememberAttacher(session: HostSession, onAttached: (path: String) -> Unit, onImage: ((TrayImage) -> Boolean)? = null): Attacher = Attacher(false, {})
