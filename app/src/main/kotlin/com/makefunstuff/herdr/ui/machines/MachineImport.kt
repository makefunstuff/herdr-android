package com.makefunstuff.herdr.ui.machines

import com.makefunstuff.herdr.core.api.SavedMachineEntry
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.data.MachineStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class ImportCandidate(
    val id: String,
    val label: String,
    val user: String,
    val host: String,
    val port: Int,
    val session: String,
    val alreadyAdded: Boolean,
) {
    fun toMachine(viaMachineId: String?) = Machine(
        id = MachineStore.newId(),
        label = label,
        host = host,
        port = port,
        user = user,
        session = session,
        viaMachineId = viaMachineId,
    )
}

/**
 * Reads herdr's saved machines on a host (`herdr machine list --json`) and resolves each SSH
 * target with that host's own `ssh -G`, since the phone has no ~/.ssh/config.
 */
object MachineImport {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(source: HostSession, existing: List<Machine>): List<ImportCandidate> {
        val conn = source.awaitConnection()
        val out = conn.run(HerdrCommands.machineList(source.machine))
        val entries = runCatching {
            json.decodeFromString(ListSerializer(SavedMachineEntry.serializer()), out.stdout.trim().ifEmpty { "[]" })
        }.getOrElse {
            throw IllegalStateException(out.stderr.trim().ifEmpty { "This herdr version can't list its machines" })
        }
        return entries.map { e ->
            val resolved = conn.run(HerdrCommands.resolveSshAlias(e.target.removePrefix("ssh://")))
            val config = resolved.stdout.lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[0].lowercase() to parts[1] else null
            }.toMap()
            val parsed = Machine.parseTarget(e.target)
            val host = config["hostname"] ?: parsed?.second ?: e.target
            val user = config["user"] ?: parsed?.first ?: source.machine.user
            val port = config["port"]?.toIntOrNull() ?: parsed?.third ?: 22
            ImportCandidate(
                id = e.id.ifEmpty { e.target },
                label = e.label.ifEmpty { e.target },
                user = user,
                host = host,
                port = port,
                session = e.session.ifEmpty { "default" },
                alreadyAdded = existing.any { it.host == host && it.port == port && it.user == user && it.session == e.session },
            )
        }
    }
}
