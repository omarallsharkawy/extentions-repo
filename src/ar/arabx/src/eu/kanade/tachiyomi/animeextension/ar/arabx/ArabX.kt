package eu.kanade.tachiyomi.animeextension.ar.arabx

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ArabX :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "ArabX"

    override val baseUrl = "https://www.arabx.cam"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(paginatedPath("most-popular", page), headers)

    override fun popularAnimeSelector(): String = "div.list-videos div.item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")
        setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
        title = link?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("strong.title")?.text().orEmpty()
        thumbnail_url = element.selectFirst("img.thumb, img")?.let { img ->
            img.attr("abs:data-original").ifBlank { null }
                ?: img.attr("abs:data-src").ifBlank { null }
                ?: img.attr("abs:src").takeUnless { it.startsWith("data:") }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination li.next a, div.pagination li.page a"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(paginatedPath("latest-updates", page), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val section = filters.firstInstanceOrNull<SectionFilter>()?.selected.orEmpty()
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()
        val tag = filters.firstInstanceOrNull<TagFilter>()?.state?.trim().orEmpty()
        val model = filters.firstInstanceOrNull<ModelFilter>()?.state?.trim().orEmpty()

        val term = query.trim()

        // Text search: path page 1, KVS async block for further pages
        if (term.isNotEmpty()) {
            val slug = encodePathSegment(term)
            if (page <= 1) {
                return GET("$baseUrl/search/$slug/", headers)
            }
            val url = "$baseUrl/search/$slug/".toHttpUrl().newBuilder()
                .addQueryParameter("mode", "async")
                .addQueryParameter("function", "get_block")
                .addQueryParameter("block_id", "list_videos_videos_list_search_result")
                .addQueryParameter("q", term)
                .addQueryParameter("from_videos", page.toString())
                .addQueryParameter("from_albums", page.toString())
                .build()
            return GET(url, headers)
        }

        // Tag / model / category path filters (empty query)
        when {
            tag.isNotEmpty() -> {
                val slug = encodePathSegment(tag)
                return GET(paginatedPath("tags/$slug", page), headers)
            }
            model.isNotEmpty() -> {
                val slug = encodePathSegment(model)
                return GET(paginatedPath("models/$slug", page), headers)
            }
            category.isNotEmpty() -> {
                return GET(paginatedPath("categories/$category", page), headers)
            }
        }

        // Browse sections (Latest / Popular / Top rated) + optional KVS sort_by
        val path = when (section) {
            "top-rated" -> "top-rated"
            "latest-updates" -> "latest-updates"
            else -> "most-popular"
        }
        val base = paginatedPath(path, page)
        if (sort.isNotEmpty()) {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("sort_by", sort)
                .build()
            return GET(url, headers)
        }
        return GET(base, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        // Async search fragments may omit pagination chrome; treat a full page as hasNext.
        val animes = document.select(searchAnimeSelector())
            .map { searchAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
        val hasNext = when {
            document.selectFirst(searchAnimeNextPageSelector()) != null -> true
            response.request.url.queryParameter("mode") == "async" -> animes.isNotEmpty()
            else -> false
        }
        return AnimesPage(animes, hasNext && animes.isNotEmpty())
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank {
                document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            }
        description = document.selectFirst("div.block-details div.info div.item em, div.info em")
            ?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        genre = document.select(
            "div.block-details a[href*=/tags/], div.block-details a[href*=/categories/], " +
                "div.info a[href*=/tags/], a.tag, div.tags a",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }
        author = document.select(
            "div.block-details a[href*=/models/], a[href*=/models/]",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        },
    )

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        // Prefer external tube host iframe (playeriz / playiri xupload JWPlayer)
        val iframeSrcs = document.select("div.player iframe[src], div.embed-wrap iframe[src], iframe[src]")
            .map { it.attr("abs:src") }
            .filter { it.isNotBlank() }
            .distinct()

        for (iframe in iframeSrcs) {
            videos += videosFromHoster(iframe, pageUrl)
        }

        // Fallbacks on the watch page itself
        if (videos.isEmpty()) {
            val html = document.html()
            videos += extractDirectStreams(html, pageUrl, labelPrefix = "Direct")
        }

        return videos.distinctBy { it.url }
    }

    private fun videosFromHoster(embedUrl: String, pageUrl: String): List<Video> {
        val embedHeaders = headersBuilder()
            .set("Referer", pageUrl)
            .set("Origin", baseUrl)
            .build()
        val html = client.newCall(GET(embedUrl, embedHeaders)).execute().use { it.body.string() }

        // Dean Edwards packed JWPlayer config (playeriz/playiri)
        val unpacked = JsUnpacker.unpackAndCombine(html).orEmpty().ifBlank {
            // Sometimes the packer is only in a script body
            documentScripts(html)
                .mapNotNull { JsUnpacker.unpackAndCombine(it) }
                .firstOrNull()
                .orEmpty()
        }

        val combined = "$html\n$unpacked"
        val streams = extractDirectStreams(combined, embedUrl, labelPrefix = "Playeriz")
        if (streams.isNotEmpty()) return streams

        // Nested iframe redirect
        val nested = IFRAME_SRC_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        return nested.flatMap { videosFromHoster(it, embedUrl) }
    }

    private fun extractDirectStreams(html: String, referer: String, labelPrefix: String): List<Video> {
        val results = mutableListOf<Video>()
        val streamHeaders = headersBuilder()
            .set("Referer", referer)
            .set(
                "Origin",
                referer.toHttpUrl().let { "${it.scheme}://${it.host}" },
            )
            .build()

        val urls = linkedSetOf<String>()
        // JW file:"https://...m3u8" / file:'...'
        FILE_URL_REGEX.findAll(html).forEach { urls += it.groupValues[1] }
        // Bare m3u8 / mp4
        STREAM_URL_REGEX.findAll(html).forEach { urls += it.groupValues[1] }

        for (url in urls) {
            when {
                url.contains(".m3u8", ignoreCase = true) -> {
                    results += playlistUtils.extractFromHls(
                        url,
                        referer = referer,
                        videoNameGen = { q -> "$labelPrefix - $q" },
                    )
                }
                url.contains(".mp4", ignoreCase = true) -> {
                    results += Video(url, "$labelPrefix - MP4", url, streamHeaders)
                }
            }
        }
        return results
    }

    private fun documentScripts(html: String): List<String> = SCRIPT_REGEX.findAll(html).map { it.groupValues[1] }.toList()

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video> { !it.quality.contains(quality, ignoreCase = true) }
                .thenByDescending { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // =============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores section/category filters"),
        AnimeFilter.Header("Empty query: Tag → Model → Category → Section"),
        SectionFilter(),
        SortFilter(),
        AnimeFilter.Separator(),
        CategoryFilter(),
        TagFilter(),
        ModelFilter(),
    )

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs[state].second
    }

    /** Browse path when search query is empty. */
    private class SectionFilter :
        UriSelectFilter(
            "Section",
            arrayOf(
                "Most popular" to "most-popular",
                "Latest updates" to "latest-updates",
                "Top rated" to "top-rated",
            ),
        )

    /**
     * KVS `sort_by` query (works on popular / latest / category blocks).
     * Site chips: video_viewed, video_viewed_month/week/today, rating, duration, …
     */
    private class SortFilter :
        UriSelectFilter(
            "Sort by",
            arrayOf(
                "Default (section order)" to "",
                "Most viewed (all time)" to "video_viewed",
                "Most viewed (month)" to "video_viewed_month",
                "Most viewed (week)" to "video_viewed_week",
                "Most viewed (today)" to "video_viewed_today",
                "Top rated" to "rating",
                "Longest" to "duration",
                "Most commented" to "most_commented",
                "Most favourited" to "most_favourited",
                "Newest" to "post_date",
            ),
        )

    private class CategoryFilter : UriSelectFilter("Category", CATEGORIES)

    private class TagFilter : AnimeFilter.Text("Tag (Arabic slug or name)")

    private class ModelFilter : AnimeFilter.Text("Model (slug, e.g. kira-queen)")

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // =============================== Helpers ==============================

    /** Path pagination: `/latest-updates/`, `/latest-updates/2/`, … */
    private fun paginatedPath(path: String, page: Int): String {
        val clean = path.trim('/')
        return if (page <= 1) {
            "$baseUrl/$clean/"
        } else {
            "$baseUrl/$clean/$page/"
        }
    }

    /**
     * Encode a free-text filter into a KVS path segment.
     * Spaces → hyphens (site convention); keep Unicode letters.
     */
    private fun encodePathSegment(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("""\s+"""), "-")
            .trim('-')
        // URLEncoder encodes Arabic to %XX which the site accepts
        return URLEncoder.encode(cleaned, Charsets.UTF_8.name())
            .replace("+", "%20")
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    companion object {
        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val FILE_URL_REGEX = Regex(
            """file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val STREAM_URL_REGEX = Regex(
            """(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""",
            RegexOption.IGNORE_CASE,
        )
        private val IFRAME_SRC_REGEX = Regex(
            """<iframe[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val SCRIPT_REGEX = Regex(
            """<script[^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080", "720", "480", "360", "240", "HLS", "MP4")
        private const val PREF_QUALITY_DEFAULT = "720"

        // Categories from /categories/ (slugs as used by the site)
        private val CATEGORIES = arrayOf(
            "Any" to "",
            "سكس مترجم" to "سكس-مترجم",
            "افلام سكس مترجمه" to "افلام-سكس-مترجمه",
            "سكس بزاز كبيرة" to "سكس-بزاز-كبيرة",
            "سكس محارم" to "سكس-محارم",
            "سكس امهات مترجم" to "سكس-امهات-مترجم",
            "سكس عائلي" to "سكس-عائلي",
            "سكس مراهقات" to "سكس-مراهقات",
            "سكس اخوات" to "سكس-اخوات",
            "سكس نيك الطيز" to "سكس-نيك-الطيز",
            "سكس" to "سكس",
            "سكس عربي" to "سكس-عربي",
            "سكس سحاق" to "سكس-سحاق",
            "سكس نيك سمراوات" to "سكس-نيك-سمراوات",
        )
    }
}
