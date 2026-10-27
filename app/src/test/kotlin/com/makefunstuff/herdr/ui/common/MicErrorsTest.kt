package com.makefunstuff.herdr.ui.common

import android.speech.SpeechRecognizer
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.Dictation.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Core's copy of Android's recognizer errors (core has no Android) against Android's own, and what each says. */
class MicErrorsTest {
    @Test fun theCodesAreAndroids() {
        val e = Dictation.Errors
        assertEquals(SpeechRecognizer.ERROR_NETWORK_TIMEOUT, e.NETWORK_TIMEOUT)
        assertEquals(SpeechRecognizer.ERROR_NETWORK, e.NETWORK)
        assertEquals(SpeechRecognizer.ERROR_AUDIO, e.AUDIO)
        assertEquals(SpeechRecognizer.ERROR_SERVER, e.SERVER)
        assertEquals(SpeechRecognizer.ERROR_CLIENT, e.CLIENT)
        assertEquals(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, e.SPEECH_TIMEOUT)
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, e.NO_MATCH)
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, e.RECOGNIZER_BUSY)
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, e.INSUFFICIENT_PERMISSIONS)
        assertEquals(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, e.TOO_MANY_REQUESTS)
        assertEquals(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, e.SERVER_DISCONNECTED)
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, e.LANGUAGE_NOT_SUPPORTED)
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, e.LANGUAGE_UNAVAILABLE)
    }

    @Test fun eachErrorSaysWhatToDo() {
        assertEquals(Problem.NoSpeech, Dictation.problem(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(Problem.NoSpeech, Dictation.problem(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(Problem.Network, Dictation.problem(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(Problem.Network, Dictation.problem(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(Problem.Busy, Dictation.problem(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(Problem.NoPermission, Dictation.problem(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(Problem.Language, Dictation.problem(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(Problem.Other, Dictation.problem(SpeechRecognizer.ERROR_CLIENT))
    }
}
