package com.harshith.cricket07

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Two screens: setup (pick your build folder and your game folder) and the game (a full-screen WebView on
 * the loopback server, with touch controls drawn by the page).
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_BUILD = 1
        const val REQ_GAME = 2
        const val TAG = "Cricket07"
    }

    private var webView: WebView? = null
    private var lastBack = 0L
    private lateinit var statusView: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = App.crashFile(this)
        if (crash.isFile) { showCrash(crash.readText()); crash.delete(); return }
        try {
            LocalServer.ensure(this)
        } catch (e: Exception) {
            // the fixed port is taken (another copy of this app, or something else on the phone)
            Log.e(TAG, "server failed to start", e)
            Toast.makeText(this, "Couldn't start the game server on port ${LocalServer.PORT}: ${e.message}", Toast.LENGTH_LONG).show()
        }
        // touching WebView loads the phone's WebView package, which throws if it's missing, disabled or
        // mid-update: that must not close the app before setup can even show
        try {
            WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect from a computer, for troubleshooting
        } catch (e: Throwable) {
            Log.e(TAG, "WebView unavailable", e)
        }
        if (GameStore.ready(this)) showGame() else showSetup()
    }

    /** Shown on the start after a crash: the report, a Copy button, and a way back into the app. */
    private fun showCrash(report: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#070B08"))
        }
        col.addView(text("The app closed last time", 22f, Color.WHITE, bold = true))
        col.addView(text("Tap Copy and paste this report to whoever is fixing the app.", 15f, Color.parseColor("#9FB3A5")))
        col.addView(button("Copy report", true) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Cricket 07 crash", report))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("Continue", false) { recreate() })
        col.addView(text(report, 12f, Color.parseColor("#EEF4EF")).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070B08")); addView(col) })
    }

    // ------------------------------------------------------------------ setup screen

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#EEF4EF"))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor(if (primary) "#2E9E58" else "#1B2620"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun showSetup() {
        webView?.let { it.destroy(); webView = null }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.show(WindowInsets.Type.systemBars())

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(Color.parseColor("#070B08"))
        }
        val muted = Color.parseColor("#9FB3A5")
        col.addView(text("Cricket 07", 30f, Color.WHITE, bold = true))
        col.addView(text(
            "This app runs your own copy of Cricket 07. It contains no game files: you give it two folders " +
                "from your phone's storage once.", 15f, muted))

        col.addView(text("1. Your browser build", 18f, Color.WHITE, bold = true).apply { setPadding(0, dp(18), 0, 0) })
        col.addView(text(
            "The folder with recomp.js and recomp.wasm that cricket07-browser's build_web.sh made from your " +
                "game (build_web/www). Copy it to your phone first.", 14f, muted))
        col.addView(button(if (GameStore.buildReady(this)) "✓ Build installed · choose again" else "Choose build folder", false) {
            pickFolder(REQ_BUILD)
        })

        col.addView(text("2. Your Cricket 07 folder", 18f, Color.WHITE, bold = true).apply { setPadding(0, dp(18), 0, 0) })
        col.addView(text(
            "The installed game folder: the one with Data, Audio and the game's .exe. The game copies it into " +
                "its own storage on first start (about 1 GB of free space needed).", 14f, muted))
        col.addView(button(if (GameStore.gameReady(this)) "✓ Game folder chosen · choose again" else "Choose game folder", false) {
            pickFolder(REQ_GAME)
        })

        statusView = text("", 14f, Color.parseColor("#45CF79"))
        col.addView(statusView)
        startButton = button("Play", true) { showGame() }
        col.addView(startButton)
        col.addView(button("Open in Chrome instead", false) { openInChrome() })
        col.addView(text(
            "Use Chrome if the game says this WebView has no WebGPU. Chrome keeps its own copy of the game " +
                "files, so the first start there copies them again.", 13f, muted))
        refreshSetup()

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070B08"))
            addView(col)
        })
    }

    private fun refreshSetup() {
        if (!::startButton.isInitialized) return
        val ready = GameStore.ready(this)
        startButton.isEnabled = ready
        startButton.alpha = if (ready) 1f else .45f
    }

    private fun pickFolder(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        @Suppress("DEPRECATION")
        startActivityForResult(i, req)
    }

    @Deprecated("framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (resultCode != RESULT_OK || tree == null) return
        when (requestCode) {
            REQ_BUILD -> installBuild(tree)
            REQ_GAME -> chooseGame(tree)
        }
    }

    /** Copies the build's top-level files (recomp.js, recomp.wasm, any worker scripts) into app storage. */
    private fun installBuild(tree: Uri) {
        statusView.text = "Copying the build…"
        Thread {
            val result = try {
                val files = GameStore.topLevel(this, tree).filter { !it.isDir }
                val names = files.map { it.name }
                require("recomp.js" in names && "recomp.wasm" in names) {
                    "That folder has no recomp.js and recomp.wasm. Pick build_web/www."
                }
                val tmp = File(filesDir, "build.tmp").apply { deleteRecursively(); mkdirs() }
                for (f in files) {
                    val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, f.docId)
                    contentResolver.openInputStream(uri)!!.use { input ->
                        File(tmp, f.name).outputStream().use { input.copyTo(it, 256 * 1024) }
                    }
                }
                val dest = GameStore.buildDir(this)
                dest.deleteRecursively()
                check(tmp.renameTo(dest)) { "couldn't move the build into place" }
                "Build installed (${files.size} files)."
            } catch (e: Exception) {
                Log.w(TAG, "build copy failed", e)
                "Couldn't install the build: ${e.message}"
            }
            runOnUiThread { showSetup(); statusView.text = result }
        }.start()
    }

    private fun chooseGame(tree: Uri) {
        val top = try { GameStore.topLevel(this, tree) } catch (e: Exception) { emptyList() }
        if (top.none { !it.isDir && it.name.lowercase().endsWith(".exe") }) {
            statusView.text = "That folder has no .exe. Pick the Cricket 07 folder itself (the one with Data and Audio)."
            return
        }
        // drop access to a folder chosen before, keep this one across restarts
        GameStore.gameTree(this)?.takeIf { it != tree }?.let {
            try { contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        GameStore.setGameTree(this, tree)
        showSetup()
        statusView.text = "Game folder chosen. On first start the game copies it (a few minutes)."
    }

    // ------------------------------------------------------------------ game screen

    @SuppressLint("SetJavaScriptEnabled")
    private fun showGame() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val wv = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                builtInZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                // stay on the loopback server; anything else (links in the launcher) opens outside
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.host == "127.0.0.1" && u.port == LocalServer.PORT) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: ActivityNotFoundException) {}
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.i(TAG, "page: ${m.message()}")
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "AndroidHost")
        }
        webView = wv
        setContentView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.loadUrl(LocalServer.url(this) + "?android=1")
    }

    private fun openInChrome() {
        // the server's notification is how the player stops it later
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
        startForegroundService(Intent(this, ServerService::class.java))
        val uri = Uri.parse(LocalServer.url(this))
        val chrome = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome")
        try { startActivity(chrome) } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** What the page can ask of the app (window.AndroidHost). */
    inner class Bridge {
        @JavascriptInterface fun openSetup() = runOnUiThread { showSetup() }
        @JavascriptInterface fun openInChrome() = runOnUiThread { this@MainActivity.openInChrome() }
        @JavascriptInterface fun vibrate(ms: Int) {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 60).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // Back pauses the game (Esc); pressing it twice within 2 seconds goes back to setup.
    @Deprecated("framework Activity API")
    override fun onBackPressed() {
        val wv = webView
        if (wv == null) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) { showSetup(); return }
        lastBack = now
        wv.evaluateJavascript("window.__c07tap && window.__c07tap('Escape')", null)
        Toast.makeText(this, "Paused. Press back again to leave the game.", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); webView?.onResume() }
    override fun onPause() { webView?.onPause(); super.onPause() }
    override fun onDestroy() { webView?.destroy(); webView = null; super.onDestroy() }
}
