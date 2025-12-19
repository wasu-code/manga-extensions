package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
/**
 * Minimal compile-time stub for DownloadCache.
 */
class DownloadCache {

    fun invalidateCache() {
        throw NotImplementedError("Stub - should be provided by host app at runtime")
    }

    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
        skipCache: Boolean = false,
    ): Boolean = throw NotImplementedError("Stub - should be provided by host app at runtime")
}
