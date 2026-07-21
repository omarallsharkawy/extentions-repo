package eu.kanade.tachiyomi.animeextension.all.pornhub

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PornHub :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = network.client.newBuilder()
        .addInterceptor(
            CookieInterceptor(
                "pornhub.com",
                listOf(
                    // Site JS may set accessAgeDisclaimerPH=2; both values satisfy the gate.
                    "accessAgeDisclaimerPH" to "1",
                    "accessAgeDisclaimerUK" to "1",
                    "age_verified" to "1",
                    "accessPH" to "1",
                    "platform" to "pc",
                    "cookieConsent" to "3",
                ),
            ),
        )
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl(page, sort = "ht"), headers)

    override fun popularAnimeSelector(): String = "li.pcVideoListItem:not(.mockNsfwThumb), " +
        "li.videoBox:not(.noVideo), ul#videoCategory li.videoblock, ul#videoSearchResult li.videoblock"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.phimage a, a.linkVideoThumb, a.thumbnailTitle, a[href*=view_video]")
        val href = link?.attr("abs:href")
            ?: element.selectFirst("a[href*=view_video]")?.attr("abs:href")
            ?: ""
        setUrlWithoutDomain(href)
        val rawTitle = link?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("span.title a, a.thumbnailTitle")?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("span.title a, a.thumbnailTitle")?.text().orEmpty()
        val duration = element.selectFirst("span.duration, var.duration, .duration, span.video-duration, var.duration")?.text()?.trim().orEmpty()
        title = if (duration.isNotBlank() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }
        thumbnail_url = element.selectFirst("div.phimage img, img")?.let { img ->
            img.attr("data-highres").ifBlank { null }
                ?: img.attr("data-poster").ifBlank { null }
                ?: img.attr("poster").ifBlank { null }
                ?: img.attr("data-mediumthumb").ifBlank { null }
                ?: img.attr("data-image").ifBlank { null }
                ?: img.attr("data-src").ifBlank { null }
                ?: img.attr("data-thumb_url").ifBlank { null }
                ?: img.attr("data-smallthumb").ifBlank { null }
                ?: img.attr("src").ifBlank { null }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next:not(.page_disabled):not(.disabled) a, " +
        "li.page_next:not(.page_disabled):not(.disabled), a.page_next:not(.disabled), a[rel=next]:not(.disabled), " +
        "link[rel=next], li.next:not(.disabled) a, a.next:not(.disabled), " +
        "a:has(i.ph-icon-chevron-right):not(.disabled), div.pagination li.active + li a, ul.pagination li.active + li a, " +
        "div.pagination3 li.active + li a, div.pagination span.current + a"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
        val isLastPage = document.selectFirst("li.page_next.page_disabled, li.page_next.disabled, li.next.disabled") != null
        val hasNext = !isLastPage && (document.selectFirst(popularAnimeNextPageSelector()) != null || animes.size >= 20) && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl(page, sort = "cm"), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "ht"
        val time = filters.firstInstanceOrNull<TimeFilter>()?.selected.orEmpty()
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()
        val duration = filters.firstInstanceOrNull<DurationFilter>()?.selected.orEmpty()
        val brand = filters.firstInstanceOrNull<BrandFilter>()?.state?.trim().orEmpty()

        // Brand / channel takes precedence (path-based browse).
        if (brand.isNotBlank()) {
            val slug = brand.slugify()
            val path = when {
                brand.startsWith("model/", ignoreCase = true) ||
                    brand.startsWith("pornstar/", ignoreCase = true) ||
                    brand.startsWith("users/", ignoreCase = true) ||
                    brand.startsWith("channels/", ignoreCase = true) ||
                    brand.startsWith("channel/", ignoreCase = true) -> brand.trim('/').lowercase()
                    .replaceFirst(Regex("^channel/"), "channels/")

                else -> "channels/$slug"
            }
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("$path/videos")
                .addQueryParameter("o", channelSort(sort))
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }

        // Keyword search: /video/search?search=…&o=&t=&min_duration=/max_duration=&filter_category=ID
        // Empty query + any filters (incl. category): /video?o=&t=&c=ID&min_duration=/max_duration=
        // Never drop filters when query is blank — Popular tab ignores them, Search does not.
        val url = if (query.isNotBlank()) {
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("video/search")
                .addQueryParameter("search", query.trim())
                .addQueryParameter("o", sort)
                .addQueryParameter("page", page.toString())
                .apply {
                    if (time.isNotBlank()) addQueryParameter("t", time)
                    applyDuration(duration)
                    // Live site: filter_category=<numeric id> (not slug)
                    if (category.isNotBlank()) addQueryParameter("filter_category", category)
                }
                .build()
        } else {
            browseUrl(page, sort = sort, time = time, duration = duration, category = category).toHttpUrl()
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.title span.inlineFree, h1.title, span.inlineFree")
            ?.text()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()

        author = document.selectFirst(
            "div.video-detailed-info div.userInfo a.bolded, " +
                "div.userInfo .usernameBadgesWrapper a, " +
                "div.usernameWrap a.bolded, " +
                "div.userInfo a[href*=/model/], " +
                "div.userInfo a[href*=/pornstar/], " +
                "div.userInfo a[href*=/channels/]",
        )?.text()

        description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        genre = document.select(
            "div.categoriesWrapper a, div.tagsWrapper a, " +
                "div.pornstarsWrapper a, a.js-categoryLink, a.js-tagLink",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.video-wrapper img, img#videoElementPoster")
                ?.attr("src")

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        },
    )

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        val flashvars = extractFlashvars(html)

        assertPlayable(html, flashvars)

        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        val videos = mutableListOf<Video>()
        var definitions = parseMediaDefinitions(flashvars, html)

        if (definitions.isEmpty()) {
            val doc = org.jsoup.Jsoup.parse(html, pageUrl)
            val iframeUrl = doc.selectFirst("iframe[src*=/embed/], iframe[src*=pornhub]")?.attr("abs:src")
            if (!iframeUrl.isNullOrBlank()) {
                try {
                    client.newCall(GET(iframeUrl, headers)).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val iframeHtml = resp.body.string()
                            val iframeFlashvars = extractFlashvars(iframeHtml)
                            definitions = parseMediaDefinitions(iframeFlashvars, iframeHtml)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Split free direct streams vs tokenized get_media (MP4 fallback).
        val direct = mutableListOf<MediaDefinition>()
        val getMediaUrls = linkedSetOf<String>()

        for (def in definitions) {
            val rawUrl = def.resolvedUrl() ?: continue
            if ("/video/get_media" in rawUrl) {
                getMediaUrls += rawUrl
                continue
            }
            // Empty quality array with no height is a stub / locked slot — skip.
            if (def.hasEmptyQuality() && def.height == null) continue
            direct += def
        }

        // Prefer HLS first, then progressive MP4 from the same mediaDefinitions list.
        for (def in direct.filter { it.isHls() }) {
            videos += videosFromDefinition(def, videoHeaders, prefix = "")
        }
        for (def in direct.filter { !it.isHls() }) {
            videos += videosFromDefinition(def, videoHeaders, prefix = "")
        }

        // MP4 fallback via get_media XHR (returns JSON list of progressive qualities when available).
        if (videos.none { it.quality.contains("MP4", ignoreCase = true) } || videos.isEmpty()) {
            val getMediaDefs = getMediaUrls.parallelCatchingFlatMapBlocking { mediaUrl ->
                fetchGetMedia(mediaUrl, pageUrl)
            }
            for (def in getMediaDefs) {
                val mp4 = def.resolvedUrl() ?: continue
                if ("/video/get_media" in mp4) continue
                val label = def.qualityLabel().ifBlank { "Default" }
                if (def.hasEmptyQuality() && def.height == null && label == "Default") continue
                videos += if (mp4.contains(".m3u8")) {
                    videosFromDefinition(def, videoHeaders, prefix = "")
                } else {
                    listOf(Video(mp4, "MP4 $label".trim(), mp4, videoHeaders))
                }
            }
        }

        // Last-resort: any master.m3u8 URLs embedded on the page / flashvars.
        if (videos.isEmpty()) {
            M3U8_REGEX.findAll(flashvars ?: html)
                .map { it.value.replace("\\/", "/") }
                .distinct()
                .forEach { url ->
                    videos += extractHlsSafe(url, qualityHint = "", videoHeaders = videoHeaders)
                }
        }

        // Preview-only / locked with trailer: surface anything we got with a clear label.
        if (videos.isEmpty() && isLocked(html, flashvars)) {
            throw Exception("Video is locked or premium-only (no free stream)")
        }
        if (videos.isEmpty() && isPreviewOnly(html, flashvars)) {
            throw Exception("Only a preview/trailer is available for this video")
        }
        if (videos.isEmpty()) {
            throw Exception("No playable streams found (geo-block, age wall, or removed video)")
        }

        return videos.distinctBy { it.url to it.quality }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { !it.quality.contains(quality, ignoreCase = true) },
                { !it.quality.contains("HLS", ignoreCase = true) },
                { -(QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0) },
            ),
        )
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply in SEARCH (empty query OK). Not on Popular tab."),
        AnimeFilter.Header("Brand/Channel overrides other filters when set"),
        SortFilter(),
        TimeFilter(),
        DurationFilter(),
        CategoryFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Brand/channel slug (e.g. brazzers) or path"),
        AnimeFilter.Header("paths: channels/x · model/x · pornstar/x"),
        BrandFilter(),
    )

    private class SortFilter :
        UriPartFilter(
            "Sort",
            arrayOf(
                Pair("Hot", "ht"),
                Pair("Most Relevant", "mr"),
                Pair("Most Viewed", "mv"),
                Pair("Top Rated", "tr"),
                Pair("Newest", "cm"),
                Pair("Longest", "lg"),
            ),
        )

    private class TimeFilter :
        UriPartFilter(
            "Time window",
            arrayOf(
                Pair("Any / All time", ""),
                Pair("Daily", "t"),
                Pair("Weekly", "w"),
                Pair("Monthly", "m"),
                Pair("Yearly", "y"),
            ),
        )

    private class DurationFilter :
        UriPartFilter(
            "Duration",
            arrayOf(
                Pair("Any", ""),
                Pair("Under 10 min", "max:10"),
                Pair("10+ min", "min:10"),
                Pair("30+ min", "min:30"),
            ),
        )

    /**
     * Category values are live numeric `c=` / `filter_category=` ids
     * (from /categories JSON + confirmed browse URLs like /video?c=3 Amateur).
     * Slugs and /categories/{slug} are not used for filtered listings.
     */
    private class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("Any", ""),
                Pair("Amateur", "3"),
                Pair("Anal", "35"),
                Pair("Asian", "1"),
                Pair("Babe", "5"),
                Pair("Big Ass", "4"),
                Pair("Big Dick", "7"),
                Pair("Big Tits", "8"),
                Pair("Blonde", "9"),
                Pair("Blowjob", "13"),
                Pair("Brunette", "11"),
                Pair("Cartoon", "86"),
                Pair("Compilation", "57"),
                Pair("Cosplay", "241"),
                Pair("Creampie", "15"),
                Pair("Ebony", "17"),
                Pair("Exclusive", "115"),
                Pair("Fetish", "18"),
                Pair("Hardcore", "21"),
                Pair("HD Porn", "38"),
                Pair("Hentai", "36"),
                Pair("Japanese", "111"),
                Pair("Latina", "26"),
                Pair("Lesbian", "27"),
                Pair("Massage", "78"),
                Pair("Mature", "28"),
                Pair("MILF", "29"),
                Pair("POV", "41"),
                Pair("Public", "24"),
                Pair("Red Head", "42"),
                Pair("Rough Sex", "67"),
                Pair("Small Tits", "59"),
                Pair("Solo Female", "492"),
                Pair("Squirt", "69"),
                Pair("Teen (18+)", "37"),
                Pair("Threesome", "65"),
                Pair("Toys", "23"),
                Pair("Transgender", "83"),
                Pair("Verified Amateurs", "138"),
            ),
        )

    private class BrandFilter : AnimeFilter.Text("Brand / Channel")

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        val selected: String get() = vals[state].second
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // =============================== Helpers ==============================

    /**
     * Browse listing: `/video?o=&t=&c=&min_duration=/max_duration=&page=`
     * Category uses numeric `c=` (e.g. c=3 Amateur, c=29 MILF) — not /categories/{slug}.
     */
    private fun browseUrl(
        page: Int,
        sort: String = "ht",
        time: String = "",
        duration: String = "",
        category: String = "",
    ): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("video")
        .addQueryParameter("o", sort)
        .addQueryParameter("page", page.toString())
        .apply {
            if (time.isNotBlank()) addQueryParameter("t", time)
            if (category.isNotBlank()) addQueryParameter("c", category)
            applyDuration(duration)
        }
        .build()
        .toString()

    private fun okhttp3.HttpUrl.Builder.applyDuration(duration: String) {
        when {
            duration.startsWith("min:") ->
                addQueryParameter("min_duration", duration.removePrefix("min:"))

            duration.startsWith("max:") ->
                addQueryParameter("max_duration", duration.removePrefix("max:"))
        }
    }

    /** Channel/model pages use different sort tokens than /video. */
    private fun channelSort(sort: String): String = when (sort) {
        "cm", "mr" -> "da"

        // most recent
        "tr" -> "ra"

        // top rated
        "mv" -> "vi"

        // most viewed
        "lg" -> "lg"

        "ht" -> "da"

        else -> "da"
    }

    private fun parseMediaDefinitions(flashvars: String?, html: String): List<MediaDefinition> {
        val mediaJson = flashvars?.let { extractJsonArray(it, "mediaDefinitions") }
            ?: extractJsonArray(html, "mediaDefinitions")
            ?: return emptyList()
        return try {
            mediaJson.parseAs<List<MediaDefinition>>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun videosFromDefinition(
        def: MediaDefinition,
        videoHeaders: Headers,
        prefix: String,
    ): List<Video> {
        val rawUrl = def.resolvedUrl() ?: return emptyList()
        val qualityLabel = def.qualityLabel()
        val namePrefix = prefix

        return when {
            def.isHls() || rawUrl.contains(".m3u8") ->
                extractHlsSafe(rawUrl, qualityHint = qualityLabel, namePrefix = namePrefix)

            rawUrl.contains(".mp4") || def.format.equals("mp4", ignoreCase = true) ->
                listOf(
                    Video(
                        rawUrl,
                        "$namePrefix${"MP4 $qualityLabel".trim()}".trim(),
                        rawUrl,
                        videoHeaders,
                    ),
                )

            else -> emptyList()
        }
    }

    private fun extractHlsSafe(
        url: String,
        qualityHint: String,
        namePrefix: String = "",
        videoHeaders: Headers = headers,
    ): List<Video> = try {
        playlistUtils.extractFromHls(
            url,
            referer = "$baseUrl/",
            videoNameGen = { q ->
                val base = if (qualityHint.isNotBlank()) {
                    "HLS $qualityHint ($q)"
                } else {
                    "HLS $q"
                }
                "$namePrefix$base".trim()
            },
        )
    } catch (_: Exception) {
        val label = buildString {
            append(namePrefix)
            append("HLS")
            if (qualityHint.isNotBlank()) append(" $qualityHint")
        }.trim()
        listOf(Video(url, label, url, videoHeaders))
    }

    private fun fetchGetMedia(mediaUrl: String, pageUrl: String): List<MediaDefinition> {
        return try {
            val reqHeaders = headers.newBuilder()
                .set("Referer", pageUrl)
                .set("Origin", baseUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()
            client.newCall(GET(mediaUrl, reqHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body.string().trim()
                if (!body.startsWith("[")) return emptyList()
                body.parseAs<List<MediaDefinition>>()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun assertPlayable(html: String, flashvars: String?) {
        val blob = flashvars ?: html
        if (GEO_BLOCK.any { it in html } ||
            Regex(""""video_unavailable_country"\s*:\s*"true"""").containsMatchIn(blob)
        ) {
            throw Exception("Video unavailable in your country")
        }
        if (REMOVED.any { it in html }) {
            throw Exception("Video removed or disabled")
        }
    }

    private fun isLocked(html: String, flashvars: String?): Boolean {
        val blob = flashvars ?: ""
        return LOCKED_MARKERS.any { it in html } ||
            Regex(""""isLock"\s*:\s*true""").containsMatchIn(blob) ||
            Regex(""""lockedQualityOptions"\s*:\s*\[[^\]]+\]""").containsMatchIn(blob)
    }

    private fun isPreviewOnly(html: String, flashvars: String?): Boolean {
        val blob = flashvars ?: html
        return PREVIEW_MARKERS.any { it in html } ||
            Regex(""""isPreview"\s*:\s*true""").containsMatchIn(blob)
    }

    private fun extractFlashvars(html: String): String? {
        val marker = Regex("""var\s+flashvars(?:_\d+)?\s*=|flashvars_\d+\s*=""")
        val match = marker.find(html) ?: return null
        val braceStart = html.indexOf('{', match.range.last)
        if (braceStart < 0) return null
        return extractBalanced(html, braceStart, '{', '}')
    }

    /**
     * Extracts a JSON array field (balanced brackets).
     * Non-greedy regex is unsafe because mp4 entries use `"quality":[]`.
     */
    private fun extractJsonArray(source: String, field: String): String? {
        val key = "\"$field\""
        val keyIdx = source.indexOf(key)
        if (keyIdx < 0) return null
        val start = source.indexOf('[', keyIdx + key.length)
        if (start < 0) return null
        return extractBalanced(source, start, '[', ']')
    }

    private fun extractBalanced(source: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until source.length) {
            val c = source[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true

                open -> depth++

                close -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    private fun String.slugify(): String = trim()
        .lowercase()
        .replace(Regex("""[^\w\s-]"""), "")
        .replace(Regex("""[\s_]+"""), "-")
        .trim('-')

    // =============================== Models ===============================

    @Serializable
    private data class MediaDefinition(
        val format: String? = null,
        val videoUrl: String? = null,
        val quality: JsonElement? = null,
        val height: Int? = null,
        val remote: Boolean? = null,
        val defaultQuality: Boolean? = null,
    ) {
        fun resolvedUrl(): String? {
            val url = videoUrl?.replace("\\/", "/")?.trim() ?: return null
            if (url.isBlank()) return null
            return when {
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "https://www.pornhub.com$url"
                else -> null
            }
        }

        fun isHls(): Boolean = format.equals("hls", ignoreCase = true) ||
            resolvedUrl()?.contains(".m3u8") == true

        /** True when quality is missing, blank, or an empty JSON array (`[]`). */
        fun hasEmptyQuality(): Boolean = when (val q = quality) {
            null -> true
            is JsonArray -> q.isEmpty()
            is JsonPrimitive -> q.contentOrNull.isNullOrBlank() || q.contentOrNull == "null"
            else -> false
        }

        fun qualityLabel(): String {
            val fromHeight = height?.takeIf { it > 0 }?.let { "${it}p" }
            val fromQuality = when (val q = quality) {
                is JsonPrimitive ->
                    q.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let { if (it.endsWith("p")) it else "${it}p" }

                is JsonArray -> {
                    // Non-empty quality arrays are rare; take first scalar entry if present.
                    q.firstOrNull()
                        ?.let { el ->
                            runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                        }
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let { if (it.endsWith("p")) it else "${it}p" }
                }

                else -> null
            }
            return fromQuality ?: fromHeight ?: ""
        }
    }

    companion object {
        private const val PAGE_SIZE = 20

        private val M3U8_REGEX =
            Regex("""https?:\\?/\\?/[^"'\s]+?\.m3u8[^"'\s]*""")

        private val QUALITY_REGEX = Regex("""(\d{3,4})""")

        private val GEO_BLOCK = listOf(
            "geoBlocked",
            "This content is unavailable in your country",
            "not available in your country",
        )

        private val REMOVED = listOf(
            "Video has been removed",
            "This video has been removed",
            "has been disabled",
            "video has been flagged",
        )

        private val LOCKED_MARKERS = listOf(
            "id=\"lockedPlayer",
            "id='lockedPlayer",
            "class=\"lockedPlayer",
            "premiumLocked",
        )

        private val PREVIEW_MARKERS = listOf(
            "previewOnly",
            "isPreviewVideo",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "240p")
    }
}
