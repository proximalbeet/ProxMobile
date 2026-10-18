package com.proxai.hermes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GeneratedApp(
    val id: String,
    val title: String,
    val description: String,
    val created: Long,
    val updated: Long,
    val version: Int,
)

/** Library of single-file HTML apps that Hermes generated. Stored in app-private files. */
class AppStore(context: Context) {
    private val dir = File(context.filesDir, "apps").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val _apps = MutableStateFlow(load())
    val apps: StateFlow<List<GeneratedApp>> = _apps

    fun get(id: String): GeneratedApp? = _apps.value.firstOrNull { it.id == id }
    fun html(id: String): String? = File(dir, "$id.html").takeIf { it.exists() }?.readText()

    /** Saves a new app, or a new version of [targetId] when Hermes remixed an existing one. */
    @Synchronized
    fun save(html: String, targetId: String? = null): GeneratedApp {
        val now = System.currentTimeMillis()
        val existing = targetId?.let { get(it) }
        val title = extractTitle(html) ?: existing?.title ?: "Untitled app"
        val description = extractDescription(html) ?: existing?.description ?: ""
        val app = if (existing != null) {
            existing.copy(title = title, description = description, updated = now, version = existing.version + 1)
        } else {
            GeneratedApp(UUID.randomUUID().toString().substring(0, 8), title, description, now, now, 1)
        }
        if (existing != null) {
            // keep the previous version around in case the remix is worse
            File(dir, "${app.id}.html").takeIf { it.exists() }?.copyTo(File(dir, "${app.id}.v${existing.version}.html"), true)
        }
        File(dir, "${app.id}.html").writeText(html)
        _apps.value = listOf(app) + _apps.value.filterNot { it.id == app.id }
        persist()
        return app
    }

    @Synchronized
    fun rename(id: String, title: String) {
        _apps.value = _apps.value.map { if (it.id == id) it.copy(title = title) else it }
        persist()
    }

    @Synchronized
    fun delete(id: String) {
        dir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
        _apps.value = _apps.value.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        _apps.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("description", it.description)
                .put("created", it.created).put("updated", it.updated).put("version", it.version))
        }
        indexFile.writeText(arr.toString())
    }

    private fun load(): List<GeneratedApp> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            GeneratedApp(o.getString("id"), o.getString("title"), o.optString("description"),
                o.getLong("created"), o.getLong("updated"), o.optInt("version", 1))
        }
    }.getOrDefault(emptyList())

    companion object {
        private val FENCE = Regex("```[ \\t]*(html|htm)?[ \\t]*\\r?\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

        /** Finds the HTML app in a Hermes reply, if there is one. */
        fun extractHtml(reply: String): String? =
            FENCE.findAll(reply)
                .map { it.groupValues[2].trim() }
                .filter { body ->
                    val head = body.take(400).lowercase()
                    head.contains("<!doctype html") || head.contains("<html") ||
                        (body.contains("<body", true) && body.contains("<script", true))
                }
                .maxByOrNull { it.length }

        fun extractTitle(html: String): String? =
            Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.take(60)

        fun extractDescription(html: String): String? =
            Regex("<meta\\s+name=[\"']description[\"']\\s+content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
    }
}
