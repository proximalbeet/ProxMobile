package com.proxai.shell

import org.json.JSONObject

/** Pure helpers (no Android classes), so they can be unit-tested on the JVM. */
object ShellLogic {
    /** Fake https origin the app's files are served from. Each APK is its own package, so
     *  localStorage and cookies are already separate per app. */
    const val HOST = "app.proxai.local"
    const val ORIGIN = "https://$HOST/"

    /** Features an app can ask for in proxai-app.json. Anything else is ignored. */
    val KNOWN_FEATURES = setOf("vibrate", "notifications", "speech", "ask", "pc-stats", "scout")

    /** ProxMobile's door for PC stats (data/PcStats.kt PcStatsProvider). */
    const val PC_STATS_URI = "content://com.proxai.hermes.pcstats"

    data class AppInfo(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val features: Set<String>,
        val color: Int,
    )

    fun parseInfo(json: String): AppInfo {
        val o = JSONObject(json)
        val feats = o.optJSONArray("features")
        val set = mutableSetOf<String>()
        if (feats != null) for (i in 0 until feats.length()) feats.optString(i).takeIf { it in KNOWN_FEATURES }?.let(set::add)
        return AppInfo(
            id = o.optString("id", "app"),
            name = o.optString("name", "ProxAI app").ifBlank { "ProxAI app" },
            version = o.optString("version", "1.0"),
            description = o.optString("description", ""),
            features = set,
            color = parseColor(o.optString("color", "")) ?: BRAND,
        )
    }

    fun parseColor(s: String): Int? {
        val hex = s.trim().removePrefix("#")
        if (!Regex("^[0-9a-fA-F]{6}$").matches(hex)) return null
        return (0xFF000000 or hex.toLong(16)).toInt()
    }

    /** Asset path for a request path on [HOST], or null if it's not allowed ('..', hidden files). */
    fun assetPath(urlPath: String?): String? {
        var p = (urlPath ?: "").trimStart('/')
        if (p.isEmpty() || p.endsWith("/")) p += "index.html"
        val parts = p.split('/')
        if (parts.any { it.isEmpty() || it == ".." || it == "." || it.startsWith(".") }) return null
        return "www/$p"
    }

    fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "txt", "md" -> "text/plain"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    /** Puts the window.ProxAI script first in <head> (or at the top) of every HTML page. */
    fun injectShim(html: String): String {
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.substring(0, head.range.last + 1) + SHIM + html.substring(head.range.last + 1)
        else SHIM + html
    }

    // ProxMobile's look (ui/Theme.kt): glowing blue gradient on a near-black background.
    const val BRAND = 0xFF4DA8FF.toInt()
    const val BRAND_DEEP = 0xFF0B3D91.toInt()
    const val BACKGROUND = 0xFF0E0F13.toInt()

    /** Same JS API as ProxMobile's app runner, plus notify/remind/app. Calls that need a feature
     *  the app didn't declare do nothing and return false. */
    const val SHIM = """<script>(function(){
  if (window.ProxAI) return;
  var n = window.__proxaiNative, pending = {}, seq = 0;
  window.__proxaiResolve = function(id, r){ var p = pending[id]; if(!p) return; delete pending[id];
    r.ok ? p.resolve(r.text) : p.reject(new Error(r.error)); };
  window.ProxAI = {
    ask: function(prompt){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: resolve, reject: reject}; n.ask(id, String(prompt)); }); },
    speak: function(t){ return n.speak(String(t)); },
    toast: function(t){ n.toast(String(t)); },
    vibrate: function(ms){ return n.vibrate(ms || 30); },
    notify: function(title, text){ return n.notify(String(title || ''), String(text || '')); },
    remind: function(when, text){ var t = (when instanceof Date) ? when.getTime() : Number(new Date(when));
      return n.remind(t, String(text || '')); },
    pcStats: function(){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: function(t){ try { resolve(JSON.parse(t)); } catch(e) { reject(e); } },
        reject: reject}; n.pcStats(id); }); },
    scout: function(){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: function(t){ try { resolve(JSON.parse(t)); } catch(e) { reject(e); } },
        reject: reject}; n.scout(id); }); },
    scoutUpdate: function(change){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: function(t){ try { resolve(JSON.parse(t)); } catch(e) { reject(e); } },
        reject: reject}; n.scoutUpdate(id, JSON.stringify(change || {})); }); },
    app: (function(){ try { return JSON.parse(n.appInfo()); } catch(e) { return {}; } })()
  };
})();</script>"""
}
