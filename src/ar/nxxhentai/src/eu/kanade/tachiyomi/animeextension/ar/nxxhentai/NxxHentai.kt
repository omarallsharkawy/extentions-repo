package eu.kanade.tachiyomi.animeextension.ar.nxxhentai

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * NxxHentai — https://nxxhentai.net/
 *
 * DooPlay / Vortex theme: series under `/anime/`, episodes under `/episodes/`,
 * multi-server via `doo_player_ajax`. Filters: sort, year (`release`), genre.
 *
 * Cloudflare: use app NetworkHelper client + default UA (do not force desktop UA).
 */
class NxxHentai :
    DooPlay(
        "ar",
        "NxxHentai",
        "https://nxxhentai.net",
    ) {

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .set("Referer", "$baseUrl/")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return GET(url, headers)
    }

    /** Catalog cards: series (tvshows) + movies + generic items. */
    private fun catalogSelector() = "article.item.tvshows div.poster, article.item.movies div.poster, " +
        "article.item div.poster, div.content article div.poster, div.items article div.poster"

    override fun popularAnimeSelector() = catalogSelector()

    override fun popularAnimeNextPageSelector() = "div.pagination span.current + a, div.pagination a.arrow_pag, " +
        "div.resppages a > i#nextpagination, div.resppages a > span.fa-chevron-left, " +
        "div.resppages a > i.fa-chevron-left, div.resppages a > span.fa-chevron-right, " +
        "div.resppages a > i.fa-caret-left, div.pagination a.inactive"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")
            ?: element.parent()?.selectFirst("a[href]")
            ?: element
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        setUrlWithoutDomain(href)
        val img = element.selectFirst("img")
        title = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: element.parent()?.selectFirst(".data h3 a, .data h3, h3")?.text().orEmpty()
        thumbnail_url = img?.getImageUrl()
    }

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodes"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/episodes/" else "$baseUrl/episodes/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = catalogSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override val fetchGenres = false

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Text query always wins (filters ignored).
        if (query.isNotBlank()) {
            val encoded = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            val url = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
            return GET(url, headers)
        }

        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.toUriPart().orEmpty()
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        val browse = filters.filterIsInstance<BrowseFilter>().firstOrNull()?.toUriPart().orEmpty()

        when (browse) {
            "movies" -> {
                val path = if (page <= 1) "$baseUrl/movies/" else "$baseUrl/movies/page/$page/"
                return GET(path, headers)
            }

            "episodes" -> {
                val path = if (page <= 1) "$baseUrl/episodes/" else "$baseUrl/episodes/page/$page/"
                return GET(path, headers)
            }
        }

        // Genre-only → cleaner /genre/{slug}/ archive (site supports both).
        if (genre.isNotBlank() && sort.isBlank() && year.isBlank()) {
            val path = if (page <= 1) {
                "$baseUrl/genre/$genre/"
            } else {
                "$baseUrl/genre/$genre/page/$page/"
            }
            return GET(path, headers)
        }

        // Year-only archive also exists as /release/{year}/
        if (year.isNotBlank() && genre.isBlank() && sort.isBlank()) {
            val path = if (page <= 1) {
                "$baseUrl/release/$year/"
            } else {
                "$baseUrl/release/$year/page/$page/"
            }
            return GET(path, headers)
        }

        // Combined / default catalog under /anime/ with query params.
        val path = if (page <= 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        val builder = path.toHttpUrl().newBuilder()
        if (sort.isNotBlank()) builder.addQueryParameter("sort", sort)
        if (year.isNotBlank()) builder.addQueryParameter("release", year)
        if (genre.isNotBlank()) builder.addQueryParameter("genre", genre)
        return GET(builder.build(), headers)
    }

    /**
     * DooPlay parent parse uses latestUpdatesSelector for non-?s= URLs and
     * mis-handles filter catalog pages. Always parse as catalog / search cards.
     */
    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(catalogSelector()).ifEmpty {
            document.select("div.result-item div.image a, .search-page .result-item article a")
        }
        val animes = elements.map { el ->
            if (el.tagName() == "a") {
                searchResultFromLink(el)
            } else {
                popularAnimeFromElement(el)
            }
        }.distinctBy { it.url }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    override fun searchAnimeSelector() = catalogSelector() + ", div.result-item div.image a"

    override fun searchAnimeFromElement(element: Element): SAnime = if (element.tagName() == "a") searchResultFromLink(element) else popularAnimeFromElement(element)

    private fun searchResultFromLink(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        val img = element.selectFirst("img")
        title = element.parents().select(".title a, .title, .details .title a").firstOrNull()?.text()
            ?: img?.attr("alt")
            ?: element.attr("title").ifBlank { element.text() }
        thumbnail_url = img?.getImageUrl()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            val sheader = doc.selectFirst("div.sheader")
            val poster = sheader?.selectFirst("div.poster img")
                ?: doc.selectFirst("div.poster img")
            thumbnail_url = poster?.getImageUrl()
            title = doc.selectFirst("div.wp-content > h1, div.data h1, h1")?.text()
                ?.ifBlank { null }
                ?: poster?.attr("alt").orEmpty()

            genre = doc.select(
                "div.sgeneros a, a[href*='/genre/'], a[href*='?genre='], " +
                    "div.wp-content a[href*='/genre/']",
            ).eachText().map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString()

            description = buildString {
                val paras = doc.select("div.wp-content p, #info p")
                    .map { it.text().trim() }
                    .filter { it.length > 20 && !it.contains("Patreon", ignoreCase = true) }
                if (paras.isNotEmpty()) append(paras.first())
            }.ifBlank { null }

            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    /**
     * Flat episodios list + any /episodes/ links (card UI). Prefer real episode
     * URLs so videoListParse hits the player page, not the series shell.
     */
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealAnimeDoc(response.asJsoup())
        val byUrl = LinkedHashMap<String, SEpisode>()

        fun addEpisode(a: Element, fallbackIndex: Int) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank() || href == doc.location()) return
            val okPath = href.contains("/episodes/") || href.contains("/movies/") ||
                a.selectFirst(".episodiotitle") != null
            if (!okPath) return
            val name = a.selectFirst(".episodiotitle")?.text()?.trim()
                ?.ifBlank { null }
                ?: a.attr("title").ifBlank { a.ownText() }.ifBlank {
                    a.parent()?.selectFirst(".episodiotitle")?.text()
                }.orEmpty().ifBlank { "الحلقة $fallbackIndex" }
            val ep = SEpisode.create().apply {
                setUrlWithoutDomain(href)
                this.name = name
                episode_number = EP_NUM.findAll(name).mapNotNull { it.groupValues[1].toFloatOrNull() }
                    .lastOrNull() ?: fallbackIndex.toFloat()
                date_upload = a.selectFirst(".episodate, .date")?.text()?.toDate()
                    ?: a.parent()?.selectFirst(".episodate, .date")?.text()?.toDate()
                    ?: 0L
            }
            byUrl.putIfAbsent(ep.url, ep)
        }

        var i = 1
        doc.select("ul.episodios > li a[href], ul.episodios li a[href]").forEach { addEpisode(it, i++) }
        i = 1
        doc.select("#episodes a[href*='/episodes/'], .se-a a[href*='/episodes/'], a[href*='/episodes/']")
            .forEach { addEpisode(it, i++) }

        if (byUrl.isNotEmpty()) {
            return byUrl.values.sortedByDescending { it.episode_number }
        }

        val seasonList = doc.select(seasonListSelector)
        if (seasonList.isNotEmpty()) {
            return seasonList.flatMap(::getSeasonEpisodes).reversed()
        }

        // Single movie / episode page opened as "anime"
        if (doc.selectFirst("ul#playeroptionsul, li.dooplay_player_option, video source, iframe.metaframe") != null) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
                    episode_number = 1F
                    name = doc.selectFirst("h1")?.text() ?: episodeMovieText
                },
            )
        }

        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(doc.location())
                episode_number = 1F
                name = episodeMovieText
            },
        )
    }

    override val episodeMovieText = "فيلم / Video"
    override val episodeSeasonPrefix = "موسم"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = document.location()
        val html = document.html()

        // Cloudflare challenge page — no player markup
        if (html.contains("Just a moment", ignoreCase = true) ||
            html.contains("Verifying you are human", ignoreCase = true) ||
            html.contains("cf-browser-verification", ignoreCase = true)
        ) {
            throw Exception(
                "Cloudflare blocked the episode page. Open WebView on this episode once, then retry play.",
            )
        }

        val videos = mutableListOf<Video>()

        // 1) Direct HTML5 / CDN sources (highest reliability)
        document.select(
            "video source[src], video#wpst-video source, #player source, " +
                ".responsive-player source, #dooplay_player_response source, source[src]",
        ).forEach { source ->
            val src = source.absUrl("src").ifBlank { source.attr("src") }.trim()
                .replace("\\/", "/")
            if (src.startsWith("http") && isMediaUrl(src)) {
                val label = source.attr("label").ifBlank {
                    QUALITY_IN_URL.find(src)?.value ?: "Player"
                }
                val tag = if (isBadProgressiveCdn(src)) "CDN fallback" else "Direct - $label"
                videos += streamFromUrl(src, tag)
            }
        }
        document.select("video[src]").forEach { v ->
            val src = v.absUrl("src").ifBlank { v.attr("src") }.trim()
            if (src.startsWith("http") && isMediaUrl(src) && !isBadProgressiveCdn(src)) {
                videos += streamFromUrl(src, "Direct")
            }
        }

        // Regex fallback for mp4/m3u8 in scripts / HTML
        MP4_OR_M3U8.findAll(html).map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .distinct()
            .filter { u ->
                u.startsWith("http") &&
                    !u.contains("wp-content/themes", ignoreCase = true) &&
                    !u.contains("px.gif")
            }
            .forEach { u ->
                if (isBadProgressiveCdn(u)) {
                    videos += streamFromUrl(u, "CDN fallback")
                } else {
                    val q = if (u.contains(".m3u8", true)) "HLS" else (QUALITY_IN_URL.find(u)?.value ?: "MP4")
                    videos += streamFromUrl(u, "Page - $q")
                }
            }

        // 2) Iframes already on page (first DooPlay tab often preloaded)
        val pageIframes = document.select(
            "iframe.metaframe, iframe.rptss, .playex iframe, " +
                "#dooplay_player_response iframe, #pframe iframe, iframe[src]",
        ).mapNotNull { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }.trim()
                .replace("&amp;", "&")
            src.takeIf { isUsefulEmbed(it) }
        }.distinct()
        pageIframes.forEach { embed ->
            videos += runCatching { runBlocking { extractFromEmbed(embed, "Embed") } }.getOrDefault(emptyList())
        }

        // 3) DooPlay ajax tabs
        val players = document.select(
            "ul#playeroptionsul li.dooplay_player_option, " +
                "ul#playeroptionsul li[data-post], li.dooplay_player_option",
        )
        players.forEach { player ->
            val name = player.selectFirst("span.title")?.text()?.trim()
                ?: player.ownText().trim().ifBlank { "Server ${player.attr("data-nume")}" }
            getPlayerUrl(player, pageUrl)?.let { embed ->
                videos += runCatching { runBlocking { extractFromEmbed(embed, name) } }.getOrDefault(emptyList())
            }
        }

        // 4) Download table /links/
        val downloads = document.select(
            "#download a[href*='/links/'], .links_table a[href*='/links/'], " +
                "table a[href*='/links/'], a.nxx-download-ad-trigger[href], " +
                ".box_links a[href*='/links/']",
        ).mapNotNull { a ->
            val href = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = a.parent()?.selectFirst(".link-server-name, .server-name, .title")?.text()
                ?: a.selectFirst(".link-server-name")?.text()
                ?: a.attr("title").ifBlank { "Download" }
            href to label.trim()
        }.distinctBy { it.first }
        downloads.forEach { (href, label) ->
            val extracted = runCatching {
                runBlocking {
                    val finalUrl = resolveRedirect(href)
                    if (finalUrl.isBlank() || (finalUrl == href && "/links/" in href)) {
                        // Try parsing a link page if its redirect is protected or absent.
                        extractFromLinkPage(href, label)
                    } else {
                        extractFromEmbed(finalUrl, label)
                    }
                }
            }.getOrDefault(emptyList())
            videos += extracted
        }

        var distinct = videos.distinctBy { it.url }

        // sacdnssedge progressive MP4 has moov-at-end → ExoPlayer often jumps to last second.
        val hosters = distinct.filterNot { isBadProgressiveCdn(it.url) }
        if (hosters.isNotEmpty()) {
            distinct = hosters
        }

        if (distinct.isEmpty()) {
            throw Exception(
                "No playable streams (empty player or Cloudflare). " +
                    "Open WebView on this episode URL, wait for the page, then retry Start.",
            )
        }
        return distinct
    }

    /** Non-faststart CDN stubs that cause scrubber-at-end / unplayable streams. */
    private fun isBadProgressiveCdn(url: String): Boolean {
        val u = url.lowercase()
        return "sacdnssedge" in u
    }

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy<Video> { hosterScore(it) }
                .thenBy { !it.quality.contains(pref, ignoreCase = true) }
                .thenByDescending { qualityRank(it.quality) },
        )
    }

    /** Lower score = preferred (played first). */
    private fun hosterScore(video: Video): Int {
        val q = (video.quality + " " + video.url).lowercase()
        return when {
            "dood" in q || "streamhg" in q || "hgcloud" in q || "streamwish" in q -> 0
            "mixdrop" in q || "mxdrop" in q -> 1
            "m3u8" in q || "hls" in q -> 1
            "streamtape" in q || "voe" in q || "uqload" in q -> 2
            "upns" in q || "rubyvidhub" in q || "playmogo" in q -> 3
            "embed" in q || "server" in q || "nxx" in q || "fast" in q -> 4
            "direct" in q || "page" in q -> 8
            isBadProgressiveCdn(video.url) || "cdn fallback" in q -> 9
            else -> 5
        }
    }

    private fun qualityRank(quality: String): Int = QUALITY_IN_URL.find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun isMediaUrl(url: String): Boolean = url.contains(".mp4", ignoreCase = true) ||
        url.contains(".m3u8", ignoreCase = true) ||
        url.contains("sacdnssedge", ignoreCase = true) ||
        (url.contains("/video/", ignoreCase = true) && url.contains("http"))

    private fun isUsefulEmbed(src: String): Boolean {
        if (!src.startsWith("http") && !src.startsWith("//")) return false
        val s = src.lowercase()
        return "google" !in s && "exoclick" !in s && "animeapp.org" !in s &&
            "doubleclick" !in s && "about:blank" !in s && s.length > 12
    }

    private fun getPlayerUrl(player: Element, pageUrl: String): String? {
        val post = player.attr("data-post").ifBlank { return null }
        val nume = player.attr("data-nume").ifBlank { return null }
        val type = player.attr("data-type").ifBlank { "tv" }

        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()

        val postHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .set("Origin", baseUrl)
            .set("Referer", pageUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val raw = runCatching {
            client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", postHeaders, body))
                .execute().use { it.body.string() }
        }.getOrNull().orEmpty()

        if (raw.isBlank() || raw.contains("Just a moment", ignoreCase = true)) return null

        // {"embed_url":"https:\/\/...","type":"mp4"} or empty
        val embedMatch = EMBED_URL_JSON.find(raw)
        val fromJson = embedMatch?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("\\\"", "\"")
            ?.trim()
            .orEmpty()
        if (fromJson.startsWith("//")) return "https:$fromJson"
        if (fromJson.startsWith("http")) return fromJson

        val iframe = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.replace("&amp;", "&")
        return when {
            iframe == null -> null
            iframe.startsWith("//") -> "https:$iframe"
            iframe.startsWith("http") -> iframe
            else -> null
        }
    }

    private fun resolveRedirect(url: String): String {
        val req = GET(
            url,
            headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .build(),
        )
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val final = resp.request.url.toString()
                if (final != url && final.startsWith("http") && "/links/" !in final) {
                    return@use final
                }
                val body = resp.body.string()
                val doc = org.jsoup.Jsoup.parse(body, url)
                doc.selectFirst("a[href^=http], a.button, .download-link a")?.absUrl("href")
                    ?.takeIf { it.startsWith("http") && "/links/" !in it }
                    ?: run {
                        val m = Regex(
                            """(?:location|window\.location|href)\s*=\s*['"](https?://[^'"]+)['"]""",
                        ).find(body)
                        m?.groupValues?.get(1)
                    }
                    ?: MP4_OR_M3U8.find(body)?.value
                    ?: final
            }
        }.getOrDefault(url)
    }

    private suspend fun extractFromLinkPage(url: String, label: String): List<Video> {
        val resolved = resolveRedirect(url)
        if (resolved != url && isUsefulEmbed(resolved)) {
            return extractFromEmbed(resolved, label)
        }
        val doc = runCatching {
            client.newCall(GET(url, headers.newBuilder().set("Referer", "$baseUrl/").build()))
                .execute().asJsoup()
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Video>()
        doc.select("iframe[src], a[href*='http']").forEach { el ->
            val u = when {
                el.hasAttr("src") -> el.absUrl("src")
                else -> el.absUrl("href")
            }
            if (isUsefulEmbed(u) && "/links/" !in u) {
                out += extractFromEmbed(u, label)
            }
        }
        MP4_OR_M3U8.findAll(doc.html()).map { it.value }.distinct().forEach {
            out += streamFromUrl(it, label)
        }
        return out
    }

    private suspend fun extractFromEmbed(url: String, label: String): List<Video> {
        var clean = url.trim().replace("&amp;", "&").replace("\\/", "/")
        if (clean.startsWith("//")) clean = "https:$clean"
        if (!clean.startsWith("http")) return emptyList()
        val lower = clean.lowercase()

        return when {
            lower.contains("dood") || lower.contains("d0o0d") || lower.contains("ds2play") ||
                lower.contains("ds2video") || lower.contains("doodstream") ->
                doodExtractor.videoFromUrl(clean, prefix = "$label - ")?.let(::listOf).orEmpty()

            lower.contains("mixdrop") || lower.contains("mxdrop") ->
                mixDropExtractor.videoFromUrl(clean, lang = label)

            STREAMWISH.any { it in lower } ->
                streamWishExtractor.videosFromUrl(clean, label)

            lower.contains("streamtape") || lower.contains("streamta.pe") ->
                streamTapeExtractor.videoFromUrl(clean, quality = label)?.let(::listOf).orEmpty()

            lower.contains("voe") || lower.contains("voe-unblock") ->
                voeExtractor.videosFromUrl(clean)

            lower.contains("uqload") ->
                uqloadExtractor.videosFromUrl(clean, "$label -")

            lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains("sacdnssedge") ->
                streamFromUrl(clean, label)

            lower.contains("upns") ->
                extractUpns(clean, label)

            lower.contains("rubyvidhub") || lower.contains("playmogo") ||
                lower.contains("vidhub") ->
                extractFromPlayerPage(clean, label)

            else -> {
                val uni = runCatching {
                    universalExtractor.videosFromUrl(clean, headers, prefix = "$label - ")
                }.getOrDefault(emptyList())
                if (uni.isNotEmpty()) return uni
                extractFromPlayerPage(clean, label)
            }
        }
    }

    private suspend fun extractUpns(url: String, label: String): List<Video> {
        val id = url.substringAfterLast('#').substringBefore('?').trim()
        if (id.isBlank() || id.startsWith("http")) {
            return extractFromPlayerPage(url, label)
        }
        val host = runCatching { url.toHttpUrl().host }.getOrNull()
            ?: return extractFromPlayerPage(url, label)
        val scheme = runCatching { url.toHttpUrl().scheme }.getOrDefault("https")
        val api = "$scheme://$host/api/v1/video?id=$id"
        val body = runCatching {
            client.newCall(
                GET(
                    api,
                    headers.newBuilder()
                        .set("Referer", "$scheme://$host/")
                        .set("Accept", "application/json,*/*")
                        .set("Origin", "$scheme://$host")
                        .build(),
                ),
            ).execute().use { it.body.string() }
        }.getOrNull().orEmpty()

        val found = mutableListOf<Video>()
        if (body.contains("http") && ("m3u8" in body || "mp4" in body)) {
            MP4_OR_M3U8.findAll(body).map { it.value.replace("\\/", "/") }.distinct().forEach {
                found += streamFromUrl(it, "$label Upns")
            }
        }
        if (found.isNotEmpty()) return found.distinctBy { it.url }
        return extractFromPlayerPage(url, label)
    }

    private suspend fun extractFromPlayerPage(url: String, label: String): List<Video> {
        val doc = runCatching {
            client.newCall(
                GET(
                    url,
                    headers.newBuilder()
                        .set("Referer", "$baseUrl/")
                        .build(),
                ),
            ).execute().asJsoup()
        }.getOrNull() ?: return emptyList()

        val nested = mutableListOf<Video>()
        val body = doc.html()

        doc.select("source[src], video[src], video source").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }
            if (src.startsWith("http") && isMediaUrl(src)) {
                nested += streamFromUrl(src, label)
            }
        }
        MP4_OR_M3U8.findAll(body).map { it.value.replace("\\/", "/") }.distinct().forEach {
            nested += streamFromUrl(it, "$label CDN")
        }
        // JWPlayer / Clappr style file:"..."
        Regex("""["']file["']\s*:\s*["'](https?://[^"']+)["']""")
            .findAll(body).map { it.groupValues[1] }.distinct().forEach {
                nested += streamFromUrl(it, label)
            }
        Regex("""["']sources?["']\s*:\s*\[\s*\{\s*["']file["']\s*:\s*["'](https?://[^"']+)["']""")
            .findAll(body).map { it.groupValues[1] }.distinct().forEach {
                nested += streamFromUrl(it, label)
            }

        doc.select("iframe[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }
            if (isUsefulEmbed(src) && src != url && "upns" !in src.lowercase()) {
                nested += extractFromEmbed(src, label)
            }
        }

        // Last resort: hash-based players sometimes put stream in query
        if (nested.isEmpty() && url.contains("#")) {
            val hash = url.substringAfter("#")
            if (hash.startsWith("http")) nested += streamFromUrl(hash, label)
        }

        return nested.distinctBy { it.url }
    }

    private fun streamFromUrl(url: String, quality: String): List<Video> {
        val clean = url.replace("\\/", "/").replace("&amp;", "&").trim()
        if (!clean.startsWith("http")) return emptyList()
        val vHeaders = videoHeaders()
        return when {
            clean.contains(".m3u8", ignoreCase = true) -> {
                runCatching {
                    playlistUtils.extractFromHls(
                        clean,
                        referer = "$baseUrl/",
                        videoNameGen = { q -> "$quality - $q" },
                    )
                }.getOrElse { listOf(Video(clean, quality, clean, headers = vHeaders)) }
            }

            else -> listOf(Video(clean, quality, clean, headers = vHeaders))
        }
    }

    private fun videoHeaders() = headers.newBuilder()
        .set(
            "User-Agent",
            headers["User-Agent"]
                ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        )
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "*/*")
        .build()

    // ============================== Filters ===============================
    // Search tab + empty query → filters apply. Popular tab ignores filters.
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("استخدم تبويب البحث (اترك المربع فارغاً) لتطبيق الفلاتر"),
        AnimeFilter.Header("البحث النصي يتجاهل الفلاتر"),
        BrowseFilter(),
        SortFilter(),
        YearFilter(),
        GenreFilter(),
    )

    private class BrowseFilter :
        UriPartFilter(
            "تصفح",
            arrayOf(
                Pair("قائمة الهنتاي /anime/", ""),
                Pair("أحدث الحلقات /episodes/", "episodes"),
                Pair("أفلام /movies/", "movies"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "ترتيب (على /anime/)",
            arrayOf(
                Pair("آخر ما تم نشره", ""),
                Pair("أحدث الإصدارات", "release"),
                Pair("A → Z", "az"),
            ),
        )

    private class YearFilter :
        UriPartFilter(
            "سنة الإصدار",
            arrayOf(Pair("الكل", "")) +
                (2026 downTo 2000).map { Pair(it.toString(), it.toString()) }.toTypedArray(),
        )

    private class GenreFilter :
        UriPartFilter(
            "التصنيف",
            arrayOf(
                Pair("الكل", ""),
                Pair("بدون حجب", "بدون-حجب"),
                Pair("محارم", "محارم"),
                Pair("حريم", "حريم"),
                Pair("ميلف", "ميلف"),
                Pair("معلمة", "معلمة"),
                Pair("معلم", "معلم"),
                Pair("طالبة مدرسة", "طالبة-مدرسة"),
                Pair("اثداء كبيرة", "اثداء-كبيرة"),
                Pair("اغتصاب", "اغتصاب"),
                Pair("اغتصاب جماعي", "اغتصاب-جماعي"),
                Pair("تحكم بالعقل", "تحكم-بالعقل"),
                Pair("تحطم العقل", "تحطم-العقل"),
                Pair("تنويم المغناطيسي", "تنويم-المغناطيسي"),
                Pair("تصوير", "تصوير"),
                Pair("تعابير منحرفة", "تعابير-منحرفة"),
                Pair("جنس فموي", "جنس-فموي"),
                Pair("جنس ثلاثي", "جنس-ثلاثي"),
                Pair("جنس جماعي", "جنس-جماعي"),
                Pair("جنس بالثدي", "جنس-بالثدي"),
                Pair("جنس بالعام", "جنس-بالعام"),
                Pair("خيانة", "خيانة"),
                Pair("دياثة", "دياثة"),
                Pair("يوري", "يوري"),
                Pair("ياوي", "ياوي"),
                Pair("فوتا", "فوتا"),
                Pair("لولي", "لولي"),
                Pair("شوتا", "شوتا"),
                Pair("حمل", "حمل"),
                Pair("عبودية", "عبودية"),
                Pair("كوميدي", "كوميدي"),
                Pair("رومانسي", "رومانسي"),
                Pair("دراما", "دراما"),
                Pair("فانتازيا", "فانتازيا"),
                Pair("فانيلا", "فانيلا"),
                Pair("هنتاي", "هنتاي"),
                Pair("وحوش", "وحوش"),
                Pair("مجسات", "مجسات"),
                Pair("خادمات", "خادمات"),
                Pair("ممرضة", "ممرضة"),
                Pair("كوسبلاي", "كوسبلاي"),
                Pair("فتاة قطة", "فتاة-قطة"),
                Pair("إبتزاز", "إبتزاز"),
                Pair("إذلال", "إذلال"),
                Pair("إلف", "إلف"),
                Pair("إيسيكاي", "إيسيكاي-isekai"),
                Pair("اخ و أخت", "اخ-و-أخت"),
                Pair("استمناء", "استمناء"),
                Pair("التحكم بالأشخاص", "التحكم-بالأشخاص"),
                Pair("العاب جنسية", "العاب-جنسية"),
                Pair("الفتاة السحرية", "الفتاة-السحرية"),
                Pair("بشرة سوداء", "بشرة-سوداء"),
                Pair("تبادل الزوجات", "تبادل-الزوجات"),
                Pair("تحرش في المواصلات", "تحرش-في-المواصلات"),
                Pair("تسوندري", "تسوندري"),
                Pair("خيال", "خيال"),
                Pair("ربات البيوت", "ربات-البيوت"),
                Pair("رعب", "رعب"),
                Pair("رياضة", "رياضة"),
                Pair("سيصدر قريباً", "سيصدر-قريباً"),
                Pair("شريحة من الحياة", "شريحة-من-الحياة"),
                Pair("شياطين", "شياطين"),
                Pair("عاهرات", "عاهرات"),
                Pair("عذرية", "عذرية"),
                Pair("قوة خارقة", "قوة-خارقة"),
                Pair("ملابس داخلية", "ملابس-داخلية"),
                Pair("ملابس سباحة", "ملابس-سباحة"),
                Pair("موظفات المكاتب", "موظفات-المكاتب"),
                Pair("وغد قبيح", "وغد-قبيح"),
                Pair("عنف و رطب حبال", "عنف-و-رطب-حبال"),
            ),
        )

    companion object {
        private val EP_NUM = Regex("""(\d{1,4})""")
        private val EMBED_URL_JSON = Regex(""""embed_url"\s*:\s*"([^"]*)"""")
        private val MP4_OR_M3U8 = Regex(
            """https?://[^\s"'<>\\]+?\.(?:mp4|m3u8)[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE,
        )
        private val QUALITY_IN_URL = Regex("""(?i)(2160|1440|1080|720|480|360|240)p?""")

        private val STREAMWISH = listOf(
            "streamwish", "ahvsh", "wishfast", "sfastwish", "flaswish", "awish",
            "dwish", "playerwish", "streamhg", "hgcloud", "hglink", "vidhide",
            "streamhide", "filelions", "vidmoly", "smoothpre",
        )
    }
}
