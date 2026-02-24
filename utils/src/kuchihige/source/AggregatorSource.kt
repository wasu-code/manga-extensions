package kuchihige.source

import android.app.Application
import android.content.Intent
import android.net.Uri
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

    private fun isPackageInstalled(packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }

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

        if (isPackageInstalled(packageName)) {
            // try to trigger searching in target source/extension if it is installed
            val searchIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("query", query)
                putExtra("filter", packageName)
            }

            context.startActivity(searchIntent)
            throw Exception("Opening manga...")
        } else if (query.startsWith("http://") || query.startsWith("https://")) {
            // try to open as URL if no compatible extension is installed and query is a URL
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(query)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(viewIntent)
            throw Exception("Opening link...")
        }

        throw Exception("No compatible extension found")
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
