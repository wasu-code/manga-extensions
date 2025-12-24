package kuchihige.utils

import android.app.Application
import android.content.SharedPreferences
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlin.getValue

/** Separator between manga title and scanlator in [SChapter.scanlator] field. Used to easily retrieve title in places where only [SChapter] is provided. */
private const val SEPARATOR = " • "
/** List of file extensions supported by [DownloadableHttpSource]  */
val SUPPORTED_EXTENSIONS = listOf("zip", "cbz", "epub", "pdf")
/**
 * List of file extensions that are natively supported by Mihon.
 * Those filetypes can be directly opened when placed in /downloads directory
 */
private val NATIVELY_SUPPORTED_EXTENSIONS = listOf("zip", "cbz", "epub")
private const val MAX_FILE_NAME_BYTES = 250

abstract class DownloadableHttpSource : HttpSource(), ConfigurableSource {
    private val context by lazy { Injekt.get<Application>() }
    private val preferences: SharedPreferences by getPreferencesLazy()
////    val a  = context.getSharedPreferences(, 0x0000).getString("__APP_STATE_storage_dir", "")
////    val storageDir = ""
    /** Whether or not source provides chapters in PDF format. Formats like zip/cbz/epub are independent from this setting */
    open val supportsPDFs = true
    override val client by lazy {
        network.client.newBuilder()
            .apply {
                if (supportsPDFs) {
                    val pdfScale = preferences.getString("PDF_SCALE", null)?.toIntOrNull() ?: 2
                    addInterceptor(PdfPageInterceptor(pdfScale))
                }
            }
            .build()
    }

