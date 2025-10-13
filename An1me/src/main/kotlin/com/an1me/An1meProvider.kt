package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.util.Base64
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import java.util.regex.Pattern

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // Helper to create extractor links using newExtractorLink builder
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

    // Normalize https
    private fun ensureHttps(url: String?): String? {
        if (url == null) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            else -> url
        }
    }

    // Try to pick the best English title or fallback to site title
    private fun extractEnglishTitleFromDoc(document: Document, el: Element?): String? {
        el?.selectFirst("span[data-en-title]")?.text()?.let { return it }
        document.selectFirst("meta[property=og:title]")?.attr("content")?.let { return it }
        return document.selectFirst("h1.entry-title, h1")?.text()?.trim()
    }

    // ---------------- Search / Home helpers ----------------
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
            ?: this.selectFirst("span[data-nt-title]")?.text()
            ?: link.attr("title")
            ?: return null

        val bannerUrl = this.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(bannerUrl)
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text()
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
            ?: this.selectFirst("span[data-nt-title]")?.text()
            ?: link.attr("title")
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------- Main page ----------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Spotlight
        try {
            val selectors = listOf(
                ".spotlight .swiper-slide",
                ".home-spotlight .swiper-slide",
                ".featured .swiper-slide",
                ".hero .swiper-slide"
            )
            val elements = selectors.flatMap { document.select(it).toList() }.distinct().mapNotNull { it.toSpotlightResult() }
            if (elements.isNotEmpty()) homePages.add(HomePageList("Featured", elements, isHorizontalImages = true))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Spotlight parse error: ${e.message}")
        }

        // Trending
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

    // ---------------- Search ----------------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=${URLEncoder.encode(query, "UTF-8")}"
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

    // ---------------- AniList metadata ----------------
    /**
     * Fetch AniList metadata for the given title.
     * Returns a map with keys:
     *  - "banner": String? (bannerImage)
     *  - "cover": String? (cover large)
     *  - "avg": Int? (averageScore)
     *  - "characters": List<Map<String,String?>>?
     *  - "episodes": Int? (episodes count)
     *  - "trailer": String? (youtube url if available or site+id)
     */
    private suspend fun fetchAnilistMetadata(title: String?): Map<String, Any?> {
        if (title.isNullOrBlank()) return emptyMap()
        try {
            val query = """
                {
                  "query":"query (\$search:String){Media(search:\$search,type:ANIME){id title {english romaji native} bannerImage coverImage{large} averageScore episodes trailer { id site thumbnail } characters(perPage:6){edges{node{name{full} image{large}}}}}}",
                  "variables":{"search":"${title.replace("\"", "\\\"")}"}
                }
            """.trimIndent()

            val body = query.toRequestBody("application/json".toMediaType())

            val response = try {
                app.post(
                    url = "https://graphql.anilist.co",
                    requestBody = body,
                    headers = mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                android.util.Log.e("An1me_Anilist", "Anilist request failed: ${e.message}")
                null
            }

            if (response == null) return emptyMap()
            val json = JSONObject(response.text)
            val media = json.optJSONObject("data")?.optJSONObject("Media") ?: return emptyMap()

            val banner = media.optString("bannerImage", null)
            val cover = media.optJSONObject("coverImage")?.optString("large", null)
            val avg = if (media.has("averageScore")) media.optInt("averageScore", -1) else -1
            val episodes = if (media.has("episodes") && media.optInt("episodes", -1) > 0) media.optInt("episodes") else null

            val trailerObj = media.optJSONObject("trailer")
            val trailer = if (trailerObj != null) {
                val site = trailerObj.optString("site", "")
                val id = trailerObj.optString("id", "")
                when {
                    site.equals("youtube", true) && id.isNotEmpty() -> "https://youtu.be/$id"
                    site.isNotBlank() && id.isNotBlank() -> "$site/$id"
                    else -> trailerObj.optString("thumbnail", null)
                }
            } else null

            val chars = mutableListOf<Map<String, String?>>()
            val charsArr = media.optJSONObject("characters")?.optJSONArray("edges")
            if (charsArr != null) {
                for (i in 0 until charsArr.length()) {
                    val node = charsArr.optJSONObject(i)?.optJSONObject("node") ?: continue
                    val name = node.optJSONObject("name")?.optString("full")
                    val img = node.optJSONObject("image")?.optString("large")
                    chars.add(mapOf("name" to name, "image" to img))
                }
            }

            return mapOf(
                "banner" to (if (banner.isNullOrBlank()) null else banner),
                "cover" to (if (cover.isNullOrBlank()) null else cover),
                "avg" to if (avg <= 0) null else avg,
                "characters" to chars,
                "episodes" to episodes,
                "trailer" to trailer
            )
        } catch (e: Exception) {
            android.util.Log.e("An1me_Anilist", "Error parsing anilist response: ${e.message}")
            return emptyMap()
        }
    }

    // ---------------- Episodes helper: try to extract from scripts ----------------
    private fun parseEpisodesFromScripts(document: Document): List<String> {
        val out = mutableListOf<String>()
        try {
            val scripts = document.select("script")
            val pattern = Pattern.compile("(?:\"|')(/watch/[^\"'\\s]+)(?:\"|')", Pattern.CASE_INSENSITIVE)
            for (s in scripts) {
                val text = s.data()
                val m = pattern.matcher(text)
                while (m.find()) {
                    val href = fixUrl(m.group(1))
                    if (href.isNotBlank()) out.add(href)
                }
            }
        } catch (_: Exception) { /* ignore */ }
        return out.distinct()
    }

    // Try to build plausible watch URL patterns from anime page URL and episode number
    private fun buildWatchUrlFromAnimePage(animeUrl: String, episodeNumber: Int): String {
        try {
            // animeUrl e.g. https://an1me.to/anime/slug
            val path = animeUrl.substringAfter("://").substringAfter("/") // remove protocol
            val segments = path.split("/")
            val slug = if (segments.size >= 2 && segments[0].equals("anime", true)) segments[1] else segments.lastOrNull() ?: ""
            if (slug.isBlank()) return "$animeUrl#ep$episodeNumber"
            // Try a few common patterns
            val candidates = listOf(
                "$mainUrl/watch/$slug/$episodeNumber",
                "$mainUrl/watch/$slug-episode-$episodeNumber",
                "$mainUrl/watch/$slug-ep-$episodeNumber",
                "$mainUrl/watch/$slug-$episodeNumber",
                "$animeUrl#ep$episodeNumber"
            )
            return candidates.first()
        } catch (_: Exception) {
            return "$animeUrl#ep$episodeNumber"
        }
    }

    // ---------------- Load (details + episodes) ----------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = extractEnglishTitleFromDoc(document, document.selectFirst("div.entry-header, header, .title, .entry"))
            ?: "Unknown"

        // site poster and banner
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val siteBanner = document.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")

        // tags & site metadata
        val description = document.selectFirst("div[data-synopsis]")?.text()
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }
        val siteEpisodeCount = document.selectFirst("li:has(span:contains(Επεισόδια:))")?.text()?.substringAfter(":")?.trim()?.toIntOrNull()

        // fetch anilist to enrich
        val anilist = try { fetchAnilistMetadata(title) } catch (e: Exception) { android.util.Log.e("An1me_Anilist", "fetch error: ${e.message}"); emptyMap() }

        val finalBanner = ensureHttps((anilist["banner"] as? String) ?: siteBanner ?: poster)
        val finalCover = ensureHttps((anilist["cover"] as? String) ?: poster)
        val aniEpisodesCount = (anilist["episodes"] as? Int) ?: siteEpisodeCount ?: -1
        val trailerUrl = anilist["trailer"] as? String

        // build enhanced description
        val avgScore = anilist["avg"] ?: ""
        val enhancedDescription = buildString {
            description?.let { append(it).append("\n\n") }
            append("━━━━━━━━━━━━━━━━\n")
            if (avgScore != "") append("⭐ Score: $avgScore\n")
            anilist["episodes"]?.let { append("🎬 Episodes (AniList): $it\n") }
            siteEpisodeCount?.let { append("📌 Episodes (Site): $it\n") }
            tags.forEach { append("• $it\n") }
            trailerUrl?.let { append("\nTrailer: $it\n") }
        }

        // COLLECT EPISODES (robust)
        val episodes = mutableListOf<com.lagradost.cloudstream3.models.Episode>()

        try {
            // 1) collect official anchor links
            val anchors = document.select("a[href*='/watch/']").mapNotNull { el ->
                val href = fixUrl(el.attr("href"))
                if (href.isBlank() || href.contains("/anime/")) return@mapNotNull null
                Pair(el, href)
            }.distinctBy { it.second }

            // 2) hidden list anchors
            val hiddenAnchors = document.select("div.episode-list-display-box a.episode-list-item[href*='/watch/']").mapNotNull { el ->
                val href = fixUrl(el.attr("href"))
                if (href.isBlank() || href.contains("/anime/")) return@mapNotNull null
                Pair(el, href)
            }.distinctBy { it.second }

            // 3) script-derived
            val scriptEpisodes = parseEpisodesFromScripts(document).map { Pair<Element?, String?>(null, it) }

            // Merge preserving order anchors -> hidden -> script
            val merged = mutableListOf<Pair<Element?, String>>()
            anchors.forEach { merged.add(Pair(it.first, it.second)) }
            hiddenAnchors.forEach { if (merged.none { m -> m.second == it.second }) merged.add(Pair(it.first, it.second)) }
            scriptEpisodes.forEach { if (it.second != null && merged.none { m -> m.second == it.second }) merged.add(Pair(null, it.second!!)) }

            // convert merged to Episode objects
            for ((el, href) in merged) {
                try {
                    val epTitleText = el?.selectFirst(".episode-list-item-title")?.text() ?: el?.attr("title") ?: ""
                    val numFromEl = el?.selectFirst(".episode-list-item-number")?.text()
                    val epNum = numFromEl?.trim()?.toIntOrNull()
                        ?: Regex("Episode\\s*(\\d+)|E\\s*(\\d+)|(\\d+)$").find(epTitleText ?: "")?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull()
                        ?: Regex("episode-(\\d+)|episode_(\\d+)|-(\\d+)/?$").find(href)?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull()
                        ?: (episodes.size + 1)

                    val epTitle = if (!epTitleText.isNullOrBlank()) epTitleText else "Episode $epNum"

                    episodes.add(newEpisode(href) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = finalCover
                    })
                } catch (ex: Exception) {
                    android.util.Log.e("An1me_EpisodeParse", "Error parsing episode $href: ${ex.message}")
                }
            }

            // If aniList says there are more episodes than we found, generate placeholders
            if (aniEpisodesCount > 0 && aniEpisodesCount > episodes.size) {
                val slugCandidates = url.substringAfter("/anime/").substringBefore("/").takeIf { it.isNotBlank() }
                for (i in 1..aniEpisodesCount) {
                    if (episodes.any { it.episode == i }) continue
                    val generatedUrl = buildWatchUrlFromAnimePage(url, i)
                    episodes.add(newEpisode(generatedUrl) {
                        this.name = "Episode $i"
                        this.episode = i
                        this.posterUrl = finalCover
                    })
                }
            }

            // Deduplicate and sort
            val unique = episodes.distinctBy { it.episode }.sortedBy { it.episode }
            episodes.clear()
            episodes.addAll(unique)

        } catch (e: Exception) {
            android.util.Log.e("An1me_EpisodeCollect", "Error collecting episodes: ${e.message}")
        }

        // If still empty, create placeholder episodes up to AniList count (so UI shows full list)
        if (episodes.isEmpty() && aniEpisodesCount > 0) {
            for (i in 1..aniEpisodesCount) {
                val genUrl = buildWatchUrlFromAnimePage(url, i)
                episodes.add(newEpisode(genUrl) {
                    this.name = "Episode $i"
                    this.episode = i
                    this.posterUrl = finalCover
                })
            }
        }

        // Finalize load response with AniList banner/cover & trailer included in description
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(finalBanner ?: finalCover)
            this.plot = enhancedDescription
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------- Video extractor (unchanged behavior, supports WeTransfer/Google/m3u8/mp4) ----------------
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

                    val match = Regex("""(?:const|var|let)\s+params\s*=\s*(\{.*?"sources".*?\});""", RegexOption.DOT_MATCHES_ALL)
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
                        Pair("1080p", "=m37") to Qualities.P1080.value,
                        Pair("720p", "=m22") to Qualities.P720.value,
                        Pair("480p", "=m18") to Qualities.P480.value,
                        Pair("360p", "=m18") to Qualities.P360.value
                    )

                    for ((qualityInfo, qualityValue) in variants) {
                        val (qualityName, qualityParam) = qualityInfo
                        val qualityUrl = "$baseUrl$qualityParam"
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
                                    val final = if (urlLine.startsWith("http")) urlLine else "${decodedUrl.substringBeforeLast("/")}/$urlLine"
                                    val safeUrl = final.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
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
                        val safeUrl = decodedUrl.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
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
