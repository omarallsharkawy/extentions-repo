package eu.kanade.tachiyomi.animeextension.all.supjav

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
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
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder

class SupJav(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SupJav"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private val langPath = when (lang) {
        "en" -> ""
        else -> "/$lang"
    }

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl$langPath/popular/page/$page", headers)

    override fun popularAnimeSelector() = "div.posts div.post"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))

        val img = element.selectFirst("img")
        if (img != null) {
            title = img.attr("alt").ifBlank { img.attr("title") }
            thumbnail_url = getThumbnailUrl(img)
        }
        if (title.isBlank()) {
            title = link.attr("title").ifBlank { element.selectFirst("h3, h2")?.text() ?: "" }
        }

        val duration = element.selectFirst("span.duration, span.time, div.duration, time, .duration, .time")?.text()?.trim()
            ?: element.parent()?.selectFirst("span.duration, span.time, div.duration, time, .duration, .time")?.text()?.trim()
        if (!duration.isNullOrBlank() && title.isNotBlank() && !title.contains(duration)) {
            title = "$title [$duration]"
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination li.active:not(:nth-last-child(2)), div.pagination a.next, div.pagination a[rel=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl$langPath/page/$page", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val path = url.pathSegments.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$path", filters)
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH).trim()
            val url = when {
                id.startsWith("http://") || id.startsWith("https://") -> id
                id.startsWith("/") -> "$baseUrl$id"
                else -> "$baseUrl/$id"
            }
            return client.newCall(GET(url, headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH).trim()
            val url = when {
                id.startsWith("http://") || id.startsWith("https://") -> id
                id.startsWith("/") -> "$baseUrl$id"
                else -> "$baseUrl/$id"
            }
            return GET(url, headers)
        }

        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selected ?: ""
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: ""
        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selected ?: ""

        val pagePath = if (page > 1) "/page/$page" else ""

        val url = when {
            query.isNotBlank() -> {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                "$baseUrl$langPath$pagePath/?s=$encodedQuery"
            }

            category.isNotBlank() -> "$baseUrl$langPath/category/$category$pagePath"

            tag.isNotBlank() -> "$baseUrl$langPath/tag/$tag$pagePath"

            sort == "popular" -> "$baseUrl$langPath/popular$pagePath"

            else -> "$baseUrl$langPath$pagePath/?s="
        }

        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(),
        SortFilter(),
        TagFilter(),
    )

    private open class UriPartFilter(name: String, private val pairs: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String
            get() = pairs[state].second
    }

    private class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("All", ""),
                Pair("Censored", "censored"),
                Pair("Uncensored", "uncensored"),
                Pair("Amateur", "amateur"),
                Pair("English Subtitle", "english-subtitle"),
                Pair("Reduced Price", "reduced-price"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "Sort Order",
            arrayOf(
                Pair("Latest", ""),
                Pair("Popular", "popular"),
            ),
        )

    private class TagFilter :
        UriPartFilter(
            "Media Type / Tag",
            arrayOf(
                Pair("All", ""),
                Pair("Subtitled", "subtitle"),
                Pair("HD", "hd"),
                Pair("VR", "vr"),
            ),
        )

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.content > div.post-meta")
        title = content?.selectFirst("h2")?.text()
            ?: document.selectFirst("div.content > div.post-meta h2")?.text()
            ?: document.selectFirst("h1, h2")?.text()
            ?: ""
        thumbnail_url = getThumbnailUrl(content?.selectFirst("img"))
            ?: getThumbnailUrl(document.selectFirst("div.content img"))

        val cats = content?.selectFirst("div.cats") ?: document.selectFirst("div.cats")
        cats?.run {
            author = select("p:contains(Maker :) > a").textsOrNull()
            artist = select("p:contains(Cast :) > a").textsOrNull()
        }
        genre = (content ?: document).select("div.tags > a").textsOrNull()
        status = SAnime.COMPLETED
    }

    private fun Elements.textsOrNull() = eachText().joinToString().takeUnless(String::isEmpty)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "JAV"
            episode_number = 1F
            url = anime.url
        }

        return listOf(episode)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "JAV"
            episode_number = 1F
            setUrlWithoutDomain(response.request.url.toString())
        }

        return listOf(episode)
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()

        val elements = doc.select("div.btnst a[data-link], div.btns a[data-link], a.btn-server, a[data-link], button[data-link], [data-link], [data-url], ul.nav-tabs a")

        val players = mutableListOf<Pair<String, String>>()
        val seenLinks = mutableSetOf<String>()

        for (el in elements) {
            val link = el.attr("data-link").ifBlank {
                el.attr("data-url").ifBlank {
                    el.attr("href")
                }
            }.trim()

            if (link.isBlank() || link.startsWith("javascript:")) continue
            if (seenLinks.contains(link)) continue
            seenLinks.add(link)

            val rawText = el.text().trim()
            val hoster = parseHosterType(rawText, link)
            players.add(hoster to link)
        }

        if (players.isEmpty()) {
            val iframeElements = doc.select("iframe[src], iframe[data-src], iframe[data-link]")
            for (iframe in iframeElements) {
                val src = iframe.attr("data-src").ifBlank {
                    iframe.attr("src").ifBlank {
                        iframe.attr("data-link")
                    }
                }.trim()

                if (src.isBlank() || src.startsWith("javascript:") || seenLinks.contains(src)) continue
                seenLinks.add(src)

                val hoster = parseHosterType("", src)
                players.add(hoster to src)
            }
        }

        if (players.isEmpty()) {
            val postLinks = doc.select("div.post-content a[href], div.content a[href]")
            for (a in postLinks) {
                val href = a.attr("href").trim()
                if (href.isBlank() || href.startsWith("javascript:") || seenLinks.contains(href)) continue
                val hoster = parseHosterType(a.text(), href)
                seenLinks.add(href)
                players.add(hoster to href)
            }
        }

        return players.parallelCatchingFlatMapBlocking(::videosFromPlayer)
    }

    private fun parseHosterType(text: String, link: String): String {
        val cleanedText = text.replace(Regex("(?i)^server\\s*:?\\s*"), "").trim().uppercase()

        when {
            cleanedText.contains("ST") || cleanedText.contains("STREAMTAPE") || cleanedText.contains("TAPE") -> return "ST"
            cleanedText.contains("VOE") -> return "VOE"
            cleanedText.contains("FST") || cleanedText.contains("STREAMWISH") || cleanedText.contains("WISH") || cleanedText.contains("FC2") -> return "FST"
            cleanedText.contains("TV") || cleanedText.contains("TURBO") -> return "TV"
            cleanedText.contains("DOOD") || cleanedText.contains("DS2PLAY") -> return "DOOD"
            cleanedText.contains("MIX") || cleanedText.contains("DROP") -> return "MIXDROP"
            cleanedText.contains("UQLOAD") || cleanedText.contains("UQ") -> return "UQLOAD"
            cleanedText.contains("VIDHIDE") || cleanedText.contains("HIDE") -> return "VIDHIDE"
        }

        val linkLower = link.lowercase()
        val revLinkLower = runCatching { link.reversed().lowercase() }.getOrDefault("")
        val combined = "$linkLower $revLinkLower"

        return when {
            combined.contains("streamtape") || combined.contains("sttape") -> "ST"
            combined.contains("voe.") || combined.contains("voe-un-block") -> "VOE"
            combined.contains("streamwish") || combined.contains("filelions") || combined.contains("embedwish") || combined.contains("fc2stream") -> "FST"
            combined.contains("dood") || combined.contains("ds2play") || combined.contains("d0000d") -> "DOOD"
            combined.contains("mixdrop") -> "MIXDROP"
            combined.contains("uqload") -> "UQLOAD"
            combined.contains("vidhide") -> "VIDHIDE"
            combined.contains("supjav.php") || combined.contains("/tv/") || combined.contains(".m3u8") -> "TV"
            cleanedText.isNotBlank() -> cleanedText
            else -> "UNKNOWN"
        }
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidhideExtractor by lazy { VidHideExtractor(client, headers) }

    private val protectorHeaders by lazy {
        super.headersBuilder().set("referer", "$PROTECTOR_URL/").build()
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private suspend fun resolveProtectorUrl(rawLink: String): String? {
        if (rawLink.isBlank()) return null

        val decoded = when {
            rawLink.startsWith("http://") || rawLink.startsWith("https://") ||
                rawLink.startsWith("supjav.php") || rawLink.startsWith("/supjav.php") ||
                rawLink.startsWith("c=") || rawLink.startsWith("l=") -> rawLink

            else -> {
                val rev = rawLink.reversed()
                if (rev.startsWith("http://") || rev.startsWith("https://") ||
                    rev.startsWith("supjav.php") || rev.startsWith("/supjav.php") ||
                    rev.startsWith("c=") || rev.startsWith("l=")
                ) {
                    rev
                } else {
                    rawLink
                }
            }
        }.trim()

        if (decoded.isBlank()) return null

        if ((decoded.startsWith("http://") || decoded.startsWith("https://")) &&
            !decoded.contains("supjav.php") && !decoded.contains("supremejav.com")
        ) {
            return decoded
        }

        val targetUrls = mutableListOf<String>()
        when {
            decoded.startsWith("http://") || decoded.startsWith("https://") -> {
                targetUrls.add(decoded)
            }

            decoded.startsWith("/") -> {
                targetUrls.add("$PROTECTOR_URL$decoded")
            }

            decoded.startsWith("supjav.php") -> {
                targetUrls.add("$PROTECTOR_URL/$decoded")
            }

            decoded.startsWith("c=") || decoded.startsWith("l=") -> {
                targetUrls.add("$PROTECTOR_URL/supjav.php?$decoded")
            }

            else -> {
                targetUrls.add("$PROTECTOR_URL/supjav.php?c=$decoded")
                targetUrls.add("$PROTECTOR_URL/supjav.php?l=$decoded")
            }
        }

        for (targetUrl in targetUrls) {
            runCatching {
                val response = noRedirectClient.newCall(GET(targetUrl, protectorHeaders)).await()
                val location = response.use { res ->
                    res.headers["location"] ?: res.headers["Location"] ?: run {
                        val body = res.bodyString()
                        Regex("""(?:href|url)=["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE)
                            .find(body)?.groupValues?.get(1)
                    }
                }
                if (!location.isNullOrBlank()) {
                    return when {
                        location.startsWith("http://") || location.startsWith("https://") -> location
                        location.startsWith("//") -> "https:$location"
                        location.startsWith("/") -> "$PROTECTOR_URL$location"
                        else -> "$PROTECTOR_URL/$location"
                    }
                }
            }
        }

        return null
    }

    private suspend fun videosFromPlayer(player: Pair<String, String>): List<Video> {
        val (hoster, rawLink) = player
        val url = resolveProtectorUrl(rawLink) ?: return emptyList()

        val hosterUpper = hoster.uppercase()

        val primaryVideos = runCatching {
            when {
                hosterUpper.contains("ST") || hosterUpper.contains("STREAMTAPE") || url.contains("streamtape") ->
                    streamtapeExtractor.videosFromUrl(url)

                hosterUpper.contains("VOE") || url.contains("voe") ->
                    voeExtractor.videosFromUrl(url)

                hosterUpper.contains("FST") || hosterUpper.contains("STREAMWISH") || hosterUpper.contains("FC2") || url.contains("streamwish") || url.contains("fc2") ->
                    streamwishExtractor.videosFromUrl(url)

                hosterUpper.contains("TV") || hosterUpper.contains("TURBO") || url.contains("turbovid") ->
                    videosFromTurboVid(url)

                hosterUpper.contains("DOOD") || url.contains("dood") || url.contains("ds2play") || url.contains("d0000d") ->
                    doodExtractor.videosFromUrl(url)

                hosterUpper.contains("MIX") || hosterUpper.contains("DROP") || url.contains("mixdrop") ->
                    mixdropExtractor.videosFromUrl(url)

                hosterUpper.contains("UQLOAD") || hosterUpper.contains("UQ") || url.contains("uqload") ->
                    uqloadExtractor.videosFromUrl(url)

                hosterUpper.contains("VIDHIDE") || hosterUpper.contains("HIDE") || url.contains("vidhide") ->
                    vidhideExtractor.videosFromUrl(url)

                else -> emptyList()
            }
        }.getOrElse { emptyList() }

        if (primaryVideos.isNotEmpty()) {
            return primaryVideos
        }

        val turboVidFallback = videosFromTurboVid(url)
        if (turboVidFallback.isNotEmpty()) {
            return turboVidFallback
        }

        val streamWishFallback = videosFromStreamWishLike(url)
        if (streamWishFallback.isNotEmpty()) {
            return streamWishFallback
        }

        return genericEmbedExtractor(url, hoster)
    }

    private suspend fun videosFromTurboVid(url: String): List<Video> {
        return try {
            if (url.endsWith(".m3u8") || url.contains(".m3u8?")) {
                return playlistUtils.extractFromHls(url, url, videoNameGen = { "TurboVid - $it" })
                    .distinctBy { it.videoUrl }
            }
            val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
            var playlistUrl = body.substringAfter("var urlPlay = '", "")
                .substringBefore("';")
                .takeUnless(String::isEmpty)
            if (playlistUrl == null) {
                playlistUrl = Regex("""https?://[^\s'"<>]+\.m3u8[^\s'"<>]*""").find(body)?.value
            }
            if (playlistUrl != null) {
                playlistUtils.extractFromHls(playlistUrl, url, videoNameGen = { "TurboVid - $it" })
                    .distinctBy { it.videoUrl }
            } else {
                emptyList()
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private suspend fun videosFromStreamWishLike(url: String): List<Video> = try {
        streamwishExtractor.videosFromUrl(url)
    } catch (e: Throwable) {
        emptyList()
    }

    private suspend fun genericEmbedExtractor(url: String, hoster: String): List<Video> {
        return try {
            if (url.contains(".m3u8")) {
                return playlistUtils.extractFromHls(url, referer = url, videoNameGen = { "$hoster - $it" })
            }
            val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
            val m3u8Match = Regex("""https?://[^\s'"<>]+\.m3u8[^\s'"<>]*""").find(body)?.value
            if (m3u8Match != null) {
                val videos = playlistUtils.extractFromHls(m3u8Match, referer = url, videoNameGen = { "$hoster - $it" })
                if (videos.isNotEmpty()) return videos
            }

            val mp4Match = Regex("""https?://[^\s'"<>]+\.mp4[^\s'"<>]*""").find(body)?.value
            if (mp4Match != null) {
                return listOf(Video(mp4Match, "$hoster - MP4", mp4Match))
            }

            emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private fun getThumbnailUrl(img: Element?): String? {
        if (img == null) return null
        val src = img.attr("data-original").ifBlank {
            img.attr("data-src").ifBlank {
                img.attr("src")
            }
        }
        if (src.isBlank()) return null
        return when {
            src.startsWith("//") -> "https:$src"

            src.startsWith("http://") || src.startsWith("https://") -> src

            else -> img.absUrl("data-original").ifBlank {
                img.absUrl("data-src").ifBlank {
                    img.absUrl("src")
                }
            }.let { if (it.startsWith("//")) "https:$it" else it }
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"

        private val SUPPORTED_PLAYERS = setOf("TV", "FST", "VOE", "ST", "DOOD", "MIXDROP", "UQLOAD", "VIDHIDE")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
