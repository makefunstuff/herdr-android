package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Starting a terminal on herdr-core from the phone (`terminal.run`, as `hc run --bg`): a shell or an
 * agent, in a space's folder, labelled into that space as the desktop app labels its own terminals
 * (`herdr.space`, a tab of its own, `where`), so the desktop app and the app's [HcMapping] both put it
 * there. A terminal started outside any space lands in Other terminals.
 */
object HcStart {
    /**
     * Where a terminal for the space [workspaceId] ([HcMapping]'s `hc:sp…` id; null for none) is started:
     * the herdr-core [session] it is in, the [machine] hc added it runs on (`on`), and its labels.
     */
    data class Place(val session: String?, val machine: String?, val labels: Map<String, String>)

    /**
     * [workspaceId]'s place for a new tab called [tabName] ([tabId]: a new tab's id, as the desktop app
     * makes them). Null when the id isn't herdr-core's.
     */
    fun place(workspaceId: String, spaceName: String, tabName: String, tabId: String = newTabId()): Place? {
        if (!workspaceId.startsWith(HcMapping.PREFIX)) return null
        val session = HcMapping.session(workspaceId)
        var rest = workspaceId.removePrefix(HcMapping.scope(session))
        if (rest.startsWith(OTHER)) {
            // Other terminals (`!other`, `!other@rohan`): none of the desktop app's spaces, so no labels.
            return Place(session, rest.removePrefix(OTHER).removePrefix("@").ifEmpty { null }, emptyMap())
        }
        // A space on a machine hc added is `rohan/sp…`; a space known only by its name is `where:<name>` (which may hold a `/`).
        var machine: String? = null
        if (!rest.startsWith(WHERE) && '/' in rest) {
            machine = rest.substringBefore('/')
            rest = rest.substringAfter('/')
        }
        val where = "$spaceName / $tabName"
        val labels = if (rest.startsWith(WHERE)) mapOf("where" to "${rest.removePrefix(WHERE)} / $tabName")
        else mapOf("herdr.space" to rest, "herdr.tab" to tabId, "where" to where)
        return Place(session, machine, labels)
    }

    /** A new tab's id as the desktop app writes them: `tb` and 32 hex digits. */
    fun newTabId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "tb" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * The herdr-core space on [snapshot] whose terminals sit in [folder] (its first terminal's folder), so an
     * agent started there opens in it; null when none does.
     */
    fun spaceIn(snapshot: SessionSnapshot?, folder: String): String? {
        val f = folder.trimEnd('/')
        return snapshot?.workspaces?.filter { it.workspaceId.startsWith(HcMapping.PREFIX) && !it.workspaceId.contains(OTHER) }?.firstOrNull { ws ->
            // A space on a machine hc added (`rohan/t7`) has that machine's folders, not this one's.
            snapshot.panes.firstOrNull { it.workspaceId == ws.workspaceId }?.takeIf { '/' !in it.terminalId }?.cwd?.trimEnd('/') == f
        }?.workspaceId
    }

    /**
     * The line that starts [program] through the machine's login shell, as the app's probe found it
     * ([com.makefunstuff.herdr.core.start.StartWork.probeCommand]): a desktop app's core may have a
     * shorter `PATH` than the person's shell. `exec` leaves the agent itself in the terminal.
     */
    fun programLine(program: String): String = "exec \"\${SHELL:-/bin/sh}\" -lc " + quote("exec " + quote(program))

    /** A name hc takes (1-32 of a-z 0-9 - _), or null. */
    fun hcName(name: String): String? =
        name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(32).trimEnd('-').ifEmpty { null }

    /** Starts a shell in [place] in [cwd]. Returns its pane id. */
    suspend fun shell(client: HcClient, place: Place, cwd: String?): String {
        val t = client.run(cwd = cwd?.ifBlank { null }, labels = place.labels, on = place.machine)
        return HcMapping.paneId(t.address, place.session)
    }

    /**
     * Starts [program] as [name] in [folder] in [place], then gives it [text] once it is ready: once it
     * reports a status (OSC 7501), else after [readyMs]. A program that refuses the words (it asks something
     * first, such as whether to trust the folder) still runs: [Started.unsent] says why they didn't go in.
     */
    suspend fun agent(client: HcClient, place: Place, folder: String, program: String, name: String, text: String, readyMs: Long = 20_000): Started {
        val line = programLine(program)
        val t = try {
            client.run(line, folder, place.labels, hcName(name), place.machine)
        } catch (e: HcException) {
            // Another live terminal has the name: hc numbers none for us, so it goes without one.
            if (e.kind != "name_taken") throw e
            client.run(line, folder, place.labels, null, place.machine)
        }
        if (text.isNotBlank()) {
            withTimeoutOrNull(readyMs) {
                while (true) {
                    val now = runCatching { client.get(t.address) }.getOrNull()
                    if (now != null && (now.status != null || !now.live)) break
                    delay(500)
                }
            }
            val sent = runCatching { client.send(t.address, text.trim()) }
            return Started(HcMapping.paneId(t.address, place.session), sent.exceptionOrNull()?.let { it.message ?: "it didn't take them" })
        }
        return Started(HcMapping.paneId(t.address, place.session), null)
    }

    /** An agent started: its [paneId]; [unsent] why what was said didn't go in, null when it did (or nothing was said). */
    data class Started(val paneId: String, val unsent: String?)

    private const val OTHER = "!other"
    private const val WHERE = "where:"
}
