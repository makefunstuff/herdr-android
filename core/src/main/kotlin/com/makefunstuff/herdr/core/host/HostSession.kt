package com.makefunstuff.herdr.core.host

import com.makefunstuff.herdr.core.api.ApiClient
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.api.PongInfo
import com.makefunstuff.herdr.core.api.SessionEntry
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.BoardClient
import com.makefunstuff.herdr.core.board.V1WorkBoard
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.chat.AgentPort
import com.makefunstuff.herdr.core.chat.ClassicAgentPort
import com.makefunstuff.herdr.core.chat.HcAgentPort
import com.makefunstuff.herdr.core.hc.HcClient
import com.makefunstuff.herdr.core.hc.HcDetection
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.hc.HcView
import com.makefunstuff.herdr.core.hc.HerdrCore
import com.makefunstuff.herdr.core.shepherdr.BoardDetection
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrTransport
import com.makefunstuff.herdr.core.shepherdr.V2Version
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.ssh.ConnectError
import com.makefunstuff.herdr.core.ssh.HerdrBinaries
import com.makefunstuff.herdr.core.ssh.HerdrBinary
import com.makefunstuff.herdr.core.ssh.HerdrChoice
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    /** Whether the look for herdr-projects answered since the machine connected, so a null [boardPath] means it isn't there. */
    val boardChecked: Boolean = false,
    /** Shepherdr 2's version where its API answers on the machine; null where it doesn't (or not checked yet). */
    val shepherdrVersion: String? = null,
    /** The herdr executable the app runs on the machine; null until it connects, or when it couldn't be probed. */
    val herdr: HerdrChoice? = null,
    /**
     * The metadata integrations report for each workspace (branch, PR, …), as key → value. Read
     * separately from [snapshot], which doesn't carry it, and kept for every workspace (empty when
     * none was reported) so the snapshot's workspace ids can be used as the cache key.
     */
    val workspaceMeta: Map<String, Map<String, String>> = emptyMap(),
    /** The session's notifications, newest first. */
    val notices: List<Notice> = emptyList(),
    /** herdr-core on this machine when one runs there (null: none, or not found yet). */
    val herdrCore: HerdrCoreState? = null,
    /** How many of [notices] arrived since they were last looked at. */
    val unreadNotices: Int = 0,
    /**
     * Why classic herdr isn't there, on a machine that is online for herdr-core alone (it isn't
     * installed, or its session doesn't run); null where classic herdr answers.
     */
    val classicMissing: String? = null,
    /**
     * Whether herdr-core was looked for since the machine connected (found or not): until then a
     * machine running both shows classic herdr's agents alone, which isn't all of them.
     */
    val herdrCoreLooked: Boolean = false,
) {
    /** Every agent of the machine is in [view]: classic herdr's and herdr-core's were both read. */
    val agentsKnown: Boolean get() = herdrCoreLooked && view != null

    /**
     * Classic herdr's [snapshot] and herdr-core's together, classic first: what the Agents list,
     * Needs you, the chats and the machine screen show. Null until either answered. herdr-core's
     * ids all start with [HcMapping.PREFIX]; [snapshot] alone stays classic herdr's.
     */
    val view: SessionSnapshot? by lazy { Sessions.merge(snapshot, herdrCore?.snapshot) }

    /** Whether classic herdr answers here (a machine may be online for herdr-core alone). */
    val classic: Boolean get() = online && classicMissing == null

    val hasBoard: Boolean get() = boardPath != null

    /** Which board the machine runs: Shepherdr 2 wins over a herdr-projects launcher, which may be an old one left on disk. */
    val boardApi: BoardApi get() = BoardDetection.decide(shepherdrVersion?.let { V2Version(version = it) }, boardPath)

    /** A work board of either kind there: Shepherdr 2's, or herdr-projects 1.x's. */
    val hasWorkBoard: Boolean get() = boardApi != BoardApi.None

    /** It runs Shepherdr 2: its board screens talk to its API ([HostSession.awaitWorkBoard]), never herdr-projects. */
    val onShepherdr2: Boolean get() = boardApi is BoardApi.V2

    /** [machine] with its herdr path set to the chosen binary: what every herdr command should run. */
    val herdrMachine: Machine get() = herdr?.let { machine.copy(herdrPath = it.binary.path) } ?: machine

    val online: Boolean get() = status == HostStatus.Online
}

