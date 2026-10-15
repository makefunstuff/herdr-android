package com.makefunstuff.herdr.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.AbstractDirectChannel
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Private key in OpenSSH PEM form plus its `authorized_keys` line. */
class SshIdentity(val privateKeyPem: String, val publicKeyLine: String)

sealed class ConnectError(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Auth(cause: Throwable?) :
        ConnectError("The machine refused the app's SSH key. Add the app's public key to ~/.ssh/authorized_keys there.", cause)

    class HostKeyChanged(val expected: String, val actual: String) :
        ConnectError("This machine's host key changed (expected $expected, got $actual). Re-approve it if you trust the change.")

    class Unreachable(target: String, cause: Throwable?) :
        ConnectError("Couldn't reach $target: ${cause?.message ?: "connection failed"}", cause)

    class HerdrMissing(detail: String) : ConnectError(detail)
}

/** A two-way byte stream to the host. */
interface Duplex : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** A process started over SSH; stdin/stdout carry herdr's newline-delimited JSON. */
class RemoteProcess internal constructor(
    private val session: Session,
    private val command: Session.Command,
) : Duplex {
    val stdin: OutputStream get() = command.outputStream
    val stdout: InputStream get() = command.inputStream
    val stderr: InputStream get() = command.errorStream
    override val input: InputStream get() = stdout
    override val output: OutputStream get() = stdin

    override fun close() {
        runCatching { command.close() }
        runCatching { session.close() }
    }
}

data class CommandResult(val exitStatus: Int?, val stdout: String, val stderr: String)

/** One authenticated SSH connection to a machine, possibly tunnelled through a jump machine. */
class MachineConnection internal constructor(
    private val client: SSHClient,
    private val jump: MachineConnection?,
    val hostKeyFingerprint: String,
) : Closeable {

    val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    /** Starts [command] and returns its streams. The caller closes it. */
    suspend fun start(command: String): RemoteProcess = withContext(Dispatchers.IO) {
        val session = client.startSession()
        try {
            RemoteProcess(session, session.exec(command))
        } catch (t: Throwable) {
            runCatching { session.close() }
            throw t
        }
    }

    /** Runs [command] to completion and collects its output. */
    suspend fun run(command: String, timeoutSeconds: Long = 30): CommandResult = withContext(Dispatchers.IO) {
        client.startSession().use { session ->
            val cmd = session.exec(command)
            cmd.outputStream.close()
            val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = cmd.errorStream.readBytes().toString(Charsets.UTF_8)
            cmd.join(timeoutSeconds, TimeUnit.SECONDS)
            CommandResult(cmd.exitStatus, out, err)
        }
    }

    internal fun openTunnel(host: String, port: Int) = client.newDirectConnection(host, port)

    /**
     * Opens a connection to a Unix socket on the host (OpenSSH `direct-streamlocal`), the same
     * as `ssh -L local.sock:/remote.sock`.
     */
    suspend fun openUnixSocket(path: String): Duplex = withContext(Dispatchers.IO) {
        val channel = DirectStreamLocal(client.connection, path)
        channel.open()
        object : Duplex {
            override val input: InputStream = channel.inputStream
            override val output: OutputStream = channel.outputStream
            override fun close() {
                runCatching { channel.close() }
            }
        }
    }

    override fun close() {
        runCatching { client.disconnect() }
        runCatching { client.close() }
        jump?.close()
    }
}

private class DirectStreamLocal(connection: Connection, private val path: String) :
    AbstractDirectChannel(connection, "direct-streamlocal@openssh.com") {
    override fun buildOpenReq(): SSHPacket = super.buildOpenReq()
        .putString(path)
        .putString("") // reserved
        .putUInt32(0) // reserved
}

object SshConnector {

    /**
     * Connects and authenticates to [machine]. A pinned host key that doesn't match fails with
     * [ConnectError.HostKeyChanged]; an unpinned one is accepted and reported in
     * [MachineConnection.hostKeyFingerprint] so the caller can show and pin it.
     */
    suspend fun connect(
        machine: Machine,
        identity: SshIdentity,
        lookup: (String) -> Machine? = { null },
        depth: Int = 0,
    ): MachineConnection = withContext(Dispatchers.IO) {
        val jump = machine.viaMachineId?.let { viaId ->
            require(depth < 3) { "Jump hosts are nested too deeply" }
            val via = lookup(viaId) ?: throw ConnectError.Unreachable(machine.target, IOException("jump machine is missing"))
            connect(via, identity, lookup, depth + 1)
        }
        val client = SSHClient(DefaultConfig())
        var seen: String? = null
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
                val fp = fingerprint(key)
                seen = fp
                val pinned = machine.hostKey
                return pinned == null || pinned == fp
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
        })
        client.connectTimeout = 15_000
        client.timeout = 0
        try {
            if (jump != null) {
                client.connectVia(jump.openTunnel(machine.host, machine.port), machine.host, machine.port)
            } else {
                client.connect(machine.host, machine.port)
            }
        } catch (e: TransportException) {
            client.close()
            jump?.close()
            val actual = seen
            if (actual != null && machine.hostKey != null && actual != machine.hostKey) {
                throw ConnectError.HostKeyChanged(machine.hostKey, actual)
            }
            throw ConnectError.Unreachable(machine.target, e)
        } catch (e: IOException) {
            client.close()
            jump?.close()
            throw ConnectError.Unreachable(machine.target, e)
        }
        client.connection.keepAlive.keepAliveInterval = 15
        try {
            val keys = client.loadKeys(identity.privateKeyPem, identity.publicKeyLine, null)
            client.authPublickey(machine.user, keys)
        } catch (e: UserAuthException) {
            client.close()
            jump?.close()
            throw ConnectError.Auth(e)
        }
        MachineConnection(client, jump, seen ?: "")
    }

    /** OpenSSH-style `SHA256:…` fingerprint. */
    fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
