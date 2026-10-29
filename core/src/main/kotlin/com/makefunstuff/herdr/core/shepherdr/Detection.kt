package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.ssh.MachineConnection
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.CancellationException

/** Which board a machine runs: Shepherdr 2 when its API answers with 2.x or later, else herdr-projects 1.x's launcher. */
object BoardDetection {
    const val PORT = 7787

    /** v2 wins when both answer: the v1 launcher may be an old one left on disk. */
    fun decide(v2: V2Version?, v1Path: String?): BoardApi = when {
        v2 != null && v2.major >= 2 -> BoardApi.V2(v2.version)
        v1Path != null -> BoardApi.V1(v1Path)
        else -> BoardApi.None
    }

    /** What answers on [transport], or null when nothing (or not Shepherdr) does. */
    suspend fun probe(transport: ShepherdrTransport): V2Version? = try {
        ShepherdrClient(transport).version().takeIf { it.major >= 2 }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Reaches Shepherdr on [conn]'s machine: through an SSH port forward to its 127.0.0.1:[PORT], or,
     * where the SSH server refuses to forward, `curl` there. The transport that answered with its
     * version, which the caller keeps (one per machine) and closes with the connection; null where
     * no Shepherdr 2 answers.
     */
    suspend fun open(conn: MachineConnection): Pair<ShepherdrTransport, V2Version>? {
        // Any free port on the phone: the forward is only ever reached by the app itself.
        val forward = runCatching { ForwardTransport.over(PortForward("127.0.0.1", PORT, preferred = 0) { h, p -> conn.openTcp(h, p) }) }.getOrNull()
        if (forward != null) {
            val version = try {
                probe(forward)
            } catch (e: CancellationException) {
                forward.close()
                throw e
            }
            version?.let { return forward to it }
            forward.close()
        }
        val curl = CurlTransport { command, stdin, seconds -> conn.runWithInput(command, CurlTransport.stdin(stdin), seconds) }
        return probe(curl)?.let { curl to it }
    }
}
