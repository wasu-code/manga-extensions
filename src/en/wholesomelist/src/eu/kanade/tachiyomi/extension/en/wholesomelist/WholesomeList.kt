package eu.kanade.tachiyomi.extension.en.wholesomelist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.utils.parseAs
import kuchihige.utils.AggregatorSource
import okhttp3.Request
import okhttp3.Response

class WholesomeList : AggregatorSource() {

    override val name = "Wholesome Hentai God List"
    override val lang = "en"
    override val supportsLatest = true
    override val baseUrl = "https://wholesomelist.com"

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/features")

    override fun popularMangaParse(response: Response): MangasPage {
        val arr = response.parseAs<FeaturedResponse>().table

        val mangas = arr.map {
            SManga.create().apply {
                title = it.title
                author = it.author
                genre = it.tier
                url = it.url
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
                url = it.url
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val req = GET("$baseUrl/api/list")
        val res = client.newCall(req).execute()

        val arr = res.parseAs<ListResponse>().table
        val mangas = arr.map {
            SManga.create().apply {
                title = it.title
                author = it.author
                genre = it.tags.joinToString()
                url = it.url
                description = it.description
                status = SManga.COMPLETED
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
                initialized = true
            }
        }

        return MangasPage(mangas, false)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        TODO("Not yet implemented")
    }

    override fun getFilterList(): FilterList {
        return FilterList()
//        tier, pages length, tags, author, parody
    }



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
