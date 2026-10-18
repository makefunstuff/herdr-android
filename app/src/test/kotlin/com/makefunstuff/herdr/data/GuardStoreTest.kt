package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.guard.Guarded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GuardStoreTest {
    private class FakeUnlock(var lock: Boolean = true, var answer: Boolean = true) : Unlock {
        var asked = 0
        var gate: CompletableDeferred<Unit>? = null
        override fun canAsk() = lock
        override suspend fun ask(title: String, subtitle: String?): Boolean {
            asked++
            gate?.await()
            return answer
        }
        override fun openSettings() {}
    }

    private fun file() = Files.createTempDirectory("guard").toFile().resolve("guard.json")

    @Test fun everyActionAsksUntilTurnedOffAndThatIsKept() {
        val f = file()
        val store = GuardStore(f, FakeUnlock())
        assertTrue(Guarded.entries.all { store.wants(it) })
        store.change { it.copy(off = setOf(Guarded.StopProcess.key)) }
        val again = GuardStore(f, FakeUnlock())
        assertFalse(again.wants(Guarded.StopProcess))
        assertTrue(again.wants(Guarded.Merge))
    }

    @Test fun theAppLockLocksAtStartAndAfterTimeAway() = runBlocking {
        val f = file()
        GuardStore(f, FakeUnlock()).change { it.copy(appLock = true, lockAfter = 1) }
        val unlock = FakeUnlock()
        val store = GuardStore(f, unlock)
        assertTrue("locked as the app starts", store.locked.value)
        assertTrue(store.open())
        assertFalse(store.locked.value)
        store.stopped(now = 1_000)
        store.started(now = 1_000 + 30_000)
        assertFalse("half a minute away is not enough", store.locked.value)
        store.stopped(now = 100_000)
        store.started(now = 100_000 + 60_000)
        assertTrue(store.locked.value)
        unlock.answer = false
        assertFalse("a cancelled prompt keeps it locked", store.open())
        assertTrue(store.locked.value)
    }

    @Test fun thePromptItselfIsNoLeaving() = runBlocking {
        val f = file()
        GuardStore(f, FakeUnlock()).change { it.copy(appLock = true, lockAfter = 0) }
        val unlock = FakeUnlock().apply { gate = CompletableDeferred() }
        val store = GuardStore(f, unlock)
        val opening = async { store.open() }
        while (unlock.asked == 0) yield()
        // Android's PIN screen takes the app off the screen while the prompt is up.
        store.stopped(now = 5_000)
        store.started(now = 9_000)
        unlock.gate!!.complete(Unit)
        assertTrue(opening.await())
        assertFalse(store.locked.value)
    }

    @Test fun withNoScreenLockThereIsNoLock() {
        val f = file()
        GuardStore(f, FakeUnlock()).change { it.copy(appLock = true) }
        val store = GuardStore(f, FakeUnlock(lock = false))
        assertFalse(store.locked.value)
        store.stopped(now = 0)
        store.started(now = 3_600_000)
        assertFalse(store.locked.value)
    }

    @Test fun turningTheLockOffOpensTheApp() {
        val f = file()
        GuardStore(f, FakeUnlock()).change { it.copy(appLock = true) }
        val store = GuardStore(f, FakeUnlock())
        assertTrue(store.locked.value)
        store.setAppLock(false)
        assertFalse(store.locked.value)
        assertEquals(false, store.state.value.appLock)
    }
}
