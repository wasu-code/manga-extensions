package eu.kanade.tachiyomi.extension.en.wholesomelist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FeaturedResponse(
    val table: List<FeaturedEntry>,
)

@Serializable
data class FeaturedEntry(
    private val link: String,
    val title: String,
    val author: String = "",
    val tier: String = "",
    @SerialName("img")
    val thumbnail_url: String = "",
) {
    val url = link
}

@Serializable
data class UpdatesResponse(
    val table: List<LatestEntry>,
)

@Serializable
data class LatestEntry(
    private val link: String,
    val title: String,
    val author: String = "",
    val tier: String = "",
    val time: Long = 0,
) {
    val url = link
}

/*
{
			"siteTags": {
                "tags": [
                    "sole female",
                    "sole male",
					"schoolgirl uniform",
                    "mosaic censorship",
                    "defloration",
                    "schoolboy uniform",
                    "condom",
                    "virginity"
                ],
                "characters": []
            },
		},
 */

@Serializable
data class ListResponse(
    val table: List<ListEntry>,
)

@Serializable
data class ListEntry(
    private val uuid: String,
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
    val description = listOfNotNull<String>(
        note,
        pages?.let { "$pages pages" },
        parody?.let { "Parody of \"$parody\"" },
        tier.let { "$tier tier" },
    ).joinToString("\n")
}
