package demo.herdr.core.host

/** When a machine last answered, in words: "2 min ago". */
object LastAnswered {
    fun words(at: Long?, now: Long): String {
        if (at == null) return "never answered"
        val seconds = (now - at) / 1000
        return when {
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} days ago"
        }
    }
}
