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
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
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
                client.newCall(GET("$baseUrl/$id"))
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

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val body = response.bodyString()
        val doc = response.useAsJsoup()

        val players = doc.select("div.btnst > a").toList()
            .filter { it.text() in SUPPORTED_PLAYERS }
            .map { it.text() to it.attr("data-link").reversed() }

        if (players.isEmpty()) {
            if (body.contains("Just a moment", ignoreCase = true) ||
                body.contains("cf-browser-verification", ignoreCase = true) ||
                body.contains("challenge-platform", ignoreCase = true)
            ) {
                throw Exception("SupJav: Cloudflare is blocking the page. Open in WebView, solve the check, then retry.")
            }
            throw Exception("SupJav: no video servers found. Page had ${doc.select("div.btnst a").size} buttons, ${doc.select("a[data-link]").size} data-link elements. Site layout may have changed.")
        }

        val errors = mutableListOf<String>()
        val results = runBlocking {
            players.map { player ->
                async {
                    try {
                        val result = videosFromPlayer(player)
                        if (result.isEmpty()) errors.add("${player.first}: returned empty")
                        result
                    } catch (e: Exception) {
                        errors.add("${player.first}: ${e.message?.take(100) ?: e.javaClass.simpleName}")
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        val videos = results.flatten()

        if (videos.isEmpty()) {
            throw Exception("SupJav: all ${players.size} server(s) failed — ${errors.joinToString("; ")}")
        }
        return videos.distinctBy { it.videoUrl }
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
