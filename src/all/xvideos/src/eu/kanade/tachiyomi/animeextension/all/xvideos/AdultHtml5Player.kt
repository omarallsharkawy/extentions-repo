package eu.kanade.tachiyomi.animeextension.all.xvideos

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Shared WGCZ html5player helper (XNXX / XVideos family).
 * Streams come from `GET {base}/html5player/getvideo/{encodedId}/{cdnId}`.
 */
class AdultHtml5Player(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromPage(pageUrl: String, pageHtml: String, baseUrl: String): List<Video> {
        val encodedId = extractEncodedId(pageHtml, pageUrl)
            ?: error("Video id not found on page (player may have changed)")

        val cdnId = extractCdnId(pageHtml)

        val apiHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("Origin", baseUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val apiUrl = "$baseUrl/html5player/getvideo/$encodedId/$cdnId"
        val body = fetchGetvideo(apiUrl, apiHeaders)

        if (isUnavailable(body)) {
            error("Video no longer available (getvideo exist=false)")
        }

        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        val videos = mutableListOf<Video>()
        val seen = mutableSetOf<String>()

        fun addProgressive(rawUrl: String?, fallbackLabel: String) {
            val url = normalizeStreamUrl(rawUrl) ?: return
            if (!seen.add(url)) return
            videos += Video(url, mp4Label(url, fallbackLabel), url, headers = videoHeaders)
        }

        addProgressive(jsonStringField(body, "mp4_low"), "Low")
        addProgressive(jsonStringField(body, "mp4_high"), "High")

        val hlsUrl = normalizeStreamUrl(jsonStringField(body, "hls"))
        if (hlsUrl != null) {
            var hlsFailure: String? = null
            val hlsVideos = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = hlsUrl,
                    referer = "$baseUrl/",
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { quality -> "HLS - $quality" },
                )
            }.onFailure { e ->
                hlsFailure = e.message ?: e::class.java.simpleName
            }.getOrDefault(emptyList())

            if (hlsVideos.isNotEmpty()) {
                hlsVideos.forEach { video ->
                    if (seen.add(video.url)) {
                        videos += video
                    }
                }
            } else if (seen.add(hlsUrl)) {
                // Master parse failed or returned nothing — still expose the master playlist.
                videos += Video(hlsUrl, "HLS", hlsUrl, headers = videoHeaders)
            }

            if (videos.isEmpty() && hlsFailure != null) {
                error("HLS playlist failed and no progressive streams: $hlsFailure")
            }
        }

        if (videos.isEmpty()) {
            error(emptyStreamsMessage(body))
        }

        return videos
    }

    private fun fetchGetvideo(apiUrl: String, apiHeaders: Headers): String {
        val response = try {
            client.newCall(GET(apiUrl, apiHeaders)).execute()
        } catch (e: IOException) {
            error("getvideo network error: ${e.message ?: e::class.java.simpleName}")
        }

        return response.use { resp ->
            val text = try {
                resp.body.string()
            } catch (e: IOException) {
                error("getvideo failed to read body: ${e.message ?: e::class.java.simpleName}")
            }
            if (!resp.isSuccessful) {
                val snippet = text.replace("\n", " ").trim().take(120)
                error(
                    buildString {
                        append("getvideo failed: HTTP ${resp.code}")
                        if (snippet.isNotEmpty()) append(" — $snippet")
                    },
                )
            }
            if (text.isBlank()) {
                error("getvideo returned empty body")
            }
            text
        }
    }

    private fun isUnavailable(body: String): Boolean = EXIST_FALSE_REGEX.containsMatchIn(body)

    private fun emptyStreamsMessage(body: String): String {
        val low = jsonStringField(body, "mp4_low")?.let { "set" } ?: "missing"
        val high = jsonStringField(body, "mp4_high")?.let { "set" } ?: "missing"
        val hls = jsonStringField(body, "hls")?.let { "set" } ?: "missing"
        val exist = jsonRawField(body, "exist") ?: "?"
        val ok = jsonStringField(body, "OK") ?: jsonRawField(body, "OK") ?: "?"
        return "No playable streams returned by getvideo " +
            "(mp4_low=$low, mp4_high=$high, hls=$hls, exist=$exist, OK=$ok)"
    }

    private fun extractEncodedId(pageHtml: String, pageUrl: String): String? {
        ENCODED_ID_REGEX.find(pageHtml)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return extractEncodedIdFromUrl(pageUrl)
    }

    private fun extractCdnId(pageHtml: String): String = CDN_ID_REGEX.find(pageHtml)?.groupValues?.get(1)
        ?: CDN_HLS_ID_REGEX.find(pageHtml)?.groupValues?.get(1)
        ?: DEFAULT_CDN_ID

    private fun mp4Label(url: String, fallback: String): String {
        MP4_QUALITY_REGEX.find(url)?.groupValues?.get(1)?.let { height ->
            return "MP4 ${height}p"
        }
        return when {
            url.contains("mp4_hd", ignoreCase = true) ||
                url.contains("video_hd", ignoreCase = true) -> "MP4 High"
            url.contains("mp4_sd", ignoreCase = true) ||
                url.contains("video_sd", ignoreCase = true) -> "MP4 Low"
            else -> "MP4 $fallback"
        }
    }

    private fun extractEncodedIdFromUrl(url: String): String? {
        val path = url.substringAfter("://", missingDelimiterValue = url)
            .substringAfter("/")
            .substringBefore("?")
            .substringBefore("#")
        val segment = path.substringBefore("/")
        return when {
            segment.startsWith("video.") -> segment.removePrefix("video.")
            segment.startsWith("video-") -> segment.removePrefix("video-")
            segment.startsWith("video") &&
                segment.length > 5 &&
                segment.drop(5).all { it.isDigit() } -> segment.removePrefix("video")
            else -> EMBED_ID_REGEX.find(url)?.groupValues?.get(1)
        }?.takeIf { it.isNotBlank() }
    }

    private fun normalizeStreamUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "false") return null
        var url = raw.trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
        if (url.startsWith("//")) {
            url = "https:$url"
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null
        }
        return url
    }

    private fun jsonStringField(json: String, name: String): String? {
        val raw = Regex(""""$name"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
            ?: return null
        return raw.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun jsonRawField(json: String, name: String): String? = Regex(""""$name"\s*:\s*([^,}\]\s]+)""").find(json)?.groupValues?.get(1)

    companion object {
        private const val DEFAULT_CDN_ID = "21"

        /** Single/double quotes; optional `html5player.` prefix. */
        private val ENCODED_ID_REGEX = Regex(
            """(?:html5player\.)?setEncodedIdVideo\(\s*['"]([^'"]+)['"]\s*\)""",
        )
        private val CDN_ID_REGEX = Regex(
            """(?:html5player\.)?setIdCDN\(\s*['"](\d+)['"]\s*\)""",
        )
        private val CDN_HLS_ID_REGEX = Regex(
            """(?:html5player\.)?setIdCdnHLS\(\s*['"](\d+)['"]\s*\)""",
        )
        private val MP4_QUALITY_REGEX = Regex(
            """(?:video_|mp4_|hls[_-]?)(\d{3,4})p""",
            RegexOption.IGNORE_CASE,
        )
        private val EXIST_FALSE_REGEX = Regex(""""exist"\s*:\s*false""")
        private val EMBED_ID_REGEX = Regex("""/embedframe/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
    }
}
