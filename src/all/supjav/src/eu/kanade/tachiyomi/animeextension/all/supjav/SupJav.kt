package eu.kanade.tachiyomi.animeextension.all.supjav

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder

class SupJav(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SupJav"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private val langPath = when (lang) {
        "en" -> ""
        else -> "/$lang"
    }

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl$langPath/popular/page/$page", headers)

    override fun popularAnimeSelector() = "div.posts div.post"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))

        val img = element.selectFirst("img")
        if (img != null) {
            title = img.attr("alt").ifBlank { img.attr("title") }
            thumbnail_url = img.absUrl("data-original").ifBlank { img.absUrl("src") }
        }
        if (title.isBlank()) {
            title = link.attr("title").ifBlank { element.selectFirst("h3, h2")?.text() ?: "" }
        }

        val duration = element.selectFirst("span.duration, span.time, div.duration, time, .duration, .time")?.text()?.trim()
        if (!duration.isNullOrBlank() && !title.contains(duration)) {
            title = "$title [$duration]"
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination li.active:not(:nth-last-child(2)), div.pagination a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl$langPath/page/$page", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val path = url.pathSegments.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$path", filters)
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH).trim()
            val url = when {
                id.startsWith("http://") || id.startsWith("https://") -> id
                id.startsWith("/") -> "$baseUrl$id"
                else -> "$baseUrl/$id"
            }
            return GET(url, headers)
        }

        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selected ?: ""
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: ""
        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selected ?: ""

        val pagePath = if (page > 1) "/page/$page" else ""

        val url = when {
            query.isNotBlank() -> {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                "$baseUrl$langPath$pagePath/?s=$encodedQuery"
            }

            sort == "popular" -> "$baseUrl$langPath/popular$pagePath"

            category.isNotBlank() -> "$baseUrl$langPath/category/$category$pagePath"

            tag.isNotBlank() -> "$baseUrl$langPath/tag/$tag$pagePath"

            else -> "$baseUrl$langPath$pagePath/?s="
        }

        return GET(url, headers)
    }

    override fun searchAnimeSelector() = "div.posts > div.post > a"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = "div.pagination li.active:not(:nth-last-child(2)), div.pagination a.next"

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(),
        SortFilter(),
        TagFilter(),
    )

    private open class UriPartFilter(name: String, private val pairs: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String
            get() = pairs[state].second
    }

    private class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("All", ""),
                Pair("Censored", "censored"),
                Pair("Uncensored", "uncensored"),
                Pair("Amateur", "amateur"),
                Pair("English Subtitle", "english-subtitle"),
                Pair("Reduced Price", "reduced-price"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "Sort Order",
            arrayOf(
                Pair("Latest", ""),
                Pair("Popular", "popular"),
            ),
        )

    private class TagFilter :
        UriPartFilter(
            "Media Type / Tag",
            arrayOf(
                Pair("All", ""),
                Pair("Subtitled", "subtitle"),
                Pair("High Definition", "hd"),
                Pair("VR", "vr"),
            ),
        )

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.content > div.post-meta")
        title = content?.selectFirst("h2")?.text()
            ?: document.selectFirst("div.content > div.post-meta h2")?.text()
            ?: document.selectFirst("h1, h2")?.text()
            ?: ""
        thumbnail_url = content?.selectFirst("img")?.let {
            it.absUrl("data-original").ifBlank { it.absUrl("src") }
        } ?: document.selectFirst("div.content img")?.let {
            it.absUrl("data-original").ifBlank { it.absUrl("src") }
        }

        val cats = content?.selectFirst("div.cats") ?: document.selectFirst("div.cats")
        cats?.run {
            author = select("p:contains(Maker :) > a").textsOrNull()
            artist = select("p:contains(Cast :) > a").textsOrNull()
        }
        genre = (content ?: document).select("div.tags > a").textsOrNull()
        status = SAnime.COMPLETED
    }

    private fun Elements.textsOrNull() = eachText().joinToString().takeUnless(String::isEmpty)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "JAV"
            episode_number = 1F
            url = anime.url
        }

        return listOf(episode)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "JAV"
            episode_number = 1F
            setUrlWithoutDomain(response.request.url.toString())
        }

        return listOf(episode)
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()

        val players = doc.select("div.btnst > a").toList()
            .filter { it.text() in SUPPORTED_PLAYERS }
            .map { it.text() to it.attr("data-link").reversed() }

        return players.parallelCatchingFlatMapBlocking(::videosFromPlayer)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private val protectorHeaders by lazy {
        super.headersBuilder().set("referer", "$PROTECTOR_URL/").build()
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private suspend fun videosFromPlayer(player: Pair<String, String>): List<Video> {
        val (hoster, id) = player
        val url = noRedirectClient.newCall(GET("$PROTECTOR_URL/supjav.php?c=$id", protectorHeaders)).await()
            .use { it.headers["location"] }
            ?: return emptyList()

        return when (hoster) {
            "ST" -> streamtapeExtractor.videosFromUrl(url)

            "VOE" -> voeExtractor.videosFromUrl(url)

            "FST" -> streamwishExtractor.videosFromUrl(url)

            "TV" -> {
                val body = client.newCall(GET(url)).awaitSuccess().bodyString()
                val playlistUrl = body.substringAfter("var urlPlay = '", "")
                    .substringBefore("';")
                    .takeUnless(String::isEmpty)
                    ?: return emptyList()

                playlistUtils.extractFromHls(playlistUrl, url, videoNameGen = { "TV - $it" })
                    .distinctBy { it.videoUrl }
            }

            else -> emptyList()
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"

        private val SUPPORTED_PLAYERS = setOf("TV", "FST", "VOE", "ST")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
