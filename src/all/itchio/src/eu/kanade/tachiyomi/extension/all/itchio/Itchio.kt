package eu.kanade.tachiyomi.extension.all.itchio

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.lib.textinterceptor.TextInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.getValue

private const val SEPARATOR = " • "
private val REGEX_URL_CHAPTER = Regex("^https?://([a-zA-Z0-9-]+\\.)+itch\\.io/.+")
private val REGEX_URL_USERPAGE = Regex("^https?://([a-zA-Z0-9-]+\\.)+itch\\.io/?$")

class Itchio : HttpSource(), ConfigurableSource {
    override val baseUrl = "https://itch.io/comics"
    override val lang = "all"
    override val supportsLatest = true
    override val name = "Itch.io"

    private val context by lazy { Injekt.get<Application>() }
    private val preferences: SharedPreferences by getPreferencesLazy()
    override val client = network.client.newBuilder()
        .addInterceptor(TextInterceptor())
        .build()

//    https://itch.io/my-purchases

    private fun comicsParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val cards = document.select(".game_grid_widget .game_cell")
        val comics = cards.map { card ->
            SManga.create().apply {
                title = card.selectFirst(".game_title")!!.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("data-lazy_src")
                url = card.selectFirst("a")!!.absUrl("href")
                    .replace(Regex("/download/[^/]+/?"), "/") // fix for my-purchases page
            }
        }

        val hasNextPage = document.selectFirst(".next_page") != null
        return MangasPage(comics, hasNextPage)
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/free?page=$page&format=html")

    override fun popularMangaParse(response: Response): MangasPage = comicsParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("https://itch.io/my-purchases")

