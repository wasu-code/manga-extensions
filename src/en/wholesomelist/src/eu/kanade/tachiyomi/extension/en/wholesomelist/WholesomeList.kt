package eu.kanade.tachiyomi.extension.en.wholesomelist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import kuchihige.source.AggregatorSource
import kuchihige.source.TargetSource
import kuchihige.utils.get
import okhttp3.Request
import okhttp3.Response

class WholesomeList : AggregatorSource() {

    override val name = "Wholesome Hentai God List"
    override val lang = "en"
    override val supportsLatest = true
    override val baseUrl = "https://wholesomelist.com"

    val wholesomeList by lazy {
        val req = GET("$baseUrl/api/list")
        val res = client.newCall(req).execute()
        res.parseAs<ListResponse>().table
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/features")

    override fun popularMangaParse(response: Response): MangasPage {
        val arr = response.parseAs<FeaturedResponse>().table

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
        val arr = response.parseAs<UpdatesResponse>().table

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
        val pageLengthFilter = filters.get<PageLengthFilter>()
        val tagsFilter = filters.get<TagsFilter>()
        val authorFilter = filters.get<AuthorFilter>()
        val parodyFilter = filters.get<ParodyFilter>()
        val sortFilter = filters.get<SortFilter>()

        val selectedTier = tierFilter.values[tierFilter.state]
        val pageLength = pageLengthFilter.state.trim()
        val selectedTags = tagsFilter.state
            .filter { it.state }
            .map { it.name }
        val authorQuery = authorFilter.state.trim()
        val parodyState = parodyFilter.state
        val sortState = sortFilter.state

        var filtered = wholesomeList.filter { manga ->
            (tierFilter.state == 0 || manga.tier == selectedTier) &&
                (pageLength.isEmpty() || manga.pages == null || manga.pages >= (pageLength.toIntOrNull() ?: 0)) &&
                (selectedTags.isEmpty() || selectedTags.all { it in manga.tags }) &&
                (authorQuery.isEmpty() || manga.author.contains(authorQuery, ignoreCase = true)) &&
                when (parodyState) {
                    Filter.TriState.STATE_INCLUDE -> manga.parody != null
                    Filter.TriState.STATE_EXCLUDE -> manga.parody == null
                    else -> true
                }
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

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val id = document.selectFirst("div:has(h1) + div > p:last-child")
            ?.text()?.removePrefix("#")?.toIntOrNull()
        val manga = wholesomeList.find { it.id == id } ?: throw Exception("Manga not found")
        return manga.toSManga()
    }

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
                    note = "Through Cubari extension",
                )
            },
        )
    }

    override fun getFilterList(): FilterList {
        return FilterList(
            TierFilter(),
            PageLengthFilter(),
            TagsFilter(),
            AuthorFilter(),
            ParodyFilter(),
        )
    }

    // Filters
    class SortFilter : Filter.Sort("Sort", arrayOf("Default", "Tier"))
    class TierFilter : Filter.Select<String>("Tier", arrayOf("All", "S", "S-", "A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D", "D-"))
    class PageLengthFilter : Filter.Text("Minimum Page Length")

    // TODO min o max
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

    // todo AND or OR for tags
    class AuthorFilter : Filter.Text("Author")
    class ParodyFilter : Filter.TriState("Parody")
    // todo isParody T/F and parody of ... contains

    // TODO settings with links to install extensions required

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
