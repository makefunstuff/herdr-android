package com.makefunstuff.herdr.core.logins

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a login reads on the phone: each CLI's line and badge, the machine row's glance, how to move a key. */
object LoginWords {
    /** A login that runs out within this is flagged: sign in again soon. */
    const val SOON_SECONDS = 3 * 86_400L

    private val day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun date(at: Long, zone: ZoneId): String = day.format(Instant.ofEpochSecond(at).atZone(zone))

    fun ago(at: Long, now: Long): String {
        val s = (now - at).coerceAtLeast(0)
        return when {
            s < 3_600 -> "within the hour"
            s < 86_400 -> "${s / 3_600} h ago"
            s < 2 * 86_400 -> "yesterday"
            else -> "${s / 86_400} days ago"
        }
    }

    /** Whether [c]'s login ran out ([now], epoch seconds). */
    fun expired(c: CliLogin, now: Long) = c.state == LoginState.SignedIn && c.expires != null && c.expires <= now

    /** Whether it runs out within [SOON_SECONDS]. */
    fun soon(c: CliLogin, now: Long) = c.state == LoginState.SignedIn && c.expires != null && c.expires > now && c.expires - now < SOON_SECONDS

    /** Whether it wants the person: signed out or ran out, with a way to sign in from the phone. */
    fun wants(c: CliLogin, now: Long) = (c.state == LoginState.SignedOut || expired(c, now)) && c.actions.isNotEmpty()

    /**
     * Its line under the name: "Claude subscription (Max) · good until 13 Nov, then sign in again",
     * "ChatGPT login · last renewed 8 days ago", "8 providers: 5 keys from a command (keyring), 3 typed in".
     */
    fun line(c: CliLogin, now: Long, zone: ZoneId): String {
        if (c.state == LoginState.Missing) return "Not installed"
        if (c.state == LoginState.Unknown) return c.msg ?: "Couldn't tell"
        if (c.providers.isNotEmpty() && c.how == null) return providers(c)
        val how = c.how ?: if (c.state == LoginState.SignedIn) "Signed in" else "Not signed in"
        val e = c.expires
        val r = c.renewed
        return when {
            c.state != LoginState.SignedIn -> how
            e != null && e <= now -> "$how · ran out ${date(e, zone)}: sign in again"
            e != null -> "$how · good until ${date(e, zone)}, then sign in again"
            r != null -> "$how · last renewed ${ago(r, now)}"
            else -> how
        }
    }

    private fun providers(c: CliLogin): String {
        val ps = c.providers
        if (c.id == "opencode") {
            // "OpenAI login · llama.cpp key · HF_TOKEN from the environment"
            return ps.joinToString(" · ") { p ->
                when (p.source) {
                    KeySource.OAuth -> "${p.id} login"
                    KeySource.Env -> "${p.method ?: p.id} from the environment"
                    else -> "${p.id} key"
                }
            }
        }
        val parts = listOfNotNull(
            ps.count { it.source == KeySource.Command }.takeIf { it > 0 }?.let { "$it ${keys(it)} from a command (keyring)" },
            ps.count { it.source == KeySource.Typed }.takeIf { it > 0 }?.let { "$it typed in" },
            ps.count { it.source == KeySource.Env }.takeIf { it > 0 }?.let { "$it from variables" },
            ps.count { it.source == KeySource.OAuth }.takeIf { it > 0 }?.let { "$it ${if (it == 1) "login" else "logins"}" },
        )
        return "${ps.size} ${if (ps.size == 1) "provider" else "providers"}: " + parts.joinToString(", ")
    }

    private fun keys(n: Int) = if (n == 1) "key" else "keys"

    /** The badge on its row: "signed in", "8 keys", "ran out", "not signed in"; null for one with a Sign in button instead. */
    fun badge(c: CliLogin, now: Long): String? = when {
        c.state == LoginState.Missing -> "not installed"
        c.state == LoginState.Unknown -> "unknown"
        expired(c, now) -> "ran out"
        c.state == LoginState.SignedIn && c.id == "pi" && c.providers.isNotEmpty() -> "${c.providers.size} ${keys(c.providers.size)}"
        c.state == LoginState.SignedIn -> "signed in"
        else -> null
    }

    /** The machine row's glance: "4 of 5 signed in · a key in ~/.bashrc". */
    fun glance(r: LoginsReport, now: Long): String {
        val all = r.installed
        if (all.isEmpty()) return "No agent CLI found"
        val ok = all.count { it.state == LoginState.SignedIn && !expired(it, now) }
        val out = all.filter { expired(it, now) }
        return listOfNotNull(
            "$ok of ${all.size} signed in",
            out.firstOrNull()?.let { "${it.name} ran out" },
            r.profileKeys.firstOrNull()?.let { k -> if (r.profileKeys.size == 1) "a key in ${k.file}" else "${r.profileKeys.size} keys in shell profiles" },
        ).joinToString(" · ")
    }

    /** The warning's text: "~/.bashrc exports HF_TOKEN (and a short placeholder OPENAI_API_KEY)". */
    fun profileWarning(r: LoginsReport): String? {
        val real = r.profileKeys
        if (real.isEmpty()) return null
        val byFile = real.groupBy { it.file }
        val main = byFile.entries.joinToString("; ") { (f, ks) -> "$f exports ${ks.joinToString(", ") { it.name }}" }
        val short = r.profile.filter { it.placeholder }
        val also = if (short.isEmpty()) "" else " (and a short placeholder ${short.joinToString(", ") { it.name }})"
        return "$main$also, so every program and agent started from a shell gets ${if (real.size == 1) "it" else "them"}."
    }

    /**
     * The steps to move [k] out of its profile: the CLI that keeps such a key in its own store, then
     * the line to take out by hand. The app shows them and edits nothing.
     */
    fun moveSteps(k: ProfileKey): List<String> {
        val store = when {
            k.name == "OPENAI_API_KEY" -> "Give it to Codex with Paste a key here (codex login --with-api-key), or to opencode with opencode auth login openai"
            k.name == "ANTHROPIC_API_KEY" -> "Sign Claude Code in with claude auth login --console (API billing) instead of the variable"
            k.name == "HF_TOKEN" || k.name == "HUGGING_FACE_HUB_TOKEN" -> "Store it with hf auth login (Hugging Face's own command keeps it in ~/.cache/huggingface/token)"
            k.name == "GEMINI_API_KEY" || k.name == "GOOGLE_API_KEY" -> "Sign Gemini CLI in with its first run (gemini, then Login with Google), or keep the key in a keyring"
            k.name == "GITHUB_TOKEN" || k.name == "GH_TOKEN" -> "Store it with gh auth login (the GitHub CLI keeps it in the keyring)"
            else -> {
                val provider = k.name.substringBefore("_API_KEY").substringBefore("_TOKEN").lowercase()
                "Give it to the agent that uses it in its own store: pi takes it here with Paste a key ($provider), opencode with opencode auth login"
            }
        }
        return listOf(
            store,
            "Open ${k.file} on the machine and delete line ${k.line} (export ${k.name}=…)",
            "Open a new shell, or run unset ${k.name} in the ones that are open; agents started before still have it until they restart",
        )
    }
}
