package com.makefunstuff.herdr.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.ui.theme.Herdr

/*
 * The coding agents' brand marks, as the herdr web UI draws them in the leading cell of agent rows
 * (its AgentMark.tsx and agentSvgMarks.ts, MIT; the marks themselves are their owners', several by
 * way of lobe-icons, MIT). Marks with brand fills keep their colours; the monochrome ones (codex,
 * cursor, pi, copilot, grok) ride the text colour. An agent nobody has drawn gets its initial in a
 * quiet disc.
 */

/** One agent's mark: its picture, and whether it is drawn in the text colour. */
class AgentMarkArt(val vector: ImageVector, val mono: Boolean)

/** The mark for an agent kind ("claude", "codex", "pi"…), or null when it has none. */
fun agentMarkArt(agent: String?): AgentMarkArt? = when (agent?.lowercase()?.trim()) {
    "claude" -> AgentMarks.Claude
    "codex" -> AgentMarks.Codex
    "cursor" -> AgentMarks.Cursor
    "opencode" -> AgentMarks.OpenCode
    "omp" -> AgentMarks.Omp
    "pi" -> AgentMarks.Pi
    "amp" -> AgentMarks.Amp
    "copilot" -> AgentMarks.Copilot
    "droid" -> AgentMarks.Droid
    "gemini" -> AgentMarks.Gemini
    "grok" -> AgentMarks.Grok
    else -> null
}

/** The leading cell of an agent row: the agent's mark at `--mark-size` (22dp), or its initial. */
@Composable
fun AgentMark(agent: String?, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val c = Herdr.colors
    val art = agentMarkArt(agent)
    when {
        art == null -> Box(
            modifier.size(size).background(c.card, CircleShape).border(1.dp, c.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                agent?.trim()?.firstOrNull()?.uppercase() ?: "?",
                style = TextStyle(fontSize = (size.value / 2).sp, fontWeight = FontWeight.Medium, lineHeight = (size.value / 2).sp),
                color = c.text,
            )
        }
        art.mono -> Icon(art.vector, null, modifier.size(size), tint = c.text)
        else -> Image(art.vector, null, modifier.size(size))
    }
}

private fun mark(
    name: String, w: Float, h: Float, mono: Boolean,
    dx: Float = 0f, dy: Float = 0f,
    draw: ImageVector.Builder.() -> Unit,
): AgentMarkArt = AgentMarkArt(
    ImageVector.Builder(name, 24.dp, 24.dp, w, h).apply {
        addGroup(translationX = dx, translationY = dy)
        draw()
        clearGroup()
    }.build(),
    mono,
)

private fun ImageVector.Builder.fill(d: String, color: Color = Color.Black, alpha: Float = 1f, evenOdd: Boolean = false) =
    addPath(addPathNodes(d), fill = SolidColor(color), fillAlpha = alpha, pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)

private fun ImageVector.Builder.fill(d: String, brush: Brush, alpha: Float = 1f) =
    addPath(addPathNodes(d), fill = brush, fillAlpha = alpha)

