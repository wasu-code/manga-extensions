package kuchihige.utils

import android.app.Application
import android.content.Intent
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class AggregatorSource : HttpSource() {
    private val context by lazy { Injekt.get<Application>()}

    override fun chapterListParse(response: Response): List<SChapter> {
        return sourceList().map {
            SChapter.create().apply {
                name = it.name
                url = it.packageName + "/" + it.query
            }
        }
    }

    fun sourceList() : List<TargetSource> {
        return listOf()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not Used")
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val (packageName, query) = chapter.url.split("/", limit = 2).let { it[0] to it[1] }

        val searchIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
//            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("query", query)
            putExtra("filter", packageName)
        }
        context.startActivity(searchIntent)
        return Observable.empty()
    }

    override fun pageListParse(response: Response): List<Page> {
        throw UnsupportedOperationException("Not Used")
    }

}

data class TargetSource(
    val name: String,
    val query: String,
    val packageName: String
)
