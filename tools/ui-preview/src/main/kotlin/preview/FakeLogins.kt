package preview

import com.makefunstuff.herdr.core.logins.HerdrLogins
import com.makefunstuff.herdr.core.ssh.CommandResult

/**
 * A machine's agent CLIs as herdr-logins reads them there (`status`), and what a sign-in says as it
 * runs (`run`): the shapes the script prints, as host/herdr-logins prints them, at the
 * demo's time. No CLI is run and nothing is signed in or out.
 */
class FakeLogins(
    /** When Claude Code's login runs out, days from the demo's time; negative: it ran out. */
    var claudeDays: Int = 13,
    /** Gemini CLI installed with no login. */
    var gemini: Boolean = true,
    /** Keys exported from ~/.bashrc: the warning's. */
    var profile: Boolean = true,
    /** Set to make reading fail as the script says it. */
    var refuse: String? = null,
) {
    private val now = Demo.NOW / 1000

    fun run(command: String): CommandResult? {
        if (!command.contains(HerdrLogins.FILE.substringAfterLast('/'))) return null
        refuse?.let { return CommandResult(1, """{"ok": false, "version": "${HerdrLogins.VERSION}", "msg": "$it"}""" + "\n", "") }
        return CommandResult(0, status() + "\n", "")
    }

    /** The events of `run <cli> <action>`, in order, up to where the person has something to do. */
    fun events(command: String): List<String>? {
        if (!command.contains(HerdrLogins.FILE.substringAfterLast('/'))) return null
        val w = Shell.words(command)
        val at = w.indexOf("run")
        if (at < 0) return null
        return when (w.getOrNull(at + 1) to w.getOrNull(at + 2)) {
            "codex" to "login" -> listOf(
                """{"event": "line", "text": "Follow these steps to sign in with ChatGPT using device code authorization:"}""",
                """{"event": "url", "url": "https://auth.openai.com/codex/device"}""",
                """{"event": "code", "code": "QK7F-29XD"}""",
            )
            "claude" to "login" -> listOf(
                """{"event": "line", "text": "Opening browser to sign in…"}""",
                """{"event": "url", "url": "https://claude.ai/oauth/authorize?code=true&client_id=9d1c250a&response_type=code&state=Xq2"}""",
                """{"event": "ask", "text": "Paste code here if prompted >", "secret": true}""",
            )
            else -> emptyList()
        }
    }

    private fun status(): String {
        val clis = listOfNotNull(
            """{"id": "claude", "name": "Claude Code", "installed": true, "state": "signed_in", "how": "Claude subscription (Max)",
               "expires": ${now + claudeDays * 86_400L}, "actions": ["login", "logout"]}""",
            """{"id": "codex", "name": "Codex", "installed": true, "state": "signed_in", "how": "ChatGPT login", "renewed": ${now - 8 * 86_400L},
               "actions": ["login", "key", "logout"]}""",
            """{"id": "pi", "name": "pi", "installed": true, "state": "signed_in", "providers": [
                 {"id": "anthropic", "source": "typed"}, {"id": "deepseek", "source": "command"}, {"id": "groq", "source": "command"},
                 {"id": "llama.cpp", "source": "typed"}, {"id": "mistral", "source": "command"}, {"id": "ollama", "source": "typed"},
                 {"id": "openrouter", "source": "command"}, {"id": "together", "source": "command"}], "actions": ["key", "logout"]}""",
            """{"id": "opencode", "name": "opencode", "installed": true, "state": "signed_in", "providers": [
                 {"id": "openai", "name": "OpenAI", "source": "oauth", "method": "OAuth", "credential": "cred_demo_openai"},
                 {"id": "llama.cpp", "name": "llama.cpp", "source": "typed", "method": "API key", "credential": "cred_demo_llama"}],
               "how": null, "actions": ["logout"]}""",
            if (gemini) """{"id": "gemini", "name": "Gemini CLI", "installed": true, "state": "signed_out", "how": "Installed, no login", "actions": []}""" else null,
        )
        val keys = if (!profile) "" else """{"file": "~/.bashrc", "line": 12, "name": "OPENAI_API_KEY", "placeholder": true},
            {"file": "~/.bashrc", "line": 27, "name": "HF_TOKEN", "placeholder": false}"""
        return """{"ok": true, "version": "${HerdrLogins.VERSION}", "at": $now, "clis": [${clis.joinToString(", ")}], "profile": [$keys]}"""
            .replace("\n", " ")
    }
}
