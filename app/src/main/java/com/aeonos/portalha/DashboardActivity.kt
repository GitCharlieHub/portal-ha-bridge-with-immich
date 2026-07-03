package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.widget.Button
import java.net.URI

class DashboardActivity : AppCompatActivity() {

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
                // ImmichFrame is a photo viewer — deny all camera/mic requests.
                android.util.Log.w("PortalHA",
                    "Denied WebView permission request from ${request.origin}")
                request.deny()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                if (isLocalUrl(error.url ?: "")) handler.proceed() else handler.cancel()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showPlaceholder("Failed to load — check the ImmichFrame URL in Settings.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                android.util.Log.w("PortalHA", "WebView renderer gone (crash=${detail.didCrash()}) — recreating")
                recreate()
                return true
            }
        }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            drawer.closeDrawers()
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<Button>(R.id.btn_reload).setOnClickListener {
            drawer.closeDrawers()
            loadImmichFrame()
        }

        loadImmichFrame()

        // First run (nothing configured yet): drop straight into Settings.
        if (savedInstanceState == null && prefs.immichFrameUrl.isBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    private fun loadImmichFrame() {
        val url = prefs.immichFrameUrl.trim()
        if (url.isBlank()) {
            showPlaceholder("ImmichFrame URL not set.\nSwipe from the left edge to open Settings.")
        } else {
            webView.loadUrl(normalise(url))
        }
    }

    // True for RFC 1918, link-local, localhost, and .local mDNS addresses.
    // Used to gate handler.proceed() on SSL errors — we accept self-signed
    // certs from local servers but not from anything that could be the internet.
    private fun isLocalUrl(url: String): Boolean = runCatching {
        val host = URI(url).host ?: return false
        host == "localhost" ||
        host == "127.0.0.1" ||
        host == "::1" ||
        host.endsWith(".local") ||
        host.matches(Regex("""^10\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) ||
        host.matches(Regex("""^172\.(1[6-9]|2\d|30|31)\.\d{1,3}\.\d{1,3}$""")) ||
        host.matches(Regex("""^192\.168\.\d{1,3}\.\d{1,3}$""")) ||
        host.matches(Regex("""^169\.254\.\d{1,3}\.\d{1,3}$"""))
    }.getOrDefault(false)

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
        BridgeService.ensureCamera(this)
        // Reload if the ImmichFrame URL changed while we were in Settings.
        val targetUrl = prefs.immichFrameUrl.trim()
        val current = webView.url ?: ""
        if (targetUrl.isNotEmpty() && !current.startsWith(normalise(targetUrl).trimEnd('/'))) {
            loadImmichFrame()
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
