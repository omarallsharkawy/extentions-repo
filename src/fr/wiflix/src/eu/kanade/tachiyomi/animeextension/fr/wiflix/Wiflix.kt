package eu.kanade.tachiyomi.animeextension.fr.wiflix

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamdavextractor.StreamDavExtractor
import aniyomi.lib.upstreamextractor.UpstreamExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidoextractor.VidoExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.datalifeengine.DataLifeEngine
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Domain list: https://wiflix-news.site | https://ww1.wiflix-adresses.fun
 * Current mirror (from address pages): https://flemmix.voto
 */
class Wiflix :
    DataLifeEngine(
        "Wiflix",
        "https://flemmix.voto",
        "fr",
    ) {

    // Site no longer wraps cards in #dle-content (theme default requires it)
    override fun popularAnimeSelector(): String = "div.mov"

    override fun popularAnimeNextPageSelector(): String = "span.navigation > span:not(.nav_ext) + a"
    override val categories = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Séries", "/serie-en-streaming/"),
        Pair("Films", "/film-en-streaming/"),
    )

    override val genres = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Action", "/film-en-streaming/action/"),
        Pair("Animation", "/film-en-streaming/animation/"),
        Pair("Arts Martiaux", "/film-en-streaming/arts-martiaux/"),
        Pair("Aventure", "/film-en-streaming/aventure/"),
        Pair("Biopic", "/film-en-streaming/biopic/"),
        Pair("Comédie", "/film-en-streaming/comedie/"),
        Pair("Comédie Dramatique", "/film-en-streaming/comedie-dramatique/"),
        Pair("Épouvante Horreur", "/film-en-streaming/horreur/"),
        Pair("Drame", "/film-en-streaming/drame/"),
        Pair("Documentaire", "/film-en-streaming/documentaire/"),
        Pair("Espionnage", "/film-en-streaming/espionnage/"),
        Pair("Famille", "/film-en-streaming/famille/"),
        Pair("Fantastique", "/film-en-streaming/fantastique/"),
        Pair("Guerre", "/film-en-streaming/guerre/"),
        Pair("Historique", "/film-en-streaming/historique/"),
        Pair("Musical", "/film-en-streaming/musical/"),
        Pair("Policier", "/film-en-streaming/policier/"),
        Pair("Romance", "/film-en-streaming/romance/"),
        Pair("Science-Fiction", "/film-en-streaming/science-fiction/"),
        Pair("Spectacles", "/film-en-streaming/spectacles/"),
        Pair("Thriller", "/film-en-streaming/thriller/"),
        Pair("Western", "/film-en-streaming/western/"),
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/serie-en-streaming/page/$page/")

    // ============================== Episodes ==============================

    // Hosts are now loadVideo('url') onclicks inside epNvs blocks (was a[href*=https] / vd.php)
    override fun episodeListSelector(): String = ".hostsblock div[class*=ep][class*=vs]:has(a[onclick*=loadVideo])"

    override fun episodeListParse(response: Response): List<SEpisode> = super.episodeListParse(response).sort()

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        episode_number = element.className().filter { it.isDigit() }.toFloatOrNull() ?: 0F
        name = "Episode ${episode_number.toInt()}"
        scanlator = when {
            element.className().contains("vf", ignoreCase = true) -> "VF"
            element.className().contains("vost", ignoreCase = true) -> "VOSTFR"
            else -> null
        }
        url = element.select("a[onclick*=loadVideo]").mapNotNull { a ->
            LOAD_VIDEO_REGEX.find(a.attr("onclick"))?.groupValues?.get(1)
        }.joinToString(",")
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val list = episode.url.split(",").filter { it.isNotBlank() }.parallelCatchingFlatMap {
            with(it) {
                when {
                    contains("dood") -> DoodExtractor(client).videosFromUrl(this)
                    contains("vido.lol") || contains("vido") -> VidoExtractor(client).videosFromUrl(this)
                    contains("uqload") -> UqloadExtractor(client).videosFromUrl(this)
                    contains("waaw") -> emptyList()
                    contains("vudeo") -> VudeoExtractor(client).videosFromUrl(this)
                    contains("streamvid") || contains("vidhide") || contains("luluvdo") || contains("lulu") ->
                        VidHideExtractor(client, headers).videosFromUrl(this)
                    contains("upstream") -> UpstreamExtractor(client).videosFromUrl(this)
                    contains("streamdav") -> StreamDavExtractor(client).videosFromUrl(this)
                    contains("voe") -> VoeExtractor(client, headers).videosFromUrl(this)
                    else -> emptyList()
                }
            }
        }
        if (list.isEmpty()) throw Exception("no player found")
        return list
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    companion object {
        private val LOAD_VIDEO_REGEX = Regex("""loadVideo\('([^']+)'\)""")
    }
}
