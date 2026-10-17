// Stand-in for app/…/ui/common/Attach.kt, which reads picked files through Android's ContentResolver.
package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import com.makefunstuff.herdr.core.host.HostSession

class Attacher(val uploading: Boolean, val pick: () -> Unit)

@Composable
fun rememberAttacher(session: HostSession, onAttached: (path: String) -> Unit): Attacher = Attacher(false) {}
