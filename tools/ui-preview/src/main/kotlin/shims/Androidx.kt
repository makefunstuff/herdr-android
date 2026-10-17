// androidx APIs that exist only on Android, for the desktop preview.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.compose

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

/** No pickers on the desktop: launching does nothing. */
@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ActivityResultLauncher<I> =
    remember { ActivityResultLauncher() }
