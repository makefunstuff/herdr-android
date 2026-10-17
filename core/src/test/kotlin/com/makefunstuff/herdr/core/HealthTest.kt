package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.HerdrJson
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.health.Disk
import com.makefunstuff.herdr.core.health.HealthClient
import com.makefunstuff.herdr.core.health.HealthCommands
import com.makefunstuff.herdr.core.health.HealthHistory
import com.makefunstuff.herdr.core.health.HealthPoint
import com.makefunstuff.herdr.core.health.HealthReader
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.HealthRules
import com.makefunstuff.herdr.core.health.HealthSample
import com.makefunstuff.herdr.core.health.Memory
import com.makefunstuff.herdr.core.health.Proc
import com.makefunstuff.herdr.core.health.ProcKind
import com.makefunstuff.herdr.core.health.ProcLabels
import com.makefunstuff.herdr.core.health.Sizes
import com.makefunstuff.herdr.core.health.WorktreeDir
import com.makefunstuff.herdr.core.ssh.CommandResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class HealthTest {
    private val linux = File("/proc/meminfo").canRead()

    private fun sh(command: String, home: File? = null, dir: File? = null): CommandResult {
        val pb = ProcessBuilder("sh", "-c", command)
        if (home != null) pb.environment()["HOME"] = home.path
        if (dir != null) pb.directory(dir)
        val p = pb.start()
        p.outputStream.close()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return CommandResult(p.exitValue(), out, err)
    }

    private val client = HealthClient { cmd, _ -> sh(cmd) }

    // ---- the probe, run on this machine -----------------------------------------------------------

    @Test
    fun probeReadsThisMachine() = runBlocking {
        assumeTrue(linux, "the full probe is for Linux")
        val s = client.sample(listOf("herdr-android/work-22-"))
        val m = assertNotNull(s.memory)
        assertTrue(m.totalKb > 0 && m.availableKb in 1..m.totalKb, "$m")
        assertTrue(s.cores >= 1)
        assertTrue(s.at > 1_700_000_000)
        assertEquals("Linux", s.os)
        assertTrue(s.home.startsWith("/"))
        assertNotNull(s.cpuBusy)
        assertTrue(s.disks.isNotEmpty(), "disks")
        assertTrue(s.disks.none { it.type == "tmpfs" })
        assertTrue(s.procs.isNotEmpty(), "procs")
        // Only what is worth showing travels: never the whole process table of small processes.
        assertTrue(s.procs.all { it.rssKb >= 65536 || it.cpu >= 2 || s.own(it) }, "filtered")
        // The account's own processes carry where they run.
        val own = s.procs.filter { s.own(it) }
        if (own.isNotEmpty()) assertTrue(own.any { it.cwd.startsWith("/") }, "cwd of own processes")
    }

    @Test
    fun quickLookReadsThisMachine() = runBlocking {
        assumeTrue(linux, "Linux")
        val s = client.quick()
        assertNotNull(s.memory)
        assertNotNull(s.cpuBusy)
        assertTrue(s.procs.isEmpty())
    }

    @Test
    fun probeFindsAProcessInAFinishedWorktree() = runBlocking {
        assumeTrue(linux, "Linux")
        val home = Files.createTempDirectory("health").toFile()
        val wt = File(home, ".herdr/worktrees/demo/work-7-old-task").apply { mkdirs() }
        val p = ProcessBuilder("sleep", "300").directory(wt).start()
        try {
            // Wait until it runs as sleep in the worktree (Java starts it through a helper first).
            val pid = p.pid()
            fun seen() = sh("readlink /proc/$pid/cwd; cat /proc/$pid/comm").stdout.lines()
            val until = System.currentTimeMillis() + 5_000
            while (seen() != listOf(wt.canonicalPath, "sleep", "") && System.currentTimeMillis() < until) Thread.sleep(50)
            // The probe reads other processes' folders as any other program of the account would. Some
            // sandboxes hide those from a process that isn't their parent; there is nothing to find then.
            assumeTrue(seen().firstOrNull() == wt.canonicalPath, "this system doesn't let a shell read another process's folder: ${seen()}")
            val s = client.sample(listOf("demo/work-7-"))
            val mine = s.procs.firstOrNull { it.pid.toLong() == pid }
                ?: fail("the sleep in the finished worktree isn't listed; the probe printed for it: " +
                    sh(HealthCommands.probe(listOf("demo/work-7-"))).stdout.lines().filter { "\t$pid\t" in it || it.startsWith("@") }.take(20))
            assertEquals("sleep", mine.comm)
            assertEquals(wt.canonicalPath, mine.cwd)
            assertEquals("sleep 300", mine.args)
            // Without the worktree in [keep] a small process stays home.
            assertNull(client.sample().procs.firstOrNull { it.pid.toLong() == pid })
        } finally {
            p.destroyForcibly()
            home.deleteRecursively()
        }
    }

    // ---- killing ------------------------------------------------------------------------------------

    @Test
    fun killStopsOnlyTheSameProgram() = runBlocking {
        assumeTrue(linux, "Linux")
        val p = ProcessBuilder("sleep", "300").start()
        try {
            val pid = p.pid().toInt()
            val wrong = client.kill(listOf(HealthCommands.Target(pid, "java")))
            assertEquals(listOf(pid), wrong.gone)
            assertTrue(p.isAlive, "a pid that is now another program is left alone")
            val done = client.kill(listOf(HealthCommands.Target(pid, "sleep")))
            assertEquals(listOf(pid), done.stopped)
            assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun killForcesWhatIgnoresTerm() = runBlocking {
        assumeTrue(linux, "Linux")
        val p = ProcessBuilder("sh", "-c", "trap '' TERM; while :; do sleep 1; done").start()
        try {
            Thread.sleep(300)
            val pid = p.pid().toInt()
            val done = client.kill(listOf(HealthCommands.Target(pid, "sh")))
            assertEquals(listOf(pid), done.stopped)
            assertEquals(listOf(pid), done.forced)
            assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        } finally {
            p.destroyForcibly()
        }
    }

    // ---- worktrees ----------------------------------------------------------------------------------

    @Test
    fun listsAndRemovesWorktrees() = runBlocking {
        assumeTrue(sh("git --version").exitStatus == 0, "git")
        val home = Files.createTempDirectory("health").toFile()
        try {
            val repo = File(home, "src/demo").apply { mkdirs() }
            fun git(vararg a: String, dir: File = repo) = sh("git " + a.joinToString(" ") { "'$it'" }, home, dir).also {
                assertEquals(0, it.exitStatus, it.stderr)
            }
            git("init", "-q", "-b", "main")
            git("-c", "user.email=a@b", "-c", "user.name=a", "commit", "-q", "--allow-empty", "-m", "first")
            val clean = File(home, ".herdr/worktrees/demo/work-3-done-task")
            val dirty = File(home, ".herdr/worktrees/demo/work-4-other")
            git("worktree", "add", "-q", "-b", "work/3", clean.path)
            git("worktree", "add", "-q", "-b", "work/4", dirty.path)
            File(dirty, "notes.txt").writeText("not committed")

            val homeClient = HealthClient { cmd, _ -> sh(cmd, home) }
            val dirs = homeClient.worktrees().sortedBy { it.path }
            assertEquals(listOf("work-3-done-task", "work-4-other"), dirs.map { it.name })
            assertEquals(listOf(false, true), dirs.map { it.dirty })
            assertTrue(dirs.all { (it.sizeKb ?: 0) > 0 })
            assertEquals("demo", dirs[0].repo)

            homeClient.removeWorktree(clean.path, force = false)
            assertFalse(clean.exists())
            val refused = assertFailsWith<java.io.IOException> { homeClient.removeWorktree(dirty.path, force = false) }
            assertTrue("untracked" in refused.message.orEmpty() || "modified" in refused.message.orEmpty(), refused.message)
            assertTrue(dirty.exists())
            homeClient.removeWorktree(dirty.path, force = true)
            assertFalse(dirty.exists())
            assertEquals("That worktree is already gone.", assertFailsWith<java.io.IOException> { homeClient.removeWorktree(dirty.path, false) }.message)
        } finally {
            home.deleteRecursively()
        }
    }

    // ---- reading -------------------------------------------------------------------------------------

    private val linuxOut = """
        Last login: someday
        @@herdr-health 1
        @os Linux
        @now 1792250000
        @uid 1000
        @home /home/demo
        @cores 8
        @meminfo
        MemTotal:       65536000 kB
        MemFree:         1000000 kB
        MemAvailable:    5242880 kB
        Buffers:          100000 kB
        Cached:          3000000 kB
        SwapTotal:       8388608 kB
        SwapFree:        4194304 kB
        @loadavg 3.20 2.10 1.50 2/1234 99999
        @df
        Filesystem     Type     1024-blocks      Used Available Capacity Mounted on
        /dev/nvme0n1p2 btrfs      976762584 900000000  70000000      93% /
        /dev/nvme0n1p2 btrfs      976762584 900000000  70000000      93% /home
        tmpfs          tmpfs       32768000      1000  32767000       1% /run
        /dev/nvme0n1p1 vfat         1046508     30000   1016508       3% /boot/efi
        /dev/loop3     squashfs       56960     56960         0     100% /snap/core20/1974
        /dev/sdb1      ext4       960302096 610837828 300609844      68% /data/big disk
        @end-df
        @cpu 37.5
        P	4242	1	1000	5234804	0.0	86400	java	/home/demo/.gradle/daemon/8.11.1	/home/demo/.herdr/worktrees/shop/work-22-old-thing	w1C:p1	w1C	/usr/lib/jvm/bin/java -Xmx4g -cp /home/demo/.gradle/wrapper/dists/gradle-8.11.1-bin/x/gradle-8.11.1/lib/gradle-daemon-main-8.11.1.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.11.1
        P	5000	4999	1000	5819660	80.0	3600	qemu-system-x86	/home/demo/.herdr/worktrees/shop/work-22-old-thing/app				/home/demo/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64 -avd Pixel_8 -no-window
        P	6000	1	0	4904052	3.0	999999	qemu-system-x86					/usr/bin/qemu-system-x86_64 -name guest=home-services-vm,debug-threads=on -m 12g
        P	7000	6900	1000	412000	0.5	1200	claude	/home/demo/.herdr/worktrees/shop/work-23-new-thing		w1D:p1	w1D	claude --dangerously-skip-permissions
        P	7100	7000	1000	210000	4.0	600	node	/home/demo/.herdr/worktrees/shop/work-23-new-thing	/home/demo/.herdr/worktrees/shop/work-23-new-thing	w1D:p2	w1D	node /home/demo/.herdr/worktrees/shop/work-23-new-thing/node_modules/.bin/vite --port 5173
        P	7200	1	1000	300000	0.0	9000	node	/home/demo/src/blog				node server.js
        P	7300	1	1000	900000	0.0	9000	java	/home/demo/.herdr/worktrees/shop/work-22-old-thing				java -cp kotlin-compiler-embeddable.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon --daemon-runFilesPath x
        P	7400	1	1001	800000	0.0	9000	java	/home/other/.herdr/worktrees/shop/work-22-old-thing				java -jar other.jar
        P	7500	1	1000	20000	0.0	9000	adb	/home/demo/.herdr/worktrees/shop/work-22-old-thing				adb -L tcp:5037 fork-server server --reply-fd 4
        P	7600	1	1000	30000	0.0	9000	herdr	/home/demo/.herdr/worktrees/shop/work-22-old-thing (deleted)				herdr --session extras22 server
        P	7700	1	1000	90000	0.0	9000	herdr	/home/demo/.herdr/worktrees/shop/work-22-old-thing				/home/demo/.local/bin/herdr server
        P	7800	1	1000	900000	35.0	9000	java	/home/demo/.gradle/daemon/8.11.1	/home/demo/.herdr/worktrees/shop/work-22-old-thing			java org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.11.1
    """.trimIndent()

    @Test
    fun readsLinux() {
        val s = HealthReader.sample(linuxOut)
        assertEquals(1792250000, s.at)
        assertEquals(8, s.cores)
        assertEquals(Memory(65536000, 5242880, 8388608, 4194304), s.memory)
        assertEquals(8.0, s.memory!!.availablePercent)
        assertEquals(50.0, s.memory!!.swapUsedPercent)
        assertEquals(3.2, s.load1)
        assertEquals(40.0, s.loadPercent)
        assertEquals(37.5, s.cpuBusy)
        // btrfs subvolumes once, under /; no tmpfs, EFI or snap images; a mount with a space.
        assertEquals(listOf("/", "/data/big disk"), s.disks.map { it.mount })
        assertEquals(12, s.procs.size)
        assertEquals("/home/demo/.herdr/worktrees/shop/work-22-old-thing", s.procs.first { it.pid == 7600 }.cwd)
        val g = s.procs.first()
        assertEquals(4242, g.pid)
        assertEquals(5234804, g.rssKb)
        assertEquals(0.0, g.cpu)
        assertEquals("/home/demo/.herdr/worktrees/shop/work-22-old-thing", g.launchDir)
        assertEquals("w1C:p1", g.paneId)
    }

    @Test
    fun readsCpuFromTwoLooks() {
        val out = "@@herdr-health 1\n@cpu1 cpu  100 0 100 700 100 0 0 0 0 0\n@cpu2 cpu  200 0 200 1300 300 0 0 0 0 0\n"
        // 1000 ticks passed, 800 of them idle or waiting.
        assertEquals(20.0, HealthReader.sample(out).cpuBusy)
    }

    @Test
    fun readsMacos() {
        val out = """
            @@herdr-health 1
            @os Darwin
            @now 1792250000
            @uid 501
            @home /Users/demo
            @cores 10
            @memsize 17179869184
            @pagesize 16384
            @swapusage total = 2048.00M  used = 1024.00M  free = 1024.00M  (encrypted)
            @loadavg  2.50 2.00 1.80
            @vmstat
            Mach Virtual Memory Statistics: (page size of 16384 bytes)
            Pages free:                               10000.
            Pages active:                            300000.
            Pages inactive:                          100000.
            Pages speculative:                         5000.
            Pages purgeable:                           1000.
            @df
            Filesystem     1024-blocks      Used Available Capacity  Mounted on
            /dev/disk3s1s1   971350180  10000000 400000000     3%    /
            devfs                  199       199         0   100%    /dev
            /dev/disk3s5     971350180 500000000 400000000    56%    /System/Volumes/Data
            map auto_home            0         0         0   100%    /System/Volumes/Data/home
            /dev/disk5s1     100000000  90000000  10000000    90%    /Volumes/External
            @end-df
            @ps
              812     1   501  1500000  45.2 02-03:04:05 /Applications/Android Studio.app/Contents/jbr/bin/java -Xmx2g
              900     1   501   700000   0.0    12:34 /opt/homebrew/bin/node /Users/demo/site/node_modules/.bin/next dev
        """.trimIndent()
        val s = HealthReader.sample(out)
        val m = assertNotNull(s.memory)
        assertEquals(16L * 1024 * 1024, m.totalKb)
        assertEquals(116000L * 16, m.availableKb)
        assertEquals(2048L * 1024, m.swapTotalKb)
        assertEquals(1024L * 1024, m.swapUsedKb)
        assertEquals(2.5, s.load1)
        assertNull(s.cpuBusy)
        assertEquals(listOf("/", "/Volumes/External"), s.disks.map { it.mount })
        assertEquals(2, s.procs.size)
        assertEquals(2 * 86400L + 3 * 3600 + 4 * 60 + 5, s.procs[0].ageSeconds)
        assertEquals("java", s.procs[0].comm)
        assertEquals(754, s.procs[1].ageSeconds)
        assertEquals(ProcKind.DevServer, ProcLabels.kind(s.procs[1]).first)
    }

    @Test
    fun refusesOutputWithoutTheMark() {
        assertFailsWith<java.io.IOException> { HealthReader.sample("sh: awk: not found\n") }
    }

    @Test
    fun readsWhatKillAndWorktreesPrint() {
        assertEquals(HealthReader.Killed(listOf(1, 2), listOf(2), listOf(3), listOf(4)), HealthReader.killed("sent 1\nsent 2\ngone 3\nfail 4\nforced 2\n"))
        assertEquals(
            listOf(WorktreeDir("/h/.herdr/worktrees/a/work-1-x", 1234, false), WorktreeDir("/h/.herdr/worktrees/a/b", null, true)),
            HealthReader.worktrees("@@herdr-health 1\nW\t/h/.herdr/worktrees/a/work-1-x\t1234\t0\nW\t/h/.herdr/worktrees/a/b\t\t1\nnoise\n"),
        )
    }

    // ---- labels, owners and leftovers ---------------------------------------------------------------

    private val tasks = BoardTasks.list(
        """[
          {"id":"shop/22","title":"Old thing","state":"done","stage":"done","msg":"Merged"},
          {"id":"shop/23","title":"New thing","state":"working","stage":"working"},
          {"id":"shop/24","title":"Archived one","state":"done","stage":"done","archivedAt":1790000000},
          {"id":"vm:shop/22","title":"Another machine's","state":"done","stage":"done"}
        ]""",
    ).map { if (it.id == "shop/22") it.copy(key = "SHO-22") else it }

    private val snapshot = HerdrJson.decodeFromString(
        SessionSnapshot.serializer(),
        """{"workspaces":[{"workspace_id":"w1D","label":"shop 23"},{"workspace_id":"w1C","label":"shop 22","worktree":{"checkout_path":"/home/demo/.herdr/worktrees/shop/work-22-old-thing"}}],
           "tabs":[{"tab_id":"w1D:t1","workspace_id":"w1D","label":"agent"}],
           "panes":[{"pane_id":"w1D:p1","workspace_id":"w1D","tab_id":"w1D:t1","agent":"claude"}],
           "agents":[{"pane_id":"w1D:p1","workspace_id":"w1D","tab_id":"w1D:t1","agent":"claude","name":"New thing","agent_status":"working"}]}""",
    )

    @Test
    fun namesWhatEachProcessIs() {
        val s = HealthReader.sample(linuxOut)
        val names = ProcLabels.label(s, tasks, snapshot).associate { it.proc.pid to (it.kind to it.name) }
        assertEquals(ProcKind.Gradle to "Gradle daemon 8.11.1", names[4242])
        assertEquals(ProcKind.Emulator to "Android emulator · Pixel_8", names[5000])
        assertEquals(ProcKind.Vm to "QEMU VM · home-services-vm", names[6000])
        assertEquals(ProcKind.Agent to "claude", names[7000])
        assertEquals(ProcKind.DevServer to "Dev server · vite", names[7100])
        assertEquals(ProcKind.Node to "node · server.js", names[7200])
        assertEquals(ProcKind.KotlinDaemon to "Kotlin daemon", names[7300])
    }

    @Test
    fun ownersAndLeftovers() {
        val s = HealthReader.sample(linuxOut)
        val labeled = ProcLabels.label(s, tasks, snapshot)
        val by = labeled.associateBy { it.proc.pid }
        // The Gradle daemon's own folder is Gradle's, but it was started from the finished task's worktree.
        assertEquals("shop/22", by[4242]!!.owner.taskId)
        assertEquals("SHO-22 · done · Old thing", by[4242]!!.ownerLine)
        assertTrue(by[4242]!!.leftover)
        assertTrue(by[5000]!!.leftover, "the emulator runs in a subfolder of it")
        assertTrue(by[7300]!!.leftover)
        // Running work, other people's processes and processes outside worktrees are never leftovers.
        assertFalse(by[7000]!!.leftover)
        assertEquals("shop/23 · working · New thing", by[7000]!!.ownerLine)
        assertEquals("New thing", by[7000]!!.owner.agent)
        assertEquals("shop 23 › agent", by[7000]!!.owner.where)
        assertFalse(by[7400]!!.leftover)
        assertFalse(by[6000]!!.leftover)
        // What other work shares stays: adb, this machine's herdr server, a Gradle daemon while it builds. A test server goes.
        assertFalse(by[7500]!!.leftover)
        assertTrue(by[7600]!!.leftover)
        assertFalse(by[7700]!!.leftover)
        assertFalse(by[7800]!!.leftover)
        assertEquals("node server.js", by[7200]!!.ownerLine)

        assertEquals(listOf("shop/work-22-", "shop/work-24-"), ProcLabels.keep(tasks))

        val dirs = listOf(
            WorktreeDir("/home/demo/.herdr/worktrees/shop/work-22-old-thing", 3_000_000, false),
            WorktreeDir("/home/demo/.herdr/worktrees/shop/work-23-new-thing", 1_000_000, true),
            WorktreeDir("/home/demo/.herdr/worktrees/shop/work-24-archived-one", 500_000, false),
            WorktreeDir("/home/demo/.herdr/worktrees/shop/work-99-not-on-the-board", 500_000, false),
        )
        val left = ProcLabels.leftovers(labeled, tasks, dirs, snapshot)
        assertEquals(listOf("shop/22", "shop/24"), left.map { it.taskId })
        assertEquals(listOf(5000, 4242, 7300, 7600), left[0].procs.map { it.proc.pid })
        assertEquals("w1C", left[0].workspace?.workspaceId)
        assertEquals(BoardStatus.Archived, left[1].status)
        assertTrue(left[1].procs.isEmpty())
        assertEquals("work-24-archived-one", left[1].worktree?.name)
        // Without the board there is nothing to call finished.
        assertTrue(ProcLabels.leftovers(ProcLabels.label(s, null, snapshot), null, dirs, snapshot).isEmpty())
        assertEquals(listOf(5000 to "qemu-system-x86", 4242 to "java"), ProcLabels.targets(left[0].procs.take(2)).map { it.pid to it.comm })
    }

    @Test
    fun worktreesAndTheirTasks() {
        assertEquals("/h/.herdr/worktrees/shop/work-22-x", ProcLabels.worktreeOf("/h/.herdr/worktrees/shop/work-22-x/app/src"))
        assertEquals("/h/.herdr/worktrees/shop/work-22-x", ProcLabels.worktreeOf("/h/.herdr/worktrees/shop/work-22-x"))
        assertNull(ProcLabels.worktreeOf("/h/.herdr/worktrees/shop"))
        assertNull(ProcLabels.worktreeOf("/h/src/shop"))
        assertEquals("shop/22", ProcLabels.taskIdOf("/h/.herdr/worktrees/shop/work-22-x"))
        assertEquals("shop/7", ProcLabels.taskIdOf("/h/.herdr/worktrees/shop/work-7"))
        assertNull(ProcLabels.taskIdOf("/h/.herdr/worktrees/shop/feature-x"))
    }

    // ---- low or fine --------------------------------------------------------------------------------

    private fun sample(availPercent: Double, disks: List<Disk> = emptyList()) = HealthSample(
        at = 0, os = "Linux", uid = 1000, home = "/h", cores = 4,
        memory = Memory(1_000_000, (availPercent * 10_000).toLong()), load1 = 0.0, load5 = 0.0, load15 = 0.0, cpuBusy = 0.0,
        disks = disks, procs = listOf(Proc(1, 0, 1000, 700_000, 0.0, 0, "qemu-system-x86", "qemu-system-x86_64 -avd Pixel_8")),
    )

    @Test
    fun ramLowWithRoomToComeBack() {
        val procs = { s: HealthSample -> ProcLabels.label(s, null, null) }
        val low = sample(9.0)
        assertEquals(listOf("ram"), HealthRules.low(low))
        val report = assertNotNull(HealthReport.of(low, procs(low)))
        assertTrue(report.ram)
        assertEquals(listOf("Android emulator · Pixel_8" to 700_000L), report.top)
        // Between 10 and 15%: neither low nor fine, so a warning stays as it is.
        assertNull(HealthReport.of(sample(12.0), procs(sample(12.0))))
        assertEquals(HealthReport(emptyList()), HealthReport.of(sample(16.0), procs(sample(16.0))))
    }

    @Test
    fun diskLowIsATenthAtMostTwentyGb() {
        val gb = 1024L * 1024
        fun disk(sizeGb: Long, freeGb: Long) = Disk("/", "/dev/x", "ext4", sizeGb * gb, (sizeGb - freeGb) * gb, freeGb * gb)
        assertEquals(listOf("disk:/"), HealthRules.low(sample(50.0, listOf(disk(100, 9)))))
        assertTrue(HealthRules.low(sample(50.0, listOf(disk(100, 11)))).isEmpty())
        // A big disk is low under 20 GB, not under a tenth.
        assertTrue(HealthRules.low(sample(50.0, listOf(disk(2000, 50)))).isEmpty())
        assertEquals(listOf("disk:/"), HealthRules.low(sample(50.0, listOf(disk(2000, 19)))))
        // Fine again from one and a half times the limit.
        assertFalse(HealthRules.fine(sample(50.0, listOf(disk(100, 12)))))
        assertTrue(HealthRules.fine(sample(50.0, listOf(disk(100, 15)))))
        // Tiny filesystems aren't watched.
        assertTrue(HealthRules.low(sample(50.0, listOf(Disk("/small", "/dev/y", "ext4", 500_000, 499_000, 1000)))).isEmpty())
    }

    // ---- history and words --------------------------------------------------------------------------

    @Test
    fun historyFromHerdrPush() {
        val text = """{"v": 1, "points": [[1000, 67.0, 21.8, null], [1030, 70.5, 21.8, 15.8], ["bad"], [1060, 71]]}"""
        assertEquals(
            listOf(HealthPoint(1000, 67.0, 21.8, null), HealthPoint(1030, 70.5, 21.8, 15.8), HealthPoint(1060, 71.0, 0.0, null)),
            HealthClient.parseHistory(text),
        )
        assertTrue(HealthClient.parseHistory("").isEmpty())
        val points = listOf(HealthPoint(0, 1.0, 0.0, 0.0), HealthPoint(5000, 2.0, 0.0, 0.0), HealthPoint(5003, 3.0, 0.0, 0.0), HealthPoint(5020, 4.0, 0.0, 0.0))
        // An hour back from now, and points closer than ten seconds are one (the newer).
        assertEquals(listOf(3.0, 4.0), HealthHistory.trim(points, 5030).map { it.memUsed })
    }

    @Test
    fun sizes() {
        assertEquals("512 KB", Sizes.kb(512))
        assertEquals("812 MB", Sizes.kb(812L * 1024))
        assertEquals("3.4 GB", Sizes.kb((3.4 * 1024 * 1024).toLong()))
        assertEquals("250 GB", Sizes.kb(250L * 1024 * 1024))
        assertEquals("1.5 TB", Sizes.kb((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("3 h", Sizes.age(3 * 3600 + 5))
    }
}
