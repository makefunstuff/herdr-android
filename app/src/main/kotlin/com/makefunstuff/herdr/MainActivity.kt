package com.makefunstuff.herdr

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.agents.AgentScreen
import com.makefunstuff.herdr.ui.agents.AgentsScreen
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.coordinator.CoordinatorScreen
import com.makefunstuff.herdr.ui.machines.EditMachineScreen
import com.makefunstuff.herdr.ui.machines.MachineScreen
import com.makefunstuff.herdr.ui.machines.MachinesScreen
import com.makefunstuff.herdr.ui.settings.KeySetupDialog
import com.makefunstuff.herdr.ui.settings.SettingsScreen
import com.makefunstuff.herdr.ui.terminal.TerminalScreen
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import com.makefunstuff.herdr.ui.worktrees.WorktreesScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HerdrApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                HerdrTheme { AppNav() }
            }
        }
    }
}

object Routes {
    const val AGENTS = "agents"
    const val MACHINES = "machines"
    const val SETTINGS = "settings"
    const val MACHINE = "machine/{id}"
    const val EDIT = "edit?id={id}"
    const val AGENT = "agent/{mid}/{pane}"
    const val TERMINAL = "terminal/{mid}/{pane}"
    const val COORDINATOR = "coordinator/{mid}"
    const val WORKTREES = "worktrees/{mid}?ws={ws}"

    fun machine(id: String) = "machine/$id"
    fun edit(id: String? = null) = if (id == null) "edit" else "edit?id=$id"
    fun agent(mid: String, pane: String) = "agent/$mid/${Uri.encode(pane)}"
    fun terminal(mid: String, pane: String) = "terminal/$mid/${Uri.encode(pane)}"
    fun coordinator(mid: String) = "coordinator/$mid"
    fun worktrees(mid: String, workspaceId: String? = null) =
        if (workspaceId == null) "worktrees/$mid" else "worktrees/$mid?ws=${Uri.encode(workspaceId)}"
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = route in setOf(Routes.AGENTS, Routes.MACHINES, Routes.SETTINGS)
    val agents by LocalContainer.current.hosts.agents.collectAsStateWithLifecycle()
    val blocked = agents.count { it.agent.agentStatus == AgentStatus.Blocked }
    val keyState by LocalContainer.current.identity.state.collectAsStateWithLifecycle()
    var keySetupDismissed by rememberSaveable { mutableStateOf(false) }

    // No usable key yet: offer to restore one before a new key is made.
    if (!keySetupDismissed && (keyState == IdentityStore.State.Missing || keyState is IdentityStore.State.Unreadable)) {
        KeySetupDialog(
            unreadable = (keyState as? IdentityStore.State.Unreadable)?.reason,
            onDismiss = { keySetupDismissed = true },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (topLevel) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.AGENTS,
                        onClick = { nav.goTop(Routes.AGENTS) },
                        icon = {
                            BadgedBox(badge = { if (blocked > 0) Badge { Text("$blocked") } }) {
                                Icon(Icons.Filled.Home, contentDescription = null)
                            }
                        },
                        label = { Text("Agents") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.MACHINES,
                        onClick = { nav.goTop(Routes.MACHINES) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("Machines") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.SETTINGS,
                        onClick = { nav.goTop(Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.AGENTS, modifier = Modifier.padding(bottom = if (topLevel) padding.calculateBottomPadding() else 0.dp)) {
            composable(Routes.AGENTS) { AgentsScreen(nav) }
            composable(Routes.MACHINES) { MachinesScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.MACHINE) { MachineScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable(Routes.EDIT) { EditMachineScreen(nav, it.arguments?.getString("id")) }
            composable(Routes.AGENT) {
                AgentScreen(nav, it.arguments?.getString("mid").orEmpty(), Uri.decode(it.arguments?.getString("pane").orEmpty()))
            }
            composable(Routes.WORKTREES) {
                WorktreesScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("ws")?.let(Uri::decode))
            }
            composable(Routes.COORDINATOR) { CoordinatorScreen(nav, it.arguments?.getString("mid").orEmpty()) }
            composable(Routes.TERMINAL) {
                TerminalScreen(nav, it.arguments?.getString("mid").orEmpty(), Uri.decode(it.arguments?.getString("pane").orEmpty()))
            }
        }
    }
}

private fun NavHostController.goTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
