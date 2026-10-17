package com.makefunstuff.herdr.core.health

import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/**
 * A machine's health over its SSH connection: [run] executes one remote shell command, with a
 * timeout in seconds.
 */
class HealthClient(private val run: suspend (command: String, timeoutSeconds: Long) -> CommandResult) {
    /** One full look; [keep] as [HealthCommands.probe]. */
    suspend fun sample(keep: List<String> = emptyList()): HealthSample =
        HealthReader.sample(check(run(HealthCommands.probe(keep), 30)))

    /** Memory, load and disks only. */
    suspend fun quick(): HealthSample = HealthReader.sample(check(run(HealthCommands.quick(), 20)))

    /** The last hour that herdr-push noted on the machine (empty where it doesn't run). */
    suspend fun history(): List<HealthPoint> = runCatching {
        parseHistory(run(HISTORY, 15).stdout)
    }.getOrDefault(emptyList())

    suspend fun worktrees(): List<WorktreeDir> = HealthReader.worktrees(check(run(HealthCommands.worktrees(), 180)))

    suspend fun kill(targets: List<HealthCommands.Target>): HealthReader.Killed {
        if (targets.isEmpty()) return HealthReader.Killed(emptyList(), emptyList(), emptyList(), emptyList())
        return HealthReader.killed(run(HealthCommands.kill(targets), 30).stdout)
    }

    /** Removes a worktree with git; the exception carries git's reason (changes in it need [force]). */
    suspend fun removeWorktree(path: String, force: Boolean) {
        val r = run(HealthCommands.removeWorktree(path, force), 120)
        if (r.exitStatus != 0) {
            val why = (r.stderr.ifBlank { r.stdout }).lines().map { it.trim().removePrefix("fatal: ").removePrefix("error: ") }
                .filter { it.isNotEmpty() }.take(3).joinToString("\n")
            throw IOException(why.ifEmpty { "git couldn't remove that worktree" })
        }
    }

    /**
     * The output of a look that ran to its end. One that failed partway (awk crashed, it timed out)
     * is an error, not a machine with nothing running: its readings would be missing, not empty.
     */
    private fun check(r: CommandResult): String {
        val why = r.stderr.trim().lines().lastOrNull { it.isNotBlank() }
        if (HealthCommands.MARK !in r.stdout) throw IOException(why ?: "The machine gave no health readings")
        if (r.exitStatus != 0) {
            throw IOException(
                when {
                    r.exitStatus == null -> "Reading the machine's health took too long"
                    why != null -> "Reading the machine's health failed: $why"
                    else -> "Reading the machine's health failed (exit ${r.exitStatus})"
                },
            )
        }
        return r.stdout
    }

    companion object {
        private const val HISTORY = "sh -c 'cat \"\${HERDR_PUSH_HOME:-\${XDG_STATE_HOME:-\$HOME/.local/state}/herdr-push}/health.json\" 2>/dev/null'"

        /** herdr-push's `health.json`: `{"v":1,"points":[[at, memUsed, swapUsed, cpu|null], …]}`. */
        fun parseHistory(text: String): List<HealthPoint> {
            val o = runCatching { HerdrJson.parseToJsonElement(text.trim()).jsonObject }.getOrNull() ?: return emptyList()
            return (o["points"] as? JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? JsonArray ?: return@mapNotNull null
                fun num(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.takeIf { !it.isString }
                val at = num(0)?.longOrNull ?: num(0)?.doubleOrNull?.toLong() ?: return@mapNotNull null
                HealthPoint(at, num(1)?.doubleOrNull ?: return@mapNotNull null, num(2)?.doubleOrNull ?: 0.0, num(3)?.doubleOrNull)
            }
        }
    }
}

/** The chart's last hour. */
object HealthHistory {
    const val SPAN = 3600L
    /** Points closer than this are one: herdr-push notes one every 30 s, the screen looks every few seconds. */
    const val STEP = 10L

    fun trim(points: List<HealthPoint>, now: Long): List<HealthPoint> {
        val out = ArrayList<HealthPoint>()
        for (p in points.filter { now - it.at in 0..SPAN }.sortedBy { it.at }) {
            val last = out.lastOrNull()
            if (last != null && p.at - last.at < STEP) out[out.size - 1] = p else out += p
        }
        return out
    }
}