object AgentMarks {
    val Claude by lazy {
        mark("claude", 512f, 509.64f, mono = false) {
            fill("M115.612 0h280.775C459.974 0 512 52.026 512 115.612v278.415c0 63.587-52.026 115.612-115.613 115.612H115.612C52.026 509.639 0 457.614 0 394.027V115.612C0 52.026 52.026 0 115.612 0z", Color(0xFFD77655))
            fill("M142.27 316.619l73.655-41.326 1.238-3.589-1.238-1.996-3.589-.001-12.31-.759-42.084-1.138-36.498-1.516-35.361-1.896-8.897-1.895-8.34-10.995.859-5.484 7.482-5.03 10.717.935 23.683 1.617 35.537 2.452 25.782 1.517 38.193 3.968h6.064l.86-2.451-2.073-1.517-1.618-1.517-36.776-24.922-39.81-26.338-20.852-15.166-11.273-7.683-5.687-7.204-2.451-15.721 10.237-11.273 13.75.935 3.513.936 13.928 10.716 29.749 23.027 38.848 28.612 5.687 4.727 2.275-1.617.278-1.138-2.553-4.271-21.13-38.193-22.546-38.848-10.035-16.101-2.654-9.655c-.935-3.968-1.617-7.304-1.617-11.374l11.652-15.823 6.445-2.073 15.545 2.073 6.547 5.687 9.655 22.092 15.646 34.78 24.265 47.291 7.103 14.028 3.791 12.992 1.416 3.968 2.449-.001v-2.275l1.997-26.641 3.69-32.707 3.589-42.084 1.239-11.854 5.863-14.206 11.652-7.683 9.099 4.348 7.482 10.716-1.036 6.926-4.449 28.915-8.72 45.294-5.687 30.331h3.313l3.792-3.791 15.342-20.372 25.782-32.227 11.374-12.789 13.27-14.129 8.517-6.724 16.1-.001 11.854 17.617-5.307 18.199-16.581 21.029-13.75 17.819-19.716 26.54-12.309 21.231 1.138 1.694 2.932-.278 44.536-9.479 24.062-4.347 28.714-4.928 12.992 6.066 1.416 6.167-5.106 12.613-30.71 7.583-36.018 7.204-53.636 12.689-.657.48.758.935 24.164 2.275 10.337.556h25.301l47.114 3.514 12.309 8.139 7.381 9.959-1.238 7.583-18.957 9.655-25.579-6.066-59.702-14.205-20.474-5.106-2.83-.001v1.694l17.061 16.682 31.266 28.233 39.152 36.397 1.997 8.999-5.03 7.102-5.307-.758-34.401-25.883-13.27-11.651-30.053-25.302-1.996-.001v2.654l6.926 10.136 36.574 54.975 1.895 16.859-2.653 5.485-9.479 3.311-10.414-1.895-21.408-30.054-22.092-33.844-17.819-30.331-2.173 1.238-10.515 113.261-4.929 5.788-11.374 4.348-9.478-7.204-5.03-11.652 5.03-23.027 6.066-30.052 4.928-23.886 4.449-29.674 2.654-9.858-.177-.657-2.173.278-22.37 30.71-34.021 45.977-26.919 28.815-6.445 2.553-11.173-5.789 1.037-10.337 6.243-9.2 37.257-47.392 22.47-29.371 14.508-16.961-.101-2.451h-.859l-98.954 64.251-17.618 2.275-7.583-7.103.936-11.652 3.589-3.791 29.749-20.474-.101.102.024.101z", Color(0xFFFCF2EE))
        }
    }
    val Codex by lazy { mark("codex", 520f, 520f, mono = true, dx = -100f, dy = -100f) { fill("M304.246 294.611V249.028C304.246 245.189 305.687 242.309 309.044 240.392L400.692 187.612C413.167 180.415 428.042 177.058 443.394 177.058C500.971 177.058 537.44 221.682 537.44 269.182C537.44 272.54 537.44 276.379 536.959 280.218L441.954 224.558C436.197 221.201 430.437 221.201 424.68 224.558L304.246 294.611ZM518.245 472.145V363.224C518.245 356.505 515.364 351.707 509.608 348.349L389.174 278.296L428.519 255.743C431.877 253.826 434.757 253.826 438.115 255.743L529.762 308.523C556.154 323.879 573.905 356.505 573.905 388.171C573.905 424.636 552.315 458.225 518.245 472.141V472.145ZM275.937 376.182L236.592 353.152C233.235 351.235 231.794 348.354 231.794 344.515V238.956C231.794 187.617 271.139 148.749 324.4 148.749C344.555 148.749 363.264 155.468 379.102 167.463L284.578 222.164C278.822 225.521 275.942 230.319 275.942 237.039V376.186L275.937 376.182ZM360.626 425.122L304.246 393.455V326.283L360.626 294.616L417.002 326.283V393.455L360.626 425.122ZM396.852 570.989C376.698 570.989 357.989 564.27 342.151 552.276L436.674 497.574C442.431 494.217 445.311 489.419 445.311 482.699V343.552L485.138 366.582C488.495 368.499 489.936 371.379 489.936 375.219V480.778C489.936 532.117 450.109 570.985 396.852 570.985V570.989ZM283.134 463.99L191.486 411.211C165.094 395.854 147.343 363.229 147.343 331.562C147.343 294.616 169.415 261.509 203.48 247.593V356.991C203.48 363.71 206.361 368.508 212.117 371.866L332.074 441.437L292.729 463.99C289.372 465.907 286.491 465.907 283.134 463.99ZM277.859 542.68C223.639 542.68 183.813 501.895 183.813 451.514C183.813 447.675 184.294 443.836 184.771 439.997L279.295 494.698C285.051 498.056 290.812 498.056 296.568 494.698L417.002 425.127V470.71C417.002 474.549 415.562 477.429 412.204 479.346L320.557 532.126C308.081 539.323 293.206 542.68 277.854 542.68H277.859ZM396.852 599.776C454.911 599.776 503.37 558.513 514.41 503.812C568.149 489.896 602.696 439.515 602.696 388.176C602.696 354.587 588.303 321.962 562.392 298.45C564.791 288.373 566.231 278.296 566.231 268.224C566.231 199.611 510.571 148.267 446.274 148.267C433.322 148.267 420.846 150.184 408.37 154.505C386.775 133.392 357.026 119.958 324.4 119.958C266.342 119.958 217.883 161.22 206.843 215.921C153.104 229.837 118.557 280.218 118.557 331.557C118.557 365.146 132.95 397.771 158.861 421.283C156.462 431.36 155.022 441.437 155.022 451.51C155.022 520.123 210.682 571.466 274.978 571.466C287.931 571.466 300.407 569.549 312.883 565.228C334.473 586.341 364.222 599.776 396.852 599.776Z") } }
    val Cursor by lazy {
        mark("cursor", 24f, 24f, mono = true) {
            fill("M11.925 24l10.425-6-10.425-6L1.5 18l10.425 6z", alpha = 0.39f)
            fill("M22.35 18V6L11.925 0v12l10.425 6z", alpha = 0.8f)
            fill("M11.925 0L1.5 6v12l10.425-6V0z", alpha = 0.6f)
            fill("M22.35 6L11.925 24V12L22.35 6z", alpha = 0.72f)
            fill("M22.35 6l-10.425 6L1.5 6h20.85z", alpha = 0.95f)
        }
    }
    val OpenCode by lazy {
        mark("opencode", 300f, 300f, mono = false, dx = 30f) {
            fill("M180 240H60V120H180V240Z", Color(0xFF4B4646))
            fill("M180 60H60V240H180V60ZM240 300H0V0H240V300Z", Color(0xFFF1ECEC))
        }
    }
    val Omp by lazy {
        mark("omp", 64f, 64f, mono = false) {
            fill("M12 0h40a12 12 0 0 1 12 12v40a12 12 0 0 1-12 12H12A12 12 0 0 1 0 52V12A12 12 0 0 1 12 0Z", Color(0xFF0F0A14))
            fill("M14 16h36v8H40v32h-8V24h-6v22h-8V24h-4z", Brush.linearGradient(0f to Color(0xFFED4ABF), 0.5f to Color(0xFF9B4DFF), 1f to Color(0xFF5AD8E6), start = Offset(0f, 0f), end = Offset(64f, 64f)))
        }
    }
    val Pi by lazy { mark("pi", 24f, 24f, mono = true) { fill("M1 1h16.5v11H12v5.5H6.5V23H1zm5.5 5.5V12H12V6.5z", evenOdd = true); fill("M17.5 12H23v11h-5.5z", evenOdd = true) } }
    val Amp by lazy { mark("amp", 24f, 24f, mono = false) { fill("m15 23.2-3 .8-2-7.8-5.8 5.7-2.3-2.2 5.8-5.8-7.8-2 .8-3 11.3 3z", Color(0xFFF34E3F)); fill("m19.5 18.8-3 .8-2.6-9.6-9.6-2.6.8-3 11.3 3z", Color(0xFFF34E3F)); fill("m23.9 14.4-3 .8-2.6-9.6-9.6-2.5.8-3.1 11.3 3z", Color(0xFFF34E3F)) } }
    val Copilot by lazy { mark("copilot", 24f, 24f, mono = true) { fill("M19.2 5.4q2 2 2.2 5.8 1 0 1.5.6l.8 1q.3.4.3 1v2.6q0 .5-.5.8A21 21 0 0 1 12 21.5c-4.6 0-9.2-2.6-11.5-4.3a1 1 0 0 1-.5-.8v-2.6q0-.6.3-1l.8-1q.6-.7 1.5-.6v-.3Q3 7.3 4.9 5.4A10 10 0 0 1 12 2.5h.2c1.4 0 4.7.3 7.1 2.9M12 9.7h-1q-.1.8-.5 1.2a4 4 0 0 1-4.9.7q-.9.1-1 1v3.3l-.1 1.7q0 .5.5.8Q8.7 20 12 20t7-1.6a1 1 0 0 0 .5-.8v-5q-.2-.9-1.1-1-.9.4-1.9.4c-.7 0-2-.1-3-1.1L13 9.7zm-2.5 4q.9 0 1 1v1.7q-.1.9-1 1a1 1 0 0 1-1-1v-1.7q0-1 1-1m5 0q.9 0 1 1v1.7q-.1.9-1 1a1 1 0 0 1-1-1v-1.7q0-1 1-1M7.6 5.1Q6 5 5.3 6c-1 1-.8 3.7-.3 4.2a3 3 0 0 0 2 .7h.1A4 4 0 0 0 10 9.8c.4-.4.7-1.5.6-2.5q0-1.3-.6-1.8T7.6 5m6.5.4q-.6.5-.6 1.8 0 1.6.6 2.5a4 4 0 0 0 2.8 1 3 3 0 0 0 2-.6c.6-.5.8-3.2-.1-4.2q-.8-.7-2.4-1-1.7 0-2.3.5M12 7.6h-.8v1h1.6v-.9z", evenOdd = true) } }
    val Droid by lazy {
        mark("droid", 508f, 508f, mono = false) {
            fill("M0 0h508v508H0z", Color(0xFF020202))
            fill("M322 150.7a4 4 0 0 1-2.8-2.2 4 4 0 0 1-.1-3.4c8.1-19.4 11.7-34.9 6-41.4-15.4-17.2-77 17-96.6 28.6a4.3 4.3 0 0 1-6.2-2c-8.2-19.3-17-32.7-25.7-33.3-23.3-1.5-42.1 65.2-47.6 87a4 4 0 0 1-2.2 2.7 4 4 0 0 1-3.6 0c-19.8-7.9-35.7-11.4-42.3-5.7-17.6 15 17.4 75.1 29.3 94.3a4 4 0 0 1-.6 5q-.6.6-1.4 1c-19.8 8-33.6 16.5-34.2 25.1-1.6 22.8 66.7 41.1 89 46.5a4 4 0 0 1 2.8 2.2 4 4 0 0 1 .1 3.5c-8.1 19.3-11.7 34.8-6 41.3 15.4 17.2 77-17 96.7-28.6a4.3 4.3 0 0 1 6 2c8.3 19.3 17 32.7 25.8 33.3 23.3 1.6 42.1-65.2 47.6-87a4 4 0 0 1 2.2-2.6 4 4 0 0 1 3.6-.1c19.8 7.9 35.7 11.4 42.3 5.7 17.6-15-17.4-75.1-29.3-94.3a4 4 0 0 1 .6-5q.6-.6 1.5-1c19.7-8 33.5-16.5 34-25.1 1.7-22.8-66.6-41.1-88.9-46.5M295.3 129c4.4 7.8-18.7 60.1-35.8 96.7q-.5.8-1.4 1.4a3 3 0 0 1-3.8-.3q-1-.7-1.1-1.6c-7-23.8-14.9-51.7-23.4-75.4a4 4 0 0 1 0-2.9q.6-1.4 2-2.1c21.2-11.3 57.4-26.3 63.5-15.8m-101.6 6.5c8.9 2.4 30.4 55.3 44.7 93a3 3 0 0 1-1 3.7 3 3 0 0 1-3.8.3c-22.1-12-48-26.2-71-37.1q-1.5-.7-2.1-2.1a4 4 0 0 1-.2-2.9c6.8-22.6 21.6-58.2 33.4-55m-67 74.6c8-4.3 61.5 18.2 99 35q.8.4 1.4 1.3a3 3 0 0 1-.3 3.8q-.6.8-1.7 1c-24.3 6.8-52.9 14.6-77.2 22.8q-1.5.6-2.9 0-1.5-.6-2.2-1.9c-11.5-20.7-27-56-16.1-62m6.6 99.2c2.5-8.6 56.7-29.7 95.3-43.7a4 4 0 0 1 3.7 1 3 3 0 0 1 .4 3.7c-12.3 21.6-27 46.9-38.1 69.5q-.7 1.4-2.1 2-1.5.5-3 .2c-23-6.7-59.6-21.1-56.2-32.7m76.4 65.5c-4.4-7.8 18.6-60 35.8-96.6q.5-1 1.4-1.5a3 3 0 0 1 3.8.3q.9.6 1.1 1.6c7 23.8 14.9 51.7 23.4 75.4q.4 1.5 0 2.9-.6 1.5-2 2.1c-21.2 11.3-57.4 26.4-63.4 15.8m101.5-6.4c-8.8-2.5-30.3-55.4-44.6-93.1a3 3 0 0 1 1-3.7 3 3 0 0 1 3.8-.3c22 12 47.9 26.3 71 37.2q1.5.6 2.1 2 .6 1.5.2 2.9c-6.8 22.6-21.6 58.2-33.5 55m67.1-74.7c-8 4.3-61.5-18.2-99-35q-.9-.4-1.4-1.3a3 3 0 0 1 .3-3.8q.6-.8 1.6-1c24.4-6.8 53-14.6 77.2-22.8q1.5-.5 3 0t2.2 1.9c11.5 20.7 27 56 16.1 62m-6.6-99.2c-2.5 8.7-56.7 29.7-95.3 43.7a4 4 0 0 1-3.7-1 3 3 0 0 1-.4-3.7c12.3-21.6 27-46.8 38-69.5q.8-1.4 2.2-2t3-.1c23 6.6 59.6 21 56.2 32.6", Color(0xFFFAFAFA))
        }
    }
    val Gemini by lazy {
        mark("gemini", 24f, 24f, mono = false) {
            fill("M0 4.4A4.4 4.4 0 0 1 4.4 0h15.2A4.4 4.4 0 0 1 24 4.4v15.2a4.4 4.4 0 0 1-4.4 4.4H4.4A4.4 4.4 0 0 1 0 19.6z", Brush.linearGradient(0f to Color(0xFFEE4D5D), 0.3f to Color(0xFFB381DD), 0.5f to Color(0xFF207CFE), start = Offset(24f, 6.6f), end = Offset(0f, 16.5f)))
            fill("M19.7 1.4a3 3 0 0 1 2.9 2.9v15.4a3 3 0 0 1-2.9 2.9H4.3a3 3 0 0 1-2.9-2.9V4.3a3 3 0 0 1 2.9-2.9zM7.2 8.6l7.8 3.7L7.2 16v2.8l9.6-4.6v-3.8L7.2 5.8z", Color(0xFF1E1E2E), evenOdd = true)
        }
    }
    val Grok by lazy { mark("grok", 24f, 24f, mono = true) { fill("m9.3 15.3 8-6q.6-.2 1 .4a6.6 6.6 0 0 1-8.5 8.5l-2.7 1.3a9 9 0 0 0 14-9.4c-1-4.2.2-5.9 2.7-9.4l.2-.2-3.3 3.3zm-1.7 1.4c-2.8-2.6-2.3-6.8 0-9.2A7 7 0 0 1 15 6.1L17.6 5l-1.9-1A9 9 0 0 0 6 6a9 9 0 0 0-2 9.7c1 2.5-.6 4.2-2.3 6l-1.7 2z", evenOdd = true) } }
}
