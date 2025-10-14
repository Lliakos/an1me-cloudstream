package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
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

    // Fetch AniList metadata
    private suspend fun fetchAniListMetadata(titleEnglish: String?, titleRomaji: String?): Map<String, Any?> {
        val searchTerms = listOfNotNull(titleEnglish, titleRomaji).distinct()
        if (searchTerms.isEmpty()) return emptyMap()
        
        for (searchTerm in searchTerms) {
            try {
                val queryJson = JSONObject().apply {
                    put("query", """
                        query (${"$"}search: String) {
                            Media(search: ${"$"}search, type: ANIME) {
                                id
                                title { english romaji native }
                                bannerImage
                                coverImage { extraLarge large }
                                averageScore
                                meanScore
                                characters(page: 1, perPage: 6, sort: FAVOURITES_DESC) {
                                    edges {
                                        role
                                        node { name { full } }
                                    }
                                }
                            }
                        }
                    """.trimIndent())
                    put("variables", JSONObject().put("search", searchTerm))
                }

                val response = app.post(
                    "https://graphql.anilist.co",
                    requestBody = queryJson.toString(),
                    headers = mapOf("Content-Type" to "application/json")
                )
                
                val json = JSONObject(response.text)
                val media = json.optJSONObject("data")?.optJSONObject("Media")
                
                if (media != null) {
                    android.util.Log.d("An1me_AniList", "Found metadata for: $searchTerm")
                    
                    val banner = media.optString("bannerImage").takeIf { it.isNotBlank() }
                    val coverObj = media.optJSONObject("coverImage")
                    val cover = coverObj?.optString("extraLarge")?.takeIf { it.isNotBlank() }
                        ?: coverObj?.optString("large")?.takeIf { it.isNotBlank() }
                    
                    val avgScore = media.optInt("averageScore", -1).takeIf { it > 0 }
                    val meanScore = media.optInt("meanScore", -1).takeIf { it > 0 }
                    
                    val chars = mutableListOf<String>()
                    val charsObj = media.optJSONObject("characters")?.optJSONArray("edges")
                    if (charsObj != null) {
                        for (i in 0 until charsObj.length()) {
                            val edge = charsObj.optJSONObject(i) ?: continue
                            val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full")
                            val role = edge.optString("role", "")
                            if (!name.isNullOrBlank()) {
                                chars.add("$name ($role)")
                            }
                        }
                    }
                    
                    return mapOf(
                        "banner" to banner,
                        "cover" to cover,
                        "averageScore" to avgScore,
                        "meanScore" to meanScore,
                        "characters" to chars
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("An1me_AniList", "Error for '$searchTerm': ${e.message}")
            }
        }
        
        return emptyMap()
    }

    // Helper to get nonce for AJAX requests
    private suspend fun getNonce(): String? {
        return try {
            val response = app.get("$mainUrl/wp-admin/admin-ajax.php?action=get_nonce").text
            val json = JSONObject(response)
            json.optString("nonce").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            android.util.Log.e("An1me_Nonce", "Failed to get nonce: ${e.message}")
            null
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null

        // Only use Japanese/Romaji title
        val title = this.selectFirst("span[data-nt-title]")?.text()
            ?: this.selectFirst("span[data-en-title]")?.text()
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

        // Only Japanese title
        val title = this.selectFirst("span[data-nt-title]")?.text()
            ?: this.selectFirst("span[data-en-title]")?.text()
            ?: return null

        // Get banner image for spotlight
        val bannerUrl = this.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(bannerUrl)
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span[data-nt-title]")?.text()
            ?: this.selectFirst("span[data-en-title]")?.text()
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span[data-nt-title]")?.text()
            ?: this.selectFirst("span[data-en-title]")?.text()
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Spotlight Section (big banners at top)
        try {
            val spotlightItems = document.select(".swiper-spotlight .swiper-slide").mapNotNull { 
                it.toSpotlightResult() 
            }
            if (spotlightItems.isNotEmpty()) {
                homePages.add(HomePageList("Spotlight", spotlightItems, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing spotlight: ${e.message}")
        }

        // Trending Section
        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide").mapNotNull { 
                it.toTrendingResult() 
            }
            if (trendingItems.isNotEmpty()) {
                homePages.add(HomePageList("Τάσεις", trendingItems))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing trending: ${e.message}")
        }

        // Latest Episodes Section
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

        // Latest Anime Section
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

            val title = item.selectFirst("span[data-nt-title]")?.text()
                ?: item.selectFirst("span[data-en-title]")?.text()
                ?: return@mapNotNull null

            val posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Get both titles
        val titleEnglish = document.selectFirst("span[data-en-title]")?.text()
        val titleRomaji = document.selectFirst("span[data-nt-title]")?.text()
        
        // Use Japanese/Romaji as main title
        val title = titleRomaji ?: titleEnglish ?: document.selectFirst("h1")?.text() ?: "Unknown"

        android.util.Log.d("An1me_Load", "Loading: $title (EN: $titleEnglish, JP: $titleRomaji)")

        // Get poster from MAL (site image)
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        
        val description = document.selectFirst("div[data-synopsis]")?.text()
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }

        // Site metadata
        val siteMalScore = document.selectFirst("li:has(span:contains(MAL Βαθμολογια:))")?.text()?.substringAfter(":")?.trim()
        val siteStatus = document.selectFirst("li:has(span:contains(Κατάσταση:)) a")?.text()
        val siteEpisodeCount = document.selectFirst("li:has(span:contains(Επεισόδια:))")?.text()?.substringAfter(":")?.trim()
        val siteDuration = document.selectFirst("li:has(span:contains(Διάρκεια:))")?.text()?.substringAfter(":")?.trim()
        val siteStudio = document.selectFirst("li:has(span:contains(Στούντιο:)) a")?.text()

        // Fetch AniList metadata
        val anilistData = try {
            fetchAniListMetadata(titleEnglish, titleRomaji)
        } catch (e: Exception) {
            android.util.Log.e("An1me_AniList", "Failed to fetch: ${e.message}")
            emptyMap()
        }

        // Get banner from AniList
        val bannerUrl = fixUrlNull(anilistData["banner"] as? String)
        val coverUrl = fixUrlNull(anilistData["cover"] as? String)
        
        val finalPoster = coverUrl ?: poster
        val finalBanner = bannerUrl ?: finalPoster

        val aniScore = anilistData["averageScore"] ?: anilistData["meanScore"]
        val finalScore = aniScore ?: siteMalScore
        
        @Suppress("UNCHECKED_CAST")
        val characters = anilistData["characters"] as? List<String>

        // Enhanced description
        val enhancedDescription = buildString {
            description?.let { append(it).append("\n\n") }
            append("━━━━━━━━━━━━━━━━\n")
            
            if (finalScore != null) {
                val scoreStr = when (finalScore) {
                    is Int -> "$finalScore/100"
                    else -> finalScore.toString()
                }
                append("⭐ Score: $scoreStr\n")
            }
            
            siteStatus?.let { append("📺 Status: $it\n") }
            siteEpisodeCount?.let { append("🎬 Episodes: $it\n") }
            siteDuration?.let { append("⏱️ Duration: $it\n") }
            siteStudio?.let { append("🎨 Studio: $it\n") }
            
            if (!characters.isNullOrEmpty()) {
                append("\n👥 Characters:\n")
                characters.take(6).forEach { append("   • $it\n") }
            }
        }

        // Get anime ID from URL for AJAX request
        val animeId = url.substringAfterLast("/anime/").substringBefore("/").substringBefore("?")
        
        // Collect episodes using AJAX
        val episodes = mutableListOf<Episode>()
        
        try {
            // Get nonce first
            val nonce = getNonce()
            
            if (nonce != null) {
                android.util.Log.d("An1me_Episodes", "Got nonce: $nonce")
                
                // Make AJAX request for episodes
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val formData = mapOf(
                    "action" to "get_episodes",
                    "anime_id" to animeId,
                    "nonce" to nonce
                )
                
                try {
                    val episodesResponse = app.post(
                        ajaxUrl,
                        data = formData,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text
                    
                    val episodesJson = JSONObject(episodesResponse)
                    val episodesArray = episodesJson.optJSONArray("episodes")
                    
                    if (episodesArray != null) {
                        for (i in 0 until episodesArray.length()) {
                            val ep = episodesArray.getJSONObject(i)
                            val epNumber = ep.optInt("number", i + 1)
                            val epUrl = fixUrl(ep.optString("url", ""))
                            val epTitle = ep.optString("title", "Episode $epNumber")
                            
                            if (epUrl.isNotEmpty()) {
                                episodes.add(newEpisode(epUrl) {
                                    this.name = epTitle
                                    this.episode = epNumber
                                    this.posterUrl = finalPoster
                                })
                            }
                        }
                        android.util.Log.d("An1me_Episodes", "Loaded ${episodes.size} episodes via AJAX")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Episodes", "AJAX request failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_Episodes", "Error getting nonce: ${e.message}")
        }

        // Fallback: scrape from page if AJAX fails
        if (episodes.isEmpty()) {
            android.util.Log.d("An1me_Episodes", "AJAX failed, falling back to page scraping")
            
            val episodeListBox = document.selectFirst("div.episode-list-display-box")
            episodeListBox?.select("a.episode-list-item[href*='/watch/']")?.forEach { ep ->
                val episodeUrl = fixUrl(ep.attr("href"))
                if (episodeUrl.isEmpty() || episodeUrl.contains("/anime/")) return@forEach

                val episodeNumberText = ep.selectFirst(".episode-list-item-number")?.text()
                val episodeNumber = episodeNumberText?.trim()?.toIntOrNull() ?: episodes.size + 1

                val episodeTitle = ep.selectFirst(".episode-list-item-title")?.text()?.trim() 
                    ?: "Episode $episodeNumber"

                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.episode = episodeNumber
                    this.posterUrl = finalPoster
                })
            }
        }
        
        episodes.sortBy { it.episode }
        android.util.Log.d("An1me_Episodes", "Final episode count: ${episodes.size}")

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalBanner
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

            // Handle WeTransfer
            if (decodedUrl.contains("wetransfer.com", true) || decodedUrl.contains("collect.wetransfer.com", true)) {
                android.util.Log.d("An1me_Video", "Detected WeTransfer link")

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
                        android.util.Log.d("An1me_Video", "No JSON params found")
                        return false
                    }

                    val json = JSONObject(match)
                    val sources = json.optJSONArray("sources")
                    if (sources != null && sources.length() > 0) {
                        val videoUrl = sources.getJSONObject(0).getString("url")
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")
                            .replace("\\u003d", "=")

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
                    android.util.Log.e("An1me_Video", "WeTransfer error: ${e.message}")
                    return false
                }
            }

            // Handle Google Photos
            if (decodedUrl.contains("photos.google.com", true)) {
                try {
                    android.util.Log.d("An1me_Video", "Detected Google Photos")

                    val photoHtml = app.get(decodedUrl, referer = iframeSrc).text
                    
                    val videoRegex = Regex("""(https:\/\/[^"'\s]+googleusercontent\.com[^"'\s]+)""")
                    val matches = videoRegex.findAll(photoHtml).toList()
                    
                    if (matches.isEmpty()) {
                        android.util.Log.d("An1me_Video", "No googleusercontent links found")
                        return false
                    }
                    
                    val qualityVariants = listOf(
                        Pair("1080p", "=m37") to Qualities.P1080.value,
                        Pair("720p", "=m22") to Qualities.P720.value,
                        Pair("480p", "=m18") to Qualities.P480.value,
                        Pair("360p", "=m18") to Qualities.P360.value
                    )
                    
                    val baseUrl = matches.first().value
                        .replace("\\u003d", "=")
                        .replace("\\u0026", "&")
                        .replace("\\/", "/")
                        .replace("\\", "")
                        .substringBefore("=m")
                        .substringBefore("?")
                    
                    for ((qualityInfo, qualityValue) in qualityVariants) {
                        val (qualityName, qualityParam) = qualityInfo
                        val qualityUrl = "$baseUrl$qualityParam"
                        
                        callback(
                            createLink(
                                sourceName = name,
                                linkName = qualityName,
                                url = qualityUrl,
                                referer = decodedUrl,
                                quality = qualityValue,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                    return true
                    
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Google Photos error: ${e.message}")
                    return false
                }
            }

            // Handle M3U8
            if (decodedUrl.contains(".m3u8", true)) {
                android.util.Log.d("An1me_Video", "Detected M3U8")
                
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
                                val urlLine = lines[index + 1]
                                if (!urlLine.startsWith("#")) {
                                    val fullUrl = if (urlLine.startsWith("http")) {
                                        urlLine
                                    } else {
                                        "${decodedUrl.substringBeforeLast("/")}/$urlLine"
                                    }

                                    val safeUrl = fullUrl
                                        .replace(" ", "%20")
                                        .replace("[", "%5B")
                                        .replace("]", "%5D")

                                    callback(
                                        createLink(
                                            sourceName = name,
                                            linkName = "${height}p",
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
                    android.util.Log.e("An1me_Video", "M3U8 error: ${e.message}")
                    return false
                }
            }

            // Handle direct MP4
            if (decodedUrl.contains(".mp4", true)) {
                android.util.Log.d("An1me_Video", "Detected MP4")
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

            android.util.Log.d("An1me_Video", "No valid video link found")
            return false

        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error loading links: ${e.message}")
            return false
        }
    }
}