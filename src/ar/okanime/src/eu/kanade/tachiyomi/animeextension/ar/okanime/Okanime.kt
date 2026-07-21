package eu.kanade.tachiyomi.animeextension.ar.okanime

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.vidbomextractor.VidBomExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Okanime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Okanime"

    override val baseUrl = "https://ww3.okanime.xyz"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    // Site redesign: paginated catalogue lives at /anime-list
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime-list?page=$page")

    override fun popularAnimeSelector() = "div.anime-card.anime-hover, div.anime-card:not(.episode-card)"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("div.anime-title > h4 > a")!!.also {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li.page-item:last-child:not(.disabled) a"

    // =============================== Latest ===============================
    // Old /espisode-list path is 404; official feed is recently-uploaded-episodes
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/recently-uploaded-episodes?page=$page")

    override fun latestUpdatesSelector() = "div.anime-card.episode-card, div.anime-card"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = "$baseUrl/search/?s=$query"
        .let { if (page > 1) "$it&page=$page" else it }
        .let(::GET)

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h1.animepage-h1, h2.animepage-h1, .animepage-title-block h1")
            ?.text()
            ?.ifBlank { null }
            ?: document.selectFirst("div.author-info-title > h1")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore("|")?.trim().orEmpty()

        thumbnail_url = document.selectFirst("img.animepage-poster, div.animepage-poster-wrap img")
            ?.attr("src")
            ?: document.selectFirst("div.text-right img")?.attr("src")

        genre = document.select("div.animepage-genres a, div.review-author-info a")
            .eachText()
            .joinToString()
            .ifBlank { null }

        status = document.selectFirst("div.animepage-meta-row:has(dt:contains(الحالة)) dd a, div.animepage-meta-row:has(dt:contains(الحالة)) dd")
            ?.text()
            .let {
                when {
                    it == null -> SAnime.UNKNOWN
                    it.contains("يعرض") || it.contains("حاليا") -> SAnime.ONGOING
                    it.contains("مكتمل") || it.contains("منتهي") -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }.takeIf { it != SAnime.UNKNOWN }
            ?: document.selectFirst("div.full-list-info:contains(حالة الأنمي) a")?.text().let {
                when (it ?: "") {
                    "يعرض الان", "يعرض الآن" -> SAnime.ONGOING
                    "مكتمل" -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }

        description = buildString {
            document.selectFirst("div.synopsis-text, div.animepage-synopsis, div.review-content")
                ?.text()
                ?.let { append(it.trim()) }

            document.select("div.animepage-meta-row").forEach { row ->
                val label = row.selectFirst("dt")?.text()?.trim().orEmpty()
                val value = row.selectFirst("dd")?.text()?.trim().orEmpty()
                if (label.isNotBlank() && value.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("$label: $value")
                }
            }
        }.ifBlank { null }
    }

    // ============================== Episodes ==============================
    // Redesign: Livewire compact grid of numbered episode buttons
    override fun episodeListSelector() = "div.ep-compact-grid a.ep-compact-btn, a.ep-compact-btn"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = document.select(episodeListSelector())
        if (episodes.isNotEmpty()) {
            return episodes.map(::episodeFromElement).reversed()
        }
        // Legacy fallback
        return document.select("div.row div.episode-card div.anime-title a")
            .map(::episodeFromElement)
            .reversed()
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val titleAttr = element.attr("title")
        val text = element.text().trim()
        name = titleAttr.ifBlank { text }.ifBlank { "حلقة" }
        episode_number = text.toFloatOrNull()
            ?: titleAttr.substringAfterLast(" ").toFloatOrNull()
            ?: Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        return response.useAsJsoup()
            .select("a.ep-link")
            .parallelCatchingFlatMapBlocking { element ->
                val quality = element.selectFirst("span")?.text().orEmpty().let {
                    when (it) {
                        "HD" -> "720p"
                        "FHD" -> "1080p"
                        "SD" -> "480p"
                        else -> "240p"
                    }
                }
                val url = extractServerUrl(element)
                if (url.isBlank()) emptyList() else extractVideosFromUrl(url, quality, hosterSelection)
            }
    }

    /**
     * Host buttons no longer expose data-src; Alpine uses @click="setServer('url')"
     * (with :class activeUrl fallback). Legacy data-src still accepted.
     */
    private fun extractServerUrl(element: Element): String {
        val candidates = listOf(
            element.attr("@click"),
            element.attr("x-on:click"),
            element.attr("onclick"),
            element.attr(":class"),
            element.attr("data-src"),
        )
        for (raw in candidates) {
            if (raw.isBlank()) continue
            Regex("""setServer\(\s*['"]([^'"]+)['"]\s*\)""").find(raw)?.groupValues?.get(1)?.let {
                return it.replace("&amp;", "&")
            }
            Regex("""activeUrl\s*===\s*['"]([^'"]+)['"]""").find(raw)?.groupValues?.get(1)?.let {
                return it.replace("&amp;", "&")
            }
            if (raw.startsWith("http")) return raw.replace("&amp;", "&")
        }
        return ""
    }

    // Inspirated by JavGuru(all)
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }

    private suspend fun extractVideosFromUrl(url: String, quality: String, selection: Set<String>): List<Video> = when {
        ("https://doo" in url || "dsvplay" in url || "ds2play" in url) && selection.contains("Dood") -> {
            doodExtractor.videoFromUrl(url, "DoodStream - $quality")
                ?.let(::listOf)
        }

        "mp4upload" in url && selection.contains("Mp4upload") -> {
            mp4uploadExtractor.videosFromUrl(url, headers)
        }

        ("ok.ru" in url || "odnoklassniki" in url) && selection.contains("Okru") -> {
            okruExtractor.videosFromUrl(url)
        }

        "voe.sx" in url && selection.contains("Voe") -> {
            voeExtractor.videosFromUrl(url)
        }

        VID_BOM_DOMAINS.any(url::contains) && selection.contains("VidBom") -> {
            vidBomExtractor.videosFromUrl(url)
        }

        else -> null
    }.orEmpty()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
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

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    companion object {
        const val PREFIX_SEARCH = "id:"

        private val VID_BOM_DOMAINS = listOf("vidbam", "vadbam", "vidbom", "vidbm")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")

        private const val PREF_HOSTER_SELECTION_KEY = "pref_hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Enable/Disable hosts"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Dood", "Voe", "Mp4upload", "VidBom", "Okru")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_ENTRIES.toSet() }
    }
}
