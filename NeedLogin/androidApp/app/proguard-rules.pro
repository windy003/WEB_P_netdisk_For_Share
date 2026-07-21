# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep JavaScript interface methods
-keepclassmembers class com.netdisk.app.webview.JavaScriptBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep MediaPlayer
-keep class android.media.MediaPlayer { *; }

# Keep MediaSessionCompat
-keep class androidx.media.** { *; }
