package eu.kanade.tachiyomi.animeextension.all.supjav

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
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * SupJav — https://supjav.com
 *
 * Cloudflare is handled by the **app** NetworkHelper client (default UA +
 * AndroidCookieJar WebView solver). Do **not** force a desktop Windows
 * User-Agent and do **not** stack a second [CloudflareInterceptor] — that
 * combination makes CF “Verifying you are human” hang forever in WebView.
 *
 * Video buttons use hex tokens on `data-link`. Site JS (`JumpChain`) builds:
 * `https://lk1.supremejav.com/supjav.php?l=<token>&bg=<bg>`
 * The protector page reverses the token and loads `?c=<reversed>` which 302s
 * to the real hoster (TurboVid / StreamTape / VOE / …).
 */
class SupJav(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SupJav"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = true

    // Use app client as-is (includes CF interceptor + cookie jar). No override.

    override fun headersBuilder() = super.headersBuilder().apply {
        // Keep app / NetworkHelper User-Agent (Android Chrome shape).
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "en-US,en;q=0.9")
        set("Referer", "$baseUrl/")
        set("Origin", baseUrl)
    }

    private val langPath = when (lang) {
        "en" -> ""
        else -> "/$lang"
    }

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl$langPath/popular/page/$page/"
        } else {
            "$baseUrl$langPath/popular/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector() = "div.posts > div.post > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))

        val img = element.selectFirst("img")
        val rawTitle = img?.attr("alt")?.ifBlank { img.attr("title") }
            ?.ifBlank { element.text() }
            ?.ifBlank { element.attr("title") }
            .orEmpty().trim()

        val duration = element.selectFirst("span.duration, span.runtime, span.time, span.ribbon, .duration, .runtime")?.text()?.trim()
            ?: element.parent()?.selectFirst("span.duration, span.runtime, span.time, span.ribbon, .duration, .runtime")?.text()?.trim()
                .orEmpty()

        title = if (duration.isNotBlank() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }

        img?.let {
            val raw = it.attr("data-original")
                .ifBlank { it.attr("data-src") }
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("data-cfsrc") }
                .ifBlank { it.attr("srcset").substringBefore(" ").substringBefore(",") }
                .ifBlank { it.attr("src") }

            val cleanUrl = when {
                raw.startsWith("http") -> raw

                raw.startsWith("//") -> "https:$raw"

                else -> it.absUrl("data-original")
                    .ifBlank { it.absUrl("data-src") }
                    .ifBlank { it.absUrl("src") }
                    .ifBlank { raw }
            }

            thumbnail_url = cleanUrl.takeIf { url -> url.startsWith("http") && !url.contains("data:image") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination a.next, div.pagination a.next.page-numbers, div.pagination a[rel=next], " +
        "div.pagination span.current + a, div.pagination span.page-numbers.current + a, div.pagination a.current + a, " +
        "ul.pagination li.active + li a, nav.pagination a.next, div.nav-links a.next, " +
        "a.next.page-numbers, a.next, a[rel=next], a[rel=\"next\"], " +
        ".pagination .current + a, .pagination .active + li a, " +
        "div.pagination a.nextpostslink, div.pagination a.next-page, " +
        "div.pagination a:contains(Next), div.pagination a:contains(›), div.pagination a:contains(»), div.pagination a:contains(>), " +
        "div.pagination a:contains(التالي)"

    private fun parseAnimePage(document: Document, selector: String): AnimesPage {
        val animeElements = document.select(selector)
        val animes = animeElements.mapNotNull { element ->
            runCatching { popularAnimeFromElement(element) }.getOrNull()
        }.filter { it.title.isNotBlank() && it.url.isNotBlank() }

        if (animes.isEmpty()) {
            return AnimesPage(emptyList(), false)
        }

        val hasNextPage = run {
            val nextSelector = popularAnimeNextPageSelector()
            val nextEl = document.select(nextSelector).firstOrNull { el -> el.hasAttr("href") }
            if (nextEl != null) {
                true
            } else {
                val currentEl = document.selectFirst(
                    "div.pagination .current, ul.pagination .active, .pagination span.current, " +
                        ".pagination .current, .pagination .active, [aria-current=page]",
                )
                val currentNum = currentEl?.text()?.trim()?.toIntOrNull()
                    ?: document.selectFirst("div.pagination span.current, .pagination .current")?.text()?.trim()?.toIntOrNull()

                if (currentNum != null) {
                    val pageNumRegex = Regex("""/page/(\d+)|[?&](?:page|paged)=(\d+)""")
                    document.select("div.pagination a, ul.pagination a, .pagination a, nav.pagination a, .nav-links a").any { a ->
                        val textNum = a.text().trim().toIntOrNull()
                        val href = a.attr("href")
                        val hrefNum = pageNumRegex.find(href)?.let { m ->
                            m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull()
                        }
                        val pageNum = textNum ?: hrefNum
                        pageNum != null && pageNum > currentNum
                    }
                } else {
                    document.select("div.pagination a, ul.pagination a, .pagination a, nav.pagination a, .nav-links a").any { a ->
                        val href = a.attr("href")
                        href.contains("/page/") || a.hasClass("next") || a.attr("rel") == "next"
                    }
                }
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimePage(document, popularAnimeSelector())
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl$langPath/page/$page/"
        } else {
            "$baseUrl$langPath/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimePage(document, latestUpdatesSelector())
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrlOrNull() ?: return AnimesPage(emptyList(), false)
            val baseHost = baseUrl.toHttpUrlOrNull()?.host
            if (baseHost != null && url.host != baseHost) {
                return AnimesPage(emptyList(), false)
            }
            val path = url.pathSegments.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: return AnimesPage(emptyList(), false)
            return getSearchAnime(page, "$PREFIX_SEARCH$path", filters)
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return runCatching {
                client.newCall(GET(getFullUrl(id)))
                    .awaitSuccess()
                    .use(::searchAnimeByIdParse)
            }.getOrDefault(AnimesPage(emptyList(), false))
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
        var categorySlug = ""
        var tagSlug = ""
        var starSlug = ""
        var makerSlug = ""
        var sortOption = 0

        for (filter in filters) {
            when (filter) {
                is SortFilter -> sortOption = filter.state

                is CategoryFilter -> categorySlug = CategoryList[filter.state].second

                is TagFilter -> {
                    if (filter.state > 0) {
                        tagSlug = TagList[filter.state].second
                    }
                }

                is CustomTagFilter -> {
                    if (filter.state.isNotBlank()) {
                        tagSlug = filter.state.trim().lowercase()
                    }
                }

                is StarFilter -> {
                    if (filter.state.isNotBlank()) {
                        starSlug = filter.state.trim().lowercase()
                    }
                }

                is MakerFilter -> {
                    if (filter.state.isNotBlank()) {
                        makerSlug = filter.state.trim().lowercase()
                    }
                }

                else -> {}
            }
        }

        val basePath = when {
            starSlug.isNotBlank() -> {
                if (page > 1) "$baseUrl$langPath/star/$starSlug/page/$page/" else "$baseUrl$langPath/star/$starSlug/"
            }

            makerSlug.isNotBlank() -> {
                if (page > 1) "$baseUrl$langPath/maker/$makerSlug/page/$page/" else "$baseUrl$langPath/maker/$makerSlug/"
            }

            tagSlug.isNotBlank() -> {
                if (page > 1) "$baseUrl$langPath/tag/$tagSlug/page/$page/" else "$baseUrl$langPath/tag/$tagSlug/"
            }

            categorySlug.isNotBlank() -> {
                if (page > 1) "$baseUrl$langPath/category/$categorySlug/page/$page/" else "$baseUrl$langPath/category/$categorySlug/"
            }

            else -> {
                when (sortOption) {
                    1 -> if (page > 1) "$baseUrl$langPath/popular/page/$page/" else "$baseUrl$langPath/popular/"
                    2 -> if (page > 1) "$baseUrl$langPath/popular/today/page/$page/" else "$baseUrl$langPath/popular/today/"
                    3 -> if (page > 1) "$baseUrl$langPath/popular/week/page/$page/" else "$baseUrl$langPath/popular/week/"
                    4 -> if (page > 1) "$baseUrl$langPath/popular/month/page/$page/" else "$baseUrl$langPath/popular/month/"
                    else -> if (page > 1) "$baseUrl$langPath/page/$page/" else "$baseUrl$langPath/"
                }
            }
        }

        val urlBuilder = basePath.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("s", query.trim())
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimePage(document, searchAnimeSelector())
    }

    // ============================= Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        SortFilter(),
        CategoryFilter(),
        TagFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Custom Search Slugs"),
        StarFilter(),
        MakerFilter(),
        CustomTagFilter(),
    )

    private class SortFilter :
        AnimeFilter.Select<String>(
            "Sort By",
            arrayOf("Latest", "Popular All Time", "Popular Today", "Popular Week", "Popular Month"),
        )

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Category",
            CategoryList.map { it.first }.toTypedArray(),
        )

    private class TagFilter :
        AnimeFilter.Select<String>(
            "Tag",
            TagList.map { it.first }.toTypedArray(),
        )

    private class StarFilter : AnimeFilter.Text("Actress / Star Slug (e.g. yua-mikami)")
    private class MakerFilter : AnimeFilter.Text("Maker / Studio Slug (e.g. s1-no-1-style)")
    private class CustomTagFilter : AnimeFilter.Text("Custom Tag Slug")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.content > div.post-meta, div.post-meta, div.content")
        title = content?.selectFirst("h2, h1")?.text()
            ?: document.selectFirst("h2, h1")?.text().orEmpty()
        thumbnail_url = content?.selectFirst("img")?.run {
            val raw = attr("data-original").ifBlank { attr("data-src") }
                .ifBlank { attr("src") }
            if (raw.startsWith("http")) raw else absUrl(raw).ifBlank { raw }
        } ?: document.selectFirst("div.content img, img")?.attr("src")

        content?.selectFirst("div.cats")?.run {
            author = select("p:contains(Maker :) > a").textsOrNull()
            artist = select("p:contains(Cast :) > a").textsOrNull()
        }
        genre = content?.select("div.tags > a")?.textsOrNull()
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

    override fun episodeListSelector(): String = "html"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        name = "JAV"
        episode_number = 1F
    }

    private fun getFullUrl(pathUrl: String): String = when {
        pathUrl.startsWith("http://") || pathUrl.startsWith("https://") -> pathUrl
        pathUrl.startsWith("/") -> "$baseUrl$pathUrl"
        else -> "$baseUrl/$pathUrl"
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(getFullUrl(anime.url), headers)

    override fun episodeListRequest(anime: SAnime): Request = GET(getFullUrl(anime.url), headers)

    override fun videoListRequest(episode: SEpisode): Request = GET(getFullUrl(episode.url), headers)

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        return runCatching {
            val doc = response.useAsJsoup()

            val playerElements = doc.select(
                "div.btnst a, div.btns a, a.btn-server, a[data-link], a[data-url], a[data-src], a[data-href], " +
                    "button[data-link], button[data-url], [data-link], [data-url], [data-src], [data-href], " +
                    "a[href*=/supjav.php], a[href*=/supjav], ul.nav-tabs a, div.post-content a[href*=/supjav]",
            )

            val players = playerElements.mapNotNull { el ->
                val rawName = el.ownText().ifBlank { el.text() }.trim().uppercase()
                    .replace("SERVER", "").replace(":", "").trim()
                val rawLink = el.attr("data-link")
                    .ifBlank { el.attr("data-url") }
                    .ifBlank { el.attr("data-src") }
                    .ifBlank { el.attr("data-href") }
                    .ifBlank { el.attr("href") }
                    .trim()
                if (rawLink.isEmpty() || rawLink.startsWith("javascript") || rawLink == "#") return@mapNotNull null

                val detected = detectHoster(rawName, rawLink)
                detected to rawLink
            }.distinctBy { it.second }

            val videoList = players.parallelCatchingFlatMapBlocking(::videosFromPlayer)
            if (videoList.isNotEmpty()) return@runCatching videoList

            // Fallback 1: check embedded iframes in post body or player container
            val iframes = doc.select("iframe[src], iframe[data-src], div.post-content iframe, div.player-wrap iframe, div#dz_video iframe")
                .mapNotNull { el ->
                    val src = el.attr("data-src").ifBlank { el.attr("src") }.trim()
                    if (src.isBlank() || src.startsWith("javascript") || src == "#") null else "EMBED" to src
                }
                .distinctBy { it.second }

            val iframeVideos = iframes.parallelCatchingFlatMapBlocking(::videosFromPlayer)
            if (iframeVideos.isNotEmpty()) return@runCatching iframeVideos

            // Fallback 2: check download buttons
            val downloadBtns = doc.select("div.downs a, div.downscase a, a.btn-down")
                .mapNotNull { el ->
                    val link = el.attr("data-link").ifBlank { el.attr("data-url") }.ifBlank { el.attr("href") }.trim()
                    val name = el.text().trim().uppercase().ifBlank { "DL" }
                    if (link.isBlank() || link.startsWith("javascript") || link == "#") null else name to link
                }
                .distinctBy { it.second }

            val downloadVideos = downloadBtns.parallelCatchingFlatMapBlocking(::videosFromPlayer)
            if (downloadVideos.isNotEmpty()) return@runCatching downloadVideos

            // Fallback 3: scan entire HTML body text for protector parameters, c=, l=, data-link or direct embed URLs
            val htmlBody = doc.outerHtml()
            val regexLinks = Regex("""(?:data-link|data-url|data-src|data-href|href|src)\s*=\s*["']([^"']+)["']""")
                .findAll(htmlBody)
                .map { it.groupValues[1].trim() }
                .filter { link ->
                    !link.startsWith("javascript") && link != "#" &&
                        (
                            link.contains("supjav.php") || link.contains("c=") || link.contains("l=") ||
                                link.contains("streamtape") || link.contains("voe") || link.contains("dood") ||
                                link.contains("mixdrop") || link.contains("uqload") || link.contains("vidhide") ||
                                link.contains("streamwish") || link.contains("turbovid") || link.contains("fc2stream") ||
                                link.contains("filelions")
                            )
                }
                .map { "SERVER" to it }
                .distinctBy { it.second }
                .toList()

            val regexVideos = regexLinks.parallelCatchingFlatMapBlocking(::videosFromPlayer)
            if (regexVideos.isNotEmpty()) return@runCatching regexVideos

            // Fallback 4: check packed JS in the HTML page body
            if (htmlBody.contains("eval(function(p,a,c")) {
                val unpackedScripts = PACKED_REGEX.findAll(htmlBody).mapNotNull { m ->
                    runCatching { JsUnpacker.unpackAndCombine(m.value) }.getOrNull()
                }.joinToString("\n")

                val scriptLinks = Regex("""https?://[^"'\s\\]+""")
                    .findAll(unpackedScripts)
                    .map { it.value }
                    .filter { link ->
                        link.contains(".m3u8") || link.contains("streamtape") || link.contains("voe") ||
                            link.contains("dood") || link.contains("mixdrop") || link.contains("uqload") ||
                            link.contains("vidhide") || link.contains("streamwish") || link.contains("turbovid")
                    }
                    .map { "SERVER" to it }
                    .distinctBy { it.second }
                    .toList()

                val scriptVideos = scriptLinks.parallelCatchingFlatMapBlocking(::videosFromPlayer)
                if (scriptVideos.isNotEmpty()) return@runCatching scriptVideos
            }

            emptyList()
        }.getOrDefault(emptyList())
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidhideExtractor by lazy { VidHideExtractor(client, headers) }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private fun detectHoster(name: String, link: String): String {
        val upperName = name.uppercase()
        val lowerLink = link.lowercase()
        return when {
            upperName == "ST" || upperName.contains("STREAMTAPE") || upperName.contains("TAPE") ||
                lowerLink.contains("streamtape") || lowerLink.contains("strtape") || lowerLink.contains("tapecontent") || lowerLink.contains("strcloud") -> "ST"

            upperName == "VOE" || upperName.contains("VOE") || lowerLink.contains("voe") -> "VOE"

            upperName == "DS" || upperName == "DOOD" || upperName.contains("DOOD") ||
                lowerLink.contains("dood") || lowerLink.contains("ds2play") || lowerLink.contains("doodstream") ||
                lowerLink.contains("ds2video") || lowerLink.contains("d000d") || lowerLink.contains("do0od") -> "DOOD"

            upperName == "MD" || upperName == "MIXDROP" || upperName.contains("MIXDROP") || upperName.contains("DROP") ||
                lowerLink.contains("mixdrop") || lowerLink.contains("mixdroop") -> "MIXDROP"

            upperName == "UQ" || upperName == "UQLOAD" || upperName.contains("UQLOAD") ||
                lowerLink.contains("uqload") -> "UQLOAD"

            upperName == "VH" || upperName == "VIDHIDE" || upperName.contains("VIDHIDE") || upperName.contains("HIDE") ||
                lowerLink.contains("vidhide") || lowerLink.contains("streamhide") -> "VIDHIDE"

            upperName == "FST" || upperName.contains("WISH") || upperName.contains("FC2") || upperName.contains("FST") ||
                lowerLink.contains("streamwish") || lowerLink.contains("fc2stream") || lowerLink.contains("wish") || lowerLink.contains("filelions") -> "FST"

            upperName == "TV" || upperName == "TURBOVID" || upperName.contains("TURBO") ||
                lowerLink.contains("turbovid") || lowerLink.contains("emturbovid") ||
                lowerLink.contains("turboviplay") || lowerLink.contains("turbosplayer") ||
                lowerLink.contains("turbovidhls") || lowerLink.contains("tvid") -> "TV"

            else -> upperName.ifBlank { "SERVER" }
        }
    }

    private fun appUserAgent(): String = headers["User-Agent"].orEmpty().ifBlank { ANDROID_USER_AGENT }

    private fun buildMediaHeaders(refererUrl: String): Headers {
        val httpUrl = refererUrl.toHttpUrlOrNull()
        // Prefer path without fragment for Referer; hosters ignore # but some CDNs are picky.
        val referer = httpUrl?.newBuilder()?.fragment(null)?.build()?.toString()
            ?: refererUrl.substringBefore('#')
        val origin = httpUrl?.let { "${it.scheme}://${it.host}" }
            ?: referer.removeSuffix("/").substringBeforeLast('/').takeIf { it.startsWith("http") }
            ?: referer

        return Headers.Builder()
            .set("User-Agent", appUserAgent())
            .set("Accept", "*/*")
            .set("Accept-Language", "en-US,en;q=0.9")
            .set("Origin", origin)
            .set("Referer", if (referer.endsWith("/")) referer else "$referer/")
            .build()
    }

    private fun cleanMediaHeaders(customHeaders: Headers?, fallbackUrl: String): Headers {
        val baseHeaders = customHeaders ?: buildMediaHeaders(fallbackUrl)
        val builder = baseHeaders.newBuilder()

        builder.removeAll("Sec-Fetch-Dest")
        builder.removeAll("Sec-Fetch-Mode")
        builder.removeAll("Sec-Fetch-Site")
        builder.removeAll("Sec-Fetch-User")
        builder.removeAll("Upgrade-Insecure-Requests")

        builder.set("User-Agent", appUserAgent())

        val accept = baseHeaders["Accept"]
        if (accept == null || accept.contains("text/html")) {
            builder.set("Accept", "*/*")
        }

        val fallbackHttpUrl = fallbackUrl.toHttpUrlOrNull()
        val fallbackOrigin = fallbackHttpUrl?.let { "${it.scheme}://${it.host}" }
        val fallbackReferer = fallbackHttpUrl?.newBuilder()?.fragment(null)?.build()?.toString()
            ?: fallbackUrl.substringBefore('#')

        val currentReferer = baseHeaders["Referer"]
        if (currentReferer.isNullOrBlank() || currentReferer.contains("supjav.com")) {
            builder.set("Referer", if (fallbackReferer.endsWith("/")) fallbackReferer else "$fallbackReferer/")
        }

        val currentOrigin = baseHeaders["Origin"]
        if (currentOrigin.isNullOrBlank() || currentOrigin.contains("supjav.com")) {
            if (fallbackOrigin != null) {
                builder.set("Origin", fallbackOrigin)
            }
        }

        return builder.build()
    }

    private suspend fun videosFromPlayer(player: Pair<String, String>): List<Video> {
        val (rawHosterName, rawLink) = player
        val resolved = resolveProtectorRedirect(rawLink) ?: return emptyList()
        // Drop #fragment (e.g. turbovidhls.com/t/xxx#supjav.com@title.mp4)
        val url = resolved.substringBefore('#').ifBlank { resolved }
        val host = url.toHttpUrlOrNull()?.host.orEmpty().lowercase()
        val hoster = detectHoster(rawHosterName, url)
        val mediaHeaders = buildMediaHeaders(url)

        return runCatching {
            val rawVideos = when {
                hoster == "ST" || host.contains("streamtape") || host.contains("strtape") ||
                    host.contains("tapecontent") || host.contains("strcloud") ->
                    streamtapeExtractor.videosFromUrl(url, quality = "ST")

                hoster == "VOE" || host.contains("voe") ->
                    VoeExtractor(client, mediaHeaders).videosFromUrl(url, prefix = "VOE ")

                hoster == "DOOD" || host.contains("dood") ||
                    host.contains("ds2play") || host.contains("doodstream") ||
                    host.contains("dood.to") || host.contains("dood.so") || host.contains("ds2video") ||
                    host.contains("d000d") || host.contains("do0od") ->
                    doodExtractor.videosFromUrl(url)

                hoster == "MIXDROP" || host.contains("mixdrop") || host.contains("mixdroop") ->
                    mixdropExtractor.videoFromUrl(url, prefix = "MixDrop - ")

                hoster == "UQLOAD" || host.contains("uqload") ->
                    uqloadExtractor.videosFromUrl(url, prefix = "Uqload - ")

                hoster == "VIDHIDE" || host.contains("vidhide") ||
                    host.contains("hide") || host.contains("streamhide") ->
                    VidHideExtractor(client, mediaHeaders).videosFromUrl(url)

                hoster == "FST" || isStreamWishLikeHost(host) ->
                    videosFromStreamWishLike(url, "FST")

                hoster == "TV" || isTurboVidHost(host) ->
                    videosFromTurboVid(url)

                else -> {
                    videosFromTurboVid(url)
                        .ifEmpty { videosFromStreamWishLike(url, rawHosterName.ifBlank { host }) }
                        .ifEmpty { videosFromGenericEmbed(url, rawHosterName.ifBlank { "SERVER" }) }
                }
            }

            rawVideos.map { video ->
                val refererForVid = video.videoUrl?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: url
                video.copy(headers = cleanMediaHeaders(video.headers, refererForVid))
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun resolveProtectorRedirect(rawLink: String): String? {
        var cleanRaw = rawLink.trim()
        if (cleanRaw.isBlank()) return null

        if (cleanRaw.startsWith("//")) {
            cleanRaw = "https:$cleanRaw"
        } else if (cleanRaw.startsWith("/")) {
            cleanRaw = "$baseUrl$cleanRaw"
        }

        if (cleanRaw.startsWith("http://") || cleanRaw.startsWith("https://")) {
            if (cleanRaw.contains("supjav.php") || isProtectorHost(cleanRaw)) {
                val fromPage = resolveViaProtectorPage(cleanRaw)
                if (!fromPage.isNullOrBlank()) return fromPage

                val paramId = cleanRaw.toHttpUrlOrNull()?.queryParameter("c")
                    ?: cleanRaw.toHttpUrlOrNull()?.queryParameter("l")
                    ?: Regex("""[?&](?:c|l)=([^&#]+)""").find(cleanRaw)?.groupValues?.get(1)

                if (!paramId.isNullOrBlank()) {
                    val fallbackRes = resolveProtectorFromId(paramId)
                    if (!fallbackRes.isNullOrBlank()) return fallbackRes
                }
                return null
            }
            // Already a hoster / m3u8 / embed URL
            return cleanRaw
        }

        val decoded = runCatching {
            String(android.util.Base64.decode(cleanRaw, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()
        if (decoded != null && (decoded.startsWith("http://") || decoded.startsWith("https://"))) {
            return resolveProtectorRedirect(decoded)
        }

        val decodedRev = runCatching {
            String(android.util.Base64.decode(cleanRaw.reversed(), android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()
        if (decodedRev != null && (decodedRev.startsWith("http://") || decodedRev.startsWith("https://"))) {
            return resolveProtectorRedirect(decodedRev)
        }

        // Hex / opaque token from data-link — JumpChain style
        return resolveProtectorFromId(cleanRaw)
    }

    private fun isProtectorHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        return host.contains("supremejav") || host.contains("supjav")
    }

    /**
     * Site flow (from theme JS):
     * 1) iframe src = `https://lk1.supremejav.com/supjav.php?l=<token>&bg=...`
     * 2) page JS reverses token and sets iframe to `?c=<reversed>`
     * 3) `?c=` responds with 302 Location → real hoster
     */
    private suspend fun resolveProtectorFromId(rawId: String): String? {
        val token = rawId.trim()
        if (token.isBlank()) return null

        // Preferred hosts: protector first (not CF-gated the same way as main site)
        val hosts = listOf(
            PROTECTOR_URL,
            "https://lk1.supremejav.com",
            "https://lk2.supremejav.com",
            baseUrl.trimEnd('/'),
        ).distinct()

        for (host in hosts) {
            // Step 1: open with ?l= (returns intermediate HTML)
            val lUrl = "$host/supjav.php?l=$token"
            val fromL = resolveViaProtectorPage(lUrl)
            if (!fromL.isNullOrBlank()) return fromL

            // Step 2: direct ?c= with reversed token (what the iframe ends up loading)
            val cReversed = "$host/supjav.php?c=${token.reversed()}"
            val locRev = fetchProtectorLocation(cReversed)
            if (!locRev.isNullOrBlank() && !locRev.contains("supjav.php") && locRev.startsWith("http")) {
                return locRev
            }

            // Step 3: direct ?c= with original token (some embeds skip reverse)
            val cOrig = "$host/supjav.php?c=$token"
            val locOrig = fetchProtectorLocation(cOrig)
            if (!locOrig.isNullOrBlank() && !locOrig.contains("supjav.php") && locOrig.startsWith("http")) {
                return locOrig
            }
        }
        return null
    }

    /** Fetch protector HTML/redirect and walk to a non-protector URL. */
    private suspend fun resolveViaProtectorPage(pageUrl: String): String? {
        val loc = fetchProtectorLocation(pageUrl) ?: return null
        if (loc.isBlank() || loc == pageUrl) return null

        if (!loc.contains("supjav.php") && loc.startsWith("http")) {
            return loc
        }

        // Relative ?c=... or another supjav.php hop
        val next = fetchProtectorLocation(loc)
        if (!next.isNullOrBlank() && !next.contains("supjav.php") && next.startsWith("http")) {
            return next
        }

        // If intermediate HTML only yielded another protector path, try reverse-token on same host
        val hostBase = pageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: PROTECTOR_URL
        val token = pageUrl.toHttpUrlOrNull()?.queryParameter("l")
            ?: pageUrl.toHttpUrlOrNull()?.queryParameter("c")
            ?: Regex("""[?&](?:c|l)=([^&#]+)""").find(pageUrl)?.groupValues?.get(1)
        if (!token.isNullOrBlank()) {
            val tryC = fetchProtectorLocation("$hostBase/supjav.php?c=${token.reversed()}")
            if (!tryC.isNullOrBlank() && !tryC.contains("supjav.php") && tryC.startsWith("http")) {
                return tryC
            }
        }
        return null
    }

    private suspend fun fetchProtectorLocation(targetUrl: String): String? = runCatching {
        val reqHeaders = Headers.Builder()
            .set("User-Agent", appUserAgent())
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.9")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        noRedirectClient.newCall(GET(targetUrl, reqHeaders)).await().use { resp ->
            val loc = (resp.header("location") ?: resp.header("Location"))?.trim()
            if (!loc.isNullOrEmpty()) {
                return@use resolveRelative(loc, targetUrl)
            }

            if (resp.isSuccessful) {
                val body = resp.bodyString()
                if (body.isNotBlank()) {
                    // JumpChain intermediate page: reverse OLID then load ?c=
                    val olid = OLID_REGEX.find(body)?.groupValues?.get(1)
                    if (!olid.isNullOrBlank()) {
                        val hostBase = targetUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                            ?: PROTECTOR_URL
                        return@use "$hostBase/supjav.php?c=${olid.reversed()}"
                    }

                    // src="?c="+OLID  or static src="?c=...."
                    val cQuery = Regex("""[?&]c=([a-fA-F0-9]{16,})""").find(body)?.groupValues?.get(1)
                    if (!cQuery.isNullOrBlank()) {
                        val hostBase = targetUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                            ?: PROTECTOR_URL
                        return@use "$hostBase/supjav.php?c=$cQuery"
                    }

                    val doc = Jsoup.parse(body, targetUrl)
                    val iframeSrc = doc.selectFirst("iframe[src], iframe[data-src]")?.let {
                        it.attr("data-src").ifBlank { it.attr("src") }
                    }?.trim()
                    if (!iframeSrc.isNullOrEmpty() && !iframeSrc.contains("404")) {
                        return@use resolveRelative(iframeSrc, targetUrl)
                    }

                    val jsLoc = Regex("""(?:window\.|location\.)(?:href|replace)\s*=\s*["']([^"']+)["']""")
                        .find(body)?.groupValues?.get(1)
                    if (!jsLoc.isNullOrEmpty()) {
                        return@use resolveRelative(jsLoc, targetUrl)
                    }

                    val metaRef = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                    val metaUrl = metaRef?.let {
                        Regex("""url=([^\s"']+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
                    }
                    if (!metaUrl.isNullOrEmpty()) {
                        return@use resolveRelative(metaUrl, targetUrl)
                    }

                    val directHosterUrl = DIRECT_HOSTER_REGEX.find(body)?.value
                    if (!directHosterUrl.isNullOrEmpty()) {
                        return@use resolveRelative(directHosterUrl, targetUrl)
                    }

                    if (body.contains("eval(function(p,a,c")) {
                        val unpacked = PACKED_REGEX.findAll(body).mapNotNull { m ->
                            runCatching { JsUnpacker.unpackAndCombine(m.value) }.getOrNull()
                        }.joinToString("\n")

                        val jsLocUnpacked = Regex("""(?:window\.|location\.)(?:href|replace)\s*=\s*["']([^"']+)["']""")
                            .find(unpacked)?.groupValues?.get(1)
                        if (!jsLocUnpacked.isNullOrEmpty()) {
                            return@use resolveRelative(jsLocUnpacked, targetUrl)
                        }
                        val iframeUnpacked = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                            .find(unpacked)?.groupValues?.get(1)
                        if (!iframeUnpacked.isNullOrEmpty()) {
                            return@use resolveRelative(iframeUnpacked, targetUrl)
                        }
                        val directHosterUnpacked = DIRECT_HOSTER_REGEX.find(unpacked)?.value
                        if (!directHosterUnpacked.isNullOrEmpty()) {
                            return@use resolveRelative(directHosterUnpacked, targetUrl)
                        }
                    }
                }
            }
            null
        }
    }.getOrNull()

    private fun resolveRelative(url: String, base: String): String {
        val clean = url.trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        if (clean.startsWith("//")) return "https:$clean"
        val baseObj = base.toHttpUrlOrNull() ?: return clean
        if (clean.startsWith("/")) {
            return "${baseObj.scheme}://${baseObj.host}$clean"
        }
        return "${baseObj.scheme}://${baseObj.host}/${clean.removePrefix("./")}"
    }

    private suspend fun videosFromTurboVid(url: String): List<Video> {
        // Strip fragment (#site@title) — only used by embed branding; confuses some parsers
        val cleanUrl = url.substringBefore('#').ifBlank { url }
        val pageHeaders = buildMediaHeaders(cleanUrl)
        val body = client.newCall(GET(cleanUrl, pageHeaders)).awaitSuccess().bodyString()

        val unpackedBody = if (body.contains("eval(function(p,a,c")) {
            val unpackedScripts = PACKED_REGEX.findAll(body).mapNotNull { m ->
                runCatching { JsUnpacker.unpackAndCombine(m.value) }.getOrNull()
            }.joinToString("\n")
            body + "\n" + unpackedScripts
        } else {
            body
        }

        val rawPlaylistUrl = URLPLAY_REGEX.find(unpackedBody)?.groupValues?.get(1)
            ?: DATA_HASH_REGEX.find(unpackedBody)?.groupValues?.get(1)
            ?: PLAYLIST_REGEX.find(unpackedBody)?.value
            ?: M3U8_REGEX.find(unpackedBody)?.value
            ?: return emptyList()

        val playlistUrl = resolveRelative(rawPlaylistUrl, cleanUrl)
        if (playlistUrl.toHttpUrlOrNull() == null) return emptyList()

        // HLS CDN often lives on cdn.turboviplay.com / hls*.turbosplayer.com — referer = embed page
        val hlsHeaders = buildMediaHeaders(playlistUrl).newBuilder()
            .set("Referer", cleanUrl)
            .set(
                "Origin",
                cleanUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://turbovidhls.com",
            )
            .build()

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl,
                referer = cleanUrl,
                masterHeaders = hlsHeaders,
                videoHeaders = hlsHeaders,
                videoNameGen = { "TV - $it" },
            ).distinctBy { it.videoUrl }
        }.getOrDefault(emptyList())
    }

    private suspend fun videosFromStreamWishLike(url: String, prefix: String): List<Video> {
        val mediaHeaders = buildMediaHeaders(url)
        val fromExtractor = runCatching {
            StreamWishExtractor(client, mediaHeaders).videosFromUrl(url) { "$prefix - $it" }
        }.getOrDefault(emptyList())
        if (fromExtractor.isNotEmpty()) return fromExtractor

        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(body, url)

        val scriptBody = doc.select("script").asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstNotNullOfOrNull { script ->
                when {
                    script.contains("eval(function(p,a,c") ->
                        JsUnpacker.unpackAndCombine(script)

                    script.contains("m3u8") || script.contains("master.txt") || script.contains("file:") -> script

                    else -> null
                }
            }
            ?: run {
                if (body.contains("eval(function(p,a,c")) {
                    PACKED_REGEX.findAll(body).mapNotNull { m ->
                        runCatching { JsUnpacker.unpackAndCombine(m.value) }.getOrNull()
                    }.joinToString("\n").ifBlank { null }
                } else {
                    null
                }
            }
            ?: return emptyList()

        val masterUrls = PLAYLIST_REGEX.findAll(scriptBody).map { it.value }.toList()
            .ifEmpty { M3U8_REGEX.findAll(scriptBody).map { it.value }.toList() }
        if (masterUrls.isEmpty()) return emptyList()

        val videos = mutableListOf<Video>()
        for (masterUrl in masterUrls) {
            val absMasterUrl = resolveRelative(masterUrl, url)
            val hlsHeaders = buildMediaHeaders(url)

            val extracted = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = absMasterUrl,
                    referer = url,
                    masterHeaders = hlsHeaders,
                    videoHeaders = hlsHeaders,
                    videoNameGen = { "$prefix - $it" },
                )
            }.getOrDefault(emptyList())
            videos.addAll(extracted)
            if (videos.isNotEmpty()) break
        }

        return videos.distinctBy { it.videoUrl }
    }

    private suspend fun videosFromGenericEmbed(url: String, hoster: String): List<Video> {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http")) return emptyList()

        val hlsHeaders = buildMediaHeaders(cleanUrl)

        if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
            return runCatching {
                playlistUtils.extractFromHls(
                    cleanUrl,
                    referer = cleanUrl,
                    masterHeaders = hlsHeaders,
                    videoHeaders = hlsHeaders,
                    videoNameGen = { "$hoster - $it" },
                )
            }.getOrDefault(emptyList())
        }

        return runCatching {
            val body = client.newCall(GET(cleanUrl, headers)).awaitSuccess().bodyString()
            val unpackedBody = if (body.contains("eval(function(p,a,c")) {
                val unpackedScripts = PACKED_REGEX.findAll(body).mapNotNull { m ->
                    runCatching { JsUnpacker.unpackAndCombine(m.value) }.getOrNull()
                }.joinToString("\n")
                body + "\n" + unpackedScripts
            } else {
                body
            }

            val m3u8Urls = M3U8_REGEX.findAll(unpackedBody).map { it.value }.toList()
            val videos = mutableListOf<Video>()
            for (m3u8 in m3u8Urls) {
                val absM3u8 = resolveRelative(m3u8, cleanUrl)
                val extracted = runCatching {
                    playlistUtils.extractFromHls(
                        absM3u8,
                        referer = cleanUrl,
                        masterHeaders = hlsHeaders,
                        videoHeaders = hlsHeaders,
                        videoNameGen = { "$hoster - $it" },
                    )
                }.getOrDefault(emptyList())
                videos.addAll(extracted)
                if (videos.isNotEmpty()) break
            }
            if (videos.isNotEmpty()) return@runCatching videos

            val doc = Jsoup.parse(unpackedBody, cleanUrl)
            val directSrc = doc.select("video source[src], video[src]").firstOrNull()?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }
            if (!directSrc.isNullOrBlank()) {
                val absDirect = resolveRelative(directSrc, cleanUrl)
                if (absDirect.startsWith("http")) {
                    return@runCatching listOf(Video(absDirect, "$hoster - Direct", absDirect, headers = buildMediaHeaders(absDirect)))
                }
            }

            val subIframe = doc.selectFirst("iframe[src], iframe[data-src]")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
            if (!subIframe.isNullOrEmpty() && subIframe != cleanUrl) {
                val absSub = resolveRelative(subIframe, cleanUrl)
                return@runCatching videosFromPlayer(hoster to absSub)
            }
            emptyList()
        }.getOrDefault(emptyList())
    }

    private fun isStreamWishLikeHost(host: String): Boolean = host.contains("fc2stream") ||
        host.contains("streamwish") ||
        host.contains("streamhg") ||
        host.contains("hlswish") ||
        host.contains("swish") ||
        host.contains("wish") ||
        host.contains("playerwish") ||
        host.contains("medix") ||
        host.contains("niram") ||
        host.contains("fst") ||
        host.contains("embedwish") ||
        host.contains("mwish") ||
        host.contains("wishembed") ||
        host.contains("sfast") ||
        host.contains("filelions") ||
        host.contains("lion") ||
        host.contains("jwplayer")

    private fun isTurboVidHost(host: String): Boolean = host.contains("turbovid") ||
        host.contains("emturbovid") ||
        host.contains("turboviplay") ||
        host.contains("turbovidhls") ||
        host.contains("turbosplayer") ||
        host.contains("sptvp") ||
        host.contains("tvid") ||
        host.contains("tbvid")

    override fun videoListSelector(): String = "html"

    override fun videoFromElement(element: Element): Video = Video("", "", "")

    override fun videoUrlParse(document: Document): String = ""

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
        /** Fallback only when app headers lack UA — Android Chrome shape for CF WebView. */
        private const val ANDROID_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"

        /** Protector intermediate page: `var OLID = 'hex...';` then reverse for `?c=`. */
        private val OLID_REGEX by lazy {
            Regex("""var\s+OLID\s*=\s*['"]([a-fA-F0-9]+)['"]""", RegexOption.IGNORE_CASE)
        }
        private val URLPLAY_REGEX by lazy {
            Regex("""urlPlay\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        }
        private val DATA_HASH_REGEX by lazy {
            Regex("""data-hash\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        }
        private val M3U8_REGEX by lazy { Regex("""https?://[^"'\s\\]+m3u8[^"'\s\\]*""") }
        private val PLAYLIST_REGEX by lazy {
            Regex("""https?://[^"'\s\\]+(?:m3u8|master\.txt)[^"'\s\\]*|/stream/[^"'\s\\]+""")
        }
        private val PACKED_REGEX by lazy {
            Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        }
        private val DIRECT_HOSTER_REGEX by lazy {
            Regex(
                """https?://[^"'\s\\]+\.(?:sx|com|net|org|to|so|la|ws|bz|ch|co|io|pro|tv|eu|link)/[^\s"'\\]*""" +
                    """(?:streamtape|strtape|strcloud|voe|dood|ds2play|mixdrop|uqload|vidhide|streamwish|""" +
                    """fc2stream|turbovid|turbovidhls|emturbovid|turboviplay|turbosplayer|filelions)[^\s"'\\]*""",
                RegexOption.IGNORE_CASE,
            )
        }

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private val CategoryList = listOf(
            "All" to "",
            "Censored" to "censored",
            "Uncensored" to "uncensored",
            "Amateur" to "amateur",
            "Reduced Price" to "reduced-price",
            "English Subtitle" to "english-subtitle",
            "VR" to "vr",
            "Chinese Subtitle" to "chinese-subtitle",
            "MGS" to "mgs",
            "Maker" to "maker",
        )

        private val TagList = listOf(
            "All" to "",
            "Creampie" to "creampie",
            "Married Woman" to "married-woman",
            "Slut" to "slut",
            "Big Tits" to "big-tits",
            "Solowork" to "solowork",
            "Mature Woman" to "mature-woman",
            "Beautiful Girl" to "beautiful-girl",
            "High Quality" to "high-quality-to-see-hd",
            "Subtitled" to "subtitled",
            "Mosaic Removed" to "mosaic-removed",
            "Censored" to "censored",
            "Uncensored" to "uncensored",
        )
    }
}
