package kuchihige.utils

import android.app.Application
import android.content.SharedPreferences
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

private const val SEPARATOR = " • "

abstract class DownloadableHttpSource : HttpSource(), ConfigurableSource {

    private val context by lazy { Injekt.get<Application>() }
    private val preferences: SharedPreferences by getPreferencesLazy()
////    val a  = context.getSharedPreferences(, 0x0000).getString("__APP_STATE_storage_dir", "")
////    val storageDir = ""


    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        chapter.scanlator = chapter.scanlator + SEPARATOR + manga.title
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        downloadChapter(chapter)

        val shouldRevalidateCache = preferences.getBoolean("REVALIDATE_CACHE", false)
        if (shouldRevalidateCache) {
            try {
                val downloadCache: DownloadCache = Injekt.get()
                downloadCache.invalidateCache()
            } catch (_: Exception) {}
        }

        // Task failed successfully
        throw Exception("Download completed")
    }

    open fun getFileExtension(chapter: SChapter): String =
        chapter.url.substringAfterLast(".").lowercase()

    private fun downloadChapter(chapter: SChapter) {
        val fileExtension = getFileExtension(chapter)
        when (fileExtension) {
            // TODO use page interceptor and pdf as lib
//            "pdf" -> handleDownload(chapter, "localpdf")
            in listOf("zip", "cbz", "epub") -> handleDownload(chapter)
            else -> throw UnsupportedOperationException("Unsupported file extension: $fileExtension")
        }
    }

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

    fun handleDownload(chapter: SChapter): UniFile {
        val mangaName = chapter.scanlator?.substringAfter(SEPARATOR) ?: "Unknown"

        val mihonUri = preferences.getString("MIHON_URI", null)?.let { Uri.parse(it) }
            ?: throw IllegalStateException("MIHON_URI not set")
        val downloadRoot = UniFile.fromUri(context, mihonUri) ?: throw Exception("Invalid MIHON_URI")

        val sourceDir = "downloads/$name (${lang.uppercase()})"
        val base = sourceDir.split("/").fold(downloadRoot) { acc, dir ->
            acc.findOrCreateDir(dir)
        }
        val mangaDir = base.findOrCreateDir(mangaName)

        val dummyExtension = ".cbz" // so it appear as downloaded when reindexing downloads
        val chapterFile = mangaDir.createFile("${chapter.scanlator}_${chapter.name}$dummyExtension") ?: throw Exception("Could not create chapter file")

        // Download file contents
        downloadToFile(chapter.url, chapterFile)
        return chapterFile
    }

    fun UniFile.findOrCreateDir(name: String): UniFile {
        return findFile(name) ?: createDirectory(name)!!
    }

    fun Uri.readablePath(): String =
        toString().substringAfter("tree/").replace("%3A", "/").replace("%2F", "/")

    /**
     * Provide custom messages for HTTP status codes encountered during downloading.
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
