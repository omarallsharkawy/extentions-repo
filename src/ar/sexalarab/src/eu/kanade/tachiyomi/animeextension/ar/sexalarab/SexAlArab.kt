package eu.kanade.tachiyomi.animeextension.ar.sexalarab

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
import kotlin.math.abs

/**
 * SexAlArab — KVS tube (sexalarab.com).
 *
 * Listings: most-popular / latest-updates / top-rated
 * Search: /search/?q=…&from_videos=N
 * Categories: /category/{slug}/ (+ async paging)
 * Streams: flashvars video_url(+alt) with function/0 hash decrypt
 */
class SexAlArab :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SexAlArab"

    override val baseUrl = "https://sexalarab.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(pagedPath("most-popular", page), headers)

    override fun popularAnimeSelector(): String = "div.list-videos div.item:not(.private) > a"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.attr("title").ifBlank {
            element.selectFirst("strong.title")?.text().orEmpty()
        }.trim()
        thumbnail_url = element.selectFirst("img.thumb, img")?.getImageUrl()
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination li.page a, div.pagination li.next a"

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

    override fun latestUpdatesRequest(page: Int): Request = GET(pagedPath("latest-updates", page), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()
        val tag = filters.firstInstanceOrNull<TagFilter>()?.state?.trim().orEmpty()

        // Priority: typed query → tag → category → sort-only listing → popular
        return when {
            query.isNotBlank() -> {
                val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query.trim())
                    .apply {
                        if (page > 1) addQueryParameter("from_videos", page.toString())
                    }
                    .build()
                GET(url, headers)
            }

            tag.isNotBlank() -> {
                val slug = slugify(tag)
                val path = if (page <= 1) "/tags/$slug/" else "/tags/$slug/$page/"
                GET(baseUrl + path, headers)
            }

            category.isNotBlank() -> {
                if (page <= 1) {
                    GET("$baseUrl/$category/", headers)
                } else {
                    // KVS category pages page via async block (path /2/ 404s on this host)
                    val url = "$baseUrl/$category/".toHttpUrl().newBuilder()
                        .addQueryParameter("mode", "async")
                        .addQueryParameter("function", "get_block")
                        .addQueryParameter("block_id", "list_videos_common_videos_list")
                        .addQueryParameter("sort_by", "post_date")
                        .addQueryParameter("from", page.toString())
                        .build()
                    GET(url, headers)
                }
            }

            sort.isNotBlank() -> GET(pagedPath(sort, page), headers)

            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector())
            .map { searchAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
        // Async category pages may omit full pagination chrome — keep paging while full page
        val hasNext = when {
            document.selectFirst(searchAnimeNextPageSelector()) != null -> true
            response.request.url.queryParameter("mode") == "async" -> animes.isNotEmpty()
            response.request.url.queryParameter("from_videos") != null -> animes.isNotEmpty()
            else -> false
        } && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val flash = document.html()
        title = FLASH_TITLE.find(flash)?.groupValues?.get(1)
            ?: document.selectFirst("h1, meta[property=og:title]")?.let {
                it.attr("content").ifBlank { it.text() }
            }.orEmpty()
        val cats = FLASH_CATS.find(flash)?.groupValues?.get(1).orEmpty()
        val tags = FLASH_TAGS.find(flash)?.groupValues?.get(1).orEmpty()
        genre = listOf(cats, tags)
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString()
            .ifBlank {
                document.select("div.info a[href*=/category/], div.info a[href*=/tag/], a[href*=/tags/]")
                    .eachText().joinToString()
            }
        description = document.selectFirst("meta[property=og:description], div.info .description, div.block-details")
            ?.let { el ->
                el.attr("content").ifBlank { el.text() }
            }
        thumbnail_url = fixUrl(document.selectFirst("meta[property=og:image]")?.attr("content"))
        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            episode_number = 1f
            date_upload = System.currentTimeMillis()
        },
    )

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val pageUrl = response.request.url.toString()
        val license = LICENSE_CODE.find(html)?.groupValues?.get(1)
        val videoHeaders = headersBuilder()
            .set("Referer", pageUrl)
            .set("Origin", baseUrl)
            .build()

        val pairs = mutableListOf<Pair<String, String>>()
        // video_url / video_alt_url / video_alt_url2 …
        FLASH_URL.findAll(html).forEach { m ->
            val key = m.groupValues[1]
            val url = m.groupValues[2]
            if (url.isBlank() || url == "1" || (!url.contains("get_file") && !url.startsWith("function"))) {
                return@forEach
            }
            val textKey = key + "_text"
            val quality = Regex("""$textKey:\s*'([^']*)'""").find(html)?.groupValues?.get(1)
                ?.ifBlank { null }
                ?: qualityFromUrl(url)
                ?: key
            pairs += quality to url
        }

        if (pairs.isEmpty()) {
            // Fallback: raw get_file links in page
            GET_FILE.findAll(html).map { it.value }.distinct().forEach { url ->
                pairs += (qualityFromUrl(url) ?: "MP4") to url
            }
        }

        val videos = pairs.mapNotNull { (quality, raw) ->
            val resolved = try {
                if (raw.startsWith("function")) {
                    if (license == null) return@mapNotNull null
                    decryptHash(raw, license)
                } else {
                    raw
                }
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (resolved.isBlank()) return@mapNotNull null
            Video(resolved, quality, resolved, headers = videoHeaders)
        }.distinctBy { it.url }

        if (videos.isEmpty()) {
            error("No playable streams found (flashvars missing or private video)")
        }
        return videos
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video> { !it.quality.contains(quality, ignoreCase = true) }
                .thenByDescending {
                    QUALITY_NUM.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    // =============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored when text query is set (except combined sort N/A)"),
        AnimeFilter.Header("Priority: Query → Tag → Category → Sort → Popular"),
        SortFilter(),
        CategoryFilter(),
        TagFilter(),
    )

    private class SortFilter :
        UriSelectFilter(
            "Sort / listing",
            arrayOf(
                "Most popular" to "most-popular",
                "Latest updates" to "latest-updates",
                "Top rated" to "top-rated",
            ),
        )

    private class CategoryFilter :
        UriSelectFilter(
            "Category",
            CATEGORIES,
        )

    private class TagFilter : AnimeFilter.Text("Tag (slug or Arabic text)")

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs[state].second
    }

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

    private fun pagedPath(section: String, page: Int): String = if (page <= 1) {
        "$baseUrl/$section/"
    } else {
        "$baseUrl/$section/$page/"
    }

    private fun slugify(raw: String): String = raw.trim()
        .replace(Regex("""\s+"""), "-")
        .trim('-')

    private fun qualityFromUrl(url: String): String? = QUALITY_FROM_URL.find(url)?.groupValues?.get(1)?.let { "${it}p" }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    /**
     * KVS function/0 video hash decrypt (hashRange=16).
     * Port of JDownloader KernelVideoSharingCom.decryptHash/calcSeed.
     */
    private fun decryptHash(videoUrl: String, licenseCode: String, hashRange: Int = 16): String {
        val parts = videoUrl.split("/").toMutableList()
        // function/0/https:/''/host/get_file/id/HASH/...
        require(parts.size > 7) { "Unexpected KVS URL shape" }
        val hashFull = parts[7]
        val convertLen = 2 * hashRange
        var hash = hashFull.take(convertLen)
        val nonConvert = hashFull.drop(convertLen)
        val seed = calcSeed(licenseCode, hashRange)
        val seedArray = seed.map { it.toString() }
        for (k in hash.length - 1 downTo 0) {
            val hashArray = hash.map { it.toString() }.toMutableList()
            var l = k
            for (m in k until seedArray.size) {
                l += seedArray[m].toInt()
            }
            while (l >= hashArray.size) {
                l -= hashArray.size
            }
            val n = StringBuilder()
            for (o in hashArray.indices) {
                n.append(
                    when (o) {
                        k -> hashArray[l]
                        l -> hashArray[k]
                        else -> hashArray[o]
                    },
                )
            }
            hash = n.toString()
        }
        parts[7] = hash + nonConvert
        return parts.drop(2).joinToString("/")
    }

    private fun calcSeed(licenseCode: String, hashRange: Int): String {
        val licenseCodeArray = licenseCode.map { it.toString() }
        val fb = StringBuilder()
        for (c in licenseCodeArray) {
            if (c == "$") continue
            val v = c.toInt()
            fb.append(if (v != 0) c else "1")
        }
        val f = fb.toString()
        val j = f.length / 2
        val k = f.substring(0, j + 1).toInt()
        val l = f.substring(j).toInt()
        var g = abs(l - k)
        var fi = g
        g = abs(k - l)
        fi += g
        fi *= 2
        val s = fi.toString()
        val fArray = s.map { it.toString() }
        val i = hashRange / 2 + 2
        val m = StringBuilder()
        for (g2 in 0..j) {
            for (h in 1..4) {
                var n = licenseCodeArray[g2 + h].toInt() + fArray[g2].toInt()
                if (n >= i) n -= i
                m.append(n)
            }
        }
        return m.toString()
    }

    private fun Element.getImageUrl(): String? {
        val src = when {
            hasAttr("data-original") -> attr("data-original")
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ").substringBefore(",")
            hasAttr("poster") -> attr("poster")
            else -> attr("src")
        }.ifBlank { attr("src") }.trim()

        if (src.isBlank() || src.startsWith("data:") || src.endsWith(".gif")) return null
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
        private val FLASH_TITLE = Regex("""video_title:\s*'((?:\\'|[^'])*)'""")
        private val FLASH_CATS = Regex("""video_categories:\s*'((?:\\'|[^'])*)'""")
        private val FLASH_TAGS = Regex("""video_tags:\s*'((?:\\'|[^'])*)'""")
        private val LICENSE_CODE = Regex("""license_code:\s*'([^']+)'""")
        private val FLASH_URL = Regex(
            """(video_url|video_alt_url\d*):\s*'((?:\\'|[^'])*)'""",
        )
        private val GET_FILE = Regex("""https?://[^"'\\\s]+/get_file/[^"'\\\s]+""")
        private val QUALITY_FROM_URL = Regex("""_(\d{3,4})p\.(?:mp4|m3u8)""", RegexOption.IGNORE_CASE)
        private val QUALITY_NUM = Regex("""(\d{3,4})""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val PREF_QUALITY_ENTRIES = listOf("1080", "720", "480", "360", "240")

        // Probed from /categories/ (paths are /category/{slug}/ on this host)
        private val CATEGORIES = arrayOf(
            "Any" to "",
            "سكس مترجم" to "category/سكس-مترجم",
            "سكس نيك عربي" to "category/سكس-نيك-عربي",
            "سكس امهات" to "category/سكس-امهات",
            "سكس اخوات" to "category/سكس-نيك-اخوات",
            "سكس عائلي" to "category/سكس-عائلي",
            "سكس ميلف" to "category/سكس-ميلف",
            "سكس مراهقات" to "category/سكس-نيك-مراهقات",
            "سكس طيز" to "category/سكس-نيك-طيز",
            "سكس سحاق" to "category/افلام-سكس-بنات-سحاق",
            "سكس مدبلج" to "category/سكس-مدبلج",
            "مسلسلات" to "category/مسلسلات-سكس-مترجم",
            "نودز" to "category/نودز",
            "سكس" to "category/سكس",
        )
    }
}
