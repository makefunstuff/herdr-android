package com.makefunstuff.herdr.core.health

/**
 * When a machine runs low, the same in the app and in herdr-push (host/herdr-push, `health_low`):
 * RAM when less than 10% is available; a disk when less than a tenth of it is free, but at most
 * 20 GB (a big disk with 50 GB free isn't low). It counts as fine again only with some room past
 * that (15%, and one and a half times the disk's limit), so a machine on the edge doesn't warn
 * again with every look.
 */
object HealthRules {
    const val RAM_LOW = 10.0
    const val RAM_OK = 15.0
    private const val DISK_CAP_KB = 20L * 1024 * 1024

    fun diskLimitKb(d: Disk): Long = minOf((d.usedKb + d.availKb) / 10, DISK_CAP_KB)

    /** The disks worth warning about: real ones bigger than 1 GB. */
    private fun watched(s: HealthSample) = s.disks.filter { it.usedKb + it.availKb > 1024L * 1024 }

    fun ramLow(m: Memory) = m.totalKb > 0 && m.availablePercent < RAM_LOW
    fun diskLow(d: Disk) = d.availKb < diskLimitKb(d)

    /** What is low now: `ram` and `disk:<mount>`. */
    fun low(s: HealthSample): List<String> =
        listOfNotNull(s.memory?.takeIf(::ramLow)?.let { "ram" }) + watched(s).filter(::diskLow).map { "disk:${it.mount}" }

    /** True once everything has its room back (past the fine-again marks): a warning can come down. */
    fun fine(s: HealthSample): Boolean =
        (s.memory?.let { it.totalKb == 0L || it.availablePercent >= RAM_OK } ?: true) &&
            watched(s).all { it.availKb >= diskLimitKb(it) * 3 / 2 }
}

/** A machine low on RAM or disk, as herdr-push sends it and as the app's own look finds it; [low] empty: fine again. */
data class HealthReport(
    val low: List<String>,
    val memory: Memory? = null,
    /** The disks that are low. */
    val disks: List<Disk> = emptyList(),
    /** What uses the most memory: name and KiB, biggest first. */
    val top: List<Pair<String, Long>> = emptyList(),
) {
    val ram: Boolean get() = "ram" in low

    companion object {
        /** The report for [sample], [procs] labelled; null when it is neither low nor fine again (in between). */
        fun of(sample: HealthSample, procs: List<LabeledProc>): HealthReport? {
            val low = HealthRules.low(sample)
            if (low.isEmpty()) return if (HealthRules.fine(sample)) HealthReport(emptyList()) else null
            val top = procs.sortedByDescending { it.proc.rssKb }.take(3).map { it.name to it.proc.rssKb }
            return HealthReport(low, sample.memory, sample.disks.filter { "disk:${it.mount}" in low }, top)
        }
    }
}
