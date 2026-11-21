# window.ProxAI bridge methods are called from JavaScript by name.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# WAKE-PHONE: sherpa-onnx's native code reads its Kotlin config classes by name (only in builds with the engine).
-keep class com.k2fsa.sherpa.onnx.** { *; }
