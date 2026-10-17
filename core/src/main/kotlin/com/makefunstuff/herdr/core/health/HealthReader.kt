package com.makefunstuff.herdr.core.health

import java.io.IOException

/** Reads what [HealthCommands] print. Lines a login shell's start-up files add around them are skipped. */
object HealthReader {
    fun sample(out: String): HealthSample {
        val lines = out.lines()
        val start = lines.indexOfFirst { it.startsWith(HealthCommands.MARK) }
        if (start < 0) throw IOException("The machine gave no health readings")
        var os = ""
        var now = 0L
        var uid = -1
        var home = ""
        var cores = 1
        val mem = HashMap<String, Long>()
        val vm = HashMap<String, Long>()
        var memsize = 0L
        var pagesize = 4096L
        var swap: Pair<Long, Long>? = null
        var load = listOf(0.0, 0.0, 0.0)
        var cpu: Double? = null
        var cpu1: List<Long>? = null
        var cpu2: List<Long>? = null
        val df = ArrayList<String>()
        val procs = ArrayList<Proc>()
        var section = ""
        for (raw in lines.drop(start + 1)) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("P\t")) {
                proc(line)?.let(procs::add)
                continue
            }
            if (line.startsWith("@")) {
                val key = line.substringBefore(' ')
                val value = line.substringAfter(' ', "").trim()
                section = ""
                when (key) {
                    "@os" -> os = value
                    "@now" -> now = value.toLongOrNull() ?: 0
                    "@uid" -> uid = value.toIntOrNull() ?: -1
                    "@home" -> home = value
                    "@cores" -> cores = value.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    "@loadavg" -> load = value.split(Regex("\\s+")).take(3).map { it.toDoubleOrNull() ?: 0.0 }.let { it + List(3 - it.size) { 0.0 } }
                    "@cpu" -> cpu = value.toDoubleOrNull()
                    "@cpu1" -> cpu1 = cpuLine(value)
                    "@cpu2" -> cpu2 = cpuLine(value)
                    "@memsize" -> memsize = value.toLongOrNull() ?: 0
                    "@pagesize" -> pagesize = value.toLongOrNull() ?: 4096
                    "@swapusage" -> swap = swapUsage(value)
                    "@meminfo", "@vmstat", "@df", "@ps" -> section = key
                }
                continue
            }
            when (section) {
                "@meminfo" -> {
                    val k = line.substringBefore(':').trim()
                    line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.let { mem[k] = it }
                }
                "@vmstat" -> {
                    val k = line.substringBefore(':').trim()
                    line.substringAfter(':', "").trim().trimEnd('.').toLongOrNull()?.let { vm[k] = it }
                    Regex("page size of (\\d+) bytes").find(line)?.let { pagesize = it.groupValues[1].toLong() }
                }
                "@df" -> df += line
                "@ps" -> macProc(line)?.let(procs::add)
            }
        }
        if (cpu == null) {
            val a = cpu1
            val b = cpu2
            if (a != null && b != null) cpu = busy(a, b)
        }
        val memory = when {
            mem["MemTotal"] != null -> {
                val total = mem.getValue("MemTotal")
                val avail = mem["MemAvailable"] ?: ((mem["MemFree"] ?: 0) + (mem["Buffers"] ?: 0) + (mem["Cached"] ?: 0))
                Memory(total, avail.coerceAtMost(total), mem["SwapTotal"] ?: 0, mem["SwapFree"] ?: 0)
            }
            memsize > 0 -> {
                val pages = listOf("Pages free", "Pages inactive", "Pages speculative", "Pages purgeable").sumOf { vm[it] ?: 0 }
                val total = memsize / 1024
                Memory(total, (pages * pagesize / 1024).coerceAtMost(total), swap?.first ?: 0, swap?.let { it.first - it.second } ?: 0)
            }
            else -> null
        }
        return HealthSample(
            at = now, os = os, uid = uid, home = home, cores = cores, memory = memory,
            load1 = load[0], load5 = load[1], load15 = load[2], cpuBusy = cpu?.coerceIn(0.0, 100.0),
            disks = disks(df), procs = procs,
        )
    }

    private fun cpuLine(v: String): List<Long> = v.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }

    /** How busy the CPUs were between two `/proc/stat` cpu lines, 0–100 (user … steal; idle and iowait count as idle). */
    fun busy(a: List<Long>, b: List<Long>): Double? {
        val n = minOf(a.size, b.size, 8)
        if (n < 4) return null
        val total = (0 until n).sumOf { b[it] - a[it] }
        val idle = (b[3] - a[3]) + if (n > 4) b[4] - a[4] else 0
        return if (total > 0) 100.0 * (total - idle) / total else null
    }

    /** `total = 2048.00M  used = 1024.50M  free = 1023.50M` → total and used, in KiB. */
    private fun swapUsage(v: String): Pair<Long, Long>? {
        fun field(name: String): Long? = Regex("$name = ([0-9.]+)([KMGT])").find(v)?.let {
            val n = it.groupValues[1].toDoubleOrNull() ?: return null
            val mul = when (it.groupValues[2]) { "K" -> 1.0; "M" -> 1024.0; "G" -> 1024.0 * 1024; else -> 1024.0 * 1024 * 1024 }
            (n * mul).toLong()
        }
        return field("total")?.let { t -> t to (field("used") ?: 0) }
    }

    private fun proc(line: String): Proc? {
        val f = line.split('\t', limit = 13)
        if (f.size < 13) return null
        return Proc(
            pid = f[1].toIntOrNull() ?: return null,
            ppid = f[2].toIntOrNull() ?: 0,
            uid = f[3].toIntOrNull() ?: -1,
            rssKb = f[4].toLongOrNull() ?: 0,
            cpu = f[5].toDoubleOrNull() ?: 0.0,
            ageSeconds = f[6].toLongOrNull() ?: 0,
            comm = f[7],
            cwd = f[8].removeSuffix(" (deleted)"),
            launchDir = f[9],
            paneId = f[10],
            workspaceId = f[11],
            args = f[12],
        )
    }

    private val MAC_PS = Regex("""^\s*(\d+)\s+(\d+)\s+(-?\d+)\s+(\d+)\s+([0-9.]+)\s+(\S+)\s+(.*)$""")

    /** `pid ppid uid rss %cpu etime args` from macOS's ps; etime is `[[dd-]hh:]mm:ss`. */
    private fun macProc(line: String): Proc? {
        val m = MAC_PS.find(line) ?: return null
        val g = m.groupValues
        val args = g[7].trim()
        return Proc(
            pid = g[1].toInt(), ppid = g[2].toInt(), uid = g[3].toInt(), rssKb = g[4].toLong(),
            cpu = g[5].toDoubleOrNull() ?: 0.0, ageSeconds = etime(g[6]),
            comm = executable(args).substringAfterLast('/'), args = args,
        )
    }

    /**
     * The program's path at the start of a command line, which macOS's ps doesn't set apart: words are
     * joined while the next one carries on a path ("/Applications/Android Studio.app/Contents/…/java").
     */
    fun executable(args: String): String {
        val words = args.split(' ')
        var n = 1
        while (n < words.size && '/' in words[n] && !words[n].startsWith("/") && !words[n].startsWith("-")) n++
        return words.take(n).joinToString(" ")
    }

    fun etime(v: String): Long {
        val days = if ('-' in v) v.substringBefore('-').toLongOrNull() ?: 0 else 0
        val parts = v.substringAfter('-').split(':').map { it.toLongOrNull() ?: 0 }
        val secs = parts.fold(0L) { acc, x -> acc * 60 + x }
        return days * 86400 + secs
    }

    private val SKIP_TYPES = setOf(
        "tmpfs", "devtmpfs", "squashfs", "overlay", "efivarfs", "proc", "sysfs", "cgroup", "cgroup2", "devfs", "autofs",
        "ramfs", "nsfs", "fusectl", "debugfs", "tracefs", "securityfs", "pstore", "bpf", "configfs", "mqueue", "hugetlbfs",
        "binfmt_misc", "nullfs", "fdescfs", "rpc_pipefs", "fuse.portal", "fuse.gvfsd-fuse", "iso9660", "udf",
    )
    private val SKIP_MOUNTS = listOf("/boot", "/dev", "/proc", "/sys", "/run", "/snap", "/var/lib/docker", "/var/snap", "/System/Volumes/", "/private/var/vm", "/Volumes/Recovery")
    private val DF_TYPED = Regex("""^(.+?)\s+(\S+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)%\s+(/.*)$""")
    private val DF_PLAIN = Regex("""^(.+?)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)%\s+(/.*)$""")

    /**
     * The filesystems worth showing from `df -kPT` (or `-kP`): real disks, not memory, images or
     * system mounts; one per filesystem where it is mounted several times (btrfs subvolumes, APFS
     * volumes of one container), under its shortest mount.
     */
    fun disks(lines: List<String>): List<Disk> {
        val typed = lines.firstOrNull()?.contains("Type") == true
        val all = lines.drop(1).mapNotNull { l ->
            if (typed) DF_TYPED.find(l)?.groupValues?.let { g -> Disk(g[7], g[1].trim(), g[2], g[3].toLong(), g[4].toLong(), g[5].toLong()) }
            else DF_PLAIN.find(l)?.groupValues?.let { g -> Disk(g[6], g[1].trim(), "", g[2].toLong(), g[3].toLong(), g[4].toLong()) }
        }
        return all.asSequence()
            .filter { it.totalKb > 0 && it.type !in SKIP_TYPES }
            .filter { d -> SKIP_MOUNTS.none { d.mount == it || d.mount.startsWith(it.trimEnd('/') + "/") } }
            .filter { typed || it.device.startsWith("/") }
            .sortedBy { it.mount.length }
            .distinctBy { if (it.device.startsWith("/")) it.device else "${it.totalKb}/${it.availKb}" }
            .distinctBy { "${it.totalKb}/${it.availKb}" }
            .sortedBy { if (it.mount == "/") "" else it.mount }
            .toList()
    }

    /** [HealthCommands.worktrees]. */
    fun worktrees(out: String): List<WorktreeDir> = out.lines().mapNotNull { l ->
        val f = l.trimEnd('\r').split('\t')
        if (f.size < 4 || f[0] != "W" || !f[1].startsWith("/")) return@mapNotNull null
        WorktreeDir(f[1], f[2].toLongOrNull(), f[3] == "1")
    }

    /** What [HealthCommands.kill] did to each pid. */
    data class Killed(val stopped: List<Int>, val forced: List<Int>, val gone: List<Int>, val failed: List<Int>)

    fun killed(out: String): Killed {
        val rows = out.lines().mapNotNull { l ->
            val w = l.trim().split(' ')
            if (w.size == 2) w[1].toIntOrNull()?.let { w[0] to it } else null
        }
        fun of(k: String) = rows.filter { it.first == k }.map { it.second }
        return Killed(of("sent"), of("forced"), of("gone"), of("fail"))
    }
}
