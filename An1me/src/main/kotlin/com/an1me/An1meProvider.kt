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

    /**
     * Spotlight result: returns an item that prefers banner images (stretched) but falls back to poster.
     * We'll use this list to populate the built-in top slider (first HomePageList).
     */
    private fun Element.toSpotlightResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val enTitle = this.selectFirst("span[data-en-title]")?.text()
        val ntTitle = this.selectFirst("span[data-nt-title]")?.text()
        val title = enTitle ?: ntTitle ?: link.attr("title") ?: this.selectFirst("img")?.attr("alt") ?: return null

        // Prefer banner image (wide) from Anilist if present; else use poster
        val bannerUrl = this.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            // For spotlight we want a stretched/banner-like image; set posterUrl to banner when available
            this.posterUrl = fixUrlNull(bannerUrl)
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        // Keep same basic structure as search result, but used for trending cards
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val enTitle = this.selectFirst("span[data-en-title]")?.text()
        val ntTitle = this.selectFirst("span[data-nt-title]")?.text()
        val title = enTitle ?: ntTitle ?: link.attr("title") ?: this.selectFirst("img")?.attr("alt") ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val enTitle = this.selectFirst("span[data-en-title]")?.text()
        val ntTitle = this.selectFirst("span[data-nt-title]")?.text()
        val title = enTitle ?: ntTitle ?: link.attr("title") ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // ---------------------------
        // SPOTLIGHT -> populate top slider (first HomePageList)
        // ---------------------------
        try {
            // Try several common spotlight selectors to be robust
            val spotlightSelectors = listOf(
                ".spotlight .swiper-slide",
                ".home-spotlight .swiper-slide",
                ".featured .swiper-slide",
                ".hero .swiper-slide",
                ".main-slider .swiper-slide"
            )

            val spotlightElements = spotlightSelectors
                .flatMap { sel -> document.select(sel).toList() }
                .distinct() // avoid duplicates
                .mapNotNull { it.toSpotlightResult() }

            if (spotlightElements.isNotEmpty()) {
                // Insert spotlight as first HomePageList; this list will be used by Cloudstream's top slider UI
                homePages.add(HomePageList("Featured", spotlightElements, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing spotlight: ${e.message}")
        }

        // ---------------------------
        // Trending Section (normal sized title cards)
        // ---------------------------
        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide, .trending .swiper-slide").mapNotNull {
                it.toTrendingResult()
            }
            if (trendingItems.isNotEmpty()) {
                // Do NOT use isHorizontalImages here — trending should be normal title cards
                homePages.add(HomePageList("Τάσεις", trendingItems))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing trending: ${e.message}")
        }

        // ---------------------------
        // Latest Episodes Section
        // ---------------------------
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

        // ---------------------------
        // Latest Anime Section (general)
        // ---------------------------
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

            val enTitle = item.selectFirst("span[data-en-title]")?.text()
            val ntTitle = item.selectFirst("span[data-nt-title]")?.text()
            val title = enTitle ?: ntTitle ?: return@mapNotNull null

            val posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()

        // Get background/banner from MAL/Anilist if available, otherwise use poster
        val bannerUrl = document.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")
            ?: poster

        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map {
            it.text().trim()
        }

        // Extract metadata
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

        // Build enhanced description
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

        // Extract trailer
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
        // Episodes: collect ALL episodes from all ".episode-list-display-box" elements
        // This fixes the 'last 30 episodes only' issue by reading every box, including hidden ones.
        // ---------------------------
        val episodes = mutableListOf<Episode>()

        // Find all boxes that may include episode anchors (visible and hidden)
        val episodeBoxes = document.select("div.episode-list-display-box")
        episodeBoxes.forEach { box ->
            box.select("a.episode-list-item[href*='/watch/']").forEach { ep ->
                try {
                    val episodeUrl = fixUrl(ep.attr("href"))
                    if (episodeUrl.isEmpty() || episodeUrl.contains("/anime/")) return@forEach

                    // Episode number - prefer dedicated element, fallback to regex on title
                    val episodeNumberText = ep.selectFirst(".episode-list-item-number")?.text()
                    val episodeNumber = episodeNumberText?.trim()?.toIntOrNull()
                        ?: Regex("Episode\\s*(\\d+)|E\\s*(\\d+)", RegexOption.IGNORE_CASE)
                            .find(ep.text())?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull()
                        ?: (episodes.size + 1)

                    val episodeTitle = ep.selectFirst(".episode-list-item-title")?.text()?.trim()
                        ?: "Episode $episodeNumber"

                    // Use the anime card/poster for episode thumbnails (title card image)
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.episode = episodeNumber
                        this.posterUrl = poster // poster is the title-card image
                    })
                } catch (ex: Exception) {
                    // don't let one bad item fail everything
                    android.util.Log.e("An1me_EpisodeParse", "Error parsing an episode entry: ${ex.message}")
                }
            }
        }

        // Fallback: if still empty, try the swiper slides (older fallback)
        if (episodes.isEmpty()) {
            document.select("div.swiper-slide a[href*='/watch/']").forEach { ep ->
                try {
                    val episodeUrl = fixUrl(ep.attr("href"))
                    if (episodeUrl.isEmpty() || episodeUrl.contains("/anime/")) return@forEach

                    val episodeTitle = ep.attr("title")
                    val episodeNumber = Regex("Episode\\s*(\\d+)|E\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(episodeTitle)?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull()
                        ?: (episodes.size + 1)

                    episodes.add(newEpisode(episodeUrl) {
                        this.name = "Episode $episodeNumber"
                        this.episode = episodeNumber
                        this.posterUrl = poster
                    })
                } catch (ex: Exception) {
                    android.util.Log.e("An1me_EpisodeParse", "Error parsing swiper episode: ${ex.message}")
                }
            }
        }

        episodes.sortBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            // Use bannerUrl as the main top image when available, otherwise the poster
            this.posterUrl = fixUrlNull(bannerUrl)
            this.plot = enhancedDescription
            this.tags = tags
            // Add all episodes (poster for each episode set above)
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
            // WeTransfer (special handling) — unchanged behavior, robust extraction
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
            // Google Photos: extract multiple quality variants (1080/720/480)
            // ensures Cloudstream gets distinct links and does not loop
            // -------------------------------------------------------
            if (decodedUrl.contains("photos.google.com", true)) {
                try {
                    android.util.Log.d("An1me_Video", "Detected Google Photos source — extracting all quality variants")

                    val photoHtml = app.get(decodedUrl, referer = iframeSrc).text

                    // collect googleusercontent matches
                    val videoRegex = Regex("""(https:\/\/[^"'\s]+googleusercontent\.com[^"'\s]+)""")
                    val matches = videoRegex.findAll(photoHtml).toList()

                    if (matches.isEmpty()) {
                        android.util.Log.d("An1me_Video", "No googleusercontent links found")
                        return false
                    }

                    // base URL: strip any existing =m param or query
                    val raw = matches.first().value
                        .replace("\\u003d", "=")
                        .replace("\\u0026", "&")
                        .replace("\\/", "/")
                        .trim()

                    val baseUrl = raw.substringBefore("=m").substringBefore("?")

                    android.util.Log.d("An1me_Video", "Base Google Photos URL: $baseUrl")

                    // Build preferred variants (ordered: 1080, 720, 480, 360)
                    val variants = listOf(
                        Pair("1080p", "=m22") to Qualities.P1080.value, // m22 often maps to 1080 (varies by Google)
                        Pair("720p", "=m18") to Qualities.P720.value,
                        Pair("480p", "=m18") to Qualities.P480.value,
                        Pair("360p", "=m18") to Qualities.P360.value
                    )

                    // Try each variant and emit as separate links so Cloudstream shows them as options
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
            // M3U8: parse master playlist and expose each track/resolution as a separate M3U8 link
            // (resolves the "resolution under tracks" case)
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
