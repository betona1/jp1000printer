package com.betona.printdriver

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Kiosk-style WebView browser — launcher activity.
 * Handles website's window.open('','PRINT') popup for printing.
 */
class WebPrintActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebPrint"
        const val EXTRA_SCREEN_OFF_NOW = "screen_off_now"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCutMode: ImageButton
    private lateinit var btnPower: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var txtClock: TextView
    private lateinit var txtSchedule: TextView
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            txtClock.text = SimpleDateFormat("yyyy-MM-dd (E) HH:mm", Locale.KOREA).format(Date())
            clockHandler.postDelayed(this, 30_000L)
        }
    }
    private var lastLoadedSchoolUrl: String? = null
    private var isPrinting = false
    private var shouldClearHistory = false

    // Night save mode: 3-min inactivity → screen off
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val inactivityTimeout = 3 * 60 * 1000L  // 3 minutes
    private val inactivityRunnable = Runnable {
        if (AppPrefs.isNightSaveMode(this) && !AppPrefs.isDaytime(this)) {
            turnScreenOff()
        }
    }

    // Hidden WebView for printing captured HTML
    private var printWebView: WebView? = null

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(ScreenOffReceiver.EXTRA_SCREEN_OFF_COUNTDOWN, false) == true) {
            showScreenOffCountdown()
        }
        if (intent?.getBooleanExtra(EXTRA_SCREEN_OFF_NOW, false) == true) {
            turnScreenOff()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_print)

        // Auto-enable PrintService (gets disabled on app reinstall)
        enablePrintService()

        // Keep screen on during normal operation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Disable all touch sounds (Android 7 plays click sound on every tap)
        val rootView = findViewById<View>(android.R.id.content)
        disableSoundRecursive(rootView)

        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)
        webView.isSoundEffectsEnabled = false

        // Toolbar buttons
        findViewById<ImageButton>(R.id.btnHome).apply {
            setOnClickListener { goHome() }
            setOnLongClickListener {
                AppPrefs.setLandscape(this@WebPrintActivity, false)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                true
            }
        }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<ImageButton>(R.id.btnLadderGame).setOnClickListener {
            startActivity(Intent(this, LadderGameActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnBingoGame).setOnClickListener {
            startActivity(Intent(this, BingoGameActivity::class.java))
        }
        btnPower = findViewById(R.id.btnPower)
        btnRotate = findViewById(R.id.btnRotate)
        btnCutMode = findViewById(R.id.btnCutMode)
        txtClock = findViewById(R.id.txtClock)
        txtSchedule = findViewById(R.id.txtSchedule)
        btnCutMode.setOnClickListener { toggleCutMode() }
        btnPower.setOnClickListener { confirmScreenOff() }
        btnRotate.setOnClickListener { toggleRotation() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // Apply saved orientation
        requestedOrientation = if (AppPrefs.isLandscape(this))
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT

        // Hide system status bar (fullscreen immersive)
        hideSystemBars()

        // Software rendering for A40i GPU compatibility (EGL errors with Chrome 113)
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = if (AppPrefs.isMobileMode(this@WebPrintActivity)) MOBILE_UA else DESKTOP_UA
            javaScriptCanOpenWindowsAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            // Aggressive caching: show cached page instantly, fetch from network only if no cache
            cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            databaseEnabled = true
        }

        // JS bridge: capture window.open('','PRINT') HTML and print natively
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun printHtml(html: String) {
                Log.d(TAG, "printHtml called, html length=${html.length}")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) loadAndPrintHtml(html)
                }
            }

            @JavascriptInterface
            fun print() {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) printPage()
                }
            }
        }, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (shouldClearHistory) {
                    view?.clearHistory()
                    shouldClearHistory = false
                    Log.d(TAG, "Home URL resolved: ${view?.url}")
                }
                // Print button always enabled (cut mode selector)
                // Fix select element styling for older WebView (v83 renders wavy borders)
                view?.evaluateJavascript("""
                    (function() {
                        var style = document.createElement('style');
                        style.textContent = 'select { -webkit-appearance: none !important; appearance: none !important; border: 1px solid #aaa !important; border-image: none !important; border-radius: 4px !important; background: #fff url("data:image/svg+xml,%3Csvg xmlns=%27http://www.w3.org/2000/svg%27 width=%2712%27 height=%278%27%3E%3Cpath d=%27M0 0l6 8 6-8z%27 fill=%27%23666%27/%3E%3C/svg%3E") no-repeat right 8px center !important; background-size: 12px 8px !important; padding: 4px 28px 4px 8px !important; outline: none !important; }';
                        document.head.appendChild(style);
                    })();
                """.trimIndent(), null)
                // Override window.open to capture print HTML, override window.print
                view?.evaluateJavascript("""
                    (function() {
                        window.print = function() { NativeBridge.print(); };
                        var _origOpen = window.open;
                        window.open = function(url, target, features) {
                            if (url && url.length > 0) {
                                window.location.href = url;
                                return null;
                            }
                            var _html = '';
                            return {
                                document: {
                                    write: function(s) { _html += s; },
                                    writeln: function(s) { _html += s + '\n'; },
                                    close: function() {},
                                    open: function() { _html = ''; },
                                    title: '', charset: 'utf-8',
                                    getElementsByTagName: function() { return []; }
                                },
                                print: function() { NativeBridge.printHtml(_html); },
                                close: function() {},
                                focus: function() {},
                                blur: function() {},
                                location: { href: '' },
                                navigator: window.navigator
                            };
                        };
                    })();
                """.trimIndent(), null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) {
                    ProgressBar.VISIBLE
                } else {
                    ProgressBar.GONE
                }
            }
        }

        // Handle screen-off countdown if launched from ScreenOffReceiver
        if (intent?.getBooleanExtra(ScreenOffReceiver.EXTRA_SCREEN_OFF_COUNTDOWN, false) == true) {
            Handler(Looper.getMainLooper()).post { showScreenOffCountdown() }
        }
    }

    // ── Print captured HTML from window.open('','PRINT') ──────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadAndPrintHtml(html: String) {
        // Create a hidden WebView, load the captured HTML, then print
        val pv = WebView(this)
        pv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        pv.settings.javaScriptEnabled = true

        // Attach to layout (needed for rendering)
        val container = webView.parent as? ViewGroup
        pv.layoutParams = ViewGroup.LayoutParams(1, 1)
        container?.addView(pv)
        printWebView = pv

        pv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "Print HTML loaded, starting print")
                isPrinting = true
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                val adapter = pv.createPrintDocumentAdapter("인쇄")
                pm.print("인쇄", adapter, null)
            }
        }

        pv.loadDataWithBaseURL(
            AppPrefs.getSchoolUrl(this), html, "text/html", "utf-8", null
        )
    }

    private fun destroyPrintWebView() {
        printWebView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        printWebView = null
    }

    // ── Navigation ────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Check if we should turn screen off (from admin page)
        if (intent?.getBooleanExtra(EXTRA_SCREEN_OFF_NOW, false) == true) {
            intent.removeExtra(EXTRA_SCREEN_OFF_NOW)
            turnScreenOff()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Apply UA mode (may have changed in admin settings)
        val newUa = if (AppPrefs.isMobileMode(this)) MOBILE_UA else DESKTOP_UA
        if (webView.settings.userAgentString != newUa) {
            webView.settings.userAgentString = newUa
            lastLoadedSchoolUrl = null  // Force reload with new UA
        }

        btnPower.visibility = if (AppPrefs.getShowPowerButton(this)) View.VISIBLE else View.GONE
        btnRotate.visibility = if (AppPrefs.getShowRotateButton(this)) View.VISIBLE else View.GONE
        val showGames = AppPrefs.getShowGames(this)
        findViewById<ImageButton>(R.id.btnLadderGame).visibility = if (showGames) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.btnBingoGame).visibility = if (showGames) View.VISIBLE else View.GONE
        updateCutModeIcon(AppPrefs.isFullCut(this))

        // Clock always visible (right side, left of menu)
        txtClock.visibility = View.VISIBLE
        clockRunnable.run()

        // Schedule (left side, right of forward arrow)
        if (AppPrefs.getShowSchedule(this)) {
            val schedText = AppPrefs.getTodayScheduleText(this)
            if (schedText != null) {
                txtSchedule.text = schedText
                txtSchedule.visibility = View.VISIBLE
            } else {
                txtSchedule.visibility = View.GONE
            }
        } else {
            txtSchedule.visibility = View.GONE
        }

        // Night save mode inactivity timer
        resetInactivityTimer()

        // Orientation
        requestedOrientation = if (AppPrefs.isLandscape(this))
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT

        if (isPrinting) {
            isPrinting = false
            destroyPrintWebView()
            goHome()
            return
        }
        val schoolUrl = AppPrefs.getSchoolUrl(this)
        if (lastLoadedSchoolUrl != schoolUrl) {
            lastLoadedSchoolUrl = schoolUrl
            shouldClearHistory = true
            if (!hasNetwork()) {
                showNoWifiWarning(schoolUrl)
            } else {
                webView.loadUrl(schoolUrl)
            }
        }
    }

    private fun goHome() {
        val url = AppPrefs.getSchoolUrl(this)
        lastLoadedSchoolUrl = url
        shouldClearHistory = true
        webView.loadUrl(url)
    }

    /** Called by JS bridge when website triggers window.print() */
    private fun printPage() {
        isPrinting = true
        val title = webView.title ?: "웹 페이지"
        Log.d(TAG, "Printing: $title")
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val adapter = webView.createPrintDocumentAdapter(title)
        printManager.print(title, adapter, null)
    }

    /** Toggle cut mode on each tap — syncs with admin settings */
    private fun toggleCutMode() {
        val nowFull = AppPrefs.isFullCut(this)
        val newFull = !nowFull
        AppPrefs.setCutMode(this, if (newFull) "full" else "partial")
        updateCutModeIcon(newFull)
    }

    private fun updateCutModeIcon(fullCut: Boolean) {
        btnCutMode.setImageResource(
            if (fullCut) R.drawable.ic_cut_full else R.drawable.ic_cut_partial
        )
    }

    /** Manual screen off — confirm then turn off screen */
    private fun confirmScreenOff() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("화면 끄기")
            .setMessage("화면을 끄시겠습니까?\n(터치하면 다시 켜집니다)")
            .setPositiveButton("끄기") { _, _ ->
                turnScreenOff()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private var screenOffOverlay: View? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun turnScreenOff() {
        if (screenOffOverlay != null) return  // already off
        // Black overlay covers entire screen, touch to wake
        val overlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    wakeScreen()
                }
                true
            }
        }
        // Add overlay on top of everything
        val root = window.decorView as ViewGroup
        root.addView(overlay)
        screenOffOverlay = overlay
        // Set brightness to minimum
        val params = window.attributes
        params.screenBrightness = 0.0f
        window.attributes = params
        Log.i(TAG, "Screen off: black overlay + brightness 0")
        PowerScheduleManager.scheduleNext(this)
    }

    private fun wakeScreen() {
        screenOffOverlay?.let {
            val root = window.decorView as ViewGroup
            root.removeView(it)
        }
        screenOffOverlay = null
        // Restore brightness to system default
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "Screen wake: overlay removed, brightness restored")
    }

    private fun showScreenOffCountdown() {
        if (isFinishing || isDestroyed) return
        var secondsLeft = 60
        val dialog = AlertDialog.Builder(this)
            .setTitle("화면 꺼짐 예정")
            .setMessage("설정시간이 되어\n60초 후 화면이 꺼집니다\n(터치하면 다시 켜집니다)")
            .setPositiveButton("바로끄기") { _, _ ->
                turnScreenOff()
            }
            .setNegativeButton("취소", null)
            .setCancelable(false)
            .create()

        val handler = Handler(Looper.getMainLooper())
        val countdown = object : Runnable {
            override fun run() {
                secondsLeft--
                if (secondsLeft > 0) {
                    dialog.setMessage("설정시간이 되어\n${secondsLeft}초 후 화면이 꺼집니다\n(터치하면 다시 켜집니다)")
                    handler.postDelayed(this, 1000)
                } else {
                    dialog.dismiss()
                    turnScreenOff()
                }
            }
        }

        dialog.setOnDismissListener {
            handler.removeCallbacks(countdown)
        }

        dialog.show()
        handler.postDelayed(countdown, 1000)
    }

    @Suppress("DEPRECATION")
    private fun hasNetwork(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun showNoWifiWarning(schoolUrl: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("네트워크 오류")
            .setMessage("와이파이 인터넷을 확인하세요")
            .setPositiveButton("다시 시도") { _, _ ->
                if (hasNetwork()) {
                    webView.loadUrl(schoolUrl)
                } else {
                    showNoWifiWarning(schoolUrl)
                }
            }
            .setNegativeButton("설정 열기") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    /** Auto-enable PrintService — reinstall disables it */
    private fun enablePrintService() {
        Thread {
            try {
                val component = "${packageName}/com.betona.printdriver.LibroPrintService"
                val p = Runtime.getRuntime().exec(arrayOf(
                    "sh", "-c", "settings put secure enabled_print_services $component"
                ))
                p.waitFor()
                Log.d(TAG, "PrintService auto-enabled: $component")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable PrintService", e)
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun disableSoundRecursive(view: View) {
        view.isSoundEffectsEnabled = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableSoundRecursive(view.getChildAt(i))
            }
        }
    }

    private fun toggleRotation() {
        val isLandscape = AppPrefs.isLandscape(this)
        val newLandscape = !isLandscape
        AppPrefs.setLandscape(this, newLandscape)
        requestedOrientation = if (newLandscape)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (AppPrefs.isNightSaveMode(this) && !AppPrefs.isDaytime(this) && screenOffOverlay == null) {
            inactivityHandler.postDelayed(inactivityRunnable, inactivityTimeout)
        }
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockRunnable)
        inactivityHandler.removeCallbacks(inactivityRunnable)
        super.onPause()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockRunnable)
        inactivityHandler.removeCallbacks(inactivityRunnable)
        destroyPrintWebView()
        try { webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
