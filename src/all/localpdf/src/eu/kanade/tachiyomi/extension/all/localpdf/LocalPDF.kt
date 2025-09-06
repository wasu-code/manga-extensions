@file:Suppress("RedundantSuspendModifier", "UNUSED_PARAMETER")

package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.days

@Suppress("unused")
class LocalPDF : HttpSource(), ConfigurableSource, UnmeteredSource {

    companion object {
        /** Extension package name */
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.localpdf"
        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds
    }

    enum class Listing {
        POPULAR,
        LATEST,
    }

    override val name = "Local PDF"
    override val lang = "other"
    override val supportsLatest = true
    override val baseUrl: String = ""

    private val context = Injekt.get<Application>()
    private val preferences: SharedPreferences by getPreferencesLazy()

    private val mihonUri = preferences.getString("MIHON_URI", null)?.let { Uri.parse(it) }

    private fun getInputDir(): UniFile? {
        return mihonUri?.let {
            UniFile.fromUri(context, it)
                ?.findFile("localpdf")
        }
    }

    private val scale = preferences.getString("SCALE", null)?.toIntOrNull() ?: 2
    override val client = super.client.newBuilder()
        .addInterceptor(PdfPageInterceptor(context, getInputDir(), scale))
        .build()

    @Suppress("unused")
    suspend fun getPopularManga(page: Int): MangasPage = getManga(page, "", Listing.POPULAR)

    @Suppress("unused")
    suspend fun getLatestUpdates(page: Int): MangasPage = getManga(page, "", Listing.LATEST)

    @Suppress("unused")
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = getManga(page, query, Listing.POPULAR)

    private suspend fun getManga(page: Int, query: String, listing: Listing): MangasPage {
        val inputDir = getInputDir() ?: throw IllegalStateException("Input directory URI is not set.\nPlease set it first in the extension's settings.")

        val lastModifiedLimit = if (listing == Listing.LATEST) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }

        val mangaDirs = inputDir.listFiles()
            // Filter out files that are hidden and is not a folder
            ?.filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            ?.distinctBy { it.name }
            ?.filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true // Popular (all)
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true) // Search
                } else {
                    it.lastModified() >= lastModifiedLimit // Latest
                }
            }
            ?.sortedByDescending(UniFile::lastModified)
            ?: emptyList()

        val mangaList = mangaDirs.map { dir ->
            SManga.create().apply {
                title = dir.name ?: "Unknown"
                url = dir.name ?: "unknown"

                val coverFile = getOrCreateCover(dir)
                coverFile?.let {
                    thumbnail_url = it.uri.toString()
                }
                initialized = true
            }
        }

        return MangasPage(mangaList, hasNextPage = false)
    }

    @Suppress("unused")
    suspend fun getMangaDetails(manga: SManga): SManga {
        val mangaDir = getInputDir()?.findFile(manga.url) ?: return manga
        val coverFile = getOrCreateCover(mangaDir)
        return manga.apply {
            coverFile?.let {
                thumbnail_url = it.uri.toString()
            }
        }
    }

    private fun getOrCreateCover(mangaDir: UniFile): UniFile? {
        val existingCover = mangaDir.listFiles()
            ?.firstOrNull { file ->
                val nameWithoutExt = file.name?.substringBeforeLast('.')?.lowercase() ?: return@firstOrNull false
                nameWithoutExt == "cover" && isImage(file)
            }

        if (existingCover != null) return existingCover

        val firstPdf = mangaDir.listFiles()
            ?.filter { it.name?.endsWith(".pdf", ignoreCase = true) == true }
            ?.minByOrNull { it.name.orEmpty() }
            ?: return null

        val descriptor = context.contentResolver.openFileDescriptor(firstPdf.uri, "r") ?: return null
        val renderer = PdfRenderer(descriptor)

        val page = renderer.openPage(0)
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        val coverFile = mangaDir.createFile("cover.jpg")
        context.contentResolver.openOutputStream(coverFile!!.uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        bitmap.recycle()
        renderer.close()
        descriptor.close()

        return coverFile
    }

    private fun isImage(file: UniFile): Boolean {
        val type = context.contentResolver.getType(file.uri) ?: return false
        return type.startsWith("image/")
    }

    @Suppress("unused")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        val inputDir = getInputDir()
        val mangaDir = inputDir?.findFile(manga.url)?.takeIf { it.isDirectory }

        val pdfFilesAndNumbers = mangaDir?.listFiles()
            ?.filter { it.name?.endsWith(".pdf", ignoreCase = true) == true }
            ?.mapNotNull { pdf ->
                val chapterName = pdf.name?.removeSuffix(".pdf") ?: return@mapNotNull null
                val chapterNumber = ChapterRecognition.parseChapterNumber(manga.title, chapterName)
                Pair(pdf, chapterNumber)
            }
            ?.sortedByDescending { it.second }
            ?: emptyList()

        return pdfFilesAndNumbers.map { (pdf, chapterNumber) ->
            SChapter.create().apply {
                name = pdf.name?.removeSuffix(".pdf") ?: "chapter"
                url = "${manga.url}/${pdf.name}"
                date_upload = pdf.lastModified()
                chapter_number = chapterNumber.toFloat()
            }
        }
    }

    @Suppress("unused")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaName = chapter.url.substringBefore("/")
        val chapterFileName = chapter.url.substringAfter("/")

        val inputDir = getInputDir()
        val pdfFile = inputDir
            ?.findFile(mangaName)
            ?.findFile(chapterFileName)

        if (pdfFile == null) {
            return emptyList()
        }

        val descriptor = context.contentResolver.openFileDescriptor(pdfFile.uri, "r")
        val renderer = PdfRenderer(descriptor!!)
        val pageCount = renderer.pageCount
        renderer.close()
        descriptor.close()

        val pages = (0 until pageCount).map { pageIndex ->
            Page(index = pageIndex, imageUrl = "http://localpdf/$mangaName/$chapterFileName/page${String.format("%03d", pageIndex)}")
        }

        return pages
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "MIHON_URI"
            val appName = context.applicationInfo.loadLabel(context.packageManager)
            title = "URI to $appName's root directory"
            dialogTitle = "[...]/$appName"
            summary = """
                Same as in "Settings » Data and storage » Storage location".
                Current: ${preferences.getString(key, "Not set")}
            """.trimIndent()
            setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue as String
                preference.summary = """
                    Same as "Settings » Data and storage » Storage location".
                    Current: $value
                """.trimIndent()
                Toast.makeText(context, "Restart app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
            setOnPreferenceClickListener {
                val intent = Intent().apply {
                    setClassName(PACKAGE_NAME, UriPickerActivity::class.java.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "INFO_INPUT_DIR"
            title = ""
            val appName = context.applicationInfo.loadLabel(context.packageManager)
            summary = """Example folder structure:
              /storage/emulated/0/${appName}/localpdf/
              ├── seriesName1/
              │   ├── ch1.pdf
              │   └── ch2.pdf
              ├── seriesName2/
              └── seriesName3/
            """.trimIndent()
            setEnabled(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "SCALE"
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
                Toast.makeText(context, "Restart app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException("Not Used")
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not Used")
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not Used")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException("Not Used")
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not Used")
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not Used")
}
