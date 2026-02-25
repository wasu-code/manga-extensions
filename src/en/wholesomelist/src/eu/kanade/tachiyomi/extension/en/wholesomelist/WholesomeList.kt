package eu.kanade.tachiyomi.extension.en.wholesomelist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.utils.parseAs
import kuchihige.source.AggregatorSource
import kuchihige.source.TargetSource
import kuchihige.utils.get
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class WholesomeList : AggregatorSource() {

    override val name = "Wholesome Hentai God List"
    override val lang = "en"
    override val supportsLatest = true
    override val baseUrl = "https://wholesomelist.com"

    val wholesomeList by lazy {
        val req = GET("$baseUrl/api/list")
        val res = client.newCall(req).execute()
        res.parseAs<ListResponse>().entries
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/features")

    override fun popularMangaParse(response: Response): MangasPage {
        val arr = response.parseAs<FeaturedResponse>().entries

        val mangas = arr.map {
            SManga.create().apply {
                title = it.title
                author = it.author
                genre = it.tier
                setUrlWithoutDomain(it.url)
                thumbnail_url = it.thumbnail_url
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/updates")

    override fun latestUpdatesParse(response: Response): MangasPage {
        val arr = response.parseAs<UpdatesResponse>().entries

        val mangas = arr.map {
            SManga.create().apply {
                title = it.title
                author = it.author
                genre = it.tier
                setUrlWithoutDomain(it.url)
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        // used only for browsing with filters, not searching
        if (query.isNotBlank()) throw UnsupportedOperationException("Not Used")

        // filtering
        val tierFilter = filters.get<TierFilter>()
        val sortFilter = filters.get<SortFilter>()

        val selectedTier = tierFilter.let { it.values[it.state] }
        val pageLength = filters.get<PageLengthFilter>().state.trim()
        val pageLengthMode = filters.get<PageLengthModeFilter>().let {
            it.values[it.state]
        }
        val tagsMode = filters.get<TagsModeFilter>().let {
            it.values[it.state]
        }
        val selectedTags = filters.get<TagsFilter>().state
            .filter { it.state }
            .map { it.name }
        val authorQuery = filters.get<AuthorFilter>().state.trim()
        val parodyState = filters.get<ParodyFilter>().state
        val parodyQuery = filters.get<ParodyOfFilter>().state.trim()
        val sortState = sortFilter.state

        var filtered = wholesomeList.filter { manga ->
            (tierFilter.state == 0 || manga.tier == selectedTier) &&
                (
                    pageLength.isEmpty() || manga.pages == null || manga.pages.let {
                        when (pageLengthMode) {
                            "Min" -> it >= pageLength.toInt()
                            "Max" -> it <= pageLength.toInt()
                            else -> throw Exception("Unknown page length mode")
                        }
                    }
                    ) &&
                (
                    selectedTags.isEmpty() || selectedTags.let { selected ->
                        when (tagsMode) {
                            "AND" -> selected.all { it in manga.tags }
                            "OR" -> selected.any { it in manga.tags }
                            else -> throw Exception("Unknown tags mode")
                        }
                    }
                    ) &&
                (authorQuery.isEmpty() || manga.author.contains(authorQuery, ignoreCase = true)) &&
                when (parodyState) {
                    Filter.TriState.STATE_INCLUDE -> manga.parody != null
                    Filter.TriState.STATE_EXCLUDE -> manga.parody == null
                    else -> true
                } &&
                (parodyQuery.isEmpty() || manga.parody?.contains(parodyQuery, ignoreCase = true) == true)
        }

        if (sortState?.index != 0) {
            val tierRank = sortFilter.values.withIndex().associate { it.value to it.index }
            filtered = if (sortState?.ascending == true) {
                filtered.sortedBy { tierRank[it.tier] }
            } else {
                filtered.sortedByDescending { tierRank[it.tier] }
            }
        }

        val mangas = filtered.map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val uuid = manga.url.substringAfterLast("/")
        val manga = wholesomeList.find { it.uuid == uuid } ?: throw Exception("Manga not found")
        return Observable.just(manga.toSManga())
    }

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException("Not Used")

    override fun getSourceList(manga: SManga): List<TargetSource> {
        val wholesomeManga = wholesomeList.find { it.uuid == manga.url.substringAfterLast("/") } ?: return emptyList()

        return listOfNotNull(
            wholesomeManga.nh?.let {
                TargetSource(
                    name = "\uD80C\uDDA9n\uD80C\uDDAA NHentai",
                    query = it.substringAfterLast("/"),
                    packageName = "eu.kanade.tachiyomi.extension.all.nhentai",
                )
            },
            // The below won't work for forks with delegated sources
            wholesomeManga.eh?.let {
                TargetSource(
                    name = "E-Hentai",
                    query = manga.title,
                    packageName = "eu.kanade.tachiyomi.extension.all.ehentai",
                )
            },
            wholesomeManga.im?.let {
                TargetSource(
                    name = "Image Chest",
                    query = it,
                    packageName = "eu.kanade.tachiyomi.extension.all.cubari",
                    note = "using Cubari extension",
                )
            },
        )
    }

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("ℹ️ Click [Filter] button without setting any fields to display the whole list"),
            SortFilter(),
            Filter.Separator(),
            TierFilter(),
            PageLengthFilter(),
            PageLengthModeFilter(),
            TagsFilter(),
            TagsModeFilter(),
            AuthorFilter(),
            ParodyFilter(),
            ParodyOfFilter(),
        )
    }

    // Filters
    class SortFilter : Filter.Sort("Sort", arrayOf("Default", "Tier"))
    class TierFilter : Filter.Select<String>("Tier", arrayOf("All", "S", "S-", "A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D", "D-"))
    class PageLengthFilter : Filter.Text("Number of pages")
    class PageLengthModeFilter : Filter.Select<String>("Number of pages constraint", arrayOf("Min", "Max"))
    class TagFilter(name: String) : Filter.CheckBox(name)
    class TagsFilter : Filter.Group<Filter.CheckBox>(
        "Tags",
        listOf(
            "Anal",
            "Childhood Friend",
            "Chubby",
            "College",
            "Couple",
            "Coworker",
            "Dark Skin",
            "Demon Girl",
            "Elf",
            "Femdom",
            "Flat Chested",
            "Full Color",
            "Futanari",
            "Gender Bender",
            "Ghost Girl",
            "Group",
            "Gyaru",
            "Handholding",
            "High School",
            "Kemonomimi",
            "Kuudere",
            "Maid",
            "MILF",
            "Monster Boy",
            "Monster Girl",
            "Parents",
            "Robot Girl",
            "Short",
            "Shy",
            "Tall",
            "Teacher",
            "Tomboy",
            "Tsundere",
            "Uncensored",
            "Yaoi",
            "Yuri",
        ).map { TagFilter(it) },
    )
    class TagsModeFilter : Filter.Select<String>("Tags mode", arrayOf("AND", "OR"))
    class AuthorFilter : Filter.Text("Author")
    class ParodyFilter : Filter.TriState("Parody")
    class ParodyOfFilter : Filter.Text("Parody of")

    // Helpers
    fun ListEntry.toSManga() = this.let {
        SManga.create().apply {
            title = it.title
            author = it.author
            genre = it.tags.joinToString()
            description = it.description
            thumbnail_url = it.thumbnail_url
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
            setUrlWithoutDomain(it.url)
        }
    }

    // Unused
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not Used")
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = throw UnsupportedOperationException("Not Used")

//    override fun relatedMangaListParse(response: Response): List<SManga> {
//        return response.parseAs<MangaObjectDto>().mangaPage.recommendations(baseUrl)
//    }
}