/**
 * herdr-core (the Herdr desktop app's runtime) on a machine that also runs classic herdr: its
 * version, the core's name, and its terminals as a [SessionSnapshot] whose ids all start with
 * [HcMapping.PREFIX], beside [HostState.snapshot]'s.
 */
data class HerdrCoreState(
    val version: String,
    val coreName: String,
    val binary: String,
    val snapshot: SessionSnapshot,
    /** Each live terminal's size by its hc address, as the program draws it (the last active viewer set it). */
    val sizes: Map<String, Pair<Int, Int>> = emptyMap(),
)

object Sessions {
    /**
     * [classic] and herdr-core's [core] as one snapshot: classic herdr's workspaces first, then
     * herdr-core's spaces numbered after them. Either alone is returned as it is.
     */
    fun merge(classic: SessionSnapshot?, core: SessionSnapshot?): SessionSnapshot? {
        if (core == null || core.workspaces.isEmpty() && core.panes.isEmpty()) return classic ?: core
        if (classic == null) return core
        val after = classic.workspaces.maxOfOrNull { it.number } ?: 0
        return classic.copy(
            workspaces = classic.workspaces + core.workspaces.map { it.copy(number = after + it.number) },
            tabs = classic.tabs + core.tabs,
            panes = classic.panes + core.panes,
            agents = classic.agents + core.agents,
        )
    }
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
    private val _answeredAt = MutableStateFlow<Long?>(null)
    /**
     * When herdr on the machine last answered (a snapshot read, at least every 15 s while connected),
     * by the phone's clock; null until it first does. Apart from [state], so that ticking doesn't redraw every screen.
     */
    val answeredAt: StateFlow<Long?> = _answeredAt.asStateFlow()

    private var loop: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var connection: MachineConnection? = null
    /** How Shepherdr 2's API is reached on this connection (its port forward), once found; closed with the connection. */
    @Volatile private var shepherdr: ShepherdrTransport? = null
    private val shepherdrLock = Mutex()
    @Volatile private var api: HerdrApi? = null
    /** herdr-core's one `hc connect` on this connection, once found; closed with the connection. */
    @Volatile private var herdrCore: HerdrCore? = null
    /** Follows herdr-core for this connection: outside the session's scope, so it never holds up a reconnect. */
    @Volatile private var herdrCoreJob: Job? = null
    @Volatile private var herdrMachine: Machine = machine
    @Volatile private var metaDirty = true

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

    fun markNoticesRead() {
        _state.update { it.copy(unreadNotices = 0) }
    }

    fun clearNotices() {
        _state.update { it.copy(notices = emptyList(), unreadNotices = 0) }
    }

    /** Takes a new snapshot, noting the agents that finished or started waiting since the last one. */
    private fun applySnapshot(next: SessionSnapshot, extra: (HostState) -> HostState = { it }) {
        _answeredAt.value = System.currentTimeMillis()
        _state.update { s ->
            val added = Notices.between(s.snapshot, next, System.currentTimeMillis())
            extra(s).copy(
                snapshot = next,
                notices = Notices.merge(s.notices, added),
                unreadNotices = minOf(Notices.LIMIT, s.unreadNotices + added.size),
            )
        }
    }

    /** Waits for the machine to come online and returns its API. */
    suspend fun awaitApi(timeoutMs: Long = 20_000): HerdrApi {
        start()
        return withTimeoutOrNull(timeoutMs) {
            state.first { it.online }
            api
        } ?: throw IOException(statusMessage())
    }

    /** Waits for the machine (online for classic herdr, or for herdr-core alone) and returns its SSH connection. */
    suspend fun awaitConnection(timeoutMs: Long = 20_000): MachineConnection {
        start()
        withTimeoutOrNull(timeoutMs) { state.first { it.online } } ?: throw IOException(statusMessage())
        return connection ?: throw IOException(statusMessage())
    }

    /**
     * Copies a file from the phone to this machine (see [Attachments]) and returns its path there,
     * for the message to name it.
     */
    suspend fun upload(fileName: String, input: java.io.InputStream, stamp: String = Attachments.stamp()): String {
        val conn = awaitConnection()
        val result = conn.runWithInput(Attachments.uploadCommand(Attachments.safeName(fileName), stamp), input)
        val path = result.stdout.trim()
        if (result.exitStatus != 0 || path.isEmpty()) {
            throw IOException(result.stderr.trim().ifEmpty { "Couldn't copy $fileName to ${machine.label}" })
        }
        return path
    }

