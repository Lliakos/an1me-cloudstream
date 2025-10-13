package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // Small cache so reopening doesn't req-scrape immediately
    private val cache = mutableMapOf<String, LoadResponse>()

    // Helper: percent-encode only characters that break URIs (spaces, brackets)
    private fun encodeProblematicUrl(u: String): String {
        return u.replace(" ", "%20")
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace("\"", "%22")
    }

    // Use the modern newExtractorLink (suspend) inside suspend functions
    private suspend fun newLinkSuspend(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String?,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ): ExtractorLink {
        // newExtractorLink is suspend; call it from suspend contexts
        return newExtractorLink(source = sourceName, name = linkName, url = url, type = type) {
            this.referer = referer
            this.quality = quality
        }
    }

    // Older constructor fallback (non-suspend) — kept for any places that still expect quick ExtractorLink
    private fun newLinkQuick(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String?,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ): ExtractorLink {
        // This constructor may be deprecated but still works at runtime; prefer suspend newLinkSuspend where possible.
        return ExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            referer = referer,
            quality = quality,
            type = type
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null

        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text()
            ?: link.attr("title")
            ?: this.selectFirst("img")?.attr("alt")
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSpotlightResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text() ?: return null
        val bannerUrl = this.selectFirst("img[src*='banner']")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(bannerUrl)
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        try {
            // Spotlight / top banner (use first large banner group)
            val spotlight = document.select(".kira-spotlight li, .kira-slider li").mapNotNull { it.toSpotlightResult() }
            if (spotlight.isNotEmpty()) homePages.add(HomePageList("Spotlight", spotlight, isHorizontalImages = true))
        } catch (_: Exception) { }

        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide").mapNotNull { it.toTrendingResult() }
            if (trendingItems.isNotEmpty()) {
                // trending smaller items horizontally
                homePages.add(HomePageList("Trending", trendingItems, isHorizontalImages = true))
            }
        } catch (_: Exception) { }

        try {
            val latestSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια)), section:has(h2:contains(New Episodes))")
            val latest = latestSection?.select(".kira-grid-listing > div, .kira-grid-listing .kira-item")?.mapNotNull { it.toSearchResult() } ?: emptyList()
            if (latest.isNotEmpty()) homePages.add(HomePageList("Latest Episodes", latest))
        } catch (_: Exception) { }

        try {
            val items = document.select(".kira-grid-listing a[href*='/anime/'], li a[href*='/anime/']").mapNotNull { it.parent()?.toSearchResult() ?: it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePages.add(HomePageList("New Anime", items))
            }
        } catch (_: Exception) { }

        return HomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=${URLEncoder.encode(query, StandardCharsets.UTF_8.toString())}"
        val document = app.get(searchUrl).document

        return document.select("#first_load_result > div, .kira-grid-listing > div, .search-result-item").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = item.selectFirst("span[data-en-title]")?.text()
                ?: item.selectFirst("span[data-nt-title]")?.text() ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        cache[url]?.let { return it } // return cached if exists

        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()

        // Gather episodes robustly:
        // 1) Look for explicit episode list items
        val episodes = mutableListOf<Episode>()
        try {
            // Common internal anime pages use a list with anchor .episode-list-item or swiper slides or .kira-episodes
            val epElements = document.select(
                "div.episode-list-display-box a.episode-list-item[href*='/watch/']," +
                        "a[href*='/watch/'][class*='anime']," +
                        "li a[href*='/watch/']," +
                        ".kira-episodes a[href*='/watch/']," +
                        ".episode-item a[href*='/watch/']"
            )

            epElements.forEach { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                if (epUrl.isEmpty() || epUrl.contains("/anime/")) return@forEach
                val epTitle = ep.selectFirst(".episode-list-item-title")?.text() ?: ep.attr("title") ?: ep.text()
                // attempt to derive episode number
                val epNum = Regex("(?:Episode|EP|Ep|E)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: ep.selectFirst(".episode-list-item-number")?.text()?.toIntOrNull()
                    ?: episodes.size + 1
                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
        } catch (_: Exception) { }

        // 2) If no episodes found, try to extract JSON embedded in scripts (kiranime returns JSON sometimes)
        if (episodes.isEmpty()) {
            try {
                val scripts = document.select("script").map { it.data() ?: "" }.joinToString("\n")
                // Try to find "episodes": [ ... ] blocks
                val epJsonMatch = Regex("""(?s)"episodes"\s*:\s*(\[[^\]]+\])""").find(scripts)
                if (epJsonMatch != null) {
                    val arrText = epJsonMatch.groupValues[1]
                    val arr = JSONObject("{\"a\":$arrText}").getJSONArray("a")
                    for (i in 0 until arr.length()) {
                        val epObj = arr.getJSONObject(i)
                        val epUrl = epObj.optString("url").ifEmpty { epObj.optString("watch_url") }
                        if (epUrl.isNullOrEmpty()) continue
                        val fullUrl = fixUrl(epUrl)
                        val epTitle = epObj.optString("title").ifEmpty { "Episode ${i + 1}" }
                        val epNum = epObj.optInt("episode", -1).let { if (it < 0) null else it } ?: (i + 1)
                        episodes.add(newEpisode(fullUrl) {
                            this.name = epTitle
                            this.episode = epNum
                            this.posterUrl = poster
                        })
                    }
                }
            } catch (_: Exception) { }
        }

        // 3) Attempt to fetch episodes via site API if there is a post ID or data-post attribute
        if (episodes.isEmpty()) {
            try {
                val postId = document.selectFirst("[data-post], [data-id], input#post_id")?.attr("data-post")
                    ?: document.selectFirst("[data-post]")?.attr("data-post")
                    ?: document.selectFirst("input#post_id")?.attr("value")
                if (!postId.isNullOrEmpty()) {
                    // try the kiranime JSON endpoint (may require nonce on some installs — but many sites allow public)
                    val apiUrl = "$mainUrl/wp-json/kiranime/v1/anime/view?id=$postId"
                    try {
                        val json = app.get(apiUrl).parsed<JsonObject>()
                        val jEpisodes = json.getJSONArray("episodes")
                        if (jEpisodes != null) {
                            for (i in 0 until jEpisodes.length()) {
                                val obj = jEpisodes.getJSONObject(i)
                                val id = obj.optString("id")
                                val titleEp = obj.optString("title").ifEmpty { "Episode ${i + 1}" }
                                val epUrl = "$mainUrl/watch/$id"
                                val epNum = obj.optInt("episode", i + 1)
                                episodes.add(newEpisode(epUrl) {
                                    this.name = titleEp
                                    this.episode = epNum
                                    this.posterUrl = poster
                                })
                            }
                        }
                    } catch (_: Exception) {
                        // ignore api fail and continue fallback
                    }
                }
            } catch (_: Exception) { }
        }

        // Ensure episodes sorted by episode number and no duplicates
        val sortedEpisodes = episodes.distinctBy { it.url }.sortedBy { it.episode ?: Int.MAX_VALUE }

        // Keep AniList lookups but *only* for additional metadata; prefer on-site data counts & episodes
        val anilistData = try { getAnilistData(title) } catch (_: Exception) { null }

        val response = newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster ?: anilistData?.poster
            this.backgroundPosterUrl = anilistData?.banner
            this.plot = description ?: anilistData?.description
            this.tags = anilistData?.genres
            this.rating = anilistData?.score
            // anilist trailer is an external youtube url; map to trailerUrl if available
            this.trailerUrl = anilistData?.trailer
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
        }

        cache[url] = response
        return response
    }

    private suspend fun getAnilistData(title: String): AnilistInfo? {
        val query = """
            {
              Page(perPage: 1) {
                media(search: "${escapeForGraphql(title)}", type: ANIME) {
                  title { romaji english native }
                  description(asHtml: false)
                  coverImage { large }
                  bannerImage
                  genres
                  averageScore
                  trailer { id site }
                }
              }
            }
        """.trimIndent()

        val body = JSONObject(mapOf("query" to query)).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("https://graphql.anilist.co", requestBody = body).text
        val json = JSONObject(res)
        val media = json.getJSONObject("data").getJSONObject("Page").getJSONArray("media").optJSONObject(0)
            ?: return null

        return AnilistInfo(
            title = media.getJSONObject("title").optString("english")
                .ifEmpty { media.getJSONObject("title").optString("romaji").ifEmpty { media.getJSONObject("title").optString("native") } },
            description = media.optString("description"),
            poster = media.getJSONObject("coverImage").optString("large"),
            banner = media.optString("bannerImage"),
            genres = media.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            score = if (media.has("averageScore")) media.optInt("averageScore") else null,
            trailer = media.optJSONObject("trailer")?.let {
                if (it.optString("site").equals("youtube", true))
                    "https://www.youtube.com/watch?v=${it.optString("id")}" else null
            }
        )
    }

    private fun escapeForGraphql(input: String): String {
        // escape quotes and backslashes for embedding in a GraphQL string literal
        return input.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    data class AnilistInfo(
        val title: String?,
        val description: String?,
        val poster: String?,
        val banner: String?,
        val genres: List<String>,
        val score: Int?,
        val trailer: String?
    )

    // loadLinks is a suspend function so we can call newExtractorLink (suspend) inside it
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) return false
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))

            // If decodedUrl contains encoded characters, keep them encoded where necessary
            val cleanedDecoded = decodedUrl.trim()

            // Case 1: Direct m3u8/playlist
            if (cleanedDecoded.contains(".m3u8", ignoreCase = true)) {
                val text = try { app.get(encodeProblematicUrl(cleanedDecoded)).text } catch (e: Exception) {
                    // fallback without encoding full url
                    app.get(cleanedDecoded).text
                }
                val lines = text.lines()
                var found = false
                lines.forEachIndexed { i, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        found = true
                        val height = """RESOLUTION=\d+x(\d+)""".toRegex().find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val urlLine = if (i + 1 < lines.size) lines[i + 1] else null
                        if (urlLine != null && !urlLine.startsWith("#")) {
                            val fullUrl = if (urlLine.startsWith("http", true)) {
                                encodeProblematicUrl(urlLine)
                            } else {
                                val base = cleanedDecoded.substringBeforeLast("/")
                                encodeProblematicUrl("$base/$urlLine")
                            }
                            val quality = when (height) {
                                2160 -> Qualities.P2160.value
                                1440 -> Qualities.P1440.value
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                360 -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            // Use suspend newExtractorLink
                            callback(newLinkQuick(name, "$name ${height ?: "Unknown"}p", fullUrl, data, quality, ExtractorLinkType.M3U8))
                        }
                    }
                }
                // If no multi-variant entries found, return the master playlist as a single m3u8 link
                if (!found) {
                    callback(newLinkQuick(name, name, encodeProblematicUrl(cleanedDecoded), data, Qualities.Unknown.value, ExtractorLinkType.M3U8))
                }
                return true
            }

            // Case 2: direct mp4
            if (cleanedDecoded.endsWith(".mp4", ignoreCase = true)) {
                callback(newLinkQuick(name, "$name (MP4)", cleanedDecoded, data, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                return true
            }

            // Case 3: iframe page with scripts (common for many hosts)
            if (cleanedDecoded.startsWith("http", true)) {
                val iframeDoc = app.get(cleanedDecoded).document
                val scripts = iframeDoc.select("script").map { it.data() ?: "" }
                // Attempt to find URLs in JS: sources, file, url, "sources": [...]
                val patterns = listOf(
                    """"file"\s*:\s*"([^"]+)"""",
                    """"url"\s*:\s*"([^"]+)"""",
                    """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""",
                    """https?://[^\s"'<>]+\.mp4[^\s"'<>]*"""
                )
                for (script in scripts) {
                    for (pattern in patterns) {
                        val match = pattern.toRegex().find(script)
                        if (match != null) {
                            // group 1 for capturing patterns, or whole match for bare url pattern
                            val extracted = match.groupValues.getOrNull(1)?.replace("\\/", "/")?.replace("\\", "") ?: match.value
                            val ex = extracted.trim()
                            if (ex.contains(".m3u8", true)) {
                                // generate m3u8 entries
                                M3u8Helper.generateM3u8(source = name, streamUrl = encodeProblematicUrl(ex), referer = cleanedDecoded).forEach { callback(it) }
                                return true
                            } else if (ex.endsWith(".mp4", true)) {
                                callback(newLinkQuick(name, "$name (MP4)", ex, cleanedDecoded, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                                return true
                            } else {
                                // unknown but attempt multi-handling
                                callback(newLinkQuick(name, name, ex, cleanedDecoded, Qualities.Unknown.value, ExtractorLinkType.OTHER))
                                return true
                            }
                        }
                    }
                }

                // Google Photos / Drive common fallback: try to parse page for direct media
                if (cleanedDecoded.contains("photos.google.com") || cleanedDecoded.contains("drive.google.com")) {
                    val pageText = iframeDoc.html()
                    val anyM3 = """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex().find(pageText)?.value
                    if (anyM3 != null) {
                        M3u8Helper.generateM3u8(source = name, streamUrl = encodeProblematicUrl(anyM3), referer = cleanedDecoded).forEach { callback(it) }
                        return true
                    }
                    val anyMp4 = """https?://[^\s"'<>]+\.mp4[^\s"'<>]*""".toRegex().find(pageText)?.value
                    if (anyMp4 != null) {
                        callback(newLinkQuick(name, "$name (MP4)", anyMp4, cleanedDecoded, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                        return true
                    }
                }

                // nothing found in iframe
                return false
            }

            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}", e)
            return false
        }
    }
}
