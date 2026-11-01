package com.makefunstuff.herdr.ui.artifacts

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.makefunstuff.herdr.core.shepherdr.ArtifactMessage
import com.makefunstuff.herdr.core.shepherdr.ArtifactPage
import com.makefunstuff.herdr.core.shepherdr.ArtifactSendGate
import java.io.ByteArrayInputStream

/** What the artifact screen tells the page it shows without loading it again: new data, what it sent went. */
class ArtifactController {
    internal var web: SealedWebView? = null

    /** Runs [script] (one of [ArtifactPage]'s) in the page, once it is there. */
    fun run(script: String) {
        web?.evaluateJavascript(script, null)
    }

    /** Hands the page new data ([ArtifactPage.data]); a page still loading gets it once it has. */
    fun data(script: String) {
        web?.let {
            it.data = script
            it.evaluateJavascript(script, null)
        }
    }
}

/**
 * An artifact's [page] ([ArtifactPage.seal]ed) in Android's WebView, sealed off as Shepherdr's frame is:
 * its own scripts and forms run, nothing loads (every request is refused but Shepherdr's [font]), it
 * goes nowhere (a link it taps is [ArtifactMessage.Open]ed by the screen) and asks for nothing (no
 * files, storage, camera or location). [dark] and [markup] reach it without a reload. Touches and typing
 * on it mark [gate], which only this view can, so the page may [ArtifactMessage.Send] just after one.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ArtifactView(
    page: String,
    dark: Boolean,
    markup: Boolean,
    background: Color,
    font: ByteArray?,
    gate: ArtifactSendGate,
    controller: ArtifactController,
    onMessage: (ArtifactMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val post = rememberUpdatedState(onMessage)
    AndroidView(
        factory = { ctx ->
            SealedWebView(ctx, gate).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                isVerticalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    // It never leaves its page: a web link comes to the screen as an Open, the rest goes nowhere.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val w = view as SealedWebView
                        val url = request.url.toString()
                        // The page itself, with the headers Shepherdr's frame has: sandboxed and sealed before it is read.
                        if (request.isForMainFrame && url == w.served) {
                            val html = w.page.orEmpty().toByteArray(Charsets.UTF_8)
                            return WebResourceResponse("text/html", "utf-8", 200, "OK", ArtifactPage.HEADERS, ByteArrayInputStream(html))
                        }
                        val bytes = w.font
                        if (url == ArtifactPage.FONT && bytes != null) {
                            return WebResourceResponse("font/woff2", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes))
                        }
                        return WebResourceResponse("text/plain", "utf-8", 404, "Not here", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        val w = view as SealedWebView
                        // It opened in the light or dark it was fetched in: the app's own now, and Mark up as it stands.
                        w.evaluateJavascript(ArtifactPage.theme(w.dark), null)
                        if (w.markup) w.evaluateJavascript(ArtifactPage.markup(true), null)
                        // Newer data that came while it loaded.
                        w.data?.let { w.evaluateJavascript(it, null) }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                }
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun post(json: String) {
                            val m = ArtifactMessage.parse(json) ?: return
                            main.post { post.value(m) }
                        }
                    },
                    ArtifactPage.BRIDGE,
                )
                controller.web = this
            }
        },
        update = { w ->
            w.setBackgroundColor(background.toArgb())
            w.font = font
            if (w.dark != dark) {
                w.dark = dark
                w.evaluateJavascript(ArtifactPage.theme(dark), null)
            }
            if (w.markup != markup) {
                w.markup = markup
                w.evaluateJavascript(ArtifactPage.markup(markup), null)
            }
            if (w.page != page) {
                w.page = page
                w.data = null
                // A new address each time, so it never comes from a cache.
                w.served = ArtifactPage.PAGE + "?n=" + (++w.loads)
                w.loadUrl(w.served!!)
            }
        },
        onRelease = { w ->
            if (controller.web === w) controller.web = null
            w.destroy()
        },
        modifier = modifier,
    )
}

/**
 * The WebView with what it was last given, marking [gate] on a tap (not a scroll or a long press) and on
 * every word typed into it, as a browser grants a page a person's touch.
 */
internal class SealedWebView(context: Context, private val gate: ArtifactSendGate) : WebView(context) {
    @Volatile var page: String? = null
    /** The address [page] is served at now. */
    @Volatile var served: String? = null
    var loads = 0
    var dark = false
    var markup = false
    /** The newest data handed to the page, again once it has loaded. */
    var data: String? = null
    @Volatile var font: ByteArray? = null
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val still = kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop
                if (still && event.eventTime - downAt < android.view.ViewConfiguration.getLongPressTimeout()) gate.touched()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Typing, not the volume or Back.
        if (event.action == KeyEvent.ACTION_UP && typing(event)) gate.touched()
        return super.dispatchKeyEvent(event)
    }

    private fun typing(e: KeyEvent) = e.isPrintingKey || e.keyCode in setOf(
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_CENTER,
    )

    /** The keyboard's words reach a page's field through this, not as key events. */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = super.commitText(text, newCursorPosition).also { gate.touched() }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = super.setComposingText(text, newCursorPosition).also { gate.touched() }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = super.deleteSurroundingText(beforeLength, afterLength).also { gate.touched() }
            override fun sendKeyEvent(event: KeyEvent): Boolean = super.sendKeyEvent(event).also { if (typing(event)) gate.touched() }
            override fun performEditorAction(editorAction: Int): Boolean = super.performEditorAction(editorAction).also { gate.touched() }
        }
    }
}
