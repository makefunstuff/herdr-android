package com.makefunstuff.herdr.ui.machines

import com.makefunstuff.herdr.ui.design.BarTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.ssh.HerdrBinaries
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.ssh.SshConnector
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.design.ActionRow
import com.makefunstuff.herdr.ui.design.BottomActionBar
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrTextField
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.manage.ConfirmDialog
import com.makefunstuff.herdr.ui.theme.color
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
    var jumpOpen by remember { mutableStateOf(false) }

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

    fun save() {
        val m = build() ?: return
        val fp = (test as? TestResult.Ok)?.fingerprint
        container.machines.upsert(if (m.hostKey == null && fp != null) m.copy(hostKey = fp) else m)
        nav.popBackStack()
    }

    val others = machines.filter { it.id != existing?.id }
    val canBuild = build() != null

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
        bottomBar = {
            BottomActionBar {
                OutlinedButton(
                    onClick = { runTest() },
                    enabled = canBuild && test != TestResult.Running,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Test connection") }
                Button(
                    onClick = { save() },
                    enabled = canBuild,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(if (existing == null) "Add" else "Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeading("Where it is", top = 8.dp)
            HerdrTextField(
                value = target,
                onValueChange = { target = it; test = null },
                label = "SSH target",
                error = targetError,
                hint = "user@host, with :port if it isn't 22",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            HerdrTextField(
                value = label,
                onValueChange = { label = it },
                label = "Name",
                hint = "How the app shows it. Empty: \u201c${parsed?.second?.substringBefore('.') ?: "the host name"}\u201d",
            )

            SectionHeading("herdr there")
            HerdrTextField(
                value = session,
                onValueChange = { session = it },
                label = "herdr session",
                hint = "The herdr session the app opens",
            )
            if (others.isNotEmpty()) {
                Box {
                    HerdrTextField(
                        value = others.firstOrNull { it.id == viaId }?.label ?: "Direct",
                        onValueChange = {},
                        label = "Connect through",
                        hint = "A jump host for a machine only reachable from there",
                        readOnly = true,
                    )
                    Box(Modifier.matchParentSize().clickable(onClickLabel = "Choose a jump host") { jumpOpen = true })
                    Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
                }
                HerdrMenu(expanded = jumpOpen, onDismissRequest = { jumpOpen = false }, title = "Connect through") {
                    HerdrMenuItem(text = { Text("Direct") }, onClick = { viaId = null; jumpOpen = false })
                    others.forEach { o ->
                        HerdrMenuItem(
                            text = { Text(o.label) },
                            leadingIcon = if (o.id == viaId) ({ Icon(Icons.Filled.Check, null) }) else null,
                            onClick = { viaId = o.id; jumpOpen = false },
                        )
                    }
                }
            }
            HerdrTextField(
                value = herdrPath,
                onValueChange = { herdrPath = it },
                label = "herdr path",
                hint = "Leave empty to find it automatically",
            )

            when (val t = test) {
                null -> {}
                TestResult.Running -> QuietCard {
                    LoadingState("Connecting to ${parsed?.second ?: "the machine"}")
                }
                is TestResult.Failed -> ErrorMessage(t.message, onRetry = { runTest() })
                is TestResult.Ok -> ConnectedCard(t, pinnedKey, session) { session = it }
            }

            SectionHeading("This phone's SSH key")
            QuietCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Add it to ~/.ssh/authorized_keys on the machine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        publicKey ?: "No SSH key yet. Set one up in Settings.",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (publicKey != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.clickable { context.copyToClipboard("public key", publicKey!!, "Public key copied") },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp).heightIn(min = 40.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) { Icon(Icons.Filled.Lock, null, modifier = Modifier.size(18.dp)); Text("Copy public key", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }

            pinnedKey?.let { fp ->
                SectionHeading("Trusted host key")
                QuietCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { pinnedKey = null; test = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Forget and check again")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            "Remove ${existing.label}?",
            "Its herdr sessions and agents keep running. Only this phone forgets how to reach them.",
            "Remove machine",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            container.machines.remove(existing.id)
            nav.popBackStack()
            nav.popBackStack()
        }
    }
}

/** What the test found: the host key to check, and the herdr sessions it can pick from. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ConnectedCard(result: TestResult.Ok, pinnedKey: String?, chosen: String, onChoose: (String) -> Unit) {
    QuietCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(AgentStatus.Idle.color())
                Text("Connected", style = MaterialTheme.typography.titleMedium)
            }
            if (pinnedKey == null) {
                Text(
                    "The host key is only seen on the first connection. Check it matches the machine before saving:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(result.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (result.sessions.isEmpty()) {
                Text(
                    "No herdr sessions are running there yet. Start herdr on the machine once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("herdr sessions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.sessions.forEach { s ->
                        FilterChip(
                            selected = chosen == s.name,
                            onClick = { onChoose(s.name) },
                            label = { Text(if (s.running) s.name else "${s.name} (stopped)") },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }
        }
    }
}
