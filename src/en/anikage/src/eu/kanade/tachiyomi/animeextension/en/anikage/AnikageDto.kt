package eu.kanade.tachiyomi.animeextension.en.anikage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NextAiringEpisode(
    val episode: Int? = null,
    val airingAt: Long? = null,
    val timeUntilAiring: Long? = null,
)

@Serializable
data class CoverImage(
    val medium: String? = null,
    val large: String? = null,
    val extraLarge: String? = null,
)

@Serializable
data class Title(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null,
)

@Serializable
data class Result(
    val slug: String,
    @SerialName("anilistId") val aniListId: Int? = null,
    val title: Title = Title(),
    val coverImage: CoverImage = CoverImage(),
    val type: String? = null,
    val format: String? = null,
    val status: String? = null,
    val totalEpisodes: Int? = null,
    val currentEpisode: Int? = null,
    val averageScore: Int? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val nextAiringEpisode: NextAiringEpisode? = null,
    val isAdult: Boolean? = null,
)

/** New browse API shape: `{ count, data: [...] }` (replaces advanced-search). */
@Serializable
data class AnikageResponse(
    val count: Int? = null,
    val data: List<Result> = emptyList(),
    // Legacy advanced-search fields (kept optional for resilience)
    val page: Int? = null,
    val perPage: Int? = null,
    val total: Int? = null,
    val hasNextPage: Boolean? = null,
    val results: List<Result> = emptyList(),
) {
    fun items(): List<Result> = data.ifEmpty { results }

    fun nextPage(requestedPage: Int, perPage: Int): Boolean {
        hasNextPage?.let { return it }
        val list = items()
        if (list.size >= perPage) return true
        val totalCount = count ?: total
        return totalCount != null && requestedPage * perPage < totalCount
    }
}

@Serializable
data class EpisodeListResponse(
    val anilistId: Int? = null,
    val total: Int? = null,
    val expectedTotal: Int? = null,
    val episodes: List<EpisodeResult> = emptyList(),
)

@Serializable
data class EpisodeResult(
    val id: String? = null,
    val number: Int,
    val title: String? = null,
    val description: String? = null,
    val img: String? = null,
    val image: String? = null,
    val airDate: String? = null,
    val isFiller: Boolean = false,
    val rating: Float? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class EpisodeSource(
    val sources: List<SourceData> = emptyList(),
    val subtitles: List<SubtitleData> = emptyList(),
    val embeds: List<Embed>? = null,
    val intro: TimeStamp? = null,
    val outro: TimeStamp? = null,
    val headers: JsonElement? = null,
    val cached: Boolean? = null,
    val stale: Boolean? = null,
)

@Serializable
data class SourceData(
    val url: String,
    val quality: String? = null,
    val isM3U8: Boolean? = null,
    val type: String? = null, // softsub,
    val embedUrl: String? = null,
) {
    fun episodeSourceUrl(): String = listOfNotNull(
        "https://prox.anikage.cc",
        isM3U8?.let { "m3u8" } ?: "stream",
        url,
    ).joinToString("/")
}

@Serializable
data class SubtitleData(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean? = null,
    val embedUrl: String? = null,
)

@Serializable
data class Embed(
    val url: String,
    val type: String? = null,
    val server: String? = null,
    val status: String? = null,
)

@Serializable
data class TimeStamp(
    val start: Int? = null,
    val end: Int? = null,
)
