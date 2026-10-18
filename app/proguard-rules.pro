# window.ProxAI bridge methods are called from JavaScript by name.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
