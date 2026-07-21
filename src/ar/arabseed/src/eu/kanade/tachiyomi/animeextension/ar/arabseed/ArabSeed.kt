package eu.kanade.tachiyomi.animeextension.ar.arabseed

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ArabSeed :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "عرب سيد"

    // Domain chain: m.asd.homes/watch 521-dead → arabseed.codes → arabseed.men
    override val baseUrl = "https://arabseed.men"

    override val lang = "ar"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.item__contents a.movie__block"

    override fun popularAnimeRequest(page: Int) = GET(
        if (page == 1) baseUrl else "$baseUrl/page/$page/",
        headers,
    )

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.attr("title").ifBlank {
            element.selectFirst("img")?.attr("alt").orEmpty()
        }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-src")
                .ifEmpty { img.attr("abs:data-lazy-src") }
                .ifEmpty { img.attr("abs:src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "ul.page-numbers li a.next, a.next.page-numbers"

    // ============================== Episode ===============================
    override fun episodeListSelector() = "a.episode__item, div.episodes__list a, ul.episodes__list a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.useAsJsoup()

        // Extract season IDs from season menu or loadMore button
        val seasonIds = doc.select(".season-item[data-season], #loadMoreEpisodes[data-season]")
            .mapNotNull { it.attr("data-season") }
            .distinct()

        if (seasonIds.isEmpty()) {
            // No AJAX episodes — fall back to watch button as single episode
            val watchPath = doc.selectFirst("a.watch__btn")?.attr("abs:href")
                ?: doc.location().trimEnd('/') + "/watch/"
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(watchPath)
                    name = "مشاهدة"
                    episode_number = 1F
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        val formHeaders = headers.newBuilder()
            .add("Content-Type", "application/x-www-form-urlencoded")
            .build()

        // Paginate through each season
        for (seasonId in seasonIds) {
            var page = 1
            while (true) {
                val form = FormBody.Builder()
                    .add("action", "load_episodes")
                    .add("season", seasonId)
                    .add("page", page.toString())
                    .build()

                val resp = client.newCall(
                    POST("$baseUrl/wp-admin/admin-ajax.php", formHeaders, form),
                ).execute().useAsJsoup()

                val html = resp.html().trim()
                if (html.isBlank() || html == "no_more") break

                // Parse <li><a href="...watch"><div class="epi__num">الحلقة <b>N</b></div></a></li>
                for (el in resp.select("li > a")) {
                    val url = el.attr("abs:href")
                    val epiBlock = el.selectFirst("div.epi__num")
                    val name = epiBlock?.text()?.ifBlank { el.text() }.orEmpty()
                    val epNumText = el.selectFirst("div.epi__num b")?.text()?.trim()
                    val episodeNumber = if (!epNumText.isNullOrBlank() && epNumText.matches(Regex("\\d+"))) {
                        epNumText.toFloatOrNull() ?: 0F
                    } else {
                        Regex("\\b(\\d+)\\b").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0F
                    }

                    episodes.add(
                        SEpisode.create().apply {
                            setUrlWithoutDomain(url)
                            this.name = if (name.isBlank()) "مشاهدة ${episodes.size + 1}" else name
                            episode_number = episodeNumber
                        },
                    )
                }

                page++
            }
        }

        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        val name = element.text().ifBlank { element.attr("title") }.ifBlank { "حلقة" }
        this.name = name
        episode_number = element.selectFirst("div.epi__num b")?.text()?.trim()?.toFloatOrNull()
            ?: element.selectFirst("em")?.text()?.toFloatOrNull()
            ?: Regex("\\b(\\d+)\\b").find(name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        // Episode/watch page may already have servers; otherwise follow watch button.
        val watchDoc = if (doc.select(videoListSelector()).isNotEmpty()) {
            doc
        } else {
            val watchUrl = doc.selectFirst("a.watch__btn")?.attr("abs:href")
                ?: doc.location().trimEnd('/').let {
                    if (it.endsWith("/watch")) it else "$it/watch/"
                }
            client.newCall(GET(watchUrl, headers)).execute().useAsJsoup()
        }
        return videosFromElement(watchDoc)
    }

    override fun videoListSelector() = "div.servers__list li[data-link], ul.d__flex li[data-link]"

    private fun videosFromElement(document: Document): List<Video> = document.select(videoListSelector())
        .parallelCatchingFlatMapBlocking { element ->
            val quality = element.attr("data-qu").ifBlank { element.text() }
            val rawLink = element.attr("data-link")
            val embedUrl = resolveEmbedUrl(rawLink)
            getVideosFromUrl(embedUrl, quality)
        }

    private fun resolveEmbedUrl(raw: String): String {
        if (raw.isBlank()) return raw
        val absolute = if (raw.startsWith("http")) raw else baseUrl + raw
        // /play.php?url=BASE64 or /play/?id=BASE64
        val encoded = absolute.toHttpUrl().queryParameter("url")
            ?: absolute.toHttpUrl().queryParameter("id")
        if (encoded != null) {
            return runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT))
            }.getOrDefault(absolute)
        }
        return absolute
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private suspend fun getVideosFromUrl(url: String, quality: String): List<Video> {
        val label = if (quality.endsWith("p", true)) quality else "${quality}p"
        return when {
            "reviewtech" in url || "reviewrate" in url || "arabseed.top" in url -> {
                val iframeResponse = client.newCall(GET(url, headers)).awaitSuccess()
                    .useAsJsoup()
                val videoUrl = iframeResponse.selectFirst("source")?.attr("abs:src")
                    ?: iframeResponse.selectFirst("iframe")?.attr("abs:src")
                    ?: return emptyList()
                if (videoUrl.endsWith(".mp4") || videoUrl.contains("m3u8")) {
                    listOf(Video(videoUrl, label, videoUrl))
                } else {
                    getVideosFromUrl(videoUrl, quality)
                }
            }

            "dood" in url -> doodExtractor.videosFromUrl(url)

            "fviplions" in url || "wish" in url || "vidhide" in url || "luluv" in url ->
                streamwishExtractor.videosFromUrl(url)

            "voe.sx" in url || "voe" in url -> voeExtractor.videosFromUrl(url)

            "vidmoly" in url -> {
                val doc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
                val src = doc.selectFirst("source")?.attr("abs:src")
                    ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(doc.html())?.groupValues?.get(1)
                src?.let { listOf(Video(it, "Vidmoly - $label", it)) } ?: emptyList()
            }

            else -> {
                // Try generic source tag on embed page
                runCatching {
                    val doc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
                    val src = doc.selectFirst("source")?.attr("abs:src")
                    src?.let { listOf(Video(it, label, it)) }
                }.getOrNull() ?: emptyList()
            }
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            // Old /find/?word= permanently redirects to home; WP search uses ?s=
            val pageSuffix = if (page > 1) "&paged=$page" else ""
            "$baseUrl/?s=$query$pageSuffix"
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
            val category = typeFilter.toUriPart()
            if (category.isEmpty()) throw Exception("اختر فلتر")

            val pageSuffix = if (page > 1) "page/$page/" else ""
            "$baseUrl/category/$category$pageSuffix"
        }
        return GET(url, headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.post__name")?.text()
            ?.replace(" مترجم", "")
            ?.replace("فيلم ", "")
            .orEmpty()
        thumbnail_url = document.selectFirst("div.poster__single img, div.single__cover img, img.images__loader, meta[property=og:image]")
            ?.let { img ->
                when {
                    img.hasAttr("content") -> img.attr("abs:content")

                    else -> img.attr("abs:data-src")
                        .ifEmpty { img.attr("abs:data-lazy-src") }
                        .ifEmpty { img.attr("abs:src") }
                }
            }
        description = document.selectFirst("div.post__story p, p.post__content")?.text()
        genre = document.select("div.info__area a[href*=genre], ul.info__area__ul a, div.genres a")
            .eachText()
            .joinToString()
            .ifBlank { null }
        status = SAnime.UNKNOWN
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        TypeFilter(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class TypeFilter :
        UriPartFilter(
            "نوع الفلم",
            arrayOf(
                Pair("أختر", ""),
                Pair("افلام عربي", "افلام-عربي/"),
                Pair("افلام اجنبى", "افلام-اجنبي/"),
                Pair("افلام اسيوية", "افلام-اسيوية/"),
                Pair("افلام هندى", "افلام-هندي/"),
                Pair("افلام تركية", "افلام-تركية/"),
                Pair("افلام انمي", "افلام-انمي/"),
                Pair("مسلسلات عربي", "مسلسلات-عربي/"),
                Pair("مسلسلات اجنبي", "مسلسلات-اجنبي/"),
                Pair("مسلسلات تركيه", "مسلسلات-تركية/"),
                Pair("مسلسلات اسيوية", "مسلسلات-اسيوية/"),
                Pair("مسلسلات هندية", "مسلسلات-هندية/"),
                Pair("مسلسلات انمي", "مسلسلات-انمي/"),
                Pair("برامج تلفزيونية", "برامج-تلفزيونية/"),
                Pair("مسلسلات رمضان 2024", "مسلسلات-رمضان-2024/"),
                Pair("مسلسلات رمضان 2025", "مسلسلات-رمضان-2025/"),
                Pair("مسلسلات رمضان 2026", "مسلسلات-رمضان-2026/"),
            ),
        )

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    // =============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
