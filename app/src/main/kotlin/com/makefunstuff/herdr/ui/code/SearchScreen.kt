package com.makefunstuff.herdr.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.code.CodeCommands
import com.makefunstuff.herdr.core.code.SearchHit
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A search for words, or a pattern, across the checkout's files, run on the machine; the lines found, by file. */
@Composable
fun SearchScreen(nav: NavController, spot: CodeSpot, initial: String = "") {
    val container = LocalContainer.current
    val session = container.hosts.session(spot.machine)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initial) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var result by remember { mutableStateOf<Load<List<SearchHit>>?>(null) }
    var searched by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searched = q
        val key = "search:${spot.machine}:${spot.dir}:$matchCase:$regex:$q"
        container.code.cache.get<List<SearchHit>>(key)?.let { result = Load.Ready(it); return }
        result = Load.Loading
        scope.launch {
            result = try {
                Load.Ready(withContext(Dispatchers.IO) { session.code().search(spot.dir, q, regex, matchCase) }.also { container.code.cache.put(key, it) })
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Load.Failed(t.message ?: "The search failed")
            }
        }
    }
    LaunchedEffect(Unit) { if (initial.isNotEmpty()) search() else runCatching { focus.requestFocus() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = { HerdrTopBar("Search in the code", subtitle = spot.dir.trimEnd('/').substringAfterLast('/'), onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = Space.screen).padding(top = Space.s)) {
                HerdrField(
                    query, { query = it }, singleLine = true,
                    placeholder = { Text(if (regex) "A regular expression" else "Words to find") },
                    leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(20.dp)) },
                    trailingIcon = if (query.isNotEmpty()) ({ BarIcon(HerdrIcons.X, "Clear", { query = "" }) }) else null,
                    textStyle = CodeStyle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.focusRequester(focus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    HerdrChip("Match case", selected = matchCase, onClick = { matchCase = !matchCase; if (searched.isNotEmpty()) search() })
                    HerdrChip("Regular expression", selected = regex, onClick = { regex = !regex; if (searched.isNotEmpty()) search() })
                }
            }
            Box(Modifier.weight(1f)) {
                when (val r = result) {
                    null -> Hint("Searches every file of the checkout, on the machine itself, as git grep does. Files Git ignores are left out.")
                    Load.Loading -> LoadingState("Searching", Modifier.align(Alignment.Center))
                    is Load.Failed -> ErrorMessage(r.message, Modifier.padding(Space.screen))
                    is Load.Ready -> Hits(r.value, searched, matchCase, regex) { hit -> nav.navigate(Routes.codeFile(spot, hit.path, hit.line)) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.padding(Space.screen))
}

@Composable
private fun Hits(hits: List<SearchHit>, query: String, matchCase: Boolean, regex: Boolean, onOpen: (SearchHit) -> Unit) {
    val c = Herdr.colors
    if (hits.isEmpty()) return Hint("Nothing found for \"$query\".")
    val byFile = remember(hits) { hits.groupBy { it.path } }
    val pattern = remember(query, matchCase, regex) {
        runCatching { if (regex) Regex(query, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) else Regex(Regex.escape(query), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl)) {
        item(key = "count") {
            Text(
                "${hits.size} line${if (hits.size == 1) "" else "s"} in ${byFile.size} file${if (byFile.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall, color = c.muted,
                modifier = Modifier.padding(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.xs),
            )
            if (hits.size >= CodeCommands.SEARCH_LIMIT) Notice("Only the first ${CodeCommands.SEARCH_LIMIT} lines found are shown: say more to find fewer.", Modifier.padding(horizontal = Space.screen, vertical = Space.xs))
        }
        byFile.forEach { (path, list) ->
            item(key = "f:$path") {
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(fileIcon(path), null, tint = c.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.s))
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1)
                    Spacer(Modifier.width(Space.s))
                    Text(path.substringBeforeLast('/', ""), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    CountPill("${list.size}")
                }
            }
            items(list, key = { "h:${it.path}:${it.line}" }) { hit ->
                val line = hit.text.trim()
                val shown = buildAnnotatedString {
                    append(line)
                    pattern?.findAll(line)?.take(20)?.forEach { m ->
                        if (m.range.isEmpty()) return@forEach
                        addStyle(SpanStyle(color = c.strong, background = c.accentTint, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClickLabel = "Open at line ${hit.line}") { onOpen(hit) }
                        .padding(horizontal = Space.screen, vertical = Space.xs),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "${hit.line}", style = CodeStyle, color = c.lineNumber, maxLines = 1,
                        modifier = Modifier.width(48.dp).background(c.ground),
                    )
                    Text(shown, style = CodeStyle, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
