package eu.kanade.tachiyomi.animeextension.ar.aflamk1

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
 * Aflamk1 — KVS tube (aflamk1.net).
 *
 * Listings: most-popular / latest-updates / top-rated
 * Search: /search/?q=…&from_videos=N
 * Categories: /categories/{slug}/[page]/
 * Streams: flashvars video_url (plain get_file + v-acctoken; function/0 if present)
 */
class Aflamk1 :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Aflamk1"

    override val baseUrl = "https://www.aflamk1.net"

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
        thumbnail_url = element.selectFirst("img.thumb, img")?.let { img ->
            img.attr("abs:data-original").ifBlank { null }
                ?: img.attr("abs:data-src").ifBlank { null }
                ?: img.attr("abs:data-webp").ifBlank { null }
                ?: img.attr("abs:src").takeUnless {
                    it.contains("data:image") || it.endsWith(".gif")
                }
        }
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
                val path = if (page <= 1) "/$category/" else "/$category/$page/"
                GET(baseUrl + path, headers)
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
        val hasNext = when {
            document.selectFirst(searchAnimeNextPageSelector()) != null -> true
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
                document.select("a[href*=/categories/], a[href*=/tags/], a[href*=/tag/]")
                    .eachText().joinToString()
            }
        description = document.selectFirst("meta[property=og:description], div.info .description, div.block-details")
            ?.let { el ->
                el.attr("content").ifBlank { el.text() }
            }
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img.thumb[src*=videos_screenshots], video[poster]")?.let {
                it.attr("abs:src").ifBlank { it.attr("abs:poster") }
            }
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
        FLASH_URL.findAll(html).forEach { m ->
            val key = m.groupValues[1]
            val url = m.groupValues[2]
            if (url.isBlank() || url == "1") return@forEach
            if (!url.contains("get_file") && !url.startsWith("function") && !url.contains(".mp4") &&
                !url.contains(".m3u8")
            ) {
                return@forEach
            }
            val quality = Regex("""${Regex.escape(key)}_text:\s*'([^']*)'""")
                .find(html)?.groupValues?.get(1)?.ifBlank { null }
                ?: qualityFromUrl(url)
                ?: if (html.contains("video_url_hd: '1'") && key == "video_url") "HD" else "MP4"
            pairs += quality to url
        }

        if (pairs.isEmpty()) {
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

    private fun decryptHash(videoUrl: String, licenseCode: String, hashRange: Int = 16): String {
        val parts = videoUrl.split("/").toMutableList()
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
        private val PREF_QUALITY_ENTRIES = listOf("1080", "720", "480", "360", "240", "HD", "MP4")

        // Probed from site nav + /categories/
        private val CATEGORIES = arrayOf(
            "Any" to "",
            "سكس مترجم" to "categories/سكس-مترجم",
            "سكس محارم" to "categories/سكس-محارم-مترجم",
            "سكس اخوات" to "categories/سكس-اخوات-مترجم",
            "سكس امهات" to "categories/سكس-امهات-مترجم",
            "سكس جماعي" to "categories/سكس-جماعى-مترجم",
            "سكس محارم الاب" to "categories/سكس-محارم-الاب-مترجم",
            "سكس شيميل" to "categories/سكس-شيميل-مترجم",
            "سكس سحاق" to "categories/سكس-سحاق-مترجم",
        )
    }
}
