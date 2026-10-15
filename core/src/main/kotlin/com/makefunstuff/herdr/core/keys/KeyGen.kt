package com.makefunstuff.herdr.core.keys

import com.makefunstuff.herdr.core.ssh.SshIdentity
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.security.SecureRandom
import java.util.Base64

object KeyGen {
    /** Generates a new Ed25519 key in OpenSSH format. */
    fun ed25519(comment: String): SshIdentity {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        val private = pair.private as Ed25519PrivateKeyParameters
        val privateBlob = OpenSSHPrivateKeyUtil.encodePrivateKey(private)
        val body = Base64.getEncoder().encodeToString(privateBlob).chunked(70).joinToString("\n")
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
        val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        val publicLine = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicBlob)} $comment"
        return SshIdentity(pem, publicLine)
    }
}

object KeyImport {
    /** Reads an existing unencrypted private key (OpenSSH or PEM) and derives its `authorized_keys` line. */
    fun fromPrivateKey(pem: String, comment: String): SshIdentity {
        val text = pem.trim() + "\n"
        val provider = net.schmizz.sshj.SSHClient(net.schmizz.sshj.DefaultConfig()).loadKeys(text, null, null)
        val public = try {
            provider.public
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't read that key. Keys with a passphrase aren't supported yet; remove it with ssh-keygen -p.", e)
        }
        val type = net.schmizz.sshj.common.KeyType.fromKey(public).toString()
        val blob = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(public).compactData
        return SshIdentity(text, "$type ${Base64.getEncoder().encodeToString(blob)} $comment")
    }
}
