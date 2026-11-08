package de.uditkaro.rax.ui.terminal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import de.uditkaro.rax.rex.UploadFile
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Files on their way to the computer: [name] is the first one's, and [sent] counts the bytes of all. */
data class Sending(val name: String, val count: Int, val sent: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * The file behind [uri], with its name and size. Rex needs the exact size before the bytes, so a file
 * whose app does not tell its size is copied into [temps] first.
 */
internal fun Context.uploadFile(uri: Uri, temps: MutableList<File>): UploadFile {
    var name: String? = null
    var size: Long? = null
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
            size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
        }
    }
    val fileName = name?.takeIf { it.isNotBlank() } ?: defaultName(contentResolver.getType(uri))
    val open = { contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()) }
    size?.takeIf { it >= 0 }?.let { return UploadFile(fileName, it, open) }

    val copy = File(cacheDir, "sending").apply { mkdirs() }.resolve(UUID.randomUUID().toString())
    temps += copy
    open().use { input -> copy.outputStream().use { input.copyTo(it) } }
    return UploadFile(fileName, copy.length()) { copy.inputStream() }
}

/** A name for a file whose app gives none, with the extension of its type. */
private fun defaultName(mimeType: String?): String {
    val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    val base = when (mimeType?.substringBefore('/')) {
        "image" -> "image"
        "video" -> "video"
        "audio" -> "audio"
        else -> "file"
    }
    return if (extension != null) "$base.$extension" else base
}
