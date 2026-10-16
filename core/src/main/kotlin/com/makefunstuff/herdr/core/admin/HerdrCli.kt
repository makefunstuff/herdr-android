package com.makefunstuff.herdr.core.admin

import com.makefunstuff.herdr.core.api.SavedMachineEntry
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** herdr's update channels (`herdr channel set`). */
enum class UpdateChannel(val wire: String) {
    Stable("stable"),
    Preview("preview");

    companion object {
        fun of(value: String): UpdateChannel? = entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/** A herdr CLI command failed; [message] is what herdr printed, fit to show as is. */
class HerdrCliException(message: String) : IOException(message)

/**
 * The admin features herdr has only on its command line (update, channel, saved machines), run
 * over SSH. [run] executes one remote shell command, with a timeout in seconds.
 */
class HerdrCli(
    private val machine: Machine,
    private val run: suspend (command: String, timeoutSeconds: Long) -> CommandResult,
) {
    /** The installed herdr version, such as `0.9.5` (may differ from the running server's after an update). */
    suspend fun version(): String = parseVersion(check(HerdrCommands.version(machine)).stdout)

    suspend fun channel(): UpdateChannel? = UpdateChannel.of(check(HerdrCommands.channelShow(machine)).stdout)

    suspend fun setChannel(channel: UpdateChannel) {
        check(HerdrCommands.channelSet(machine, channel.wire))
    }

    /** Downloads and installs the latest herdr on the channel; returns herdr's output. */
    suspend fun update(handoff: Boolean): String {
        val r = check(HerdrCommands.update(machine, handoff), timeoutSeconds = 600)
        return (r.stdout + "\n" + r.stderr).trim()
    }

    suspend fun machines(): List<SavedMachineEntry> = parseMachines(check(HerdrCommands.machineList(machine)).stdout)

    /** Saves a machine; herdr connects to it from this host to prepare it, so it can take a while. */
    suspend fun addMachine(sshTarget: String, label: String?, session: String?): String {
        val r = check(HerdrCommands.machineAdd(machine, sshTarget, label, session), timeoutSeconds = 180)
        return (r.stdout + "\n" + r.stderr).trim()
    }

    suspend fun removeMachine(profileId: String) {
        check(HerdrCommands.machineRemove(machine, profileId))
    }

    private suspend fun check(command: String, timeoutSeconds: Long = 30): CommandResult {
        val r = run(command, timeoutSeconds)
        if (r.exitStatus != 0) throw HerdrCliException(failureMessage(r))
        return r
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun parseVersion(out: String): String =
            Regex("""\d+\.\d+\.\d+[\w.+-]*""").find(out)?.value ?: out.trim()

        fun parseMachines(out: String): List<SavedMachineEntry> = try {
            json.decodeFromString(ListSerializer(SavedMachineEntry.serializer()), out.trim().ifEmpty { "[]" })
        } catch (e: Exception) {
            throw HerdrCliException("herdr on this machine can't list its saved machines")
        }

        /** The useful part of a failed command's output, without herdr's help footer. */
        fun failureMessage(r: CommandResult): String {
            if (r.exitStatus == HerdrCommands.NOT_FOUND_EXIT) return HerdrCommands.NOT_FOUND_MESSAGE
            val text = (r.stderr.ifBlank { r.stdout })
                .substringBefore("Are you an AI?")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.removePrefix("error: ").removePrefix("Error: ") }
            return text.take(4).joinToString("\n").ifEmpty {
                if (r.exitStatus == null) "herdr didn't finish in time" else "herdr exited with status ${r.exitStatus}"
            }
        }
    }
}
