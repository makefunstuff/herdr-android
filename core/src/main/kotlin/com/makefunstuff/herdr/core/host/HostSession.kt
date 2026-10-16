package com.makefunstuff.herdr.core.host

import com.makefunstuff.herdr.core.api.ApiClient
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.api.SessionEntry
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.BoardClient
import com.makefunstuff.herdr.core.ssh.ConnectError
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.ssh.MachineConnection
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.SshConnector
import com.makefunstuff.herdr.core.ssh.SshIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

sealed interface HostStatus {
    data object Stopped : HostStatus
    data object Connecting : HostStatus
    data object Online : HostStatus
    data class Reconnecting(val message: String, val retryInSeconds: Int) : HostStatus

    /** Needs the user: authentication, host key, or herdr missing on the machine. */
    data class Attention(val message: String, val kind: Kind) : HostStatus {
        enum class Kind { Auth, HostKey, Herdr, Other }
    }
}

data class HostState(
    val machine: Machine,
    val status: HostStatus = HostStatus.Stopped,
    val snapshot: SessionSnapshot? = null,
    val herdrVersion: String? = null,
    /** The herdr-projects launcher on the machine; null when the board isn't installed (or not checked yet). */
    val boardPath: String? = null,
) {
    val hasBoard: Boolean get() = boardPath != null

    val online: Boolean get() = status == HostStatus.Online
}

/**
 * Keeps one machine connected and its herdr state fresh.
 *
 * Follows herdr's documented cache recipe: subscribe first, then read `session.snapshot`, and
 * treat every event as a reason to re-read rather than a patch. A slow periodic refresh covers
 * anything the subscriptions miss.
 */
