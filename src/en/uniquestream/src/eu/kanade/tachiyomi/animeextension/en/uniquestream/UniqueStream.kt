
@file:Suppress("ktlint")

package eu.kanade.tachiyomi.animeextension.en.uniquestream

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * UniqueStream (https://uniquestream.net) — standalone source.
 *
 * The site fully left the DooPlay theme, so this no longer extends the DooPlay
 * theme class. Everything below was reverse-engineered from the live site.
 *
 * VIDEO FLOW (no Cloudflare Turnstile involved — Turnstile is only used for the
 * "report" modal, never for playback):
 *  1. Fetch the movie/episode page with a cache-buster to bypass LiteSpeed cache
 *     and obtain a FRESH logged-out nonce from `var uniquestreamPlayer = {...}`.
 *     (Cached pages serve stale nonces which make the AJAX return HTTP 403 "-1".)
 *  2. POST wp-admin/admin-ajax.php with
 *     { action: "uniquestream_player_ajax", nonce, post, type, nume }
 *     -> { "embed_url": "<iframe ... src=\"//hls.uniquestream.net/local_embed?id=..\">", "type": "iframe" }
 *  3. GET the iframe src (Referer = the movie/episode page) -> HTML containing
 *     `let url = "https://mediacache.cc/.../master.m3u8?st=<token>"`.
 *  4. Resolve the HLS master playlist; variant URIs carry the same ?st= token.
 *
 * SEARCH: `/?s=` is blocked by Cloudflare (403). Search is instead performed via
 * the site genre-filter AJAX endpoint, which accepts a free-text `search` param.
 */
class UniqueStream : ParsedAnimeHttpSource() {

    override val name = "UniqueStream"
    override val baseUrl = "https://uniquestream.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    // The home feed is a single server-rendered grid (~98 cards) with no
    // pagination, so Popular and Latest both read page 1 of the home feed.
    override fun popularAnimeSelector(): String = "article.content-card"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromCard(element)

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesSelector(): String = "article.content-card"

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromCard(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    private fun animeFromCard(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a.card-link")
        val url = link?.attr("abs:href") ?: element.selectFirst("a")?.attr("abs:href").orEmpty()
        anime.setUrlWithoutDomain(url)
        val img = element.selectFirst("img.card-poster-img") ?: element.selectFirst("img")

        val altText = img?.attr("alt")
        val linkText = link?.text()
        anime.title = if (!altText.isNullOrBlank()) altText else if (!linkText.isNullOrBlank()) linkText else "Untitled"

        val srcUrl = img?.attr("abs:src")
        val dataSrcUrl = img?.attr("abs:data-src")
        anime.thumbnail_url = if (!srcUrl.isNullOrBlank()) srcUrl else if (!dataSrcUrl.isNullOrBlank()) dataSrcUrl else null

        return anime
    }

    // =============================== Search ===============================
    // =============================== Search ===============================

    /**
     * Direct search /?s= is Cloudflare-blocked (403). Archive pages (/genre/...)
     * are currently empty. Therefore, search returns the home feed content.
     * Users can browse all available content via Popular or Latest.
     */
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET(baseUrl, headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a.card-link")
        val url = link?.attr("abs:href") ?: element.selectFirst("a")?.attr("abs:href").orEmpty()
        anime.setUrlWithoutDomain(url)
        val img = element.selectFirst("img.card-poster-img") ?: element.selectFirst("img")

        val altText = img?.attr("alt")
        val linkText = link?.text()
        anime.title = if (!altText.isNullOrBlank()) altText else if (!linkText.isNullOrBlank()) linkText else "Untitled"

        val srcUrl = img?.attr("abs:src")
        val dataSrcUrl = img?.attr("abs:data-src")
        anime.thumbnail_url = if (!srcUrl.isNullOrBlank()) srcUrl else if (!dataSrcUrl.isNullOrBlank()) dataSrcUrl else null

        return anime
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // ============================== Details ===============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val article = document.selectFirst("article.movie-content, article.tvshow-content")

        var jsonLd: JsonObject? = null
        for (script in document.select("script[type=application/ld+json]")) {
            try {
                val el = json.parseToJsonElement(script.data()).jsonObject
                val type = el["@type"]?.jsonPrimitive?.content
                if (type == "Movie" || type == "TVSeries" || type == "Series") {
                    jsonLd = el
                    break
                }
            } catch (_: Exception) {}
        }

        val name = jsonLd?.get("name")?.jsonPrimitive?.content
        val docTitle = document.selectFirst(".movie-title")?.text() ?: article?.selectFirst("h1")?.text()
        anime.title = if (!name.isNullOrBlank()) name else if (!docTitle.isNullOrBlank()) docTitle else "Untitled"

        val description = jsonLd?.get("description")?.jsonPrimitive?.content
        val docDesc = document.select(".synopsis-text p").joinToString("\n\n") { it.text() }
        anime.description = if (!description.isNullOrBlank()) description else if (docDesc.isNotBlank()) docDesc else null

        val image = jsonLd?.get("image")?.jsonPrimitive?.content
        val docImg = document.selectFirst("img.card-poster-img, .movie-poster img, img")?.attr("abs:src")
        anime.thumbnail_url = if (!image.isNullOrBlank()) image else docImg

        // Status & genre parsing
        val isTv = article?.selectFirst("span.genre")?.text()?.contains("TV Series", ignoreCase = true) == true
        val statusText = article?.selectFirst(".movie-metadata")?.text()
        anime.status = when {
            statusText?.contains("Completed", ignoreCase = true) == true -> SAnime.COMPLETED
            statusText?.contains("Ongoing", ignoreCase = true) == true -> SAnime.ONGOING
            isTv -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }

        val genres = mutableListOf<String>()
        val genreArray = jsonLd?.get("genre")
        if (genreArray is JsonArray) {
            genreArray.forEach { genres.add(it.jsonPrimitive.content) }
        } else if (genreArray != null) {
            val content = genreArray.jsonPrimitive.content
            if (content.contains(",")) {
                content.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { genres.add(it) }
            } else {
                genres.add(content)
            }
        }

        anime.genre = genres.distinct().joinToString(", ")

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String =
        throw UnsupportedOperationException("Not used because we override episodeListParse.")

    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException("Not used because we override episodeListParse.")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()

        // Movies: a single "episode" pointing at the movie page itself.
        val isTv = url.contains("/tvshows/") ||
            document.selectFirst("article.tvshow-content") != null
        if (!isTv) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(url)
                    name = "Movie"
                    episode_number = 1F
                },
            )
        }

        // TV: episodes are rendered directly on the detail page inside
        // div.season-carousel-panel[data-season-number] > a.ep-card.
        val episodes = mutableListOf<SEpisode>()
        document.select("div.season-carousel-panel").forEach { panel ->
            val season = panel.attr("data-season-number").toIntOrNull() ?: 1
            panel.select("a.ep-card").forEach { card ->
                val badge = card.selectFirst("span.ep-card-badge")?.text()?.trim().orEmpty()
                val epNum = badge.substringAfter("E", "").toFloatOrNull()
                    ?: card.attr("href").substringAfterLast("-").toFloatOrNull()
                    ?: 1F
                val epTitle = card.selectFirst("h3.ep-card-title")?.text()?.ifBlank { null }
                    ?: card.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: "Episode ${epNum.toInt()}"
                episodes.add(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(card.attr("abs:href"))
                        name = "S${season}E${epNum.toInt()} - $epTitle"
                        episode_number = epNum
                    },
                )
            }
        }

        return episodes.reversed()
    }

    // =============================== Video ================================

    override fun videoListSelector(): String =
        throw UnsupportedOperationException("Not used because we override videoListParse.")

    override fun videoFromElement(element: Element): Video =
        throw UnsupportedOperationException("Not used because we override videoListParse.")

    override fun videoUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used because we override videoListParse.")

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()

        // Re-fetch with a cache-buster to bypass LiteSpeed cache and get a fresh
        // nonce; cached renders carry stale nonces that fail with HTTP 403 "-1".
        val freshUrl = pageUrl + (if (pageUrl.contains("?")) "&" else "?") + "_cb=${System.currentTimeMillis()}"
        val document = client.newCall(GET(freshUrl, headers)).execute().asJsoup()
        val html = document.html()

        val nonce = PLAYER_NONCE_REGEX.find(html)?.groupValues?.get(1)
            ?: throw Exception("Playback is currently unavailable: could not read a fresh player nonce from the page. Please retry.")

        val servers = document.select("button.server-btn")
        val videos = mutableListOf<Video>()

        if (servers.isEmpty()) {
            // No server buttons: fall back to the player config own post id.
            val postId = PLAYER_POSTID_REGEX.find(html)?.groupValues?.get(1)
            if (postId != null) {
                videos += fetchServerVideos(nonce, postId, "mv", "1", pageUrl, "Server 1")
            }
        } else {
            servers.forEach { btn ->
                val post = btn.attr("data-post").ifBlank {
                    PLAYER_POSTID_REGEX.find(html)?.groupValues?.get(1) ?: ""
                }
                val type = btn.attr("data-type").ifBlank { "mv" }
                val num = btn.attr("data-num").ifBlank { "1" }
                val label = btn.text().ifBlank { "Server ${videos.size + 1}" }
                if (post.isNotEmpty()) {
                    videos += fetchServerVideos(nonce, post, type, num, pageUrl, label)
                }
            }
        }

        if (videos.isEmpty()) {
            throw Exception(
                "Playback failed: no playable stream was returned by the site. " +
                    "The video servers may be down or the stream token has expired. Please retry.",
            )
        }

        return videos
    }

    private fun fetchServerVideos(
        nonce: String,
        post: String,
        type: String,
        num: String,
        pageUrl: String,
        label: String,
    ): List<Video> {
        try {
            val body = FormBody.Builder()
                .add("action", "uniquestream_player_ajax")
                .add("nonce", nonce)
                .add("post", post)
                .add("type", type)
                .add("nume", num)
                .build()

            val ajaxHeaders = headers.newBuilder()
                .add("Accept", "application/json, text/javascript, *" + "/" + "*; q=0.01")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Origin", baseUrl)
                .add("Referer", pageUrl)
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val ajaxResponse = client.newCall(
                POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body),
            ).execute().body.string().trim()

            if (!ajaxResponse.startsWith("{")) return emptyList() // 403 "-1" / stale nonce

            val embedUrl = json.decodeFromString<EmbedResponse>(ajaxResponse).embedUrl
            var iframeSrc = IFRAME_SRC_REGEX.find(embedUrl)?.groupValues?.get(1) ?: embedUrl
            if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"

            val embedHeaders = headers.newBuilder().add("Referer", pageUrl).build()
            val embedHtml = client.newCall(GET(iframeSrc, embedHeaders)).execute().body.string()

            val m3u8 = M3U8_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return emptyList()

            return playlistUtils.extractFromHls(
                m3u8,
                referer = pageUrl,
                videoNameGen = { quality -> "$label - $quality" }
            )
        } catch (e: Exception) {
            return emptyList()
        }
    }

    // ============================== Filters ===============================

    // No filters/search supported. Use Popular or Latest to browse the ~98 available titles.
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()


    // ============================== Utilities =============================

    @Serializable
    data class EmbedResponse(
        @SerialName("embed_url") val embedUrl: String = "",
    )



    companion object {
        private val PLAYER_NONCE_REGEX = Regex("\"nonce\":\"([a-f0-9]+)\"")
        private val PLAYER_POSTID_REGEX = Regex("\"postId\":\"(\\d+)\"")
        private val IFRAME_SRC_REGEX = Regex("src=\"([^\"]+)\"")
        private val M3U8_REGEX = Regex("let url = \u0027([^\u0027]+)\u0027")
    }
}