    override fun latestUpdatesParse(response: Response): MangasPage = comicsParse(response)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.matches(REGEX_URL_CHAPTER)) {
            return MangasPage(listOf(SManga.create().apply { url = query }), false)
        }
        return super.getSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        // Handle showing user page
        if (query.matches(REGEX_URL_USERPAGE)) return GET(query)
        val user = filters.findInstance<UserFilter>()?.state
        if (user != null) return GET("https://$user.itch.io/")

        // Handle searching
        if (query.isNotBlank()) return GET("https://itch.io/search?q=$query&classification=comic")

        // Handle filtering
        val sortingState: Int = filters.findInstance<SortingFilter>()?.state ?: 0
        val sorting: String = SORTING.values.elementAt(sortingState)

        val priceState = filters.findInstance<PriceFilter>()?.state ?: 0
        val price = PRICE.values.elementAt(priceState)

        val tagFilters = filters.findInstance<TagsFilter>()?.state ?: emptyList()
        val selectedTagFilters = tagFilters.filter { it.state }
        val selectedTagNames = selectedTagFilters.map { it.name }
        val tags = cachedTags
            ?.filter { it.name in selectedTagNames }
            ?.joinToString("") { it.url }
            ?: ""

        return GET("$baseUrl$sorting$price$tags")
    }

    override fun searchMangaParse(response: Response): MangasPage = comicsParse(response)

    override fun getFilterList(): FilterList {
        try {
            fetchTags()
        } catch (_: Exception) {}

        return FilterList(
            SortingFilter(),
            PriceFilter(),
            TagsFilter(cachedTags?.map { it.name } ?: emptyList()),
            Filter.Header("Below will ignore other filters"),
            UserFilter(),
        )
    }

    private var cachedTags: List<Tag>? = null

    private fun fetchTags() {
        cachedTags?.let { return }

        val url = "https://itch.io/tags.json?format=browse&nsfw=true&classification=comic"
        val request = GET(url)
        val response = client.newCall(request).execute()

        val tags = response.parseAs<TagsResponse>().tags
        val sortedTags = tags.sortedByDescending { it.primary }

        cachedTags = sortedTags
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(manga.url)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val info = document.selectFirst(".game_info_panel_widget")!!

        return SManga.create().apply {
            title = document.selectFirst(".game_title")!!.text()
            description = document.selectFirst(".formatted_description")?.wholeText() + "\n\n" + document.select(
                ".screenshot_list img",
            ).joinToString("\n\n") { "![preview](${it.absUrl("src")})" }
            thumbnail_url = document.selectFirst("meta[property=\"og:image\"]")?.absUrl("content")
            status = when (info.selectFirst("td:contains(Status) + td")?.text()) {
                "In development" -> SManga.ONGOING
                "Released" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = info.select("td:contains(Tags) + td a").joinToString { it.text() }
            author = info.selectFirst("td:contains(Author) + td a")?.text()
            url = document.location()
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(manga.url)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val purchasedBanner = document.selectFirst(".purchase_banner")
        // get purchased
        if (purchasedBanner != null) {
            val ownershipReason = purchasedBanner.selectFirst(".ownership_reason")?.text()
            val fileListUrl = purchasedBanner.selectFirst("a")!!.absUrl("href")
            val fileListResponse = network.client.newCall(
                GET(fileListUrl),
            ).execute()
            val fileList = fileListResponse.asJsoup().select(".upload")

            return fileList.map {
                val fileId = it.selectFirst(".download_btn")!!.attr("data-upload_id")
                val downloadUrl = fileListUrl.replace(
                    "/download/",
                    "/file/$fileId?source=game_download&key=",
                )

                val response = network.client.newCall(
                    POST(downloadUrl),
                ).execute()
                val body = response.body.string()
                val json = JSONObject(body)
                val downloadUrl2 = json.getString("url")

                SChapter.create().apply {
                    name = it.selectFirst(".upload_name .name")!!.text()
                    url = downloadUrl2
                    date_upload = SimpleDateFormat("dd MMMM yyyy '@' HH:mm z", Locale.US)
                        .tryParse(
                            it.selectFirst(".upload_date")
                                ?.attr("title"),
                        )
                    scanlator = listOfNotNull(
                        ownershipReason,
                        it.selectFirst(".file_size")?.text(),
                    ).joinToString()
                }
            }
        }

        val directDownloads = document.select(".upload:has(.download_btn)")
        // get free
        if (directDownloads.isNotEmpty()) {
            return directDownloads.map {
                val uploadId = it.selectFirst(".download_btn")!!.attr("data-upload_id")
                SChapter.create().apply {
                    name = it.selectFirst(".upload_name .name")!!.text()
                    url = "https://api.itch.io/uploads/$uploadId/download"
                    scanlator = listOfNotNull(
                        "Free",
                        it.selectFirst(".file_size")?.text(),
                    ).joinToString()
                    date_upload = SimpleDateFormat("dd MMMM yyyy '@' HH:mm z", Locale.US)
                        .tryParse(
                            it.selectFirst(".upload_date")
                                ?.attr("title"),
                        )
                }
            }
        }

        // donation based - can't get
        throw Exception("Purchase comic to display chapter")
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val fileExtension = chapter.name.substringAfterLast(".").lowercase()

        when (fileExtension) {
            // TODO use page interceptor and pdf as lib
            "pdf" -> handleDownload(chapter, "localpdf")
            in listOf("zip", "cbz", "epub") -> handleDownload(chapter, "downloads/$name (${lang.uppercase()})")
            else -> throw UnsupportedOperationException("Unsupported file format")
        }

        // Show dummy page to let user know download is complete (and so it doesn't throw errors)
        // TODO add actual dummy pages when dealing with pdf
//        return Observable.just(
//            listOf(
//                Page(
//                    0,
//                    "",
//                    TextInterceptorHelper.createUrl(
//                        "Download completed",
//                        "Reopen the chapter to start reading",
//                    ),
//                    file.uri,
//                ),
//            ),
//        )

        // Reindex download to show in UI that chapter was updated
        // TODO: make it optional
        val downloadCache: DownloadCache = Injekt.get()
        downloadCache.invalidateCache()

        // Task failed successfully
        throw Exception("Download completed")
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        chapter.scanlator = chapter.scanlator + SEPARATOR + manga.title
    }

    fun handleDownload(chapter: SChapter, baseDir: String): UniFile {
        val mangaName = chapter.scanlator?.substringAfter(SEPARATOR) ?: "Unknown"

        val mihonUri = preferences.getString("MIHON_URI", null)?.let { Uri.parse(it) }
            ?: throw IllegalStateException("MIHON_URI not set")
        val downloadRoot = UniFile.fromUri(context, mihonUri) ?: throw Exception("Invalid MIHON_URI")

        val base = baseDir.split("/").fold(downloadRoot) { acc, dir ->
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

    fun downloadToFile(url: String, targetFile: UniFile) {
        val request = GET(url)
        val response = network.client.newCall(request).execute()

        if (response.code == 401) throw Exception("Unauthorized. Login in WebView")
        if (response.code == 403) throw Exception("Forbidden. Refresh chapter list")
        if (!response.isSuccessful) throw Exception("Unexpected code ${response.code}: ${response.message}")

        response.body.byteStream().use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    // TODO komikku recommendations https://itch.io/games-like/3108307/the-cummoner-26-teachers-petting
//    override fun relatedMangaListRequest(manga: SManga): Request {
//        return super.relatedMangaListRequest(manga)
//    }
//
//    override fun relatedMangaListParse(response: Response): List<SManga> {
//        return super.relatedMangaListParse(response)
//    }

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
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    fun Uri.readablePath(): String =
        toString().substringAfter("tree/").replace("%3A", "/").replace("%2F", "/")

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not Used")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not Used")
}
