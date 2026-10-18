package com.makefunstuff.herdr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.makefunstuff.herdr.ui.share.Ask

/** The app. A FragmentActivity, as the fingerprint prompt (androidx.biometric) shows over one. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HerdrApp).container
        // With the app lock on, the recent apps show no picture of the screens either.
        if (Build.VERSION.SDK_INT >= 33) lifecycleScope.launch { container.guard.state.collect { setRecentsScreenshotEnabled(!it.appLock) } }
        setContent { HerdrRoot(container) }
        if (savedInstanceState == null && !open(intent) && container.shares.pending.value != null) {
            // Something shared was waiting for a machine when Android ended the app: ask again.
            Links.open(Routes.ask(Ask.SHARE))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    /**
     * A tapped notification: its agent's chat, its task on the board (or its review card), its machine's health, a quick command's run, or the brief. The tile,
     * a widget or a share: [Ask]. A floating chat's "Open in the app" or its shortcut: that chat. False
     * when it opens nothing.
     */
    private fun open(intent: Intent?): Boolean {
        intent?.getStringExtra(OPEN_ASK)?.takeIf { it in ASKS }?.let { Links.open(Routes.ask(it)); return true }
        if (intent?.getBooleanExtra(OPEN_BRIEF, false) == true) { Links.open(Routes.BRIEF); return true }
        val machine = intent?.getStringExtra(OPEN_MACHINE)?.takeIf { it.isNotEmpty() } ?: return false
        val pane = intent.getStringExtra(OPEN_PANE).orEmpty()
        val task = intent.getStringExtra(OPEN_TASK).orEmpty()
        val quick = intent.getStringExtra(OPEN_QUICK).orEmpty()
        when {
            intent.getBooleanExtra(OPEN_HEALTH, false) -> Links.open(Routes.health(machine))
            quick.isNotEmpty() -> Links.open(Routes.quickRuns(machine, quick))
            intent.getBooleanExtra(OPEN_COORDINATOR, false) -> Links.open(Routes.coordinator(machine))
            pane.isNotEmpty() -> Links.open(Routes.agent(machine, pane))
            // Finished work that waits for a yes: its review card, with the others after it.
            task.isNotEmpty() && intent.getBooleanExtra(OPEN_REVIEW, false) -> Links.open(Routes.review(machine, task))
            task.isNotEmpty() -> Links.open(Routes.board(machine, task))
            else -> Links.open(Routes.SETTINGS)
        }
        return true
    }

    companion object {
        const val OPEN_MACHINE = "open_machine"
        const val OPEN_PANE = "open_pane"
        const val OPEN_TASK = "open_task"
        const val OPEN_REVIEW = "open_review"
        const val OPEN_HEALTH = "open_health"
        const val OPEN_QUICK = "open_quick"
        const val OPEN_COORDINATOR = "open_coordinator"
        const val OPEN_ASK = "open_ask"
        const val OPEN_BRIEF = "open_brief"
        private val ASKS = setOf(Ask.COORDINATOR, Ask.BOARD, Ask.SHARE)

        /** Opens the app on [to] (an [Ask]), in its own task, from the tile, a widget or a share. */
        fun ask(context: Context, to: String): Intent = Intent(context, MainActivity::class.java)
            .setAction("com.makefunstuff.herdr.ASK.$to")
            .putExtra(OPEN_ASK, to)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
