package com.betona.printdriver

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Kiosk-style WebView browser — launcher activity.
 * No URL bar (prevents page departure). Navigation via back/forward/home buttons only.
 * Print button only enabled when page contains 청구기호.
 */
class WebPrintActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebPrint"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPrint: ImageButton
    private var lastLoadedSchoolUrl: String? = null
    private var isPrinting = false
    private var shouldClearHistory = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_print)

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
        btnPrint.setOnClickListener { printPage() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // WebView setup
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (shouldClearHistory) {
                    view?.clearHistory()
                    shouldClearHistory = false
                }
                checkPrintButtonVisibility()
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
    }

    override fun onResume() {
        super.onResume()
        if (isPrinting) {
            isPrinting = false
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

    /**
     * Print button enabled when user has navigated away from home (search) page.
     * e.g. book detail page showing 서명/청구기호.
     */
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

    /**
     * After print: countdown 3→2→1 then auto-navigate home.
     * User can cancel to stay on current page.
     */
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

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
