package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.widget.Button

class DashboardActivity : AppCompatActivity() {

    private var currentMode = DashboardMode.HA_DASHBOARD
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
                val allowed = currentMode == DashboardMode.HA_DASHBOARD &&
                    isAllowedMediaOrigin(request.origin.toString(), prefs.haUrl)
                if (allowed) request.grant(request.resources) else request.deny()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                if (isLocalDashboardUrl(error.url ?: "")) handler.proceed() else handler.cancel()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showPlaceholder("Failed to load — check the URL in Settings.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The WebView renderer died (usually OOM on a long-running
                // dashboard). Rebuild the activity instead of crashing the app.
                android.util.Log.w("PortalHA", "WebView renderer gone (crash=${detail.didCrash()}) — recreating dashboard")
                recreate()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                if (!isAllowedExternalNavigation(url)) return true
                runCatching {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }.onFailure {
                    android.util.Log.w("PortalHA", "Could not launch $url: ${it.message}")
                }
                return true
            }
        }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            drawer.closeDrawers()
            startActivity(Intent(this, MainActivity::class.java))
        }

        currentMode = initialDashboardMode(
            immichEnabled = prefs.immichFrameEnabled,
            immichUrl = prefs.immichFrameUrl
        )

        findViewById<Button>(R.id.btn_toggle_mode).setOnClickListener {
            drawer.closeDrawers()
            currentMode = if (currentMode == DashboardMode.IMMICH_FRAME) {
                DashboardMode.HA_DASHBOARD
            } else {
                DashboardMode.IMMICH_FRAME
            }
            updateModeButton()
            loadDashboard()
        }

        findViewById<Button>(R.id.btn_reload).setOnClickListener {
            drawer.closeDrawers()
            loadDashboard()
        }

        updateModeButton()
        loadDashboard()

        // First run (nothing configured yet): drop straight into Settings rather
        // than showing the empty dashboard placeholder. Only on a genuine fresh
        // create — savedInstanceState guards against config-change recreation,
        // and onCreate (not onResume) means backing out of Settings won't loop.
        val immichReady = prefs.immichFrameEnabled && prefs.immichFrameUrl.isNotBlank()
        if (savedInstanceState == null && prefs.haUrl.isBlank() && !immichReady) {
            startActivity(Intent(this, MainActivity::class.java))
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
        // Reload if URL changed in settings
        if (currentMode == DashboardMode.IMMICH_FRAME &&
            (!prefs.immichFrameEnabled || prefs.immichFrameUrl.isBlank())
        ) {
            currentMode = DashboardMode.HA_DASHBOARD
        }
        updateModeButton()
        val url = activeUrl()
        val current = webView.url ?: ""
        if (url.isNotEmpty() && !current.startsWith(normaliseDashboardUrl(url).trimEnd('/'))) {
            loadDashboard()
        }
    }

    private fun loadDashboard() {
        val url = activeUrl().trim()
        if (url.isEmpty()) {
            val target = if (currentMode == DashboardMode.IMMICH_FRAME) "ImmichFrame" else "Home Assistant"
            showPlaceholder("Swipe from the left edge to open Settings\nand enter your $target URL.")
        } else {
            webView.loadUrl(normaliseDashboardUrl(url))
        }
    }

    private fun activeUrl() = when (currentMode) {
        DashboardMode.IMMICH_FRAME -> prefs.immichFrameUrl
        DashboardMode.HA_DASHBOARD -> prefs.haUrl
    }

    private fun updateModeButton() {
        val button = findViewById<Button>(R.id.btn_toggle_mode)
        when (currentMode) {
            DashboardMode.IMMICH_FRAME -> {
                button.text = "HA Dashboard"
                button.visibility = if (prefs.haUrl.isNotBlank()) View.VISIBLE else View.GONE
            }
            DashboardMode.HA_DASHBOARD -> {
                button.text = "Photo Frame"
                button.visibility = if (prefs.immichFrameEnabled && prefs.immichFrameUrl.isNotBlank()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }
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
