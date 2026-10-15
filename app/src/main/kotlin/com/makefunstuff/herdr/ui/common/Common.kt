package com.makefunstuff.herdr.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.AppContainer
import com.makefunstuff.herdr.core.host.HostStatus

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

fun HostStatus.label(): String = when (this) {
    HostStatus.Stopped -> "Paused"
    HostStatus.Connecting -> "Connecting"
    HostStatus.Online -> "Online"
    is HostStatus.Reconnecting -> "Reconnecting in ${retryInSeconds}s"
    is HostStatus.Attention -> when (kind) {
        HostStatus.Attention.Kind.Auth -> "! auth"
        HostStatus.Attention.Kind.HostKey -> "! host key"
        HostStatus.Attention.Kind.Herdr -> "! herdr"
        HostStatus.Attention.Kind.Other -> "! error"
    }
}

fun HostStatus.color(): Color = when (this) {
    HostStatus.Online -> Color(0xFF6CC993)
    HostStatus.Connecting, is HostStatus.Reconnecting -> Color(0xFFE2B64A)
    is HostStatus.Attention -> Color(0xFFEF7B70)
    HostStatus.Stopped -> Color(0xFF7A847F)
}

fun HostStatus.detail(): String? = when (this) {
    is HostStatus.Reconnecting -> message
    is HostStatus.Attention -> message
    else -> null
}

fun Context.copyToClipboard(label: String, text: String, toast: String = "Copied") {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
