package kuchihige.source

import android.app.Application
import android.content.Intent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class AggregatorSource : HttpSource() {
    private val context by lazy { Injekt.get<Application>()}

    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return getSourceList(manga).map {
            SChapter.create().apply {
                name = "🧩 ${it.name}"
                url = it.packageName + "/" + it.query
                scanlator = it.note
            }
        } + listOf(
            SChapter.create().apply {
                name = "🔎 Global search"
                url = "/" + manga.title
            }
        )
    }
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not Used")

    open fun getSourceList(manga: SManga) : List<TargetSource> {
        return emptyList()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not Used")
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val (packageName, query) = chapter.url.split("/", limit = 2).let { it[0] to it[1] }
        val searchIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.SEARCH"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("query", query)
            putExtra("filter", packageName)
        }
        context.startActivity(searchIntent)
        throw Exception("Opening manga...")
    }

    override fun pageListParse(response: Response): List<Page> {
        throw UnsupportedOperationException("Not Used")
    }

}

data class TargetSource(
    val name: String,
    val query: String,
    val packageName: String?,
    val note: String? = null
)
