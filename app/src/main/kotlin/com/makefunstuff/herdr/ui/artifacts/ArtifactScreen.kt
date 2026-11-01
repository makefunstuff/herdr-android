package com.makefunstuff.herdr.ui.artifacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.shepherdr.ArtifactGone
import com.makefunstuff.herdr.core.shepherdr.ArtifactMessage
import com.makefunstuff.herdr.core.shepherdr.ArtifactPage
import com.makefunstuff.herdr.core.shepherdr.ArtifactSendGate
import com.makefunstuff.herdr.core.shepherdr.Artifacts
import com.makefunstuff.herdr.core.shepherdr.V2Artifact
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.coordinator.ChatEmpty
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The page in the WebView: Shepherdr's version whose page it is ([page]), the version whose data it has ([v]). */
private data class Shown(val id: String, val page: Int, val v: Int, val html: String)

/** Shepherdr's font, read once a run: every artifact is set in it. */
private object ShepherdrFont {
    @Volatile var bytes: ByteArray? = null
}

/**
 * An artifact full screen, as Shepherdr's viewer has it: its page sealed off at the phone's width in the
 * app's light or dark, its versions under the clock (older ones say so, with the way back to the latest),
 * Mark up to note on one thing in it, and a box that writes to its keeper. A newer version swaps in while
 * you look; one that only brought data goes into the open page without a reload. [v] opens that version.
 */
