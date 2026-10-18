package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.PasswordPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasswordPromptTest {
    @Test fun asksForSecrets() {
        listOf(
            "[sudo] password for jpl:",
            "Password:",
            "jpl@devbox's password:",
            "Enter passphrase for key '/home/jpl/.ssh/id_ed25519':",
            "Enter PIN for 'PIV Card Holder pin (PIV_II)':",
            "Password for 'https://jpl@github.com':",
            "Enter pass phrase for server.key:",
            "Retype new password:",
            "(current) UNIX password:",
            "Bad passphrase, try again for /home/jpl/.ssh/id_ed25519:",
        ).forEach { assertEquals(it, PasswordPrompt.ask("$it "), it) }
    }

    @Test fun leavesOtherLinesAlone() {
        listOf(
            "",
            "Username for 'https://github.com':",
            "passwd: password updated successfully",
            "Password changed:",
            "password: invalid",
            "Forgot your password?",
            "$ cat ~/.config/passwords.txt",
            "spinning: 3 of 4",
            "Enter the password " + "x".repeat(200) + ":",
        ).forEach { assertNull(PasswordPrompt.ask(it), it) }
    }

    @Test fun readsTheScreensLastLine() {
        assertEquals("[sudo] password for jpl:", PasswordPrompt.find("$ sudo pacman -Syu\n[sudo] password for jpl: \n\n   \n"))
        // Answered: the prompt went on, and the screen's last line is something else.
        assertNull(PasswordPrompt.find("$ sudo pacman -Syu\n[sudo] password for jpl:\n:: Synchronizing package databases...\n"))
        assertNull(PasswordPrompt.find(""))
    }
}
