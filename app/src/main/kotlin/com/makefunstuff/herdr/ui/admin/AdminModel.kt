package com.makefunstuff.herdr.ui.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.makefunstuff.herdr.core.admin.HerdrCli
import com.makefunstuff.herdr.core.admin.UpdateChannel
import com.makefunstuff.herdr.core.api.AgentManifestInfo
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.IntegrationInfo
import com.makefunstuff.herdr.core.api.PluginAction
import com.makefunstuff.herdr.core.api.PluginInfo
import com.makefunstuff.herdr.core.api.PluginLog
import com.makefunstuff.herdr.core.api.SavedMachineEntry
import com.makefunstuff.herdr.core.api.plainMessage
import com.makefunstuff.herdr.core.host.HostSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a section shows while it loads, once loaded, or when herdr said no. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * State and actions for a machine's admin screen. herdr sends no events for plugins, integrations
 * or its own settings, so the screen re-reads after every change, when the machine reconnects, and
 * on a slow timer while it's open.
 */
class AdminModel(private val session: HostSession, private val scope: CoroutineScope) {
    var plugins by mutableStateOf<Load<List<PluginInfo>>>(Load.Loading)
    var actions by mutableStateOf<List<PluginAction>>(emptyList())
    var logs by mutableStateOf<Load<List<PluginLog>>>(Load.Loading)
    var integrations by mutableStateOf<Load<List<IntegrationInfo>>>(Load.Loading)
    var manifests by mutableStateOf<Load<List<AgentManifestInfo>>>(Load.Loading)
    var machines by mutableStateOf<Load<List<SavedMachineEntry>>>(Load.Loading)
    var installedVersion by mutableStateOf<String?>(null)
    var channel by mutableStateOf<UpdateChannel?>(null)
    var os by mutableStateOf<String?>(null)

    /** Keys of the things being changed right now, to show progress on them. */
    var busy by mutableStateOf<Set<String>>(emptySet())
        private set

    /** One-off messages for a snackbar: results and errors. */
    var message by mutableStateOf<String?>(null)

    private suspend fun api(): HerdrApi = session.awaitApi()

    private suspend fun cli(): HerdrCli {
        val conn = session.awaitConnection()
        return HerdrCli(session.machine) { cmd, timeout -> conn.run(cmd, timeout) }
    }

    fun refreshAll() {
        refreshPlugins()
        refreshIntegrations()
        refreshServer()
        refreshMachines()
    }

    fun refreshPlugins() = scope.launch {
        if (os == null) {
            os = runCatching { session.awaitConnection().run("uname -s").stdout.trim().lowercase() }.getOrNull()
                ?.let { if (it == "darwin") "macos" else it.ifEmpty { null } }
        }
        plugins = load { api().plugins() }
        actions = runCatching { api().pluginActions() }.getOrDefault(actions)
        logs = load { api().pluginLogs(limit = 100) }
    }

    fun refreshLogs() = scope.launch { logs = load { api().pluginLogs(limit = 100) } }

    fun refreshIntegrations() = scope.launch { integrations = load { api().integrations() } }

    fun refreshServer() = scope.launch {
        manifests = load { api().agentManifests() }
        runCatching { cli() }.getOrNull()?.let { c ->
            installedVersion = runCatching { c.version() }.getOrNull()
            channel = runCatching { c.channel() }.getOrNull()
        }
    }

    fun refreshMachines() = scope.launch { machines = load { cli().machines() } }

    fun setEnabled(plugin: PluginInfo, enabled: Boolean) = act("plugin:${plugin.pluginId}") {
        val updated = api().setPluginEnabled(plugin.pluginId, enabled)
        (plugins as? Load.Ready)?.let { r -> plugins = Load.Ready(r.value.map { if (it.pluginId == updated.pluginId) updated else it }) }
        actions = runCatching { api().pluginActions() }.getOrDefault(actions)
        "${updated.title} ${if (updated.enabled) "enabled" else "disabled"}"
    }

    fun invoke(action: PluginAction) = act("action:${action.pluginId}/${action.actionId}") {
        api().invokePluginAction(action.pluginId, action.actionId)
        logs = load { api().pluginLogs(limit = 100) }
        "Started “${action.title}”"
    }

    fun install(i: IntegrationInfo) = act("integration:${i.target}") {
        val out = api().installIntegration(i.target)
        integrations = load { api().integrations() }
        out.lastOrNull() ?: "Installed the ${i.label} integration"
    }

    fun uninstall(i: IntegrationInfo) = act("integration:${i.target}") {
        val out = api().uninstallIntegration(i.target)
        integrations = load { api().integrations() }
        out.lastOrNull() ?: "Removed the ${i.label} integration"
    }

    fun reloadConfig() = act("reload-config") {
        val r = api().reloadConfig()
        when (r.status) {
            "applied" -> "Config reloaded"
            else -> "Config ${r.status}: " + r.diagnostics.joinToString("; ").ifEmpty { "no details" }
        }
    }

    fun reloadManifests() = act("reload-manifests") {
        val m = api().reloadAgentManifests()
        manifests = Load.Ready(m)
        "Reloaded ${m.size} agent manifest${if (m.size == 1) "" else "s"}"
    }

    fun stopServer(onStopped: () -> Unit) = act("stop") {
        api().stopServer()
        session.refresh()
        onStopped()
        "herdr stopped on ${session.machine.label}"
    }

    fun setChannel(c: UpdateChannel) = act("channel") {
        cli().setChannel(c)
        channel = c
        installedVersion = runCatching { cli().version() }.getOrNull()
        "Update channel set to ${c.wire}" + (installedVersion?.let { " · herdr $it installed" } ?: "")
    }

    fun update(handoff: Boolean) = act("update") {
        val out = cli().update(handoff)
        installedVersion = runCatching { cli().version() }.getOrNull()
        out.lines().lastOrNull { it.isNotBlank() } ?: "herdr is up to date"
    }

    /** Why the last "save machine" failed, shown in its sheet. */
    var addError by mutableStateOf<String?>(null)

    fun addMachine(target: String, label: String?, sessionName: String?, onDone: () -> Unit) = act("machine-add", onError = { addError = it }) {
        addError = null
        cli().addMachine(target, label, sessionName)
        machines = load { cli().machines() }
        onDone()
        "Saved ${label?.ifBlank { null } ?: target}"
    }

    fun removeMachine(m: SavedMachineEntry) = act("machine:${m.id}") {
        cli().removeMachine(m.id)
        machines = load { cli().machines() }
        "Removed ${m.label.ifBlank { m.target }}"
    }

    fun isBusy(key: String) = key in busy

    private fun act(key: String, onError: ((String) -> Unit)? = null, block: suspend () -> String) = scope.launch {
        if (key in busy) return@launch
        busy = busy + key
        try {
            message = block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (onError != null) onError(t.plainMessage()) else message = t.plainMessage()
        } finally {
            busy = busy - key
        }
    }

    private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Load.Failed(t.plainMessage())
    }
}
