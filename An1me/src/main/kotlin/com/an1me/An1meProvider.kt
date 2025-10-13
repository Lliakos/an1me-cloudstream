package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.util.Base64
import java.util.regex.Pattern

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

    // -----------------------
    // Helpers
    // -----------------------
    private fun ensureHttps(url: String?): String? {
        if (url == null) return null
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("http://")) return url.replaceFirst("http://", "https://")
        return url
    }

    // Prefer English title; fallback to romaji or og:title or header
    private fun extractEnglishTitleFromDoc(document: Document, el: Element?): String? {
        el?.let {
            val en = it.selectFirst("span[data-en-title]")?.text()
            if (!en.isNullOrBlank()) return en
        }
        val enMeta = document.selectFirst("meta[property=og:title]")?.attr("content")
        if (!enMeta.isNullOrBlank()) return enMeta
        return document.selectFirst("h1.entry-title, h1")?.text()?.trim()
    }

    // -----------------------
    // Search / Home helpers
    // -----------------------
    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null

        // English only
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
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
            ?: this.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
            ?: return null

        // Prefer title-card/poster for spotlight; fallback to banner
        val posterImg = this.selectFirst("img")?.attr("src")
        val bannerImg = this.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")
        val useImg = posterImg ?: bannerImg

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(useImg)
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
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    // -----------------------
    // Main page
    // -----------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Spotlight -> first list (top slider)
        try {
            val selectors = listOf(
                ".spotlight .swiper-slide",
                ".home-spotlight .swiper-slide",
                ".featured .swiper-slide",
                ".hero .swiper-slide",
                ".main-slider .swiper-slide"
            )
            val elements = selectors.flatMap { document.select(it).toList() }.distinct().mapNotNull { it.toSpotlightResult() }
            if (elements.isNotEmpty()) homePages.add(HomePageList("Featured", elements, isHorizontalImages = true))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Spotlight parse error: ${e.message}")
        }

        // Trending normal cards
        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide, .trending .swiper-slide").mapNotNull { it.toTrendingResult() }
            if (trendingItems.isNotEmpty()) homePages.add(HomePageList("Τάσεις", trendingItems))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Trending parse error: ${e.message}")
        }

        // Latest episodes
        try {
            val latestSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια))")
            val latestItems = latestSection?.select(".kira-grid-listing > div")?.mapNotNull { it.toLatestEpisodeResult() } ?: emptyList()
            if (latestItems.isNotEmpty()) homePages.add(HomePageList("Καινούργια Επεισόδια", latestItems))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Latest episodes parse error: ${e.message}")
        }

        // Latest anime
        try {
            val items = document.select("li").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) homePages.add(HomePageList("Καινούργια Anime", items))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Latest anime parse error: ${e.message}")
        }

        return HomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document
        return document.select("#first_load_result > div").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = item.selectFirst("span[data-en-title]")?.text()
                ?: item.selectFirst("img")?.attr("alt")
                ?: link.attr("title")
                ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    // -----------------------
    // Try to extract episodes from inline scripts (if site injects episodes via JS)
    // -----------------------
    private fun parseEpisodesFromScripts(document: Document): List<Pair<String, Element?>> {
        val results = mutableListOf<Pair<String, Element?>>()
        try {
            val scripts = document.select("script")
            val pattern = Pattern.compile("(?:\"|')(/watch/[^\"'\\s]+)(?:\"|')", Pattern.CASE_INSENSITIVE)
            for (s in scripts) {
                val text = s.data()
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    val href = fixUrl(matcher.group(1))
                    if (href.isNotEmpty()) results.add(Pair(href, null))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_EpisodeJSParse", "Script parse error: ${e.message}")
        }
        return results.distinctBy { it.first }
    }

    // -----------------------
    // Anilist metadata helper (GraphQL). Attempts to enrich metadata for all anime.
    // Falls back gracefully if network fails.
    // -----------------------
    private suspend fun fetchAnilistMetadata(titleEnglish: String?): Map<String, Any?> {
        if (titleEnglish.isNullOrBlank()) return emptyMap()
        try {
            val query = """
                {"query":"query (\$search:String){Media(search:\$search,type:ANIME){id title { english romaji } bannerImage coverImage siteUrl averageScore characters(perPage:6){edges{node{name{full} native} image{large} } }}}","variables":{"search":"${titleEnglish.replace("\"","\\\"")}"}} 
            """.trimIndent()

            val response = try {
                app.post("https://graphql.anilist.co", query, headers = mapOf("Content-Type" to "application/json"))
            } catch (e: Exception) {
                android.util.Log.e("An1me_Anilist", "Anilist request failed: ${e.message}")
                null
            }
            if (response == null) return emptyMap()
            val body = response.text
            val json = JSONObject(body)
            val data = json.optJSONObject("data")?.optJSONObject("Media") ?: return emptyMap()
            val banner = data.optString("bannerImage", "")
            val cover = data.optString("coverImage", "")
            val avg = if (data.has("averageScore")) data.optInt("averageScore", -1) else -1

            // characters (build small list)
            val chars = mutableListOf<Map<String, String?>>()
            val charsObj = data.optJSONObject("characters")?.optJSONArray("edges")
            if (charsObj != null) {
                for (i in 0 until charsObj.length()) {
                    val edge = charsObj.optJSONObject(i)?.optJSONObject("node") ?: continue
                    val name = edge.optJSONObject("name")?.optString("full")
                    val img = edge.optJSONObject("image")?.optString("large")
                    chars.add(mapOf("name" to name, "image" to img))
                }
            }

            return mapOf("banner" to (if (banner.isNullOrBlank()) null else banner),
                "cover" to (if (cover.isNullOrBlank()) null else cover),
                "avg" to if (avg <= 0) null else avg,
                "characters" to chars)
        } catch (e: Exception) {
            android.util.Log.e("An1me_Anilist", "Error parsing Anilist response: ${e.message}")
            return emptyMap()
        }
    }

    // -----------------------
    // Main load (episode list + metadata)
    // -----------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // English title priority
        val title = extractEnglishTitleFromDoc(document, document.selectFirst("div.entry-header, header, .title, .entry"))
            ?: "Unknown"

        // site poster and banner
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val bannerFromSite = document.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")
        val banner = fixUrlNull(bannerFromSite ?: poster)

        val description = document.selectFirst("div[data-synopsis]")?.text()
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }

        // basic metadata from site
        val siteMalScore = document.selectFirst("li:has(span:contains(MAL Βαθμολογια:))")?.text()?.substringAfter(":")?.trim()
        val siteStatus = document.selectFirst("li:has(span:contains(Κατάσταση:)) a")?.text()
        val siteEpisodeCount = document.selectFirst("li:has(span:contains(Επεισόδια:))")?.text()?.substringAfter(":")?.trim()

        // Try to fetch Anilist metadata to ensure banner + characters + average score for all anime
        val anilistData = try {
            fetchAnilistMetadata(title)
        } catch (e: Exception) {
            android.util.Log.e("An1me_Anilist", "Anilist fetch exception: ${e.message}")
            emptyMap<String, Any?>()
        }

        val finalBanner = ensureHttps((anilistData["banner"] as? String) ?: banner)
        val finalPoster = ensureHttps((anilistData["cover"] as? String) ?: poster) ?: finalBanner

        val avgScore = anilistData["avg"] ?: siteMalScore
        val characters = anilistData["characters"] as? List<*>

        // Build enhanced description with Anilist fallback data
        val enhancedDescription = buildString {
            description?.let { append(it).append("\n\n") }
            append("━━━━━━━━━━━━━━━━\n")
            if (avgScore != null) append("⭐ MAL/AniList Score: $avgScore\n")
            siteStatus?.let { append("📺 Status: $it\n") }
            siteEpisodeCount?.let { append("🎬 Episodes: $it\n") }
            poster?.let { append("\n") }
            if (characters != null && characters.isNotEmpty()) {
                append("\nTop characters:\n")
                (characters.take(4)).forEach {
                    val m = it as? Map<*, *>
                    val n = m?.get("name") as? String
                    append("• $n\n")
                }
            }
        }

        // -----------------------
        // EPISODE COLLECTION (robust)
        // -----------------------
        val episodes = mutableListOf<Episode>()
        try {
            // 1) Collect all anchors that resemble episode watch links
            val anchors = document.select("a[href*='/watch/']").mapNotNull { el ->
                val href = fixUrl(el.attr("href"))
                if (href.isNullOrBlank() || href.contains("/anime/")) return@mapNotNull null
                Pair(el, href)
            }.distinctBy { it.second }

            android.util.Log.d("An1me_EpisodeDebug", "Anchor-based episode count: ${anchors.size}")

            // 2) Collect hidden episode-list-display-box anchors (some pages use this)
            val hiddenAnchors = document.select("div.episode-list-display-box a.episode-list-item[href*='/watch/']").mapNotNull { el ->
                val href = fixUrl(el.attr("href"))
                if (href.isNullOrBlank() || href.contains("/anime/")) return@mapNotNull null
                Pair(el, href)
            }.distinctBy { it.second }
            android.util.Log.d("An1me_EpisodeDebug", "Hidden-box anchor count: ${hiddenAnchors.size}")

            // 3) Script-derived episodes (if JS injected)
            val scriptEpisodes = parseEpisodesFromScripts(document)
            android.util.Log.d("An1me_EpisodeDebug", "Script-derived episode count: ${scriptEpisodes.size}")

            // Merge sources, with anchors first (preserve site order), then hidden, then script ones
            val merged = mutableListOf<Pair<Element?, String>>()
            anchors.forEach { merged.add(Pair(it.first, it.second)) }
            hiddenAnchors.forEach { if (merged.none { m -> m.second == it.second }) merged.add(Pair(it.first, it.second)) }
            scriptEpisodes.forEach { if (merged.none { m -> m.second == it.first }) merged.add(Pair(null, it.first)) }

            android.util.Log.d("An1me_EpisodeDebug", "Total merged candidates: ${merged.size}")

            // If we only have ~30 and the page claims more episodes (episodeCount), try to find embedded JSON list (alternative)
            val claimedCount = siteEpisodeCount?.filter { it.isDigit() }?.toIntOrNull() ?: -1
            if (merged.size < 60 && claimedCount > merged.size) {
                // look for JSON arrays named "episodes" or similar inside scripts
                val scripts = document.select("script").map { it.data() }.joinToString("\n")
                val episodesJsonPattern = Pattern.compile("""(episodes|episode_list|episodeList)\s*[:=]\s*(\[[^\]]{50,}\])""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
                val m = episodesJsonPattern.matcher(scripts)
                if (m.find()) {
                    val jsonText = m.group(2)
                    try {
                        val arr = JSONObject("{ \"a\": $jsonText }").optJSONArray("a")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val href = obj.optString("url").takeIf { it.isNotBlank() } ?: obj.optString("link")
                                if (href.isNullOrBlank()) continue
                                val fixed = fixUrl(href)
                                if (merged.none { it.second == fixed }) merged.add(Pair(null, fixed))
                            }
                            android.util.Log.d("An1me_EpisodeDebug", "Found ${arr.length()} episodes inside embedded JSON")
                        }
                    } catch (ex: Exception) {
                        android.util.Log.d("An1me_EpisodeDebug", "Embedded JSON parse failed: ${ex.message}")
                    }
                }
            }

            // Now parse each merged candidate into Episode objects (robust number parsing)
            for ((el, href) in merged) {
                try {
                    val elText = el?.text() ?: ""
                    // Try multiple ways to get episode number
                    val numText = el?.selectFirst(".episode-list-item-number")?.text()
                        ?: el?.attr("data-episode-number")?.takeIf { it.isNotBlank() }
                        ?: el?.attr("data-episode-search-query")?.takeIf { it.isNotBlank() }
                        ?: elText
                        ?: href

                    val epNum = Regex("Episode\\s*(\\d+)|E\\s*(\\d+)|(\\d+)$").find(numText ?: "")?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull()
                        ?: Regex("episode-(\\d+)|episode_(\\d+)|-(\\d+)/?$").find(href)?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull()
                        ?: (episodes.size + 1)

                    val epTitle = el?.selectFirst(".episode-list-item-title")?.text()?.trim()
                        ?: el?.attr("title")?.takeIf { it.isNotBlank() }
                        ?: "Episode $epNum"

                    episodes.add(newEpisode(href) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = finalPoster // use Anilist cover or site poster
                    })
                } catch (ex: Exception) {
                    android.util.Log.e("An1me_EpisodeParse", "Episode parse error for $href : ${ex.message}")
                }
            }

            // final dedupe by episode number, sort ascending
            val unique = episodes.distinctBy { it.episode }.sortedBy { it.episode }
            episodes.clear()
            episodes.addAll(unique)

            android.util.Log.d("An1me_EpisodeDebug", "Final episode count after merge/dedupe: ${episodes.size}")
        } catch (e: Exception) {
            android.util.Log.e("An1me_EpisodeCollect", "Error collecting episodes: ${e.message}")
        }

        // Fallback: if episodes empty, try original hidden box parser (legacy)
        if (episodes.isEmpty()) {
            try {
                document.select("div.episode-list-display-box a.episode-list-item[href*='/watch/']").forEach { ep ->
                    val episodeUrl = fixUrl(ep.attr("href"))
                    if (episodeUrl.isNullOrBlank() || episodeUrl.contains("/anime/")) return@forEach
                    val episodeNumberText = ep.selectFirst(".episode-list-item-number")?.text()
                    val episodeNumber = episodeNumberText?.trim()?.toIntOrNull() ?: episodes.size + 1
                    val episodeTitle = ep.selectFirst(".episode-list-item-title")?.text()?.trim() ?: "Episode $episodeNumber"
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.episode = episodeNumber
                        this.posterUrl = finalPoster
                    })
                }
                episodes.sortBy { it.episode }
            } catch (_: Exception) { /* ignore */ }
        }

        // Return load response: use banner as main poster for details (big banner)
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(finalBanner ?: finalPoster)
            this.plot = enhancedDescription
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // -----------------------
    // loadLinks unchanged (we keep working extractor logic)
    // -----------------------
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

            // WeTransfer
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

            // Google Photos
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

            // M3U8
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

            // MP4 fallback
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
