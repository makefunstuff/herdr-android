package com.makefunstuff.herdr.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.makefunstuff.herdr.core.terminal.Cell
import com.makefunstuff.herdr.core.terminal.Keys
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [TerminalEmulator] and turns touch and keyboard input into terminal bytes.
 *
 * In "fit" mode the grid follows the view size and [onGridSize] reports it. Otherwise the grid
 * keeps the host's size and the user pans across it.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context) : View(context) {
    var emulator: TerminalEmulator? = null
        set(value) { field = value; invalidate() }

    /** Called with text or escape sequences to send to the pane. */
    var onInput: (String) -> Unit = {}
    /** Called with the grid that fits the view, in fit mode. */
    var onGridSize: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    /** Called for vertical drags in fit mode: positive lines scroll back. */
    var onScroll: (lines: Int) -> Unit = {}
    var onLongPress: () -> Unit = {}

    var fitToView = true
        set(value) { field = value; panX = 0f; reportGrid(); invalidate() }

    /** Sticky modifiers from the extra-keys row, consumed by the next typed key. */
    var ctrlSticky = false
    var altSticky = false
    var onModifiersConsumed: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private var fontSizePx = 11f * resources.displayMetrics.scaledDensity
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val bgPaint = Paint()
    private var cellW = 1f
    private var cellH = 1f
    private var baseline = 0f
    private var panX = 0f
    private var panY = 0f
    private var scrollAccum = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateMetrics()
    }

    fun setFontSizeSp(sp: Float) {
        fontSizePx = sp * resources.displayMetrics.scaledDensity
        updateMetrics()
        reportGrid()
        invalidate()
    }

    private fun updateMetrics() {
        textPaint.textSize = fontSizePx
        boldPaint.textSize = fontSizePx
        cellW = textPaint.measureText("M")
        val fm = textPaint.fontMetrics
        cellH = (fm.descent - fm.ascent) * 1.08f
        baseline = -fm.ascent + (cellH - (fm.descent - fm.ascent)) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportGrid()
    }

    private fun reportGrid() {
        if (!fitToView || width == 0 || height == 0) return
        val cols = max(10, floor((width - 2 * PAD * density) / cellW).toInt())
        val rows = max(4, floor((height - 2 * PAD * density) / cellH).toInt())
        onGridSize(cols, rows)
    }

    // ---- Drawing ------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(DEFAULT_BG)
        val emu = emulator ?: return
        val pad = PAD * density
        synchronized(emu.lock) {
            clampPan(emu)
            canvas.save()
            canvas.translate(pad - panX, pad - panY)
            val sb = StringBuilder()
            for (y in 0 until emu.rows) {
                val row = emu.row(y)
                val top = y * cellH
                var x = 0
                while (x < emu.cols) {
                    val cell = row[x]
                    if (cell.isContinuation) { x++; continue }
                    val (fg, bg) = colors(cell)
                    // Gather a run of single-width cells with the same style.
                    val start = x
                    sb.setLength(0)
                    sb.append(cell.text ?: " ")
                    var end = x + 1
                    val wide = end < emu.cols && row[end].isContinuation
                    if (!wide) {
                        while (end < emu.cols) {
                            val next = row[end]
                            if (next.isContinuation || next.fg != cell.fg || next.bg != cell.bg || next.attrs != cell.attrs) break
                            if (end + 1 < emu.cols && row[end + 1].isContinuation) break
                            sb.append(next.text ?: " ")
                            end++
                        }
                    } else {
                        end++
                    }
                    val left = start * cellW
                    val right = end * cellW
                    if (bg != DEFAULT_BG) {
                        bgPaint.color = bg
                        canvas.drawRect(left, top, right, top + cellH, bgPaint)
                    }
                    if (cell.attrs and Cell.HIDDEN == 0) {
                        val paint = if (cell.attrs and Cell.BOLD != 0) boldPaint else textPaint
                        paint.color = fg
                        paint.textSkewX = if (cell.attrs and Cell.ITALIC != 0) -0.2f else 0f
                        paint.isUnderlineText = cell.attrs and Cell.UNDERLINE != 0
                        paint.isStrikeThruText = cell.attrs and Cell.STRIKE != 0
                        if (wide) {
                            canvas.drawText(sb, 0, sb.length, left, top + baseline, paint)
                        } else {
                            // Draw each cell at its grid position so box-drawing lines stay aligned.
                            var col = start
                            var i = 0
                            while (i < sb.length) {
                                val cp = Character.codePointAt(sb, i)
                                val n = Character.charCount(cp)
                                if (cp != ' '.code) canvas.drawText(sb, i, i + n, col * cellW, top + baseline, paint)
                                i += n
                                col++
                            }
                        }
                    }
                    x = end
                }
            }
            if (emu.cursorVisible) {
                bgPaint.color = CURSOR
                bgPaint.alpha = 160
                canvas.drawRect(emu.cursorX * cellW, emu.cursorY * cellH, (emu.cursorX + 1) * cellW, (emu.cursorY + 1) * cellH, bgPaint)
                bgPaint.alpha = 255
            }
            canvas.restore()
        }
    }

    private fun clampPan(emu: TerminalEmulator) {
        val pad = PAD * density
        val maxX = max(0f, emu.cols * cellW + 2 * pad - width)
        val maxY = max(0f, emu.rows * cellH + 2 * pad - height)
        panX = panX.coerceIn(0f, maxX)
        panY = panY.coerceIn(0f, maxY)
    }

    private fun colors(cell: Cell): Pair<Int, Int> {
        var fg = if (cell.fg == Cell.DEFAULT_COLOR) DEFAULT_FG else resolve(cell.fg)
        var bg = if (cell.bg == Cell.DEFAULT_COLOR) DEFAULT_BG else resolve(cell.bg)
        if (cell.attrs and Cell.BOLD != 0 && cell.fg in 0..7) fg = resolve(cell.fg + 8)
        if (cell.attrs and Cell.INVERSE != 0) { val t = fg; fg = bg; bg = t }
        if (cell.attrs and Cell.DIM != 0) fg = (fg and 0x00FFFFFF) or (0x99 shl 24)
        return fg to bg
    }

    private fun resolve(color: Int): Int = when {
        Cell.isRgb(color) -> 0xFF000000.toInt() or (color and 0xFFFFFF)
        color in 0..15 -> ANSI[color]
        color in 16..231 -> {
            val i = color - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            0xFF000000.toInt() or (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
        }
        color in 232..255 -> {
            val v = 8 + (color - 232) * 10
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        else -> DEFAULT_FG
    }

    // ---- Touch -------------------------------------------------------------------------

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val sp = fontSizePx / resources.displayMetrics.scaledDensity
            setFontSizeSp((sp * detector.scaleFactor).coerceIn(5f, 24f))
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return true
            val emu = emulator
            val horizontal = abs(dx) > abs(dy)
            if (!fitToView && emu != null) {
                panX += dx
                // Pan vertically while the host grid is taller than the view; past that, scroll back.
                val canPanY = emu.rows * cellH + 2 * PAD * density > height
                if (canPanY && !horizontal) panY += dy
                invalidate()
                if (canPanY || horizontal) return true
            }
            if (!horizontal) {
                scrollAccum += dy
                val lines = (scrollAccum / cellH).toInt()
                if (lines != 0) {
                    scrollAccum -= lines * cellH
                    onScroll(-lines)
                }
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) scrollAccum = 0f
        return true
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---- Keyboard ----------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password keeps keyboards from autocorrecting or holding text in composition.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing("")
                typeText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(max(1, beforeLength)) { onInput(Keys.BACKSPACE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }

            /** Some keyboards compose even in password fields; send only the difference. */
            private fun replaceComposing(next: String) {
                val common = composing.commonPrefixWith(next).length
                repeat(composing.length - common) { onInput(Keys.BACKSPACE) }
                if (next.length > common) typeText(next.substring(common))
                composing = next
            }
        }
    }

    private fun typeText(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\n", "\r")
        if (ctrlSticky || altSticky) {
            val first = normalized.substring(0, Character.charCount(normalized.codePointAt(0)))
            onInput(Keys.withModifiers(first, ctrlSticky, altSticky))
            ctrlSticky = false
            altSticky = false
            onModifiersConsumed()
            if (normalized.length > first.length) onInput(normalized.substring(first.length))
        } else {
            onInput(normalized)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val seq = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keys.ENTER
            KeyEvent.KEYCODE_DEL -> Keys.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) Keys.SHIFT_TAB else Keys.TAB
            KeyEvent.KEYCODE_ESCAPE -> Keys.ESC
            KeyEvent.KEYCODE_DPAD_UP -> Keys.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Keys.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Keys.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Keys.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> Keys.HOME
            KeyEvent.KEYCODE_MOVE_END -> Keys.END
            KeyEvent.KEYCODE_PAGE_UP -> Keys.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Keys.PAGE_DOWN
            KeyEvent.KEYCODE_BACK -> return super.onKeyDown(keyCode, event)
            else -> null
        }
        if (seq != null) {
            onInput(if (event.isAltPressed || altSticky) Keys.ESC + seq else seq)
            if (altSticky) { altSticky = false; onModifiersConsumed() }
            return true
        }
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (unicode == 0) return super.onKeyDown(keyCode, event)
        val text = String(Character.toChars(unicode))
        val ctrl = event.isCtrlPressed || ctrlSticky
        val alt = event.isAltPressed || altSticky
        onInput(Keys.withModifiers(text, ctrl, alt))
        if (ctrlSticky || altSticky) {
            ctrlSticky = false
            altSticky = false
            onModifiersConsumed()
        }
        return true
    }

    /** Visible screen text, for copying. */
    fun screenText(): String = emulator?.let { synchronized(it.lock) { it.screenText() } } ?: ""

    companion object {
        private const val PAD = 4f
        val DEFAULT_FG = 0xFFD8DEDA.toInt()
        val DEFAULT_BG = 0xFF0B0D0C.toInt()
        val CURSOR = 0xFF7DD3B6.toInt()
        val ANSI = intArrayOf(
            0xFF1D2321.toInt(), 0xFFE0675C.toInt(), 0xFF7CC68B.toInt(), 0xFFE2B64A.toInt(),
            0xFF6E9FE6.toInt(), 0xFFC38AD8.toInt(), 0xFF5CC3C0.toInt(), 0xFFC9D1CC.toInt(),
            0xFF5B6560.toInt(), 0xFFF2877D.toInt(), 0xFF98DCA6.toInt(), 0xFFF0CC6C.toInt(),
            0xFF8FB8F5.toInt(), 0xFFD6A6E8.toInt(), 0xFF7FDAD6.toInt(), 0xFFF4F7F5.toInt(),
        )
    }
}
