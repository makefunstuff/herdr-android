package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.voice.EdgeVoice
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * The coordinator's voice in Settings: the voice server (the owner's LFM3 server: its address and
 * key), whose voice, how fast, and a test that says how soon it was heard.
 */
@Composable
fun VoiceCard() {
    val container = LocalContainer.current
    val store = container.voiceSettings
    val s by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val c = Herdr.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("Coordinator's voice", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(
            "The headphones in a coordinator chat read its replies aloud and hear you. The voice comes from your server; when it can't be reached, the phone's own voice stands in.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
        )
        HerdrField(
            s.server, store::setServer, Modifier.fillMaxWidth(), label = "Voice server", singleLine = true,
            isError = s.server.isNotBlank() && EdgeVoice.normalize(s.server) == null,
            supportingText = {
                Text(
                    if (s.server.isNotBlank() && EdgeVoice.normalize(s.server) == null) "Write it as http://address:port"
                    else "An http:// server is reached through the chat's machine, as that machine sees it",
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        HerdrField(
            s.key, store::setKey, Modifier.fillMaxWidth(), label = "Key", singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("From ~/.config/local-ai/edge.key on the server") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        SegmentedPills(listOf("Female", "Male"), if (s.voice == "male") 1 else 0, onSelect = { store.setVoice(if (it == 1) "male" else "female") })
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            listOf(0.8f, 1f, 1.2f, 1.4f, 1.6f).forEach { v ->
                HerdrChip((if (v % 1f == 0f) "%.0f" else "%.1f").format(v) + "×", selected = kotlin.math.abs(s.speed - v) < 0.05f, onClick = {
                    store.setSpeed(v)
                    container.coordinatorVoice.setSpeed(v)
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(if (testing) "Speaking…" else "Test", enabled = !testing) {
                testing = true
                result = null
                scope.launch {
                    result = runCatching { container.coordinatorVoice.test() }.getOrElse { it.message ?: "Couldn't speak" }
                    testing = false
                }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f)) }
        }
    }
}
