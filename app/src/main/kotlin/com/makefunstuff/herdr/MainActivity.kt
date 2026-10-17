package com.makefunstuff.herdr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HerdrApp).container
        setContent { HerdrRoot(container) }
        if (savedInstanceState == null) open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    /** A tapped notification: its agent's chat, its task on the board, or its machine's health. */
    private fun open(intent: Intent?) {
        val machine = intent?.getStringExtra(OPEN_MACHINE)?.takeIf { it.isNotEmpty() } ?: return
        val pane = intent.getStringExtra(OPEN_PANE).orEmpty()
        val task = intent.getStringExtra(OPEN_TASK).orEmpty()
        when {
            intent.getBooleanExtra(OPEN_HEALTH, false) -> Links.open(Routes.health(machine))
            pane.isNotEmpty() -> Links.open(Routes.agent(machine, pane))
            task.isNotEmpty() -> Links.open(Routes.board(machine, task))
            else -> Links.open(Routes.SETTINGS)
        }
    }

    companion object {
        const val OPEN_MACHINE = "open_machine"
        const val OPEN_PANE = "open_pane"
        const val OPEN_TASK = "open_task"
        const val OPEN_HEALTH = "open_health"
    }
}