    /**
     * A picture named in a message ([Images.split]), read back from this machine to show it in the
     * chat: its bytes, or an error when it isn't there any more or is larger than [Images.MAX_FETCH_BYTES].
     */
    suspend fun fetchImage(path: String): ByteArray {
        val conn = awaitConnection()
        val result = conn.run(Images.fetchCommand(path), timeoutSeconds = 60)
        if (result.exitStatus != 0 || result.stdout.isBlank()) throw IOException("${path.substringAfterLast('/')} isn't on ${machine.label} any more")
        return java.util.Base64.getMimeDecoder().decode(result.stdout.trim())
    }

    /** The work board on this machine, when it has one. */
    suspend fun awaitBoard(timeoutMs: Long = 20_000): BoardClient {
        val conn = awaitConnection(timeoutMs)
        val path = state.value.boardPath ?: throw IOException("herdr-projects isn't installed on ${machine.label}")
        return BoardClient(conn, state.value.herdrMachine, path)
    }

    /** Looks for the board again, for one installed or removed since the machine connected: Shepherdr 2's API, then v1's launcher. */
    suspend fun checkBoard() {
        val conn = connection ?: return
        val v2 = findShepherdr(conn)
        val path = runCatching { BoardClient.locate(conn) }.getOrElse { return }
        _state.update { it.copy(boardPath = path, shepherdrVersion = v2?.version, boardChecked = true) }
    }

    /** Shepherdr 2 on this machine, over the connection's one port forward to it. */
    suspend fun awaitShepherdr(timeoutMs: Long = 20_000): ShepherdrClient {
        val conn = awaitConnection(timeoutMs)
        shepherdr?.let { return ShepherdrClient(it) }
        findShepherdr(conn) ?: throw IOException("Shepherdr 2 isn't running on ${machine.label}")
        return ShepherdrClient(shepherdr ?: throw IOException(statusMessage()))
    }

    /** The machine's work board, whichever it runs ([HostState.boardApi]). */
    suspend fun awaitWorkBoard(timeoutMs: Long = 20_000): WorkBoard {
        awaitConnection(timeoutMs)
        if (!state.value.boardChecked) checkBoard()
        val s = state.value
        return when (val api = s.boardApi) {
            // The client each time: after a reconnect it is the new connection's forward.
            is BoardApi.V2 -> V2WorkBoard({ awaitShepherdr(timeoutMs) }, api.version)
            is BoardApi.V1 -> V1WorkBoard(awaitBoard(timeoutMs), api.path)
            BoardApi.None -> throw IOException("Neither Shepherdr nor herdr-projects is installed on ${machine.label}")
        }
    }

    /** Keeps the transport that still answers, else finds one; null where Shepherdr 2 doesn't answer. */
    private suspend fun findShepherdr(conn: MachineConnection): V2Version? = shepherdrLock.withLock {
        shepherdr?.let { t ->
            BoardDetection.probe(t)?.let { return@withLock it }
            shepherdr = null
            runCatching { t.close() }
        }
        val (t, v) = BoardDetection.open(conn) ?: return@withLock null
        if (connection !== conn) {
            runCatching { t.close() }
            return@withLock null
        }
        shepherdr = t
        // Torn down meanwhile: teardown let go of the connection before it closed what it found here.
        if (connection !== conn) {
            shepherdr = null
            runCatching { t.close() }
            return@withLock null
        }
        v
    }

