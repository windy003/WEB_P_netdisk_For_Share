package com.netdisk.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class PreferencesManager(context: Context) {

    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREF_NAME = "netdisk_prefs"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_FULL_SERVER_URL = "full_server_url"
        private const val KEY_LAST_SUCCESSFUL_URL = "last_successful_url"
        private const val KEY_AUTH_COOKIES = "auth_cookies"
        private const val KEY_HAS_CONFIGURED = "has_configured"
        private const val KEY_STREAM_TOKEN = "stream_token"
        private const val DEFAULT_HOST = "192.168.1.100"
        private const val DEFAULT_PORT = 5003
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveServerConfig(host: String, port: Int) {
        prefs.edit().apply {
            putString(KEY_SERVER_HOST, host)
            putInt(KEY_SERVER_PORT, port)
            putBoolean(KEY_HAS_CONFIGURED, true)  // Mark as configured
            // 清除旧的完整URL配置，避免冲突
            remove(KEY_FULL_SERVER_URL)
            apply()
        }
    }

    // 新方法：保存完整URL（支持HTTPS和cloudflare tunnel）
    fun saveFullServerUrl(fullUrl: String) {
        prefs.edit().apply {
            putString(KEY_FULL_SERVER_URL, fullUrl)
            putBoolean(KEY_HAS_CONFIGURED, true)
            // 清除旧的host:port配置，避免冲突
            remove(KEY_SERVER_HOST)
            remove(KEY_SERVER_PORT)
            apply()
        }
    }

    // 清除完整URL配置
    fun clearFullServerUrl() {
        prefs.edit().remove(KEY_FULL_SERVER_URL).apply()
    }

    fun hasConfigured(): Boolean {
        return prefs.getBoolean(KEY_HAS_CONFIGURED, false)
    }

    fun getServerHost(): String {
        return prefs.getString(KEY_SERVER_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
    }

    fun getServerPort(): Int {
        return prefs.getInt(KEY_SERVER_PORT, DEFAULT_PORT)
    }

    fun getServerUrl(): String {
        // 优先使用最近成功登录的URL
        val lastSuccessfulUrl = prefs.getString(KEY_LAST_SUCCESSFUL_URL, null)
        Log.d(TAG, "getServerUrl: lastSuccessfulUrl = $lastSuccessfulUrl")
        if (lastSuccessfulUrl != null && lastSuccessfulUrl.isNotEmpty()) {
            Log.d(TAG, "getServerUrl: returning lastSuccessfulUrl")
            return lastSuccessfulUrl
        }

        // 其次使用完整URL（支持cloudflare tunnel等）
        val fullUrl = prefs.getString(KEY_FULL_SERVER_URL, null)
        Log.d(TAG, "getServerUrl: fullUrl = $fullUrl")
        if (fullUrl != null && fullUrl.isNotEmpty()) {
            Log.d(TAG, "getServerUrl: returning fullUrl")
            return fullUrl
        }

        // 回退到旧的host:port模式
        val host = getServerHost()
        val port = getServerPort()
        Log.d(TAG, "getServerUrl: returning host:port = http://$host:$port")
        return "http://$host:$port"
    }

    // 保存最近成功登录的URL
    fun saveLastSuccessfulUrl(url: String) {
        Log.d(TAG, "saveLastSuccessfulUrl: saving $url")
        prefs.edit().putString(KEY_LAST_SUCCESSFUL_URL, url).apply()
        Log.d(TAG, "saveLastSuccessfulUrl: saved successfully")
    }

    fun getLastSuccessfulUrl(): String? {
        return prefs.getString(KEY_LAST_SUCCESSFUL_URL, null)
    }

    fun clearLastSuccessfulUrl() {
        prefs.edit().remove(KEY_LAST_SUCCESSFUL_URL).apply()
    }

    fun saveAuthCookies(cookies: String) {
        prefs.edit().putString(KEY_AUTH_COOKIES, cookies).apply()
    }

    fun getAuthCookies(): String? {
        return prefs.getString(KEY_AUTH_COOKIES, null)
    }

    fun clearAuthCookies() {
        prefs.edit().remove(KEY_AUTH_COOKIES).apply()
    }

    fun saveStreamToken(token: String) {
        prefs.edit().putString(KEY_STREAM_TOKEN, token).apply()
    }

    fun getStreamToken(): String? {
        return prefs.getString(KEY_STREAM_TOKEN, null)
    }

    fun clearStreamToken() {
        prefs.edit().remove(KEY_STREAM_TOKEN).apply()
    }

}
