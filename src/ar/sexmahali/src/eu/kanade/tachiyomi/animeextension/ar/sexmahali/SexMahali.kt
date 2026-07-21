package eu.kanade.tachiyomi.animeextension.ar.sexmahali

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Locale

class SexMahali :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SexMahali"

    override val baseUrl = "https://sexmahali.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl(page, SORT_POPULAR, categoryPath = ""), headers)

    override fun popularAnimeSelector(): String = "article.loop-video.thumb-block, article.thumb-block"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.attr("title").ifBlank {
            element.selectFirst("header.entry-header span, header.entry-header")?.text().orEmpty()
        }.ifBlank { link.text() }.trim()
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-src").ifBlank { img.attr("abs:src") }
                .takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl(page, SORT_LATEST, categoryPath = ""), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
            .ifBlank { SORT_LATEST }
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()

        return when {
            query.isNotBlank() -> GET(searchUrl(page, query.trim()), headers)
            category.isNotBlank() || sort.isNotBlank() ->
                GET(browseUrl(page, sort, category), headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        SortFilter(),
        CategoryFilter(),
    )

    private class SortFilter :
        UriSelectFilter(
            "Sort",
            arrayOf(
                "Popular (best)" to SORT_POPULAR,
                "Latest" to SORT_LATEST,
                "Most viewed" to SORT_MOST_VIEWED,
                "Longest" to SORT_LONGEST,
                "Random" to SORT_RANDOM,
            ),
        )

    private class CategoryFilter :
        UriSelectFilter(
            "Category",
            arrayOf(
                "<Any>" to "",
                "سكس مترجم" to "category/سكس-مترجم",
                "سكس امهات مترجم" to "category/سكس-امهات-مترجم",
                "سكس اخوات مترجم" to "category/سكس-اخوات-مترجم",
                "سكس خيانة مترجم" to "category/سكس-خيانة-مترجم",
                "سكس ميلف مترجم" to "category/سكس-ميلف-مترجم",
                "سكس حجاب مترجم" to "category/سكس-حجاب-مترجم",
                "سكس محارم مترجم" to "category/سكس-محارم-مترجم",
                "سكس مساج مترجم" to "category/سكس-مساج-مترجم",
                "سكس ثلاثي مترجم" to "category/سكس-ثلاثي-مترجم",
                "سكس مراهقات مترجم" to "category/سكس-مراهقات-مترجم",
            ),
        )

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs.getOrNull(state)?.second.orEmpty()
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.ifBlank { null }
            ?: document.selectFirst("h1, .entry-title, .video-player meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1, .entry-title")?.text().orEmpty()

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")

        description = buildString {
            document.selectFirst(".video-description, #video-about .desc, .entry-content .desc")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { append(it) }

            document.selectFirst("#video-date")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }

            document.selectFirst("#video-views")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (isNotEmpty()) append("\n")
                append("المشاهدات: $it")
            }
        }.ifBlank {
            document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        }

        genre = document.select(".tags-list a.label, .tags a.label, a[rel=category tag]")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .distinct()
            .joinToString()
            .ifBlank { null }

        artist = document.select("#video-actors a")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .joinToString()
            .ifBlank { null }

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val date = parseArabicDate(document.selectFirst("#video-date")?.text().orEmpty())
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Video"
                episode_number = 1f
                date_upload = date
            },
        )
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageHtml = document.html()
        val embeds = extractEmbedUrls(document, pageHtml)
        if (embeds.isEmpty()) return emptyList()

        return embeds.distinct().parallelCatchingFlatMapBlocking { embedUrl ->
            videosFromEmbed(embedUrl)
        }
    }

    private fun extractEmbedUrls(document: Document, pageHtml: String): List<String> {
        val urls = linkedSetOf<String>()

        document.select("iframe[src]").forEach { iframe ->
            iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                .takeIf { it.startsWith("http") }
                ?.let(urls::add)
        }

        document.select("meta[itemprop=embedURL]").forEach { meta ->
            meta.attr("content").takeIf { it.startsWith("http") }?.let(urls::add)
        }

        // WP-Script multi-server: embeds=["<iframe src=\"https:\/\/host\/...\">", ...]
        IFRAME_SRC_REGEX.findAll(pageHtml).forEach { match ->
            val raw = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("&amp;", "&")
            if (raw.startsWith("http")) urls.add(raw)
        }

        return urls.toList()
    }

    private fun videosFromEmbed(embedUrl: String): List<Video> {
        val host = runCatching { embedUrl.toHttpUrl().host.lowercase() }.getOrDefault("")
        val label = hostLabel(host)

        return when {
            host.contains("voe") ->
                voeExtractor.videosFromUrl(embedUrl, prefix = "$label ")

            host.contains("streamtape") || host.contains("stape") || host.contains("strtape") ->
                streamTapeExtractor.videosFromUrl(embedUrl, quality = label)

            isTurboVidHost(host) ->
                videosFromTurboVid(embedUrl, label)

            else ->
                videosFromGenericEmbed(embedUrl, label)
        }
    }

    private fun videosFromTurboVid(embedUrl: String, label: String): List<Video> {
        val body = client.newCall(GET(embedUrl, headers)).execute().use { it.bodyString() }
        val playlistUrl = DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: URLPLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: M3U8_REGEX.find(body)?.value
            ?: return emptyList()

        if (runCatching { playlistUrl.toHttpUrl() }.isFailure) return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl,
            referer = embedUrl,
            videoNameGen = { "$label - $it" },
        ).distinctBy { it.videoUrl }
    }

    private fun videosFromGenericEmbed(embedUrl: String, label: String): List<Video> {
        val body = runCatching {
            client.newCall(GET(embedUrl, headers)).execute().use { it.bodyString() }
        }.getOrNull() ?: return emptyList()

        // Cloudflare / JS-only shells won't expose media URLs
        if ("Just a moment" in body || "cf-browser-verification" in body) return emptyList()

        val playlistUrl = DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: URLPLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: M3U8_REGEX.find(body)?.value

        if (playlistUrl != null && runCatching { playlistUrl.toHttpUrl() }.isSuccess) {
            return playlistUtils.extractFromHls(
                playlistUrl,
                referer = embedUrl,
                videoNameGen = { "$label - $it" },
            ).distinctBy { it.videoUrl }
        }

        val mp4 = MP4_REGEX.find(body)?.value ?: return emptyList()
        val videoHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .build()
        return listOf(Video(mp4, "$label - MP4", mp4, headers = videoHeaders))
    }

    private fun isTurboVidHost(host: String): Boolean = host.contains("turbovid") ||
        host.contains("turboviplay") ||
        host.contains("emturbovid") ||
        host.contains("sptvp") ||
        host.contains("turbosplayer")

    private fun hostLabel(host: String): String = when {
        host.contains("voe") -> "Voe"
        host.contains("streamtape") || host.contains("stape") -> "StreamTape"
        host.contains("turbovid") || host.contains("turboviplay") -> "TurboVid"
        host.contains("hgcloud") -> "HGCloud"
        host.contains("abyss") -> "Abyss"
        host.contains("seekplays") -> "SeekPlays"
        host.contains("playmogo") -> "PlayMogo"
        host.isBlank() -> "Server"
        else -> host.substringBefore('.').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video> { !it.quality.contains(quality, ignoreCase = true) }
                .thenByDescending {
                    QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // =============================== Helpers ==============================

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }

        val current = document.selectFirst("div.pagination a.current")?.text()?.toIntOrNull() ?: 1
        val hasNext = document.select("div.pagination a").any {
            (it.text().toIntOrNull() ?: -1) > current
        }

        return AnimesPage(animes, hasNext && animes.isNotEmpty())
    }

    private fun browseUrl(page: Int, sort: String, categoryPath: String): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        if (categoryPath.isNotBlank()) {
            categoryPath.trim('/').split('/').filter { it.isNotEmpty() }.forEach {
                builder.addPathSegment(it)
            }
        }
        if (page > 1) {
            builder.addPathSegment("page")
            builder.addPathSegment(page.toString())
        }
        if (sort.isNotBlank()) {
            builder.addQueryParameter("filter", sort)
        }
        return builder.build().toString()
    }

    private fun searchUrl(page: Int, query: String): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        if (page > 1) {
            builder.addPathSegment("page")
            builder.addPathSegment(page.toString())
        }
        builder.addQueryParameter("s", query)
        return builder.build().toString()
    }

    /**
     * Site date text examples: "تاريخ: يوليو 17, 2026"
     */
    private fun parseArabicDate(raw: String): Long {
        val cleaned = raw
            .replace("تاريخ:", "", ignoreCase = false)
            .replace("تاريخ", "", ignoreCase = false)
            .trim()
        if (cleaned.isBlank()) return 0L

        val match = ARABIC_DATE_REGEX.find(cleaned) ?: return 0L
        val monthName = match.groupValues[1].trim()
        val day = match.groupValues[2].toIntOrNull() ?: return 0L
        val year = match.groupValues[3].toIntOrNull() ?: return 0L
        val month = ARABIC_MONTHS[monthName] ?: return 0L

        return runCatching {
            Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.getOrDefault(0L)
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    companion object {
        private const val SORT_POPULAR = "popular"
        private const val SORT_LATEST = "latest"
        private const val SORT_MOST_VIEWED = "most-viewed"
        private const val SORT_LONGEST = "longest"
        private const val SORT_RANDOM = "random"

        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val IFRAME_SRC_REGEX = Regex(
            """src\\?=["'](https?:\\?/\\?/[^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val DATA_HASH_REGEX = Regex(
            """data-hash\s*=\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val URLPLAY_REGEX = Regex(
            """urlPlay\s*=\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE,
        )
        private val M3U8_REGEX = Regex("""https?://[^"'\s\\<>]+m3u8[^"'\s\\<>]*""")
        private val MP4_REGEX = Regex("""https?://[^"'\s\\<>]+\.mp4[^"'\s\\<>]*""")
        private val ARABIC_DATE_REGEX = Regex(
            """([^\d\s/,-]+)\s+(\d{1,2})\s*,\s*(\d{4})""",
        )

        private val ARABIC_MONTHS = mapOf(
            "يناير" to 1,
            "فبراير" to 2,
            "مارس" to 3,
            "أبريل" to 4,
            "ابريل" to 4,
            "مايو" to 5,
            "يونيو" to 6,
            "يوليو" to 7,
            "أغسطس" to 8,
            "اغسطس" to 8,
            "سبتمبر" to 9,
            "أكتوبر" to 10,
            "اكتوبر" to 10,
            "نوفمبر" to 11,
            "ديسمبر" to 12,
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080", "720", "480", "360")
    }
}
