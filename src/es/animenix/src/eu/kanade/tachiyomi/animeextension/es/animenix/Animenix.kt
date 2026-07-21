package eu.kanade.tachiyomi.animeextension.es.animenix

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Animenix :
    AnimeStream(
        "es",
        "Animenix",
        "https://animenix.com",
    ) {
    override val preferences by getPreferencesLazy()

    override val prefQualityDefault = "1080p"
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p")

    override val animeListUrl = "$baseUrl/anime"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale("es"))
    }

    override val episodePrefix = "Episodio"

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = listOf("StreamWish", "Filemoon", "Universal")
    }

    // ============================ Video Links =============================
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        val lower = "$url $name".lowercase()
        return when {
            "filemoon" in lower || "moon" in lower -> filemoonExtractor.videosFromUrl(url)
            "streamwish" in lower || "swdyu" in lower || "wish" in lower ||
                "wishembed" in lower || "cdnwish" in lower || "flaswish" in lower ||
                "sfastwish" in lower || "asnwish" in lower ->
                streamWishExtractor.videosFromUrl(url, "StreamWish")
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.videoSortPref
        val server = preferences.serverPref
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVER_LIST,
            entryValues = SERVER_LIST,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
    }
}
