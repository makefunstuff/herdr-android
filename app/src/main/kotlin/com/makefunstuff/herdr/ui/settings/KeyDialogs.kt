package com.makefunstuff.herdr.ui.settings

import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrSheet
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.keys.PassphraseException
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PASSPHRASE = 8
private const val MAX_KEY_FILE = 64 * 1024

/**
 * Restores a backup made by the app or imports an existing OpenSSH (or PEM) private key, pasted or
 * picked as a file. With [replacing] the dialog says that the current key goes away.
 */
@Composable
fun ImportKeyDialog(replacing: Boolean, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var needsPassphrase by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { context.readSmallText(uri) }
                .onSuccess {
                    text = it
                    error = null
                }
                .onFailure { error = it.message ?: "Couldn't open that file" }
        }
    }

    HerdrDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (replacing) "Replace the SSH key" else "Restore or import a key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose a key backup saved from this app, or an OpenSSH private key (a file like ~/.ssh/id_ed25519). You can also paste it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (replacing) {
                    Text(
                        "This replaces the app's current key. Machines that only trust the current key will refuse the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Choose file") }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        error = null
                    },
                    label = { Text(if (needsPassphrase) "Passphrase" else "Passphrase (if it has one)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { com.makefunstuff.herdr.ui.design.ErrorMessage(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { container.identity.import(text, passphrase.ifEmpty { null }, replace = replacing) }
                            .onSuccess {
                                container.hosts.retryAll()
                                context.toast("Key saved")
                                onDismiss()
                            }
                            .onFailure {
                                if (it is PassphraseException) needsPassphrase = true
                                error = it.message ?: "Couldn't read that key"
                            }
                        busy = false
                    }
                },
            ) { Text(if (replacing) "Replace key" else "Use this key") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Saves the key, encrypted with a passphrase the human picks, to a file of their choice. */
@Composable
fun ExportKeyDialog(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching {
                val backup = container.identity.export(passphrase)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(backup.toByteArray(Charsets.UTF_8)) }
                }
            }
                .onSuccess {
                    context.toast("Key backup saved")
                    onDismiss()
                }
                .onFailure { context.toast(it.message ?: "Couldn't save the backup") }
            busy = false
        }
    }

    val tooShort = passphrase.length < MIN_PASSPHRASE
    val mismatch = repeat.isNotEmpty() && repeat != passphrase
    HerdrDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Back up the SSH key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Saves the private key as an OpenSSH key file protected by a passphrase. Restore it here after reinstalling the app or on a new phone, and your machines keep trusting it. Without the passphrase the file is useless, so don't lose it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    supportingText = { Text("At least $MIN_PASSPHRASE characters") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = { Text("Repeat passphrase") },
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text("Doesn't match") }) else null,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !tooShort && repeat == passphrase && !busy,
                onClick = { save.launch("herdr-android-key") },
            ) { Text("Save file") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks before making a new key; [replacing] says the current one is thrown away. */
@Composable
fun NewKeyDialog(replacing: Boolean, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    HerdrDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (replacing) "Replace the key with a new one?" else "Make a new key?") },
        text = {
            Text(
                if (replacing) {
                    "The current key is deleted for good. Your machines will refuse the app until you add the new public key to them. Back up the current key first if you may need it again."
                } else {
                    "You'll add the new public key to each machine's ~/.ssh/authorized_keys. If you have a backup of an earlier key, restore it instead."
                },
            )
        },
        confirmButton = {
            // The dialog's scope ends when it closes, so it closes once the key is saved.
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { container.identity.create(replace = replacing) }
                        .onSuccess { container.hosts.retryAll() }
                        .onFailure { context.toast(it.message ?: "Couldn't make a key") }
                    busy = false
                    onDismiss()
                }
            }) { Text(if (replacing) "Replace key" else "Make new key") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shown on launch while the app has no usable key: restoring comes first, so a reinstall or a
 * new phone can keep the key the machines already trust.
 */
@Composable
fun KeySetupDialog(unreadable: String?, onDismiss: () -> Unit) {
    var importing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    when {
        importing -> ImportKeyDialog(replacing = unreadable != null, onDismiss = { importing = false })
        creating -> NewKeyDialog(replacing = unreadable != null, onDismiss = { creating = false })
        else -> HerdrDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (unreadable != null) "The SSH key can't be read" else "Set up the SSH key") },
            text = {
                Text(
                    if (unreadable != null) {
                        "The app's saved key can't be decrypted ($unreadable). Restore a backup or import your key; the saved one is kept until you do."
                    } else {
                        "The app signs in to your machines with an SSH key. If you used Herdr before, restore your key backup so your machines keep trusting it. Otherwise import an existing OpenSSH key or make a new one."
                    },
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { importing = true }) { Text("Restore or import") }
                    TextButton(onClick = { creating = true }) { Text("Make a new key") }
                    TextButton(onClick = onDismiss) { Text("Later") }
                }
            },
        )
    }
}

private suspend fun Context.readSmallText(uri: Uri): String = withContext(Dispatchers.IO) {
    val out = java.io.ByteArrayOutputStream()
    contentResolver.openInputStream(uri)!!.use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            require(out.size() <= MAX_KEY_FILE) { "That file is too big to be a key." }
        }
    }
    out.toString(Charsets.UTF_8.name())
}
