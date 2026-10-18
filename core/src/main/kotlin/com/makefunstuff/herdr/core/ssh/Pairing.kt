package com.makefunstuff.herdr.core.ssh

import com.makefunstuff.herdr.core.keys.KeyGen
import com.makefunstuff.herdr.core.keys.OpenSshKeyFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * What a pairing code says: the QR code `herdr-push pair` shows on a machine (host/herdr-push).
 *
 * `herdr://pair?v=1&n=devbox&u=demo&a=devbox.tail1234.ts.net,100.84.3.12&p=22&k=<fp>,<fp>&c=<code>&x=<expires>`:
 * the machine's name, its user, where it may be reached (best first), its SSH port, its host keys'
 * SHA256 fingerprints, the one-time code (the 32-byte seed of an Ed25519 key that may log in there
 * once, for five minutes, only to add a key) and when it expires on the machine's clock; `s` names
 * a herdr session other than `default`. Fingerprints and code are URL-safe base64 without padding.
 */
class PairOffer(
    val name: String,
    val user: String,
    val addresses: List<String>,
    val port: Int,
    /** `SHA256:…`, as [SshConnector.fingerprint] gives them. */
    val hostKeys: List<String>,
    private val code: ByteArray,
    /** Unix seconds, on the machine's clock. */
    val expires: Long,
    val session: String = "default",
) {
    /** The one-time key the code stands for. */
    fun oneTimeKey(): SshIdentity {
        val key = KeyGen.ed25519Key(Ed25519PrivateKeyParameters(code, 0))
        return SshIdentity(OpenSshKeyFile.write(key), key.publicLine("herdr-pair"))
    }

    /** `demo@devbox.tail1234.ts.net`, or with `:port`, at the first address. */
    fun target(address: String = addresses.first()): String =
        if (port == 22) "$user@$address" else "$user@$address:$port"

    companion object {
        const val SCHEME = "herdr://pair"

        /** Reads a scanned or pasted pairing link; null when it isn't one (or is from a newer herdr-push). */
        fun parse(text: String): PairOffer? {
            val raw = text.trim()
            if (!raw.startsWith("$SCHEME?")) return null
            val query = runCatching { URI(raw).rawQuery }.getOrNull() ?: return null
            val fields = query.split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }.toMap()
            if (fields["v"] != "1") return null
            val user = fields["u"]?.takeIf { it.isNotBlank() } ?: return null
            val addresses = fields["a"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (addresses.isEmpty()) return null
            val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
            val keys = fields["k"].orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { fp ->
                val bytes = decode(fp) ?: return@mapNotNull null
                if (bytes.size != 32) null else "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(bytes)
            }
            if (keys.isEmpty()) return null
            val code = fields["c"]?.let(::decode)?.takeIf { it.size == 32 } ?: return null
            val expires = fields["x"]?.toLongOrNull() ?: return null
            val name = fields["n"]?.takeIf { it.isNotBlank() } ?: addresses.first().substringBefore('.')
            return PairOffer(name, user, addresses, port, keys, code, expires, fields["s"]?.takeIf { it.isNotBlank() } ?: "default")
        }

        private fun decode(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text.trimEnd('=')) }.getOrNull()
    }
}

/** How an address reaches a machine, to say so ("Reached via Tailscale · 100.84.3.12"). */
enum class AddressKind(val label: String) {
    Tailscale("Tailscale"),
    Local("local network"),
    Other("");

    companion object {
        fun of(address: String): AddressKind {
            val a = address.lowercase()
            if (a.endsWith(".ts.net")) return Tailscale
            if (a.endsWith(".local") || a.endsWith(".lan") || a.endsWith(".home.arpa")) return Local
            val ip = a.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
            if (ip.size != 4 || a.count { it == '.' } != 3) return Other
            return when {
                ip[0] == 100 && ip[1] in 64..127 -> Tailscale
                ip[0] == 10 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] in 16..31 -> Local
                else -> Other
            }
        }
    }
}

/** Something that stopped a pairing, said for the person holding the phone. */
class PairError(message: String, cause: Throwable? = null) : Exception(message, cause)

object Pairing {
    /** Where the phone reached the machine, and the host key it showed there (one of the code's). */
    data class Reached(val address: String, val hostKey: String) {
        val kind: AddressKind get() = AddressKind.of(address)
    }

    /**
     * Tries every address in the code at once and takes the first, in the code's order, where the
     * machine answers with one of the code's host keys. Fails when none does, saying whether an
     * address answered with another key (not that machine, or its keys changed) or none answered.
     */
    suspend fun reach(offer: PairOffer, timeoutMs: Int = 6_000): Reached {
        val seen = coroutineScope {
            offer.addresses.map { address ->
                async { address to runCatching { SshConnector.hostKey(address, offer.port, timeoutMs) } }
            }.awaitAll()
        }
        seen.firstOrNull { (_, key) -> key.getOrNull() in offer.hostKeys }?.let { (address, key) ->
            return Reached(address, key.getOrThrow())
        }
        val other = seen.firstOrNull { (_, key) -> key.isSuccess }
        if (other != null) {
            throw PairError(
                "${other.first} answered with another host key than the code's (${other.second.getOrThrow()}). " +
                    "Something else answers there, or ${offer.name}'s keys changed: run herdr-push pair there again.",
            )
        }
        val where = offer.addresses.joinToString(", ")
        throw PairError("Couldn't reach ${offer.name} at $where. Is the phone on the same network, or on Tailscale?")
    }

    /**
     * Logs in at [at] with the code's one-time key, hands the machine this phone's public key
     * ([identity]), then logs in with the phone's own key to check it took. Gives the machine to
     * save, with its host key pinned.
     */
    suspend fun pair(offer: PairOffer, at: Reached, identity: SshIdentity, id: String): Machine {
        val machine = Machine(
            id = id,
            label = offer.name,
            host = at.address,
            port = offer.port,
            user = offer.user,
            session = offer.session,
            hostKey = at.hostKey,
        )
        val answer = try {
            SshConnector.connect(machine, offer.oneTimeKey()).use { conn ->
                conn.runWithInput("pair", (identity.publicKeyLine.trim() + "\n").byteInputStream(), timeoutSeconds = 30)
            }
        } catch (e: ConnectError.Auth) {
            throw PairError("${offer.name} didn't take the code: it was used already, it expired, or herdr-push pair was stopped. Run it again.", e)
        } catch (e: ConnectError) {
            throw PairError(e.message ?: "Couldn't connect to ${offer.name}.", e)
        }
        val reply = answer.stdout.lines().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (reply?.get("ok")?.jsonPrimitive?.booleanOrNull != true) {
            val why = reply?.get("error")?.jsonPrimitive?.contentOrNull
                ?: answer.stderr.trim().lines().lastOrNull()?.takeIf { it.isNotBlank() }
                ?: "it answered nothing"
            throw PairError("${offer.name} didn't add this phone's key: $why")
        }
        try {
            SshConnector.connect(machine, identity).use { it.run("true") }
        } catch (e: ConnectError.Auth) {
            throw PairError(
                "${offer.name} added this phone's key, but still refuses it. Its sshd may read keys from somewhere else " +
                    "than ~/.ssh/authorized_keys (AuthorizedKeysFile in /etc/ssh/sshd_config).",
                e,
            )
        } catch (e: ConnectError) {
            throw PairError(e.message ?: "Couldn't connect to ${offer.name}.", e)
        }
        return machine
    }
}
