package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.terminal.TerminalCanvas
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalPainter
import com.makefunstuff.herdr.core.terminal.TerminalStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Java2D stand-in for the Android canvas, so the shared painter can be checked by eye. */
class Java2DCanvas(private val g: Graphics2D, private val font: Font) : TerminalCanvas {
    private val bold = font.deriveFont(Font.BOLD)

    override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, argb: Int) {
        g.color = Color(argb, true)
        g.fill(java.awt.geom.Rectangle2D.Float(left, top, right - left, bottom - top))
    }

    override fun drawText(text: String, x: Float, baseline: Float, maxWidth: Float, argb: Int, bold: Boolean, italic: Boolean) {
        val f = if (bold) this.bold else font
        g.font = f
        g.color = Color(argb, true)
        val w = g.fontMetrics.stringWidth(text).toFloat()
        if (w > maxWidth * 1.05f && w > 0) {
            val old = g.transform
            g.translate(x.toDouble(), baseline.toDouble())
            g.scale((maxWidth / w).toDouble(), 1.0)
            g.drawString(text, 0f, 0f)
            g.transform = old
        } else {
            g.drawString(text, x, baseline)
        }
    }
}

fun renderPng(emu: TerminalEmulator, out: File, fontPx: Float = 26f, lineFactor: Float = 1.08f) {
    val font = Font("DejaVu Sans Mono", Font.PLAIN, 1).deriveFont(fontPx)
    val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().apply { this.font = font }
    val fm = probe.fontMetrics
    val cellW = fm.charWidth('M').toFloat()
    val cellH = (fm.ascent + fm.descent) * lineFactor
    val baseline = fm.ascent + (cellH - (fm.ascent + fm.descent)) / 2f
    val img = BufferedImage((emu.cols * cellW).toInt() + 1, (emu.rows * cellH).toInt() + 1, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    synchronized(emu.lock) { TerminalPainter().paint(emu, Java2DCanvas(g, font), cellW, cellH, baseline) }
    ImageIO.write(img, "png", out)
}

/**
 * Opens HERDR_PROBE_PANE as a phone would and writes what the app would show (PNG + text) next to
 * herdr's own reading of the pane. Env-gated like [LiveHerdrTest].
 */
class TuiProbe {
    @Test
    fun probe() {
        val pane = System.getenv("HERDR_PROBE_PANE") ?: return
        val outDir = File(System.getenv("HERDR_PROBE_OUT") ?: "build/probe").apply { mkdirs() }
        val (cols, rows) = (System.getenv("HERDR_PROBE_SIZE") ?: "50x30").split("x").map { it.toInt() }
        val (user, host, port) = Machine.parseTarget(System.getenv("HERDR_LIVE_TARGET"))!!
        val identity = KeyImport.fromPrivateKey(File(System.getenv("HERDR_LIVE_KEY")).readText(), "probe")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(Machine("p", "p", host, port, user ?: "root"), { identity }, { null }, scope, { _, _ -> })
            session.start()
            val conn = session.awaitConnection(30_000)
            val stream = TerminalStream(conn, session.machine, pane, cols, rows, scope)
            stream.open()
            withTimeout(15_000) { stream.status.first { it == TerminalStream.Status.Live } }
            System.getenv("HERDR_PROBE_INPUT")?.let { delay(800); stream.send(it.replace("\\r", "\r").replace("\\e", "\u001b")) }
            delay((System.getenv("HERDR_PROBE_WAIT") ?: "2500").toLong())
            val mine = synchronized(stream.emulator.lock) { stream.emulator.screenText() }
            val theirs = session.awaitApi().readPane(pane, "visible", rows).text.trimEnd()
            File(outDir, "app.txt").writeText(mine)
            File(outDir, "herdr.txt").writeText(theirs)
            renderPng(stream.emulator, File(outDir, "app.png"))
            println("SIZE ${stream.emulator.cols}x${stream.emulator.rows} SAME_TEXT=${mine.lines().map { it.trimEnd() } == theirs.lines().map { it.trimEnd() }}")
            stream.close()
            delay(300)
            session.stop()
            scope.cancel()
        }
    }
}
