package org.oioioi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * The phone screen: the same web app as the site (map, flags, photos, reports), with Drive mode handed to
 * the native DriveService so listening keeps working with the screen off.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val onChange: () -> Unit = { pushState() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "OioiNative")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) = pushState()
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, true, false) }
            override fun onPermissionRequest(request: PermissionRequest?) { runOnUiThread { request?.grant(request.resources) } }
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { startActivityForResult(params!!.createIntent(), 2); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.loadUrl(BuildConfig.SERVER)
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onStart() { super.onStart(); DriveService.listeners += onChange; pushState() }
    override fun onStop() { DriveService.listeners -= onChange; super.onStop() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 2) { fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCallback = null }
        else super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    /** Tells the page whether Drive mode is on, what it is doing, and to reload the flags. */
    private fun pushState() {
        if (!::web.isInitialized) return
        val s = DriveService.instance
        val state = JSONObject().put("driving", s != null).put("status", s?.status ?: "").put("flagging", s?.flagging == true)
        web.evaluateJavascript("window.onNativeDrive && window.onNativeDrive($state)", null)
    }

    inner class Bridge {
        @JavascriptInterface fun uid(): String = Api.uid(this@MainActivity)
        @JavascriptInterface fun isDriving(): Boolean = DriveService.instance != null
        @JavascriptInterface fun startDrive(settings: String) = runOnUiThread { DriveService.start(this@MainActivity, settings) }
        @JavascriptInterface fun stopDrive() = runOnUiThread { DriveService.stop(this@MainActivity) }
        @JavascriptInterface fun flag() = runOnUiThread { DriveService.instance?.flag() }
    }
}
