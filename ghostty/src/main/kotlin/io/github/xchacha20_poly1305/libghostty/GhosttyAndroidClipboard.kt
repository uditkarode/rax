package io.github.xchacha20_poly1305.libghostty

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri

/** The Android primary clip. */
public class GhosttyAndroidClipboard(context: Context) : GhosttyClipboard {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun hasText(): Boolean = manager.hasPrimaryClip()

    override fun getText(): String? = manager.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

    override fun setText(text: String) {
        manager.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    override fun getFile(): Uri? {
        val clip = manager.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        val item = clip.getItemAt(0)
        val uri = item.uri ?: return null
        // A text file's contents paste as text.
        if (item.text != null || uri.scheme != ContentResolver.SCHEME_CONTENT || clip.description.hasMimeType("text/*")) return null
        return uri
    }
}
