package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.widget.Button

class DashboardActivity : AppCompatActivity() {

    private enum class Mode { IMMICH_FRAME, HA_DASHBOARD }
    private var currentMode = Mode.IMMICH_FRAME

    private lateinit var webView: WebView
    private lateinit var drawer: DrawerLayout
    private lateinit var prefs: Prefs

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        prefs = Prefs(this)
        BridgeService.start(this)

        // Hold the screen awake while the dashboard is up. Portal's display
        // timeout is what starts the idle cascade (screen off + launcher
        // asserting HOME over us). HA's Screen switch can still sleep it —
        // this only blocks the timeout path, like a playing video does.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersive()   // kiosk: hide the system nav/status bars

        drawer = findViewById(R.id.drawer_layout)
        webView = findViewById(R.id.web_view)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Grant media permissions so HA calls work inside the WebView
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed() // Accept self-signed certs for local HA / ImmichFrame
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showPlaceholder("Failed to load — check the URL in Settings.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The WebView renderer died (usually OOM on a long-running
                // dashboard). Rebuild the activity instead of crashing the app.
                android.util.Log.w("PortalHA", "WebView renderer gone (crash=${detail.didCrash()}) — recreating")
                recreate()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                // intent:// and other app schemes — WebView drops these silently,
                // so hand them to Android (lets HA cards launch Portal apps).
                runCatching {
                    val intent =
                        if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        else Intent(Intent.ACTION_VIEW, request.url)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }.onFailure {
                    android.util.Log.w("PortalHA", "Could not launch $url: ${it.message}")
                }
                return true
            }
        }

        // Default to ImmichFrame mode if it is configured, otherwise HA dashboard.
        currentMode = if (prefs.immichFrameEnabled && prefs.immichFrameUrl.isNotBlank()) {
            Mode.IMMICH_FRAME
        } else {
            Mode.HA_DASHBOARD
        }

        // Restore mode if the activity is being recreated (e.g. after renderer crash).
        savedInstanceState?.getString("mode")?.let {
            currentMode = if (it == "HA_DASHBOARD") Mode.HA_DASHBOARD else Mode.IMMICH_FRAME
        }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            drawer.closeDrawers()
            startActivity(Intent(this, MainActivity::class.java))
        }

        // Toggle between ImmichFrame and HA dashboard
        findViewById<Button>(R.id.btn_toggle_mode).setOnClickListener {
            drawer.closeDrawers()
            currentMode = if (currentMode == Mode.IMMICH_FRAME) Mode.HA_DASHBOARD else Mode.IMMICH_FRAME
            updateModeButton()
            loadCurrentMode()
        }

        findViewById<Button>(R.id.btn_reload).setOnClickListener {
            drawer.closeDrawers()
            loadCurrentMode()
        }

        updateModeButton()
        loadCurrentMode()

        // First run (nothing configured yet): drop straight into Settings.
        val immichReady = prefs.immichFrameEnabled && prefs.immichFrameUrl.isNotBlank()
        val haReady = prefs.haUrl.isNotBlank()
        if (savedInstanceState == null && !immichReady && !haReady) {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mode", currentMode.name)
    }

    // Show the toggle button only when there is something to switch to.
    private fun updateModeButton() {
        val btn = findViewById<Button>(R.id.btn_toggle_mode)
        when (currentMode) {
            Mode.IMMICH_FRAME -> {
                val hasHa = prefs.haUrl.isNotBlank()
                btn.visibility = if (hasHa) View.VISIBLE else View.GONE
                btn.text = "HA Dashboard"
            }
            Mode.HA_DASHBOARD -> {
                val hasImmich = prefs.immichFrameEnabled && prefs.immichFrameUrl.isNotBlank()
                btn.visibility = if (hasImmich) View.VISIBLE else View.GONE
                btn.text = "Photo Frame"
            }
        }
    }

    private fun loadCurrentMode() {
        when (currentMode) {
            Mode.IMMICH_FRAME -> {
                val url = prefs.immichFrameUrl.trim()
                if (url.isBlank()) {
                    showPlaceholder("ImmichFrame URL not set.\nSwipe from the left edge to open Settings.")
                } else {
                    webView.loadUrl(normalise(url))
                }
            }
            Mode.HA_DASHBOARD -> {
                val url = prefs.haUrl.trim()
                if (url.isBlank()) {
                    showPlaceholder("Home Assistant URL not set.\nSwipe from the left edge to open Settings.")
                } else {
                    webView.loadUrl(normalise(url))
                }
            }
        }
    }

    // Hide the status/navigation bars for a full-screen kiosk view. STICKY so a
    // swipe only reveals them briefly, then they auto-hide. (Deprecated flags, but
    // these are the working API on the Portal's Android 9/10.)
    @Suppress("DEPRECATION")
    private fun enableImmersive() {
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // Immersive-sticky drops after focus changes (dialogs, the drawer, app
    // switches) — re-assert it whenever we regain focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    override fun onResume() {
        super.onResume()
        enableImmersive()
        // Re-acquire the camera if another app (e.g. the Portal launcher) took
        // it while we were in the background.
        BridgeService.ensureCamera(this)
        updateModeButton()
        // Reload if the active mode's URL changed while we were in Settings.
        val targetUrl = when (currentMode) {
            Mode.IMMICH_FRAME -> prefs.immichFrameUrl
            Mode.HA_DASHBOARD -> prefs.haUrl
        }
        val current = webView.url ?: ""
        if (targetUrl.isNotEmpty() && !current.startsWith(normalise(targetUrl).trimEnd('/'))) {
            loadCurrentMode()
        }
    }

    private fun normalise(url: String) = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> "http://$url"
    }

    private fun showPlaceholder(message: String) {
        // Must use loadDataWithBaseURL, not loadData: loadData treats the payload
        // like a URL and chokes on the '#' in hex colors, rendering a blank page.
        webView.loadDataWithBaseURL(
            null,
            """<html><body style="background:#1c1c1c;color:#ccc;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;
               height:100vh;margin:0;text-align:center;padding:40px;box-sizing:border-box;">
               <div><h2 style="color:#fff">Portal HA Bridge</h2><p>$message</p></div>
               </body></html>""",
            "text/html", "UTF-8", null
        )
    }

    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }
}
