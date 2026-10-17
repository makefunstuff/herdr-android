// A machine's health as the app's probe reads it (core/…/health/HealthCommands.kt), from fixed demo
// data: memory, disks, the processes worth showing, the worktrees on disk and herdr-push's last hour.
package preview

import com.makefunstuff.herdr.core.health.HealthCommands
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlin.math.PI
import kotlin.math.sin

class FakeHealth(
    /** KiB of RAM available, of [ramKb]. */
    var availKb: Long = 26_000_000,
    var ramKb: Long = 65_536_000,
    var procs: List<P> = DEVBOX_PROCS,
    var disks: String = DEVBOX_DISKS,
    var worktrees: List<String> = DEVBOX_WORKTREES,
) {
    /** One process: `pid ppid uid rssKb cpu ageSeconds comm cwd pwd pane workspace args`. */
    class P(
        val pid: Int, val rssKb: Long, val cpu: Double, val comm: String, val args: String,
        val cwd: String = "", val pwd: String = "", val pane: String = "", val uid: Int = 1000, val age: Long = 7200,
    )

    fun run(command: String): CommandResult? = when {
        "health.json" in command -> ok(history())
        "herdr-kill" in command -> ok("")
        HealthCommands.MARK !in command -> null
        "/.herdr/worktrees/*/*/" in command -> ok(HealthCommands.MARK + " 1\n" + worktrees.joinToString("") { "W\t$it\n" })
        "find /proc" in command -> ok(head() + "@cpu 31.4\n" + procs.joinToString("") { line(it) })
        else -> ok(head() + "@cpu1 cpu 1000 0 500 8000 100 0 0 0\n@cpu2 cpu 1250 0 560 8250 110 0 0 0\n")
    }

    private fun ok(out: String) = CommandResult(0, out, "")

    private fun head() = """
        |${HealthCommands.MARK} 1
        |@os Linux
        |@now $NOW
        |@uid 1000
        |@home /home/demo
        |@cores 16
        |@meminfo
        |MemTotal: $ramKb kB
        |MemAvailable: $availKb kB
        |SwapTotal: 16777216 kB
        |SwapFree: 13631488 kB
        |@loadavg 5.12 4.80 4.02 3/1500 4242
        |@df
        |$disks
        |@end-df
        |""".trimMargin()

    private fun line(p: P) = listOf("P", p.pid, 1, p.uid, p.rssKb, p.cpu, p.age, p.comm, p.cwd, p.pwd, p.pane, p.pane.substringBefore(':'), p.args)
        .joinToString("\t") + "\n"

    /** An hour of herdr-push's readings, every 30 s: RAM climbing as the emulator and builds start, CPU in bursts. */
    private fun history(): String {
        val used = 100.0 - availKb * 100.0 / ramKb
        val points = (0 until 120).map { i ->
            val t = NOW - (119 - i) * 30L
            val ram = (used - 22 + 22.0 * i / 119 + 2.5 * sin(i / 6.0)).coerceIn(5.0, 99.0)
            val cpu = (24 + 18 * sin(i * PI / 14) + 14 * sin(i * PI / 5)).coerceIn(2.0, 98.0)
            "[$t, ${"%.1f".format(java.util.Locale.ROOT, ram)}, 18.0, ${"%.1f".format(java.util.Locale.ROOT, cpu)}]"
        }
        return """{"v": 1, "points": [${points.joinToString(", ")}]}"""
    }

    companion object {
        const val NOW = 1792250000L
        private const val WT = "/home/demo/.herdr/worktrees"
        private const val GRADLE = "/usr/lib/jvm/java-17/bin/java -Xmx4g -cp /home/demo/.gradle/wrapper/dists/gradle-8.11.1-bin/lib/gradle-daemon-main-8.11.1.jar … org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.11.1"

        val DEVBOX_PROCS = listOf(
            P(3101, 5_872_000, 34.0, "qemu-system-x86", "/home/demo/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64 -avd Pixel_8 -no-window -no-audio", cwd = "$WT/herdr-android/work-2-replace-agent-terminals", pwd = "$WT/herdr-android/work-2-replace-agent-terminals", age = 4 * 86400),
            P(3200, 3_250_000, 0.0, "java", GRADLE, cwd = "/home/demo/.gradle/daemon/8.11.1", pwd = "$WT/herdr-android/work-2-replace-agent-terminals", age = 4 * 86400),
            P(3300, 2_310_000, 142.0, "java", GRADLE, cwd = "/home/demo/.gradle/daemon/8.11.1", pwd = "$WT/herdr-android/work-9-show-when-each-machine", pane = "w1:p2", age = 1800),
            P(3400, 1_420_000, 18.0, "java", "java -cp kotlin-compiler-embeddable.jar … org.jetbrains.kotlin.daemon.KotlinCompileDaemon --daemon-runFilesPath /home/demo/.local/share/kotlin/daemon", cwd = "$WT/herdr-android/work-9-show-when-each-machine", age = 1700),
            P(4100, 4_190_000, 12.0, "qemu-system-x86", "/usr/bin/qemu-system-x86_64 -name guest=home-services,debug-threads=on -m 4096 -enable-kvm", uid = 0, age = 30 * 86400),
            P(4000, 1_150_000, 1.5, "postgres", "postgres: 16/main: checkpointer", cwd = "", uid = 999, age = 30 * 86400),
            P(3700, 432_000, 6.0, "claude", "claude --permission-mode default", cwd = "/home/demo/src/herdr-android", pane = Demo.APP_AGENT, age = 5400),
            P(3800, 398_000, 1.0, "claude", "claude --permission-mode default", cwd = "/home/demo/src/api-server", pane = Demo.API_AGENT, age = 9000),
            P(3500, 384_000, 2.0, "node", "node $WT/website/work-4-rewrite-the-install-page/node_modules/.bin/vite --port 5173", cwd = "$WT/website/work-4-rewrite-the-install-page", age = 3600),
            P(3600, 296_000, 0.0, "node", "node $WT/herdr-android/work-2-replace-agent-terminals/docs/node_modules/.bin/vite --port 4173", cwd = "$WT/herdr-android/work-2-replace-agent-terminals/docs", age = 4 * 86400),
            P(4300, 262_000, 3.0, "python3", "/home/demo/src/api-server/.venv/bin/python3 -m uvicorn app.main:app --reload", cwd = "$WT/api-server/work-3-speed-up-jobs", age = 9000),
            P(3900, 214_000, 0.5, "codex", "codex", cwd = "/home/demo/src/website", pane = Demo.SITE_AGENT, age = 7000),
            P(4200, 168_000, 0.8, "herdr", "/home/demo/.local/bin/herdr server", cwd = "/home/demo", age = 30 * 86400),
        )

        val DEVBOX_DISKS = """
            Filesystem     Type     1024-blocks      Used Available Capacity Mounted on
            /dev/nvme0n1p2 ext4       499000000 412000000  62000000      87% /
            tmpfs          tmpfs       32768000      1000  32767000       1% /run
            /dev/sda1      ext4      1922000000 1250000000 672000000     66% /data
        """.trimIndent()

        val DEVBOX_WORKTREES = listOf(
            "$WT/herdr-android/work-2-replace-agent-terminals\t1887436\t0",
            "$WT/herdr-android/work-9-show-when-each-machine\t912000\t1",
            "$WT/api-server/work-3-speed-up-jobs\t310000\t1",
            "$WT/website/work-4-rewrite-the-install-page\t455000\t0",
        )
    }
}
