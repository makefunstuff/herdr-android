package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.BuildConfig
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val keyState by container.identity.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<KeyDialog?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SSH key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The app signs in to your machines with this key. Its private half is encrypted with a key kept in this phone's secure hardware, so it stays through app updates. Back it up to keep it when you reinstall the app or move to a new phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (val state = keyState) {
                        IdentityStore.State.Loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                        is IdentityStore.State.Ready -> {
                            Text(state.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { context.copyToClipboard("public key", state.publicKey, "Public key copied") }) { Text("Copy") }
                                OutlinedButton(onClick = { dialog = KeyDialog.Export }) { Text("Back up") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { dialog = KeyDialog.Import(replacing = true) }) { Text("Restore or import") }
                                TextButton(onClick = { dialog = KeyDialog.New(replacing = true) }) { Text("New key") }
                            }
                        }
                        IdentityStore.State.Missing, is IdentityStore.State.Unreadable -> {
                            val unreadable = (state as? IdentityStore.State.Unreadable)?.reason
                            Text(
                                if (unreadable != null) "The saved key can't be read ($unreadable). It is kept until you restore or replace it." else "No key yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (unreadable != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { dialog = KeyDialog.Import(replacing = unreadable != null) }) { Text("Restore or import") }
                                TextButton(onClick = { dialog = KeyDialog.New(replacing = unreadable != null) }) { Text("New key") }
                            }
                        }
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text("Herdr for Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Talks to herdr over SSH using its socket API. Machines stay connected while the app is open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        KeyDialog.Export -> ExportKeyDialog(onDismiss = { dialog = null })
        is KeyDialog.Import -> ImportKeyDialog(replacing = d.replacing, onDismiss = { dialog = null })
        is KeyDialog.New -> NewKeyDialog(replacing = d.replacing, onDismiss = { dialog = null })
    }
}

private sealed interface KeyDialog {
    data object Export : KeyDialog
    data class Import(val replacing: Boolean) : KeyDialog
    data class New(val replacing: Boolean) : KeyDialog
}
