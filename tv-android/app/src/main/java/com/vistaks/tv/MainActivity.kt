package com.vistaks.tv

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vistaks.tv.nativeplayer.NativePlayerHost
import com.vistaks.tv.update.SelfUpdateHost
import org.json.JSONObject

/**
 * Thin native shell for the hosted Vistaks TV web app.
 *
 * The page owns pairing, the library UI, navigation and playback. This activity only provides:
 * a full-screen WebView, the `VistaksTvHost` JS bridge, Back / media-key / lifecycle forwarding to
 * `window.VistaksTv`, iframe fullscreen support, a native offline/retry screen and immersive mode.
 *
 * Long-run duties (ADR-0089 amendment 2026-11-01): the screen stays on only while the page holds
 * a keep-awake lease (it renews it while something plays; an unrenewed lease expires); the page
 * hears pause / resume / trim; a renderer the system killed is replaced by a new WebView that
 * resumes the page's last session instead of landing on Home (a crash loop stops on the native
 * error screen); a planned recycle asked for by the page takes the same path.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var errorView: View
    private lateinit var retryButton: Button

    /**
     * The native player (ADR-0100). The class comes from the build flavor: `sideload` carries
     * Media3 + NewPipeExtractor (version 1), `play` a no-op stub (version 0). The page drives it
     * through the bridge's native* methods (§3); the host tells us when its surface shows (overlay).
     */
    private lateinit var nativePlayer: NativePlayerHost

    /**
     * The self-update (sideload with a configured manifest only; play's is a no-op whose bridge is
     * null). Its bridge is `window.VistaksTvUpdate`; its events reach the page as
     * `window.VistaksTv.native('update', json)` (ADR-0100 §3, ADR-0094 amendment 2026-11-10).
     */
    private lateinit var selfUpdate: SelfUpdateHost

    private var webViewDestroyed = false

    /**
     * The native surface shows under the WebView (ADR-0100 §1): the WebView's own base colour is
     * transparent then, so the page's transparent areas let the video through. Survives a WebView
     * rebuild: the new WebView gets the same base colour (the held picture stays visible under it).
     */
    private var nativeOverlay = false
    private var failedUrl: String? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activityResumed = false

    /** Why the page that is loading now was loaded: read once by the page (`launchReason()`). */
    @Volatile private var launchReason = LAUNCH

    /** A recycle the page asked for is under way: the renderer's end is planned, not a crash. */
    private var recyclePending = false
    private val recycleFallback = Runnable {
        if (recyclePending) {
            recyclePending = false
            rebuildWebView(RECYCLE)
        }
    }

    /** Unplanned renderer deaths in the last RENDER_LOOP_WINDOW_MS (elapsedRealtime). */
    private val rendererDeaths = ArrayDeque<Long>()

    private var keepAwake = false
    private val keepAwakeExpiry = Runnable {
        keepAwake = false
        applyPlaybackActive()
        applyKeepAwake()
    }

    /**
     * The native player plays (its own `state` events: 1 playing, 3 buffering). The self-update
     * reads "something plays" from this or the page's keep-awake lease: the 2026-11-10 device run
     * found the page's single-video player never takes the lease, so the lease alone let the
     * installer's dialog pop over a native 4K video.
     */
    private var nativePlaying = false

    /** Something plays: the update's download slows down and its installer waits (ready). */
    private fun applyPlaybackActive() {
        if (this::selfUpdate.isInitialized) selfUpdate.setPlaybackActive(keepAwake || nativePlaying)
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            decorView().addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            webView.visibility = View.INVISIBLE
            view.setBackgroundColor(Color.BLACK)
            view.requestFocus()
            hideSystemBars()
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            decorView().removeView(view)
            customView = null
            webView.visibility = View.VISIBLE
            webView.requestFocus()
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            hideSystemBars()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Camera, microphone, MIDI, protected media ID: the TV shell never grants any of them.
            request.deny()
        }
    }

    private val viewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            // A main-frame navigation the page made itself (location.reload(), an update) leaves the
            // native player with nobody in control: the S3/S4 device run (2026-11-10) reloaded the
            // page mid-video and the video played on to its end. Hold it exactly as after a rebuilt
            // WebView (paused, released in 30 s unless the new page takes it over; ADR-0100 §7). The
            // rebuild's own load reaches here too; a second hold on the same video is harmless.
            nativePlayer.onWebViewRebuilt()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Sub-frame navigations (YouTube / Twitch embeds) are never blocked here.
            if (!request.isForMainFrame) return false
            return !isAllowedMainFrameUrl(request.url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                showError(request.url.toString(), if (BuildConfig.DEBUG) "${error.errorCode} ${error.description}" else null)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Debug builds only, and only for the configured dev host: a LAN mock serves the page
            // over a self-signed certificate so the WebView treats it as a secure context
            // (pairing needs crypto.subtle). Production is vistaks.com with a real certificate;
            // everything else keeps the default (cancel).
            val devHost = if (BuildConfig.DEBUG) BuildConfig.TV_URL.toUri().host?.lowercase() else null
            val errorHost = error.url?.toUri()?.host?.lowercase()
            val pageHost = view.url?.toUri()?.host?.lowercase()
            if (devHost != null && (errorHost == devHost || errorHost.isNullOrEmpty() || pageHost == devHost)) handler.proceed()
            else handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer crashed, was killed for memory, or was ended on purpose (a planned
            // recycle). Drop this WebView and build a new one in its place — the activity and the
            // app process live on — and let the page pick its session up again.
            if (view !== webView || webViewDestroyed) {
                // A WebView already replaced: nothing of ours is in it any more.
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
            val planned = recyclePending
            recyclePending = false
            mainHandler.removeCallbacks(recycleFallback)
            if (!planned) {
                val now = SystemClock.elapsedRealtime()
                rendererDeaths.addLast(now)
                while (rendererDeaths.isNotEmpty() && now - rendererDeaths.first() > RENDER_LOOP_WINDOW_MS) rendererDeaths.removeFirst()
                if (rendererDeaths.size > RENDER_LOOP_LIMIT) {
                    // A page that dies again and again is not resumed: the native error screen,
                    // and 重試 starts over from Home.
                    rebuildWebView(LAUNCH, load = false)
                    showError(BuildConfig.TV_URL, message = getString(R.string.render_loop_message), hint = getString(R.string.render_loop_hint))
                    return true
                }
            }
            rebuildWebView(if (planned) RECYCLE else RENDER_GONE)
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nativePlayer = NativePlayerHost(
            this,
            { event, json -> sendNative(event, json) },
            { transparent -> setNativeOverlay(transparent) },
        )
        if (nativePlayer.version() > 0) {
            // A window with alpha, set before the window is first added (a later format change
            // rebuilds the window surface): the page's transparent pixels over the SurfaceView's
            // hole must reach the compositor as transparent even when the full-screen WebView
            // leaves no transparent-region hint (ADR-0100 §1).
            window.setFormat(PixelFormat.TRANSLUCENT)
        }
        nativePlayer.attach(findViewById(R.id.root))
        selfUpdate = SelfUpdateHost(this) { event, json -> sendNative(event, json) }

        errorView = findViewById(R.id.error_view)
        retryButton = findViewById(R.id.retry_button)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        installWebView(findViewById(R.id.web_view))

        retryButton.setOnClickListener { retry() }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        hideSystemBars()

        launchReason = LAUNCH
        webView.loadUrl(BuildConfig.TV_URL)
        webView.requestFocus()
        // Debug only: an update test on a device checks now (am start ... --ez updateForceCheck true).
        selfUpdate.onCreate(forceCheck = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_UPDATE_FORCE_CHECK, false))
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun installWebView(view: WebView) {
        webView = view
        webViewDestroyed = false
        applyWebViewBackground(view)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            // ADR-0089 amendment 2026-11-08 (4K60 playback on the TV): an Android UA gets
            // YouTube's mobile embed player — a shorter quality ladder, no 60 fps rungs, setVolume
            // ignored. A desktop Chrome UA of the same engine version gets the full player; the
            // page still knows the shell through the VistaksTvHost bridge, never through the UA.
            userAgentString = desktopUserAgent(userAgentString)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // The page draws its own dark and light; never let WebView repaint it.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        view.webViewClient = viewClient
        view.webChromeClient = chromeClient
        view.addJavascriptInterface(TvHostBridge(), "VistaksTvHost")
        // Only a shell that can update itself has this object; the page shows its update entry by it.
        selfUpdate.bridge()?.let { view.addJavascriptInterface(it, UPDATE_BRIDGE) }
    }

    private fun destroyWebView() {
        if (webViewDestroyed) return
        webViewDestroyed = true
        val view = webView
        view.removeJavascriptInterface("VistaksTvHost")
        view.removeJavascriptInterface(UPDATE_BRIDGE)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    /**
     * A new WebView in the old one's place (a renderer gone, or a planned recycle). The page that
     * loads in it reads [reason] through `launchReason()` and resumes its session; LAUNCH lands on Home.
     */
    private fun rebuildWebView(reason: String, load: Boolean = true) {
        customView?.let { decorView().removeView(it) }
        customView = null
        customViewCallback = null
        destroyWebView()
        // The page that held the screen is gone; the new one takes a lease again when it plays.
        mainHandler.removeCallbacks(keepAwakeExpiry)
        keepAwake = false
        // The host holds the native player paused for the new page (onWebViewRebuilt below).
        nativePlaying = false
        applyPlaybackActive()
        applyKeepAwake()
        val fresh = WebView(this).apply {
            id = R.id.web_view
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // Right under the error screen, never at index 0: the native player's SurfaceView sits at
        // index 0 (ADR-0100 §1), and a WebView below it would hide the UI behind the video. With
        // only web_view and error_view in root (the old WebView already removed) this is index 0.
        val root = findViewById<FrameLayout>(R.id.root)
        root.addView(
            fresh,
            root.indexOfChild(errorView),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        installWebView(fresh)
        // The player outlives the WebView: it holds (paused) until the new page takes it over.
        // installWebView gave the new WebView the base colour the overlay calls for (transparent
        // while the surface still shows the held picture).
        nativePlayer.onWebViewRebuilt()
        logNativeLayers("webview rebuilt ($reason)")
        // Rebuilt in the background (the system killed the renderer of a stopped app): it starts
        // paused, and pauseTimers (process-wide) still holds until onStart.
        if (!activityResumed) fresh.onPause()
        if (!load) return
        errorView.visibility = View.GONE
        launchReason = reason
        fresh.loadUrl(BuildConfig.TV_URL)
        fresh.requestFocus()
    }

    /** The page asked for a planned recycle: end the renderer (a new process, all its memory back), else replace the WebView. */
    private fun requestRecycle() {
        if (webViewDestroyed || isFinishing || recyclePending) return
        recyclePending = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)
        ) {
            val renderer = WebViewCompat.getWebViewRenderProcess(webView)
            if (renderer != null && renderer.terminate()) {
                // onRenderProcessGone rebuilds; should it never come, rebuild anyway.
                mainHandler.postDelayed(recycleFallback, RECYCLE_FALLBACK_MS)
                return
            }
        }
        recyclePending = false
        rebuildWebView(RECYCLE)
    }

    override fun onStart() {
        super.onStart()
        // pauseTimers is process-wide: timers come back before the page hears 'resume'.
        if (!webViewDestroyed) webView.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        applyKeepAwake()
        if (!webViewDestroyed) webView.onResume()
        hideSystemBars()
        sendLifecycle("resume")
        selfUpdate.onResume()
    }

    override fun onPause() {
        activityResumed = false
        // The page first (its players pause, the revision socket closes, hidden videos lock),
        // then the WebView, which on its own does not pause JavaScript.
        sendLifecycle("pause")
        // The decoder and the surface go; the host hides the surface and the WebView turns opaque.
        nativePlayer.onHostPause()
        applyKeepAwake()
        if (!webViewDestroyed) webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Fully out of sight (HOME, another app, the screen off): no page timer runs until onStart.
        if (!webViewDestroyed) webView.pauseTimers()
        super.onStop()
    }

    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_*: still delivered to a foreground app below API 34.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // RUNNING_LOW and worse while in front, UI_HIDDEN and the background levels after it: the
        // page drops what it can (and recycles itself on RUNNING_CRITICAL when nothing plays).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) sendLifecycle("trim", level)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        nativePlayer.release()
        selfUpdate.release()
        destroyWebView()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // RestrictedApi: known lint false positive on overriding ComponentActivity.dispatchKeyEvent.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val command = mediaCommandFor(event.keyCode)
        if (command != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val repeatable = command == "rewind" || command == "fastforward"
                if (event.repeatCount == 0 || repeatable) sendMediaCommand(command)
            }
            // Consume both DOWN and UP so the system never acts on media keys behind the page.
            return true
        }
        // D-pad, Enter and everything else: untouched, the WebView turns them into DOM key events.
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        if (customView != null) {
            chromeClient.onHideCustomView()
            return
        }
        if (webViewDestroyed || errorView.isVisible) {
            moveTaskToBack(true)
            return
        }
        // The page owns navigation: ask it first, never fall back to WebView history. With nothing
        // deeper open it shows 「再按一次返回鍵離開」 and calls exit() on the second press.
        webView.evaluateJavascript(BACK_SCRIPT) { result ->
            if (result != "1" && !isFinishing) moveTaskToBack(true)
        }
    }

    private fun sendMediaCommand(command: String) {
        if (webViewDestroyed) return
        webView.evaluateJavascript(
            "(function(){try{return window.VistaksTv&&window.VistaksTv.media('$command')?1:0}catch(e){return 0}})()",
            null,
        )
    }

    /** `window.VistaksTv.lifecycle(event[, level])`; nothing when the WebView is gone or the page has no bridge yet. */
    private fun sendLifecycle(event: String, level: Int? = null) {
        if (webViewDestroyed) return
        val argument = if (level == null) "" else ",$level"
        webView.evaluateJavascript(
            "(function(){try{return window.VistaksTv&&window.VistaksTv.lifecycle&&window.VistaksTv.lifecycle('$event'$argument)?1:0}catch(e){return 0}})()",
            null,
        )
    }

    /** `window.VistaksTv.native(event, json)` (ADR-0100 §3); main thread. Quiet when the page has no such hook. */
    private fun sendNative(event: String, json: String) {
        if (event == "state") noteNativeState(json)
        // The host is built (and could emit) before installWebView runs.
        if (!this::webView.isInitialized || webViewDestroyed) return
        webView.evaluateJavascript(
            "(function(){try{return window.VistaksTv&&window.VistaksTv.native&&window.VistaksTv.native(" +
                JSONObject.quote(event) + "," + JSONObject.quote(json) + ")?1:0}catch(e){return 0}})()",
            null,
        )
    }

    /**
     * The WebView's two native backgrounds (ADR-0100 §1 layers 2 and 3; layer 1 is the page's CSS):
     * its View background drawable is always none (activity_main.xml sets none either, so the
     * inflated and the rebuilt WebView behave alike), and its content base colour is #050505, or
     * transparent while the native surface shows under it.
     */
    private fun applyWebViewBackground(view: WebView) {
        view.background = null
        view.setBackgroundColor(if (nativeOverlay) Color.TRANSPARENT else WEB_BASE_COLOR)
    }

    /** The native host's surface showed (true) or went (false); main thread. */
    private fun setNativeOverlay(transparent: Boolean) {
        nativeOverlay = transparent
        if (this::webView.isInitialized && !webViewDestroyed) applyWebViewBackground(webView)
        logNativeLayers(if (transparent) "overlay on" else "overlay off")
    }

    /**
     * One logcat line (tag VistaksNative) after the next layout: @id/root's children bottom to top
     * with visibility and bounds, the WebView's base colour and drawable, the window's pixel format.
     * The order must be surface < web_view < error_view; anything else is logged as an error.
     */
    private fun logNativeLayers(reason: String) {
        val root = findViewById<FrameLayout>(R.id.root) ?: return
        root.post {
            val children = (0 until root.childCount).joinToString(" | ") { i ->
                val child = root.getChildAt(i)
                val name = try {
                    if (child.id != View.NO_ID) resources.getResourceEntryName(child.id) else "-"
                } catch (_: Exception) {
                    "?"
                }
                val shown = when (child.visibility) {
                    View.VISIBLE -> "VISIBLE"
                    View.INVISIBLE -> "INVISIBLE"
                    else -> "GONE"
                }
                "$i:${child.javaClass.simpleName}#$name $shown ${child.left},${child.top} ${child.width}x${child.height}"
            }
            val surfaceIndex = (0 until root.childCount).firstOrNull { root.getChildAt(it) is SurfaceView } ?: -1
            val webIndex = if (this::webView.isInitialized && !webViewDestroyed) root.indexOfChild(webView) else -1
            val errorIndex = root.indexOfChild(errorView)
            val line = "$reason: webBase=${if (nativeOverlay) "transparent" else "#050505"}" +
                " webDrawable=${if (webIndex >= 0) (webView.background != null).toString() else "n/a"}" +
                " webVisibility=${if (webIndex >= 0) webView.visibility else -1}" +
                " windowFormat=${window.attributes.format} root=${root.width}x${root.height} [$children]"
            if (surfaceIndex >= 0 && (webIndex in 0..surfaceIndex || errorIndex in 0..surfaceIndex)) {
                Log.e(NATIVE_TAG, "$line: the WebView or the error screen is UNDER the native surface")
            } else {
                Log.i(NATIVE_TAG, line)
            }
        }
    }

    /** The lease (from the page) and the activity in front: both, or the screen is the system's. */
    private fun applyKeepAwake() {
        if (keepAwake && activityResumed) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The native player's `state` event (main thread): playing or buffering counts as playing. */
    private fun noteNativeState(json: String) {
        val state = try { JSONObject(json).optInt("state", Int.MIN_VALUE) } catch (_: Exception) { Int.MIN_VALUE }
        if (state == Int.MIN_VALUE) return
        nativePlaying = state == 1 || state == 3
        applyPlaybackActive()
    }

    private fun setKeepAwake(on: Boolean) {
        mainHandler.removeCallbacks(keepAwakeExpiry)
        keepAwake = on
        // The lease means something plays (as does the native player's own state).
        applyPlaybackActive()
        // A lease nobody renews (the page died, or forgot) ends by itself.
        if (on) mainHandler.postDelayed(keepAwakeExpiry, KEEP_AWAKE_LEASE_MS)
        applyKeepAwake()
    }

    private fun showError(
        url: String,
        detail: String? = null,
        message: String = getString(R.string.error_message),
        hint: String = getString(R.string.error_hint),
    ) {
        findViewById<android.widget.TextView>(R.id.error_message).text =
            if (detail == null) message else "$message\n$detail\n$url"
        findViewById<android.widget.TextView>(R.id.error_hint).text = hint
        failedUrl = url
        webView.visibility = View.INVISIBLE
        errorView.visibility = View.VISIBLE
        retryButton.requestFocus()
    }

    private fun retry() {
        val target = failedUrl ?: BuildConfig.TV_URL
        failedUrl = null
        rendererDeaths.clear()
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        launchReason = LAUNCH
        webView.loadUrl(target)
        webView.requestFocus()
    }

    private fun isAllowedMainFrameUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (scheme == "https" && (host == PROD_HOST || host.endsWith(".$PROD_HOST"))) return true
        if (BuildConfig.DEBUG && (scheme == "http" || scheme == "https")) {
            val devHost = BuildConfig.TV_URL.toUri().host?.lowercase()
            if (devHost != null && host == devHost) return true
        }
        return false
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun decorView(): FrameLayout = window.decorView as FrameLayout

    /** Exposed to the page as `window.VistaksTvHost`. Methods run on a WebView binder thread. */
    inner class TvHostBridge {
        @JavascriptInterface
        fun platform(): String {
            val uiMode = getSystemService(UiModeManager::class.java)
            return if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
                "android-tv"
            } else {
                "android"
            }
        }

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun deviceName(): String = (Build.MODEL ?: "").trim().take(80)

        @JavascriptInterface
        fun exit() {
            runOnUiThread { finishAndRemoveTask() }
        }

        /** The page holds (true) or releases (false) the screen; a hold not renewed within KEEP_AWAKE_LEASE_MS ends. */
        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread { setKeepAwake(on) }
        }

        /** Why this page load happened ("launch", "render-gone", "recycle"); answered once, then "launch". */
        @JavascriptInterface
        fun launchReason(): String {
            val reason = launchReason
            launchReason = LAUNCH
            return reason
        }

        /** A planned recycle: a new renderer and a new WebView, the page resumes its session. */
        @JavascriptInterface
        fun recycle() {
            runOnUiThread { requestRecycle() }
        }

        /** 0: no native player, the page plays YouTube in an iframe (play); 1: sideload (ADR-0100 §3). */
        @JavascriptInterface
        fun nativePlayerVersion(): Int = nativePlayer.version()

        // --- ADR-0100 §3: the native player. Every build flavor answers; play's host does nothing
        // (version 0, "{}"). The host parses the JSON on this binder thread and touches the player
        // and the views on the main thread; nativeState / nativeStats read @Volatile snapshots.
        // The WebView is never hidden: the host's overlay callback turns it transparent while the
        // surface shows and opaque again when it goes (setNativeOverlay).

        /**
         * `{ videoId, startSeconds, maxHeight, maxFps, hdr, autoplay, muted, rate }`, plus (S3/S4)
         * `matchFrameRate` (bool, default false) and optional `captions` (bool), `prerollMs`,
         * `resumePrerollMs`.
         */
        @JavascriptInterface
        fun nativeLoad(json: String) = nativePlayer.load(json)

        /** `{ cmd, value }`: play, pause, seek (s), rate, mute, unmute, volume (0–100), captions (1/0, bool, or a language tag), stop. */
        @JavascriptInterface
        fun nativeCommand(json: String) = nativePlayer.command(json)

        /** The video box in CSS px: `{ x, y, width, height }` (innerWidth / innerHeight optional). */
        @JavascriptInterface
        fun nativeRect(json: String) = nativePlayer.rect(json)

        /** `{ videoId, state, currentTime, duration }`; a read only (the page's next load / command / rect takes over after a rebuild). */
        @JavascriptInterface
        fun nativeState(): String = nativePlayer.state()

        /** `{ width, height, fps, codec, decoder, hdr, dropped, rendered, bitrateEstimate, … }`. */
        @JavascriptInterface
        fun nativeStats(): String = nativePlayer.stats()

        /** Player, decoder and surface released; the WebView opaque again. */
        @JavascriptInterface
        fun nativeRelease() {
            nativePlayer.release()
            // A release sends no state: nothing plays from here.
            runOnUiThread { nativePlaying = false; applyPlaybackActive() }
        }

        /**
         * ADR-0100 §6 / S5: the APK's license notice, `{ license, sourceUrl, components: [{ name,
         * version, license, url }] }` from the sideload assets' NOTICE.json; "{}" for play (no GPL
         * code) or when the APK carries none. The page's About shows it only when it has content.
         */
        @JavascriptInterface
        fun licenseInfo(): String = nativePlayer.licenseInfo()
    }

    /** The WebView's own Chrome version under a desktop Linux Chrome UA, with the shell's marker kept. */
    private fun desktopUserAgent(default: String): String {
        val chrome = Regex("Chrome/(\\d+(?:\\.\\d+)*)").find(default)?.groupValues?.get(1) ?: FALLBACK_CHROME_VERSION
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36 VistaksTV/${BuildConfig.VERSION_NAME}"
    }

    private companion object {
        /** When the system WebView's UA names no Chrome version (never seen; a safe, current-enough default). */
        const val FALLBACK_CHROME_VERSION = "124.0.0.0"
        const val PROD_HOST = "vistaks.com"

        /** The WebView's opaque base colour (@color/vistaks_bg, the page's own dark). */
        val WEB_BASE_COLOR = Color.rgb(5, 5, 5)

        /** The native player's logcat tag (StreamResolver.TAG in the sideload flavor). */
        const val NATIVE_TAG = "VistaksNative"

        /** The self-update's bridge object (sideload with a manifest only). */
        const val UPDATE_BRIDGE = "VistaksTvUpdate"
        const val EXTRA_UPDATE_FORCE_CHECK = "updateForceCheck"

        const val LAUNCH = "launch"
        const val RENDER_GONE = "render-gone"
        const val RECYCLE = "recycle"

        /** More unplanned renderer deaths than this within the window: the error screen, not another resume. */
        const val RENDER_LOOP_LIMIT = 3
        const val RENDER_LOOP_WINDOW_MS = 10 * 60 * 1000L

        /** The page renews every 4 minutes while something plays. */
        const val KEEP_AWAKE_LEASE_MS = 10 * 60 * 1000L
        const val RECYCLE_FALLBACK_MS = 3_000L

        const val BACK_SCRIPT =
            "(function(){try{return window.VistaksTv&&window.VistaksTv.back()?1:0}catch(e){return 0}})()"

        fun mediaCommandFor(keyCode: Int): String? = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "playpause"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "fastforward"
            KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
            else -> null
        }
    }
}
