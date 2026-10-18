package com.makefunstuff.herdr.ui.preview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.makefunstuff.herdr.ui.common.openUrl
import java.io.File

/**
 * What the dev server screen asks of its page: to load again, go back, and a picture of it as it
 * shows. [address] is where it is (`/settings?tab=2`), [canGoBack] and [error] how it stands.
 */
class PageController {
    internal var web: WebView? = null
    var address by mutableStateOf("/")
        internal set
    var canGoBack by mutableStateOf(false)
        internal set
    /** Why the page didn't load (`net::ERR_CONNECTION_REFUSED`), null once it does. */
    var error by mutableStateOf<String?>(null)
        internal set

    fun reload() {
        error = null
        web?.reload()
    }

    fun back() = web?.goBack()

    /** The page as it shows on the screen, written to [file] as PNG; false when there's none yet. */
    fun screenshot(file: File): Boolean {
        val w = web ?: return false
        if (w.width == 0 || w.height == 0) return false
        val bitmap = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        w.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return true
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/** At desktop size the page lays out 1280 px wide, whatever its viewport tag says, and is shown zoomed out. */
private const val DESKTOP_VIEWPORT = "(function(){var m=document.querySelector('meta[name=viewport]');" +
    "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
    "m.setAttribute('content','width=1280');})()"

/**
 * The page at [url] (a dev server through the app's forwarded port) in Android's WebView: at the
 * phone's own size, or with [phoneSize] off at a desktop's, zoomed out. Links that leave the
 * machine open in the phone's browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PageView(url: String, phoneSize: Boolean, controller: PageController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PageWebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val host = request.url.host
                        if (host == "localhost" || host == "127.0.0.1") return false
                        ctx.openUrl(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        controller.error = null
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if ((view as PageWebView).phoneSize == false) view.evaluateJavascript(DESKTOP_VIEWPORT, null)
                        controller.address = url?.let(Uri::parse)?.let { u -> (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "") } ?: "/"
                        controller.canGoBack = view.canGoBack()
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) controller.error = error.description?.toString() ?: "The page didn't load"
                    }
                }
                controller.web = this
            }
        },
        update = { web ->
            // A new size loads the page again at it.
            val sized = web.phoneSize
            if (sized != phoneSize) {
                web.phoneSize = phoneSize
                web.settings.userAgentString = if (phoneSize) null else DESKTOP_UA
                if (sized != null && web.base == url) web.reload()
            }
            if (web.base != url) {
                web.base = url
                web.loadUrl(url)
            }
        },
        modifier = modifier,
    )
    DisposableEffect(controller) {
        onDispose {
            controller.web?.destroy()
            controller.web = null
        }
    }
}

/** The WebView with what it was last given: the address it opened and the size it lays out at. */
private class PageWebView(context: android.content.Context) : WebView(context) {
    var base: String? = null
    var phoneSize: Boolean? = null
}
