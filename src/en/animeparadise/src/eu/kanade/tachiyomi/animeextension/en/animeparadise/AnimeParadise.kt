package eu.kanade.tachiyomi.animeextension.en.animeparadise

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AnimeParadise :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeParadise"

    override val baseUrl = "https://www.animeparadise.moe"

    private val apiUrl = "https://api.animeparadise.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/plain, */*")
        add("Host", apiUrl.toHttpUrl().host)
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    // ============================== Popular ===============================

    // Site API moved from /?sort=… to dedicated routes (see site JS: /anime/highrate, /search).
    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/anime/highrate", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.parseAs<AnimeListResponse>().data.map { it.toSAnime(json) }
        return AnimesPage(animeList, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/ep/recently-added", apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val recent = response.parseAs<RecentEpisodesResponse>().data
        val animeList = recent.mapNotNull { ep ->
            val origin = ep.origin ?: return@mapNotNull null
            SAnime.create().apply {
                title = origin.title ?: origin.link.replace('-', ' ')
                thumbnail_url = origin.posterImage?.original
                    ?: origin.posterImage?.large
                    ?: origin.posterImage?.medium
                    ?: origin.posterImage?.small
                    ?: ""
                url = json.encodeToString(LinkData(slug = origin.link, id = origin.id))
            }
        }.distinctBy { it.url }
        return AnimesPage(animeList, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as? GenreFilter

        val url = when {
            query.isNotBlank() -> {
                "$apiUrl/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .build()
                    .toString()
            }
            genreFilter != null && genreFilter.state != 0 -> apiUrl + genreFilter.toUriPart()
            else -> "$apiUrl/anime/highrate"
        }

        return GET(url, headers = apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Filters are ignored if using search text"),
        GenreFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("<select>", ""),
                Pair("Comedy", "/search?genre=Comedy"),
                Pair("Drama", "/search?genre=Drama"),
                Pair("Action", "/search?genre=Action"),
                Pair("Fantasy", "/search?genre=Fantasy"),
                Pair("Supernatural", "/search?genre=Supernatural"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val data = json.decodeFromString<LinkData>(anime.url)
        // Prefer API search (SSR page no longer ships __NEXT_DATA__).
        return GET(
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", data.slug.replace('-', ' '))
                .build(),
            apiHeaders,
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val list = response.parseAs<AnimeListResponse>().data
        val query = response.request.url.queryParameter("q")?.lowercase().orEmpty()
        val match = list.firstOrNull { it.link.replace('-', ' ').equals(query, true) }
            ?: list.firstOrNull { it.link.contains(query.replace(' ', '-'), true) }
            ?: list.firstOrNull()
            ?: return SAnime.create()
        return match.toSAnime(json)
    }

    override fun getAnimeUrl(anime: SAnime): String = runCatching {
        val data = json.decodeFromString<LinkData>(anime.url)
        "$baseUrl/anime/${data.slug}"
    }.getOrDefault(baseUrl)

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val data = json.decodeFromString<LinkData>(anime.url)
        return GET("$apiUrl/anime/${data.id}/episode", headers = apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<EpisodeListResponse>()
        return data.data.map { it.toSEpisode() }.reversed()
    }

    // ============================ Video Links =============================

    // Watch page no longer ships __NEXT_DATA__; stream comes from /ep/{uid}?origin=…
    override fun videoListRequest(episode: SEpisode): Request {
        val httpUrl = (baseUrl + episode.url).toHttpUrl()
        val uid = httpUrl.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: throw Exception("Missing episode id")
        val origin = httpUrl.queryParameter("origin")
            ?: throw Exception("Missing origin id")
        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("ep")
            .addPathSegment(uid)
            .addQueryParameter("origin", origin)
            .build()
        return GET(url, headers = apiHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val payload = response.parseAs<EpisodeWatchResponse>()
        val episode = payload.data?.episode
            ?: throw Exception("Episode stream data not found")
        val streamLink = episode.streamLink
            ?: throw Exception("streamLink missing for episode")

        val subtitleList = episode.subData.orEmpty().mapNotNull { sub ->
            val src = sub.src ?: return@mapNotNull null
            val resolved = when {
                src.startsWith("http") -> src
                src.startsWith("/") -> apiUrl + src
                else -> "$apiUrl/stream/file/$src"
            }
            Track(resolved, sub.label ?: "Subtitle")
        }

        val masterUrl = STREAM_BASE.toHttpUrl().newBuilder()
            .addPathSegment("m3u8")
            .addQueryParameter("url", streamLink)
            .build()
            .toString()

        val videoHeaders = headers.newBuilder().apply {
            set("Accept", "*/*")
            set("Origin", baseUrl)
            set("Referer", "$baseUrl/")
        }.build()

        // Master playlist returns relative /m3u8?url=… quality variants.
        val masterBody = client.newCall(GET(masterUrl, headers = videoHeaders)).execute()
            .use { it.body.string() }

        val videos = parseMasterPlaylist(masterBody, masterUrl, videoHeaders, subtitleList)
        return videos.ifEmpty {
            listOf(
                Video(masterUrl, "Default", masterUrl, headers = videoHeaders, subtitleTracks = subtitleList),
            )
        }
    }

    private fun parseMasterPlaylist(
        body: String,
        masterUrl: String,
        videoHeaders: Headers,
        subtitleList: List<Track>,
    ): List<Video> {
        val masterHttp = masterUrl.toHttpUrl()
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val out = mutableListOf<Video>()
        var pendingLabel: String? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingLabel = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1)
                    ?: Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.let { "${it}p" }
                    ?: "HLS"
                continue
            }
            if (line.startsWith("#")) continue
            val label = pendingLabel ?: "HLS"
            pendingLabel = null
            val streamUrl = when {
                line.startsWith("http") -> line
                line.startsWith("/") -> "${masterHttp.scheme}://${masterHttp.host}$line"
                else -> masterUrl.substringBeforeLast("/") + "/" + line
            }
            out += Video(streamUrl, label, streamUrl, headers = videoHeaders, subtitleTracks = subtitleList)
        }
        return out
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val STREAM_BASE = "https://stream.animeparadise.moe"
    }
    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
