package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.Base64
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private val cache = mutableMapOf<String, LoadResponse>() // caching for reopening app

    // Helper to create ExtractorLink without using deprecated constructor calls
    private fun newLinkObj(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String?,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ): ExtractorLink {
        // Use the constructor form available in the SDK; keep type argument explicit
        return ExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            referer = referer,
            quality = quality,
            type = type
        )
    }

    private fun encodeM3u8Url(u: String): String {
        // Minimal percent-encoding for problematic characters in path which break URI parsing.
        // This encodes spaces and brackets and quotes commonly found in filenames, which fixed your Illegal character errors.
        return u.replace(" ", "%20")
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace("\"", "%22")
            .replace("'", "%27")
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

        // Spotlight (top top top) - ensure banner images and horizontal look
        try {
            val spotlight = document.select(".kira-hero, .main-spotlight, .spotlight, .top-slider")
                .firstOrNull()?.select("a[href*='/anime/']")?.mapNotNull { it.toSpotlightResult() }
            if (!spotlight.isNullOrEmpty()) {
                // Use the built-in spotlight (horizontal large banners)
                homePages.add(HomePageList("Spotlight", spotlight, isHorizontalImages = true))
            }
        } catch (_: Exception) { }

        // Trending - prefer a horizontal carousel style, but smaller images
        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide, .trending .item, .kira-trending .swiper-slide")
                .mapNotNull { it.toTrendingResult() }
            if (trendingItems.isNotEmpty()) {
                homePages.add(HomePageList("Trending", trendingItems, isHorizontalImages = true))
            }
        } catch (_: Exception) { }

        // Latest / New Anime
        try {
            val latestSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια)), section:has(h2:contains(New Episodes)), .latest")
            val latest = latestSection?.select(".kira-grid-listing > div, .kira-grid-listing a, .latest .item")?.mapNotNull { it.toSearchResult() } ?: emptyList()
            if (latest.isNotEmpty()) {
                homePages.add(HomePageList("Latest Episodes", latest))
            }
        } catch (_: Exception) { }

        // Fallback New Anime listing
        try {
            val items = document.select(".kira-grid-listing a, li, .list .item").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePages.add(HomePageList("New Anime", items))
            }
        } catch (_: Exception) { }

        return HomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("#first_load_result > div, .search-results .item").mapNotNull { item ->
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

        // Collect episodes from multiple possible structures so we won't miss any
        val episodesList = mutableListOf<Episode>()

        // 1) Swiper / watch anchors
        document.select("div.swiper-slide a[href*='/watch/'], a[href*='/watch/'][class*='anime']").forEachIndexed { idx, ep ->
            val episodeUrl = fixUrl(ep.attr("href"))
            if (episodeUrl.isEmpty() || episodeUrl.contains("/anime/")) return@forEachIndexed
            val episodeTitle = ep.attr("title").ifEmpty { ep.text() }
            val num = Regex("""\b(?:Episode|Ep|E)\s*#?:?\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(episodeTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            episodesList.add(newEpisode(episodeUrl) {
                this.name = if (episodeTitle.isNotEmpty()) episodeTitle else "Episode ${num ?: idx + 1}"
                this.episode = num ?: (episodesList.size + 1)
                this.posterUrl = poster
            })
        }

        // 2) Episode-list-display-box (newer layouts)
        document.select("div.episode-list-display-box a.episode-list-item[href*='/watch/']").forEachIndexed { idx, ep ->
            val episodeUrl = fixUrl(ep.attr("href"))
            if (episodeUrl.isEmpty() || episodeUrl.contains("/anime/")) return@forEachIndexed
            val epNum = ep.selectFirst(".episode-list-item-number")?.text()?.toIntOrNull()
            val epTitle = ep.selectFirst(".episode-list-item-title")?.text() ?: ep.attr("title").ifEmpty { ep.text() }
            episodesList.add(newEpisode(episodeUrl) {
                this.name = if (epTitle.isNotEmpty()) epTitle else "Episode ${epNum ?: idx + 1}"
                this.episode = epNum ?: (episodesList.size + 1)
                this.posterUrl = poster
            })
        }

        // 3) Generic anchors fallback (any anchor with /watch/)
        document.select("a[href*='/watch/']").forEachIndexed { idx, ep ->
            val episodeUrl = fixUrl(ep.attr("href"))
            if (episodeUrl.isEmpty() || episodesList.any { it.url == episodeUrl } || episodeUrl.contains("/anime/")) return@forEachIndexed
            val epTitle = ep.attr("title").ifEmpty { ep.text() }
            val num = Regex("""\b(?:Episode|Ep|E)\s*#?:?\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            episodesList.add(newEpisode(episodeUrl) {
                this.name = if (epTitle.isNotEmpty()) epTitle else "Episode ${num ?: idx + 1}"
                this.episode = num ?: (episodesList.size + 1)
                this.posterUrl = poster
            })
        }

        // Ensure unique by url
        val uniqueEpisodes = episodesList.distinctBy { it.url }.toMutableList()

        // If episodes had no numeric ordering, try to infer order from url (trailing number) or keep order found.
        uniqueEpisodes.forEachIndexed { idx, ep ->
            if (ep.episode == null) {
                val fromUrl = Regex("(?i)(?:episode|ep|e)[-_/ ]?(\\d+)").find(ep.url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (fromUrl != null) {
                    ep.episode = fromUrl
                } else {
                    ep.episode = idx + 1
                }
            }
        }

        // Sort episodes by episode number ascending
        uniqueEpisodes.sortBy { it.episode ?: Int.MAX_VALUE }

        val anilistData = getAnilistData(title)
        val response = newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster ?: anilistData?.poster
            this.backgroundPosterUrl = anilistData?.banner
            this.plot = description ?: anilistData?.description
            this.tags = anilistData?.genres
            this.rating = anilistData?.score
            addEpisodes(DubStatus.Subbed, uniqueEpisodes)
        }

        cache[url] = response
        return response
    }

    private suspend fun getAnilistData(title: String): AnilistInfo? {
        val query = """
            {
              Page(perPage: 1) {
                media(search: "$title", type: ANIME) {
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
            title = if (media.getJSONObject("title").optString("english").isNullOrBlank()) media.getJSONObject("title").optString("romaji") else media.getJSONObject("title").optString("english"),
            description = media.optString("description"),
            poster = media.getJSONObject("coverImage").optString("large"),
            banner = media.optString("bannerImage"),
            genres = media.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            score = media.optInt("averageScore", 0),
            trailer = media.optJSONObject("trailer")?.let {
                if (it.optString("site") == "youtube")
                    "https://www.youtube.com/watch?v=${it.optString("id")}" else null
            }
        )
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
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))

            // If google photos share page -> try to fetch direct sources from meta tags / source tags
            if (decodedUrl.contains("photos.google.com")) {
                try {
                    val photoDoc = app.get(decodedUrl).document
                    // Try meta tags: og:video, og:video:url, og:video:secure_url, og:video:type
                    val candidates = listOf(
                        photoDoc.selectFirst("meta[property=og:video]")?.attr("content"),
                        photoDoc.selectFirst("meta[property=og:video:url]")?.attr("content"),
                        photoDoc.selectFirst("meta[property=og:video:secure_url]")?.attr("content"),
                        photoDoc.selectFirst("meta[property=og:image]")?.attr("content"),
                        photoDoc.selectFirst("source[src]")?.attr("src")
                    ).filterNotNull().map { it.trim() }

                    for (candidate in candidates) {
                        val urlCandidate = candidate.replace("\\/", "/")
                        if (urlCandidate.contains(".m3u8", true)) {
                            // generate M3U8 links
                            val safeUrl = encodeM3u8Url(urlCandidate)
                            M3u8Helper.generateM3u8(source = name, streamUrl = safeUrl, referer = decodedUrl).forEach(callback)
                            return true
                        } else if (urlCandidate.endsWith(".mp4", true)) {
                            callback(newLinkObj(name, "$name (MP4)", urlCandidate, decodedUrl, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                            return true
                        }
                    }
                } catch (_: Exception) {
                    // fallthrough to other handlers
                }
            }

            // If decoded is an M3U8 master/variant playlist
            if (decodedUrl.contains(".m3u8", true)) {
                val text = app.get(encodeM3u8Url(decodedUrl)).text
                val lines = text.lines()
                lines.forEachIndexed { idx, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val height = """RESOLUTION=\d+x(\d+)""".toRegex()
                            .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (idx + 1 < lines.size && !lines[idx + 1].startsWith("#")) {
                            val nextLine = lines[idx + 1].trim()
                            val fullUrl = if (nextLine.startsWith("http")) nextLine else "${decodedUrl.substringBeforeLast("/")}/${nextLine}"
                            val safe = encodeM3u8Url(fullUrl)
                            val quality = when (height) {
                                2160 -> Qualities.P2160.value
                                1440 -> Qualities.P1440.value
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                360 -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            callback(newLinkObj(name, "$name ${height ?: "Unknown"}p", safe, data, quality, ExtractorLinkType.M3U8))
                        }
                    }
                }
                // fallback: master playlist itself if no variants
                if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
                    callback(newLinkObj(name, name, encodeM3u8Url(decodedUrl), data, Qualities.Unknown.value, ExtractorLinkType.M3U8))
                }
                return true
            }

            // If direct mp4 or other direct video
            if (decodedUrl.endsWith(".mp4", true)) {
                callback(newLinkObj(name, "$name (MP4)", decodedUrl, data, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                return true
            }

            // Otherwise, attempt to load the decoded page and find m3u8/mp4 inside scripts
            if (decodedUrl.startsWith("http")) {
                val iframeDoc = app.get(decodedUrl).document
                val scripts = iframeDoc.select("script")
                val scriptText = scripts.firstOrNull { it.data().contains("sources") || it.data().contains(".m3u8") || it.data().contains(".mp4") }?.data()

                if (!scriptText.isNullOrEmpty()) {
                    val regexes = listOf(
                        """"(?:file|url)"\s*:\s*"([^"]+.m3u8[^"]*)"""".toRegex(),
                        """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex(),
                        """https?://[^\s"'<>]+\.mp4[^\s"'<>]*""".toRegex()
                    )
                    for (r in regexes) {
                        val m = r.find(scriptText)
                        if (m != null) {
                            val candidate = m.groupValues.getOrNull(1) ?: m.value
                            val cleaned = candidate.replace("\\/", "/").trim()
                            if (cleaned.contains(".m3u8", true)) {
                                M3u8Helper.generateM3u8(source = name, streamUrl = encodeM3u8Url(cleaned), referer = decodedUrl).forEach(callback)
                                return true
                            } else if (cleaned.endsWith(".mp4", true)) {
                                callback(newLinkObj(name, "$name (MP4)", cleaned, decodedUrl, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                                return true
                            }
                        }
                    }
                }
            }

            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}", e)
            return false
        }
    }
}
