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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

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
        val content = document.selectFirst("div.content > div.post-meta")!!
        title = content.selectFirst("h2")!!.text()
        thumbnail_url = content.selectFirst("img")?.run {
            val raw = attr("data-original").ifBlank { attr("data-src") }
                .ifBlank { attr("src") }
            if (raw.startsWith("http")) raw else absUrl(raw).ifBlank { raw }
        }

        content.selectFirst("div.cats")?.run {
            author = select("p:contains(Maker :) > a").textsOrNull()
            artist = select("p:contains(Cast :) > a").textsOrNull()
        }
        genre = content.select("div.tags > a").textsOrNull()
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

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else baseUrl + anime.url
        return GET(url, headers)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else baseUrl + anime.url
        return GET(url, headers)
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val url = if (episode.url.startsWith("http")) episode.url else baseUrl + episode.url
        return GET(url, headers)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()

        val playerElements = doc.select(
            "div.btnst a, div.btns a, a.btn-server, a[data-link], a[data-url], a[data-src], " +
                "a[href*=/supjav.php], a[href*=/supjav], ul.nav-tabs a, div.post-content a[href*=/supjav]",
        )

        val players = playerElements.mapNotNull { el ->
            val rawName = el.ownText().ifBlank { el.text() }.trim().uppercase()
            val rawLink = el.attr("data-link")
                .ifBlank { el.attr("data-url") }
                .ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("href") }
                .trim()
            if (rawLink.isEmpty() || rawLink.startsWith("javascript") || rawLink == "#") return@mapNotNull null

            val fallbackName = when {
                rawLink.contains("streamtape") || rawLink.contains("st") -> "ST"
                rawLink.contains("voe") -> "VOE"
                rawLink.contains("dood") || rawLink.contains("ds2") -> "DOOD"
                rawLink.contains("mixdrop") -> "MIXDROP"
                rawLink.contains("uqload") -> "UQLOAD"
                rawLink.contains("vidhide") || rawLink.contains("hide") -> "VIDHIDE"
                rawLink.contains("wish") || rawLink.contains("fst") -> "FST"
                rawLink.contains("turbo") || rawLink.contains("tv") -> "TV"
                else -> "SERVER"
            }
            val finalName = if (rawName.isBlank()) fallbackName else rawName
            finalName to rawLink
        }.distinctBy { it.second }

        val videoList = players.parallelCatchingFlatMapBlocking(::videosFromPlayer)
        if (videoList.isNotEmpty()) return videoList

        // Fallback to checking embedded iframes in post body
        val iframes = doc.select("iframe[src], iframe[data-src], div.post-content iframe, div.player-wrap iframe, div#dz_video iframe")
            .mapNotNull { el ->
                val src = el.attr("data-src").ifBlank { el.attr("src") }.trim()
                if (src.isBlank() || src.startsWith("javascript")) null else "EMBED" to src
            }
            .distinctBy { it.second }

        val iframeVideos = iframes.parallelCatchingFlatMapBlocking(::videosFromPlayer)
        if (iframeVideos.isNotEmpty()) return iframeVideos

        // Last resort: scan entire HTML body text for protector parameters, c=, l=, data-link or direct embed URLs
        val htmlBody = doc.outerHtml()
        val regexLinks = Regex("""(?:data-link|data-url|data-src|href|src)\s*=\s*["']([^"']+)["']""")
            .findAll(htmlBody)
            .map { it.groupValues[1].trim() }
            .filter { link ->
                link.contains("supjav.php") || link.contains("c=") || link.contains("l=") ||
                    link.contains("streamtape") || link.contains("voe") || link.contains("dood") ||
                    link.contains("mixdrop") || link.contains("uqload") || link.contains("vidhide") ||
                    link.contains("streamwish") || link.contains("turbovid") || link.contains("fc2stream")
            }
            .map { "SERVER" to it }
            .distinctBy { it.second }
            .toList()

        return regexLinks.parallelCatchingFlatMapBlocking(::videosFromPlayer)
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
        super.headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private suspend fun videosFromPlayer(player: Pair<String, String>): List<Video> {
        val (hoster, rawLink) = player
        val url = resolveProtectorRedirect(rawLink) ?: return emptyList()
        val host = url.toHttpUrlOrNull()?.host.orEmpty().lowercase()

        return runCatching {
            when {
                hoster == "ST" || host.contains("streamtape") || host.contains("strtape") ||
                    host.contains("tapecontent") ->
                    streamtapeExtractor.videosFromUrl(url, quality = "ST")

                hoster == "VOE" || host.contains("voe") ->
                    voeExtractor.videosFromUrl(url, prefix = "VOE ")

                hoster == "DS" || hoster == "DOOD" || host.contains("dood") ||
                    host.contains("ds2play") || host.contains("doodstream") ||
                    host.contains("dood.to") || host.contains("dood.so") || host.contains("ds2video") ->
                    doodExtractor.videosFromUrl(url)

                hoster == "MD" || hoster == "MIXDROP" || host.contains("mixdrop") ->
                    mixdropExtractor.videoFromUrl(url, prefix = "MixDrop - ")

                hoster == "UQ" || hoster == "UQLOAD" || host.contains("uqload") ->
                    uqloadExtractor.videosFromUrl(url, prefix = "Uqload - ")

                hoster == "VH" || host.contains("vidhide") || host.contains("hide") ->
                    vidhideExtractor.videosFromUrl(url)

                hoster == "FST" || isStreamWishLikeHost(host) ->
                    videosFromStreamWishLike(url, "FST")

                hoster == "TV" || isTurboVidHost(host) ->
                    videosFromTurboVid(url)

                else -> {
                    videosFromTurboVid(url)
                        .ifEmpty { videosFromStreamWishLike(url, hoster.ifBlank { host }) }
                        .ifEmpty { videosFromGenericEmbed(url, hoster) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun resolveProtectorRedirect(rawLink: String): String? {
        val cleanRaw = rawLink.trim()
        if (cleanRaw.isBlank()) return null
        if (cleanRaw.startsWith("http://") || cleanRaw.startsWith("https://")) {
            return cleanRaw
        }
        if (cleanRaw.startsWith("//")) {
            return "https:$cleanRaw"
        }

        val decoded = runCatching {
            String(android.util.Base64.decode(cleanRaw, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()
        if (decoded != null && (decoded.startsWith("http://") || decoded.startsWith("https://"))) {
            return decoded
        }

        val decodedRev = runCatching {
            String(android.util.Base64.decode(cleanRaw.reversed(), android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()
        if (decodedRev != null && (decodedRev.startsWith("http://") || decodedRev.startsWith("https://"))) {
            return decodedRev
        }

        val hosts = listOf("https://lk1.supremejav.com", "https://supjav.com")
        val params = listOf("c", "l")
        val ids = listOf(cleanRaw, cleanRaw.reversed()).distinct()

        for (host in hosts) {
            for (param in params) {
                for (id in ids) {
                    val targetUrl = "$host/supjav.php?$param=$id"
                    val location = fetchProtectorLocation(targetUrl)
                    if (!location.isNullOrBlank()) {
                        return location
                    }
                }
            }
        }
        return null
    }

    private suspend fun fetchProtectorLocation(targetUrl: String): String? = runCatching {
        val req = GET(targetUrl, protectorHeaders)
        noRedirectClient.newCall(req).await().use { resp ->
            val loc = (resp.header("location") ?: resp.header("Location"))?.trim()
            if (!loc.isNullOrEmpty()) {
                return@use when {
                    loc.startsWith("http") -> loc

                    loc.startsWith("//") -> "https:$loc"

                    loc.startsWith("/") -> {
                        val httpUrl = targetUrl.toHttpUrlOrNull()
                        if (httpUrl != null) "${httpUrl.scheme}://${httpUrl.host}$loc" else null
                    }

                    else -> null
                }
            }

            if (resp.isSuccessful) {
                val body = resp.bodyString()
                if (body.isNotBlank()) {
                    val doc = Jsoup.parse(body, targetUrl)
                    val iframeSrc = doc.selectFirst("iframe[src], iframe[data-src]")?.let {
                        it.attr("data-src").ifBlank { it.attr("src") }
                    }?.trim()
                    if (!iframeSrc.isNullOrEmpty() && (iframeSrc.startsWith("http") || iframeSrc.startsWith("//"))) {
                        return@use if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                    }

                    val jsLoc = Regex("""(?:window\.|location\.)(?:href|replace)\s*=\s*["'](https?://[^"']+)["']""")
                        .find(body)?.groupValues?.get(1)
                    if (!jsLoc.isNullOrEmpty()) return@use jsLoc

                    val metaRef = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                    val metaUrl = metaRef?.let { Regex("""url=(https?://[^\s"']+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                    if (!metaUrl.isNullOrEmpty()) return@use metaUrl
                }
            }
            null
        }
    }.getOrNull()

    private suspend fun videosFromTurboVid(url: String): List<Video> {
        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()

        val playlistUrl = URLPLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: PLAYLIST_REGEX.find(body)?.value
            ?: return emptyList()

        if (playlistUrl.toHttpUrlOrNull() == null) return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl,
            referer = url,
            videoNameGen = { "TV - $it" },
        ).distinctBy { it.videoUrl }
    }

    private suspend fun videosFromStreamWishLike(url: String, prefix: String): List<Video> {
        val fromExtractor = runCatching {
            streamwishExtractor.videosFromUrl(url) { "$prefix - $it" }
        }.getOrDefault(emptyList())
        if (fromExtractor.isNotEmpty()) return fromExtractor

        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(body, url)

        val scriptBody = doc.select("script").asSequence()
            .map { it.data() }
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
                    val packed = PACKED_REGEX.find(body)?.value
                    packed?.let { JsUnpacker.unpackAndCombine(it) }
                } else {
                    null
                }
            }
            ?: return emptyList()

        val baseUrlObj = url.toHttpUrlOrNull()
        val referer = baseUrlObj?.let { "${it.scheme}://${it.host}/" } ?: url

        val masterUrls = PLAYLIST_REGEX.findAll(scriptBody).map { it.value }.toList()
        if (masterUrls.isEmpty()) return emptyList()

        val videos = mutableListOf<Video>()
        for (masterUrl in masterUrls) {
            val absMasterUrl = when {
                masterUrl.startsWith("http://") || masterUrl.startsWith("https://") -> masterUrl
                masterUrl.startsWith("//") -> "https:$masterUrl"
                masterUrl.startsWith("/") && baseUrlObj != null -> "${baseUrlObj.scheme}://${baseUrlObj.host}$masterUrl"
                else -> masterUrl
            }
            val hlsHeaders = headers.newBuilder()
                .set("Accept", "*/*")
                .set("Accept-Language", "en-US,en;q=0.9")
                .set("Origin", referer.removeSuffix("/"))
                .set("Referer", referer)
                .build()

            val extracted = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = absMasterUrl,
                    referer = referer,
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

        val embedHost = cleanUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: url
        val hlsHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Accept-Language", "en-US,en;q=0.9")
            .set("Origin", embedHost.removeSuffix("/"))
            .set("Referer", embedHost)
            .build()

        if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
            return runCatching {
                playlistUtils.extractFromHls(
                    cleanUrl,
                    referer = embedHost,
                    masterHeaders = hlsHeaders,
                    videoHeaders = hlsHeaders,
                    videoNameGen = { "$hoster - $it" },
                )
            }.getOrDefault(emptyList())
        }

        return runCatching {
            val body = client.newCall(GET(cleanUrl, headers)).awaitSuccess().bodyString()
            val m3u8Urls = M3U8_REGEX.findAll(body).map { it.value }.toList()
            val videos = mutableListOf<Video>()
            for (m3u8 in m3u8Urls) {
                val extracted = runCatching {
                    playlistUtils.extractFromHls(
                        m3u8,
                        referer = embedHost,
                        masterHeaders = hlsHeaders,
                        videoHeaders = hlsHeaders,
                        videoNameGen = { "$hoster - $it" },
                    )
                }.getOrDefault(emptyList())
                videos.addAll(extracted)
                if (videos.isNotEmpty()) break
            }
            if (videos.isNotEmpty()) return@runCatching videos

            val doc = Jsoup.parse(body, cleanUrl)
            val subIframe = doc.selectFirst("iframe[src], iframe[data-src]")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
            if (!subIframe.isNullOrEmpty() && subIframe != cleanUrl) {
                val absSub = if (subIframe.startsWith("//")) "https:$subIframe" else subIframe
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
        host.contains("wishembed")

    private fun isTurboVidHost(host: String): Boolean = host.contains("turbovid") ||
        host.contains("emturbovid") ||
        host.contains("turboviplay") ||
        host.contains("sptvp") ||
        host.contains("turbosplayer") ||
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
        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"

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
