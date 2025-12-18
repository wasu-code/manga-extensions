package eu.kanade.tachiyomi.extension.all.itchio

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable

//    Sort
// Popular             <no value>
// New & Popular       /new-and-popular
// Top Sellers         /top-sellers
// Top rated           /top-rated
// Most recent         /newest
//
// Price
// All     <no value>
// Free    /free
// On sale /on-sale
// Paid    /store
// $5 or less  /5-dollars-or-less
// $15 or less /15-dollars-or-less
//
// Tags (multi)
// https://itch.io/tags.json?format=browse&nsfw=true&classification=comic

val SORTING: Map<String, String> = mapOf(
    "Popular" to "",
    "New & Popular" to "/new-and-popular",
    "Top Sellers" to "/top-sellers",
    "Top rated" to "/top-rated",
    "Most recent" to "/newest",
)

val PRICE: Map<String, String> = mapOf(
    "All" to "",
    "Free" to "/free",
    "On sale" to "/on-sale",
    "Paid" to "/store",
    "5 or less" to "/5-dollars-or-less",
    "15 or less" to "/15-dollars-or-less",
)

class SortingFilter : Filter.Select<String>("Sort by", SORTING.keys.toTypedArray())

class PriceFilter : Filter.Select<String>("Price", PRICE.keys.toTypedArray())

class TagsFilter(tags: List<String>) : Filter.Group<Filter.CheckBox>(
    "Tags",
    tags.map { TagFilter(it) },
)

class TagFilter(name: String) : Filter.CheckBox(name)

class UserFilter() : Filter.Text("Username")

@Serializable
data class TagsResponse(
    val tags: List<Tag>,
)

@Serializable
data class Tag(
    val name: String,
    val url: String,
    val primary: Boolean = false,
)
