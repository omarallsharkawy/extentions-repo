package eu.kanade.tachiyomi.animeextension.ar.arabxn

import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class ArabXN :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "ArabXN"

    override val baseUrl = "https://www.arabxn.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    // Catalog: /video/ (all videos). Page 2+ → /video/{n}
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/video/" else "$baseUrl/video/$page"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = "article.rowvideo"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href*=/watch-]")
            ?: element.selectFirst("a[href]")
        val href = link?.attr("abs:href").orEmpty()
        setUrlWithoutDomain(href)
        val rawTitle = link?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("strong.title, .row_titleVideo .title, .title")?.text()?.trim()
            ?: link?.text()?.trim().orEmpty()
        val duration = element.selectFirst(
            "div.timevideo1, div.timevideo, div.duration, span.duration, span.time, span.clock, time",
        )?.text()?.trim()?.ifBlank { null }
        title = if (!duration.isNullOrEmpty() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
    }

    override fun popularAnimeNextPageSelector(): String = "link[rel=next], div.pagination span.current-page ~ a[href], div.pagination .current ~ a[href], div.pagination a.next, a[rel=next]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.contains("/watch-") && it.title.isNotBlank() }
            .distinctBy { it.url }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================
    // Homepage مترجم feed: /  and /page/{n}
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    /**
     * Text search: /search/{query}  and /search/{query}/{page}
     * Tag browse:  /tag/{slug}     and /tag/{slug}/page/{page}
     * Channel:     /channel/{slug}/ and /channel/{slug}/page/{page}
     * Pornstar:    /pornstar/{slug}
     * Category:    path from filter (mixed /tag/… and section roots)
     */
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()
        val channel = filters.firstInstanceOrNull<ChannelFilter>()?.state?.trim().orEmpty()
        val tag = filters.firstInstanceOrNull<TagFilter>()?.state?.trim().orEmpty()
        val pornstar = filters.firstInstanceOrNull<PornstarFilter>()?.state?.trim().orEmpty()

        val fullQuery = buildString {
            append(query.trim())
            if (channel.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(channel)
            }
            if (pornstar.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(pornstar)
            }
            if (tag.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(tag)
            }
        }.trim()

        val path = when {
            fullQuery.isNotBlank() -> searchPath(fullQuery, page)
            channel.isNotBlank() -> channelPath(channel, page)
            pornstar.isNotBlank() -> pornstarPath(pornstar, page)
            tag.isNotBlank() -> tagPath(tag, page)
            category.isNotBlank() -> categoryPath(category, page)
            else -> return popularAnimeRequest(page)
        }
        return GET("$baseUrl$path", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("div.video-details h1, h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()

        author = document.select("a.taga[href*=/pornstar/]")
            .mapNotNull { it.attr("title").ifBlank { it.text() }.trim().takeIf(String::isNotEmpty) }
            .map { it.replace(Regex("""\s*ممتل.*$"""), "").trim() }
            .distinct()
            .joinToString()
            .ifBlank { null }

        genre = document.select("a.taga[href*=/tag/]")
            .mapNotNull { it.attr("title").ifBlank { it.text() }.trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }

        val views = document.selectFirst(".watch-views-value-full")?.text()?.trim()
        val age = document.select("div.video-details p")
            .map { it.text().trim() }
            .firstOrNull { it.startsWith("منذ") || it.contains("ساعة") || it.contains("يوم") }
        description = buildString {
            views?.let { append("المشاهدات: $it\n") }
            age?.let { append(it).append('\n') }
            document.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { append(it) }
        }.trim().ifBlank { null }

        thumbnail_url = fixUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("video#my-video")?.attr("poster")
                ?: document.selectFirst("video[poster]")?.attr("abs:poster"),
        )

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val dateUpload = parseReleaseDate(
            document.selectFirst("meta[property=og:video:release_date]")?.attr("content"),
        )
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                setUrlWithoutDomain(response.request.url.toString())
                episode_number = 1f
                date_upload = dateUpload
            },
        )
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    /**
     * Player embeds multi-quality MP4 on arabxn.xyz:
     *   <source src="…/240p/….mp4" title="240p">
     *   <source src="…/360p/….mp4" title="360p">
     *   <source src="….mp4" title="HD 720p">  (full quality, no quality folder)
     */
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videoHeaders = headersBuilder()
            .set("Referer", pageUrl)
            .build()

        val fromSources = document.select("video#my-video source[src], video source[src]")
            .mapNotNull { source ->
                val url = source.attr("abs:src").ifBlank { source.attr("src") }
                if (url.isBlank() || !url.contains(".mp4", ignoreCase = true)) return@mapNotNull null
                val label = source.attr("title").ifBlank { qualityFromUrl(url) }
                Video(url, label, url, headers = videoHeaders)
            }
            .distinctBy { it.url }

        if (fromSources.isNotEmpty()) return fromSources

        // Fallback: scan page HTML for arabxn.xyz MP4 URLs
        val html = document.html()
        return MP4_URL_REGEX.findAll(html)
            .map { it.value }
            .distinct()
            .map { url ->
                Video(url, qualityFromUrl(url), url, headers = videoHeaders)
            }
            .toList()
    }

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
        AnimeFilter.Header("اترك البحث فارغاً واستخدم الفلاتر"),
        AnimeFilter.Header("الأولوية: نص البحث → قناة → ممثل → تاج → تصنيف"),
        AnimeFilter.Separator(),
        CategoryFilter(),
        ChannelFilter(),
        TagFilter(),
        PornstarFilter(),
    )

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs[state].second
    }

    private class CategoryFilter : UriSelectFilter("التصنيف / Category", CATEGORIES)

    private class ChannelFilter : AnimeFilter.Text("قناة / Channel (slug, e.g. brazzers)")

    private class TagFilter : AnimeFilter.Text("تاج / Tag (e.g. milf, سكس-مترجم)")

    private class PornstarFilter : AnimeFilter.Text("ممثل / Pornstar (slug, e.g. abi-james)")

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
    private fun searchPath(query: String, page: Int): String {
        val slug = encodePathSegment(query)
        return if (page <= 1) "/search/$slug" else "/search/$slug/$page"
    }

    private fun tagPath(raw: String, page: Int): String {
        val slug = normalizeSlug(raw)
        return if (page <= 1) "/tag/$slug" else "/tag/$slug/page/$page"
    }

    private fun channelPath(raw: String, page: Int): String {
        val slug = normalizeSlug(raw.removePrefix("channel/").removePrefix("/"))
        return if (page <= 1) "/channel/$slug/" else "/channel/$slug/page/$page"
    }

    private fun pornstarPath(raw: String, page: Int): String {
        val slug = normalizeSlug(raw.removePrefix("pornstar/").removePrefix("/"))
        // Site pornstar pages rarely paginate; keep single path
        return if (page <= 1) "/pornstar/$slug" else "/pornstar/$slug/page/$page"
    }

    /**
     * Category values are full path suffixes without host, e.g. `/video/`, `/سكس-عربي/`, `/tag/سكس-مصري`.
     * Pagination: homepage → `/page/N`; tags → `…/page/N`; section roots → `…/N`.
     */
    private fun categoryPath(path: String, page: Int): String {
        val clean = path.trim().let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')
        // Homepage "/"
        if (clean.isEmpty()) {
            return if (page <= 1) "/" else "/page/$page"
        }
        if (page <= 1) {
            // Keep trailing slash for section roots (site uses both; trailing is safer)
            return if (clean.startsWith("/tag/")) clean else "$clean/"
        }
        // tag paths use /page/N
        if (clean.startsWith("/tag/")) {
            return "$clean/page/$page"
        }
        // video catalog uses /video/{n}
        if (clean == "/video") {
            return "/video/$page"
        }
        // section roots like /سكس-عربي or /xnxx use /{n}
        return "$clean/$page"
    }

    private fun encodePathSegment(raw: String): String {
        // Keep Arabic + alnum; spaces → -, encode the rest for path safety
        val cleaned = raw.trim().replace(Regex("""\s+"""), "-")
        return URLEncoder.encode(cleaned, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%2D", "-")
            .replace("%5F", "_")
    }

    private fun normalizeSlug(raw: String): String = raw.trim()
        .replace(Regex("""\s+"""), "-")
        .trim('/')
        .let { encodePathSegment(it) }

    private fun qualityFromUrl(url: String): String {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            "/240p/" in lower -> "240p"
            "/360p/" in lower -> "360p"
            "/480p/" in lower -> "480p"
            "/720p/" in lower -> "720p"
            "/1080p/" in lower -> "1080p"
            else -> "Full HD"
        }
    }

    private fun parseReleaseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            DATE_FORMAT.parse(raw.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    private fun Element.getImageUrl(): String? {
        val src = when {
            hasAttr("data-original") -> attr("data-original")
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ").substringBefore(",")
            hasAttr("poster") -> attr("poster")
            else -> attr("src")
        }.ifBlank { attr("src") }.trim()

        if (src.isBlank() || src.startsWith("data:")) return null
        val resolved = if (src.startsWith("//")) {
            "https:$src"
        } else if (src.startsWith("http://") || src.startsWith("https://")) {
            src
        } else {
            absUrl("data-original").ifBlank { absUrl("data-src").ifBlank { absUrl("src") } }
        }
        return resolved.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> trimmed
        }.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    companion object {
        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val MP4_URL_REGEX = Regex(
            """https?://(?:arabxn\.xyz|www\.arabxn\.com)[^"'<\s]+\.mp4""",
            RegexOption.IGNORE_CASE,
        )
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "الجودة المفضلة / Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("720", "360", "240", "Full", "HD")
        private const val PREF_QUALITY_DEFAULT = "720"

        // From site nav + /التصنيفات/
        private val CATEGORIES = arrayOf(
            "أي / Any" to "",
            "كل الفيديوهات / All videos" to "/video/",
            "سكس مترجم (الرئيسية)" to "/",
            "سكس" to "/السكس",
            "سكس عربي" to "/سكس-عربي/",
            "سكس اخوات" to "/سكس-اخوات/",
            "سكس أمهات" to "/سكس-امهات/",
            "سكس محارم" to "/سكس-محارم/",
            "سكس مصري" to "/tag/سكس-مصري",
            "سكس طيز" to "/tag/سكس-طيز",
            "xnxx" to "/xnxx/",
        )
    }
}
