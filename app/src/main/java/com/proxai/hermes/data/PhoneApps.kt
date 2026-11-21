package com.proxai.hermes.data

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * P3 (0.9.5, 2026-10-31): someone else's app on this phone. Nathan: REA is for other apps, widgets and
 * firmware, not his own apps (ProxAI and the apps it builds, whose source is on the PC). Its APK files
 * (base + splits) go to the PC, where Claude Code with REA looks inside them in the sandbox (Hermes's
 * claude_code with reverse_engineering, project app-research). Android lets any app read installed APK
 * files; seeing the apps needs the launcher and widget <queries> entries in the manifest.
 */
data class PhoneApp(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    /** base.apk first, then the split APKs. */
    val parts: List<ApkPart>,
    val system: Boolean = false,
    /** The home-screen widgets it offers. */
    val widgets: List<PhoneWidget> = emptyList(),
    val bytes: Long = parts.sumOf { it.file.length() },
) {
    /** The folder the apps server keeps them in, under ~/dev/proxai. */
    val folder: String get() = "app-research/$packageName-$versionCode"
}

/** One APK file of an app and the name the PC keeps it under: base.apk, or the split's own name. */
data class ApkPart(val name: String, val file: File)

/** A home-screen widget an app offers: what the widget picker calls it and its provider class. */
data class PhoneWidget(val label: String, val className: String)

