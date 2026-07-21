package eu.kanade.tachiyomi.animeextension.all.xvideos

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Xvideos :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Xvideos"

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
        .set("Accept-Language", "en-US,en;q=0.9")
        .set(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        )

    /** Search requests: same browser-like headers; Referer always site root (search form). */
    private fun searchHeaders() = headersBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    // ============================== Popular ===============================

    /**
     * Best-of for the previous calendar month (current month may be empty early on).
     * Pagination is 0-based: page 1 → `/best/yyyy-MM` (or `/0`), page 2 → `/1`, …
     */
    override fun popularAnimeRequest(page: Int): Request {
        val month = bestMonthPath()
        val path = if (page <= 1) {
            "/best/$month"
        } else {
            "/best/$month/${page - 1}"
        }
        return GET("$baseUrl$path", headers)
    }

    // Covers legacy `frame-block thumb-block` and redesign `thumb-block video`
    override fun popularAnimeSelector(): String = "div.thumb-block:not(.thumb-block-profile):not(.thumb-cat)"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val href = element.selectFirst("a.thumb-link, div.thumb a, div.title a, p.title a")
            ?.attr("href")
            .orEmpty()
        anime.setUrlWithoutDomain(absUrl(href))
        val rawTitle = element.selectFirst("div.title")?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("div.title a, p.title a")?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("div.title a, p.title a, p.title")?.text().orEmpty()
                .replace(Regex("""\s+\d+\s*min\s*$"""), "")
                .trim()
        val duration = element.selectFirst("span.duration, span.metadata, p.metadata, div.metadata")?.text()?.let { text ->
            Regex("""\b(\d+:\d+|\d+\s*min)\b""", RegexOption.IGNORE_CASE).find(text)?.value
        }?.trim().orEmpty()
        anime.title = if (duration.isNotBlank() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }
        val img = element.selectFirst("div.thumb img, img")
        val rawThumb = img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("data-lazy-src")?.ifBlank { null }
            ?: img?.attr("poster")?.ifBlank { null }
            ?: img?.attr("src")
        anime.thumbnail_url = rawThumb?.let { absUrl(it) }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.next-page:not(.disabled), a.no-page.next-page:not(.disabled), " +
        "div.pagination a.next:not(.disabled), a.dir.next:not(.disabled), div.pagination .current + a, " +
        "div.pagination li.current + li a, a.pagination-next:not(.disabled), a.page-next:not(.disabled), " +
        ".pagination a[rel=next]:not(.disabled), a[rel=next]:not(.disabled)"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================

    // Site uses 1-based pages: /new/1, /new/2, … (/new/0 → 404)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/new/${page.coerceAtLeast(1)}", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "relevance"
        val datef = filters.firstInstanceOrNull<DateFilter>()?.selected ?: "all"
        val durf = filters.firstInstanceOrNull<DurationFilter>()?.selected ?: "allduration"
        val quality = filters.firstInstanceOrNull<QualityFilter>()?.selected ?: "all"
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()
        val tag = filters.firstInstanceOrNull<TagFilter>()?.state?.trim().orEmpty()
        val brand = filters.firstInstanceOrNull<BrandFilter>()?.state?.trim().orEmpty()

        val pageIndex = (page - 1).coerceAtLeast(0)
        val hasQueryFilters = hasNonDefaultSearchFilters(sort, datef, durf, quality)

        return when {
            query.isNotBlank() -> {
                val combinedQuery = buildString {
                    append(query.trim())
                    if (category.isNotBlank()) {
                        val catName = category.substringBeforeLast("-").replace("_", " ")
                        append(" ").append(catName)
                    }
                    if (tag.isNotBlank()) {
                        append(" ").append(tag.trim())
                    }
                    if (brand.isNotBlank()) {
                        append(" ").append(brand.trim())
                    }
                }
                searchUrl(combinedQuery, pageIndex, sort, datef, durf, quality, typef = null)
            }

            brand.isNotBlank() -> {
                // Channel / brand search (site "Channels" tab → video results with data-is-channel)
                searchUrl(brand, pageIndex, sort, datef, durf, quality, typef = "channel")
            }

            category.isNotBlank() -> {
                // Category path: /c/{Name-id}[/pageIndex] — site ignores sort/datef/durf here
                val path = if (pageIndex == 0) {
                    "/c/$category"
                } else {
                    "/c/$category/$pageIndex"
                }
                GET("$baseUrl$path", searchHeaders())
            }

            tag.isNotBlank() -> {
                val slug = normalizeSlug(tag)
                val path = if (pageIndex == 0) {
                    "/tags/$slug"
                } else {
                    "/tags/$slug/$pageIndex"
                }
                GET("$baseUrl$path", searchHeaders())
            }

            // Empty text + only Sort/Date/Duration/Quality: site requires k= (no k → homepage;
            // empty/special k → 500). Verified broad keyword "a" applies all filters (~full page).
            hasQueryFilters -> {
                searchUrl(FILTER_ONLY_KEYWORD, pageIndex, sort, datef, durf, quality, typef = null)
            }

            else -> popularAnimeRequest(page)
        }
    }

    /**
     * XVideos search: `?k={term}&p={page}&sort=…&datef=…&durf=…&quality=…[&typef=channel]`
     *
     * Probe (www.xvideos.com): without `k`, sort/datef/durf are ignored (homepage).
     * `k=` / `k=*` / `k=.` / `k=%` → HTTP 500. `k=a` + filters → 200 + filtered results.
     */
    private fun searchUrl(
        keyword: String,
        pageIndex: Int,
        sort: String,
        datef: String,
        durf: String,
        quality: String,
        typef: String?,
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("k", keyword)
            .addQueryParameter("p", pageIndex.toString())
            .addQueryParameter("sort", sort)
            .apply {
                if (datef.isNotBlank() && datef != "all") {
                    addQueryParameter("datef", datef)
                }
                if (durf.isNotBlank() && durf != "allduration") {
                    addQueryParameter("durf", durf)
                }
                if (quality.isNotBlank() && quality != "all") {
                    addQueryParameter("quality", quality)
                }
                if (!typef.isNullOrBlank()) {
                    addQueryParameter("typef", typef)
                }
            }
            .build()
        return GET(url, searchHeaders())
    }

    private fun hasNonDefaultSearchFilters(
        sort: String,
        datef: String,
        durf: String,
        quality: String,
    ): Boolean = sort != "relevance" ||
        (datef.isNotBlank() && datef != "all") ||
        (durf.isNotBlank() && durf != "allduration") ||
        (quality.isNotBlank() && quality != "all")

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h2.page-title, #main h2")
            ?.ownText()
            ?.ifBlank { null }
            ?: document.selectFirst("h2.page-title, #main h2")?.text().orEmpty()
                .ifBlank {
                    document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
                }
        anime.author = document.selectFirst(
            "div.video-metadata li.main-uploader a span.name, " +
                "div.video-metadata a.main.uploader-tag span.name, " +
                "div.video-metadata a.uploader-tag span.name",
        )?.text()?.trim()
            ?: document.selectFirst("div.video-metadata a.name, a.main.uploader-tag")?.text()?.trim()
        anime.description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        anime.genre = document.select(
            "div.video-metadata ul li a span.name, " +
                "div.video-metadata ul li a span, " +
                "div.video-tags-list a span.name, " +
                "div.video-tags-list a",
        ).mapNotNull { it.ownText().ifBlank { it.text() }.trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }
        anime.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }
            ?: document.selectFirst("#html5player img, #main img, div.thumb img, img")?.run {
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
        AnimeFilter.Header("Sort / Date / Duration / Quality apply to text search"),
        AnimeFilter.Header("Empty text + those filters: broad search (site needs k=)"),
        AnimeFilter.Header("Without text: Brand → Category → Tag (first wins)"),
        SortFilter(),
        DateFilter(),
        DurationFilter(),
        QualityFilter(),
        AnimeFilter.Separator(),
        CategoryFilter(),
        TagFilter(),
        BrandFilter(),
    )

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs[state].second
    }

    private class SortFilter :
        UriSelectFilter(
            "Sort",
            arrayOf(
                "Relevance" to "relevance",
                "Upload date" to "uploaddate",
                "Rating" to "rating",
                "Length" to "length",
                "Views" to "views",
                "Random" to "random",
            ),
        )

    private class DateFilter :
        UriSelectFilter(
            "Date / time window",
            arrayOf(
                "All time" to "all",
                "Today" to "today",
                "This week" to "week",
                "This month" to "month",
                "Last 3 months" to "3month",
                "Last 6 months" to "6month",
            ),
        )

    private class DurationFilter :
        UriSelectFilter(
            "Duration",
            arrayOf(
                "Any duration" to "allduration",
                "1–3 min" to "1-3min",
                "3–10 min" to "3-10min",
                "10–20 min" to "10-20min",
                "20+ min" to "20min_more",
                "10+ min" to "10min_more",
            ),
        )

    private class QualityFilter :
        UriSelectFilter(
            "Quality filter (search)",
            arrayOf(
                "Any" to "all",
                "HD" to "hd",
                "1080p+" to "1080P",
            ),
        )

    private class CategoryFilter : UriSelectFilter("Category", CATEGORIES)

    private class TagFilter : AnimeFilter.Text("Tag (slug or name, e.g. japanese)")

    private class BrandFilter : AnimeFilter.Text("Brand / channel name")

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

    private fun normalizeSlug(raw: String): String = raw.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), "-")
        .replace(Regex("""[^a-z0-9\-]+"""), "")
        .trim('-')

    private fun bestMonthPath(): String {
        // Prefer previous month: current month "best" is often incomplete / empty early.
        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        return SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    companion object {
        private val QUALITY_REGEX = Regex("""(\d{3,4})""")

        /**
         * Broad keyword when filters are set without a user query.
         * Probed: site requires non-empty `k` for sort/datef/durf/quality; `"a"` returns a full page.
         */
        private const val FILTER_ONLY_KEYWORD = "a"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"

        // Free-site domains only. .es confirmed independent free mirror (same getvideo API).
        // Premium (xvideos.red) and xvideos2/3 (302 → www.xvideos.com) intentionally omitted.
        private val PREF_DOMAIN_ENTRIES = listOf(
            "www.xvideos.com",
            "www.xvideos.es",
        )
        private val PREF_DOMAIN_VALUES = PREF_DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_VALUES.first()

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080", "720", "480", "360", "240", "HLS", "MP4")
        private const val PREF_QUALITY_DEFAULT = "1080"

        // Main category chips from site nav (Name-id slugs as used in /c/…)
        private val CATEGORIES = arrayOf(
            "Any" to "",
            "AI" to "AI-239",
            "Amateur" to "Amateur-65",
            "Anal" to "Anal-12",
            "Arab" to "Arab-159",
            "Asian Woman" to "Asian_Woman-32",
            "ASMR" to "ASMR-229",
            "Ass" to "Ass-14",
            "BBW" to "bbw-51",
            "Bi Sexual" to "Bi_Sexual-62",
            "Big Ass" to "Big_Ass-24",
            "Big Cock" to "Big_Cock-34",
            "Big Tits" to "Big_Tits-23",
            "Black Woman" to "Black_Woman-30",
            "Blonde" to "Blonde-20",
            "Blowjob" to "Blowjob-15",
            "Brunette" to "Brunette-25",
            "Cam Porn" to "Cam_Porn-58",
            "Creampie" to "Creampie-40",
            "Cuckold" to "Cuckold-237",
            "Cumshot" to "Cumshot-18",
            "Femdom" to "Femdom-235",
            "Fisting" to "Fisting-165",
            "Fucked Up Family" to "Fucked_Up_Family-81",
            "Gangbang" to "Gangbang-69",
            "Gapes" to "Gapes-167",
            "Indian" to "Indian-89",
            "Interracial" to "Interracial-27",
            "Latina" to "Latina-16",
            "Lesbian" to "Lesbian-26",
            "Lingerie" to "Lingerie-83",
            "Mature" to "Mature-38",
            "MILF" to "Milf-19",
            "Oiled" to "Oiled-22",
            "Redhead" to "Redhead-31",
            "Solo and Masturbation" to "Solo_and_Masturbation-33",
            "Squirting" to "Squirting-56",
            "Stockings" to "Stockings-28",
            "Teen" to "Teen-13",
        )
    }
}
