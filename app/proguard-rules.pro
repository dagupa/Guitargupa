# Reglas de ProGuard/R8.
# Métodos expuestos a JavaScript (puente WebView).
-keepclassmembers class com.tunergp.app.MainActivity$NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
