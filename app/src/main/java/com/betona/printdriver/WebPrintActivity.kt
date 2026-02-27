package com.betona.printdriver

import android.annotation.SuppressLint
import android.os.Bundle
import android.print.PrintManager
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * WebView browser with desktop User-Agent for printing from library systems.
 * Desktop mode ensures print buttons are visible on web pages.
 */
class WebPrintActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebPrint"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        private const val DEFAULT_URL = "https://read365.edunet.net/PureScreen/SchoolSearch?schoolName=%EC%86%A1%EC%9A%B4%EC%B4%88%EB%93%B1%ED%95%99%EA%B5%90&provCode=J10&neisCode=J100002752"
    }

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_print)

        urlBar = findViewById(R.id.etUrl)
        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)

        findViewById<Button>(R.id.btnGo).setOnClickListener { loadUrl() }
        findViewById<Button>(R.id.btnPrint).setOnClickListener { printPage() }
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                loadUrl()
                true
            } else false
        }

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
                urlBar.setText(url ?: "")
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

        webView.loadUrl(DEFAULT_URL)
    }

    private fun loadUrl() {
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        webView.loadUrl(url)
    }

    private fun printPage() {
        val title = webView.title ?: "웹 페이지"
        Log.d(TAG, "Printing: $title")
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val adapter = webView.createPrintDocumentAdapter(title)
        printManager.print(title, adapter, null)
        Toast.makeText(this, "인쇄 대화상자 열기...", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