class HostSession(
    machine: Machine,
    private val identity: suspend () -> SshIdentity,
    private val lookup: (String) -> Machine?,
    parentScope: CoroutineScope,
    /** Called with the fingerprint the first time an unpinned machine connects. */
    private val onHostKeyLearned: (machineId: String, fingerprint: String) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val _state = MutableStateFlow(HostState(machine))
    val state: StateFlow<HostState> = _state.asStateFlow()

    private var loop: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var connection: MachineConnection? = null
    @Volatile private var api: HerdrApi? = null

    val machine: Machine get() = _state.value.machine

    fun updateMachine(machine: Machine) {
        val old = this.machine
        val reconnect = old.host != machine.host || old.port != machine.port || old.user != machine.user ||
            old.session != machine.session || old.viaMachineId != machine.viaMachineId ||
            old.herdrPath != machine.herdrPath || (old.hostKey != null && old.hostKey != machine.hostKey)
        _state.update { it.copy(machine = machine) }
        if (reconnect && loop?.isActive == true) {
            stop()
            start()
        }
    }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        teardown()
        _state.update { it.copy(status = HostStatus.Stopped) }
    }

    fun retry() {
        retryNow.trySend(Unit)
    }

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    /** Waits for the machine to come online and returns its API. */
    suspend fun awaitApi(timeoutMs: Long = 20_000): HerdrApi {
        start()
        return withTimeoutOrNull(timeoutMs) {
            state.first { it.online }
            api
        } ?: throw IOException(statusMessage())
    }

    /** Waits for the machine and returns its SSH connection, for terminal streams. */
    suspend fun awaitConnection(timeoutMs: Long = 20_000): MachineConnection {
        awaitApi(timeoutMs)
        return connection ?: throw IOException(statusMessage())
    }

    /** The work board on this machine, when it has one. */
    suspend fun awaitBoard(timeoutMs: Long = 20_000): BoardClient {
        val conn = awaitConnection(timeoutMs)
        val path = state.value.boardPath ?: throw IOException("herdr-projects isn't installed on ${machine.label}")
        return BoardClient(conn, machine, path)
    }

    /** Looks for the board again, for one installed or removed since the machine connected. */
    suspend fun checkBoard() {
        val conn = connection ?: return
        val path = runCatching { BoardClient.locate(conn) }.getOrElse { return }
        _state.update { it.copy(boardPath = path) }
    }

    private fun statusMessage(): String = when (val s = state.value.status) {
        is HostStatus.Attention -> s.message
        is HostStatus.Reconnecting -> s.message
        else -> "${machine.label} is not connected"
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (true) {
            _state.update { it.copy(status = HostStatus.Connecting) }
            val failure: Throwable = try {
                session()
            } catch (e: CancellationException) {
                teardown()
                throw e
            } catch (t: Throwable) {
                t
            }
            teardown()
            val (status, wait) = classify(failure, attempt)
            _state.update { it.copy(status = status) }
            attempt = if (status is HostStatus.Attention) attempt else attempt + 1
            if (withTimeoutOrNull(wait * 1000L) { retryNow.receive() } != null) attempt = 0
        }
    }

    private fun classify(t: Throwable, attempt: Int): Pair<HostStatus, Int> = when (t) {
        is ConnectError.Auth -> HostStatus.Attention(t.message ?: "Authentication failed", HostStatus.Attention.Kind.Auth) to 120
        is ConnectError.HostKeyChanged -> HostStatus.Attention(t.message ?: "Host key changed", HostStatus.Attention.Kind.HostKey) to 3600
        is ConnectError.HerdrMissing -> HostStatus.Attention(t.message ?: "herdr missing", HostStatus.Attention.Kind.Herdr) to 120
        else -> {
            val wait = minOf(120, 1 shl minOf(attempt, 7))
            HostStatus.Reconnecting(t.message ?: t.javaClass.simpleName, wait) to wait
        }
    }

    /** Runs one connected session until something breaks. */
    private suspend fun session(): Nothing = coroutineScope {
        val m = machine
        val conn = SshConnector.connect(m, identity(), lookup)
        connection = conn
        if (m.hostKey == null && conn.hostKeyFingerprint.isNotEmpty()) {
            _state.update { it.copy(machine = it.machine.copy(hostKey = conn.hostKeyFingerprint)) }
            onHostKeyLearned(m.id, conn.hostKeyFingerprint)
        }

        val opener = apiOpener(conn, m)
        val herdr = HerdrApi(ApiClient(opener))
        val pong = herdr.ping()
        api = herdr

        // Subscribe before the first snapshot so no change slips between them.
        val events = subscribe(opener, HerdrApi.subscriptionParams(emptyList()))

        val snapshot = herdr.snapshot()
        _state.update { it.copy(status = HostStatus.Online, snapshot = snapshot, herdrVersion = pong.version) }
        launch { checkBoard() }

        val done = CompletableDeferred<Throwable?>()
        launch { done.complete(events.closed.await() ?: IOException("herdr event stream closed")) }
        launch { events.events.collect { refreshRequests.trySend(Unit) } }
        launch { watchAgentStatus(opener) }
        launch {
            while (true) {
                withTimeoutOrNull(15_000) { refreshRequests.receive() }
                delay(150) // coalesce bursts of events
                while (refreshRequests.tryReceive().isSuccess) Unit
                val next = try {
                    herdr.snapshot()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    done.complete(t)
                    break
                }
                _state.update { it.copy(snapshot = next) }
            }
        }
        val error = done.await()
        throw error ?: IOException("Connection closed")
    }

    /**
     * Finds how to reach the session's API socket. Prefers a direct Unix-socket channel (no process
     * per request); falls back to `herdr remote-api-bridge` when the SSH server forbids socket
     * forwarding.
     */
    private suspend fun apiOpener(conn: MachineConnection, m: Machine): suspend () -> Duplex {
        val list = conn.run(HerdrCommands.sessionList(m))
        if (list.exitStatus == HerdrCommands.NOT_FOUND_EXIT) {
            throw ConnectError.HerdrMissing(
                "herdr isn't installed on ${m.label}, or it's outside the usual paths. Install it, or set its path in this machine's settings.",
            )
        }
        val sessions = runCatching {
            HerdrJson.decodeFromJsonElement(SessionListSerializer, HerdrJson.parseToJsonElement(list.stdout).jsonObject["sessions"]!!)
        }.getOrElse {
            val detail = list.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "unexpected output"
            throw ConnectError.HerdrMissing("Couldn't list herdr sessions on ${m.label}: $detail")
        }
        val entry = sessions.firstOrNull { it.name == m.session }
            ?: throw ConnectError.HerdrMissing(
                "${m.label} has no herdr session named \"${m.session}\". Sessions there: ${sessions.joinToString { it.name }.ifEmpty { "none" }}.",
            )
        if (!entry.running) {
            throw ConnectError.HerdrMissing(
                "herdr session \"${m.session}\" isn't running on ${m.label}. Start it there by running herdr, then retry.",
            )
        }
        val socketPath = entry.socketPath
        if (socketPath.isNotEmpty()) {
            val viaSocket: suspend () -> Duplex = { conn.openUnixSocket(socketPath) }
            val works = runCatching { HerdrApi(ApiClient(viaSocket)).ping() }.isSuccess
            if (works) return viaSocket
        }
        val check = conn.run(HerdrCommands.apiBridgeCheck(m))
        if (check.stdout.trim() != "herdr-api-bridge-v1") {
            throw ConnectError.HerdrMissing(
                "herdr on ${m.label} is too old for the app, and SSH socket forwarding is off. Update herdr there (herdr update).",
            )
        }
        return { conn.start(HerdrCommands.apiBridge(m)) }
    }

    private suspend fun subscribe(opener: suspend () -> Duplex, params: kotlinx.serialization.json.JsonObject): ApiConnection {
        val duplex = opener()
        val connection = ApiConnection(duplex.input, duplex.output, scope, onClose = { duplex.close() })
        try {
            connection.subscribe(params)
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
        return connection
    }

    /**
     * Agent status changes are per-pane subscriptions in herdr, so keep one subscription for the
     * current set of panes and replace it when panes come and go.
     */
    private suspend fun watchAgentStatus(opener: suspend () -> Duplex): Nothing = coroutineScope {
        var current: Set<String> = emptySet()
        var watcher: ApiConnection? = null
        var forwarder: Job? = null
        try {
            state.collect { s ->
                val panes = s.snapshot?.panes?.map { it.paneId }?.toSet() ?: emptySet()
                if (panes != current && panes.isNotEmpty()) {
                    current = panes
                    forwarder?.cancel()
                    watcher?.close()
                    watcher = runCatching { subscribe(opener, HerdrApi.subscriptionParams(panes.toList())) }.getOrNull()
                    forwarder = watcher?.let { w -> launch { w.events.collect { refreshRequests.trySend(Unit) } } }
                }
            }
        } finally {
            watcher?.close()
        }
        awaitCancellation()
    }

    private fun teardown() {
        api = null
        val c = connection
        connection = null
        runCatching { c?.close() }
    }
}

private val SessionListSerializer = ListSerializer(SessionEntry.serializer())
