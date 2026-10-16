package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.BuildConfig
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.LocalRowBackground
import com.makefunstuff.herdr.ui.design.MonoBlock
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val keyState by container.identity.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<KeyDialog?>(null) }

    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("Settings") },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xl),
        ) {
            SectionHeading("SSH key", top = Space.xs)
            Text(
                "The app signs in to your machines with this key. Its private half is encrypted with a key kept in this phone's secure hardware, so it stays through app updates. Back it up to keep it when you reinstall the app or move to a new phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted,
                modifier = Modifier.padding(bottom = Space.m),
            )
            QuietCard {
                CompositionLocalProvider(LocalRowBackground provides c.card) {
                    when (val state = keyState) {
                        IdentityStore.State.Loading -> LoadingState("Reading the key")
                        is IdentityStore.State.Ready -> {
                            MonoBlock(state.publicKey, Modifier.padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.xs))
                            SettingRow("Copy public key", HerdrIcons.ContentCopy) { context.copyToClipboard("public key", state.publicKey, "Public key copied") }
                            SettingRow("Back up", HerdrIcons.Upload, "A passphrase-protected file you keep", chevron = true) { dialog = KeyDialog.Export }
                            SettingRow("Restore or import", HerdrIcons.Download, "A backup, or an existing OpenSSH key", chevron = true) { dialog = KeyDialog.Import(replacing = true) }
                            SettingRow("New key", HerdrIcons.Key, "Replaces this one; machines must trust the new key", chevron = true) { dialog = KeyDialog.New(replacing = true) }
                        }
                        IdentityStore.State.Missing, is IdentityStore.State.Unreadable -> {
                            val unreadable = (state as? IdentityStore.State.Unreadable)?.reason
                            Box(Modifier.padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.xs)) {
                                if (unreadable != null) ErrorMessage("The saved key can't be read ($unreadable). It is kept until you restore or replace it.")
                                else Text("No key yet.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                            }
                            SettingRow("Restore or import", HerdrIcons.Download, "A backup, or an existing OpenSSH key", chevron = true) { dialog = KeyDialog.Import(replacing = unreadable != null) }
                            SettingRow("New key", HerdrIcons.Key, chevron = true) { dialog = KeyDialog.New(replacing = unreadable != null) }
                        }
                    }
                }
            }
            SectionHeading("About")
            QuietCard {
                ListRow(
                    "Herdr for Android",
                    subtitle = "Version ${BuildConfig.VERSION_NAME}",
                    leading = { IconTile(HerdrIcons.Info) },
                )
                Text(
                    "Talks to herdr over SSH using its socket API. Machines stay connected while the app is open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted,
                    modifier = Modifier.padding(start = Space.l, end = Space.l, bottom = Space.l),
                )
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

/** A row of a settings card: icon, label, an optional muted line, the whole row the target. */
@Composable
private fun SettingRow(title: String, icon: ImageVector, detail: String? = null, chevron: Boolean = false, onClick: () -> Unit) {
    ListRow(title, subtitle = detail, leading = { IconTile(icon) }, chevron = chevron, onClick = onClick)
}