    private fun statusMessage(): String = state.value.classicMissing?.takeIf { state.value.online } ?: when (val s = state.value.status) {
        is HostStatus.Attention -> s.message
        is HostStatus.Reconnecting -> s.message
        else -> "${machine.label} is not connected"
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (true) {
            _state.update { it.copy(status = HostStatus.Connecting, classicMissing = null) }
            val failure: Throwable = try {
                session()
            } catch (e: CancellationException) {
                teardown()
                throw e
            } catch (t: Throwable) {
                t
            }
            teardown()
            if (failure is ClassicHerdrBack) {
                attempt = 0
                continue
            }
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

        // Run the same herdr as the server: the first one on SSH's PATH may be an older install.
        val found = probeHerdr(conn, m)
        var choice = HerdrBinaries.choose(found, null)?.let { HerdrChoice(it, null, found) }
        herdrMachine = choice?.let { m.copy(herdrPath = it.binary.path) } ?: m
        val openerResult = try {
            apiOpener(conn, m, found)
        } catch (e: ConnectError.HerdrMissing) {
            herdrCoreOnly(conn, m, e)
        }
        val opener = openerResult.opener
        val herdr = HerdrApi(ApiClient(opener))
        val pong = openerResult.pong ?: herdr.ping()
        api = herdr
        choice = HerdrBinaries.choose(found, pong.version)?.let { HerdrChoice(it, pong.version, found) }
        herdrMachine = choice?.let { m.copy(herdrPath = it.binary.path) } ?: m

        // Subscribe before the first snapshot so no change slips between them.
        val events = subscribe(opener, HerdrApi.subscriptionParams(emptyList()))

        val snapshot = herdr.snapshot()
        applySnapshot(snapshot) { it.copy(status = HostStatus.Online, herdrVersion = pong.version, herdr = choice) }
        launch { checkBoard() }
        followHerdrCore(conn, null)
        launch { loadWorkspaceMeta(herdr, snapshot) }

        val done = CompletableDeferred<Throwable?>()
        launch { done.complete(events.closed.await() ?: IOException("herdr event stream closed")) }
        launch {
            events.events.collect { event ->
                // Subscription uses the dotted name; herdr pushes the event's own name with underscores.
                if (event["event"]?.jsonPrimitive?.content == WORKSPACE_METADATA_EVENT) metaDirty = true
                refreshRequests.trySend(Unit)
            }
        }
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
                applySnapshot(next)
                if (metaDirty || next.workspaces.map { it.workspaceId }.toSet() != _state.value.workspaceMeta.keys) {
                    metaDirty = false
                    loadWorkspaceMeta(herdr, next)
                }
            }
        }
        val error = done.await()
        throw error ?: IOException("Connection closed")
    }

    /** Every herdr on the machine, best first; empty when the probe itself failed (the search script still runs then). */
    private suspend fun probeHerdr(conn: MachineConnection, m: Machine): List<HerdrBinary> {
        val out = runCatching { conn.run(HerdrCommands.probe(m)) }.getOrNull() ?: return emptyList()
        return HerdrBinaries.parse(out.stdout)
    }

    private data class OpenerResult(val opener: suspend () -> Duplex, val pong: PongInfo? = null)

    /**
     * Finds how to reach the session's API socket. Prefers a direct Unix-socket channel (no process
     * per request); falls back to `herdr remote-api-bridge` when the SSH server forbids socket
     * forwarding. Tests Unix socket with `ping()` and returns the PongInfo to save a redundant round trip.
     */
    private suspend fun apiOpener(conn: MachineConnection, m: Machine, found: List<HerdrBinary>): OpenerResult {
        val list = conn.run(HerdrCommands.sessionList(herdrMachine))
        if (list.exitStatus == HerdrCommands.NOT_FOUND_EXIT) {
            throw ConnectError.HerdrMissing(
                "herdr isn't installed on ${m.label}, or it's outside the usual paths. Install it, or set its path in this machine's settings.",
            )
        }
        if (list.exitStatus != 0 && list.exitStatus != null) {
            val detail = list.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "session list failed (exit ${list.exitStatus})"
            throw IOException("Couldn't list herdr sessions on ${m.label}: $detail")
        }
        val sessions = runCatching {
            HerdrJson.decodeFromJsonElement(SessionListSerializer, HerdrJson.parseToJsonElement(list.stdout).jsonObject["sessions"]!!)
        }.getOrElse {
            val detail = list.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "unexpected output"
            throw IOException("Couldn't parse herdr sessions on ${m.label}: $detail")
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
            val pong = runCatching { HerdrApi(ApiClient(viaSocket)).ping() }.getOrNull()
            if (pong != null) return OpenerResult(viaSocket, pong)
        }
        herdrMachine = findApiBridge(m, found, { conn.run(it) }, { conn.start(it) })
        // Read at each request, so the bridge follows the binary chosen once the server's version is known.
        return OpenerResult({ conn.start(HerdrCommands.apiBridge(herdrMachine)) }, null)
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

    /** Reads each workspace's integration metadata (workspace.get); the snapshot doesn't carry it. */
    private suspend fun loadWorkspaceMeta(herdr: HerdrApi, snapshot: SessionSnapshot) {
        // Concurrency limited to 4 to avoid exceeding OpenSSH default MaxSessions (10) on multiplexed links.
        val semaphore = Semaphore(4)
        val meta = supervisorScope {
            snapshot.workspaces.map { ws ->
                async {
                    semaphore.withPermit {
                        ws.workspaceId to runCatching { herdr.workspace(ws.workspaceId).tokens }.getOrDefault(emptyMap())
                    }
                }
            }.awaitAll().toMap()
        }
        _state.update { it.copy(workspaceMeta = meta) }
    }

    /**
     * Where [paneId]'s keys and words go: herdr-core's API for one of its terminals
     * ([HcMapping.PREFIX]), else classic herdr's.
     */
    suspend fun awaitAgent(paneId: String, timeoutMs: Long = 20_000): AgentPort =
        if (HcMapping.address(paneId) != null) HcAgentPort(awaitHerdrCore(timeoutMs)) else ClassicAgentPort(awaitApi(timeoutMs))

    /** herdr-core's API on this machine, over the connection's one `hc connect`. */
    suspend fun awaitHerdrCore(timeoutMs: Long = 20_000): HcClient {
        start()
        // The core itself, not the last state kept of one that went a moment ago.
        return withTimeoutOrNull(timeoutMs) { state.first { it.herdrCore != null && herdrCore != null } }?.let { herdrCore?.client }
            ?: throw IOException("herdr-core isn't running on ${machine.label}")
    }

    /**
     * A machine without classic herdr where herdr-core runs: the session stays connected for
     * herdr-core and is online while herdr-core answers ([HostState.classicMissing] says what
     * classic herdr lacks); while herdr-core is gone too, the status says what classic herdr lacks,
     * as before. Classic herdr is looked for again as often as the Attention retry would; once it
     * answers, the session starts over with both.
     */
    private suspend fun herdrCoreOnly(conn: MachineConnection, m: Machine, missing: ConnectError.HerdrMissing): Nothing = coroutineScope {
        val core = try {
            HcDetection.open(conn, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: throw missing
        val why = missing.message ?: "herdr is missing"
        val attention = HostStatus.Attention(why, HostStatus.Attention.Kind.Herdr)
        _state.update { it.copy(status = attention, classicMissing = why) }
        followHerdrCore(conn, core)
        // Online for herdr-core while it answers; what classic herdr lacks while it doesn't.
        val status = launch {
            state.collect { s ->
                val next = if (s.herdrCore != null) HostStatus.Online else attention
                // Only between these two: a stop or a new connection meanwhile keeps its own status.
                if (s.status != next) _state.update { if (it.status == HostStatus.Online || it.status == attention) it.copy(status = next) else it }
            }
        }
        var due = System.currentTimeMillis() + CLASSIC_RECHECK_MS
        while (true) {
            // Retry looks again at once, as it reconnects a machine whose herdr is missing.
            val retry = withTimeoutOrNull(LINK_CHECK_MS) { retryNow.receive() } != null
            // Nothing else here notices a dropped link: no classic event stream.
            if (!conn.isConnected) throw IOException("Lost the connection to ${m.label}")
            if (!retry && System.currentTimeMillis() < due) continue
            due = System.currentTimeMillis() + CLASSIC_RECHECK_MS
            val back = try {
                apiOpener(conn, m, probeHerdr(conn, m))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            if (back) throw ClassicHerdrBack()
        }
        @Suppress("UNREACHABLE_CODE")
        awaitCancellation()
    }

    /** Starts following herdr-core on [conn] ([first]: already connected), beside classic herdr. */
    private fun followHerdrCore(conn: MachineConnection, first: HerdrCore?) {
        herdrCoreJob?.cancel()
        herdrCoreJob = scope.launch { watchHerdrCore(conn, first) }
    }

    /**
     * Follows herdr-core, never failing the session: finds it (again a minute after it was missing
     * or its line dropped, as when the desktop app starts later or hc updates), subscribes, and keeps
     * [HostState.herdrCore] up to date from its events.
     */
    private suspend fun watchHerdrCore(conn: MachineConnection, first: HerdrCore?): Nothing {
        var next = first
        while (true) {
            val core = next ?: try {
                HcDetection.open(conn, scope)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            next = null
            var followed = false
            if (core == null) {
                // Not there (any more): what was kept of a core that went is gone too.
                _state.update { it.copy(herdrCore = null, herdrCoreLooked = true) }
            } else {
                herdrCore = core
                try {
                    // Torn down meanwhile: teardown let go of the connection before this one was kept.
                    if (connection === conn) followEvents(core) { followed = true }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                } finally {
                    runCatching { core.connection.close() }
                    if (herdrCore === core) {
                        herdrCore = null
                        // A core that was followed keeps its last state until it is looked for again
                        // (3 s): a restart doesn't make its agents disappear and come back.
                        if (!followed) _state.update { it.copy(herdrCore = null, herdrCoreLooked = true) }
                    }
                }
            }
            // A core that was followed and went (a restart, an hc update) is usually back in seconds;
            // one that wouldn't subscribe waits the full minute.
            delay(if (followed) HERDR_CORE_BACK_MS else HERDR_CORE_RETRY_MS)
        }
    }

    /** Follows [core]'s events until its line ends; [subscribed] once it answered with its snapshot. */
    private suspend fun followEvents(core: HerdrCore, subscribed: () -> Unit) = coroutineScope {
        val subscription = core.client.subscribe()
        val view = MutableStateFlow(HcView.of(subscription.snapshot))
        subscribed()
        val dirty = Channel<Unit>(Channel.CONFLATED)
        fun publish() {
            val terminals = view.value.terminals.values
            val snapshot = HcMapping.snapshot(terminals, core.version)
            val sizes = terminals.filter { it.live && it.cols > 0 && it.rows > 0 }.associate { it.address to (it.cols to it.rows) }
            // Checked inside the update: teardown may let go of this core at any moment.
            // Only a change the snapshot shows: classic screens redraw on every new state.
            val next = HerdrCoreState(core.version, core.core.name, core.binary.path, snapshot, sizes)
            _state.update { s ->
                if (herdrCore !== core || s.herdrCore == next) return@update s
                // Its agents that finished or started waiting, as for classic herdr's (none on the first look).
                val now = System.currentTimeMillis()
                val added = Notices.between(s.herdrCore?.snapshot, snapshot, now)
                s.copy(
                    herdrCore = next,
                    herdrCoreLooked = true,
                    notices = Notices.merge(s.notices, added),
                    unreadNotices = minOf(Notices.LIMIT, s.unreadNotices + added.size),
                )
            }
        }
        publish()
        val publisher = launch {
            for (ping in dirty) {
                delay(150) // coalesce bursts of events
                publish()
            }
        }
        try {
            subscription.events.collect { event ->
                // When a status word changed, by the core's clock: the record says, the event doesn't.
                val at = view.value.statusChange(event)?.let { word ->
                    val t = event.terminal ?: return@let null
                    runCatching { core.client.get(t) }.getOrNull()?.takeIf { it.status?.state == word }?.statusMs
                }
                view.value = view.value.apply(event, statusAt = at)
                if (event.method != "heartbeat") dirty.trySend(Unit)
            }
        } finally {
            subscription.close()
            publisher.cancel()
        }
    }

    private fun teardown() {
        api = null
        herdrCoreJob?.cancel()
        herdrCoreJob = null
        herdrCore?.let { runCatching { it.connection.close() } }
        herdrCore = null
        _state.update { it.copy(herdrCore = null, herdrCoreLooked = false) }
        val c = connection
        connection = null
        shepherdr?.let { runCatching { it.close() } }
        shepherdr = null
        runCatching { c?.close() }
    }
}

/** How long the app waits before looking for herdr-core again where it wasn't, or its line dropped. */
private const val HERDR_CORE_RETRY_MS = 60_000L

/** How soon herdr-core is looked for again after a followed core went. */
private const val HERDR_CORE_BACK_MS = 3_000L

/** How long a machine with only herdr-core waits before looking for classic herdr again (the Attention retry's). */
private const val CLASSIC_RECHECK_MS = 120_000L

/** How often a machine with only herdr-core checks its SSH link is still up. */
private const val LINK_CHECK_MS = 5_000L

/** Classic herdr answers on a machine that had only herdr-core: the session starts over, at once, with both. */
private class ClassicHerdrBack : IOException("herdr started")

private val SessionListSerializer = ListSerializer(SessionEntry.serializer())

/**
 * The event herdr pushes when `workspace.report_metadata` changes a workspace's tokens. Note the
 * underscores: subscriptions ask for `workspace.metadata_updated`, but the pushed `event` field is
 * the schema's own name, `workspace_metadata_updated`.
 */
internal const val WORKSPACE_METADATA_EVENT = "workspace_metadata_updated"
