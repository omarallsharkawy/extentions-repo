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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.IOException

class SupJav2(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SupJav 2"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = true

    /*
     * Keep NetworkHelper's Android UA and cookie jar. A forced Windows UA
     * makes Cloudflare's WebView clearance cookie unusable by the app client.
     */
    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    private val langPath = when (lang) {
        "en" -> ""
        else -> "/$lang"
    }

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl$langPath/popular${if (page > 1) "/page/$page" else ""}", headers)

    override fun popularAnimeSelector() = "div.posts > div.post > a"

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

    /**
     * SupJav has used both a `<div class="pagination">` and a WordPress
     * `<nav class="navigation pagination">` over time.  Keep the selector
     * broad enough for either markup; the parser below also checks the hrefs
     * so a page-number link is only treated as a next page when it is ahead
     * of the current request.
     */
    override fun popularAnimeNextPageSelector() = "a.next[href], a[rel=next][href], .pagination a.next[href], .pagination a[rel=next][href], .pagination li.active + li a[href], .page-numbers[href]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        val document = response.asJsoup()
        document.throwIfCloudflareChallenge()
        return AnimesPage(
            document.select(popularAnimeSelector())
                .map(::popularAnimeFromElement)
                .distinctBy { it.url },
            hasNextPage(document, response.request.url),
        )
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl$langPath${if (page > 1) "/page/$page" else ""}", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        val document = response.asJsoup()
        document.throwIfCloudflareChallenge()
        val anime = document.select(latestUpdatesSelector())
            .map(::latestUpdatesFromElement)
            .distinctBy { it.url }
        // The site's home/latest route has no real pagination endpoint: its
        // `/page/N` variants repeat the same mixed home feed. Only advertise
        // a next page when the document contains a genuine forward link.
        return AnimesPage(anime, hasNextPage(document, response.request.url))
    }

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

    /**
     * Return true only when the pagination markup points past the page being
     * parsed.  The site currently emits WordPress `page-numbers`, but older
     * layouts used `li.active + li` and some pages expose only a `rel=next`
     * link.  Looking at the target page number prevents the current/previous
     * links from accidentally keeping infinite scrolling alive forever.
     */
    private fun hasNextPage(document: Document, requestUrl: okhttp3.HttpUrl): Boolean {
        val currentPage = pageNumber(requestUrl)
        val links = document.select(popularAnimeNextPageSelector())

        if (links.any { link ->
                val href = link.attr("href").trim()
                if (href.isBlank() || href == "#" || link.hasClass("disabled")) {
                    false
                } else {
                    val explicitNext = link.hasClass("next") ||
                        link.attr("rel").equals("next", ignoreCase = true)
                    if (!explicitNext) {
                        false
                    } else {
                        val target = requestUrl.resolve(href)
                        target != null && pageNumber(target) > currentPage
                    }
                }
            }
        ) {
            return true
        }

        return links.mapNotNull { link ->
            val href = link.attr("href").trim()
            if (href.isBlank() || href == "#") return@mapNotNull null
            val target = requestUrl.resolve(href) ?: return@mapNotNull null
            pageNumber(target)
        }.any { it > currentPage }
    }

    private fun pageNumber(url: okhttp3.HttpUrl): Int {
        val pathPage = Regex("/page/(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
            .find(url.encodedPath)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return pathPage
            ?: url.queryParameter("paged")?.toIntOrNull()
            ?: url.queryParameter("page")?.toIntOrNull()
            ?: 1
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val pagePath = if (page > 1) "/page/$page" else ""
        return when {
            query.startsWith(PREFIX_SEARCH) -> {
                val id = query.removePrefix(PREFIX_SEARCH).removePrefix("/").trim()
                GET("$baseUrl/$id", headers)
            }

            query.isNotBlank() -> {
                val url = "$baseUrl$langPath$pagePath/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query.trim())
                    .build()
                GET(url, headers)
            }

            else -> {
                val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.toUriPart()
                val customTag = filters.filterIsInstance<TagSlugFilter>().firstOrNull()
                    ?.state
                    ?.trim()
                    ?.lowercase()
                    ?.replace(Regex("""[^a-z0-9-]+"""), "-")
                    ?.trim('-')
                val tag = customTag?.takeIf(String::isNotBlank)
                    ?: filters.filterIsInstance<TagFilter>().firstOrNull()?.toUriPart()
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

    override fun searchAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        val document = response.asJsoup()
        document.throwIfCloudflareChallenge()
        return AnimesPage(
            document.select(searchAnimeSelector())
                .map(::searchAnimeFromElement)
                .distinctBy { it.url },
            hasNextPage(document, response.request.url),
        )
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
        TagFilter(),
        TagSlugFilter(),
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
    class TagSlugFilter : AnimeFilter.Text("Custom tag slug (optional)")
    class SortFilter : UriPartFilter("Sort", SORTS)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.throwIfCloudflareChallenge()
        val content = document.selectFirst("div.content > div.post-meta")
            ?: document.selectFirst("div.post-meta")
        if (content != null) {
            title = content.selectFirst("h2")?.text() ?: ""
            val image: Element? = content.selectFirst("img")
            if (image != null) {
                val rawThumb = image.attr("data-original")
                    .ifBlank { image.attr("data-src") }
                    .ifBlank { image.attr("src") }
                    .ifBlank { image.absUrl("src") }
                thumbnail_url = when {
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
        response.throwIfCloudflareChallenge()
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
        response.throwIfCloudflareChallenge()
        val doc = response.useAsJsoup()
        doc.throwIfCloudflareChallenge()
        val pageReferer = response.request.url.toString()

        val players = doc.select("div.btnst a[data-link], div.btns a[data-link], a.btn-server[data-link], a[href*='supjav.php']")
            .mapNotNull { element ->
                val label = cleanServerLabel(element.text())
                val link = element.attr("data-link")
                    .ifBlank { element.attr("data-url") }
                    .ifBlank { element.attr("href") }
                    .trim()
                if (link.isBlank() || link.startsWith("javascript:", ignoreCase = true)) {
                    return@mapNotNull null
                }
                PlayerRequest(label, link, pageReferer)
            }
            .distinctBy { it.id }

        if (players.isEmpty()) {
            throw IOException("SupJav 2 returned no server buttons. Refresh the video page and retry.")
        }

        val videos = players
            .flatMap { player -> videosFromPlayerBlocking(player) }
            .distinctBy { it.videoUrl }
            // TurboVid's current "HLS" playlist is made of image-wrapped
            // chunks that only its browser player can decode. Keep it as a
            // fallback, but prefer standard HLS/MP4 hosts in native players.
            .sortedBy { video -> if (video.quality.startsWith("TV", ignoreCase = true)) 1 else 0 }

        if (videos.isEmpty()) {
            throw IOException("SupJav 2 servers were found, but none returned a playable stream. Retry or open the video in WebView once.")
        }
        return videos
    }

    /**
     * This legacy API is called from host apps with different Kotlin runtime
     * versions. Keep it blocking so R8 cannot turn suspend lambdas into an
     * incompatible Function2 at runtime.
     */
    private fun videosFromPlayerBlocking(player: PlayerRequest): List<Video> {
        val url = resolveProtectorRedirectBlocking(player.id) ?: return emptyList()
        val hoster = when {
            url.contains("turbovid", true) -> "TV"

            url.contains("fc2stream", true) || url.contains("streamwish", true) ||
                url.contains("fastshow", true) -> "FST"

            url.contains("streamtape", true) -> "ST"

            url.contains("voe", true) -> "VOE"

            url.contains("dood", true) || url.contains("ds2play", true) -> "DOOD"

            url.contains("mixdrop", true) -> "MIXDROP"

            else -> player.label
        }

        return runCatching {
            when (hoster) {
                "TV" -> extractTvVideosBlocking(url, player.pageReferer)
                "ST" -> streamtapeExtractor.videosFromUrl(url)
                "DOOD" -> doodExtractor.videosFromUrl(url)
                "MIXDROP" -> mixdropExtractor.videosFromUrl(url)
                else -> extractPackedHlsBlocking(url, player.pageReferer, hoster)
            }
        }.getOrDefault(emptyList())
    }

    private fun resolveProtectorRedirectBlocking(id: String): String? {
        if (id.isBlank()) return null
        if (id.startsWith("//")) return "https:$id"
        if (id.startsWith("http://") || id.startsWith("https://")) {
            if (!id.contains("supjav", true)) return id
            return fetchRedirectBlocking(id)
        }
        return fetchRedirectBlocking(id.reversed()) ?: fetchRedirectBlocking(id)
    }

    private fun fetchRedirectBlocking(code: String): String? = runCatching {
        val targetUrl = if (
            code.startsWith("http://") ||
            code.startsWith("https://") ||
            code.contains("supjav.php")
        ) {
            code
        } else {
            "$PROTECTOR_URL/supjav.php?c=$code"
        }
        noRedirectClient.newCall(GET(targetUrl, protectorHeaders)).execute().use { response ->
            response.throwIfCloudflareChallenge()
            response.header("Location")?.trim()?.takeIf(String::isNotBlank)
                ?.let { response.request.url.resolve(it)?.toString() }
        }
    }.getOrNull()

    private fun extractTvVideosBlocking(url: String, pageReferer: String): List<Video> {
        val body = client.newCall(GET(url, embedHeaders(pageReferer))).execute().use {
            it.throwIfCloudflareChallenge()
            it.body.string()
        }.replace("\\/", "/")
        val document = Jsoup.parse(body, url)
        val rawPlaylist = TV_URL_PLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: TV_DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
            ?: TV_FILE_URL_REGEX.find(body)?.groupValues?.get(1)
            ?: FST_M3U8_REGEX.find(body)?.value
            ?: return emptyList()
        val playlist = normalizePlayerUrl(rawPlaylist, url) ?: return emptyList()
        if (isBrowserWrappedTvPlaylist(playlist, url)) return emptyList()
        return listOf(Video(playlist, "TV - HLS", playlist, headers = playbackHeaders(url)))
    }

    /**
     * TurboVid sometimes publishes an M3U8 whose media entries are PNG files.
     * Its web player unwraps them with site JavaScript, while ExoPlayer/mpv see
     * PNG tracks and fail. Suppress only that browser-only form; normal HLS is
     * still returned automatically when the host switches back to it.
     */
    private fun isBrowserWrappedTvPlaylist(
        playlist: String,
        referer: String,
        depth: Int = 0,
    ): Boolean = runCatching {
        client.newCall(GET(playlist, playbackHeaders(referer))).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val mediaUrls = response.body.string()
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toList()
            mediaUrls.any { mediaUrl ->
                mediaUrl.contains("tiktokcdn.com", ignoreCase = true) ||
                    mediaUrl.substringBefore('?').endsWith(".image", ignoreCase = true)
            } ||
                (
                    depth < 2 &&
                        mediaUrls
                            .filter { it.substringBefore('?').endsWith(".m3u8", ignoreCase = true) }
                            .any { child -> isBrowserWrappedTvPlaylist(child, playlist, depth + 1) }
                    )
        }
    }.getOrDefault(false)

    private fun extractPackedHlsBlocking(
        url: String,
        pageReferer: String,
        label: String,
    ): List<Video> {
        val response = client.newCall(GET(url, embedHeaders(pageReferer))).execute()
        val body = response.use {
            it.throwIfCloudflareChallenge()
            it.body.string()
        }
        val scripts = Jsoup.parse(body, url).select("script").joinToString("\n") { script ->
            val data = script.data()
            if ("eval(function(p,a,c" in data) {
                runCatching { JsUnpacker.unpackAndCombine(data) }.getOrNull() ?: data
            } else {
                data
            }
        }.replace("\\/", "/").replace("\\u0026", "&")
        val playlist = FST_M3U8_REGEX.find(scripts)?.value
            ?.let { normalizePlayerUrl(it, url) }
            ?: return emptyList()
        if (!isReachableHlsPlaylist(playlist, url)) return emptyList()
        return listOf(Video(playlist, "$label - HLS", playlist, headers = playbackHeaders(url)))
    }

    private fun isReachableHlsPlaylist(playlist: String, referer: String): Boolean = runCatching {
        client.newCall(GET(playlist, playbackHeaders(referer))).execute().use { response ->
            response.isSuccessful && response.body.string().startsWith("#EXTM3U")
        }
    }.getOrDefault(false)

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
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }

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
        val targetUrl = if (
            code.startsWith("http://") ||
            code.startsWith("https://") ||
            code.contains("supjav.php")
        ) {
            code
        } else {
            "$PROTECTOR_URL/supjav.php?c=$code"
        }

        noRedirectClient.newCall(GET(targetUrl, protectorHeaders))
            .await()
            .use { redirectResponse ->
                redirectResponse.throwIfCloudflareChallenge()
                val location = redirectResponse.header("Location")?.trim().orEmpty()
                if (location.isBlank()) return@use null
                redirectResponse.request.url.resolve(location)?.toString()
            }
    }.getOrNull()

    private suspend fun videosFromPlayer(player: PlayerRequest): List<Video> {
        val url = resolveProtectorRedirect(player.id) ?: return emptyList()
        val hoster = player.label
        val embedHeaders = embedHeaders(player.pageReferer)

        val normalizedHoster = when {
            hoster in SUPPORTED_SERVERS -> hoster

            url.contains("streamtape", ignoreCase = true) -> "ST"

            url.contains("voe", ignoreCase = true) -> "VOE"

            url.contains("streamwish", ignoreCase = true) ||
                url.contains("wish", ignoreCase = true) ||
                url.contains("fastshow", ignoreCase = true) ||
                url.contains("fc2stream", ignoreCase = true) -> "FST"

            url.contains("turbovid", ignoreCase = true) -> "TV"

            url.contains("dood", ignoreCase = true) || url.contains("ds2play", ignoreCase = true) -> "DOOD"

            url.contains("mixdrop", ignoreCase = true) -> "MIXDROP"

            url.contains("uqload", ignoreCase = true) -> "UQLOAD"

            url.contains("vidhide", ignoreCase = true) -> "VIDHIDE"

            else -> hoster
        }

        return runCatching {
            when (normalizedHoster) {
                "ST" -> streamtapeExtractor.videosFromUrl(url)

                "VOE" -> VoeExtractor(client, embedHeaders).videosFromUrl(url)

                "FST" -> {
                    if (url.contains("fc2stream", ignoreCase = true)) {
                        extractFstFallback(url, player.pageReferer)
                    } else {
                        StreamWishExtractor(client, embedHeaders).videosFromUrl(url)
                            .ifEmpty { extractFstFallback(url, player.pageReferer) }
                    }
                }

                "DOOD" -> doodExtractor.videosFromUrl(url)

                "MIXDROP" -> mixdropExtractor.videosFromUrl(url)

                "UQLOAD" -> uqloadExtractor.videosFromUrl(url)

                "VIDHIDE" -> VidHideExtractor(client, embedHeaders).videosFromUrl(url)

                "TV" -> extractTvVideos(url, player.pageReferer)

                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    /**
     * turbovidhls currently returns HTTP 500 while still serving a valid
     * player body. Do not use awaitSuccess here; parse the body when the
     * expected player marker is present.
     */
    private suspend fun extractTvVideos(url: String, pageReferer: String): List<Video> {
        val response = client.newCall(GET(url, embedHeaders(pageReferer))).await()
        val body = response.use {
            it.throwIfCloudflareChallenge()
            it.bodyString()
        }.replace("\\/", "/")

        val document = Jsoup.parse(body, url)
        val rawPlaylist = TV_URL_PLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: TV_DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
            ?: TV_FILE_URL_REGEX.find(body)?.groupValues?.get(1)
            ?: FST_M3U8_REGEX.find(body)?.value
            ?: return emptyList()
        val playlistUrl = normalizePlayerUrl(rawPlaylist, url) ?: return emptyList()
        val playerHeaders = playbackHeaders(url)

        return playlistUtils.extractFromHls(
            playlistUrl = playlistUrl,
            referer = url,
            masterHeaders = playerHeaders,
            videoHeaders = playerHeaders,
            videoNameGen = { "TV - $it" },
        ).distinctBy { it.videoUrl }
    }

    /**
     * Some FST clones expose a valid packed player only on the host returned
     * by the protector. StreamWishExtractor intentionally rewrites /e/{id} to
     * its canonical domains, so retain an absolute-host fallback here.
     */
    private suspend fun extractFstFallback(url: String, pageReferer: String): List<Video> {
        val playerHeaders = playbackHeaders(url)
        val response = client.newCall(GET(url, embedHeaders(pageReferer))).await()
        val body = response.use {
            it.throwIfCloudflareChallenge()
            it.bodyString()
        }
        val document = Jsoup.parse(body, url)
        val scripts = document.select("script").map { it.data() }
        val expandedScripts = scripts.map { script ->
            if ("eval(function(p,a,c" in script) {
                runCatching { JsUnpacker.unpackAndCombine(script) }.getOrDefault(script)
            } else {
                script
            }
        }
        val normalizedScript = expandedScripts.joinToString("\n")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
        val rawPlaylist = FST_M3U8_REGEX.find(normalizedScript)?.value
            ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
            ?: return emptyList()
        val playlistUrl = normalizePlayerUrl(rawPlaylist, url) ?: return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl = playlistUrl,
            referer = url,
            masterHeaders = playerHeaders,
            videoHeaders = playerHeaders,
            videoNameGen = { "FST - $it" },
        ).distinctBy { it.videoUrl }
    }

    private fun playbackHeaders(referer: String): okhttp3.Headers = headers.newBuilder()
        .set("Referer", referer)
        .removeAll("Origin")
        .build()

    private fun embedHeaders(pageReferer: String): okhttp3.Headers = headers.newBuilder()
        .set("Referer", pageReferer)
        .removeAll("Origin")
        .build()

    private fun normalizePlayerUrl(rawUrl: String, pageUrl: String): String? {
        val clean = rawUrl.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> pageUrl.toHttpUrlOrNull()?.resolve(clean)?.toString()
        }
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

    private fun Response.throwIfCloudflareChallenge() {
        val preview = runCatching { peekBody(CHALLENGE_PEEK_BYTES).string() }.getOrDefault("")
        val cloudflareError = code in CLOUDFLARE_ERROR_CODES &&
            (
                header("Server")?.contains("cloudflare", ignoreCase = true) == true ||
                    header("cf-ray") != null ||
                    header("cf-mitigated").equals("challenge", ignoreCase = true)
                )
        if (cloudflareError || preview.isCloudflareChallengeHtml()) {
            throw CloudflareBlockedException()
        }
    }

    private fun Document.throwIfCloudflareChallenge() {
        if (html().isCloudflareChallengeHtml()) throw CloudflareBlockedException()
    }

    private fun String.isCloudflareChallengeHtml(): Boolean {
        val lower = lowercase()
        return "_cf_chl_opt" in lower ||
            "id=\"challenge-form\"" in lower ||
            "id='challenge-form'" in lower ||
            TITLE_CHALLENGE_REGEX.containsMatchIn(this)
    }

    private class CloudflareBlockedException : IOException("Cloudflare blocked SupJav 2. Open the page in WebView once, complete the challenge, then retry.")

    private data class PlayerRequest(
        val label: String,
        val id: String,
        val pageReferer: String,
    )

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"
        private const val CHALLENGE_PEEK_BYTES = 512L * 1024L

        private val CLOUDFLARE_ERROR_CODES = setOf(403, 429, 503)
        private val TITLE_CHALLENGE_REGEX =
            Regex("""<title>\s*(?:just a moment|attention required)[^<]*</title>""", RegexOption.IGNORE_CASE)
        private val TV_URL_PLAY_REGEX =
            Regex("""\b(?:(?:var|let|const)\s+)?urlPlay\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TV_DATA_HASH_REGEX =
            Regex("""\bdata-hash\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val TV_FILE_URL_REGEX =
            Regex("""\bfile\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val FST_M3U8_REGEX =
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)

        private val SUPPORTED_SERVERS = setOf("TV", "FST", "VOE", "ST", "DOOD", "MIXDROP", "UQLOAD", "VIDHIDE")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private val CATEGORIES = arrayOf(
            Pair("All", ""),
            Pair("Censored JAV", "censored-jav"),
            Pair("Uncensored JAV", "uncensored-jav"),
            Pair("Amateur", "amateur"),
            Pair("Reducing Mosaic", "reducing-mosaic"),
            Pair("English Subtitles", "english-subtitles"),
            Pair("Chinese Subtitles", "chinese-subtitles"),
        )

        private val TAGS = arrayOf(
            Pair("All", ""),
            Pair("4K", "4k"),
            Pair("Amateur", "amateur"),
            Pair("Anal", "anal"),
            Pair("Big Tits", "big-tits"),
            Pair("Blowjob", "blowjob"),
            Pair("Bondage", "bondage"),
            Pair("Bukkake", "bukkake"),
            Pair("Cosplay", "cosplay"),
            Pair("Creampie", "creampie"),
            Pair("Cumshot", "cumshot"),
            Pair("Documentary", "documentary"),
            Pair("Facials", "facials"),
            Pair("FC2PPV", "fc2ppv"),
            Pair("Handjob", "handjob"),
            Pair("Lesbian", "lesbian"),
            Pair("Married Woman", "married-woman"),
            Pair("Massage", "massage"),
            Pair("Masturbation", "masturbation"),
            Pair("Mature Woman", "mature-woman"),
            Pair("Nurse", "nurse"),
            Pair("Pantyhose", "pantyhose"),
            Pair("POV", "pov"),
            Pair("School Girls", "school-girls"),
            Pair("Slender", "slender"),
            Pair("Slut", "slut"),
        )

        private val SORTS = arrayOf(
            Pair("Latest", ""),
            Pair("Popular", "popular"),
        )
    }
}
