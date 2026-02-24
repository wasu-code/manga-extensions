package eu.kanade.tachiyomi.extension.en.wholesomelist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FeaturedResponse(
    @SerialName("table")
    val entries: List<FeaturedEntry>,
)

@Serializable
data class FeaturedEntry(
    @SerialName("link")
    val url: String,
    val title: String,
    val author: String = "",
    val tier: String = "",
    @SerialName("img")
    val thumbnail_url: String = "",
)

@Serializable
data class UpdatesResponse(
    @SerialName("table")
    val entries: List<LatestEntry>,
)

@Serializable
data class LatestEntry(
    @SerialName("link")
    val url: String,
    val title: String,
    val author: String = "",
    val tier: String = "",
    val time: Long = 0,
)

@Serializable
data class ListResponse(
    @SerialName("table")
    val entries: List<ListEntry>,
)

@Serializable
data class ListEntry(
    val uuid: String,
    val id: Int,
    val title: String,
    val author: String = "",
    val hm: String? = null,
    val nh: String? = null,
    val eh: String? = null,
    val im: String? = null,
    val tier: String = "",
    val tags: List<String> = listOf(),
    val parody: String? = null,
    val note: String? = null,
    val pages: Int? = null,
    @SerialName("image")
    val thumbnail_url: String = "",
) {
    val url = "https://wholesomelist.com/list/$uuid"
    val description = listOfNotNull(
        note,
        pages?.let { "$pages pages" },
        parody?.let { "Parody of \"$parody\"" },
        tier.let { "$tier tier" },
    ).joinToString("\n")
}
