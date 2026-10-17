package com.makefunstuff.herdr

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import com.makefunstuff.herdr.ui.admin.AdminScreen
import com.makefunstuff.herdr.ui.agents.AgentScreen
import com.makefunstuff.herdr.ui.agents.AgentsScreen
import com.makefunstuff.herdr.ui.board.BoardScreen
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.coordinator.CoordinatorScreen
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrNavBar
import com.makefunstuff.herdr.ui.design.NavItem
import com.makefunstuff.herdr.ui.layout.LayoutScreen
import com.makefunstuff.herdr.ui.machines.EditMachineScreen
import com.makefunstuff.herdr.ui.machines.MachineScreen
import com.makefunstuff.herdr.ui.machines.MachinesScreen
import com.makefunstuff.herdr.ui.settings.KeySetupDialog
import com.makefunstuff.herdr.ui.settings.SettingsScreen
import com.makefunstuff.herdr.ui.terminal.TerminalScreen
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.HerdrTheme
import com.makefunstuff.herdr.ui.worktrees.WorktreesScreen

/** The whole app: theme, container, navigation and toasts. [nav] and [dark] are given by the desktop preview's scenes. */
@Composable
fun HerdrRoot(container: AppContainer, dark: Boolean = isSystemInDarkTheme(), nav: NavHostController = rememberNavController()) {
    CompositionLocalProvider(LocalContainer provides container) {
        HerdrTheme(dark) {
            Box {
                AppNav(nav)
                com.makefunstuff.herdr.ui.design.ToastHost()
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
    const val LAYOUT = "layout/{mid}/{pane}"
    const val COORDINATOR = "coordinator/{mid}"
    const val BOARD = "board/{mid}"
    const val ADMIN = "admin/{mid}?tab={tab}"
    const val WORKTREES = "worktrees/{mid}?ws={ws}"

    fun machine(id: String) = "machine/$id"
    fun edit(id: String? = null) = if (id == null) "edit" else "edit?id=$id"
    fun agent(mid: String, pane: String) = "agent/$mid/${Uri.encode(pane)}"
    fun terminal(mid: String, pane: String) = "terminal/$mid/${Uri.encode(pane)}"
    fun layout(mid: String, pane: String) = "layout/$mid/${Uri.encode(pane)}"
    fun coordinator(mid: String) = "coordinator/$mid"
    fun board(mid: String) = "board/$mid"
    fun admin(mid: String, tab: Int = 0) = "admin/$mid?tab=$tab"
    fun worktrees(mid: String, workspaceId: String? = null) =
        if (workspaceId == null) "worktrees/$mid" else "worktrees/$mid?ws=${Uri.encode(workspaceId)}"
}

@Composable
private fun AppNav(nav: NavHostController) {
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
        containerColor = Herdr.colors.ground,
        bottomBar = {
            if (topLevel) {
                HerdrNavBar {
                    NavItem("Agents", HerdrIcons.House, route == Routes.AGENTS, badge = blocked) { nav.goTop(Routes.AGENTS) }
                    NavItem("Machines", HerdrIcons.List, route == Routes.MACHINES) { nav.goTop(Routes.MACHINES) }
                    NavItem("Settings", HerdrIcons.Settings, route == Routes.SETTINGS) { nav.goTop(Routes.SETTINGS) }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = Routes.AGENTS,
            modifier = Modifier.padding(bottom = if (topLevel) padding.calculateBottomPadding() else 0.dp),
            // Short and quiet: a detail slides in a little from the side, tabs cross-fade.
            enterTransition = { fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 12 } },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it / 12 } },
        ) {
            composable(Routes.AGENTS) { AgentsScreen(nav) }
            composable(Routes.MACHINES) { MachinesScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.MACHINE) { MachineScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable(Routes.EDIT) { EditMachineScreen(nav, it.arguments?.getString("id")) }
            composable(Routes.AGENT) {
                AgentScreen(nav, it.arguments?.getString("mid").orEmpty(), Uri.decode(it.arguments?.getString("pane").orEmpty()))
            }
            composable(Routes.ADMIN) {
                AdminScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("tab")?.toIntOrNull() ?: 0)
            }
            composable(Routes.WORKTREES) {
                WorktreesScreen(nav, it.arguments?.getString("mid").orEmpty(), it.arguments?.getString("ws")?.let(Uri::decode))
            }
            composable(Routes.BOARD) { BoardScreen(nav, it.arguments?.getString("mid").orEmpty()) }
            composable(Routes.COORDINATOR) { CoordinatorScreen(nav, it.arguments?.getString("mid").orEmpty()) }
            composable(Routes.TERMINAL) {
                TerminalScreen(nav, it.arguments?.getString("mid").orEmpty(), Uri.decode(it.arguments?.getString("pane").orEmpty()))
            }
            composable(Routes.LAYOUT) {
                LayoutScreen(nav, it.arguments?.getString("mid").orEmpty(), Uri.decode(it.arguments?.getString("pane").orEmpty()))
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
