package com.tunergp.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Contenedor nativo (Kotlin) que carga la app web `assets/index.html`.
 *
 * Se sirve desde https://appassets.androidplatform.net para tener un contexto seguro,
 * necesario para getUserMedia (micrófono), Web Audio y Media Session.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingWebPermission: PermissionRequest? = null

    @Volatile private var pageLoaded = false
    @Volatile private var metronomeOn = false
    @Volatile private var tunerOn = false

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(APP_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !pageLoaded }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this)
        setContentView(webView)

        // Respeta las barras del sistema (la web ya usa env(safe-area-inset-*)).
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true // localStorage (ajustes y favoritos)
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(NativeBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Enlaces externos al navegador.
                if (request.url.host == APP_HOST) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoaded = true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (hasMicPermission()) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingWebPermission = request
                            ActivityCompat.requestPermissions(
                                this@MainActivity,
                                arrayOf(Manifest.permission.RECORD_AUDIO),
                                REQ_MIC
                            )
                        }
                    } else {
                        request.deny()
                    }
                }
            }
        }

        // Atrás: primero cierra Ajustes/Ayuda; si no hay paneles abiertos, sale.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(CLOSE_PANELS_JS) { closed ->
                    if (closed != "true") finish()
                }
            }
        })

        // Botón "Detener" de la notificación.
        KeepAliveService.onStopRequested = {
            runOnUiThread { webView.evaluateJavascript("if(window.mStop)mStop()", null) }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://$APP_HOST/assets/index.html")
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            pendingWebPermission?.let {
                if (granted) it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else it.deny()
            }
            pendingWebPermission = null
        }
    }

    /** Pantalla encendida solo mientras el afinador o el metrónomo están activos. */
    private fun updateKeepScreenOn() {
        runOnUiThread {
            if (metronomeOn || tunerOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    /** Funciones nativas que la web invoca vía `window.AndroidBridge`. */
    private inner class NativeBridge {
        @JavascriptInterface
        fun setMetronome(on: Boolean) {
            metronomeOn = on
            runOnUiThread {
                if (on) KeepAliveService.start(this@MainActivity)
                else KeepAliveService.stop(this@MainActivity)
            }
            updateKeepScreenOn()
        }

        @JavascriptInterface
        fun setTuner(on: Boolean) {
            tunerOn = on
            updateKeepScreenOn()
        }

        /** Patrón estilo navigator.vibrate: duraciones separadas por comas (vibra, pausa, ...). */
        @JavascriptInterface
        fun vibrate(pattern: String) {
            val p = pattern.split(',').mapNotNull { it.trim().toLongOrNull() }
            if (p.isEmpty()) return
            // Waveform empieza con una pausa: se antepone 0 para que el primer valor vibre.
            vibrate(longArrayOf(0) + p.map { it.coerceIn(0, 2000) })
        }
    }

    // Con el metrónomo sonando el WebView no se pausa (KeepAliveService mantiene el
    // proceso); en cualquier otro caso se pausa para ahorrar batería.
    override fun onStop() {
        super.onStop()
        if (!metronomeOn) {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    override fun onStart() {
        super.onStart()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        KeepAliveService.onStopRequested = null
        KeepAliveService.stop(this)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val REQ_MIC = 1
        private const val REQ_NOTIF = 2
        private const val CLOSE_PANELS_JS =
            "(function(){var o=document.querySelectorAll('.panel.open');" +
                "o.forEach(function(p){p.classList.remove('open')});return o.length>0})()"
    }
}
