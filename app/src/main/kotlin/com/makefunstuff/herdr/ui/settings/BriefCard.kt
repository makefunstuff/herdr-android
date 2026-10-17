package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** The brief in Settings: when it comes, and the way to it. */
@Composable
fun BriefCard(onOpen: () -> Unit, onSchedule: () -> Unit) {
    val container = LocalContainer.current
    val st by container.briefWork.store.state.collectAsStateWithLifecycle()
    val c = Herdr.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("Brief", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(
            "What your agents and the board did while you were away, on every machine, and what waits for you. Read it aloud for the drive.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
        )
        Column {
            RowDivider(inset = 0.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onSchedule).padding(vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("When it comes", style = MaterialTheme.typography.titleMedium, color = c.strong)
                    Text(
                        st.schedule.label.let { if (st.schedule.on) "$it · read aloud: ${st.schedule.readAloud.label.lowercase()}" else it },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                    )
                }
                Spacer(Modifier.width(Space.s))
                Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(18.dp))
            }
            RowDivider(inset = 0.dp)
        }
        Row {
            QuietButton(onClick = onOpen) {
                Icon(HerdrIcons.Newspaper, null, Modifier.size(18.dp)); Text("Open the brief")
            }
        }
    }
}
