package com.proxai.hermes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Phase 2, C5 (2026-10-29): a file attached to a message. The Hermes API server takes text and
 * images (data:image/... parts), so: a photo is sent as an image (long side 1568 px, the size Claude
 * reads best); a PDF as its first [MAX_PDF_PAGES] pages drawn as images; a text file (notes, code,
 * CSV, JSON...) as text in the message. [files]: copies in filesDir/attachments, so the bubble can
 * show them later. Someone else's app on this phone, or a firmware image or other file (P3, 0.9.5), goes
 * to the PC first, for Claude Code with REA; then [files] is the app's icon (or nothing) and [info] what
 * the note tells ProxAI about it (where it is on the PC).
 */
data class Attachment(val name: String, val kind: String, val files: List<String>, val pages: Int = 0, val info: String = "") {
    /** Sent to the model as pictures (a photo, a PDF's pages); an app's icon is only for the bubble. */
    val pictures: Boolean get() = kind == IMAGE || kind == PDF

    companion object {
        const val IMAGE = "image"
        const val PDF = "pdf"
        const val TEXT = "text"
        const val APP = "app"
        /** A firmware image or other file sent to the PC to look inside (P3); [files] is empty. */
        const val PC_FILE = "pcfile"
    }
}

object Attachments {
    const val MAX_SIDE = 1568
    const val MAX_PDF_PAGES = 5
    /** Images per message (PDF pages count): keeps a request well under the API server's 10 MB. */
    const val MAX_IMAGES = 8
    const val MAX_TEXT_BYTES = 150_000
    private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini",
        "log", "py", "kt", "java", "js", "ts", "html", "css", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "sh",
        "sql", "tex", "srt")

    fun kindOf(name: String, mime: String?): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime?.startsWith("image/") == true -> Attachment.IMAGE
            mime == "application/pdf" || ext == "pdf" -> Attachment.PDF
            mime?.startsWith("text/") == true || mime == "application/json" || ext in TEXT_EXT -> Attachment.TEXT
            else -> null
        }
    }

    fun imageCount(list: List<Attachment>) = list.filter { it.pictures }.sumOf { it.files.size }

    /** S2: the screen behind the power-button assistant, as an image (shrunk like a photo). */
    suspend fun fromScreenshot(ctx: Context, shot: Bitmap, appName: String?): Attachment = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
        val f = File(dir, UUID.randomUUID().toString().substring(0, 13) + ".jpg")
        // Android may hand over a hardware bitmap, which can't be scaled or saved directly.
        val soft = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
        f.outputStream().use { fit(soft, MAX_SIDE).compress(Bitmap.CompressFormat.JPEG, 85, it) }
        Attachment(if (appName.isNullOrBlank()) "Screen" else "Screen ($appName)", Attachment.IMAGE, listOf(f.path))
    }

    /** Copies [uri] into the app (shrunk / drawn / read) as an [Attachment]. Throws with a message for Nathan. */
    suspend fun prepare(ctx: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri)
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
        fun newFile(ext: String) = File(dir, UUID.randomUUID().toString().substring(0, 13) + "." + ext)
        when (kindOf(name, mime)) {
            Attachment.IMAGE -> {
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Can't read $name")
                val bmp = upright(MediaCache.decode(bytes, MAX_SIDE) ?: throw IOException("$name isn't a picture ProxMobile can read"),
                    orientation(bytes))
                val f = newFile("jpg")
                f.outputStream().use { fit(bmp, MAX_SIDE).compress(Bitmap.CompressFormat.JPEG, 85, it) }
                Attachment(name, Attachment.IMAGE, listOf(f.path))
            }
            Attachment.PDF -> {
                val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("Can't read $name")
                pfd.use {
                    PdfRenderer(it).use { pdf ->
                        val files = (0 until minOf(pdf.pageCount, MAX_PDF_PAGES)).map { i ->
                            pdf.openPage(i).use { page ->
                                val w = 1240
                                val h = (w.toLong() * page.height / maxOf(1, page.width)).toInt().coerceIn(1, 4 * w)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val f = newFile("jpg")
                                f.outputStream().use { o -> fit(bmp, MAX_SIDE).compress(Bitmap.CompressFormat.JPEG, 85, o) }
                                f.path
                            }
                        }
                        if (files.isEmpty()) throw IOException("$name has no pages")
                        Attachment(name, Attachment.PDF, files, pdf.pageCount)
                    }
                }
            }
            Attachment.TEXT -> {
                val bytes = cr.openInputStream(uri)?.use { s -> readUpTo(s, MAX_TEXT_BYTES + 1) } ?: throw IOException("Can't read $name")
                if (bytes.size > MAX_TEXT_BYTES) throw IOException("$name is over ${MAX_TEXT_BYTES / 1000} KB of text")
                if (bytes.any { it == 0.toByte() }) throw IOException("$name isn't a text file")
                val f = newFile("txt")
                f.writeBytes(bytes)
                Attachment(name, Attachment.TEXT, listOf(f.path))
            }
            else -> throw IOException("ProxMobile can attach pictures, PDFs and text files for now, not $name")
        }
    }

    /** The text files, written into the message (outside [...] notes: they're part of what he sent). */
    fun textBlocks(list: List<Attachment>, read: (String) -> String = { File(it).readText() }): String =
        list.filter { it.kind == Attachment.TEXT }.joinToString("") { a ->
            "\n\n--- Attached file: ${a.name} ---\n" + read(a.files.first()).trimEnd() + "\n--- End of ${a.name} ---"
        }

    /** One note naming what's attached, e.g. "photo.jpg (picture), notes.pdf (PDF: pages 1-5 of 12, as pictures)". */
    fun note(list: List<Attachment>): String {
        if (list.isEmpty()) return ""
        val items = list.joinToString(", ") { a ->
            when (a.kind) {
                Attachment.IMAGE -> "${a.name} (picture)"
                Attachment.PDF -> "${a.name} (PDF: " + (if (a.pages > a.files.size) "pages 1-${a.files.size} of ${a.pages}"
                    else "${a.pages} page${if (a.pages == 1) "" else "s"}") + ", as pictures)"
                Attachment.APP, Attachment.PC_FILE -> "${a.name} (${a.info})"
                else -> "${a.name} (text, below)"
            }
        }
        return "[Attached: ${ChatNotices.unbracket(items)}]"
    }

    /** data:image/jpeg URLs for the pictures and PDF pages, in order. */
    fun dataUrls(list: List<Attachment>): List<String> = list.filter { it.pictures }.flatMap { a ->
        a.files.map { "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(File(it).readBytes()) }
    }

    fun delete(list: List<Attachment>) = list.forEach { a -> a.files.forEach { File(it).delete() } }

    /** InputStream.readNBytes is Android 13+; ProxMobile runs from Android 10. */
    private fun readUpTo(s: java.io.InputStream, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < n) {
            val got = s.read(buf, 0, minOf(buf.size, n - out.size()))
            if (got < 0) break
            out.write(buf, 0, got)
        }
        return out.toByteArray()
    }

    private fun fit(b: Bitmap, max: Int): Bitmap {
        val side = maxOf(b.width, b.height)
        if (side <= max) return b
        val s = max.toFloat() / side
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun orientation(bytes: ByteArray): Int = runCatching {
        android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)

    /** Phone photos are often stored sideways with an EXIF note; turn them upright before sending. */
    private fun upright(b: Bitmap, orientation: Int): Bitmap {
        val deg = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }
}
