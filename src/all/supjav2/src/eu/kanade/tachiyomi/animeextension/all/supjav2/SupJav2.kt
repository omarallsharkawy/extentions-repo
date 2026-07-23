package eu.kanade.tachiyomi.animeextension.all.supjav2

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder

class SupJav2(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SupJav 2"

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
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl$langPath/popular${if (page > 1) "/page/$page" else ""}", headers)

    override fun popularAnimeSelector() = "div.posts > div.post > a, div.posts > div.post, div.post > a, div.post"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = if (element.tagName() == "a") element else (element.selectFirst("a") ?: element)
        setUrlWithoutDomain(link.attr("href"))

        val img = element.selectFirst("img") ?: element.parent()?.selectFirst("img")
        if (img != null) {
            title = img.attr("alt").ifBlank { img.attr("title") }
            val rawThumb = img.attr("data-original")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
                .ifBlank { img.absUrl("data-original") }
                .ifBlank { img.absUrl("data-src") }
                .ifBlank { img.absUrl("src") }

            thumbnail_url = when {
                rawThumb.startsWith("//") -> "https:$rawThumb"
                rawThumb.startsWith("/") -> "$baseUrl$rawThumb"
                else -> rawThumb
            }
        }
        if (title.isBlank()) {
            title = link.attr("title").ifBlank {
                element.selectFirst("h3, h2")?.text()
                    ?: element.parent()?.selectFirst("h3, h2")?.text()
                    ?: ""
            }
        }

        val duration = (
            element.selectFirst("span.duration, span.time, div.duration, time")
                ?: element.parent()?.selectFirst("span.duration, span.time, div.duration, time")
            )?.text()?.trim()

        if (!duration.isNullOrBlank()) {
            title = "$title [$duration]"
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination li.active:not(:nth-last-child(2)), div.pagination a.next, div.pagination a[rel=next], div.pagination .next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl$langPath${if (page > 1) "/page/$page" else ""}", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val url = runCatching { query.toHttpUrl() }.getOrNull()
                ?: return AnimesPage(emptyList(), false)
            if (!url.host.equals(baseUrl.toHttpUrl().host, ignoreCase = true)) {
                throw Exception("Unsupported url host")
            }
            val path = url.encodedPath.removePrefix("/")
            if (path.isBlank()) {
                return AnimesPage(emptyList(), false)
            }
            return getSearchAnime(page, "$PREFIX_SEARCH$path", filters)
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH).removePrefix("/").trim()
            if (id.isBlank()) {
                return AnimesPage(emptyList(), false)
            }
            val url = when {
                id.startsWith("http://") || id.startsWith("https://") -> id
                id.startsWith("/") -> "$baseUrl$id"
                else -> "$baseUrl/$id"
            }
            return runCatching {
                client.newCall(GET(url, headers))
                    .awaitSuccess()
                    .use(::searchAnimeByIdParse)
            }.getOrElse { AnimesPage(emptyList(), false) }
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
        val pagePath = if (page > 1) "/page/$page" else ""
        return when {
            query.startsWith(PREFIX_SEARCH) -> {
                val id = query.removePrefix(PREFIX_SEARCH).removePrefix("/").trim()
                GET("$baseUrl/$id", headers)
            }

            query.isNotBlank() -> {
                val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
                GET("$baseUrl$langPath$pagePath/?s=$encodedQuery", headers)
            }

            else -> {
                val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.toUriPart()
                val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.toUriPart()
                val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart()

                val url = when {
                    !category.isNullOrBlank() -> "$baseUrl$langPath/category/$category$pagePath"
                    !tag.isNullOrBlank() -> "$baseUrl$langPath/tag/$tag$pagePath"
                    sort == "popular" -> "$baseUrl$langPath/popular$pagePath"
                    else -> "$baseUrl$langPath$pagePath"
                }
                GET(url, headers)
            }
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
        TagFilter(),
        SortFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        fun toUriPart() = vals[state].second
    }

    class CategoryFilter : UriPartFilter("Category", CATEGORIES)
    class TagFilter : UriPartFilter("Tag", TAGS)
    class SortFilter : UriPartFilter("Sort", SORTS)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.content > div.post-meta")
            ?: document.selectFirst("div.post-meta")
        if (content != null) {
            title = content.selectFirst("h2")?.text() ?: ""
            thumbnail_url = content.selectFirst("img")?.let { img ->
                val rawThumb = img.attr("data-original")
                    .ifBlank { img.attr("data-src") }
                    .ifBlank { img.attr("src") }
                    .ifBlank { img.absUrl("src") }
                when {
                    rawThumb.startsWith("//") -> "https:$rawThumb"
                    rawThumb.startsWith("/") -> "$baseUrl$rawThumb"
                    else -> rawThumb
                }
            }

            content.selectFirst("div.cats")?.run {
                author = select("p:contains(Maker :) > a").textsOrNull()
                artist = select("p:contains(Cast :) > a").textsOrNull()
            }
            genre = content.select("div.tags > a").textsOrNull()
        }
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
            url = response.request.url.encodedPath
        }

        return listOf(episode)
    }

    override fun episodeListSelector(): String = "div.content"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()

        val players = doc.select("div.btnst a, div.btns a, a.btn-server, div.download-links a, div.downloads a, a[data-link], a[data-url], a[href*='supjav.php']")
            .distinct()
            .mapNotNull { element ->
                val label = cleanServerLabel(element.text())
                val link = element.attr("data-link")
                    .ifBlank { element.attr("data-url") }
                    .ifBlank { element.attr("href") }
                    .trim()
                if (link.isBlank() || link.startsWith("javascript:", ignoreCase = true)) {
                    return@mapNotNull null
                }
                label to link
            }

        return players.parallelCatchingFlatMapBlocking(::videosFromPlayer)
    }

    private fun cleanServerLabel(text: String): String {
        val clean = text.trim().uppercase()
        return when {
            clean.contains("TV") -> "TV"
            clean.contains("VOE") -> "VOE"
            clean.contains("FST") || clean.contains("WISH") || clean.contains("STREAMWISH") || clean.contains("FASTSTREAM") || clean.contains("FASTSHOW") -> "FST"
            clean.contains("ST") || clean.contains("STREAMTAPE") -> "ST"
            clean.contains("DOOD") -> "DOOD"
            clean.contains("MIXDROP") -> "MIXDROP"
            clean.contains("UQLOAD") -> "UQLOAD"
            clean.contains("VIDHIDE") -> "VIDHIDE"
            else -> clean
        }
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidhideExtractor by lazy { VidHideExtractor(client, headers) }

    private val protectorHeaders by lazy {
        super.headersBuilder().set("Referer", "$PROTECTOR_URL/").build()
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private suspend fun resolveProtectorRedirect(id: String): String? {
        if (id.isBlank()) return null
        if (id.startsWith("http://") || id.startsWith("https://")) {
            if (!id.contains("supjav", ignoreCase = true)) {
                return id
            }
            return fetchRedirect(id)
        }
        if (id.startsWith("//")) {
            return "https:$id"
        }

        return fetchRedirect(id.reversed()) ?: fetchRedirect(id)
    }

    private suspend fun fetchRedirect(code: String): String? = runCatching {
        val targetUrl = if (code.startsWith("http://") || code.startsWith("https://") || code.contains("supjav.php")) code else "$PROTECTOR_URL/supjav.php?c=$code"
        noRedirectClient.newCall(GET(targetUrl, protectorHeaders))
            .await()
            .use { it.headers["location"] }
    }.getOrNull()

    private suspend fun videosFromPlayer(player: Pair<String, String>): List<Video> {
        val (hoster, id) = player
        val url = resolveProtectorRedirect(id) ?: return emptyList()

        val normalizedHoster = when {
            hoster in SUPPORTED_SERVERS -> hoster
            url.contains("streamtape", ignoreCase = true) -> "ST"
            url.contains("voe", ignoreCase = true) -> "VOE"
            url.contains("streamwish", ignoreCase = true) || url.contains("wish", ignoreCase = true) || url.contains("fastshow", ignoreCase = true) -> "FST"
            url.contains("dood", ignoreCase = true) || url.contains("ds2play", ignoreCase = true) -> "DOOD"
            url.contains("mixdrop", ignoreCase = true) -> "MIXDROP"
            url.contains("uqload", ignoreCase = true) -> "UQLOAD"
            url.contains("vidhide", ignoreCase = true) -> "VIDHIDE"
            else -> hoster
        }

        return runCatching {
            when (normalizedHoster) {
                "ST" -> streamtapeExtractor.videosFromUrl(url)

                "VOE" -> voeExtractor.videosFromUrl(url)

                "FST" -> streamwishExtractor.videosFromUrl(url)

                "DOOD" -> doodExtractor.videosFromUrl(url)

                "MIXDROP" -> mixdropExtractor.videosFromUrl(url)

                "UQLOAD" -> uqloadExtractor.videosFromUrl(url)

                "VIDHIDE" -> vidhideExtractor.videosFromUrl(url)

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
        }.getOrElse { emptyList() }
    }

    override fun videoListSelector(): String = "div.content"

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

        private val SUPPORTED_SERVERS = setOf("TV", "FST", "VOE", "ST", "DOOD", "MIXDROP", "UQLOAD", "VIDHIDE")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private val CATEGORIES = arrayOf(
            Pair("All", ""),
            Pair("Censored", "censored"),
            Pair("Uncensored", "uncensored"),
            Pair("Uncensored Leaked", "uncensored-leaked"),
            Pair("VR", "vr"),
            Pair("Reduced", "reduced"),
        )

        private val TAGS = arrayOf(
            Pair("All", ""),
            Pair("Amateur", "amateur"),
            Pair("Big Tits", "big-tits"),
            Pair("Censored JAV", "censored-jav"),
            Pair("Creampie", "creampie"),
            Pair("Cumshot", "cumshot"),
            Pair("Documentary", "documentary"),
            Pair("Featured", "featured"),
            Pair("HD", "hd"),
            Pair("Housewife", "housewife"),
            Pair("Married Woman", "married-woman"),
            Pair("Milf", "milf"),
            Pair("Mosaic Removed", "mosaic-removed"),
            Pair("Pantyhose", "pantyhose"),
            Pair("Slut", "slut"),
            Pair("Subtitled", "subtitled"),
            Pair("Uncensored JAV", "uncensored-jav"),
            Pair("VR", "vr"),
        )

        private val SORTS = arrayOf(
            Pair("Latest", ""),
            Pair("Popular", "popular"),
        )
    }
}
