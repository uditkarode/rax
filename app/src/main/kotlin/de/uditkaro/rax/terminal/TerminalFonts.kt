package de.uditkaro.rax.terminal

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The terminal's fonts: the families bundled in `assets/fonts` and the ones the user adds from
 * files. Every typeface falls back to the Nerd Font icons, then to JuliaMono for symbols the font
 * lacks, then to the system's monospace for CJK and other scripts. JuliaMono comes before the
 * system fonts, so symbols such as ⏺ and ⏸ draw as text, the way desktop terminals show them.
 */
class TerminalFonts(private val context: Context) {

    @Serializable
    data class Bundled(
        val id: String,
        val name: String,
        val version: String,
        val license: String,
        val source: String,
        val styles: List<String>,
        val variable: Boolean = false,
    )

    @Serializable
    private data class Imported(val id: String, val name: String, val faces: List<ImportedFace>)

    @Serializable
    private data class ImportedFace(val file: String, val index: Int, val weight: Int, val italic: Boolean)

    data class Choice(val id: String, val name: String, val imported: Boolean)

    sealed interface ImportResult {
        data class Added(val names: List<String>) : ImportResult
        data class Failed(val reason: Reason) : ImportResult
        enum class Reason { NotAFont, NotMonospaced, Unreadable, NotSaved }
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val assets = context.assets
    private val dir = File(context.filesDir, "fonts")
    private val manifest = File(dir, "imported.json")
    private val lock = Mutex()

    val bundled: List<Bundled> = assets.open("fonts/fonts.json").bufferedReader().use {
        json.decodeFromString<List<Bundled>>(it.readText())
    }

    private val _imported = MutableStateFlow(readManifest())
    private val imported get() = _imported.value

    val bundledChoices: List<Choice> = bundled.map { Choice(it.id, it.name, imported = false) }

    val importedChoices: StateFlow<List<Choice>> get() = _importedChoices.asStateFlow()
    private val _importedChoices = MutableStateFlow(imported.map { Choice(it.id, it.name, imported = true) })

    private val full = ConcurrentHashMap<String, Typeface>()
    private val previews = ConcurrentHashMap<String, Typeface>()

    private val fallbacks: List<FontFamily> by lazy {
        listOf("fonts/symbols-nerd/regular.ttf", "fonts/juliamono/regular.ttf").map { path ->
            FontFamily.Builder(Font.Builder(assets, path).build()).build()
        }
    }

    fun name(id: String): String =
        bundled.firstOrNull { it.id == id }?.name ?: imported.firstOrNull { it.id == id }?.name ?: bundled.first().name

    fun exists(id: String) = bundled.any { it.id == id } || imported.any { it.id == id }

    /** The full family (regular, bold, italics) for the terminal. Slow the first time; call off the main thread. */
    fun typeface(id: String): Typeface {
        val key = if (exists(id)) id else bundled.first().id
        return full.getOrPut(key) { build(key, previewOnly = false) }
    }

    /** Just the regular face, for showing a font's name in itself in the picker. */
    fun preview(id: String): Typeface = previews.getOrPut(id) { build(id, previewOnly = true) }

    private fun build(id: String, previewOnly: Boolean): Typeface = try {
        val fonts = bundled.firstOrNull { it.id == id }?.let { bundledFonts(it, previewOnly) }
            ?: imported.first { it.id == id }.let { importedFonts(it, previewOnly) }
        val family = FontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach(::addFont) }.build()
        Typeface.CustomFallbackBuilder(family)
            .apply { fallbacks.forEach(::addCustomFallback) }
            .setSystemFallback("monospace")
            .build()
    } catch (e: Exception) {
        Log.w(TAG, "font $id failed to load", e)
        Typeface.MONOSPACE
    }

