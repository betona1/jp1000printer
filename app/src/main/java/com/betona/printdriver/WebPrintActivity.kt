package com.betona.printdriver

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import android.util.Log

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
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPrint: ImageButton
    private lateinit var btnPower: ImageButton
    private lateinit var txtSchedule: TextView
    private var lastLoadedSchoolUrl: String? = null
    private var isPrinting = false
    private var shouldClearHistory = false

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

        // Keep screen on during normal operation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)
        btnPrint = findViewById(R.id.btnPrint)

        // Toolbar buttons
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        btnPower = findViewById(R.id.btnPower)
        txtSchedule = findViewById(R.id.txtSchedule)
        btnPrint.setOnClickListener { printPage() }
        btnPower.setOnClickListener { confirmScreenOff() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // Software rendering for A40i GPU compatibility (EGL errors with Chrome 113)
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = CHROME_UA
            javaScriptCanOpenWindowsAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }

        // JS bridge: capture window.open('','PRINT') HTML and print natively
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun printHtml(html: String) {
                Log.d(TAG, "printHtml called, html length=${html.length}")
                runOnUiThread { loadAndPrintHtml(html) }
            }

            @JavascriptInterface
            fun print() {
                runOnUiThread { printPage() }
            }
        }, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (shouldClearHistory) {
                    view?.clearHistory()
                    shouldClearHistory = false
                }
                checkPrintButtonVisibility()
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

        btnPower.visibility = if (AppPrefs.getShowPowerButton(this)) View.VISIBLE else View.GONE
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

        if (isPrinting) {
            isPrinting = false
            destroyPrintWebView()
            showPostPrintDialog()
            return
        }
        val schoolUrl = AppPrefs.getSchoolUrl(this)
        if (lastLoadedSchoolUrl != schoolUrl) {
            lastLoadedSchoolUrl = schoolUrl
            webView.loadUrl(schoolUrl)
        }
    }

    private fun goHome() {
        val url = AppPrefs.getSchoolUrl(this)
        lastLoadedSchoolUrl = url
        shouldClearHistory = true
        webView.loadUrl(url)
    }

    private fun checkPrintButtonVisibility() {
        val currentUrl = webView.url ?: ""
        val homeUrl = AppPrefs.getSchoolUrl(this)
        val isHome = currentUrl == homeUrl || currentUrl.isEmpty()
        btnPrint.isEnabled = !isHome
        btnPrint.alpha = if (!isHome) 1.0f else 0.3f
    }

    private fun printPage() {
        isPrinting = true
        val title = webView.title ?: "웹 페이지"
        Log.d(TAG, "Printing: $title")
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val adapter = webView.createPrintDocumentAdapter(title)
        printManager.print(title, adapter, null)
    }

    private fun showPostPrintDialog() {
        var secondsLeft = 3
        val dialog = AlertDialog.Builder(this)
            .setMessage("홈으로 이동합니다... (3)")
            .setNegativeButton("취소", null)
            .setCancelable(true)
            .create()

        val handler = Handler(Looper.getMainLooper())
        val countdown = object : Runnable {
            override fun run() {
                secondsLeft--
                if (secondsLeft > 0) {
                    dialog.setMessage("홈으로 이동합니다... ($secondsLeft)")
                    handler.postDelayed(this, 1000)
                } else {
                    dialog.dismiss()
                    goHome()
                }
            }
        }

        dialog.setOnDismissListener {
            handler.removeCallbacks(countdown)
        }

        dialog.show()
        handler.postDelayed(countdown, 1000)
    }

    /** Manual screen off — confirm then turn off screen */
    private fun confirmScreenOff() {
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

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        destroyPrintWebView()
        super.onDestroy()
    }
}
