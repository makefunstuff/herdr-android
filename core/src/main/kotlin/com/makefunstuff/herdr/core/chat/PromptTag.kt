package com.makefunstuff.herdr.core.chat

/**
 * What a blocked agent's question would do, in two or three words for a tag on its card: "runs a
 * migration", "pushes", "deletes files". Read from the command or the words the agent shows; a
 * question of its own (Claude's AskUserQuestion) has none.
 */
data class PromptTag(val text: String, val risk: Risk) {
    /** [Danger]: it can't be taken back (red). [Caution]: it changes something outside this checkout (amber). [Plain]: neutral. */
    enum class Risk { Danger, Caution, Plain }

    companion object {
        private val I = setOf(RegexOption.IGNORE_CASE)
        private fun r(p: String) = Regex(p, I)

        /** First match wins, so the most serious reading of a command is the one shown. */
        private val RULES: List<Pair<Regex, PromptTag>> = listOf(
            r("\\bgit\\s+push\\b[^\\n]*(\\s--force\\b|\\s-f\\b|--force-with-lease|\\s\\+\\S)") to PromptTag("force-pushes", Risk.Danger),
            r("\\brm\\s+(-\\S+\\s+)*\\S|\\brmdir\\b|\\bgit\\s+clean\\b|\\bshred\\b|\\bfind\\b[^\\n]*\\s-delete\\b|\\bunlink\\s") to PromptTag("deletes files", Risk.Danger),
            r("\\bdrop\\s+(table|database|schema|index)\\b|\\btruncate\\s+(table\\s+)?\\w|\\bdelete\\s+from\\b|\\bdropdb\\b|\\bflushall\\b") to PromptTag("deletes data", Risk.Danger),
            r("\\bgit\\s+(reset\\s+--hard|checkout\\s+--\\s|checkout\\s+\\.|restore\\s|stash\\s+(drop|clear))") to PromptTag("discards changes", Risk.Danger),
            // -D deletes a branch with commits that aren't merged; -d (only a merged one) is safe, so this one minds the case.
            Regex("\\bgit\\s+branch\\s+(-\\S*D\\b|--delete\\s+--force|-d\\s+-f\\b|-f\\s+-d\\b)") to PromptTag("discards changes", Risk.Danger),
            r("\\bterraform\\s+destroy\\b|\\bkubectl\\s+delete\\b|\\bhelm\\s+uninstall\\b|\\bdocker\\s+(rm|rmi|system\\s+prune|volume\\s+rm)\\b") to PromptTag("tears down", Risk.Danger),
            r("\\bmigrate\\b|db:migrate|\\b(run|runs|apply|applies)\\s+(the\\s+)?(new\\s+|pending\\s+)?migrations?\\b|\\balembic\\s+upgrade\\b|\\bprisma\\s+(migrate|db\\s+push)\\b|\\bflyway\\b|\\bliquibase\\b|\\bdbmate\\b") to PromptTag("runs a migration", Risk.Caution),
            r("\\bgit\\s+push\\b") to PromptTag("pushes", Risk.Caution),
            r("\\bgit\\s+rebase\\b|\\bgit\\s+commit\\s[^\\n]*--amend\\b") to PromptTag("rewrites history", Risk.Caution),
            r("\\bgh\\s+pr\\s+merge\\b|\\bgit\\s+merge\\b") to PromptTag("merges", Risk.Caution),
            r("\\bdeploy\\b|\\bkubectl\\s+(apply|rollout|scale)\\b|\\bterraform\\s+apply\\b|\\bhelm\\s+(install|upgrade)\\b|\\bfly\\s+deploy\\b") to PromptTag("deploys", Risk.Caution),
            r("\\bgh\\s+(pr|issue|release)\\s+(create|comment|close|edit)\\b|\\bgit\\s+tag\\b|\\bnpm\\s+publish\\b|\\bcargo\\s+publish\\b") to PromptTag("publishes", Risk.Caution),
            r("\\bsudo\\b") to PromptTag("runs as root", Risk.Caution),
            r("\\b(npm|pnpm|yarn|bun)\\s+(install|i|add)\\b|\\bpip3?\\s+install\\b|\\buv\\s+(add|pip\\s+install)\\b|\\bapt(-get)?\\s+install\\b|\\bbrew\\s+install\\b|\\bcargo\\s+(add|install)\\b|\\bgo\\s+(get|install)\\b") to PromptTag("installs packages", Risk.Caution),
            r("\\bgit\\s+commit\\b") to PromptTag("commits", Risk.Plain),
            r("\\bcurl\\b|\\bwget\\b|\\bweb\\s?fetch\\b|https?://") to PromptTag("uses the network", Risk.Plain),
        )
        private val EDIT = r("make this edit|make the following edits|create [^?]+\\?|overwrite|\\bwrite to\\b|^(edit|create|write|update) file\\b|apply_patch|\\bapply (this|the) patch")
        private val TRUST = r("\\btrust\\b")
        private val RUN = r("^bash command\\b|would you like to run|^\\$ |\\brun (this|the following) command|\\bexecute\\b")

        fun of(prompt: BlockedPrompt): PromptTag? {
            // Claude's own questions and forms ask for a choice, not for a permission.
            if (prompt.steps.isNotEmpty() || prompt.multiSelect || prompt.customIndex != null) return null
            val lines = listOf(prompt.question) + prompt.details
            val text = lines.joinToString("\n")
            RULES.firstOrNull { it.first.containsMatchIn(text) }?.let { return it.second }
            return when {
                TRUST.containsMatchIn(prompt.question) -> PromptTag("trusts this folder", Risk.Caution)
                // A command's own description may say "Create file b.txt": the command is what runs.
                lines.any { RUN.containsMatchIn(it) } -> PromptTag("runs a command", Risk.Plain)
                lines.any { EDIT.containsMatchIn(it) } -> PromptTag("edits a file", Risk.Plain)
                else -> null
            }
        }
    }
}
