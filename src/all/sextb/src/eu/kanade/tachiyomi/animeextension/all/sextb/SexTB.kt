package eu.kanade.tachiyomi.animeextension.all.sextb

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class SexTB : ParsedAnimeHttpSource() {

    override val name = "SexTB"

    override val baseUrl = "https://sextb.net"

    override val lang = "all"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

    // Extractors
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidhideExtractor by lazy { VidHideExtractor(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // Popular Anime
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/page/$page/", headers)

    override fun popularAnimeSelector(): String = "div.video-item, div.post-item, div.item, article.post"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]") ?: element
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst("h2, h3, .title, .entry-title")?.text()
            ?.ifBlank { null } ?: link.attr("title").ifBlank { link.text() }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty {
                img.attr("data-lazy-src").ifEmpty {
                    img.attr("src")
                }
            }
        }
    }

    override fun popularAnimeNextPageSelector(): String? = "a.next, .pagination a.next, a[rel=next]"

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    // Search Anime
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = if (page > 1) {
                "$baseUrl/page/$page/?s=$query"
            } else {
                "$baseUrl/?s=$query"
            }
            return GET(url, headers)
        }

        var studioSlug = ""
        var actressSlug = ""

        for (filter in filters) {
            when (filter) {
                is StudioFilter -> studioSlug = filter.selected
                is ActressFilter -> actressSlug = filter.selected
                else -> {}
            }
        }

        return when {
            studioSlug.isNotEmpty() -> {
                val url = if (page > 1) {
                    "$baseUrl/studio/$studioSlug/page/$page/"
                } else {
                    "$baseUrl/studio/$studioSlug/"
                }
                GET(url, headers)
            }

            actressSlug.isNotEmpty() -> {
                val url = if (page > 1) {
                    "$baseUrl/actress/$actressSlug/page/$page/"
                } else {
                    "$baseUrl/actress/$actressSlug/"
                }
                GET(url, headers)
            }

            else -> {
                latestUpdatesRequest(page)
            }
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

    // Filters
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        StudioFilter(),
        ActressFilter(),
    )

    class StudioFilter :
        AnimeFilter.Select<String>(
            "Studio",
            STUDIOS.map { it.first }.toTypedArray(),
        ) {
        val selected get() = STUDIOS[state].second

        companion object {
            val STUDIOS = listOf(
                Pair("All", ""),
                Pair("S1", "s1-no-1-style-4qcwwbhb"),
                Pair("Moodyz", "moodyz-hpqrql0f"),
                Pair("Idea Pocket", "idea-pocket-sip65bhm"),
                Pair("Soft On Demand", "sod-create-hqtvjs77"),
                Pair("MUTEKI", "muteki-yej59j2k"),
                Pair("Premium", "premium-0w0gfxb5"),
                Pair("IPX", "ipx"),
                Pair("Attackers", "attackers-3t7al8uh"),
                Pair("Prestige", "prestige-v6k9075j"),
                Pair("Oppai", "oppai-fdt1v3fn"),
                Pair("Kawaii", "kawaii-d073oxh7"),
            )
        }
    }

    class ActressFilter :
        AnimeFilter.Select<String>(
            "Actress",
            ACTRESSES.map { it.first }.toTypedArray(),
        ) {
        val selected get() = ACTRESSES[state].second

        companion object {
            val ACTRESSES = listOf(
                Pair("All", ""),
                Pair("Yumi Kazama", "th1bdtq81"),
                Pair("Yui Hatano", "6273dbs6j"),
                Pair("Meari Tachibana", "kn257tc95"),
                Pair("Yu Shinoda", "yu-shinoda"),
                Pair("Hana Haruna", "if68a06ll"),
                Pair("Ririko Kinoshita", "1fjvbdte7"),
                Pair("Mina Kitano", "mina-kitano"),
                Pair("Ai Sayama", "ai-sayama"),
                Pair("Eimi Fukada", "eimi-fukada"),
            )
        }
    }

    // Anime Details
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.entry-title, h1.title, h1")?.text().orEmpty()
        genre = document.select("a[rel=tag], .genres a, .tags a").joinToString { it.text() }
        description = document.selectFirst(".entry-content p, .description p")?.text()
        thumbnail_url = document.selectFirst(".poster img, .entry-content img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
    }

    // Episodes
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Episode"
            episode_number = 1F
            url = anime.url
        }
        return listOf(episode)
    }

    override fun episodeListSelector(): String = "div.tray-item-description"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        name = "Episode"
        setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Episode"
            episode_number = 1F
            url = response.request.url.encodedPath
        }
        return listOf(episode)
    }

    // Video URLs / Player Server Extraction
    override fun videoListSelector(): String = "button.btn-player.episode, button.btn-download-free, a.btn-player, a[data-url]"

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Using videoListParse directly")

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        var elements = document.select(videoListSelector())

        if (elements.isEmpty()) {
            // Secondary selector fallback
            elements = document.select("iframe[src], a[href*=embed], a[data-src], a[data-embed]")
        }

        if (elements.isEmpty()) {
            return emptyList()
        }

        return extractVideosFromElements(elements)
    }

    private fun extractVideosFromElements(elements: Elements): List<Video> {
        val extractedData = elements.mapNotNull { element ->
            val rawUrl = element.attr("data-url").ifEmpty {
                element.attr("data-embed").ifEmpty {
                    element.attr("data-src").ifEmpty {
                        element.attr("href").ifEmpty {
                            element.attr("src").ifEmpty {
                                element.attr("value")
                            }
                        }
                    }
                }
            }.trim()

            if (rawUrl.isEmpty() || rawUrl == "#" || rawUrl.startsWith("javascript:")) {
                return@mapNotNull null
            }

            val url = when {
                rawUrl.startsWith("//") -> "https:$rawUrl"

                rawUrl.startsWith("/") -> "$baseUrl$rawUrl"

                !rawUrl.startsWith("http://") && !rawUrl.startsWith("https://") -> {
                    element.attr("abs:data-url").ifEmpty {
                        element.attr("abs:href").ifEmpty {
                            element.attr("abs:src").ifEmpty {
                                "$baseUrl/$rawUrl"
                            }
                        }
                    }
                }

                else -> rawUrl
            }

            val elementText = element.text().trim()
            val dataServer = element.attr("data-server").ifEmpty {
                element.attr("data-name").ifEmpty {
                    element.attr("title")
                }
            }.trim()

            val label = determineServerLabel(url, elementText, dataServer)
            Triple(label, url, elementText)
        }.distinctBy { it.second }

        val videos = extractedData.parallelCatchingFlatMapBlocking { (label, url, _) ->
            extractVideosForServer(label, url)
        }

        // CRITICAL REQUIREMENT: Prioritize SW over TB (and order servers according to priority score)
        return videos.sortedWith(
            compareBy { video ->
                getServerPriorityFromQuality(video.quality)
            },
        )
    }

    private fun determineServerLabel(url: String, text: String, dataServer: String): String {
        val lowerUrl = url.lowercase()
        val combinedText = "$text $dataServer".uppercase()

        return when {
            // SW: StreamWish / FastStream
            lowerUrl.contains("streamwish") || lowerUrl.contains("strwish") ||
                lowerUrl.contains("embedwish") || lowerUrl.contains("faststream") ||
                lowerUrl.contains("swish") || lowerUrl.contains("niramirus") ||
                lowerUrl.contains("medixiru") || combinedText.contains("STREAMWISH") ||
                combinedText.contains("FASTSTREAM") || combinedText.contains("SW") -> "SW"

            // FL: FileLions
            lowerUrl.contains("filelions") || lowerUrl.contains("lion") ||
                combinedText.contains("FILELIONS") || combinedText.contains("FL") -> "FL"

            // DD: DoodStream
            lowerUrl.contains("dood") || lowerUrl.contains("ds2play") ||
                lowerUrl.contains("dooood") || lowerUrl.contains("d0000d") ||
                combinedText.contains("DOOD") || combinedText.contains("DD") -> "DD"

            // US: Uqload
            lowerUrl.contains("uqload") || combinedText.contains("UQLOAD") ||
                combinedText.contains("US") -> "US"

            // PP: StreamP2P / VidHide
            lowerUrl.contains("streamp2p") || lowerUrl.contains("vidhide") ||
                lowerUrl.contains("streamhide") || lowerUrl.contains("p2p") ||
                lowerUrl.contains("guccihide") || combinedText.contains("STREAMP2P") ||
                combinedText.contains("VIDHIDE") || combinedText.contains("PP") -> "PP"

            // TB: SexTB Default
            lowerUrl.contains("sextb") || combinedText.contains("SEXTB") ||
                combinedText.contains("TB") -> "TB"

            else -> if (text.isNotBlank()) text else "TB"
        }
    }

    private suspend fun extractVideosForServer(label: String, url: String): List<Video> = runCatching {
        when (label) {
            "SW" -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish - $it" })

            "FL" -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "FileLions - $it" })

            "DD" -> doodExtractor.videosFromUrl(url, quality = "DoodStream")

            "US" -> uqloadExtractor.videosFromUrl(url, prefix = "Uqload")

            "PP" -> vidhideExtractor.videosFromUrl(url, videoNameGen = { "VidHide - $it" })

            "TB" -> extractSexTBVideo(url)

            else -> {
                when {
                    url.contains("streamwish") || url.contains("strwish") || url.contains("embedwish") || url.contains("faststream") ->
                        streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish - $it" })

                    url.contains("filelions") || url.contains("lion") ->
                        streamwishExtractor.videosFromUrl(url, videoNameGen = { "FileLions - $it" })

                    url.contains("dood") || url.contains("ds2play") || url.contains("dooood") ->
                        doodExtractor.videosFromUrl(url, quality = "DoodStream")

                    url.contains("uqload") ->
                        uqloadExtractor.videosFromUrl(url, prefix = "Uqload")

                    url.contains("vidhide") || url.contains("streamhide") || url.contains("streamp2p") || url.contains("p2p") ->
                        vidhideExtractor.videosFromUrl(url, videoNameGen = { "VidHide - $it" })

                    else -> extractSexTBVideo(url)
                }
            }
        }
    }.getOrElse { emptyList() }

    private fun extractSexTBVideo(url: String): List<Video> {
        if (url.contains(".m3u8")) {
            return playlistUtils.extractFromHls(url, videoNameGen = { "SexTB - $it" })
        }

        return runCatching {
            val response = client.newCall(GET(url, headers)).execute()
            val doc = response.asJsoup()

            val scriptData = doc.select("script").map { it.data() }.firstOrNull { it.contains("m3u8") }
            val m3u8Url = scriptData?.let {
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(it)?.value
            } ?: doc.selectFirst("source[src*=m3u8], video source[src]")?.attr("src")

            if (!m3u8Url.isNullOrEmpty()) {
                playlistUtils.extractFromHls(m3u8Url, videoNameGen = { "SexTB - $it" })
            } else {
                val mp4Url = doc.selectFirst("source[src*=mp4], video source[src*=mp4]")?.attr("src")
                if (!mp4Url.isNullOrEmpty()) {
                    listOf(Video(mp4Url, "SexTB - Default", mp4Url, headers = headers))
                } else {
                    emptyList()
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun getServerPriorityFromQuality(quality: String): Int = when {
        quality.contains("StreamWish", ignoreCase = true) || quality.contains("SW", ignoreCase = true) || quality.contains("FastStream", ignoreCase = true) -> 1
        quality.contains("FileLions", ignoreCase = true) || quality.contains("FL", ignoreCase = true) -> 2
        quality.contains("Dood", ignoreCase = true) || quality.contains("DD", ignoreCase = true) -> 3
        quality.contains("Uqload", ignoreCase = true) || quality.contains("US", ignoreCase = true) -> 4
        quality.contains("VidHide", ignoreCase = true) || quality.contains("StreamP2P", ignoreCase = true) || quality.contains("PP", ignoreCase = true) -> 5
        quality.contains("SexTB", ignoreCase = true) || quality.contains("TB", ignoreCase = true) -> 6
        else -> 7
    }
}
