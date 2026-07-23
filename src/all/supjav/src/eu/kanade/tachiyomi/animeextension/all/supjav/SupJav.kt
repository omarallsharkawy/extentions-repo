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
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
        // Listing pages contain several <div class="posts"> blocks: the main
        // listing plus sidebar blocks like "Today's Popular" that repeat the
        // same items on EVERY page. Selecting all blocks duplicates entries
        // while scrolling (visible jank/repeats) and pollutes Latest/Search.
        val allBlocks = document.select("div.posts")
        val mainBlocks = allBlocks.filter { block ->
            val heading = block.previousElementSibling()?.selectFirst("h2")?.text()
                ?: block.parent()?.selectFirst("div.archive-title > h2")?.text()
            heading?.contains("popular", ignoreCase = true) != true
        }.ifEmpty { allBlocks.take(1) } // fallback: main listing is always first

        val animeElements = if (mainBlocks.isNotEmpty()) {
            mainBlocks.flatMap { it.select("div.post > a") }
        } else {
            document.select(selector)
        }

        val animes = animeElements.mapNotNull { element ->
            runCatching { popularAnimeFromElement(element) }.getOrNull()
        }.filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { it.url }

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

    /** "Yua Mikami" -> "yua-mikami" (site slugs are lowercase-dash). */
    private fun slugify(text: String): String = text.trim().lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    private fun pagedPath(path: String, page: Int): String = if (page > 1) "$baseUrl$langPath/$path/page/$page/" else "$baseUrl$langPath/$path/"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var categorySlug = ""
        var tagSlug = ""
        var castSlug = ""
        var makerSlug = ""
        var sortOption = 0

        for (filter in filters) {
            when (filter) {
                is SortFilter -> sortOption = filter.state

                is CategoryFilter -> categorySlug = CategoryList.getOrNull(filter.state)?.second.orEmpty()

                is TagFilter -> {
                    if (filter.state > 0) {
                        tagSlug = TagList.getOrNull(filter.state)?.second.orEmpty()
                    }
                }

                is CustomTagFilter -> {
                    if (filter.state.isNotBlank()) {
                        tagSlug = slugify(filter.state)
                    }
                }

                is CastFilter -> {
                    if (filter.state.isNotBlank()) {
                        castSlug = slugify(filter.state)
                    }
                }

                is MakerFilter -> {
                    if (filter.state.isNotBlank()) {
                        makerSlug = slugify(filter.state)
                    }
                }

                else -> {}
            }
        }

        // Real site structure (verified against live pages):
        //   cast:   /category/cast/<slug>/
        //   maker:  /category/maker/<slug>/
        //   tag:    /tag/<slug>/
        //   sort:   /popular/?sort=week|month  (query param, NOT /popular/week/)
        val basePath = when {
            castSlug.isNotBlank() -> pagedPath("category/cast/$castSlug", page)

            makerSlug.isNotBlank() -> pagedPath("category/maker/$makerSlug", page)

            tagSlug.isNotBlank() -> pagedPath("tag/$tagSlug", page)

            categorySlug.isNotBlank() -> pagedPath("category/$categorySlug", page)

            else -> {
                when (sortOption) {
                    1 -> if (page > 1) "$baseUrl$langPath/popular/page/$page/" else "$baseUrl$langPath/popular/"
                    2 -> if (page > 1) "$baseUrl$langPath/popular/page/$page/?sort=week" else "$baseUrl$langPath/popular/?sort=week"
                    3 -> if (page > 1) "$baseUrl$langPath/popular/page/$page/?sort=month" else "$baseUrl$langPath/popular/?sort=month"
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
        AnimeFilter.Header("Slug = lowercase words joined with '-'"),
        CastFilter(),
        MakerFilter(),
        CustomTagFilter(),
    )

    private class SortFilter :
        AnimeFilter.Select<String>(
            "Sort By",
            arrayOf("Latest", "Popular All Time", "Popular Week", "Popular Month"),
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

    private class CastFilter : AnimeFilter.Text("Cast / Actress Slug (e.g. yua-mikami)")
    private class MakerFilter : AnimeFilter.Text("Maker / Studio Slug (e.g. s1-no-1-style)")
    private class CustomTagFilter : AnimeFilter.Text("Custom Tag Slug")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.content > div.post-meta, div.post-meta, div.content")
        title = content?.selectFirst("h2, h1")?.text()
            ?: document.selectFirst("h1, h2")?.text().orEmpty()
        thumbnail_url = content?.selectFirst("img")?.run {
            val raw = attr("data-original").ifBlank { attr("data-src") }
                .ifBlank { attr("src") }
            if (raw.startsWith("http")) raw else absUrl(raw).ifBlank { raw }
        } ?: document.selectFirst("div.content img, img")?.attr("src")

        // Real markup: <div class="post-meta"> <p class="cat">..</p>
        //   <p><span>Maker : </span><a .../></p> <p><span>Cast : </span><a .../></p>
        //   <div class="tags"><a .../></div> </div>
        author = content?.select("p:contains(Maker :) > a")?.textsOrNull()
        artist = content?.select("p:contains(Cast :) > a")?.textsOrNull()
        genre = (
            content?.select("p.cat > a")?.eachText().orEmpty() +
                content?.select("div.tags > a")?.eachText().orEmpty()
            ).distinct().joinToString().takeUnless { it.isEmpty() }
        description = document.selectFirst("div.post-content")?.text()?.trim()
            ?.take(800)?.takeUnless { it.isEmpty() }
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
        val body = response.bodyString()
        // CF challenge page has no servers — surface a real error instead of
        // a silent empty list (user just saw "host list is empty").
        val isCfChallenge = body.contains("Just a moment", ignoreCase = true) &&
            (
                body.contains("cf-browser-verification", ignoreCase = true) ||
                    body.contains("challenge-platform", ignoreCase = true) ||
                    body.contains("cf-mitigated", ignoreCase = true)
                )
        if (isCfChallenge) {
            throw Exception("SupJav: Cloudflare check is blocking the video page. Open the entry in WebView, solve the check, then retry.")
        }

        val doc = Jsoup.parse(body, response.request.url.toString())
        val players = collectPlayers(doc, body)
        if (players.isEmpty()) {
            throw Exception("SupJav: no video servers found on the episode page (site layout may have changed).")
        }

        // ST / FST first — TurboVid (TV) often serves ad-only playlists
        val ordered = players.sortedBy { (name, _) ->
            when (name) {
                "ST" -> 0
                "FST" -> 1
                "VOE" -> 2
                "TV" -> 9
                else -> 5
            }
        }

        // Resolve all servers in parallel — sequential resolution with
        // protector redirects + playlist probes can take 30s+ and the
        // app gives up before any video appears.
        hosterFailReasons.clear()
        val videoList = runBlocking {
            ordered.map { player ->
                async {
                    runCatching { videosFromPlayer(player) }.getOrDefault(emptyList())
                }
            }.flatMap { it.await() }
        }
        val result = videoList.distinctBy { it.videoUrl ?: it.url }
        if (result.isEmpty()) {
            val reasons = hosterFailReasons.entries
                .joinToString("; ") { (k, v) -> "$k: $v" }
                .take(500)
                .ifBlank { "unknown error" }
            throw Exception("SupJav: all ${players.size} server(s) failed — $reasons")
        }
        return result
    }

    /** Collect server buttons: DOM first, then HTML regex for hex data-link tokens. */
    private fun collectPlayers(doc: Document, htmlBody: String): List<Pair<String, String>> {
        val out = linkedMapOf<String, String>() // token/url -> hoster name

        fun add(name: String, link: String) {
            val clean = link.trim()
            if (clean.isEmpty() || clean.startsWith("javascript") || clean == "#") return
            // Prefer first non-blank short hoster name
            val key = clean
            if (!out.containsKey(key) || out[key].isNullOrBlank() || out[key] == "SERVER") {
                out[key] = name.ifBlank { detectHoster(name, clean) }
            }
        }

        // Primary: .btn-server[data-link] (site layout)
        doc.select("a.btn-server, div.btnst a.btn-server, div.btnst a[data-link]").forEach { el ->
            val rawName = el.ownText().ifBlank { el.text() }.trim().uppercase()
                .replace("SERVER", "").replace(":", "").trim()
            val rawLink = el.attr("data-link")
                .ifBlank { el.attr("data-url") }
                .ifBlank { el.attr("href") }
            add(rawName, rawLink)
        }

        // Any remaining data-link on page (avoid download buttons)
        doc.select("a[data-link], button[data-link]").forEach { el ->
            if (el.hasClass("btn-down") || el.parents().any { it.hasClass("downs") || it.hasClass("downscase") }) {
                return@forEach
            }
            val rawName = el.ownText().ifBlank { el.text() }.trim().uppercase()
                .replace("SERVER", "").replace(":", "").trim()
            add(rawName, el.attr("data-link"))
        }

        // Regex fallback: data-link="hex..." >LABEL<
        BTN_SERVER_REGEX.findAll(htmlBody).forEach { m ->
            val link = m.groupValues[1].ifBlank { m.groupValues[3] }
            val name = m.groupValues[2].ifBlank { m.groupValues[4] }.trim().uppercase()
            add(name, link)
        }

        // Hex-only tokens (32+ hex chars) near btn-server
        HEX_DATA_LINK_REGEX.findAll(htmlBody).forEach { m ->
            add(m.groupValues[2].trim().uppercase().ifBlank { "SERVER" }, m.groupValues[1])
        }

        // Direct protector / hoster URLs in markup
        Regex("""https?://[^"'\s]+(?:supjav\.php|supremejav|turbovid|streamtape|voe\.|fc2stream|streamwish)[^"'\s]*""", RegexOption.IGNORE_CASE)
            .findAll(htmlBody)
            .forEach { add("SERVER", it.value) }

        return out.map { (link, name) -> detectHoster(name, link) to link }
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

    /** Last failure reason per hoster — surfaced when every server fails. */
    private val hosterFailReasons = java.util.concurrent.ConcurrentHashMap<String, String>()

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
        val label = rawHosterName.ifBlank { "SERVER" }
        val resolved = resolveProtectorRedirect(rawLink)
        if (resolved == null) {
            hosterFailReasons[label] = "link protector resolve failed"
            return emptyList()
        }
        // Drop #fragment (e.g. turbovidhls.com/t/xxx#supjav.com@title.mp4)
        val url = resolved.substringBefore('#').ifBlank { resolved }
        val host = url.toHttpUrlOrNull()?.host.orEmpty().lowercase()
        val hoster = detectHoster(rawHosterName, url)
        val mediaHeaders = buildMediaHeaders(url)

        return runCatching {
            val rawVideos = when {
                hoster == "ST" || host.contains("streamtape") || host.contains("strtape") ||
                    host.contains("tapecontent") || host.contains("strcloud") ->
                    videosFromStreamTape(url)

                hoster == "VOE" || host.contains("voe") || host.contains("impactstation") ->
                    videosFromVoe(url, mediaHeaders)

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
                    host.contains("streamhide") ->
                    VidHideExtractor(client, mediaHeaders).videosFromUrl(url)

                hoster == "FST" || isStreamWishLikeHost(host) ->
                    videosFromStreamWishLike(url, "FST")

                hoster == "TV" || isTurboVidHost(host) ->
                    videosFromTurboVid(url)

                else -> {
                    videosFromTurboVid(url)
                        .ifEmpty { videosFromStreamWishLike(url, rawHosterName.ifBlank { host }) }
                        .ifEmpty { videosFromStreamTape(url) }
                        .ifEmpty { videosFromVoe(url, mediaHeaders) }
                        .ifEmpty { videosFromGenericEmbed(url, rawHosterName.ifBlank { "SERVER" }) }
                }
            }

            if (rawVideos.isEmpty()) {
                hosterFailReasons[hoster.ifBlank { label }] = "no playable stream found"
            }

            rawVideos.map { video ->
                // Keep hoster-page Referer for CDN URLs (tapecontent, hls CDN).
                // Using CDN host as Referer breaks StreamTape / FST auth.
                val keepReferer = when (hoster) {
                    "ST" -> "https://streamtape.com/"
                    "FST" -> url
                    "VOE" -> url
                    "TV" -> url
                    else -> url
                }
                val vidHeaders = video.headers ?: buildMediaHeaders(keepReferer)
                video.copy(headers = fixPlaybackHeaders(vidHeaders, keepReferer, hoster))
            }
        }.getOrElse { e ->
            hosterFailReasons[hoster.ifBlank { label }] = (e.message ?: e.javaClass.simpleName).take(150)
            emptyList()
        }
    }

    /** Playback headers: correct Referer/Origin for each hoster family. */
    private fun fixPlaybackHeaders(base: Headers, embedOrReferer: String, hoster: String): Headers {
        val builder = base.newBuilder()
        builder.removeAll("Sec-Fetch-Dest")
        builder.removeAll("Sec-Fetch-Mode")
        builder.removeAll("Sec-Fetch-Site")
        builder.removeAll("Sec-Fetch-User")
        builder.removeAll("Upgrade-Insecure-Requests")
        builder.set("User-Agent", appUserAgent())
        builder.set("Accept", "*/*")

        when (hoster) {
            "ST" -> {
                builder.set("Referer", "https://streamtape.com/")
                builder.set("Origin", "https://streamtape.com")
            }

            "FST" -> {
                // FST blocks embeds unless referer is the player page or supjav
                val ref = embedOrReferer.ifBlank { "$baseUrl/" }
                builder.set("Referer", if (ref.endsWith("/")) ref else "$ref/")
                val origin = ref.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: baseUrl
                builder.set("Origin", origin)
            }

            else -> {
                val ref = embedOrReferer.substringBefore('#')
                val origin = ref.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                builder.set("Referer", if (ref.endsWith("/")) ref else "$ref/")
                if (origin != null) builder.set("Origin", origin)
            }
        }
        return builder.build()
    }

    private fun isHexToken(value: String): Boolean = value.length >= 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private suspend fun resolveProtectorRedirect(rawLink: String): String? {
        var cleanRaw = rawLink.trim()
        if (cleanRaw.isBlank()) return null

        if (cleanRaw.startsWith("//")) {
            cleanRaw = "https:$cleanRaw"
        } else if (cleanRaw.startsWith("/")) {
            cleanRaw = "$baseUrl$cleanRaw"
        }

        // Pure hex data-link token → JumpChain reverse + ?c=
        if (isHexToken(cleanRaw)) {
            return resolveProtectorFromId(cleanRaw)
        }

        if (cleanRaw.startsWith("http://") || cleanRaw.startsWith("https://")) {
            if (cleanRaw.contains("supjav.php") || isProtectorHost(cleanRaw)) {
                val paramId = cleanRaw.toHttpUrlOrNull()?.queryParameter("c")
                    ?: cleanRaw.toHttpUrlOrNull()?.queryParameter("l")
                    ?: Regex("""[?&](?:c|l)=([^&#]+)""").find(cleanRaw)?.groupValues?.get(1)

                if (!paramId.isNullOrBlank()) {
                    val fromId = resolveProtectorFromId(paramId)
                    if (!fromId.isNullOrBlank()) return fromId
                }

                val fromPage = resolveViaProtectorPage(cleanRaw)
                if (!fromPage.isNullOrBlank()) return fromPage
                return null
            }
            return cleanRaw
        }

        // Only try Base64 if it looks like base64 (not pure hex — hex can false-decode)
        if (cleanRaw.length % 4 == 0 && cleanRaw.any { it == '+' || it == '/' || it == '=' }) {
            val decoded = runCatching {
                String(android.util.Base64.decode(cleanRaw, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrNull()
            if (decoded != null && (decoded.startsWith("http://") || decoded.startsWith("https://"))) {
                return resolveProtectorRedirect(decoded)
            }
        }

        return resolveProtectorFromId(cleanRaw)
    }

    private fun isProtectorHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        return host.contains("supremejav") || (host.contains("supjav") && !host.contains("turbovid"))
    }

    /**
     * Fast path proven against live protector:
     * GET `https://lk1.supremejav.com/supjav.php?c=<token.reversed()>`
     * → 302 Location to turbovid / streamtape / voe / fc2stream
     */
    private suspend fun resolveProtectorFromId(rawId: String): String? {
        val token = rawId.trim()
        if (token.isBlank()) return null

        val hosts = listOf(
            "https://lk1.supremejav.com",
            "https://lk2.supremejav.com",
            PROTECTOR_URL,
            baseUrl.trimEnd('/'),
        ).distinct()

        val candidates = listOf(
            token.reversed(), // primary — what site JS does after loading ?l=
            token, // rare embeds skip reverse
        ).distinct()

        // 1) Follow redirects with normal client (fastest / most reliable)
        for (host in hosts) {
            for (id in candidates) {
                val target = "$host/supjav.php?c=$id"
                val finalUrl = followProtectorRedirects(target)
                if (!finalUrl.isNullOrBlank()) return finalUrl
            }
        }

        // 2) No-redirect Location header parse
        for (host in hosts) {
            for (id in candidates) {
                val loc = fetchProtectorLocation("$host/supjav.php?c=$id")
                if (!loc.isNullOrBlank() && !loc.contains("supjav.php") && loc.startsWith("http")) {
                    return loc.substringBefore('#')
                }
            }
        }

        // 3) Intermediate ?l= HTML → reverse → ?c=
        for (host in hosts) {
            val fromL = resolveViaProtectorPage("$host/supjav.php?l=$token")
            if (!fromL.isNullOrBlank()) return fromL
        }
        return null
    }

    /** Follow HTTP redirects; return final non-protector URL. */
    private suspend fun followProtectorRedirects(startUrl: String): String? = runCatching {
        val reqHeaders = protectorRequestHeaders()
        client.newCall(GET(startUrl, reqHeaders)).await().use { resp ->
            val finalUrl = resp.request.url.toString().substringBefore('#')
            if (finalUrl.startsWith("http") &&
                !finalUrl.contains("supjav.php", ignoreCase = true) &&
                !isProtectorHost(finalUrl)
            ) {
                return@use finalUrl
            }
            // Manual Location if somehow not followed
            val loc = resp.header("Location") ?: resp.header("location")
            if (!loc.isNullOrBlank()) {
                val abs = resolveRelative(loc, startUrl).substringBefore('#')
                if (abs.startsWith("http") && !abs.contains("supjav.php")) return@use abs
            }
            null
        }
    }.getOrNull()

    private fun protectorRequestHeaders(): Headers = Headers.Builder()
        .set("User-Agent", appUserAgent())
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .build()

    /** Fetch protector HTML/redirect and walk to a non-protector URL. */
    private suspend fun resolveViaProtectorPage(pageUrl: String): String? {
        val loc = fetchProtectorLocation(pageUrl) ?: return null
        if (loc.isBlank() || loc == pageUrl) return null

        if (!loc.contains("supjav.php") && loc.startsWith("http")) {
            return loc.substringBefore('#')
        }

        // Intermediate returned ?c=REVERSED path
        if (loc.contains("supjav.php")) {
            val followed = followProtectorRedirects(loc)
            if (!followed.isNullOrBlank()) return followed
            val next = fetchProtectorLocation(loc)
            if (!next.isNullOrBlank() && !next.contains("supjav.php") && next.startsWith("http")) {
                return next.substringBefore('#')
            }
        }

        val hostBase = pageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: PROTECTOR_URL
        val token = pageUrl.toHttpUrlOrNull()?.queryParameter("l")
            ?: pageUrl.toHttpUrlOrNull()?.queryParameter("c")
            ?: Regex("""[?&](?:c|l)=([^&#]+)""").find(pageUrl)?.groupValues?.get(1)
        if (!token.isNullOrBlank()) {
            val followed = followProtectorRedirects("$hostBase/supjav.php?c=${token.reversed()}")
            if (!followed.isNullOrBlank()) return followed
        }
        return null
    }

    private suspend fun fetchProtectorLocation(targetUrl: String): String? = runCatching {
        noRedirectClient.newCall(GET(targetUrl, protectorRequestHeaders())).await().use { resp ->
            val loc = (resp.header("location") ?: resp.header("Location"))?.trim()
            if (!loc.isNullOrEmpty()) {
                return@use resolveRelative(loc, targetUrl)
            }

            if (resp.isSuccessful) {
                val body = resp.bodyString()
                if (body.isNotBlank()) {
                    val olid = OLID_REGEX.find(body)?.groupValues?.get(1)
                    if (!olid.isNullOrBlank()) {
                        val hostBase = targetUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                            ?: PROTECTOR_URL
                        return@use "$hostBase/supjav.php?c=${olid.reversed()}"
                    }

                    val cQuery = Regex("""[?&]c=([a-fA-F0-9]{16,})""").find(body)?.groupValues?.get(1)
                    if (!cQuery.isNullOrBlank()) {
                        val hostBase = targetUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                            ?: PROTECTOR_URL
                        return@use "$hostBase/supjav.php?c=$cQuery"
                    }

                    val directHosterUrl = DIRECT_HOSTER_REGEX.find(body)?.value
                    if (!directHosterUrl.isNullOrEmpty()) {
                        return@use resolveRelative(directHosterUrl, targetUrl)
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
        // TV (TurboVid) frequently returns ad-only HLS (tiktok CDN). Keep as last resort only.
        val cleanUrl = url.substringBefore('#').ifBlank { url }
        val pageHeaders = buildMediaHeaders(cleanUrl)
        val body = runCatching {
            client.newCall(GET(cleanUrl, pageHeaders)).awaitSuccess().bodyString()
        }.getOrDefault("")
        if (body.isBlank()) return emptyList()

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

        val origin = cleanUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://turbovidhls.com"
        val hlsHeaders = Headers.Builder()
            .set("User-Agent", appUserAgent())
            .set("Accept", "*/*")
            .set("Origin", origin)
            .set("Referer", if (cleanUrl.endsWith("/")) cleanUrl else "$cleanUrl/")
            .build()

        // Probe playlist — must be a real playlist, drop ad-only junk
        val probe = runCatching {
            client.newCall(GET(playlistUrl, hlsHeaders)).execute().bodyString()
        }.getOrDefault("")
        if (!probe.contains("#EXTM3U") ||
            probe.contains("tiktokcdn", ignoreCase = true) ||
            probe.contains("ad-site", ignoreCase = true)
        ) {
            return emptyList()
        }

        // If master points to another playlist, probe that too
        val child = M3U8_REGEX.find(probe)?.value
        if (!child.isNullOrBlank()) {
            val childBody = runCatching {
                client.newCall(GET(child, hlsHeaders)).execute().bodyString()
            }.getOrDefault("")
            if (childBody.contains("tiktokcdn", ignoreCase = true) ||
                childBody.contains("ad-site", ignoreCase = true)
            ) {
                return emptyList()
            }
        }

        val fromPlaylist = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl,
                referer = cleanUrl,
                masterHeaders = hlsHeaders,
                videoHeaders = hlsHeaders,
                videoNameGen = { "TV - $it" },
            ).distinctBy { it.videoUrl }
        }.getOrDefault(emptyList())

        return fromPlaylist.filter { v ->
            val u = v.videoUrl.orEmpty()
            !u.contains("tiktokcdn", ignoreCase = true) && !u.contains("ad-site", ignoreCase = true)
        }
    }

    /**
     * StreamTape modern page:
     * ```
     * robotlink.innerHTML = '//streamtape.com/get_video?i' + ('xcdd=ID&...').substring(2).substring(1);
     * ```
     * → https://streamtape.com/get_video?id=ID&...&stream=1  → 302 → tapecontent.net MP4
     */
    private fun videosFromStreamTape(url: String): List<Video> {
        val clean = url.substringBefore('#').ifBlank { url }
        val id = Regex("""streamtape\.com/(?:e|v)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)
            ?: clean.toHttpUrlOrNull()?.pathSegments?.firstOrNull { it.length in 8..20 && !it.contains('.') }
        val embed = if (!id.isNullOrBlank()) "https://streamtape.com/e/$id/" else clean
        val stHeaders = Headers.Builder()
            .set("User-Agent", appUserAgent())
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Referer", "https://streamtape.com/")
            .set("Origin", "https://streamtape.com")
            .build()

        return runCatching {
            val body = client.newCall(GET(embed, stHeaders)).execute().bodyString()
            val playUrl = parseStreamTapePlayUrl(body) ?: return@runCatching emptyList()

            val playHeaders = Headers.Builder()
                .set("User-Agent", appUserAgent())
                .set("Accept", "*/*")
                .set("Referer", "https://streamtape.com/")
                .set("Origin", "https://streamtape.com")
                .build()

            // Prefer final CDN MP4 (more reliable in ExoPlayer than intermediate get_video)
            val finalUrl = runCatching {
                noRedirectClient.newCall(GET(playUrl, playHeaders)).execute().use { resp ->
                    val loc = resp.header("Location") ?: resp.header("location")
                    if (!loc.isNullOrBlank() && loc.contains("http")) {
                        resolveRelative(loc, playUrl)
                    } else {
                        null
                    }
                }
            }.getOrNull()

            val stream = finalUrl?.takeIf { it.startsWith("http") } ?: playUrl
            listOf(Video(stream, "ST", stream, headers = playHeaders))
        }.getOrDefault(emptyList())
    }

    private fun parseStreamTapePlayUrl(body: String): String? {
        // Pattern A (current): prefix + ('xcd...').substring(n).substring(m)
        // The page plants several DECOY assignments (e.g. producing
        // "get_video?idb=..."). A valid play URL always has "get_video?id=",
        // so iterate all candidates and keep the first valid one.
        val patternA = Regex(
            """(?:robotlink|botlink|ideoolink)[^;]{0,80}innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(['"]([^'"]+)['"]\)""" +
                """(?:\.substring\((\d+)\))?(?:\.substring\((\d+)\))?""",
            RegexOption.IGNORE_CASE,
        )

        for (modern in patternA.findAll(body)) {
            var suffix = modern.groupValues[2]
            val s1 = modern.groupValues[3].toIntOrNull()
            val s2 = modern.groupValues[4].toIntOrNull()
            if (s1 != null && s1 in 0 until suffix.length) suffix = suffix.substring(s1)
            if (s2 != null && s2 in 0 until suffix.length) suffix = suffix.substring(s2)
            // Legacy: substringAfter "+ ('xcd" style (old extractor)
            if (suffix.startsWith("xcd", ignoreCase = true) && s1 == null) {
                suffix = suffix.removePrefix("xcd").removePrefix("XCD")
            }
            var path = modern.groupValues[1] + suffix
            path = path.replace(" ", "")
            val url = when {
                path.startsWith("http") -> path
                path.startsWith("//") -> "https:$path"
                path.startsWith("/") -> "https://streamtape.com$path"
                else -> "https://$path"
            }
            if (!url.contains("get_video?id=", ignoreCase = true)) continue // decoy
            return if (url.contains("stream=")) url else "$url&stream=1"
        }

        // Pattern B: static get_video in hidden div
        val static = Regex(
            """(?:robotlink|botlink|ideoolink)[^>]*>\s*(//?[^<]*get_video[^<]+)""",
            RegexOption.IGNORE_CASE,
        ).find(body)?.groupValues?.get(1)?.trim()
            ?: Regex("""//streamtape\.com/get_video\?[^"'<\s]+""", RegexOption.IGNORE_CASE)
                .find(body)?.value

        if (!static.isNullOrBlank()) {
            val url = when {
                static.startsWith("http") -> static
                static.startsWith("//") -> "https:$static"
                static.startsWith("/") -> "https://streamtape.com$static"
                else -> "https://$static"
            }.replace(" ", "")
            return if (url.contains("stream=")) url else "$url&stream=1"
        }

        // Pattern C: plain get_video?id=
        val plain = Regex(
            """get_video\?id=[A-Za-z0-9]+[^"'<\s]*""",
            RegexOption.IGNORE_CASE,
        ).find(body)?.value ?: return null
        val url = "https://streamtape.com/$plain".replace(" ", "")
        return if (url.contains("stream=")) url else "$url&stream=1"
    }

    private fun videosFromVoe(url: String, mediaHeaders: Headers): List<Video> {
        val clean = url.substringBefore('#').ifBlank { url }
        // Follow intermediate voe.sx → mirror
        val pageBody = runCatching {
            client.newCall(GET(clean, mediaHeaders)).execute().bodyString()
        }.getOrDefault("")
        val redirect = Regex(
            """window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""",
            RegexOption.IGNORE_CASE,
        ).find(pageBody)?.groupValues?.get(1)
        val target = redirect?.takeIf { it.startsWith("http") } ?: clean
        val headersForTarget = buildMediaHeaders(target)

        val fromLib = runCatching {
            VoeExtractor(client, headersForTarget).videosFromUrl(target, prefix = "VOE ")
        }.getOrDefault(emptyList())
        if (fromLib.isNotEmpty()) return fromLib

        // Fallback: m3u8 / mp4 in page after redirect
        val body2 = if (target == clean) {
            pageBody
        } else {
            runCatching {
                client.newCall(GET(target, headersForTarget)).execute().bodyString()
            }.getOrDefault("")
        }
        val m3u8 = M3U8_REGEX.find(body2)?.value
        if (!m3u8.isNullOrBlank()) {
            return runCatching {
                playlistUtils.extractFromHls(
                    m3u8,
                    referer = target,
                    masterHeaders = headersForTarget,
                    videoHeaders = headersForTarget,
                    videoNameGen = { "VOE - $it" },
                )
            }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    private suspend fun videosFromStreamWishLike(url: String, prefix: String): List<Video> {
        val clean = url.substringBefore('#').ifBlank { url }
        // FST (fc2stream / streamhg) rejects embeds without Supjav-like referer
        val pageHeaders = Headers.Builder()
            .set("User-Agent", appUserAgent())
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.9")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        val body = runCatching {
            client.newCall(GET(clean, pageHeaders)).awaitSuccess().bodyString()
        }.getOrDefault("")
        if (body.isBlank()) return emptyList()
        if (body.contains("restricted for this domain", ignoreCase = true) &&
            !body.contains("eval(function")
        ) {
            // Retry with player page as referer
            val retry = runCatching {
                client.newCall(GET(clean, buildMediaHeaders(clean))).awaitSuccess().bodyString()
            }.getOrDefault("")
            if (retry.isNotBlank()) {
                return extractFstFromHtml(retry, clean, prefix)
            }
        }

        val fromExtractor = runCatching {
            StreamWishExtractor(client, pageHeaders).videosFromUrl(clean) { "$prefix - $it" }
        }.getOrDefault(emptyList())
        if (fromExtractor.isNotEmpty()) return fromExtractor

        return extractFstFromHtml(body, clean, prefix)
    }

    private fun extractFstFromHtml(body: String, pageUrl: String, prefix: String): List<Video> {
        if (body.contains("restricted for this domain", ignoreCase = true) &&
            !body.contains("eval(function")
        ) {
            return emptyList()
        }

        val unpacked = buildString {
            append(body)
            if (body.contains("eval(function")) {
                PACKED_REGEX.findAll(body).forEach { m ->
                    runCatching { JsUnpacker.unpackAndCombine(m.value) }.getOrNull()?.let {
                        append('\n')
                        append(it)
                    }
                }
            }
        }

        // Prefer hls2/hls3/hls4 style sources from unpacked JWPlayer config
        val candidates = linkedSetOf<String>()
        M3U8_REGEX.findAll(unpacked).map { it.value }.forEach { candidates.add(it) }
        Regex(
            """https?://[^"'\s\\]+/(?:hls[0-9]?|stream)/[^"'\s\\]+""",
            RegexOption.IGNORE_CASE,
        ).findAll(unpacked).map { it.value }.forEach { candidates.add(it) }
        Regex(
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(unpacked).map { it.groupValues[1] }.forEach { candidates.add(it) }
        // Relative /stream/hls...
        Regex("""["'](/(?:stream|hls)[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { resolveRelative(it.groupValues[1], pageUrl) }
            .forEach { candidates.add(it) }

        val hlsHeaders = Headers.Builder()
            .set("User-Agent", appUserAgent())
            .set("Accept", "*/*")
            .set("Referer", if (pageUrl.endsWith("/")) pageUrl else "$pageUrl/")
            .set(
                "Origin",
                pageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: baseUrl,
            )
            .build()

        val videos = mutableListOf<Video>()
        for (master in candidates) {
            if (!master.contains("m3u8", ignoreCase = true) && !master.contains("/hls")) continue
            val abs = resolveRelative(master, pageUrl)
            // Skip dead playlists: some CDN mirrors 403 (e.g. premilkyway),
            // and extractFromHls would emit the dead URL as a fake video
            // before the working mirror (master.txt /hls3/) is ever tried.
            val playlistOk = runCatching {
                client.newCall(GET(abs, hlsHeaders)).execute().bodyString().contains("#EXTM3U")
            }.getOrDefault(false)
            if (!playlistOk) continue
            val extracted = runCatching {
                playlistUtils.extractFromHls(
                    abs,
                    referer = pageUrl,
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
            compareBy<Video> { video ->
                // Prefer working hosters first (ST / FST), demote TV (often ads)
                val q = video.quality.uppercase()
                when {
                    q.startsWith("ST") -> 0
                    q.startsWith("FST") -> 1
                    q.startsWith("VOE") -> 2
                    q.startsWith("TV") -> 9
                    else -> 5
                }
            }.thenByDescending { it.quality.contains(quality) },
        )
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

        /** e.g. class="btn-server" data-link="hex">TV  or data-link first */
        private val BTN_SERVER_REGEX by lazy {
            Regex(
                """class=["'][^"']*btn-server[^"']*["'][^>]*data-link=["']([a-fA-F0-9]{16,}|https?://[^"']+)["'][^>]*>([^<]{0,40})|""" +
                    """data-link=["']([a-fA-F0-9]{16,}|https?://[^"']+)["'][^>]*class=["'][^"']*btn-server[^"']*["'][^>]*>([^<]{0,40})""",
                RegexOption.IGNORE_CASE,
            )
        }
        private val HEX_DATA_LINK_REGEX by lazy {
            Regex(
                """data-link=["']([a-fA-F0-9]{32,})["'][^>]*>(\s*[A-Za-z0-9]{1,12}\s*)<""",
                RegexOption.IGNORE_CASE,
            )
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

        // Slugs verified against live site (main menu + post pages).
        private val CategoryList = listOf(
            "All" to "",
            "Censored" to "censored-jav",
            "Uncensored" to "uncensored-jav",
            "Amateur" to "amateur",
            "Reducing Mosaic" to "reducing-mosaic",
            "English Subtitles" to "english-subtitles",
            "Chinese Subtitles" to "chinese-subtitles",
        )

        // Slugs verified from live /tag/<slug> links on post pages.
        private val TagList = listOf(
            "All" to "",
            "Creampie" to "creampie",
            "Married Woman" to "married-woman",
            "Solowork" to "solowork",
            "Amateur" to "amateur",
            "Big Tits" to "big-tits",
            "POV" to "pov",
            "3P/4P" to "3p4p",
            "4K" to "4k",
            "Affair" to "affair",
            "Cuckold" to "cuckold",
            "Delusion" to "delusion",
            "JAVPlayer Decensored" to "javplayer-decensored",
            "FC2PPV" to "fc2ppv",
            "Slut" to "slut",
            "Mature Woman" to "mature-woman",
            "Beautiful Girl" to "beautiful-girl",
        )
    }
}
