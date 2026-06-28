package com.audiobookshelf.app.plugins

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "AbsCfZeroTrust")
class AbsCfZeroTrust : Plugin() {
  private val tag = "AbsCfZeroTrust"

  @SuppressLint("SetJavaScriptEnabled")
  @PluginMethod
  fun openCfWebView(call: PluginCall) {
    val serverAddress = call.getString("serverAddress") ?: run {
      call.reject("serverAddress is required")
      return
    }

    val serverHost = try { Uri.parse(serverAddress).host ?: "" } catch (e: Exception) { "" }
    if (serverHost.isEmpty()) {
      call.reject("Invalid serverAddress")
      return
    }

    activity.runOnUiThread {
      val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
      val webView = WebView(activity)

      webView.settings.javaScriptEnabled = true
      webView.settings.domStorageEnabled = true

      CookieManager.getInstance().setAcceptCookie(true)
      CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

      var resolved = false

      // URL indicator bar so the user can see what host they're authenticating against
      val urlBar = TextView(activity).apply {
        text = serverHost
        setTextColor(Color.parseColor("#AAAAAA"))
        setBackgroundColor(Color.parseColor("#111111"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(16, 12, 16, 12)
      }

      webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
          return false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
          if (url == null || resolved) return
          val currentHost = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
          urlBar.text = currentHost

          if (currentHost == serverHost || currentHost.endsWith(".$serverHost")) {
            val serverOrigin = try {
              val u = Uri.parse(serverAddress)
              "${u.scheme}://${u.host}${if (u.port != -1) ":${u.port}" else ""}"
            } catch (e: Exception) { serverAddress }
            val allCookies = CookieManager.getInstance().getCookie(serverOrigin)
            if (!allCookies.isNullOrEmpty() && allCookies.contains("CF_Authorization=")) {
              Log.d(tag, "CF auth complete, cookies extracted for host $currentHost")
              resolved = true
              dialog.dismiss()
              val result = JSObject()
              result.put("cookieHeader", allCookies)
              call.resolve(result)
            }
          }
        }
      }

      val layout = LinearLayout(activity)
      layout.orientation = LinearLayout.VERTICAL
      layout.addView(urlBar, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ))
      layout.addView(webView, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.MATCH_PARENT
      ))

      dialog.setContentView(layout)
      dialog.setCancelable(true)
      dialog.setOnDismissListener {
        if (!resolved) {
          call.reject("cancelled")
        }
      }
      dialog.show()
      webView.loadUrl(serverAddress)
    }
  }
}
