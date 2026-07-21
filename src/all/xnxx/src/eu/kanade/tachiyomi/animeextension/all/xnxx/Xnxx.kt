package eu.kanade.tachiyomi.animeextension.all.xnxx

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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Xnxx :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Xnxx"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val baseUrl by lazy {
        val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
        if (stored != null && stored in PREF_DOMAIN_VALUES) {
            stored
        } else {
            preferences.edit().putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).apply()
            PREF_DOMAIN_DEFAULT
        }
    }

    private val player by lazy { AdultHtml5Player(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    // ============================== Popular ===============================

    /**
     * Best-of for the previous calendar month (current month may be sparse early on).
     * Pagination is 0-based: page 1 → `/best/yyyy-MM/0`, page 2 → `/1`, …
     */
    override fun popularAnimeRequest(page: Int): Request {
        val month = bestMonthPath()
        return GET("$baseUrl/best/$month/${(page - 1).coerceAtLeast(0)}", headers)
    }

    // Redesign: `thumb-block video` / search `thumb-block`; exclude category stubs
    override fun popularAnimeSelector(): String = "div.thumb-block:not(.thumb-cat):not(.thumb-block-profile)"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val href = element.selectFirst(
            "a.thumb-link, a.title, div.thumb a, div.thumb-under a[href*=/video]",
        )?.attr("href").orEmpty()
        anime.setUrlWithoutDomain(absUrl(href))
        anime.title = element.selectFirst("a.title")?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("div.thumb-under a[title], p a[title]")?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("a.title, div.thumb-under a, p a[href*=/video]")?.text().orEmpty()
                .replace(Regex("""\s+\d+\s*min\s*$"""), "")
                .trim()
        val img = element.selectFirst("div.thumb img, img")
        val rawThumb = img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("data-lazy-src")?.ifBlank { null }
            ?: img?.attr("poster")?.ifBlank { null }
            ?: img?.attr("src")
        anime.thumbnail_url = rawThumb?.let { absUrl(it) }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.next, a.dir.next, a.next-page, " +
        "div.pagination a.no-page.next, div.pagination .current + a, div.pagination li.current + li a, " +
        "a.pagination-next, a.page-next, .pagination a[rel=next], a[rel=next]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================

    /**
     * Real newest-style listing on free XNXX: `/todays-selection` (curated daily).
     * Further pages walk day archives: `/todays-selection/yyyy-MM-dd`.
     * (`/new/` is 404; homepage listings are client-rendered stubs without age gate.)
     */
    override fun latestUpdatesRequest(page: Int): Request = if (page <= 1) {
        GET("$baseUrl/todays-selection", headers)
    } else {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -(page - 1))
        }
        val date = DAY_FORMAT.format(cal.time)
        GET("$baseUrl/todays-selection/$date", headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(latestUpdatesSelector()).map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        // Day archives: keep paging while the day returned videos (gaps rare)
        return AnimesPage(animes, hasNextPage = animes.isNotEmpty())
    }

    // =============================== Search ===============================

    /**
     * Path-based search (not XVideos query params):
     * `/search/{sort?}/{period?}/{duration?}/{quality?}/{query}/{page}`
     *
     * Site UI sorts (probed): Default (relevance), Hits (views), Random.
     * Old `/search/date|rating|length/...` paths 301 → default search.
     *
     * Empty query + modifiers still builds a filtered URL. Without a term the site
     * treats the last path segment as the keyword (`/search/hits/0` → search "hits"),
     * so we use a broad placeholder when only Sort/Period/Duration/Quality are set.
     * Tags: `/tags/{slug}` 301 → `/search/{slug}` — use search path directly.
     */
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
        val period = filters.firstInstanceOrNull<PeriodFilter>()?.selected.orEmpty()
        val duration = filters.firstInstanceOrNull<DurationFilter>()?.selected.orEmpty()
        val quality = filters.firstInstanceOrNull<QualityFilter>()?.selected.orEmpty()
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()
        val tag = filters.firstInstanceOrNull<TagFilter>()?.state?.trim().orEmpty()

        val pageIndex = (page - 1).coerceAtLeast(0)
        val hasModifiers = sort.isNotBlank() || period.isNotBlank() ||
            duration.isNotBlank() || quality.isNotBlank()

        // Priority: text query → Tag → Category → broad placeholder if only modifiers
        val term = when {
            query.isNotBlank() -> query.trim()
            tag.isNotBlank() -> tag
            category.isNotBlank() -> category
            hasModifiers -> FILTER_ONLY_TERM
            else -> null
        }

        // Only fall back to Popular when Search has neither text nor any filter
        if (term == null) {
            return popularAnimeRequest(page)
        }

        val slug = encodeSearchTerm(term)
        // Site chip order (probed, no 301 strip): sort → period → duration → quality → term
        val modifiers = listOf(sort, period, duration, quality)
            .filter { it.isNotBlank() }
            .joinToString("/")
        val path = buildString {
            append("/search/")
            if (modifiers.isNotEmpty()) {
                append(modifiers)
                append("/")
            }
            append(slug)
            append("/")
            append(pageIndex)
        }
        return GET("$baseUrl$path", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst(
            "#video-content-metadata > div.clear-infobar strong, " +
                "#video-content-metadata strong, h1, h2.page-title",
        )?.text().orEmpty()
            .ifBlank {
                document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            }
        anime.author = document.selectFirst(
            "#video-content-metadata > div.clear-infobar span a, " +
                "#video-content-metadata a[href*=/porn-maker/], " +
                "#video-content-metadata a[href*=/profiles/]",
        )?.text()?.trim()
        anime.description = document.selectFirst("#video-content-metadata > p")?.text()
            ?.replace("\n", " ")
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        anime.genre = document.select(
            "#video-content-metadata > div.metadata-row.video-tags > a, " +
                "#video-content-metadata a[href*=/search/], " +
                "div.video-tags a, div.metadata-row a",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }
        anime.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }
            ?: document.selectFirst("#html5player img, #video-content-metadata img, div.thumb img, img")?.run {
                val raw = attr("data-src").ifBlank { attr("data-original") }.ifBlank { attr("src") }
                absUrl(raw)
            }
        anime.status = SAnime.COMPLETED
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        }
        return listOf(episode)
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        // Player no longer embeds setVideoUrl* in HTML; streams come from getvideo RPC
        return player.videosFromPage(pageUrl, document.html(), baseUrl)
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
        AnimeFilter.Header("— Search tab —"),
        AnimeFilter.Header("Sort / Period / Duration / Quality apply even with empty query"),
        AnimeFilter.Header("Term priority: typed query → Tag → Category"),
        AnimeFilter.Header("Tag/Category use /search/{slug} (site /tags/* redirects there)"),
        AnimeFilter.Header("Sorts: Relevance / Hits / Random only (date|rating|length 301-stripped)"),
        SortFilter(),
        PeriodFilter(),
        DurationFilter(),
        QualityFilter(),
        AnimeFilter.Separator(),
        CategoryFilter(),
        TagFilter(),
    )

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs[state].second
    }

    /** Site sort chips: Default (relevance), Hits (views), Random. */
    private class SortFilter :
        UriSelectFilter(
            "Sort",
            arrayOf(
                "Relevance (default)" to "",
                "Views (Hits)" to "hits",
                "Random" to "random",
            ),
        )

    /** Time window when sorting/searching (All / Month / Year). */
    private class PeriodFilter :
        UriSelectFilter(
            "Time period",
            arrayOf(
                "All time" to "",
                "This month" to "month",
                "This year" to "year",
            ),
        )

    private class DurationFilter :
        UriSelectFilter(
            "Duration",
            arrayOf(
                "Any duration" to "",
                "0–10 min" to "0-10min",
                "10–20 min" to "10-20min",
                "10+ min" to "10min+",
                "20+ min" to "20min+",
            ),
        )

    private class QualityFilter :
        UriSelectFilter(
            "Quality filter (search)",
            arrayOf(
                "Any" to "",
                "720p+" to "hd-only",
                "1080p+" to "fullhd",
            ),
        )

    private class CategoryFilter : UriSelectFilter("Category (tag search)", CATEGORIES)

    private class TagFilter : AnimeFilter.Text("Tag (e.g. amateur, japanese)")

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = PREF_DOMAIN_TITLE,
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
            restartRequired = true,
        )
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

    private fun absUrl(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.isBlank() -> href
        else -> "$baseUrl$href"
    }

    /**
     * Search path segment: spaces → `+` (site form uses `k=` then redirects to `/search/term`).
     * Underscores kept (e.g. `big_ass` is a recognized tag collection; hyphens often generic-search).
     * Duration segments keep literal `+` (`10min+`); do not percent-encode ( `%2B` strips filter).
     */
    private fun encodeSearchTerm(raw: String): String {
        val cleaned = raw.trim().replace(Regex("""\s+"""), "+")
        // Avoid path injection / empty segments
        return cleaned.trim('+').ifBlank { FILTER_ONLY_TERM }
    }

    private fun bestMonthPath(): String {
        // Prefer previous month: current month "best" is often incomplete early.
        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        return MONTH_FORMAT.format(cal.time)
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    companion object {
        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val MONTH_FORMAT = SimpleDateFormat("yyyy-MM", Locale.US)
        private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"

        // Free-site domains only. .es confirmed independent free mirror (same getvideo API).
        // Premium (xnxx.gold) and bare xnxx.com (301 → www) intentionally omitted.
        private val PREF_DOMAIN_ENTRIES = listOf(
            "www.xnxx.com",
            "www.xnxx.es",
        )
        private val PREF_DOMAIN_VALUES = PREF_DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_VALUES.first()

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080", "720", "480", "360", "240", "HLS", "MP4")
        private const val PREF_QUALITY_DEFAULT = "1080"

        /**
         * Placeholder term when only Sort/Period/Duration/Quality are set.
         * Required: without a keyword the last filter segment becomes the query
         * (e.g. `/search/hits/0` → title "'hits' Search").
         */
        private const val FILTER_ONLY_TERM = "a"

        // Common tags from site `/tags` (slugs as `/search/{slug}`; multi-word use `_`
        // which resolves to tagged collections, e.g. Big Ass videos)
        private val CATEGORIES = arrayOf(
            "Any" to "",
            "Amateur" to "amateur",
            "Anal" to "anal",
            "Arab" to "arab",
            "Asian" to "asian",
            "ASMR" to "asmr",
            "Ass" to "ass",
            "BBW" to "bbw",
            "BDSM" to "bdsm",
            "Big ass" to "big_ass",
            "Big cock" to "big_cock",
            "Big tits" to "big_tits",
            "Blonde" to "blonde",
            "Blowjob" to "blowjob",
            "Brunette" to "brunette",
            "Creampie" to "creampie",
            "Cuckold" to "cuckold",
            "Cumshot" to "cumshot",
            "Ebony" to "ebony",
            "Fetish" to "fetish",
            "Hardcore" to "hardcore",
            "Indian" to "indian",
            "Interracial" to "interracial",
            "Japanese" to "japanese",
            "Latina" to "latina",
            "Lesbian" to "lesbian",
            "Mature" to "mature",
            "MILF" to "milf",
            "Redhead" to "redhead",
            "Teen" to "teen",
            "Threesome" to "threesome",
        )
    }
}
