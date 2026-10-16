package com.makefunstuff.herdr.core.host

import com.makefunstuff.herdr.core.api.ApiClient
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.ConnectError
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrBinary
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Bootstrap before the server's version is known. A discovered executable may belong to another
 * session, so try each candidate until a bridge actually answers on the requested session.
 * An explicit path remains an override; errors name every binary tried.
 */
internal suspend fun findApiBridge(
    machine: Machine,
    found: List<HerdrBinary>,
    run: suspend (String) -> CommandResult,
    start: suspend (String) -> Duplex,
): Machine {
    val candidates = if (!machine.herdrPath.isNullOrBlank()) {
        listOf(machine)
    } else {
        found.map { machine.copy(herdrPath = it.path) }.ifEmpty { listOf(machine) }
    }
    val failures = mutableListOf<String>()
    for (candidate in candidates) {
        try {
            val check = run(HerdrCommands.apiBridgeCheck(candidate))
            if (check.exitStatus != 0 || check.stdout.trim() != "herdr-api-bridge-v1") {
                throw IOException(check.stderr.trim().ifEmpty { "remote-api-bridge isn't supported" })
            }
            // --check only tests the client. Verify that it can also talk to this session's server.
            HerdrApi(ApiClient { start(HerdrCommands.apiBridge(candidate)) }).ping()
            return candidate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val binary = found.firstOrNull { it.path == candidate.herdrPath }
            val description = "${candidate.herdrPath ?: "herdr from the usual paths"} (${binary?.version ?: "unknown version"})"
            failures += "$description: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    throw ConnectError.HerdrMissing(
        "Couldn't reach herdr session \"${machine.session}\" on ${machine.label} using remote-api-bridge; " +
            "SSH socket forwarding is unavailable. Tried: ${failures.joinToString("; ")}. " +
            "Set this machine's herdr path to the server's binary, or enable SSH socket forwarding.",
    )
}
