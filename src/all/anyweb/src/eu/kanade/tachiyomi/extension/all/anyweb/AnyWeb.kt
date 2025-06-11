package eu.kanade.tachiyomi.extension.all.anyweb

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

val urlRegex = """^(?:https?://)?(?:[\w-]+\.)+[a-z]{2,6}(?:/\S*)?$""".toRegex()
const val INDEX_PREFIX = "index:"

const val EXCLUDE_SELECTOR_DEFAULTS = "nav, footer, header, aside, .comments"
const val EXCLUDE_KEYWORDS_DEFAULTS = "avatar, icon, profile"
const val EXCLUDE_URL_KEYWORDS_DEFAULTS = "avatar, icon, profile"
const val MAX_DIMENSIONS_DEFAULTS = "301"
const val MIN_SIZE_DEFAULTS = "10000"

class AnyWeb : ConfigurableSource, ParsedHttpSource() {

    override val name = "AnyWeb"
    override val baseUrl = "" // Placeholder, URLs come from user input
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val websiteUrl = query.removePrefix(INDEX_PREFIX).let { stripped ->
            require(urlRegex.matches(stripped)) { "Query is not a URL" }
            if (stripped.startsWith("http://") || stripped.startsWith("https://")) stripped else "http://$stripped"
        }

        return Observable.just(
            MangasPage(
                listOf(
                    SManga.create().apply {
                        title = "Click to load"
                        url = websiteUrl
                        genre = if (query.startsWith(INDEX_PREFIX)) "index" else websiteUrl // Will serve as storage for chapter list
                    },
                ),
                false,
            ),
        )
    }

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("meta[name=title]")?.attr("content")
                ?: document.title()
            author = document.selectFirst("meta[name=author]")?.attr("content")
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst("meta[name=description]")?.attr("content")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[name=image]")?.attr("content")
                ?: document.selectFirst("img")?.attr("src")
            url = document.location()
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        // choose chapter parsing strategy
        return if (manga.genre
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.contains("index") == true
        ) {
            chaptersFromIndex(manga)
        } else {
            chaptersFromGenre(manga)
        }
    }

    private fun chaptersFromGenre(manga: SManga): Observable<List<SChapter>> {
        return Observable.just(
            manga.genre?.split(",")?.map { it.trim() }?.mapIndexed { index, url ->
                SChapter.create().apply {
                    name = "Chapter ${index + 1}"
                    this.url = url
                }
            }?.reversed() ?: emptyList(),
        )
    }

    private fun chaptersFromIndex(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val document = client.newCall(GET(manga.url, headers)).execute().asJsoup()

            // Ignore links in footer/header
            val excludeSelector = preferences.getString("INDEX_EXCLUDE_SELECTOR", null) ?: EXCLUDE_SELECTOR_DEFAULTS
            document.select(excludeSelector).forEach { it.remove() }

            val maxDepth = 3

            val selectors = mutableListOf<String>()
            val weights = mutableListOf<Int>()

            for (d in 1..maxDepth) {
                val path = List(d) { "> *" }.joinToString(" ")
                val selector = ("$path > a")
                    .replace("> * > a", "> a") // fix for depth=1
                selectors += selector
                weights += maxOf(1, maxDepth - (d - 1)) // e.g. depth 1 = 3, depth 2 = 2, etc.
            }

            val candidates = document.select("*").filter { element ->
                selectors.any { sel -> element.select(sel).isNotEmpty() }
            }

            var bestScore = 0
            var bestContainer: Element? = null

            for (container in candidates) {
                var score = 0
                for ((i, sel) in selectors.withIndex()) {
                    val count = container.select(sel).size
                    score += count * weights[i]
                }

                if (score > bestScore) {
                    bestScore = score
                    bestContainer = container
                }
            }

            val chapters = bestContainer?.select("a")?.mapIndexed { index, a ->
                SChapter.create().apply {
                    name = a.text().ifBlank { "Untitled" }
                    url = a.absUrl("href")
                    chapter_number = index.toFloat()
                }
            } ?: emptyList()

            chapters.reversed()
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val excludeKeywords = preferences.getString("EXCLUDE_KEYWORDS", EXCLUDE_KEYWORDS_DEFAULTS) ?: EXCLUDE_KEYWORDS_DEFAULTS
        val excludeUrlKeywords = preferences.getString("EXCLUDE_URL_KEYWORDS", EXCLUDE_URL_KEYWORDS_DEFAULTS) ?: EXCLUDE_URL_KEYWORDS_DEFAULTS
        val excludeSelector = preferences.getString("EXCLUDE_SELECTOR", EXCLUDE_SELECTOR_DEFAULTS) ?: EXCLUDE_SELECTOR_DEFAULTS
        val minSize = preferences.getString("MIN_SIZE", MIN_SIZE_DEFAULTS)?.toIntOrNull() ?: MIN_SIZE_DEFAULTS.toInt()
        val minWidth = preferences.getString("MIN_WIDTH", MAX_DIMENSIONS_DEFAULTS)?.toIntOrNull() ?: MAX_DIMENSIONS_DEFAULTS.toInt()
        val minHeight = preferences.getString("MIN_HEIGHT", MAX_DIMENSIONS_DEFAULTS)?.toIntOrNull() ?: MAX_DIMENSIONS_DEFAULTS.toInt()

        val checkKeywords = preferences.getBoolean("CHECK_KEYWORDS", false)
        val checkUrlKeywords = preferences.getBoolean("CHECK_URL_KEYWORDS", true)
        val checkSelector = preferences.getBoolean("CHECK_SELECTOR", true)
        var checkSize = preferences.getBoolean("CHECK_SIZE", false)
        val checkDimensions = preferences.getBoolean("CHECK_DIMENSIONS", false)

        val keywords = excludeKeywords.split(",").map { it.trim() }
        val urlKeywords = excludeUrlKeywords.split(",").map { it.trim() }

        fun Element.extractImageUrl(): String? {
            val candidates = listOf(
                "src",
                "data-src",
                "data-original",
                "data-lazy",
                "data-img",
                "data-image",
                "data-thumb",
                "data-hi-res-src",
            )

            for (attr in candidates) {
                val url = absUrl(attr)
                if (url.isNotEmpty()) return url
            }

            return null
        }

        val seenUrls = mutableSetOf<String>()
        return document.getElementsByTag("img").mapIndexedNotNull { index, img ->
            val imgUrl = img.extractImageUrl() ?: return@mapIndexedNotNull null

            if (imgUrl in seenUrls) return@mapIndexedNotNull null
            seenUrls += imgUrl

            // Skip SVG images as mihon doesn't support them
            if (imgUrl.lowercase().endsWith(".svg")) return@mapIndexedNotNull null

            if (checkSelector && img.closest(excludeSelector) != null) return@mapIndexedNotNull null

            if (checkDimensions) {
                val imgWidth = img.attr("width").toIntOrNull()
                val imgHeight = img.attr("height").toIntOrNull()
                if ((imgWidth != null && imgWidth < minWidth) || (imgHeight != null && imgHeight < minHeight)) {
                    return@mapIndexedNotNull null
                }
            }

            if (checkKeywords) {
                val alt = img.attr("alt")
                val title = img.attr("title")

                if (keywords.any { alt.contains(it, ignoreCase = true) || title.contains(it, ignoreCase = true) }) {
                    return@mapIndexedNotNull null
                }
            }

            if (checkUrlKeywords) {
                if (urlKeywords.any { imgUrl.contains(it, ignoreCase = true) }) {
                    return@mapIndexedNotNull null
                }
            }

            if (checkSize) {
                val contentLength = runBlocking { getContentLength(imgUrl) }

                // Stop HEAD checks if website doesn't support it
                if (contentLength == null || contentLength == 0L) {
                    checkSize = false
                    return@mapIndexedNotNull null
                }

                if (contentLength < minSize) { return@mapIndexedNotNull null }
            }

            // If none of the checks rejected a page add it to chapter
            Page(index, "", imgUrl)
        }
    }

    private fun getContentLength(url: String): Long? {
        val request = Request.Builder()
            .url(url)
            .head() // Perform a HEAD request
            .build()

        try {
            val response: Response = client.newCall(request).execute()

            if (!response.isSuccessful) return null

            // Get Content-Length header from the response
            return response.header("Content-Length")?.toLongOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "INFO"
            title = "ℹ️ INFO"
            summary = "After changing settings below you may need to 'Clear chapter cache' in 'Settings > Data and Storage' in order to apply them to already loaded chapters."
            setEnabled(false)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = "CHECK_SELECTOR"
            title = "Exclude using CSS"
            summary = "If the image is inside an element matching the CSS selector provided below it will be excluded."
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "EXCLUDE_SELECTOR"
            title = "CSS selector"
            dialogTitle = "Enter CSS selector"
            setDefaultValue(EXCLUDE_SELECTOR_DEFAULTS)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = "CHECK_KEYWORDS"
            title = "Exclude keywords on element"
            summary = "Images with provided keywords present in their alternative text or title will be excluded."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "EXCLUDE_KEYWORDS"
            title = "Keywords"
            dialogTitle = "Enter keywords (separated by comma)"
            setDefaultValue(EXCLUDE_KEYWORDS_DEFAULTS)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = "CHECK_URL_KEYWORD"
            title = "Exclude keywords in URL"
            summary = "Images with provided keywords present in their URL will be excluded."
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "EXCLUDE_URL_KEYWORDS"
            title = "Keywords"
            dialogTitle = "Enter keywords (separated by comma)"
            setDefaultValue(EXCLUDE_URL_KEYWORDS_DEFAULTS)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = "CHECK_DIMENSIONS"
            title = "Exclude by dimensions"
            summary = "Will check width and height attributes of images and eliminate all that have at least one of them smaller than specified below."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "MIN_WIDTH"
            title = "Minimal width of image"
            dialogTitle = "Enter minimal image width in pixels"
            setDefaultValue(MAX_DIMENSIONS_DEFAULTS)
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "MIN_HEIGHT"
            title = "Minimal height of image"
            dialogTitle = "Enter minimal image height in pixels"
            setDefaultValue(MAX_DIMENSIONS_DEFAULTS)
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = "CHECK_SIZE"
            title = "Exclude by size"
            summary = "⚠️ Activating this option will make additional requests to check image size and therefore will SLOW DOWN chapter loading process (and potentially may trigger anti-spam/bot protection if website has many images). \nTurn it off to speed up loading. "
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "MIN_SIZE"
            title = "Minimal size of image"
            dialogTitle = "Enter minimal image size file in bytes"
            summary = "Provided size will be compared to Content-Length property in response to HEAD request. Content-Length may describe size after compression."
            setDefaultValue(MIN_SIZE_DEFAULTS)
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "INDEX_DEPTH"
            title = "Index Depth"
            dialogTitle = "Set Index Depth"
            summary = """
                Defines how deep the DOM is scanned to auto-detect chapter links (when searching index:<url>).
                Setting this to 1 will detect only links that are placed one after another in the DOM (e.g. in a single list or container).
                Higher values increase scan depth and may help when dealing with chapters divided into sections.
                Setting this value too high might make the extension detect all links on a webpage.
                Recommended: 1–3.
            """.trimIndent()
            setDefaultValue("3")
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "INDEX_EXCLUDE_SELECTOR"
            title = "Index: CSS selector to exclude"
            dialogTitle = "Enter CSS selector"
            setDefaultValue(EXCLUDE_SELECTOR_DEFAULTS)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "INFO_INDEX"
            title = "ℹ️ KNOWN ISSUES"
            summary = """
                🛑 Issue:
                If user search for url and then try to prefix it with `index:` that won't work (until cache is cleared).
                URL is the same for both index and non-index entries, so they are considered the same by the app (and therefore not fetched again).

                🛠️ Fix:
                If host app allows tag editing: add entry to library and add "index" tag.
            """.trimIndent()
            setEnabled(false)
        }.also(screen::addPreference)
    }

    // =================================Not Used=====================================================
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException("\n\nPaste url or index:<url> in search bar")
    override fun popularMangaSelector(): String = throw UnsupportedOperationException("Not used")
    override fun popularMangaFromElement(element: Element): SManga = throw UnsupportedOperationException("Not used")
    override fun popularMangaNextPageSelector(): String = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesFromElement(element: Element): SManga = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException("Not used")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException("Not used")

    override fun searchMangaSelector(): String = throw UnsupportedOperationException("Not used")
    override fun searchMangaNextPageSelector(): String = throw UnsupportedOperationException("Not used")
    override fun searchMangaFromElement(element: Element): SManga = throw UnsupportedOperationException("Not used")

    override fun chapterListSelector(): String = throw UnsupportedOperationException("Not used")
    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")
}