object PhoneApps {
    private val SPLIT_RE = Regex("^split_[A-Za-z0-9_.]{1,100}\\.apk$")
    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]{0,60}(\\.[A-Za-z][A-Za-z0-9_]{0,60}){1,9}$")
    private const val WIDGETS_NAMED = 6

    /**
     * The app's APK files as the PC names them. The main one is always base.apk: an app installed or
     * updated from a store has /data/app/.../base.apk, but one that came with the phone keeps its own
     * name (/system/priv-app/Settings/Settings.apk). Splits keep their names; any the PC wouldn't take,
     * or that can't be read, are left out. Empty = nothing to send.
     */
    fun parts(sourceDir: String?, splitSourceDirs: Array<String>?): List<ApkPart> {
        val base = sourceDir?.let(::File)?.takeIf { it.name.endsWith(".apk") && it.canRead() } ?: return emptyList()
        val splits = splitSourceDirs.orEmpty().map(::File).filter { SPLIT_RE.matches(it.name) && it.canRead() }
        return listOf(ApkPart("base.apk", base)) + splits.map { ApkPart(it.name, it) }
    }

    fun validPackage(name: String): Boolean = PACKAGE_RE.matches(name)

    /** ProxAI itself and the apps it builds: Nathan's own, their source is on the PC, so they aren't offered. */
    fun isOwn(pkg: String): Boolean = pkg.startsWith("com.proxai.")

    /**
     * Other people's apps: the ones the home screen shows plus any that only offer widgets, A to Z.
     * Call it off the main thread.
     */
    fun list(ctx: Context): List<PhoneApp> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val launchable = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }
        val widgets = widgetsByPackage(ctx)
        return (launchable + widgets.keys).toSet().filterNot(::isOwn)
            .mapNotNull { pkg -> of(pm, pkg)?.copy(widgets = widgets[pkg].orEmpty()) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    /** Package -> the widgets it offers (Android's widget list), each once. */
    fun widgetsByPackage(ctx: Context): Map<String, List<PhoneWidget>> = runCatching {
        val pm = ctx.packageManager
        AppWidgetManager.getInstance(ctx).installedProviders
            .map { it.provider.packageName to PhoneWidget(it.loadLabel(pm)?.toString().orEmpty().ifBlank { it.provider.shortClassName },
                it.provider.className) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.distinctBy { it.className } }
    }.getOrDefault(emptyMap())

    @Suppress("DEPRECATION")
    fun of(pm: PackageManager, pkg: String): PhoneApp? = runCatching {
        val info = pm.getPackageInfo(pkg, 0)
        val ai = info.applicationInfo ?: return@runCatching null
        PhoneApp(
            label = ai.loadLabel(pm).toString().ifBlank { pkg },
            packageName = pkg,
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
            parts = parts(ai.sourceDir, ai.splitSourceDirs),
            system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }.getOrNull()?.takeIf { it.parts.isNotEmpty() && validPackage(it.packageName) }

    /** Its icon as a PNG in the attachments folder, for the chat thumbnail; null if it has none. */
    fun saveIcon(ctx: Context, pkg: String): String? = runCatching {
        val bitmap = ctx.packageManager.getApplicationIcon(pkg).toBitmap(192, 192)
        val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
        val f = File(dir, UUID.randomUUID().toString().substring(0, 13) + ".png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f.path
    }.getOrNull()

    /** The attachment the message carries once the app's files are on the PC. */
    fun attachment(app: PhoneApp, icon: String?, sent: AppResearchSent): Attachment =
        Attachment(name = app.label, kind = Attachment.APP, files = listOfNotNull(icon), info = info(app, sent))

    /**
     * What the [Attached: ...] note says about the app: where its files are on the PC, its widgets, and
     * how to look inside (Hermes reads this; no brackets, the note is in one).
     */
    fun info(app: PhoneApp, sent: AppResearchSent): String {
        val version = app.versionName.ifBlank { "?" }
        val splits = sent.files - 1
        val files = if (splits == 0) "1 APK file" else "${sent.files} APK files (base + $splits split${if (splits == 1) "" else "s"})"
        val widgets = if (app.widgets.isEmpty()) "" else {
            val named = app.widgets.take(WIDGETS_NAMED).joinToString(", ") { "${clean(it.label)} (${it.className})" }
            val more = app.widgets.size - WIDGETS_NAMED
            ", home-screen widgets: $named" + if (more > 0) " and $more more" else ""
        }
        return "app from this phone: package ${app.packageName}, version $version (code ${app.versionCode}), $files, " +
            "${mb(sent.bytes)}$widgets, now on the PC in ~/dev/proxai/${sent.folder}/. To look inside it, use claude_code " +
            "with project app-research and reverse_engineering true; its files are in ${sent.folder.removePrefix("app-research/")}/"
    }

    /** The attachment for a firmware image or other file sent to the PC to look inside. */
    fun fileAttachment(name: String, sent: AppResearchFileSent): Attachment =
        Attachment(name = name, kind = Attachment.PC_FILE, files = emptyList(), info = fileInfo(sent))

    fun fileInfo(sent: AppResearchFileSent): String =
        "file from this phone, ${mb(sent.size)}, now on the PC at ~/dev/proxai/${sent.folder}/${sent.file}. To look inside it " +
            "(firmware, an APK file, a binary), use claude_code with project app-research and reverse_engineering true; " +
            "it's at files/${sent.file}"

    private fun clean(s: String) = s.replace('[', '(').replace(']', ')').replace('\n', ' ').trim()

    fun mb(bytes: Long): String = when {
        bytes >= 100_000_000 -> "${bytes / 1_000_000} MB"
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
        else -> "${(bytes + 999) / 1000} KB"
    }
}

/** P3 (0.9.5): a file picked from the phone (firmware, an APK file...) on its way to the PC. */
object PcFiles {
    /** The picked file's name as the phone shows it ("file" if it has none). */
    fun displayName(ctx: Context, uri: android.net.Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "file"

    /** 0.9.6: keeps reading the picked file allowed after the picker is gone (the background send copies it). */
    fun keepGrant(ctx: Context, uri: android.net.Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun releaseGrant(ctx: Context, uri: android.net.Uri) {
        runCatching { ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * Copies the picked file into the cache (so its size is known and a retry can read it again) and
     * returns (its name as the phone shows it, the copy). The caller deletes the copy. Refuses a file
     * bigger than the PC takes, before or while copying.
     */
    fun copyForSending(ctx: Context, uri: android.net.Uri): Pair<String, File> {
        val cr = ctx.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > AppResearch.FILE_MAX) throw AppResearchException("That file is over ${AppResearch.FILE_MAX / 1024 / 1024} MB, more than the PC takes")
        val dir = File(ctx.cacheDir, "pc-files").apply { mkdirs() }
        val copy = File(dir, UUID.randomUUID().toString().substring(0, 13))
        try {
            (cr.openInputStream(uri) ?: throw AppResearchException("Can't open that file")).use { input ->
                copy.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > AppResearch.FILE_MAX) throw AppResearchException("That file is over ${AppResearch.FILE_MAX / 1024 / 1024} MB, more than the PC takes")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        if (copy.length() == 0L) {
            copy.delete()
            throw AppResearchException("That file is empty")
        }
        return name to copy
    }
}
