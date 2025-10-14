package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // AniList in-memory cache (case-insensitive keys)
    private val aniListCache = ConcurrentHashMap<String, JSONObject>()

    // ---------------- Helpers ----------------

    // Resolve lazy-loaded or normal image attributes
    private fun Element?.resolveImageUrl(): String? {
        if (this == null) return null
        val attrs = listOf("src", "data-src", "data-lazy", "data-original", "data-srcset", "data-bg")
        for (a in attrs) {
            val v = this.attr(a)
            if (!v.isNullOrEmpty()) {
                return v.split(Regex("[,\\s]"))[0].takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun Document?.ogImage(): String? {
        if (this == null) return null
        val meta = this.selectFirst("meta[property=og:image]") ?: this.selectFirst("meta[name=og:image]")
        return meta?.attr("content")?.takeIf { it.isNotBlank() }
    }

    // Clean title to increase AniList match success (strip parentheses, punctuation, extra whitespace)
    private fun cleanTitleForAniList(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace(Regex("\\(.*?\\)"), "") // remove anything in parentheses
            .replace(Regex("\\[.*?]"), "") // remove brackets
            .replace(Regex("[^\\p{L}\\p{N}\\s:]"), " ") // keep letters, numbers, spaces, colon
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // Fetch AniList (with caching). Uses JSON body and headers correctly.
    private suspend fun fetchAniListByTitle(title: String): JSONObject? {
        val key = title.trim().lowercase()
        aniListCache[key]?.let { return it } // cached

        try {
            val query = """
                query (${"$"}search: String) {
                  Media(search: ${"$"}search, type: ANIME) {
                    id
                    title { romaji english native }
                    bannerImage
                    coverImage { large medium }
                    averageScore
                    meanScore
                    episodes
                    description(asHtml: false)
                    characters(page: 1, perPage: 12) {
                      edges {
                        role
                        node { name { full } }
                      }
                    }
                    staff(page: 1, perPage: 10) {
                      edges {
                        role
                        node { name { full } }
                      }
                    }
                  }
                }
            """.trimIndent()

            val jsonBody = JSONObject()
                .put("query", query)
                .put("variables", JSONObject().put("search", title))

            val res = app.post(
                "https://graphql.anilist.co",
                json = jsonBody.toString(),
                headers = mapOf("Content-Type" to "application/json")
            ).text

            val media = JSONObject(res).optJSONObject("data")?.optJSONObject("Media")
            if (media != null) {
                aniListCache[key] = media
            }
            return media
        } catch (e: Exception) {
            android.util.Log.e("An1me_AniList", "AniList fetch failed: ${e.message}", e)
            return null
        }
    }

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

    // ---------------- Card helpers ----------------

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null

        // Prioritize English title only
        val en = this.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val other = this.selectFirst("span[data-nt-title]")?.text()
        val titleFinal = en ?: other ?: link.attr("title") ?: this.selectFirst("img")?.attr("alt") ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        return newAnimeSearchResponse(titleFinal, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val en = this.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val other = this.selectFirst("span[data-nt-title]")?.text()
        val titleFinal = en ?: other ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        return newAnimeSearchResponse(titleFinal, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val en = this.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val other = this.selectFirst("span[data-nt-title]")?.text()
        val titleFinal = en ?: other ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        return newAnimeSearchResponse(titleFinal, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------- Main page ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Trending -> normal cards (not wide)
        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide").mapNotNull { it.toTrendingResult() }
            if (trendingItems.isNotEmpty()) {
                homePages.add(HomePageList("Τάσεις", trendingItems, isHorizontalImages = false))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing trending: ${e.message}")
        }

        // Latest Episodes
        try {
            val latestEpisodesSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια))")
            val latestEpisodeItems = latestEpisodesSection?.select(".kira-grid-listing > div")?.mapNotNull { it.toLatestEpisodeResult() } ?: emptyList()
            if (latestEpisodeItems.isNotEmpty()) {
                homePages.add(HomePageList("Καινούργια Επεισόδια", latestEpisodeItems))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing latest episodes: ${e.message}")
        }

        // Latest Anime
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

    // ---------------- Search ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document
        return document.select("#first_load_result > div").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val en = item.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
            val other = item.selectFirst("span[data-nt-title]")?.text()
            val titleFinal = en ?: other ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.selectFirst("img")?.resolveImageUrl())
            newAnimeSearchResponse(titleFinal, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    // ---------------- Load (anime page) ----------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Titles: prefer explicit English data attributes
        val enTitle = document.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val ntTitle = document.selectFirst("span[data-nt-title]")?.text()
        val title = enTitle ?: ntTitle ?: document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"

        // Poster resolution and fallback to og:image
        val poster = fixUrlNull(
            document.selectFirst(".entry-thumb img")?.resolveImageUrl()
                ?: document.selectFirst(".anime-thumb img")?.resolveImageUrl()
                ?: document.selectFirst("img")?.resolveImageUrl()
                ?: document.ogImage()
        )

        val description = document.selectFirst("div[data-synopsis]")?.text()
        var bannerUrl = document.selectFirst("img[src*='anilistcdn/media/anime/banner']")?.attr("src") ?: poster
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }

        // AniList enrichment: clean English-first title for lookup
        val lookupTitle = cleanTitleForAniList(enTitle ?: ntTitle ?: title) ?: cleanTitleForAniList(title)
        val anilist = lookupTitle?.let { fetchAniListByTitle(it) }

        // Use AniList banner if present
        anilist?.optString("bannerImage", null)?.let { b ->
            if (b.isNotBlank()) bannerUrl = b
        }

        // Extract AniList score, characters, staff
        val avgScore = anilist?.optInt("averageScore", -1)?.takeIf { it > 0 }
        val charactersArr = anilist?.optJSONObject("characters")?.optJSONArray("edges")
        val staffArr = anilist?.optJSONObject("staff")?.optJSONArray("edges")
        val charList = mutableListOf<String>()
        val staffList = mutableListOf<String>()
        charactersArr?.let {
            for (i in 0 until it.length()) {
                val edge = it.getJSONObject(i)
                val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full")
                val role = edge.optString("role")
                if (!name.isNullOrBlank()) charList.add("$name ($role)")
            }
        }
        staffArr?.let {
            for (i in 0 until it.length()) {
                val edge = it.getJSONObject(i)
                val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full")
                val role = edge.optString("role")
                if (!name.isNullOrBlank()) staffList.add("$name ($role)")
            }
        }

        // Build enhanced description (include AniList score & top characters/staff)
        val enhancedDescription = buildString {
            description?.let { append(it).append("\n\n") }
            avgScore?.let { append("⭐ AniList Score: $it\n") }
            if (charList.isNotEmpty()) append("👥 Characters: ${charList.take(6).joinToString(", ")}\n")
            if (staffList.isNotEmpty()) append("🎨 Staff: ${staffList.take(4).joinToString(", ")}\n")
            append("━━━━━━━━━━━━━━━━\n")
            append("Source: $name\n")
        }

        // ---------------- Episodes extraction ----------------
        // Collect all /watch/ links on the page and try additional page variants to fetch more episodes
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        // Helper to collect from a Document
        fun collectEpisodesFromDoc(doc: Document) {
            doc.select("a[href*='/watch/']").forEach { ep ->
                try {
                    val raw = ep.attr("href")
                    val epUrl = fixUrl(raw)
                    if (epUrl.isBlank() || epUrl.contains("/anime/")) return@forEach
                    if (!seen.add(epUrl)) return@forEach

                    // Try to determine episode number using several heuristics
                    val numberCandidates = listOfNotNull(
                        ep.selectFirst(".episode-list-item-number")?.text(),
                        ep.selectFirst(".episode-list-item-title")?.text(),
                        ep.attr("title"),
                        ep.text(),
                        ep.attr("data-episode")
                    ).joinToString(" ")

                    val number = Regex("""(?:Episode|Ep|E)[^\d]*(\d{1,4})""", RegexOption.IGNORE_CASE).find(numberCandidates)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\b(\d{1,4})\b""").find(numberCandidates)?.groupValues?.get(1)?.toIntOrNull()
                        ?: episodes.size + 1

                    val epTitle = ep.selectFirst(".episode-list-item-title")?.text()?.trim()
                        ?: ep.attr("title")?.takeIf { it.isNotBlank() } ?: "Episode $number"

                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                        this.episode = number
                        this.posterUrl = poster // use anime poster for every episode
                    })
                } catch (e: Exception) {
                    android.util.Log.e("An1me_EpParse", "Error parsing episode: ${e.message}", e)
                }
            }
        }

        // Collect from main document
        collectEpisodesFromDoc(document)

        // If we still have <= 30 episodes, try to fetch additional likely paginated endpoints
        if (episodes.size <= 30) {
            val triedUrls = mutableSetOf<String>()
            val pageVariants = listOf("?page=", "?p=", "?pg=", "/page/")
            for (p in 2..12) { // try a bunch of pages (2..12)
                var foundNew = false
                for (variant in pageVariants) {
                    // Build candidate URL variants and avoid repeating
                    val candidate = when {
                        url.contains("?") && variant.startsWith("?") -> "$url&${variant.removePrefix("?")}$p"
                        variant.startsWith("?") -> "$url$variant$p"
                        else -> url.trimEnd('/') + variant + p
                    }
                    if (candidate in triedUrls) continue
                    triedUrls.add(candidate)

                    try {
                        val doc = app.get(candidate).document
                        val beforeCount = seen.size
                        collectEpisodesFromDoc(doc)
                        if (seen.size > beforeCount) {
                            foundNew = true
                        }
                    } catch (e: Exception) {
                        // ignore failures for particular candidate
                    }
                }
                if (!foundNew) break // no new episodes found on this iteration -> stop
            }
        }

        // Final sort
        episodes.sortBy { it.episode }

        // ---------------- Return response ----------------
        return newAnimeLoadResponse(enTitle ?: title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bannerUrl
            this.plot = enhancedDescription
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------- loadLinks (original logic preserved) ----------------

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

            // Handle Google Photos
            if (decodedUrl.contains("photos.google.com", true)) {
                try {
                    android.util.Log.d("An1me_Video", "Detected Google Photos source — extracting all quality variants")

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

                    android.util.Log.d("An1me_Video", "Base Google Photos URL: $baseUrl")

                    for ((qualityInfo, qualityValue) in qualityVariants) {
                        val (qualityName, qualityParam) = qualityInfo
                        val qualityUrl = "$baseUrl$qualityParam"

                        android.util.Log.d("An1me_Video", "Adding $qualityName: $qualityUrl")

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
                    android.util.Log.e("An1me_Video", "Error extracting Google Photos video: ${e.message}", e)
                    return false
                }
            }

            // Handle M3U8
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
                    android.util.Log.e("An1me_Video", "Error parsing M3U8: ${e.message}")
                    return false
                }
            }

            // Handle direct MP4
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
