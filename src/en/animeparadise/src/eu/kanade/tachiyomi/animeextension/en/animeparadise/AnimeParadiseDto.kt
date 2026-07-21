package eu.kanade.tachiyomi.animeextension.en.animeparadise

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AnimeListResponse(
    val success: Boolean? = null,
    val data: List<AnimeObject> = emptyList(),
) {
    @Serializable
    data class AnimeObject(
        @SerialName("_id")
        val id: String,
        val title: String? = null,
        val link: String,
        val synopsys: String? = null,
        val genres: List<String>? = null,
        val status: String? = null,
        val posterImage: ImageObject? = null,
        val alternativeTitle: AlternativeTitle? = null,
    ) {
        @Serializable
        data class ImageObject(
            val original: String? = null,
            val large: String? = null,
            val medium: String? = null,
            val small: String? = null,
        )

        @Serializable
        data class AlternativeTitle(
            val english: String? = null,
            val native: String? = null,
            val romaji: String? = null,
        )

        fun resolvedTitle(): String = title
            ?: alternativeTitle?.english
            ?: alternativeTitle?.romaji
            ?: link.replace('-', ' ')

        fun toSAnime(json: Json): SAnime = SAnime.create().apply {
            title = resolvedTitle()
            thumbnail_url = posterImage?.original
                ?: posterImage?.large
                ?: posterImage?.medium
                ?: posterImage?.small
                ?: ""
            description = synopsys
            genre = genres?.joinToString(", ")
            val animeStatus = this@AnimeObject.status
            status = when (animeStatus?.lowercase()) {
                "current", "ongoing", "releasing" -> SAnime.ONGOING
                "finished", "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            url = json.encodeToString(LinkData(slug = link, id = id))
        }
    }
}

@Serializable
data class LinkData(
    val slug: String,
    val id: String,
)

@Serializable
data class RecentEpisodesResponse(
    val success: Boolean? = null,
    val data: List<RecentEpisodeObject> = emptyList(),
) {
    @Serializable
    data class RecentEpisodeObject(
        val origin: RecentOrigin? = null,
    ) {
        @Serializable
        data class RecentOrigin(
            @SerialName("_id")
            val id: String,
            val title: String? = null,
            val link: String,
            val posterImage: AnimeListResponse.AnimeObject.ImageObject? = null,
        )
    }
}

@Serializable
data class EpisodeListResponse(
    val success: Boolean? = null,
    val data: List<EpisodeObject> = emptyList(),
) {
    @Serializable
    data class EpisodeObject(
        val uid: String,
        val origin: String,
        val number: String? = null,
        val title: String? = null,
    ) {
        fun toSEpisode(): SEpisode = SEpisode.create().apply {
            episode_number = number?.toFloatOrNull() ?: 1F
            name = (number?.let { "Ep. $number" } ?: "Episode") + (title?.let { " - $it" } ?: "")
            url = "/watch/$uid?origin=$origin"
        }
    }
}

@Serializable
data class EpisodeWatchResponse(
    val success: Boolean? = null,
    val data: EpisodeWatchData? = null,
) {
    @Serializable
    data class EpisodeWatchData(
        val episode: WatchEpisode? = null,
    )

    @Serializable
    data class WatchEpisode(
        val streamLink: String? = null,
        val number: String? = null,
        val subData: List<SubDataObject>? = null,
    )

    @Serializable
    data class SubDataObject(
        val src: String? = null,
        val label: String? = null,
        val type: String? = null,
    )
}

/** Legacy Next.js payload (kept for reference; player no longer uses this). */
@Serializable
data class VideoData(
    val props: PropsObject,
) {
    @Serializable
    data class PropsObject(
        val pageProps: PagePropsObject,
    ) {
        @Serializable
        data class PagePropsObject(
            val subtitles: List<SubtitleObject>? = null,
            val animeData: AnimeDataObject,
            val episode: EpisodeObject,
        ) {
            @Serializable
            data class SubtitleObject(
                val src: String,
                val label: String,
            )

            @Serializable
            data class AnimeDataObject(
                val title: String,
            )

            @Serializable
            data class EpisodeObject(
                val number: String,
            )
        }
    }
}

@Serializable
data class VideoList(
    val directUrl: List<VideoObject>? = null,
    val message: String? = null,
) {
    @Serializable
    data class VideoObject(
        val src: String,
        val label: String,
    )
}
