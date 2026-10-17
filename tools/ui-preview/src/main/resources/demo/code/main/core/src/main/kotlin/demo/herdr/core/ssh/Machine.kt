package demo.herdr.core.ssh

/** A herdr machine: an SSH target and one herdr session on it. */
data class Machine(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    /** Another machine to reach this one through. */
    val via: String? = null,
) {
    val target: String get() = if (port == 22) "$user@$host" else "$user@$host:$port"
}
