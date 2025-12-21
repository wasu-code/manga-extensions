package kuchihige.utils

import android.app.Application
import android.content.SharedPreferences
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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


abstract class DownloadableHttpSource : HttpSource(), ConfigurableSource {
    private val context by lazy { Injekt.get<Application>() }
    private val preferences: SharedPreferences by getPreferencesLazy()
////    val a  = context.getSharedPreferences(, 0x0000).getString("__APP_STATE_storage_dir", "")
////    val storageDir = ""

    /** Scale factor used when converting PDF page to png. */
    open val pdfScale = 2
    override val client = network.client.newBuilder()
        .addInterceptor(PdfPageInterceptor(pdfScale))
        .build()

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

    private fun handleDownload(chapter: SChapter, nativelySupported: Boolean = true): UniFile {
        // Extract manga title stored in SChapter.scanlator field
        val mangaName = chapter.scanlator?.substringAfter(SEPARATOR) ?: "Unknown"

        // Find or create directory for specific manga
        val mihonUri = preferences.getString("MIHON_URI", null)?.let { Uri.parse(it) }
            ?: throw IllegalStateException("MIHON_URI not set")
        val mihonDir = UniFile.fromUri(context, mihonUri) ?: throw Exception("Invalid MIHON_URI")
        val sourceDir = "downloads/$name (${lang.uppercase()})"
        val base = sourceDir.split("/").fold(mihonDir) { acc, dir ->
            acc.findOrCreateDir(dir)
        }
        val mangaDir = base.findOrCreateDir(mangaName)

        // Modify filename so it can/cannot be recognized by host app
        val dummyExtension = if (nativelySupported) {
            ".cbz" // so it appear as downloaded when reindexing downloads
        } else {
            "_.${getFileExtension(chapter)}" // so the host doesn't try to open it as archive
        }
        val chapterFile = mangaDir.createFile("${chapter.scanlator}_${chapter.name}$dummyExtension") ?: throw Exception("Could not create chapter file")

        // Return file if it already exists. Eg. when loading pages list for PDFs
        if (chapterFile.exists() && chapterFile.length() > 0) return chapterFile

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
            title = "Revalidate cache"
            summary = "Trigger download cache revalidation after every download. Will update download indicator and make it easier to remove files. May cause unnecessary burden especially for large libraries."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    private fun UniFile.findOrCreateDir(name: String): UniFile {
        return findFile(name) ?: createDirectory(name)!!
    }

    private fun Uri.readablePath(): String =
        toString().substringAfter("tree/").replace("%3A", "/").replace("%2F", "/")

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
}
