package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.host.findApiBridge
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.ConnectError
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrBinary
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiBridgeTest {
    private val machine = Machine("m", "box", "h", user = "u", session = "named-review")
    private val old = HerdrBinary("/usr/bin/herdr", "0.8.2", "server")
    private val matching = HerdrBinary("/opt/custom/herdr", "0.9.5", "server")
    private val found = listOf(old, matching)
    private val supported = CommandResult(0, "herdr-api-bridge-v1\n", "")

    private class Reply(text: String) : Duplex {
        override val input = ByteArrayInputStream(text.toByteArray())
        override val output = ByteArrayOutputStream()
        var closed = false
        override fun close() { closed = true }
    }

    @Test
    fun triesMatchingBinaryWhenAnUnrelatedOldServerHasNoBridge() = runBlocking {
        val checked = mutableListOf<String>()
        val reply = Reply("""{"result":{"version":"0.9.5","protocol":1}}""" + "\n")
        val chosen = findApiBridge(machine, found, { command ->
            checked += command
            if (command == HerdrCommands.apiBridgeCheck(machine.copy(herdrPath = old.path))) {
                CommandResult(2, "", "unknown command remote-api-bridge")
            } else supported
        }, { command ->
            assertEquals(HerdrCommands.apiBridge(machine.copy(herdrPath = matching.path)), command)
            reply
        })
        assertEquals(found.map { HerdrCommands.apiBridgeCheck(machine.copy(herdrPath = it.path)) }, checked)
        assertEquals(matching.path, chosen.herdrPath)
        assertEquals(machine.session, chosen.session)
        assertTrue(reply.closed)
    }

    @Test
    fun checksCommunicationWithTheRequestedServerBeforeChoosingABridge() = runBlocking {
        val replies = mutableListOf<Reply>()
        val chosen = findApiBridge(machine, found, { supported }, { command ->
            Reply(if (command == HerdrCommands.apiBridge(machine.copy(herdrPath = old.path))) ""
            else """{"result":{"version":"0.9.5"}}""" + "\n").also { replies += it }
        })
        assertEquals(matching.path, chosen.herdrPath)
        assertEquals(2, replies.size)
        assertTrue(replies.all { it.closed })
    }

    @Test
    fun explicitPathRemainsAnOverrideAndFailureNamesIt() = runBlocking {
        val pinned = machine.copy(herdrPath = old.path)
        val checked = mutableListOf<String>()
        val error = assertFailsWith<ConnectError.HerdrMissing> {
            findApiBridge(pinned, found, { command ->
                checked += command
                CommandResult(2, "", "old client")
            }, { error("unsupported bridge must not start") })
        }
        assertEquals(listOf(HerdrCommands.apiBridgeCheck(pinned)), checked)
        assertTrue("/usr/bin/herdr (0.8.2)" in error.message!!)
        assertTrue("named-review" in error.message!!)
    }

    @Test
    fun allFailuresNameEveryBinaryAndVersion() = runBlocking {
        val error = assertFailsWith<ConnectError.HerdrMissing> {
            findApiBridge(machine, found, { CommandResult(2, "", "bridge failed") }, { error("must not start") })
        }
        found.forEach { assertTrue("${it.path} (${it.version})" in error.message!!, error.message) }
    }

    @Test
    fun cancellationDoesNotTryMoreBinaries() = runBlocking {
        var attempts = 0
        assertFailsWith<CancellationException> {
            findApiBridge(machine, found, { attempts++; throw CancellationException() }, { error("must not start") })
        }
        assertEquals(1, attempts)
    }
}
