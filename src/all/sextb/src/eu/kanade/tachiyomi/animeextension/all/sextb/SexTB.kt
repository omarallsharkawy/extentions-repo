package eu.kanade.tachiyomi.animeextension.all.sextb

import android.util.Base64
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.bodyString
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SexTB : ParsedAnimeHttpSource() {

    override val name = "SexTB"

    override val baseUrl = "https://sextb.net"

    override val lang = "all"

    override val supportsLatest = true

    /*
     * Keep the app-provided Android User-Agent and cookie jar. Forcing a
     * desktop UA here makes Cloudflare clearance cookies incompatible with
     * the WebView session used by Aniyomi's NetworkHelper.
     */
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val resolvedCatalogPaths = ConcurrentHashMap(CURRENT_CATALOG_PATHS)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = catalogRequest(
        page = page,
        categoryPath = DEFAULT_CATEGORY,
        sort = "viewed",
    )

    override fun popularAnimeSelector(): String = "div.tray-content > div.tray-item, div.tray-item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]") ?: element
        setUrlWithoutDomain(link.attr("href"))

        val image: Element? = element.selectFirst("img")
        title = element.selectFirst("div.tray-item-title")?.text()
            ?.ifBlank { image?.attr("alt") }
            ?: image?.attr("alt").orEmpty()

        if (image != null) {
            thumbnail_url = normalizeUrl(
                image.attr("data-src")
                    .ifBlank { image.attr("src") }
                    .ifBlank { image.absUrl("data-src") }
                    .ifBlank { image.absUrl("src") },
            )
        }

        if (title.isBlank()) {
            title = link.attr("title").ifBlank { element.text() }
        }

        val code = element.selectFirst("div.tray-item-code")?.text()?.trim()
        val duration = element.selectFirst("div.tray-item-runtime")?.text()?.trim()
        if (!code.isNullOrBlank() && !title.contains(code, ignoreCase = true)) {
            title = "[$code] $title"
        }
        if (!duration.isNullOrBlank()) {
            title = "$title [$duration]"
        }
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:has(a.current) + li a, ul.pagination li.active + li a, ul.pagination a[rel=next], ul.pagination li.next a, a[rel=next]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        response.rememberCatalogPath()
        return super.popularAnimeParse(response)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(
        page = page,
        categoryPath = DEFAULT_CATEGORY,
        sort = "desc",
    )

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        response.rememberCatalogPath()
        return super.latestUpdatesParse(response)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("search")
                .addPathSegment(query.trim())
                .apply {
                    if (page > 1) addQueryParameter("page", page.toString())
                }
                .build()
            return GET(url, headers)
        }

        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selected ?: DEFAULT_CATEGORY
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected ?: "all"
        val studio = filters.filterIsInstance<StudioFilter>().firstOrNull()?.selected ?: "all"
        val quality = filters.filterIsInstance<QualityFilter>().firstOrNull()?.selected ?: "all"
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.selected ?: "all"
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: "desc"

        return catalogRequest(page, category, genre, studio, quality, year, sort)
    }

    private fun catalogRequest(
        page: Int,
        categoryPath: String,
        genre: String = "all",
        studio: String = "all",
        quality: String = "all",
        year: String = "all",
        sort: String,
    ): Request {
        val resolvedPath = resolvedCatalogPaths[categoryPath] ?: categoryPath
        val pagedPath = if (page > 1) "$resolvedPath/pg-${page.coerceAtLeast(1)}" else resolvedPath
        val url = "$baseUrl$pagedPath".toHttpUrl().newBuilder()
            .apply {
                if (genre != "all") addQueryParameter("genre", genre)
                if (studio != "all") addQueryParameter("studio", studio)
                if (quality != "all") addQueryParameter("quality", quality)
                if (year != "all") addQueryParameter("year", year)
                if (sort != "desc") addQueryParameter("sort", sort)
            }
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        response.rememberCatalogPath()
        return super.searchAnimeParse(response)
    }

    // =============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
        GenreFilter(),
        StudioFilter(),
        QualityFilter(),
        YearFilter(),
        SortFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        private val entries: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, entries.map { it.first }.toTypedArray()) {
        val selected get() = entries[state].second
    }

    class CategoryFilter : UriPartFilter("Category", CATEGORIES)
    class GenreFilter : UriPartFilter("Genre", GENRES)
    class StudioFilter : UriPartFilter("Studio", STUDIOS)
    class QualityFilter : UriPartFilter("Quality", QUALITIES)
    class YearFilter : UriPartFilter("Year", YEARS)
    class SortFilter : UriPartFilter("Sort By", SORTS)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        document.throwIfCloudflareChallenge()

        title = document.selectFirst("h1.film-info-title, h1")?.text().orEmpty()
        val thumbnailElement: Element? = document.selectFirst("#poster img, div.col-5 img, img[itemprop=image]")
        if (thumbnailElement != null) {
            thumbnail_url = normalizeUrl(
                thumbnailElement.attr("data-src").ifBlank { thumbnailElement.attr("src") },
            )
        }
        author = document.metadataLinks("Director:")
        artist = document.metadataLinks("Cast(s):", "Cast:")
        genre = document.metadataLinks("Genre(s):", "Genre:")
        val descriptionElement: Element? =
            document.selectFirst(".text-desc-container, .full-text-desc, meta[name=description]")
        val synopsis = if (descriptionElement?.tagName() == "meta") {
            descriptionElement.attr("content")
        } else {
            descriptionElement?.text()
        }?.takeIf(String::isNotBlank)
        description = buildList {
            synopsis?.let(::add)
            document.metadataText("Label:")?.let { add("Label: $it") }
            document.metadataText("Studio:")?.let { add("Studio: $it") }
            document.metadataText("Quality:")?.let { add("Quality: $it") }
            document.metadataText("Release Date:")?.let { add("Release Date: $it") }
            document.metadataText("Runtimes:", "Runtime:")?.let { add("Runtime: $it") }
        }.joinToString("\n").takeIf(String::isNotBlank)
        status = SAnime.COMPLETED
    }

    private fun Document.metadataLinks(vararg labels: String): String? = select("div.description")
        .firstOrNull { element -> labels.any { label -> element.text().contains(label, ignoreCase = true) } }
        ?.select("a")
        ?.joinToString { it.text() }
        ?.takeIf(String::isNotBlank)

    private fun Document.metadataText(vararg labels: String): String? = select("div.description")
        .firstOrNull { element -> labels.any { label -> element.text().startsWith(label, ignoreCase = true) } }
        ?.text()
        ?.let { text ->
            labels.firstNotNullOfOrNull { label ->
                text.takeIf { it.startsWith(label, ignoreCase = true) }?.substring(label.length)?.trim()
            }
        }
        ?.takeIf(String::isNotBlank)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            episode_number = 1F
            url = anime.url
        },
    )

    override fun episodeListSelector(): String = "div.film-info"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        response.throwIfCloudflareChallenge()
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = response.request.url.encodedPath
            },
        )
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        response.throwIfCloudflareChallenge()
        val document = response.asJsoup()
        document.throwIfCloudflareChallenge()

        val playerButtons = document.select("button.btn-player.episode[data-id], button.btn-player[data-id]:not(.vip)")
            .distinctBy { it.attr("data-id") }
        if (playerButtons.isEmpty()) {
            throw IOException("SexTB returned no server buttons. Refresh the video page and retry.")
        }

        val pageHtml = document.html()
        val initialToken = PT_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        val initialKey = PK_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        val fallbackFilmId = FILM_ID_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        if (initialToken.isBlank() || initialKey.isBlank()) {
            throw IOException("SexTB player tokens are missing. Open this video in WebView once, then retry.")
        }

        var token = initialToken
        var key = initialKey
        val referer = response.request.url.toString()
        val resolvedPlayers = mutableListOf<PlayerTarget>()

        for (button in playerButtons) {
            val episodeId = button.attr("data-id").trim()
            val filmId = button.attr("data-source").ifBlank { fallbackFilmId }.trim()
            if (episodeId.isBlank() || filmId.isBlank()) continue

            val payload = fetchPlayerPayload(episodeId, filmId, token, referer) ?: continue
            val decrypted = xorDecrypt(payload.encryptedPlayer, key)
            if (decrypted.isBlank()) continue

            token = payload.nextToken.ifBlank { token }
            key = payload.nextKey.ifBlank { key }

            val label = normalizeServerLabel(button.text())
            extractPlayerUrls(decrypted, referer).forEach { url ->
                resolvedPlayers += PlayerTarget(label, url, referer)
            }
        }

        if (resolvedPlayers.isEmpty()) {
            throw IOException("SexTB could not resolve any server. Open the video in WebView once, then retry.")
        }

        val prioritizedPlayers = resolvedPlayers
            .distinctBy { it.url }
            .sortedBy { serverPriority(it.label) }

        val videos = prioritizedPlayers
            .flatMap { extractVideosBlocking(it, mutableSetOf(), 0) }
            .distinctBy { it.videoUrl }
            .sortedBy { serverPriority(it.quality) }

        if (videos.isEmpty()) {
            throw IOException("SexTB servers were found, but none returned a playable stream. Retry or open the video in WebView once.")
        }
        return videos
    }

    /**
     * Keep the legacy videoListParse path synchronous. Some host apps load
     * minified extensions with a different Kotlin coroutine runtime, which
     * turns runBlocking/suspend lambdas into a Function2 ClassCastException.
     * Returning the master HLS URL is sufficient for ExoPlayer and avoids
     * that runtime boundary entirely.
     */
    private fun extractVideosBlocking(
        target: PlayerTarget,
        visited: MutableSet<String>,
        depth: Int,
    ): List<Video> {
        if (depth > MAX_PLAYER_DEPTH || !visited.add(target.url)) return emptyList()
        val lowerUrl = target.url.lowercase()
        val label = normalizeServerLabel(target.label, lowerUrl)
        val videoHeaders = headersFor(target.referer)

        if (".m3u8" in lowerUrl) {
            return listOf(Video(target.url, "$label - HLS", target.url, headers = videoHeaders))
        }
        if (lowerUrl.substringBefore('?').endsWith(".mp4")) {
            return listOf(Video(target.url, "$label - MP4", target.url, headers = videoHeaders))
        }

        val extractorVideos = runCatching {
            when (label) {
                "DD" -> doodExtractor.videosFromUrl(target.url, quality = "DD")
                "ST" -> streamTapeExtractor.videosFromUrl(target.url)
                "MD" -> mixDropExtractor.videosFromUrl(target.url)
                "UPN", "US" -> extractUpnVideosBlocking(target)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        if (extractorVideos.isNotEmpty()) return extractorVideos

        return runCatching {
            client.newCall(GET(target.url, videoHeaders)).execute().use { response ->
                response.throwIfCloudflareChallenge()
                val finalUrl = response.request.url.toString()
                val body = response.body.string().replace("\\/", "/").replace("\\u0026", "&")
                val document = Jsoup.parse(body, finalUrl)
                val scripts = document.select("script").joinToString("\n") { script ->
                    val data = script.data()
                    if ("eval(function(p,a,c" in data) autoUnpacker(data) ?: data else data
                }
                val rawPlaylist = M3U8_URL_REGEX.find(scripts)?.value
                    ?: document.selectFirst("[data-hash*=m3u8]")?.attr("data-hash")
                    ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
                val playlist = rawPlaylist?.let { normalizePlayerUrl(it, finalUrl) }
                if (playlist != null) {
                    return@use listOf(
                        Video(
                            playlist,
                            "$label - HLS",
                            playlist,
                            headers = headersFor(finalUrl),
                        ),
                    )
                }

                val nested = extractPlayerUrls(body, finalUrl)
                    .firstOrNull { it != target.url && it.startsWith("http") }
                    ?: return@use emptyList()
                extractVideosBlocking(
                    PlayerTarget(normalizeServerLabel(label, nested.lowercase()), nested, finalUrl),
                    visited,
                    depth + 1,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun extractUpnVideosBlocking(target: PlayerTarget): List<Video> {
        val playerUrl = target.url.toHttpUrlOrNull() ?: return emptyList()
        val videoId = playerUrl.fragment?.substringBefore('&')?.trim().orEmpty()
        if (videoId.isBlank()) return emptyList()
        val playerPageUrl = playerUrl.newBuilder().fragment(null).query(null).build().toString()
        val playerHeaders = headersFor(playerPageUrl)
        val referrerHost = target.referer.toHttpUrlOrNull()?.host.orEmpty()
        val apiUrl = playerUrl.newBuilder()
            .encodedPath("/api/v1/video")
            .query(null)
            .fragment(null)
            .addQueryParameter("id", videoId)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1080")
            .apply { if (referrerHost.isNotBlank()) addQueryParameter("r", referrerHost) }
            .build()
        val encrypted = client.newCall(GET(apiUrl, playerHeaders)).execute().use {
            it.throwIfCloudflareChallenge()
            it.body.string().trim()
        }
        val payload = decryptUpnPayload(encrypted) ?: return emptyList()
        val data = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        val playlist = listOf("cfNative", "source", "cf")
            .map { data.optString(it).trim() }
            .firstOrNull { it.startsWith("http", true) && ".m3u8" in it.lowercase() }
            ?: return emptyList()
        return listOf(Video(playlist, "US - HLS", playlist, headers = playerHeaders))
    }

    private fun normalizePlayerUrl(rawUrl: String, pageUrl: String): String? {
        val clean = rawUrl.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> pageUrl.toHttpUrlOrNull()?.resolve(clean)?.toString()
        }
    }

    private fun fetchPlayerPayload(
        episodeId: String,
        filmId: String,
        token: String,
        referer: String,
    ): PlayerPayload? {
        val form = FormBody.Builder()
            .add("episode", episodeId)
            .add("filmId", filmId)
            .add("pt", token)
            .build()
        val ajaxHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("Origin", baseUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        return try {
            client.newCall(POST("$baseUrl/ajax/player", ajaxHeaders, form)).execute().use { ajaxResponse ->
                ajaxResponse.throwIfCloudflareChallenge()
                val json = JSONObject(ajaxResponse.body.string())
                if (json.optBoolean("error", false)) return null
                PlayerPayload(
                    encryptedPlayer = json.optString("player_enc"),
                    nextToken = json.optString("next_pt"),
                    nextKey = json.optString("next_pk"),
                ).takeIf { it.encryptedPlayer.isNotBlank() }
            }
        } catch (e: CloudflareBlockedException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun xorDecrypt(encoded: String, key: String): String {
        if (encoded.isBlank() || key.isBlank()) return ""
        return runCatching {
            val encrypted = Base64.decode(encoded, Base64.DEFAULT)
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val decrypted = ByteArray(encrypted.size) { index ->
                (encrypted[index].toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
            }
            decrypted.toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun extractPlayerUrls(html: String, referer: String): List<String> {
        val normalizedHtml = html.replace("\\/", "/").replace("\\u0026", "&")
        val document = Jsoup.parse(normalizedHtml, referer)
        val elementUrls = document.select("iframe[src], source[src], video[src], a[href]")
            .mapNotNull { element ->
                val attribute = if (element.hasAttr("src")) "src" else "href"
                normalizeUrl(element.absUrl(attribute).ifBlank { element.attr(attribute) })
                    ?.takeUnless { it.startsWith("javascript:", ignoreCase = true) || it == "#" }
            }
        val scriptUrls = PLAYER_URL_REGEX.findAll(normalizedHtml)
            .map { normalizeUrl(it.value) }
            .filterNotNull()
            .toList()

        return (elementUrls + scriptUrls).distinct()
    }

    private suspend fun extractVideos(
        target: PlayerTarget,
        visited: MutableSet<String>,
        depth: Int,
    ): List<Video> {
        if (depth > MAX_PLAYER_DEPTH || !visited.add(target.url)) return emptyList()

        val url = target.url
        val lowerUrl = url.lowercase()
        val label = normalizeServerLabel(target.label, lowerUrl)
        val videoHeaders = headersFor(target.referer)

        return runCatching {
            when {
                ".m3u8" in lowerUrl -> playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = target.referer,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$label - $it" },
                )

                lowerUrl.substringBefore('?').endsWith(".mp4") ->
                    listOf(Video(url, "$label - MP4", url, headers = videoHeaders))

                label == "SW" || label == "FL" ->
                    StreamWishExtractor(client, videoHeaders)
                        .videosFromUrl(url, videoNameGen = { "$label - $it" })

                label == "DD" -> doodExtractor.videosFromUrl(url, quality = "DD")

                label == "ST" -> streamTapeExtractor.videosFromUrl(url)

                label == "UPN" -> extractUpnVideos(target)

                label == "US" || label == "UQLOAD" ->
                    uqloadExtractor.videosFromUrl(url, prefix = "US")

                label == "MD" -> mixDropExtractor.videosFromUrl(url)

                label == "PP" -> VidHideExtractor(client, videoHeaders)
                    .videosFromUrl(url, videoNameGen = { "PP - $it" })

                label == "VOE" -> VoeExtractor(client, videoHeaders).videosFromUrl(url)

                else -> extractNestedPlayer(target, visited, depth)
            }
        }.getOrElse { emptyList() }
    }

    /**
     * player.upn.one encrypts its JSON response with AES-CBC. The decrypted
     * payload contains a signed HLS URL (including k/kx); returning the embed
     * URL itself cannot work in ExoPlayer and treating it as Uqload rewrites
     * it to an unrelated domain.
     */
    private suspend fun extractUpnVideos(target: PlayerTarget): List<Video> {
        val playerUrl = target.url.toHttpUrlOrNull() ?: return emptyList()
        val videoId = playerUrl.fragment?.substringBefore('&')?.trim().orEmpty()
        if (videoId.isBlank()) return emptyList()

        val playerPageUrl = playerUrl.newBuilder()
            .fragment(null)
            .query(null)
            .build()
            .toString()
        val playerHeaders = headersFor(playerPageUrl)
        val referrerHost = target.referer.toHttpUrlOrNull()?.host.orEmpty()
        val apiUrl = playerUrl.newBuilder()
            .encodedPath("/api/v1/video")
            .query(null)
            .fragment(null)
            .addQueryParameter("id", videoId)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1080")
            .apply {
                if (referrerHost.isNotBlank()) addQueryParameter("r", referrerHost)
            }
            .build()

        val encryptedPayload = client.newCall(GET(apiUrl, playerHeaders)).await().use {
            it.throwIfCloudflareChallenge()
            it.bodyString().trim()
        }
        val payload = decryptUpnPayload(encryptedPayload) ?: return emptyList()
        val data = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        val playlists = listOf("cfNative", "source", "cf")
            .map { data.optString(it).trim() }
            .filter { it.startsWith("http", ignoreCase = true) && ".m3u8" in it.lowercase() }
            .distinct()

        for (playlist in playlists) {
            val videos = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = playlist,
                    referer = playerPageUrl,
                    masterHeaders = playerHeaders,
                    videoHeaders = playerHeaders,
                    videoNameGen = { "US - $it" },
                )
            }.getOrDefault(emptyList())
            if (videos.isNotEmpty()) return videos
        }
        return emptyList()
    }

    private fun decryptUpnPayload(encryptedHex: String): String? = runCatching {
        if (encryptedHex.length % 2 != 0 || !UPN_HEX_REGEX.matches(encryptedHex)) return null
        val encrypted = ByteArray(encryptedHex.length / 2) { index ->
            encryptedHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(UPN_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(UPN_AES_IV.toByteArray(Charsets.UTF_8)),
        )
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private suspend fun extractNestedPlayer(
        target: PlayerTarget,
        visited: MutableSet<String>,
        depth: Int,
    ): List<Video> {
        val pageHeaders = headersFor(target.referer)
        val response = client.newCall(GET(target.url, pageHeaders)).await()
        val body = response.use {
            it.throwIfCloudflareChallenge()
            it.bodyString()
        }
        val nestedTargets = extractPlayerUrls(body, target.url)
            .filterNot { it == target.url }
            .map { PlayerTarget(normalizeServerLabel(target.label, it.lowercase()), it, target.url) }

        val videos = mutableListOf<Video>()
        for (nested in nestedTargets) {
            videos += extractVideos(nested, visited, depth + 1)
        }
        return videos
    }

    private fun normalizeServerLabel(label: String, url: String = ""): String {
        val clean = label.trim().uppercase()
        return when {
            "ryderjet" in url || "callistanise" in url || "vidhide" in url ||
                "streamhide" in url || "streamp2p" in url || "strp2p" in url -> "PP"

            "streamwish" in url || "strwish" in url || "embedwish" in url ||
                "faststream" in url || "filelions" in url || clean in setOf("SW", "FL") -> clean.ifBlank { "SW" }

            "dood" in url || "ds2play" in url || clean in setOf("DD", "DOOD") -> "DD"

            "streamtape" in url || clean in setOf("ST", "STREAMTAPE") -> "ST"

            "player.upn." in url || "upn.one" in url -> "UPN"

            "uqload" in url || clean == "UQLOAD" -> "UQLOAD"

            clean == "US" -> "US"

            "mixdrop" in url || clean in setOf("MD", "MIXDROP") -> "MD"

            "vidhide" in url || "streamhide" in url || "streamp2p" in url ||
                clean in setOf("PP", "VIDHIDE") -> "PP"

            "voe." in url || clean == "VOE" -> "VOE"

            "sextb" in url || clean == "TB" -> "TB"

            else -> clean.ifBlank { "TB" }
        }
    }

    private fun headersFor(referer: String): Headers = headers.newBuilder()
        .set("Referer", referer)
        .removeAll("Origin")
        .build()

    private fun normalizeUrl(rawUrl: String): String? {
        val clean = rawUrl.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$baseUrl$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> "$baseUrl/${clean.removePrefix("/")}"
        }
    }

    private fun Response.rememberCatalogPath() {
        val resolvedPath = request.url.encodedPath.substringBefore("/pg-").trimEnd('/')
        val stablePath = when {
            resolvedPath.startsWith("/jav-censored-") -> "/jav-censored"
            resolvedPath.startsWith("/jav-uncensored-") -> "/jav-uncensored"
            resolvedPath.startsWith("/jav-amateur-") -> "/jav-amateur"
            resolvedPath.startsWith("/jav-subtitle-") -> "/jav-subtitle"
            resolvedPath.startsWith("/genre/uncensored-leaked-") -> "/genre/uncensored-leaked"
            else -> return
        }
        resolvedCatalogPaths[stablePath] = resolvedPath
    }

    private fun Response.throwIfCloudflareChallenge() {
        val preview = runCatching { peekBody(CHALLENGE_PEEK_BYTES).string() }.getOrDefault("")
        val cloudflareError = code in CLOUDFLARE_ERROR_CODES &&
            (
                header("Server")?.contains("cloudflare", ignoreCase = true) == true ||
                    header("cf-ray") != null ||
                    header("cf-mitigated").equals("challenge", ignoreCase = true)
                )
        if (cloudflareError || preview.isCloudflareChallengeHtml()) {
            throw CloudflareBlockedException()
        }
    }

    private fun Document.throwIfCloudflareChallenge() {
        if (html().isCloudflareChallengeHtml()) throw CloudflareBlockedException()
    }

    private fun String.isCloudflareChallengeHtml(): Boolean {
        val lower = lowercase()
        return "_cf_chl_opt" in lower ||
            "id=\"challenge-form\"" in lower ||
            "id='challenge-form'" in lower ||
            titleChallengeRegex.containsMatchIn(this)
    }

    private fun serverPriority(quality: String): Int = when {
        quality.contains("SW", true) || quality.contains("StreamWish", true) -> 1
        quality.contains("FL", true) || quality.contains("FileLions", true) -> 2
        quality.contains("DD", true) || quality.contains("Dood", true) -> 3
        quality.contains("ST", true) || quality.contains("StreamTape", true) -> 4
        quality.contains("US", true) || quality.contains("UPN", true) || quality.contains("Uqload", true) -> 5
        quality.contains("MD", true) || quality.contains("MixDrop", true) -> 6
        quality.contains("PP", true) || quality.contains("VidHide", true) -> 7
        quality.contains("VOE", true) -> 8
        quality.contains("TB", true) || quality.contains("SexTB", true) -> 9
        else -> 10
    }

    override fun videoListSelector(): String = "button.btn-player[data-id]"

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    private data class PlayerPayload(
        val encryptedPlayer: String,
        val nextToken: String,
        val nextKey: String,
    )

    private data class PlayerTarget(
        val label: String,
        val url: String,
        val referer: String,
    )

    private class CloudflareBlockedException : IOException("Cloudflare blocked SexTB. Open the page in WebView once, complete the challenge, then retry.")

    companion object {
        private const val DEFAULT_CATEGORY = "/jav-censored"
        private const val MAX_PLAYER_DEPTH = 2
        private const val CHALLENGE_PEEK_BYTES = 512L * 1024L
        private const val UPN_AES_KEY = "kiemtienmua911ca"
        private const val UPN_AES_IV = "1234567890oiuytr"

        private val CLOUDFLARE_ERROR_CODES = setOf(403, 429, 503)
        private val titleChallengeRegex = Regex("""<title>\s*(?:just a moment|attention required)[^<]*</title>""", RegexOption.IGNORE_CASE)
        private val PT_REGEX = Regex("""window\.__pt\s*=\s*["']([^"']+)""")
        private val PK_REGEX = Regex("""window\.__pk\s*=\s*["']([^"']+)""")
        private val FILM_ID_REGEX = Regex("""(?:var|let|const)\s+filmId\s*=\s*(\d+)""")
        private val PLAYER_URL_REGEX = Regex("""(?:https?:)?//[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        private val M3U8_URL_REGEX =
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
        private val UPN_HEX_REGEX = Regex("""[0-9a-fA-F]+""")

        private val CATEGORIES = arrayOf(
            "Censored" to "/jav-censored",
            "Uncensored" to "/jav-uncensored",
            "Amateur" to "/jav-amateur",
            "English Subtitle" to "/jav-subtitle",
            "Uncensored Leaked" to "/genre/uncensored-leaked",
        )

        private val CURRENT_CATALOG_PATHS = mapOf(
            "/jav-censored" to "/jav-censored-6gt09dgi",
            "/jav-uncensored" to "/jav-uncensored-x7ibgpyt",
            "/jav-amateur" to "/jav-amateur-b6i6vejs",
            "/jav-subtitle" to "/jav-subtitle-iye0wdn6",
            "/genre/uncensored-leaked" to "/genre/uncensored-leaked-8iodxgmu",
        )

        private val GENRES = arrayOf(
            "All" to "all",
            "Amateur" to "amateur",
            "Anal" to "anal",
            "Beautiful Girl" to "beautiful-girl",
            "Big Asses" to "big-asses",
            "Big Tits" to "big-tits",
            "Blowjob" to "blowjob",
            "Bondage" to "bondage",
            "Cheating Wife" to "cheating-wife",
            "Cosplay" to "cosplay",
            "Creampie" to "creampie",
            "Cumshots" to "cumshots",
            "Deep Throat" to "deep-throat",
            "Doggy Style" to "doggy-style",
            "Facials" to "facials",
            "Gonzo" to "gonzo",
            "Handjob" to "handjob",
            "Married Woman" to "married-woman",
            "Massage" to "massage",
            "Masturbation" to "masturbation",
            "Mature Woman" to "mature-woman",
            "Office Lady" to "office-lady",
            "Older Sister" to "older-sister",
            "Orgy" to "orgy",
            "Over 4 Hours" to "over-4-hours",
            "POV" to "pov",
            "School Girls" to "school-girls",
            "Sex Toys" to "sex-toys",
            "Slender" to "slender",
            "Squirting" to "squirting",
            "Stepfamily" to "stepfamily",
            "Threesome / Foursome" to "threesome---foursome",
            "Voyeur" to "voyeur",
            "Young Wife" to "young-wife",
        )

        private val STUDIOS = arrayOf(
            "All" to "all",
            "Alice Japan" to "alice-japan",
            "Attackers" to "attackers",
            "Center Village" to "center-village",
            "Deep's" to "deep-s",
            "E-body" to "e-body",
            "Faleno" to "faleno",
            "Fitch" to "fitch",
            "Glory Quest" to "glory-quest",
            "Hunter" to "hunter",
            "Idea Pocket" to "idea-pocket",
            "K M Produce" to "k-m-produce",
            "Kawaii" to "kawaii",
            "Madonna" to "madonna",
            "Maxing" to "maxing",
            "Moodyz" to "moodyz",
            "Nadeshiko" to "nadeshiko",
            "Nagae Style" to "nagae-style",
            "Oppai" to "oppai",
            "Premium" to "premium",
            "Prestige" to "prestige",
            "Prestige Premium" to "prestige-premium",
            "S1 No.1 Style" to "s1-no-1-style",
            "SOD Create" to "sod-create",
            "Tameike Goro" to "tameike-goro",
            "U & K" to "u---k",
            "Venus" to "venus",
            "Wanz Factory" to "wanz-factory",
        )

        private val QUALITIES = arrayOf(
            "All" to "all",
            "HD" to "hd",
            "SD" to "sd",
        )

        private val YEARS: Array<Pair<String, String>> = buildList {
            add("All" to "all")
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            for (year in currentYear downTo 2016) add(year.toString() to year.toString())
        }.toTypedArray()

        private val SORTS = arrayOf(
            "Last Updated" to "desc",
            "New Release" to "release",
            "Most Liked" to "liked",
            "Most Favorite" to "favorite",
            "Most Viewed" to "viewed",
            "Most Viewed Day" to "viewed-day",
            "Most Viewed Week" to "viewed-week",
            "Most Viewed Month" to "viewed-month",
        )
    }
}
