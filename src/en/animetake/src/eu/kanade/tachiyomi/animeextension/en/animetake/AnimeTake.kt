package eu.kanade.tachiyomi.animeextension.en.animetake

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelFlatMap
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeTake :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeTake"

    override val baseUrl = "https://animetake.tv"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animelist/popular")

    override fun popularAnimeSelector() = "div.card.component-animelist"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href*=/anime/]")!!.attr("href")
        setUrlWithoutDomain(link)
        val imgSrc = element.selectFirst("div.animeposter img")?.attr("data-src")
            ?.ifBlank { null }
            ?: element.selectFirst("div.animeposter img")?.attr("src").orEmpty()
        thumbnail_url = when {
            imgSrc.startsWith("http") -> imgSrc
            imgSrc.isNotBlank() -> baseUrl + imgSrc
            else -> null
        }
        title = element.selectFirst("span.animename")!!.text()
    }

    // Popular page is a single SSR dump with no traditional pagination.
    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    // /animelist/ is client-rendered; empty search returns a server-rendered catalog.
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/search/?search=&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeTakeFilters.getSearchParameters(filters)
        val cleanQuery = query.replace(" ", "+").lowercase()
        val multiString = buildString {
            if (params.letters.isNotEmpty()) append(params.letters + "&")
            if (params.genres.isNotEmpty()) append(params.genres + "&")
            if (params.score.isNotEmpty()) append(params.score + "&")
            if (params.years.isNotEmpty()) append(params.years + "&")
            if (params.ratings.isNotEmpty()) append(params.ratings + "&")
        }
        return if (query.isNotEmpty()) {
            GET("$baseUrl/search/?search=$cleanQuery&page=$page&$multiString")
        } else {
            // Filtered catalog is also SSR via /search/, not the empty /animelist/ shell.
            GET("$baseUrl/search/?search=&page=$page&$multiString")
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeTakeFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h2 > b")?.text()
            ?: document.selectFirst("h2")?.text().orEmpty()
        anime.genre = document.select("span.badge-genre").joinToString { it.text() }
        val descBlock = document.selectFirst("div.d-none.d-sm-block:contains(Description)")
            ?: document.selectFirst("div:containsOwn(Description)")
        anime.description = descBlock?.ownText()?.ifBlank {
            descBlock.text().substringAfter("Description").trim()
        }
        val statusText = document.select("tr:has(td:contains(Status)) td").lastOrNull()?.text().orEmpty()
        anime.status = when {
            statusText.contains("Ongoing", true) -> SAnime.ONGOING
            statusText.contains("Completed", true) -> SAnime.COMPLETED
            document.select("div.well > center:contains(Next Episode)").isNotEmpty() -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.card.episodelist"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector())
            .map(::episodeFromElement)
            // Site lists newest first; keep ascending order for the app.
            .reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst("a[href*=/watch/]")
            ?: element.parents().firstOrNull {
                it.tagName() == "a" && it.attr("href").contains("/watch/")
            }
        setUrlWithoutDomain(link!!.attr("href"))
        val epName = element.selectFirst("span.animename")?.text()?.trim()
            ?: link.attr("title").trim()
        name = epName
        val upDate = element.selectFirst("span.time, span.badge.date")?.ownText()?.trim()
            ?: element.selectFirst("span.time, span.badge.date")?.text()?.trim().orEmpty()
        date_upload = parseDate(upDate)
        episode_number = Regex("""(?i)episode\s+(\d+(?:\.\d+)?)""")
            .find(epName)?.groupValues?.get(1)?.toFloatOrNull()
            ?: epName.split(" ").lastOrNull()?.toFloatOrNull()
            ?: 0F
    }

    // ============================ Video Links =============================
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scripts = document.select("div#divscript > script").filter { it ->
            it.data().contains("function")
        }
        return scripts.parallelCatchingFlatMapBlocking { elem ->
            val data = elem.data().trimIndent()
            val url = baseUrl + extractIframeSrc(data)
            if (data.contains("vidstream()")) {
                val iframeSrc = client.newCall(GET(url)).execute().asJsoup()
                    .select("iframe").attr("src")
                extractVideo(iframeSrc)
            } else if (data.contains("fm()")) {
                val iframeSrc = client.newCall(GET(url)).execute().asJsoup()
                    .select("iframe").attr("src")
                filemoonExtractor.videosFromUrl(url = iframeSrc, headers = headers)
            } else {
                emptyList()
            }
        }
    }

    private suspend fun extractVideo(url: String): List<Video> {
        val videos = gogoStreamExtractor.videosFromUrl(url)

        val request = GET(url)
        val response = client.newCall(request).await()
        val document = response.asJsoup()
        val servers = document.select("div#list-server-more > ul > li.linkserver")
        return servers.parallelFlatMap {
            val link = it.attr("data-video")
            when (it.text().lowercase()) {
                "doodstream" -> doodExtractor.videosFromUrl(link)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(link, headers)
                else -> emptyList()
            }
        } + videos
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun extractIframeSrc(scriptData: String): String {
        val iframeRegex = "<iframe[^>]*>.*?</iframe>".toRegex()
        val iframe = iframeRegex.find(scriptData)?.value ?: return ""
        val srcRegex = "(?<=src=\").*?(?=[\\*\"])".toRegex()
        return srcRegex.find(iframe)?.value.orEmpty()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    private fun parseDate(date: String): Long {
        if (date.isBlank()) return 0L
        val patterns = listOf(
            SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH),
            SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH),
            SimpleDateFormat("dd LLLL yyyy", Locale.ENGLISH),
        )
        for (formatter in patterns) {
            runCatching {
                formatter.parse(date)?.let { return it.time }
            }
        }
        return 0L
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vidstreaming"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "Vidstreaming",
            "Filemoon",
            "Doodstream",
            "Mp4upload",
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRIES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
