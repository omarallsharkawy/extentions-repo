package eu.kanade.tachiyomi.animeextension.all.supjav

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class SupJav(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SupJav"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = false

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

    override fun popularAnimeSelector() = "div.posts > div.post > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))

        element.selectFirst("img")!!.run {
            title = attr("alt")
            thumbnail_url = absUrl("data-original").ifBlank { absUrl("src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination a.next, div.pagination li.active:not(:nth-last-child(2)), div.pagination li.active + li, div.pagination li:has(.current) + li, div.pagination span.current + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

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
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$id"))
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
        val url = if (page > 1) {
            "$baseUrl$langPath/page/$page/?s=$query"
        } else {
            "$baseUrl$langPath/?s=$query"
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.content > div.post-meta")!!
        title = content.selectFirst("h2")!!.text()
        thumbnail_url = content.selectFirst("img")?.absUrl("src")

        content.selectFirst("div.cats")?.run {
            author = select("p:contains(Maker :) > a").textsOrNull()
            artist = select("p:contains(Cast :) > a").textsOrNull()
        }
        genre = content.select("div.tags > a").textsOrNull()
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

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()

        // Site still uses div.btnst > a.btn-server with reversed data-link tokens.
        val players = doc.select("div.btnst a[data-link], div.btnst > a")
            .mapNotNull { el ->
                val name = el.ownText().ifBlank { el.text() }.trim().uppercase()
                val rawLink = el.attr("data-link").trim()
                if (name.isEmpty() || rawLink.isEmpty()) return@mapNotNull null
                name to rawLink.reversed()
            }
            .distinctBy { it.second }

        return players.parallelCatchingFlatMapBlocking(::videosFromPlayer)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private val protectorHeaders by lazy {
        super.headersBuilder().set("Referer", "$PROTECTOR_URL/").build()
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private suspend fun videosFromPlayer(player: Pair<String, String>): List<Video> {
        val (hoster, id) = player
        val url = resolveProtectorRedirect(id) ?: return emptyList()
        val host = url.toHttpUrlOrNull()?.host.orEmpty().lowercase()

        return when {
            hoster == "ST" || host.contains("streamtape") || host.contains("strtape") ||
                host.contains("tapecontent") ->
                streamtapeExtractor.videosFromUrl(url, quality = "ST")

            hoster == "VOE" || host.contains("voe") ->
                voeExtractor.videosFromUrl(url, prefix = "VOE ")

            hoster == "FST" || isStreamWishLikeHost(host) ->
                videosFromStreamWishLike(url, "FST")

            hoster == "TV" || isTurboVidHost(host) ->
                videosFromTurboVid(url)

            else -> {
                // Unknown server label: try TurboVid-style urlPlay, then packed HLS clones.
                videosFromTurboVid(url)
                    .ifEmpty { videosFromStreamWishLike(url, hoster.ifBlank { host }) }
            }
        }
    }

    /**
     * Protector still accepts reversed data-link via `?c=` and 302s to the real embed.
     * Site JS embeds with `?l=` (raw token) for iframe display; scrapers use `?c=`.
     */
    private suspend fun resolveProtectorRedirect(id: String): String? {
        val response = noRedirectClient
            .newCall(GET("$PROTECTOR_URL/supjav.php?c=$id", protectorHeaders))
            .await()

        return response.use { resp ->
            (resp.header("location") ?: resp.header("Location"))
                ?.trim()
                ?.takeIf { it.startsWith("http") }
        }
    }

    private suspend fun videosFromTurboVid(url: String): List<Video> {
        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()

        val playlistUrl = URLPLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: return emptyList()

        if (playlistUrl.toHttpUrlOrNull() == null) return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl,
            referer = url,
            videoNameGen = { "TV - $it" },
        ).distinctBy { it.videoUrl }
    }

    /**
     * FST currently resolves to StreamWish-family clones (e.g. fc2stream.tv) whose IDs
     * are not mirrored on streamwish.com. Prefer the redirected embed page: unpack
     * packed JWPlayer script and pull the master m3u8.
     */
    private suspend fun videosFromStreamWishLike(url: String, prefix: String): List<Video> {
        val fromExtractor = streamwishExtractor.videosFromUrl(url) { "$prefix - $it" }
        if (fromExtractor.isNotEmpty()) return fromExtractor

        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(body, url)

        val scriptBody = doc.select("script").asSequence()
            .map { it.data() }
            .firstNotNullOfOrNull { script ->
                when {
                    script.contains("eval(function(p,a,c") ->
                        JsUnpacker.unpackAndCombine(script)

                    script.contains("m3u8") -> script

                    else -> null
                }
            }
            ?: run {
                // Whole-page fallback if script tags were obfuscated differently.
                if (body.contains("eval(function(p,a,c")) {
                    val packed = PACKED_REGEX.find(body)?.value
                    packed?.let { JsUnpacker.unpackAndCombine(it) }
                } else {
                    null
                }
            }
            ?: return emptyList()

        val masterUrl = M3U8_REGEX.find(scriptBody)?.value ?: return emptyList()
        val referer = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: url

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = referer,
            videoNameGen = { "$prefix - $it" },
        )
    }

    private fun isStreamWishLikeHost(host: String): Boolean = host.contains("fc2stream") ||
        host.contains("streamwish") ||
        host.contains("streamhg") ||
        host.contains("hlswish") ||
        host.contains("swish") ||
        host.contains("wish") ||
        host.contains("playerwish") ||
        host.contains("medix") ||
        host.contains("niram")

    private fun isTurboVidHost(host: String): Boolean = host.contains("turbovid") ||
        host.contains("emturbovid") ||
        host.contains("turboviplay") ||
        host.contains("sptvp") ||
        host.contains("turbosplayer")

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

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"

        private val URLPLAY_REGEX by lazy {
            Regex("""urlPlay\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        }
        private val DATA_HASH_REGEX by lazy {
            Regex("""data-hash\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        }
        private val M3U8_REGEX by lazy { Regex("""https?://[^"'\s\\]+m3u8[^"'\s\\]*""") }
        private val PACKED_REGEX by lazy {
            Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        }

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
