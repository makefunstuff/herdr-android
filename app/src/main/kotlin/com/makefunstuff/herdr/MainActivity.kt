package com.makefunstuff.herdr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.makefunstuff.herdr.ui.share.Ask

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HerdrApp).container
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

    /** A tapped notification: its agent's chat, or its task on the board. The tile, a widget or a share: [Ask]. False when it opens nothing. */
    private fun open(intent: Intent?): Boolean {
        intent?.getStringExtra(OPEN_ASK)?.takeIf { it in ASKS }?.let { Links.open(Routes.ask(it)); return true }
        val machine = intent?.getStringExtra(OPEN_MACHINE)?.takeIf { it.isNotEmpty() } ?: return false
        val pane = intent.getStringExtra(OPEN_PANE).orEmpty()
        val task = intent.getStringExtra(OPEN_TASK).orEmpty()
        when {
            pane.isNotEmpty() -> Links.open(Routes.agent(machine, pane))
            task.isNotEmpty() -> Links.open(Routes.board(machine, task))
            else -> Links.open(Routes.SETTINGS)
        }
        return true
    }

    companion object {
        const val OPEN_MACHINE = "open_machine"
        const val OPEN_PANE = "open_pane"
        const val OPEN_TASK = "open_task"
        const val OPEN_ASK = "open_ask"
        private val ASKS = setOf(Ask.COORDINATOR, Ask.BOARD, Ask.SHARE)

        /** Opens the app on [to] (an [Ask]), in its own task, from the tile, a widget or a share. */
        fun ask(context: Context, to: String): Intent = Intent(context, MainActivity::class.java)
            .setAction("com.makefunstuff.herdr.ASK.$to")
            .putExtra(OPEN_ASK, to)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
