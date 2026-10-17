package com.makefunstuff.herdr.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** What is left in the box once a message is sent. */
class DraftStoreTest {
    @Test fun sentDraftIsCleared() {
        assertEquals("", DraftStore.afterSend("Fix the build", "Fix the build"))
    }

    @Test fun typedOnWhileSendingIsKept() {
        assertEquals("and the tests", DraftStore.afterSend("Fix the build\nand the tests", "Fix the build"))
    }

    @Test fun editedWhileSendingIsKeptWhole() {
        assertEquals("Fix the CI build", DraftStore.afterSend("Fix the CI build", "Fix the build"))
    }

    @Test fun keysAreOnePerChat() {
        assertEquals("agent/m1/w1:p1", DraftStore.agent("m1", "w1:p1"))
        assertEquals("coordinator/m1", DraftStore.coordinator("m1"))
    }
}
