package com.makefunstuff.herdr.ui.machines

import com.makefunstuff.herdr.ui.design.BarTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.ArrowDropDown
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.api.SessionEntry
import com.makefunstuff.herdr.core.ssh.HerdrBinaries
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.ssh.SshConnector
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private sealed interface TestResult {
    data object Running : TestResult
    data class Ok(val fingerprint: String, val sessions: List<SessionEntry>) : TestResult
    data class Failed(val message: String) : TestResult
}

/** Add a machine (id == null) or edit an existing one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMachineScreen(nav: NavController, id: String?) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(id) { id?.let { container.machines.get(it) } }
    val machines by container.machines.machines.collectAsStateWithLifecycle()
    val publicKey by container.identity.publicKey.collectAsStateWithLifecycle()

    var target by remember { mutableStateOf(existing?.target ?: "") }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var session by remember { mutableStateOf(existing?.session ?: "default") }
    var herdrPath by remember { mutableStateOf(existing?.herdrPath ?: "") }
    var viaId by remember { mutableStateOf(existing?.viaMachineId) }
    var pinnedKey by remember { mutableStateOf(existing?.hostKey) }
    var test by remember { mutableStateOf<TestResult?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val parsed = Machine.parseTarget(target)
    val targetError = when {
        target.isBlank() -> null
        parsed == null -> "Use user@host or user@host:port"
        parsed.first == null -> "Add the user name, like you@${parsed.second}"
        else -> null
    }

    fun build(): Machine? {
        val (user, host, port) = parsed ?: return null
        if (user == null) return null
        return Machine(
            id = existing?.id ?: MachineStore.newId(),
            label = label.trim().ifEmpty { host.substringBefore('.') },
            host = host,
            port = port,
            user = user,
            session = session.trim().ifEmpty { "default" },
            hostKey = pinnedKey,
            viaMachineId = viaId,
            herdrPath = herdrPath.trim().ifEmpty { null },
            enabled = existing?.enabled ?: true,
        )
    }

    fun runTest() {
        val m = build() ?: return
        test = TestResult.Running
        scope.launch {
            test = try {
                val conn = SshConnector.connect(m.copy(hostKey = pinnedKey), container.identity.get(), { container.machines.get(it) })
                try {
                    val found = HerdrBinaries.parse(runCatching { conn.run(HerdrCommands.probe(m)).stdout }.getOrDefault(""))
                    val herdr = HerdrBinaries.choose(found, null)?.let { m.copy(herdrPath = it.path) } ?: m
                    val out = conn.run(HerdrCommands.sessionList(herdr))
                    if (out.exitStatus == HerdrCommands.NOT_FOUND_EXIT) {
                        TestResult.Failed("Connected, but herdr wasn't found. Install it on this machine or set its path below.")
                    } else {
                        val sessions = runCatching {
                            val json = Json { ignoreUnknownKeys = true }
                            json.decodeFromJsonElement(
                                ListSerializer(SessionEntry.serializer()),
                                json.parseToJsonElement(out.stdout).jsonObject["sessions"]!!,
                            )
                        }.getOrElse { emptyList() }
                        TestResult.Ok(conn.hostKeyFingerprint, sessions)
                    }
                } finally {
                    conn.close()
                }
            } catch (t: Throwable) {
                TestResult.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { BarTitle(if (existing == null) "Add machine" else "Edit ${existing.label}") },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Remove machine") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (confirmDelete && existing != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Remove ${existing.label} from the app? Its herdr sessions and agents keep running.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                container.machines.remove(existing.id)
                                nav.popBackStack()
                                nav.popBackStack()
                            }) { Text("Remove") }
                            TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = target,
                onValueChange = { target = it; test = null },
                label = { Text("SSH target") },
                placeholder = { Text("you@workbox.tail1234.ts.net") },
                supportingText = { Text(targetError ?: "user@host, with :port if it isn't 22") },
                isError = targetError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name") },
                placeholder = { Text(parsed?.second?.substringBefore('.') ?: "workbox") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = session,
                onValueChange = { session = it },
                label = { Text("herdr session") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val others = machines.filter { it.id != existing?.id }
            if (others.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedTextField(
                        value = others.firstOrNull { it.id == viaId }?.label ?: "Direct",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Connect through") },
                        supportingText = { Text("Use another machine as a jump host when this one is only reachable from there") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.matchParentSize().clickable(onClickLabel = "Choose a jump host") { open = true })
                }
                HerdrMenu(expanded = open, onDismissRequest = { open = false }) {
                    HerdrMenuItem(text = { Text("Direct") }, onClick = { viaId = null; open = false })
                    others.forEach { o ->
                        HerdrMenuItem(text = { Text(o.label) }, onClick = { viaId = o.id; open = false })
                    }
                }
            }

            OutlinedTextField(
                value = herdrPath,
                onValueChange = { herdrPath = it },
                label = { Text("herdr path (optional)") },
                placeholder = { Text("Found automatically") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This phone's SSH key", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Add it to ~/.ssh/authorized_keys on the machine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(publicKey ?: "No SSH key yet. Set one up in Settings.", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { publicKey?.let { context.copyToClipboard("public key", it, "Public key copied") } }) {
                        Text("Copy public key")
                    }
                }
            }

            pinnedKey?.let { fp ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Trusted host key", style = MaterialTheme.typography.titleSmall)
                        Text(fp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { pinnedKey = null; test = null }) { Text("Forget and re-check") }
                    }
                }
            }

            when (val t = test) {
                null -> {}
                TestResult.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.makefunstuff.herdr.ui.design.LoadingState("Connecting")
                }
                is TestResult.Failed -> com.makefunstuff.herdr.ui.design.ErrorMessage(t.message)
                is TestResult.Ok -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Connected", style = MaterialTheme.typography.titleSmall)
                        if (pinnedKey == null) {
                            Text("Host key on first connection. Check it matches the machine before saving:", style = MaterialTheme.typography.bodySmall)
                            Text(t.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        if (t.sessions.isEmpty()) {
                            Text("No herdr sessions are running there yet. Start herdr on the machine once.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("herdr sessions", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                t.sessions.forEach { s ->
                                    FilterChip(
                                        selected = session == s.name,
                                        onClick = { session = s.name },
                                        label = { Text(if (s.running) s.name else "${s.name} (stopped)") },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runTest() }, enabled = build() != null && test != TestResult.Running) { Text("Test connection") }
                Button(
                    onClick = {
                        val m = build() ?: return@Button
                        val fp = (test as? TestResult.Ok)?.fingerprint
                        container.machines.upsert(if (m.hostKey == null && fp != null) m.copy(hostKey = fp) else m)
                        nav.popBackStack()
                    },
                    enabled = build() != null,
                ) { Text("Save") }
            }
        }
    }
}
