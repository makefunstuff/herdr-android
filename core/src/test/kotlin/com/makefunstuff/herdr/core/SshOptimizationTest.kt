package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.ssh.SshConnector
import com.makefunstuff.herdr.core.terminal.TerminalStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshOptimizationTest {

    @Test
    fun defaultConfigPrioritizesAeadAndEnablesCompression() {
        val config = SshConnector.defaultConfig()

        // Compression: delayed-zlib (zlib@openssh.com), zlib, none
        val compressionNames = config.compressionFactories.map { it.name }
        assertTrue(compressionNames.contains("zlib@openssh.com"), "Expected delayed-zlib compression: $compressionNames")
        assertTrue(compressionNames.contains("zlib"), "Expected zlib compression: $compressionNames")
        assertEquals("zlib@openssh.com", compressionNames.first())

        // Ciphers: AEAD first (ChaCha20, AES-GCM), followed by CTR, and NO CBC modes
        val cipherNames = config.cipherFactories.map { it.name }
        assertEquals("chacha20-poly1305@openssh.com", cipherNames[0])
        assertEquals("aes128-gcm@openssh.com", cipherNames[1])
        assertEquals("aes256-gcm@openssh.com", cipherNames[2])
        assertTrue(cipherNames.contains("aes128-ctr"))
        assertTrue(cipherNames.contains("aes256-ctr"))
        assertFalse(cipherNames.any { it.contains("cbc") }, "CBC ciphers should be excluded: $cipherNames")

        // MACs: Encrypt-Then-MAC (ETM) prioritized
        val macNames = config.macFactories.map { it.name }
        assertTrue(macNames.first().contains("-etm@openssh.com"), "Expected ETM MAC first: $macNames")
        assertFalse(macNames.any { it.contains("md5") }, "Insecure MD5 MACs should be excluded: $macNames")
    }

    @Test
    fun terminalClosedDistinguishesRemoteExitFromNetworkDrop() {
        val normalExit = TerminalStream.Status.Closed("Process finished", isRemoteExit = true)
        assertTrue(normalExit.isRemoteExit)

        val networkDrop = TerminalStream.Status.Closed("Connection reset by peer", isRemoteExit = false)
        assertFalse(networkDrop.isRemoteExit)
    }
}
