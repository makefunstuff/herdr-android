package com.makefunstuff.herdr.ui.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.quick.QuickKind
import com.makefunstuff.herdr.core.quick.QuickRun
import com.makefunstuff.herdr.core.quick.RunState
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.data.quick
import com.makefunstuff.herdr.ui.code.shortPath
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.clipboardText
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a run's output keeps at most on the phone: the end of a long one. */
private const val KEEP_CHARS = 400_000

/**
 * One run of a quick command, as it goes: its output streaming in (a prompt's answer, as Markdown),
 * how it ended, and Stop, Copy and Run again; an AI one's answer also goes to the Coordinator or
 * becomes a board task. It is read from the machine, so it can be closed and opened again, and the
 * run carries on without it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunSheet(nav: NavController, machineId: String, id: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null, onRun: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    val host = session?.state?.collectAsStateWithLifecycle()?.value
    var run by remember(id) { mutableStateOf<QuickRun?>(null) }
    var text by remember(id) { mutableStateOf("") }
    var offset by remember(id) { mutableLongStateOf(0L) }
    var now by remember(id) { mutableLongStateOf(0L) }
    var gone by remember(id) { mutableStateOf(false) }
    var problem by remember(id) { mutableStateOf<String?>(null) }
    var again by remember(id) { mutableIntStateOf(0) }
    var busy by remember(id) { mutableStateOf(false) }
    var task by remember(id) { mutableStateOf(false) }
    val online = host?.online == true

    // Read it every second while it runs (a script's output from where the last read stopped), then once more.
    LaunchedEffect(id, again, online) {
        if (session == null || !online) return@LaunchedEffect
        val q = session.quick()
        while (true) {
            try {
                val read = q.read(id, if (run?.kind == QuickKind.Prompt) 0 else offset)
                now = read.now
                val r = read.run
                if (r == null) { gone = true; return@LaunchedEffect }
                if (r.kind == QuickKind.Prompt) text = read.text
                else if (read.text.isNotEmpty()) {
                    val all = text + read.text
                    text = if (all.length > KEEP_CHARS) all.takeLast(KEEP_CHARS) else all
                    offset += read.text.toByteArray().size
                }
                run = r
                problem = null
                if (!r.state.running) {
                    container.alertWork.quickSeen(machineId, host?.machine?.label.orEmpty(), r)
                    return@LaunchedEffect
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't read it"
            }
            delay(if (run?.state == RunState.Setup) 2000 else 900)
        }
    }

    fun act(what: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }.onFailure { context.toast(it.message ?: "Couldn't $what") }
            busy = false
            again++
        }
    }

    HerdrSheet(onDismissRequest = onDismiss) {
        val r = run
        Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) Space.s else Space.xl, end = Space.l, bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BarIcon(HerdrIcons.ArrowLeft, "Back to the commands", onBack)
            if (r != null) IconTile(r.iconVector(), tint = if (r.kind == QuickKind.Prompt) Herdr.colors.accent else null, size = 36.dp)
            Column(Modifier.weight(1f).padding(start = Space.m)) {
                Text(r?.name ?: "Quick command", style = MaterialTheme.typography.titleLarge, color = Herdr.colors.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r != null) Text(
                    listOfNotNull(
                        r.cwd.ifEmpty { null }?.let { host?.shortPath(it) ?: it },
                        r.args.ifEmpty { null }?.let { "“$it”" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (r != null) StatusBadge(r.stateLabel(), r.state.color())
        }
        Column(Modifier.padding(horizontal = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            when {
                gone -> Notice("This run isn't on the machine any more.", icon = HerdrIcons.Info)
                r == null && problem != null -> ErrorMessage(problem!!) { again++ }
                r == null && !online -> Notice("${host?.machine?.label ?: "The machine"} is offline", icon = HerdrIcons.Clock)
                r == null -> LoadingState("Reading it")
                else -> {
                    Facts(r, now)
                    if (r.state == RunState.Lost) Notice(
                        if (r.kind == QuickKind.Prompt) "Its worker stopped before it finished (the machine may have restarted). It carries on from where it was."
                        else "It stopped without saying how it ended (the machine may have restarted).",
                        color = Herdr.colors.notice, icon = HerdrIcons.TriangleAlert,
                    )
                    if (r.error.isNotEmpty() && r.state != RunState.Done) ErrorMessage(r.error)
                    Output(r, text)
                    if (problem != null) ErrorMessage(problem!!) { again++ }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (r.state.running) DangerButton(if (busy) "Stopping…" else "Stop", enabled = !busy) {
                            act("stop it") { session!!.quick().stop(r.id) }
                        }
                        if (r.state == RunState.Lost && r.kind == QuickKind.Prompt) PrimaryButton("Carry on", enabled = !busy) {
                            act("start it again") { session!!.quick().resume(r) }
                        }
                        if (text.isNotEmpty()) SecondaryButton(onClick = { context.copyToClipboard(r.name, text) }) {
                            Icon(HerdrIcons.Copy, null, Modifier.size(16.dp)); Text("Copy", Modifier.padding(start = Space.xs))
                        }
                        if (!r.state.running && r.repeatable) SecondaryButton(onClick = {
                            busy = true
                            scope.launch {
                                runCatching { session!!.rerunQuick(r, context.clipboardText().orEmpty(), host?.machine?.label.orEmpty(), java.time.LocalDate.now().toString()) }
                                    .onSuccess(onRun).onFailure { context.toast(it.message ?: "Couldn't run it again") }
                                busy = false
                            }
                        }, enabled = !busy) {
                            Icon(HerdrIcons.RotateCw, null, Modifier.size(16.dp)); Text("Run again", Modifier.padding(start = Space.xs))
                        }
                        if (r.kind == QuickKind.Prompt && text.isNotBlank() && host?.hasBoard == true) {
                            SecondaryButton(onClick = {
                                container.drafts.add(DraftStore.coordinator(machineId), "${r.name} answered:\n\n${text.trim()}")
                                onDismiss()
                                nav.navigate(Routes.coordinator(machineId))
                            }) { Icon(HerdrIcons.Send, null, Modifier.size(16.dp)); Text("Send to Coordinator", Modifier.padding(start = Space.xs)) }
                            SecondaryButton(onClick = { task = true }) {
                                Icon(HerdrIcons.ListTodo, null, Modifier.size(16.dp)); Text("Make it a task", Modifier.padding(start = Space.xs))
                            }
                        }
                    }
                }
            }
        }
    }

    val r = run
    if (task && r != null && session != null) MakeTaskDialog(r, text, onDismiss = { task = false }) { title, repo, desc ->
        task = false
        scope.launch {
            runCatching { session.awaitBoard().add(title, repo, desc) }
                .onSuccess { context.toast(it.ifEmpty { "Added to the board" }) }
                .onFailure { context.toast(it.message ?: "The board refused") }
        }
    }
}

/** How long it ran, and for a prompt the model and the tokens. */
@Composable
private fun Facts(r: QuickRun, now: Long) {
    val parts = listOfNotNull(
        r.seconds(now).takeIf { r.started > 0 }?.let { (if (r.state.running) "for " else "took ") + duration(it) },
        r.ended.takeIf { it > 0 }?.let { runAgo(it, now) },
        r.model.ifEmpty { null },
        r.tokens.takeIf { it > 0 }?.let { "%,d tokens".format(it) },
    )
    if (parts.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        if (r.state.running) Spinner(size = 14.dp)
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
    }
}

