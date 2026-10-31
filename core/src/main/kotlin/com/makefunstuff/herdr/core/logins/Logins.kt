package com.makefunstuff.herdr.core.logins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** Where an agent CLI stands on a machine. */
enum class LoginState { SignedIn, SignedOut, Missing, Unknown }

/** How a CLI takes a secret: typed in (kept in its own file), from a command (a keyring), a variable, or a login. */
enum class KeySource { Typed, Command, Env, OAuth }

/** What the app can do about a CLI's login there, each through that CLI's own command (host/herdr-logins). */
enum class LoginAction(val key: String) {
    /** Sign in again: a device code or a browser code, shown on the phone. */
    Login("login"),

    /** Paste a key, taken on the command's stdin. */
    Key("key"),
    Logout("logout"),
    ;

    companion object {
        fun of(key: String): LoginAction? = entries.firstOrNull { it.key == key }
    }
}

/** One provider of a CLI that has several (pi, opencode): how its secret is kept, never the secret. */
data class LoginProvider(val id: String, val source: KeySource, val method: String? = null, val expires: Long? = null)

/**
 * One agent CLI's login on a machine. [expires]: when the login runs out (epoch seconds), [renewed]:
 * when it was last renewed; [env]: it is signed in by a variable in the environment, nothing to change here.
 */
data class CliLogin(
    val id: String,
    val name: String,
    val state: LoginState,
    val how: String? = null,
    val expires: Long? = null,
    val renewed: Long? = null,
    val env: Boolean = false,
    val actions: Set<LoginAction> = emptySet(),
    val providers: List<LoginProvider> = emptyList(),
    val msg: String? = null,
)

/** A key exported in plain text from a shell profile: where, never its value. [placeholder]: too short to be a real one. */
data class ProfileKey(val file: String, val line: Int, val name: String, val placeholder: Boolean)

/** A machine's agent logins as herdr-logins read them there, at [at] (its clock, epoch seconds). */
data class LoginsReport(val at: Long, val clis: List<CliLogin>, val profile: List<ProfileKey> = emptyList()) {
    /** The CLIs installed there, in herdr-logins' order. */
    val installed: List<CliLogin> get() = clis.filter { it.state != LoginState.Missing }

    /** Real-looking keys in a profile: the warning's. */
    val profileKeys: List<ProfileKey> get() = profile.filterNot { it.placeholder }
}

/** What a sign-in or sign-out says as it runs. */
sealed class LoginEvent {
    /** A page to open on the phone. */
    data class Link(val url: String) : LoginEvent()

    /** A code to type on that page. */
    data class Code(val code: String) : LoginEvent()

    /** The CLI waits for something typed: [secret] hides it as it is typed; [again]: it refused what was typed and waits on. */
    data class Ask(val text: String, val secret: Boolean, val again: Boolean = false) : LoginEvent()

    /** A line it printed (with anything typed in masked). */
    data class Line(val text: String) : LoginEvent()

    /** It ended; [status] is that CLI's login after it. */
    data class Done(val ok: Boolean, val msg: String?, val status: CliLogin?) : LoginEvent()
}

object LoginsReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** herdr-logins' JSON answer in [stdout] (its last JSON line), or null when there is none. */
    fun answer(stdout: String): JsonObject? = stdout.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.lastOrNull()
        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    fun parse(stdout: String): LoginsReport {
        val o = answer(stdout) ?: throw IOException("herdr-logins gave no answer")
        if (o.bool("ok") == false) throw IOException(o.str("msg") ?: "herdr-logins failed")
        return LoginsReport(
            at = o.long("at") ?: 0L,
            clis = (o["clis"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::cli) },
            profile = (o["profile"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                ProfileKey(p.str("file") ?: return@mapNotNull null, p.int("line") ?: 0, p.str("name") ?: return@mapNotNull null, p.bool("placeholder") == true)
            },
        )
    }

    fun cli(o: JsonObject): CliLogin? {
        val id = o.str("id") ?: return null
        return CliLogin(
            id = id,
            name = o.str("name") ?: id,
            state = when (o.str("state")) {
                "signed_in" -> LoginState.SignedIn
                "signed_out" -> LoginState.SignedOut
                "missing" -> LoginState.Missing
                else -> LoginState.Unknown
            },
            how = o.str("how"),
            expires = o.long("expires"),
            renewed = o.long("renewed"),
            env = o.str("source") == "env",
            actions = (o["actions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let(LoginAction::of) }.toSet(),
            providers = (o["providers"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                LoginProvider(
                    p.str("id") ?: return@mapNotNull null,
                    when (p.str("source")) {
                        "command" -> KeySource.Command
                        "env" -> KeySource.Env
                        "oauth" -> KeySource.OAuth
                        else -> KeySource.Typed
                    },
                    p.str("method"),
                    p.long("expires"),
                )
            },
            msg = o.str("msg"),
        )
    }

    /** One line of a run, or null for one that isn't an event. */
    fun event(line: String): LoginEvent? {
        val o = line.trim().takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        return when (o.str("event")) {
            "url" -> o.str("url")?.let { LoginEvent.Link(it) }
            "code" -> o.str("code")?.let { LoginEvent.Code(it) }
            "ask" -> LoginEvent.Ask(o.str("text").orEmpty(), o.bool("secret") == true, o.bool("again") == true)
            "line" -> o.str("text")?.let { LoginEvent.Line(it) }
            "done" -> LoginEvent.Done(o.bool("ok") == true, o.str("msg"), (o["status"] as? JsonObject)?.let(::cli))
            else -> null
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
}
