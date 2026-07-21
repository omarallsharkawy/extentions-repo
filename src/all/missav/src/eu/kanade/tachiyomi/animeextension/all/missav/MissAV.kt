package eu.kanade.tachiyomi.animeextension.all.missav

import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.javcoverfetcher.JavCoverFetcher
import aniyomi.lib.javcoverfetcher.JavCoverFetcher.fetchHDCovers
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class MissAV :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MissAV"

    override val lang = "all"

    private val preferences by getPreferencesLazy()

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    override val supportsLatest = true

    private var docHeaders by LazyMutable {
        newHeaders()
    }

    private fun newHeaders(): Headers = headers.newBuilder().apply {
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        set("Accept-Language", "en-US,en;q=0.9")
        set("Origin", baseUrl)
        set("Referer", "$baseUrl/")
    }.build()

    private fun videoHeaders(cookie: String? = null): Headers = headers.newBuilder().apply {
        set("Accept", "*/*")
        set("Accept-Language", "en-US,en;q=0.9")
        set("Origin", baseUrl)
        set("Referer", "$baseUrl/")
        if (!cookie.isNullOrBlank()) {
            set("Cookie", cookie)
        }
    }.build()

    private var playlistExtractor by LazyMutable {
        PlaylistUtils(client, docHeaders)
    }

    private fun popularAnimeNextPageSelector() = "a[rel=next], a[aria-label*=Next], a.next, div.pagination a.next, div.pagination span.current + a, button[aria-label*=Next], a:contains(Next)"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/en/today-hot?page=$page", docHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        return parseThumbnailPage(document)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/en/new?page=$page", docHeaders)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            val genre = filters.firstInstanceOrNull<GenreList>()?.selected
            if (query.isNotEmpty()) {
                addEncodedPathSegments("en/search")
                addPathSegment(query.trim())
            } else if (genre != null) {
                addEncodedPathSegments(genre)
            } else {
                addEncodedPathSegments("en/new")
            }
            filters.firstInstanceOrNull<SortFilter>()?.selected?.let {
                addQueryParameter("sort", it)
            }
            addQueryParameter("page", page.toString())
        }.build().toString()

        return GET(url, docHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        if (document.selectFirst("div[x-data*=handleRecommendResponse]") != null) {
            val url = response.request.url
            val pathSegments = url.pathSegments
            val queryStr = pathSegments.getOrNull(pathSegments.indexOf("search") + 1)
                ?: throw Exception("Failed to parse search query from URL: $url")
            val query = URLDecoder.decode(queryStr, StandardCharsets.UTF_8.name())
            val page = url.queryParameter("page")?.toIntOrNull() ?: 1
            client.newCall(fallbackApiSearch(query, page))
                .execute().use {
                    if (!it.isSuccessful) {
                        Log.e("MissAv", "Failed to fetch search results: ${it.code}")
                        throw Exception("No more results found")
                    }

                    val data = it.body.string().parseAs<RecommendationsResponse>()
                    recommMap[query] = data.recommId
                    return data.toAnimePage()
                }
        }

        return parseThumbnailPage(document)
    }

    private fun parseThumbnailPage(document: Document): AnimesPage {
        // Site prefixes utility classes with the active host (e.g. missav_fans-thumbnail).
        val entries = document.select("div[class*=thumbnail]").mapNotNull { element ->
            val link = element.selectFirst("a[class*=text-secondary][href]")
                ?: element.selectFirst("a[href][alt]")
                ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank() || href.endsWith("#") || href.contains("javascript:", ignoreCase = true)) {
                return@mapNotNull null
            }
            val titleText = link.text().ifBlank { link.attr("alt") }.trim()
            if (titleText.isBlank()) return@mapNotNull null
            val duration = element.selectFirst("span[class*=duration], span[class*=time], div[class*=duration], time, span.font-mono")
                ?.text()?.trim()
                ?.takeIf { it.contains(":") }
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = if (!duration.isNullOrBlank()) "[$duration] $titleText" else titleText
                thumbnail_url = element.selectFirst("img[data-src]")?.attr("abs:data-src")
                    ?: element.selectFirst("img[data-original]")?.attr("abs:data-original")
                    ?: element.selectFirst("img[data-lazy-src]")?.attr("abs:data-lazy-src")
                    ?: element.selectFirst("img[poster]")?.attr("abs:poster")
                    ?: element.selectFirst("img")?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(entries, hasNextPage)
    }

    private val recommMap: MutableMap<String, String> = ConcurrentHashMap()

    private fun fallbackApiSearch(query: String, page: Int): Request {
        val recommId = recommMap[query]
        return if (page == 1 || recommId == null) {
            val body = MissAvApi.searchData(query)
                .toJsonRequestBody()
            POST(MissAvApi.searchURL(getUuid()), docHeaders, body)
        } else {
            val body = MissAvApi.recommData
                .toJsonRequestBody()
            POST(MissAvApi.recommURL(recommId), docHeaders, body)
        }
    }

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val body = MissAvApi.relatedData(getUuid(), anime.url.substringAfterLast("/"))
            .toJsonRequestBody()

        return POST(MissAvApi.relatedURL(), docHeaders, body)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val data = response.body.string().parseAs<List<RelatedResponse>>()
        return data.flatMap { it.toAnimeList() }
    }

    override fun String.stripKeywordForRelatedAnimes(): List<String> = replace(regexSpecialCharacters, " ")
        .split(regexWhitespace)
        .map {
            // remove number only
            it.replace(regexNumberOnly, "")
                .lowercase()
        }
        // exclude single character
        .filter { it.length > 1 }

    override fun getFilterList() = getFilters()

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val jpTitle = document.selectFirst("span:containsOwn(Title:) + span")?.text()
            ?: document.select("div[class*=text-secondary] span:contains(title) + span").text()
        val siteCover = document.selectFirst("video[class*=player]")?.attr("abs:data-poster")
            ?: document.selectFirst("video[data-poster]")?.attr("abs:data-poster")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[data-src]")?.attr("abs:data-src")
            ?: document.selectFirst("img")?.attr("abs:src")

        return SAnime.create().apply {
            title = document.selectFirst("h1[class*=text-base]")?.text()
                ?: document.selectFirst("h1")?.text().orEmpty()
            genre = document.getInfo("/genres/")
            author = listOfNotNull(
                document.getInfo("/directors/"),
                document.getInfo("/makers/"),
            ).joinToString()
            artist = document.getInfo("/actresses/")
            status = SAnime.COMPLETED
            description = buildString {
                document.selectFirst("div[class*=mb-1]")?.text()?.also { append("$it\n") }

                document.getInfo("/labels/")?.also { append("\nLabel: $it") }
                document.getInfo("/series/")?.also { append("\nSeries: $it") }

                document.select("div[class*=text-secondary]:not(:has(a)):has(span)")
                    .eachText()
                    .forEach { append("\n$it") }
            }
            thumbnail_url = if (preferences.fetchHDCovers) {
                JavCoverFetcher.getCoverByTitle(jpTitle) ?: siteCover
            } else {
                siteCover
            }
        }
    }

    private fun Element.getInfo(urlPart: String) = select("div[class*=text-secondary] a[href*=$urlPart], a[href*=$urlPart]")
        .eachText()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.contains("ranking", ignoreCase = true) }
        .distinct()
        .joinToString()
        .takeIf(String::isNotBlank)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "Episode"
        },
    )

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val cookie = response.headers("Set-Cookie")
            .mapNotNull { it.substringBefore(';').takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("; ")
            .ifBlank { null }

        val packedScript = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.takeIf { it.contains("function(p,a,c,k,e,d)") }
            ?: document.select("script").asSequence()
                .map { it.data() }
                .firstOrNull { it.contains("function(p,a,c,k,e,d)") && (it.contains("m3u8") || it.contains("source") || it.contains("surrogate") || it.contains("hls")) }
            ?: document.select("script").asSequence()
                .map { it.data() }
                .firstOrNull { it.contains("m3u8") || it.contains("source") || it.contains("surrogate") || it.contains("hls") }
            ?: return emptyList()

        val unpacked = Unpacker.unpack(packedScript).ifEmpty {
            // Some mirrors wrap packer slightly differently; fall back to raw script body.
            packedScript
        }

        val masterPlaylist = extractMasterPlaylist(unpacked)
            ?: extractMasterPlaylist(packedScript)
            ?: return emptyList()

        val masterHost = masterPlaylist.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: "$baseUrl/"
        val hlsHeaders = headers.newBuilder().apply {
            set("Accept", "*/*")
            set("Accept-Language", "en-US,en;q=0.9")
            set("Origin", masterHost.removeSuffix("/"))
            set("Referer", masterHost)
            if (!cookie.isNullOrBlank()) {
                set("Cookie", cookie)
            }
        }.build()

        return playlistExtractor.extractFromHls(
            masterPlaylist,
            referer = masterHost,
            masterHeaders = hlsHeaders,
            videoHeaders = hlsHeaders,
        )
    }

    /**
     * Packed player bootstrap unpacks to:
     * `source="https://.../playlist.m3u8";source842=".../720p/video.m3u8";source1280="..."`
     * or `surrogate="https://..."` / `m3u8="..."`
     */
    private fun extractMasterPlaylist(script: String): String? {
        val cleanScript = script.replace("\\/", "/")
        val fromSource = M3U8_SOURCE_REGEX.find(cleanScript)?.groupValues?.get(1)
        if (!fromSource.isNullOrBlank()) return fromSource

        // Prefer master playlist.m3u8, then any quality video.m3u8.
        val all = M3U8_URL_REGEX.findAll(cleanScript).map { it.value }.distinct().toList()
        return all.firstOrNull { it.contains("playlist.m3u8") }
            ?: all.firstOrNull()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = PREF_DOMAIN_TITLE,
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_ENTRIES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            docHeaders = newHeaders()
            playlistExtractor = PlaylistUtils(client, docHeaders)
        }

        screen.addListPreference(
            key = PREF_QUALITY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        JavCoverFetcher.addPreferenceToScreen(screen)
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, docHeaders)

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, docHeaders)

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Episode"
            setUrlWithoutDomain(response.request.url.toString())
        },
    )

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, docHeaders)

    private inline fun <reified T> List<*>.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

    private fun getUuid(): String = preferences.getString(PREF_UUID_KEY, null) ?: synchronized(this) {
        // Double-check pattern to avoid generating UUID if another thread already did
        preferences.getString(PREF_UUID_KEY, null) ?: run {
            val uuid = MissAvApi.generateUUID()
            preferences.edit().putString(PREF_UUID_KEY, uuid).apply()
            uuid
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"

        // missav.fans is currently reachable without a CF interstitial; keep legacy hosts as prefs
        // for installs that can clear CF in-app.
        private val PREF_DOMAIN_ENTRIES = listOf(
            "https://missav.fans",
            "https://missav.live",
            "https://missav.ai",
            "https://missav.ws",
        )
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_ENTRIES.first()

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_VALUES[1]

        private const val PREF_UUID_KEY = "missav_uuid"

        private val regexWhitespace = Regex("\\s+")
        private val regexSpecialCharacters =
            Regex("([-.!~#$%^&*+_|/\\\\,?:;'“”‘’\"<>(){}\\[\\]。・～：—！？、―«»《》〘〙【】「」｜]|\\s-|-\\s|\\s\\.|\\.\\s)")
        private val regexNumberOnly = Regex("^\\d+$")

        private val M3U8_SOURCE_REGEX by lazy {
            Regex("""(?:source|surrogate|m3u8|hls)\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
        }
        private val M3U8_URL_REGEX by lazy {
            Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
        }
    }
}
