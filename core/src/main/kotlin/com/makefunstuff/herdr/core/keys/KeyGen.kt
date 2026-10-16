package com.makefunstuff.herdr.core.keys

import com.makefunstuff.herdr.core.ssh.SshIdentity
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.math.BigInteger
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.util.Base64

object KeyGen {
    /** Generates a new Ed25519 key in OpenSSH format. */
    fun ed25519(comment: String): SshIdentity {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val private = generator.generateKeyPair().private as Ed25519PrivateKeyParameters
        val key = ed25519Key(private).withComment(comment)
        return SshIdentity(OpenSshKeyFile.write(key), key.publicLine(comment))
    }

    internal fun ed25519Key(private: Ed25519PrivateKeyParameters): OpenSshKeyFile.Key {
        val blob = Base64.getEncoder().encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(private))
        return OpenSshKeyFile.read("${OpenSshKeyFile.BEGIN}\n$blob\n${OpenSshKeyFile.END}\n", null)
    }
}

object KeyImport {
    /**
     * Reads an existing private key, with or without a passphrase: OpenSSH format (what ssh-keygen
     * writes, and the app's own backups) or PEM/PKCS#8. The result is stored unencrypted in OpenSSH format.
     * The key's own comment is kept; [comment] is used when it has none.
     *
     * @throws PassphraseException when the key is protected and [passphrase] is missing or wrong.
     */
    fun fromPrivateKey(text: String, comment: String, passphrase: String? = null): SshIdentity {
        val read = readKey(text, passphrase)
        val key = read.withComment(read.comment.ifBlank { comment })
        val identity = SshIdentity(OpenSshKeyFile.write(key), key.publicLine(key.comment))
        // Make sure the SSH library signs in with exactly the key that was read.
        val loaded = SSHClient(DefaultConfig()).loadKeys(identity.privateKeyPem, null, null)
        check(Buffer.PlainBuffer().putPublicKey(loaded.public).compactData.contentEquals(key.publicBlob)) { "Key check failed." }
        loaded.private
        return identity
    }

    internal fun readKey(text: String, passphrase: String?): OpenSshKeyFile.Key {
        val clean = text.replace("\r\n", "\n").trim() + "\n"
        return if (OpenSshKeyFile.isOpenSsh(clean)) OpenSshKeyFile.read(clean, passphrase) else fromPem(clean, passphrase)
    }

    private fun fromPem(text: String, passphrase: String?): OpenSshKeyFile.Key {
        val finder = passphrase?.takeIf { it.isNotEmpty() }?.let { p ->
            object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?) = p.toCharArray()
                override fun shouldRetry(resource: Resource<*>?) = false
            }
        }
        val protected = text.contains("ENCRYPTED")
        if (protected && finder == null) throw PassphraseException("This key is protected. Enter its passphrase.")
        val (private, public) = try {
            val provider = SSHClient(DefaultConfig()).loadKeys(text, null, finder)
            provider.private to provider.public
        } catch (e: Exception) {
            if (protected) throw PassphraseException("Wrong passphrase.")
            throw IllegalArgumentException("Couldn't read that key.", e)
        }
        val blob = Buffer.PlainBuffer().putPublicKey(public).compactData
        val pub = Buffer.PlainBuffer(blob)
        val type = pub.readStringAsBytes()
        val noComment = ByteArray(0)
        return when (private) {
            is RSAPrivateCrtKey -> OpenSshKeyFile.Key(
                blob,
                listOf(type) + listOf(
                    private.modulus, private.publicExponent, private.privateExponent,
                    private.crtCoefficient, private.primeP, private.primeQ,
                ).map(BigInteger::toByteArray) + noComment,
            )
            is ECPrivateKey -> {
                val curve = pub.readStringAsBytes()
                val point = pub.readStringAsBytes()
                OpenSshKeyFile.Key(blob, listOf(type, curve, point, private.s.toByteArray(), noComment))
            }
            else -> {
                val params = runCatching { PrivateKeyFactory.createKey(private.encoded) }.getOrNull()
                if (params !is Ed25519PrivateKeyParameters) throw IllegalArgumentException("Unsupported key type ${private.algorithm}.")
                KeyGen.ed25519Key(params)
            }
        }
    }
}

object KeyExport {
    /**
     * The key in OpenSSH format, protected with [passphrase]: a backup the app can import again,
     * and a normal key file for ssh and ssh-keygen.
     */
    fun encrypted(identity: SshIdentity, passphrase: String): String {
        require(passphrase.isNotEmpty()) { "Choose a passphrase." }
        val comment = identity.publicKeyLine.split(' ').drop(2).joinToString(" ")
        val key = KeyImport.readKey(identity.privateKeyPem, null)
        return OpenSshKeyFile.write(if (comment.isBlank()) key else key.withComment(comment), passphrase)
    }
}