/** What it printed (a script's, in the terminal's type, scrolled to its end while it runs) or answered (a prompt's, as Markdown). */
@Composable
private fun Output(r: QuickRun, text: String) {
    val c = Herdr.colors
    val scroll = rememberScrollState()
    LaunchedEffect(text.length, r.state) { if (r.kind == QuickKind.Script) scroll.scrollTo(scroll.maxValue) }
    if (text.isEmpty()) {
        Text(
            when {
                r.state == RunState.Setup -> "Setting up the AI worker on the machine (the first time takes a minute)…"
                r.state.running -> if (r.kind == QuickKind.Prompt) "Waiting for the answer…" else "Waiting for output…"
                else -> "It printed nothing."
            },
            style = MaterialTheme.typography.bodyMedium, color = c.muted,
        )
        return
    }
    Box(
        Modifier.fillMaxWidth().heightIn(max = 460.dp).background(if (r.kind == QuickKind.Script) c.ground else c.card, HerdrShapes.Field)
            .hairline(HerdrShapes.Field).verticalScroll(scroll).padding(Space.m),
    ) {
        SelectionContainer {
            if (r.kind == QuickKind.Prompt) MarkdownText(text)
            else Text(text.trimEnd('\n'), style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = c.text)
        }
    }
}

/** A board task from an AI command's answer: its title, its repository, and the answer as its description. */
@Composable
private fun MakeTaskDialog(r: QuickRun, answer: String, onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf(com.makefunstuff.herdr.core.quick.QuickText.line(answer).take(80).ifEmpty { r.name }) }
    var repo by remember { mutableStateOf((r.repo.ifEmpty { r.cwd }).trimEnd('/').substringAfterLast('/')) }
    var desc by remember { mutableStateOf("From the quick command “${r.name}”:\n\n${answer.trim()}") }
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make it a task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                HerdrField(title, { title = it }, label = "Title", modifier = Modifier.fillMaxWidth())
                HerdrField(repo, { repo = it }, label = "Repo (name or path)", singleLine = true, modifier = Modifier.fillMaxWidth())
                HerdrField(desc, { desc = it }, label = "Description (Markdown)", minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
            }
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { PrimaryButton("Add", enabled = title.isNotBlank()) { onAdd(title.trim(), repo.trim(), desc.trim()) } },
    )
}