    /**
     * Appends manga title to scanlator (after a [SEPARATOR]) so it can be retrieved when constructing download path.
     */
    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        chapter.scanlator = chapter.scanlator + SEPARATOR + manga.title
    }

    /**
     * Handles downloading a chapter file if one of [SUPPORTED_EXTENSIONS].
     * @param chapter The chapter to download. Must have [SChapter.url] set to direct download link.
     * @return List of pages for the chapter (for PDF only).
     * @throws Exception with message "Download completed" when chapter downloaded.
     * @throws UnsupportedOperationException when file extension is not supported.
     */
    final override suspend fun getPageList(chapter: SChapter): List<Page> {
        val fileExtension = getFileExtension(chapter)

        if (fileExtension !in SUPPORTED_EXTENSIONS)
            throw UnsupportedOperationException("Unsupported file extension: $fileExtension")

        val nativelySupported = fileExtension in NATIVELY_SUPPORTED_EXTENSIONS
        val file = handleDownload(chapter, nativelySupported)

        // Trigger cache revalidation based on user preferences
        // Revalidates cache for ALL chapters in host app
        val shouldRevalidateCache = preferences.getBoolean("REVALIDATE_CACHE", false)
        if (nativelySupported && shouldRevalidateCache) {
            try {
                val downloadCache: DownloadCache = Injekt.get()
                downloadCache.invalidateCache()
            } catch (_: Exception) {}
        }

        // Dummy URLs for PDF pages, will then use [PdfPageInterceptor] to load images from stored PDF
        // Necessary as opening PDFs is not natively supported by Mihon
        if (fileExtension == "pdf") {
            val descriptor = context.contentResolver.openFileDescriptor(file.uri, "r")
            val renderer = PdfRenderer(descriptor!!)
            val pageCount = renderer.pageCount
            descriptor.close()
            renderer.close()

            return List(pageCount) { Page(it, imageUrl = PdfPageInterceptorHelper.createUrl(file.uri, it)) }
        }

        // Task failed successfully
        // Throws error to show toast. Chapter can be then natively opened by Mihon
        throw Exception("Download completed")
    }

    private fun handleDownload(chapter: SChapter, isFormatSupported: Boolean = true): UniFile {
        // Extract manga title stored in SChapter.scanlator field
        val mangaName = chapter.scanlator?.substringAfter(SEPARATOR) ?: "Unknown"

        // Find or create directory for specific manga
        val mihonUri = preferences.getString("MIHON_URI", null)?.let { Uri.parse(it) }
            ?: throw IllegalStateException("MIHON_URI not set")
        val mihonDir = UniFile.fromUri(context, mihonUri) ?: throw Exception("Invalid MIHON_URI")

        val sourceDirName = buildValidFilename((this as Source).toString())
        val mangaDirName = buildValidFilename(mangaName)
        val chapterDirName: () -> String = {
            var dirName = chapter.scanlator + "_" + chapter.name
            // Subtract 7 bytes for hash and underscore, 5 bytes for .cbz
            dirName = buildValidFilename(dirName, MAX_FILE_NAME_BYTES - 4)
            dirName
        }
        // Modify filename so it can/cannot be recognized by host app
        val dummyExtension = if (isFormatSupported) {
            ".cbz" // so it appear as downloaded when reindexing downloads
        } else {
            "_.${getFileExtension(chapter)}" // so the host doesn't try to open it as archive
        }

        val chapterFileName = chapterDirName() + dummyExtension
        val chapterFile = mihonDir
            .getDir("downloads")
            .getDir(sourceDirName)
            .getDir(mangaDirName)
            .getFile(chapterFileName)
        Log.d("DownloadableHttpSource", "chapterFile: ${chapterFile.uri}")
        // If already downloaded return it
        if (chapterFile.length() > 0) return chapterFile

        // Download file contents
        downloadToFile(chapter.url, chapterFile)
        return chapterFile
    }

    /**
     * Determines file extension for a chapter.
     * By default uses string after last dot in [SChapter.url] to determine extension.
     * @param chapter The chapter to get extension for.
     * @return File extension.
     */
    open fun getFileExtension(chapter: SChapter): String =
        chapter.url.substringAfterLast(".").lowercase()

    /**
     * Setup preferences necessary for downloading.
     * Don't forget to call `super.setupPreferenceScreen(screen)` when overriding.
     * @param screen The screen to add preferences to.
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        ListPreference(screen.context).apply {
            key = "MIHON_URI"
            val appName = context.applicationInfo.loadLabel(context.packageManager)
            title = "$appName's root directory"
            summary = """
                Same as in "Settings » Data and storage » Storage location".
            """.trimIndent()
            val availableUris = context.contentResolver.persistedUriPermissions
            entries = availableUris.map { it.uri.readablePath() }.toTypedArray()
            entryValues = availableUris.map { it.uri.toString() }.toTypedArray()
            setDefaultValue(availableUris.firstOrNull()?.uri?.toString())
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "REVALIDATE_CACHE"
            title = "Revalidate cache after downloading"
            summary = "Trigger download cache revalidation after every download. Will update download indicator and make it easier to remove files. May cause unnecessary burden especially for large libraries."
            setDefaultValue(true)
        }.also(screen::addPreference)

        if (supportsPDFs) {
            EditTextPreference(screen.context).apply {
                key = "PDF_SCALE"
                title = "Scale Factor"
                dialogTitle = "Set Scale Factor"
                val currentScale = preferences.getString(key, "2")
                summary = """
                    Set the scale for PDF rendering.
                    Current: $currentScale (default: 2)
                """.trimIndent()
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                setOnPreferenceChangeListener { _, newValue ->
                    summary = """
                        Set the scale for PDF rendering.
                        Current value: $newValue (default: 2)
                    """.trimIndent()
                    Toast.makeText(context, "Restart app and clear chapter cache to apply changes", Toast.LENGTH_LONG).show()
                    true
                }
            }.also(screen::addPreference)
        }
    }

    /**
     * Provide custom messages for HTTP status codes encountered during download process.
     * May be used to provide more helpful error messages and hints to user.
     * @return Map of HTTP status codes to messages.
     */
    open val downloadErrors: Map<Int, String> = mapOf(
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not Found",
        413 to "Payload Too Large",
        429 to "Too Many Requests",
        500 to "Server Error",
    )

    private fun downloadToFile(url: String, targetFile: UniFile) {
        val request = GET(url)
        val response = network.client.newCall(request).execute()

        if (response.code in downloadErrors) throw Exception(downloadErrors[response.code])
        if (!response.isSuccessful) throw Exception("Unexpected code ${response.code}: ${response.message}")

        response.body.byteStream().use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun Uri.readablePath(): String =
        toString().substringAfter("tree/").replace("%3A", "/").replace("%2F", "/")

    /** Finds or creates a directory */
    private fun UniFile.getDir(name: String): UniFile {
        return findFile(name)
            ?: createDirectory(name)
            ?: throw Exception("Failed to create directory")
    }

    /** Finds or creates a file */
    private fun UniFile.getFile(name: String): UniFile {
        return findFile(name)
            ?: createFile(name)
            ?: throw Exception("Failed to create file")
    }

    //// From mihon

    private fun buildValidFilename(
        origName: String,
        maxBytes: Int = MAX_FILE_NAME_BYTES,
    ): String {
        val name = origName.trim('.', ' ')
        if (name.isEmpty()) {
            return "(invalid)"
        }
        val sb = StringBuilder(name.length)
        name.forEach { c ->
            if (isValidFatFilenameChar(c)) {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        return truncateToLength(sb.toString(), maxBytes)
    }

    private fun truncateToLength(s: String, maxBytes: Int): String {
        val charset = Charsets.UTF_8
        val decoder = charset.newDecoder()
        val sba = s.toByteArray(charset)
        if (sba.size <= maxBytes) {
            return s
        }
        // Ensure truncation by having byte buffer = maxBytes
        val bb = ByteBuffer.wrap(sba, 0, maxBytes)
        val cb = CharBuffer.allocate(maxBytes)
        // Ignore an incomplete character
        decoder.onMalformedInput(CodingErrorAction.IGNORE)
        decoder.decode(bb, cb, true)
        decoder.flush(cb)
        return String(cb.array(), 0, cb.position())
    }

    private fun isValidFatFilenameChar(c: Char): Boolean {
        if (0x00.toChar() <= c && c <= 0x1f.toChar()) {
            return false
        }
        return when (c) {
            '"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7f.toChar() -> false
            else -> true
        }
    }
}