@Composable
fun ArtifactScreen(nav: NavController, machineId: String, artifactId: String, v: Int?) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        ChatEmpty(HerdrIcons.Monitor, "No such machine", "It was removed from the app.")
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shelf = rememberArtifactShelf(session)
    val board = shelf.board
    val a = shelf.all?.firstOrNull { it.id == artifactId }
    val c = Herdr.colors
    val dark = c.dark

    /** The version picked in Versions; null follows the latest. */
    var picked by rememberSaveable(artifactId) { mutableStateOf(v) }
    // A version no longer kept (the last 20 are) shows the latest, and says so.
    val gone = a != null && picked != null && a.version(picked!!) == null
    val want = if (a == null) null else if (gone) a.v else picked ?: a.v

    var shown by remember(artifactId) { mutableStateOf<Shown?>(null) }
    var problem by remember(artifactId) { mutableStateOf<String?>(null) }
    var font by remember { mutableStateOf(ShepherdrFont.bytes) }
    val controller = remember { ArtifactController() }
    val gate = remember { ArtifactSendGate(container.clock) }
    // The page for the version wanted: the same page takes new data where it is; another page loads.
    LaunchedEffect(board, a?.id, want, a?.let { art -> want?.let(art::htmlOf) }) {
        val b = board ?: return@LaunchedEffect
        val art = a ?: return@LaunchedEffect
        val n = want ?: return@LaunchedEffect
        val page = art.htmlOf(n)
        val now = shown
        if (now != null && now.id == art.id && now.page == page) {
            if (now.v == n) return@LaunchedEffect
            val data = runCatching { b.artifactData(art, n) }.getOrNull()
            if (data != null) {
                controller.data(ArtifactPage.data(data))
                shown = now.copy(v = n)
                return@LaunchedEffect
            }
        }
        while (true) {
            try {
                // Its font first, which the page asks for as it loads.
                if (font == null) font = runCatching { b.artifactFont() }.getOrNull()?.also { ShepherdrFont.bytes = it }
                shown = Shown(art.id, page, n, ArtifactPage.seal(b.artifactPage(art, n, dark)))
                problem = null
                break
            } catch (e: CancellationException) {
                throw e
            } catch (gone: ArtifactGone) {
                // Deleted, or artifacts turned off there: asking again won't bring it.
                problem = gone.message
                break
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't read it from Shepherdr"
                delay(5_000)
            }
        }
    }

    // Following the latest: say so when a new version comes in.
    var seen by remember(artifactId) { mutableStateOf<Int?>(null) }
    var fresh by remember(artifactId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(a?.v) {
        val now = a?.v ?: return@LaunchedEffect
        val was = seen
        seen = now
        if (picked == null && was != null && now > was) fresh = now
    }
    LaunchedEffect(fresh) {
        if (fresh == null) return@LaunchedEffect
        delay(4_000)
        fresh = null
    }

    var markup by remember(artifactId) { mutableStateOf(false) }
    var pick by remember(artifactId) { mutableStateOf<ArtifactMessage.Pick?>(null) }
    var versions by remember { mutableStateOf(false) }
    var said by remember(artifactId) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(artifactId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    fun onPage(m: ArtifactMessage) {
        val art = a ?: return
        val b = board ?: return
        val n = shown?.v ?: return
        when (m) {
            // A page speaks for you only right after you tapped or typed in it, at most once every two seconds.
            is ArtifactMessage.Send -> gate.admit(m.text)?.let { text ->
                scope.launch {
                    runCatching { b.artifactSay(art, n, text, page = true) }.onSuccess { to -> controller.run(ArtifactPage.sent(text, to)) }
                }
            }
            is ArtifactMessage.Open -> if (gate.fresh()) context.openUrl(m.url)
            is ArtifactMessage.Pick -> if (markup) pick = m
        }
    }

    fun send() {
        val art = a ?: return
        val b = board ?: return
        val n = want ?: return
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        val on = pick
        scope.launch {
            runCatching { b.artifactSay(art, n, text, pick = on) }
                .onSuccess { to ->
                    draft = ""
                    pick = null
                    markup = false
                    said = "Sent to $to. A new version shows here when it's in."
                }
                .onFailure { said = it.message ?: "That didn't go through. Try again." }
            sending = false
        }
    }

    Scaffold(
        containerColor = c.ground,
        topBar = {
            HerdrTopBar(
                a?.title ?: "Artifact",
                subtitle = if (a == null) session.machine.label else a.byline(want ?: a.v),
                onBack = { nav.popBackStack() },
                actions = {
                    if (a != null) {
                        BarIcon(HerdrIcons.History, "Versions", { versions = true })
                        BarIcon(
                            HerdrIcons.Highlighter, if (markup) "Done marking up" else "Mark up",
                            onClick = {
                                markup = !markup
                                if (!markup) pick = null
                            },
                            selected = markup,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
            when {
                gone -> Banner("v$picked is no longer kept, only the last 20 versions are. This is the latest.")
                a != null && picked != null && picked != a.v -> Banner("An older version: v$picked of ${a.v}.", "Show the latest") { picked = null; pick = null }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(shepherdrBackground(dark))) {
                val s = shown
                when {
                    shelf.read && shelf.all != null && a == null -> ChatEmpty(HerdrIcons.LayoutDashboard, "This artifact was deleted", "It isn't kept on ${session.machine.label} any more.")
                    shelf.read && shelf.all == null -> ChatEmpty(HerdrIcons.LayoutDashboard, "No artifacts here", "Shepherdr on ${session.machine.label} keeps none.")
                    s != null && s.id == artifactId -> ArtifactView(
                        s.html, dark = dark, markup = markup, background = shepherdrBackground(dark), font = font, gate = gate,
                        controller = controller, onMessage = ::onPage, modifier = Modifier.fillMaxSize(),
                    )
                    (problem ?: shelf.problem) != null -> ChatEmpty(HerdrIcons.CircleAlert, "Can't open it", problem ?: shelf.problem.orEmpty())
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Opening") }
                }
                fresh?.let { n ->
                    Surface(
                        color = c.strong, contentColor = c.ground, shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = Space.m),
                    ) {
                        Text("Updated to v$n", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Space.m, vertical = Space.xs))
                    }
                }
            }
            if (a != null) {
                val note = pick
                ChatComposer(
                    draft = draft,
                    onDraft = {
                        draft = it
                        said = null
                    },
                    placeholder = if (note != null) "What should change?" else "Ask ${a.keeper.ifBlank { "its keeper" }} for a change…",
                    onSend = ::send,
                    modifier = Modifier.padding(top = Space.s),
                    enabled = board != null,
                    sending = sending,
                    sendLabel = if (note != null) "Send the note" else "Send",
                    floating = true,
                    above = when {
                        note != null -> ({
                            Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "A note on " + (note.text.takeIf { it.isNotEmpty() }?.let { "“${it.take(40)}”" } ?: "what you picked"),
                                    style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("Not on that", style = MaterialTheme.typography.labelMedium, color = c.accent, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { pick = null }.padding(Space.xs))
                            }
                        })
                        said != null -> ({ Hint(said.orEmpty()) })
                        markup -> ({ Hint("Tap what should change.") })
                        else -> null
                    },
                )
            }
        }
    }
    if (a != null) {
        HerdrMenu(expanded = versions, onDismissRequest = { versions = false }, title = "Versions", subtitle = a.title) {
            a.versions.forEach { x ->
                HerdrMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Artifacts.versionLine(a, x), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(x.`when`, style = MaterialTheme.typography.labelMedium, color = c.muted)
                        }
                    },
                    onClick = {
                        versions = false
                        pick = null
                        picked = if (x.n == a.v) null else x.n
                    },
                    leadingIcon = { if (x.n == want) Icon(HerdrIcons.Check, null, Modifier.size(20.dp)) else Spacer(Modifier.size(20.dp)) },
                )
            }
        }
    }
}

/** A quiet line over the page: an older version, one no longer kept; with an [action] at its end. */
@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = Herdr.colors
    Row(Modifier.fillMaxWidth().background(c.panel).heightIn(min = 40.dp).padding(horizontal = Space.screen), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
        if (action != null) {
            Spacer(Modifier.width(Space.s))
            Text(action, style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clickable(onClick = onAction).padding(vertical = Space.s))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xs),
    )
}

/** The words a version reads with in the bar: "v3 · HA-1 agent · 2m". */
internal fun V2Artifact.byline(n: Int): String {
    val x = version(n)
    return listOf("v$n", x?.by ?: by, x?.`when` ?: `when`).filter { it.isNotBlank() }.joinToString(" · ")
}
