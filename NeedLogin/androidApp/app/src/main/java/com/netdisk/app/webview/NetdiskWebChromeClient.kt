package com.netdisk.app.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

class NetdiskWebChromeClient(
    private val activity: Activity
) : WebChromeClient() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        // Clean up previous callback
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = filePathCallback

        // Launch file picker with multiple file selection support
        // 使用 ACTION_OPEN_DOCUMENT 代替 ACTION_GET_CONTENT，性能更好
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) // Enable multi-select
            // 添加持久化权限标志，提高性能
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            activity.startActivityForResult(
                Intent.createChooser(intent, "选择文件（可多选）"),
                FILE_CHOOSER_REQUEST_CODE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file chooser", e)
            this.filePathCallback = null
            return false
        }

        return true
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        // You can update a progress bar here if needed
        Log.d(TAG, "Page load progress: $newProgress%")
    }

    // 全屏支持
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        Log.d(TAG, "onShowCustomView called")

        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback

        // 保存原始 UI 状态
        originalSystemUiVisibility = activity.window.decorView.systemUiVisibility

        // 设置全屏
        activity.window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // 添加全屏视图
        val decorView = activity.window.decorView as FrameLayout
        decorView.addView(customView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        Log.d(TAG, "Fullscreen view shown")
    }

    override fun onHideCustomView() {
        Log.d(TAG, "onHideCustomView called")

        if (customView == null) {
            return
        }

        // 恢复 UI 状态
        activity.window.decorView.systemUiVisibility = originalSystemUiVisibility

        // 移除全屏视图
        val decorView = activity.window.decorView as FrameLayout
        decorView.removeView(customView)

        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null

        Log.d(TAG, "Fullscreen view hidden")
    }

    fun isInFullscreen(): Boolean {
        return customView != null
    }

    fun exitFullscreen() {
        customViewCallback?.onCustomViewHidden()
        onHideCustomView()
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results = if (resultCode == Activity.RESULT_OK && data != null) {
                // Handle multiple file selection
                val clipData = data.clipData
                if (clipData != null) {
                    // Multiple files selected
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { uris.add(it) }
                    }
                    Log.d(TAG, "Multiple files selected: ${uris.size}")
                    uris.toTypedArray()
                } else {
                    // Single file selected
                    data.data?.let {
                        Log.d(TAG, "Single file selected: $it")
                        arrayOf(it)
                    }
                }
            } else {
                Log.d(TAG, "File selection cancelled")
                null
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    companion object {
        private const val TAG = "NetdiskWebChromeClient"
        const val FILE_CHOOSER_REQUEST_CODE = 1001
    }
}
