package com.makefunstuff.herdr.core.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Typed calls on top of an [ApiCaller]. Method names follow herdr's socket API. */
class HerdrApi(private val connection: ApiCaller) {

    suspend fun ping(): PongInfo = decode(connection.call("ping"))

    suspend fun snapshot(): SessionSnapshot =
        HerdrJson.decodeFromJsonElement(connection.call("session.snapshot")["snapshot"] ?: JsonObject(emptyMap()))

    suspend fun agents(): List<AgentInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("agent.list")["agents"] ?: JsonArray(emptyList()))

    suspend fun readPane(paneId: String, source: String = "recent", lines: Int = 80): PaneRead =
        HerdrJson.decodeFromJsonElement(
            connection.call("pane.read", params("pane_id" to paneId, "source" to source, "lines" to lines))["read"]
                ?: JsonObject(emptyMap()),
        )

    /** Types [text] into an agent and submits it. */
    suspend fun prompt(target: String, text: String) {
        connection.call("agent.prompt", params("target" to target, "text" to text))
    }

    /** Sends herdr key-combo strings such as `enter`, `esc`, `ctrl+c`, `1`. */
    suspend fun sendKeys(paneId: String, keys: List<String>) {
        connection.call("pane.send_keys", params("pane_id" to paneId, "keys" to JsonArray(keys.map(::JsonPrimitive))))
    }

    suspend fun sendText(paneId: String, text: String) {
        connection.call("pane.send_text", params("pane_id" to paneId, "text" to text))
    }

    suspend fun createWorkspace(label: String?, cwd: String?) {
        connection.call("workspace.create", params("label" to label, "cwd" to cwd, "focus" to false))
    }

    suspend fun createTab(workspaceId: String, label: String?) {
        connection.call("tab.create", params("workspace_id" to workspaceId, "label" to label, "focus" to false))
    }

    suspend fun splitPane(paneId: String, direction: String) {
        connection.call("pane.split", params("target_pane_id" to paneId, "direction" to direction, "focus" to false))
    }

    suspend fun closePane(paneId: String) {
        connection.call("pane.close", params("pane_id" to paneId))
    }

    /** Starts an agent of [kind] (claude, codex, pi…) named [name] in a new split next to [paneId]. */
    suspend fun startAgent(name: String, kind: String, paneId: String) {
        connection.call(
            "agent.start",
            params("name" to name, "kind" to kind, "pane_id" to paneId),
            timeoutMs = 60_000,
        )
    }

    // --- Plugins ---

    suspend fun plugins(): List<PluginInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("plugin.list")["plugins"] ?: JsonArray(emptyList()))

    /** Enables or disables a plugin and returns it as herdr now reports it. */
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): PluginInfo =
        HerdrJson.decodeFromJsonElement(
            connection.call(if (enabled) "plugin.enable" else "plugin.disable", params("plugin_id" to pluginId))["plugin"]
                ?: throw HerdrApiException("bad_response", "herdr didn't return the plugin"),
        )

    suspend fun pluginActions(pluginId: String? = null): List<PluginAction> =
        HerdrJson.decodeFromJsonElement(
            connection.call("plugin.action.list", params("plugin_id" to pluginId))["actions"] ?: JsonArray(emptyList()),
        )

    /** Starts a plugin action. herdr runs it in the background; the returned log is its first state. */
    suspend fun invokePluginAction(pluginId: String, actionId: String): PluginLog? =
        connection.call(
            "plugin.action.invoke",
            params("plugin_id" to pluginId, "action_id" to actionId, "context" to params("invocation_source" to "herdr-android")),
        )["log"]?.let { HerdrJson.decodeFromJsonElement<PluginLog>(it) }

    /** Recent plugin command runs, newest first. */
    suspend fun pluginLogs(pluginId: String? = null, limit: Int? = 50): List<PluginLog> =
        HerdrJson.decodeFromJsonElement<List<PluginLog>>(
            connection.call("plugin.log.list", params("plugin_id" to pluginId, "limit" to limit))["logs"] ?: JsonArray(emptyList()),
        ).sortedByDescending { it.startedUnixMs }

    // --- Integrations ---

    suspend fun integrations(): List<IntegrationInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("integration.list")["integrations"] ?: JsonArray(emptyList()))

    /** Installs herdr's hook for an agent; returns herdr's messages about what it did. */
    suspend fun installIntegration(target: String): List<String> =
        messages(connection.call("integration.install", params("target" to target), timeoutMs = 60_000))

    suspend fun uninstallIntegration(target: String): List<String> =
        messages(connection.call("integration.uninstall", params("target" to target), timeoutMs = 60_000))

    // --- Server ---

    suspend fun reloadConfig(): ConfigReload = decode(connection.call("server.reload_config"))

    suspend fun agentManifests(): List<AgentManifestInfo> =
        HerdrJson.decodeFromJsonElement(connection.call("server.agent_manifests")["manifests"] ?: JsonArray(emptyList()))

    suspend fun reloadAgentManifests(): List<AgentManifestInfo> =
        HerdrJson.decodeFromJsonElement(
            connection.call("server.reload_agent_manifests", timeoutMs = 60_000)["manifests"] ?: JsonArray(emptyList()),
        )

    /** Stops the herdr server, closing every pane in the session. */
    suspend fun stopServer() {
        connection.call("server.stop")
    }

    private fun messages(result: JsonObject): List<String> =
        (result["details"] as? JsonObject)?.get("messages")?.let { HerdrJson.decodeFromJsonElement<List<String>>(it) } ?: emptyList()

    private inline fun <reified T> decode(obj: JsonObject): T = HerdrJson.decodeFromJsonElement(obj)

    companion object {
        /** Lifecycle events that change what the app shows. */
        val LIFECYCLE_EVENTS = listOf(
            "workspace.created", "workspace.updated", "workspace.renamed", "workspace.closed",
            "workspace.reordered", "tab.created", "tab.closed", "tab.renamed", "tab.moved",
            "pane.created", "pane.closed", "pane.updated", "pane.moved", "pane.exited",
            "pane.agent_detected", "layout.updated",
        )

        fun subscriptionParams(paneIds: List<String>): JsonObject {
            val entries = LIFECYCLE_EVENTS.map { JsonObject(mapOf("type" to JsonPrimitive(it))) } +
                paneIds.map {
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("pane.agent_status_changed"),
                            "pane_id" to JsonPrimitive(it),
                        ),
                    )
                }
            return params("subscriptions" to JsonArray(entries))
        }
    }
}