    private fun bundledFonts(entry: Bundled, previewOnly: Boolean): List<Font> {
        val base = "fonts/${entry.id}"
        if (entry.variable) {
            val faces = listOf(
                Triple("upright.ttf", 400, false), Triple("upright.ttf", 700, false),
                Triple("italic.ttf", 400, true), Triple("italic.ttf", 700, true),
            ).let { if (previewOnly) it.take(1) else it }
            return faces.map { (file, weight, italic) ->
                Font.Builder(assets, "$base/$file")
                    .setFontVariationSettings("'wght' $weight")
                    .setWeight(weight)
                    .setSlant(if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                    .build()
            }
        }
        val styles = if (previewOnly) listOf("regular") else entry.styles
        return styles.map { style ->
            Font.Builder(assets, "$base/$style.ttf")
                .setWeight(if (style.startsWith("bold")) 700 else 400)
                .setSlant(if (style.endsWith("italic")) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                .build()
        }
    }

    private fun importedFonts(entry: Imported, previewOnly: Boolean): List<Font> {
        val faces = if (previewOnly) {
            listOf(entry.faces.minBy { (if (it.italic) 1000 else 0) + kotlin.math.abs(it.weight - 400) })
        } else {
            // A family can hold one face per weight and slant.
            entry.faces.distinctBy { it.weight to it.italic }
        }
        return faces.map { face ->
            Font.Builder(File(dir, "${entry.id}/${face.file}"))
                .setTtcIndex(face.index)
                .setWeight(face.weight)
                .setSlant(if (face.italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                .build()
        }
    }

    /**
     * Adds the faces in [uris], grouped into families by their font names, so picking Regular,
     * Bold and Italic files together gives one family. A family that is already there gains the
     * new faces, and a new face replaces the one it has with the same weight and slant.
     */
    suspend fun import(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val read = uris.map { uri ->
                val bytes = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                    .getOrElse { return@withContext ImportResult.Failed(ImportResult.Reason.Unreadable) }
                val faces = runCatching { OpenType.read(bytes) }
                    .getOrElse { return@withContext ImportResult.Failed(ImportResult.Reason.NotAFont) }
                val name = runCatching { displayName(uri) }
                    .getOrElse { return@withContext ImportResult.Failed(ImportResult.Reason.Unreadable) }
                Triple(name, bytes, faces)
            }
            if (read.any { (_, _, faces) -> faces.none { it.monospaced } }) {
                return@withContext ImportResult.Failed(ImportResult.Reason.NotMonospaced)
            }

            val families = imported.associateBy { it.id }.toMutableMap()
            val added = LinkedHashSet<String>()
            try {
                for ((fileName, bytes, faces) in read) {
                    for ((family, familyFaces) in faces.filter { it.monospaced }.groupBy { it.family }) {
                        val id = familyId(family) { families[it]?.name }
                        val folder = File(dir, id).apply { mkdirs() }
                        val ext = fileName.substringAfterLast('.', "ttf").lowercase().take(4)
                        val file = "${bytes.contentHashCode().toUInt().toString(16)}.$ext"
                        File(folder, file).writeBytes(bytes)
                        val existing = families[id]?.faces.orEmpty()
                        val newFaces = familyFaces.map { ImportedFace(file, it.index, it.weight, it.italic) }
                        families[id] = Imported(id, family, (newFaces + existing).distinctBy { it.weight to it.italic })
                        added += family
                        full.remove(id)
                        previews.remove(id)
                    }
                }
                save(families.values.sortedBy { it.name.lowercase() })
            } catch (e: Exception) {
                Log.w(TAG, "could not save imported fonts", e)
                return@withContext ImportResult.Failed(ImportResult.Reason.NotSaved)
            }
            // Files whose faces were all replaced.
            for (family in families.values.filter { it.name in added }) {
                val used = family.faces.map { it.file }.toSet()
                File(dir, family.id).listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
            }
            ImportResult.Added(added.toList())
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = runCatching { save(imported.filterNot { it.id == id }) }
            saved.exceptionOrNull()?.let { Log.w(TAG, "could not remove font $id", it) }
            if (saved.isSuccess) {
                File(dir, id).deleteRecursively()
                full.remove(id)
                previews.remove(id)
            }
        }
    }

    private fun save(list: List<Imported>) {
        dir.mkdirs()
        val tmp = File(dir, "imported.json.tmp")
        tmp.writeText(json.encodeToString(list))
        check(tmp.renameTo(manifest)) { "could not replace $manifest" }
        _imported.value = list
        _importedChoices.update { list.map { Choice(it.id, it.name, imported = true) } }
    }

    private fun readManifest(): List<Imported> = runCatching {
        json.decodeFromString<List<Imported>>(manifest.readText())
    }.getOrDefault(emptyList())

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "font.ttf"

    internal companion object {
        private const val TAG = "TerminalFonts"

        /**
         * The ID of the imported family named [family], from its name. A name with no Latin letters or digits, or
         * one whose ID another family has ([nameOf] gives the name of the family with an ID), gets an ID from a hash
         * of the whole name.
         */
        fun familyId(family: String, nameOf: (id: String) -> String?): String {
            val slug = family.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val id = "user-$slug"
            if (slug.isNotEmpty() && nameOf(id).let { it == null || it == family }) return id
            return "user-" + family.hashCode().toUInt().toString(16)
        }
    }
}
