package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.Base64

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private suspend fun createLink(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ): ExtractorLink {
        return newExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            type = type
        ) {
            this.referer = referer
            this.quality = quality
        }
    }

    // Always prefer English title. If not available, fallback to meta og:title or plain text.
    private fun Element.extractEnglishTitle(): String? {
        return this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-title-en]")?.text()
            ?: this.selectFirst("meta[property=og:title]")?.attr("content")
            ?: this.selectFirst("a")?.attr("title")
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null

        // Prefer English title only
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    /**
     * Spotlight result: prefer title-card (poster) stretched, fallback to banner if needed.
     * We changed this to ensure the spotlight has an image.
     */
    private fun Element.toSpotlightResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        // Prefer English title only
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
            ?: return null

        // Prefer the title-card (poster) for spotlight so slider has images.
        val posterImg = this.selectFirst("img")?.attr("src")
        val bannerImg = this.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")

        // set posterUrl to posterImg (title card). If missing, use banner.
        val imageToUse = posterImg ?: bannerImg

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(imageToUse)
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Spotlight -> top slider: collect spotlight elements robustly
        try {
            val spotlightSelectors = listOf(
                ".spotlight .swiper-slide",
                ".home-spotlight .swiper-slide",
                ".featured .swiper-slide",
                ".hero .swiper-slide",
                ".main-slider .swiper-slide"
            )

            val spotlightElements = spotlightSelectors
                .flatMap { sel -> document.select(sel).toList() }
                .distinct()
                .mapNotNull { it.toSpotlightResult() }

            if (spotlightElements.isNotEmpty()) {
                // Put spotlight as first list so Cloudstream top slider uses it.
                homePages.add(HomePageList("Featured", spotlightElements, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing spotlight: ${e.message}")
        }

        // Trending: normal sized title cards (ensure not horizontal)
        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide, .trending .swiper-slide")
                .mapNotNull { it.toTrendingResult() }

            if (trendingItems.isNotEmpty()) {
                homePages.add(HomePageList("Τάσεις", trendingItems))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing trending: ${e.message}")
        }

        // Latest Episodes
        try {
            val latestEpisodesSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια))")
            val latestEpisodeItems = latestEpisodesSection?.select(".kira-grid-listing > div")?.mapNotNull {
                it.toLatestEpisodeResult()
            } ?: emptyList()
            if (latestEpisodeItems.isNotEmpty()) {
                homePages.add(HomePageList("Καινούργια Επεισόδια", latestEpisodeItems))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing latest episodes: ${e.message}")
        }

        // Latest Anime (general)
        try {
            val items = document.select("li").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePages.add(HomePageList("Καινούργια Anime", items))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing latest anime: ${e.message}")
        }

        return HomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document

        return document.select("#first_load_result > div").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))

            // Always prefer English title
            val title = item.selectFirst("span[data-en-title]")?.text()
                ?: item.selectFirst("img")?.attr("alt")
                ?: link.attr("title")
                ?: return@mapNotNull null

            val posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Prefer the English title from page or metadata; fallback to plain header text
        val title = document.selectFirst("span[data-en-title]")?.text()
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown"

        // poster (title card)
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        // banner (wide) - prefer Anilist banner if present
        val bannerUrl = document.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")
            ?: poster

        val description = document.selectFirst("div[data-synopsis]")?.text()

        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map {
            it.text().trim()
        }

        // metadata extraction (unchanged)
        val malScore = document.selectFirst("li:has(span:contains(MAL Βαθμολογια:))")
            ?.text()?.substringAfter(":")?.trim()
        val status = document.selectFirst("li:has(span:contains(Κατάσταση:)) a")?.text()
        val premiered = document.selectFirst("li:has(span:contains(Πρεμιέρα:))")
            ?.text()?.substringAfter(":")?.trim()
        val aired = document.selectFirst("li:has(span:contains(Προβλήθηκε:))")
            ?.text()?.substringAfter(":")?.trim()
        val duration = document.selectFirst("li:has(span:contains(Διάρκεια:))")
            ?.text()?.substringAfter(":")?.trim()
        val episodeCount = document.selectFirst("li:has(span:contains(Επεισόδια:))")
            ?.text()?.substringAfter(":")?.trim()
        val studio = document.selectFirst("li:has(span:contains(Στούντιο:)) a")?.text()

        val enhancedDescription = buildString {
            description?.let { append(it).append("\n\n") }
            append("━━━━━━━━━━━━━━━━\n")
            malScore?.let { append("⭐ MAL Score: $it\n") }
            status?.let { append("📺 Status: $it\n") }
            episodeCount?.let { append("🎬 Episodes: $it\n") }
            duration?.let { append("⏱️ Duration: $it\n") }
            aired?.let { append("📅 Aired: $it\n") }
            premiered?.let { append("🎭 Premiered: $it\n") }
            studio?.let { append("🎨 Studio: $it") }
        }

        // trailer extraction unchanged
        var trailerUrl: String? = null
        try {
            val trailerButton = document.selectFirst("a:contains(Watch Trailer), a:has(span:contains(Watch Trailer))")
            if (trailerButton != null) {
                val onclickAttr = trailerButton.attr("onclick")
                val youtubeRegex = """(?:youtube\.com/watch\?v=|youtu\.be/)([a-zA-Z0-9_-]{11})""".toRegex()
                val match = youtubeRegex.find(onclickAttr)
                if (match != null) {
                    trailerUrl = "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_Trailer", "Error extracting trailer: ${e.message}")
        }

        // ---------------------------
        // Episodes: collect ALL anchors matching /watch/ across the entire document.
        // This is the key fix — it avoids depending on a single "last 30" container.
        // We dedupe by URL and then sort properly by episode number (ASC).
        // ---------------------------
        val episodes = mutableListOf<Episode>()
        try {
            val anchors = document.select("a[href*='/watch/']")
                .mapNotNull { el ->
                    val href = fixUrl(el.attr("href"))
                    if (href.isNullOrEmpty() || href.contains("/anime/")) return@mapNotNull null
                    Pair(el, href)
                }
                // dedupe by href while preserving order of first occurrence
                .distinctBy { it.second }

            anchors.forEach { (el, episodeUrl) ->
                try {
                    // episode number: prefer element .episode-list-item-number, else try data attribute, else regex on text or url
                    val numText = el.selectFirst(".episode-list-item-number")?.text()
                        ?: el.attr("data-episode-number").takeIf { it.isNotBlank() }
                        ?: el.selectFirst(".episode-list-item")?.attr("data-episode-search-query")
                        ?: el.text()

                    val episodeNumber = Regex("Episode\\s*(\\d+)|E\\s*(\\d+)|(\\d+)$")
                        .find(numText ?: "")?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull()
                        // fallback: try to extract digits from url (/episode-123/ or -episode-123)
                        ?: Regex("episode-(\\d+)|episode_(\\d+)|-(\\d+)/?$").find(episodeUrl)?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull()
                        ?: (episodes.size + 1)

                    val episodeTitle = el.selectFirst(".episode-list-item-title")?.text()?.trim()
                        ?: el.attr("title").takeIf { it.isNotBlank() }
                        ?: "Episode $episodeNumber"

                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.episode = episodeNumber
                        // Use the anime's title card (poster) as the episode thumbnail
                        this.posterUrl = poster
                    })
                } catch (ex: Exception) {
                    android.util.Log.e("An1me_EpisodeParse", "Error parsing an episode anchor: ${ex.message}")
                }
            }

            // Final sort and dedupe again by episode number
            val uniqueByNumber = episodes
                .distinctBy { it.episode } // keep first occurrence of each episode number
                .sortedBy { it.episode }

            episodes.clear()
            episodes.addAll(uniqueByNumber)
        } catch (e: Exception) {
            android.util.Log.e("An1me_EpisodeParse", "Error collecting all episode anchors: ${e.message}")
        }

        // Fallback: if none found, try older selectors (keeps robust)
        if (episodes.isEmpty()) {
            try {
                document.select("div.episode-list-display-box a.episode-list-item[href*='/watch/']").forEach { ep ->
                    try {
                        val episodeUrl = fixUrl(ep.attr("href"))
                        if (episodeUrl.isEmpty() || episodeUrl.contains("/anime/")) return@forEach

                        val episodeNumberText = ep.selectFirst(".episode-list-item-number")?.text()
                        val episodeNumber = episodeNumberText?.trim()?.toIntOrNull() ?: (episodes.size + 1)

                        val episodeTitle = ep.selectFirst(".episode-list-item-title")?.text()?.trim() ?: "Episode $episodeNumber"

                        episodes.add(newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.episode = episodeNumber
                            this.posterUrl = poster
                        })
                    } catch (ex: Exception) {
                        android.util.Log.e("An1me_EpisodeParse", "Fallback parse failed: ${ex.message}")
                    }
                }
                episodes.sortBy { it.episode }
            } catch (_: Exception) { /* ignore */ }
        }

        // ensure episodes sorted ascending
        episodes.sortBy { it.episode }

        // ---------------------------
        // Return load response with banner as main image for all anime (One Piece banner etc)
        // ---------------------------
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            // Use banner when available; this makes the top of details pages full-banner for everything
            this.posterUrl = fixUrlNull(bannerUrl)
            this.plot = enhancedDescription
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
                ?: return false.also { android.util.Log.d("An1me_Video", "No iframe found") }

            android.util.Log.d("An1me_Video", "Iframe src: $iframeSrc")

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) return false.also {
                android.util.Log.d("An1me_Video", "No base64 part found")
            }

            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            android.util.Log.d("An1me_Video", "Decoded URL: $decodedUrl")

            // -------------------------------------------------------
            // WeTransfer (special handling)
            // -------------------------------------------------------
            if (decodedUrl.contains("wetransfer.com", true) || decodedUrl.contains("collect.wetransfer.com", true)) {
                android.util.Log.d("An1me_Video", "Detected WeTransfer link, attempting extraction...")

                try {
                    val iframeHtml = app.get(iframeSrc).text
                    val cleanedHtml = iframeHtml
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("\\u003c", "<")
                        .replace("\\u003e", ">")
                        .replace("\\\\", "\\")
                        .replace("\\/", "/")

                    val match = Regex("""const\s+params\s*=\s*(\{.*?"sources".*?\});""", RegexOption.DOT_MATCHES_ALL)
                        .find(cleanedHtml)
                        ?.groupValues?.get(1)

                    if (match == null) {
                        android.util.Log.d("An1me_Video", "No JSON params found in WeTransfer iframe")
                        return false
                    }

                    val json = JSONObject(match)
                    val sources = json.optJSONArray("sources")
                    if (sources != null && sources.length() > 0) {
                        val videoUrl = sources.getJSONObject(0).getString("url")
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")
                            .replace("\\u003d", "=")

                        android.util.Log.d("An1me_Video", "Found WeTransfer video URL: $videoUrl")

                        callback(
                            createLink(
                                sourceName = name,
                                linkName = "$name (WeTransfer)",
                                url = videoUrl,
                                referer = iframeSrc,
                                quality = if (videoUrl.contains("1080")) Qualities.P1080.value else Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        return true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Error parsing WeTransfer iframe: ${e.message}", e)
                    return false
                }
            }

            // -------------------------------------------------------
            // Google Photos: emit multiple quality variants to avoid reload loops
            // -------------------------------------------------------
            if (decodedUrl.contains("photos.google.com", true)) {
                try {
                    android.util.Log.d("An1me_Video", "Detected Google Photos source — extracting quality variants")

                    val photoHtml = app.get(decodedUrl, referer = iframeSrc).text

                    val videoRegex = Regex("""(https:\/\/[^"'\s]+googleusercontent\.com[^"'\s]+)""")
                    val matches = videoRegex.findAll(photoHtml).toList()
                    if (matches.isEmpty()) {
                        android.util.Log.d("An1me_Video", "No googleusercontent links found")
                        return false
                    }

                    val raw = matches.first().value
                        .replace("\\u003d", "=")
                        .replace("\\u0026", "&")
                        .replace("\\/", "/")
                        .trim()

                    val baseUrl = raw.substringBefore("=m").substringBefore("?")
                    android.util.Log.d("An1me_Video", "Base Google Photos URL: $baseUrl")

                    val variants = listOf(
                        Pair("1080p", "=m22") to Qualities.P1080.value,
                        Pair("720p", "=m18") to Qualities.P720.value,
                        Pair("480p", "=m18") to Qualities.P480.value,
                        Pair("360p", "=m18") to Qualities.P360.value
                    )

                    for ((qualityInfo, qualityValue) in variants) {
                        val (qualityName, qualityParam) = qualityInfo
                        val qualityUrl = "$baseUrl$qualityParam"

                        android.util.Log.d("An1me_Video", "Adding Google Photos variant $qualityName -> $qualityUrl")

                        callback(
                            createLink(
                                sourceName = name,
                                linkName = "$name (Google Photos) $qualityName",
                                url = qualityUrl,
                                referer = decodedUrl,
                                quality = qualityValue,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                    return true
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Error extracting Google Photos video: ${e.message}", e)
                    return false
                }
            }

            // -------------------------------------------------------
            // M3U8 parsing (master playlist -> separate tracks)
            // -------------------------------------------------------
            if (decodedUrl.contains(".m3u8", true)) {
                android.util.Log.d("An1me_Video", "Detected M3U8 stream — parsing qualities")

                try {
                    val m3u8Response = app.get(decodedUrl).text
                    val lines = m3u8Response.lines()
                    var addedAnyQuality = false

                    lines.forEachIndexed { index, line ->
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val height = """RESOLUTION=\d+x(\d+)""".toRegex()
                                .find(line)?.groupValues?.get(1)?.toIntOrNull()

                            val quality = when (height) {
                                2160 -> Qualities.P2160.value
                                1440 -> Qualities.P1440.value
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                360 -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }

                            if (index + 1 < lines.size) {
                                val urlLine = lines[index + 1].trim()
                                if (!urlLine.startsWith("#") && urlLine.isNotEmpty()) {
                                    val fullUrl = if (urlLine.startsWith("http")) urlLine
                                    else "${decodedUrl.substringBeforeLast("/")}/$urlLine"

                                    val safeUrl = fullUrl
                                        .replace(" ", "%20")
                                        .replace("[", "%5B")
                                        .replace("]", "%5D")

                                    callback(
                                        createLink(
                                            sourceName = name,
                                            linkName = if (height != null) "${height}p" else name,
                                            url = safeUrl,
                                            referer = data,
                                            quality = quality,
                                            type = ExtractorLinkType.M3U8
                                        )
                                    )
                                    addedAnyQuality = true
                                }
                            }
                        }
                    }

                    if (!addedAnyQuality) {
                        val safeUrl = decodedUrl
                            .replace(" ", "%20")
                            .replace("[", "%5B")
                            .replace("]", "%5D")

                        callback(
                            createLink(
                                sourceName = name,
                                linkName = name,
                                url = safeUrl,
                                referer = data,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                    return true
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Error parsing M3U8: ${e.message}")
                    return false
                }
            }

            // -------------------------------------------------------
            // direct MP4 fallback
            // -------------------------------------------------------
            if (decodedUrl.contains(".mp4", true)) {
                android.util.Log.d("An1me_Video", "Detected direct MP4 video — playing directly")
                callback(
                    createLink(
                        sourceName = name,
                        linkName = "$name (MP4)",
                        url = decodedUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }

            android.util.Log.d("An1me_Video", "No valid video link found.")
            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error loading links: ${e.message}", e)
            return false
        }
    }
}
