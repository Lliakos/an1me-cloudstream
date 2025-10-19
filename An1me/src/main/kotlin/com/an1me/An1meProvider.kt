package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.net.URLEncoder

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // ---------------- Caches ----------------
    private val aniListCache = ConcurrentHashMap<String, JSONObject>()
    private val malCoverCache = ConcurrentHashMap<String, String?>()
    private val malEpisodesCache = ConcurrentHashMap<String, Map<Int, String>>()

    // ---------------- Helpers ----------------

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

    private fun cleanTitleForAniList(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")

    // ---------------- AniList (GraphQL) ----------------

    private suspend fun fetchAniListByTitle(title: String): JSONObject? {
        val key = title.trim().lowercase()
        aniListCache[key]?.let { return it }

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
                    trailer { id site }
                    characters(page: 1, perPage: 50, sort: ROLE) {
                      edges {
                        role
                        node { 
                          name { full }
                          image { large }
                        }
                        voiceActors(language: JAPANESE, sort: RELEVANCE) { 
                          name { full }
                          language
                        }
                      }
                    }
                    staff(page: 1, perPage: 50) {
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
                requestBody = jsonBody.toString(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                )
            ).text

            val media = JSONObject(res).optJSONObject("data")?.optJSONObject("Media")
            if (media != null) {
                android.util.Log.d("An1me_AniList", "Successfully fetched AniList data for: $title")
                aniListCache[key] = media
            } else {
                android.util.Log.w("An1me_AniList", "No media found for: $title")
            }
            return media
        } catch (e: Exception) {
            android.util.Log.e("An1me_AniList", "AniList fetch failed for '$title': ${e.message}", e)
            return null
        }
    }

    // ---------------- Jikan / MAL helpers ----------------

    private suspend fun fetchMalCoverByTitle(title: String): String? {
        val key = title.trim().lowercase()
        if (malCoverCache.containsKey(key)) return malCoverCache[key]

        try {
            val url = "https://api.jikan.moe/v4/anime?q=${title.encodeUrl()}&limit=1"
            val resText = app.get(url).text
            val resJson = JSONObject(resText)
            val dataArr = resJson.optJSONArray("data")
            val img = if (dataArr != null && dataArr.length() > 0) {
                val first = dataArr.getJSONObject(0)
                val images = first.optJSONObject("images")
                var imageUrl: String? = null
                images?.let {
                    val jpg = it.optJSONObject("jpg")
                    imageUrl = jpg?.optString("large_image_url", null)
                    if (imageUrl.isNullOrBlank()) imageUrl = jpg?.optString("image_url", null)
                }
                if (imageUrl.isNullOrBlank()) imageUrl = first.optString("image_url", null)
                imageUrl
            } else null

            malCoverCache[key] = img
            return img
        } catch (e: Exception) {
            android.util.Log.e("An1me_MAL", "MAL cover fetch failed: ${e.message}", e)
            malCoverCache[key] = null
            return null
        }
    }

    private suspend fun fetchMalEpisodeTitlesByTitle(title: String): Map<Int, String>? {
        val key = title.trim().lowercase()
        malEpisodesCache[key]?.let { return it }

        try {
            val searchUrl = "https://api.jikan.moe/v4/anime?q=${title.encodeUrl()}&limit=1"
            val searchRes = app.get(searchUrl).text
            val searchJson = JSONObject(searchRes)
            val dataArr = searchJson.optJSONArray("data")
            val malId = dataArr?.optJSONObject(0)?.optInt("mal_id", -1) ?: -1
            if (malId <= 0) {
                malEpisodesCache[key] = emptyMap()
                return emptyMap()
            }

            android.util.Log.d("An1me_MAL", "Fetching episodes for MAL ID: $malId")

            val episodesMap = mutableMapOf<Int, String>()
            var page = 1
            loop@ while (true) {
                val epsUrl = "https://api.jikan.moe/v4/anime/$malId/episodes?page=$page"
                android.util.Log.d("An1me_MAL", "Fetching page $page: $epsUrl")
                
                // Add delay to respect rate limits
                if (page > 1) Thread.sleep(1000)
                
                val epsRes = app.get(epsUrl).text
                val epsJson = JSONObject(epsRes)
                val epsArr = epsJson.optJSONArray("data") ?: JSONArray()
                
                android.util.Log.d("An1me_MAL", "Page $page: ${epsArr.length()} episodes")
                
                if (epsArr.length() == 0) break
                for (i in 0 until epsArr.length()) {
                    val obj = epsArr.getJSONObject(i)
                    val epNo = obj.optInt("mal_id")
                    val epTitle = obj.optString("title").takeIf { it.isNotBlank() } 
                        ?: obj.optString("title_japanese").takeIf { it.isNotBlank() }
                    if (epNo > 0 && !epTitle.isNullOrBlank()) {
                        episodesMap[epNo] = epTitle
                        android.util.Log.d("An1me_MAL", "Ep $epNo: $epTitle")
                    }
                }
                val pagination = epsJson.optJSONObject("pagination")
                val hasNext = pagination?.optBoolean("has_next_page", false) ?: false
                if (!hasNext) break@loop
                page += 1
                if (page > 100) break // Safety limit
            }

            android.util.Log.d("An1me_MAL", "Total episodes fetched: ${episodesMap.size}")
            malEpisodesCache[key] = episodesMap
            return episodesMap
        } catch (e: Exception) {
            android.util.Log.e("An1me_MAL", "MAL episodes fetch failed: ${e.message}", e)
            malEpisodesCache[key] = emptyMap()
            return emptyMap()
        }
    }

    private fun pickAniTitle(ani: JSONObject?): String? {
        if (ani == null) return null
        val titleObj = ani.optJSONObject("title") ?: return null
        return titleObj.optString("english", null)
            ?: titleObj.optString("romaji", null)
            ?: titleObj.optString("native", null)
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

    // ---------------- AniList enrichment helper ----------------
    private suspend fun enrichCardListWithAniList(cards: List<AnimeSearchResponse>): List<AnimeSearchResponse> {
        if (cards.isEmpty()) return cards
        val enriched = mutableListOf<AnimeSearchResponse>()
        for (card in cards) {
            try {
                val siteTitle = card.name
                val lookupTitle = cleanTitleForAniList(siteTitle) ?: siteTitle
                val ani = lookupTitle.let { fetchAniListByTitle(it) }

                val aniTitle = pickAniTitle(ani) ?: card.name
                val cover = ani?.optJSONObject("coverImage")?.optString("large", null)
                    ?: ani?.optJSONObject("coverImage")?.optString("medium", null)
                val posterToUse = if (!cover.isNullOrBlank()) fixUrl(cover) 
                    else (fetchMalCoverByTitle(siteTitle) ?: card.posterUrl)

                val newCard = newAnimeSearchResponse(aniTitle, card.url, TvType.Anime) {
                    this.posterUrl = posterToUse
                }

                enriched.add(newCard)
            } catch (e: Exception) {
                android.util.Log.e("An1me_EnrichCard", "Error enriching card ${card.name}: ${e.message}", e)
                enriched.add(card)
            }
        }
        return enriched
    }

    // ---------------- Main page ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        val trendingItems = try {
            document.select(".swiper-trending .swiper-slide").mapNotNull { it.toTrendingResult() }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing trending: ${e.message}", e)
            emptyList()
        }

        val latestEpisodeItems = try {
            val latestEpisodesSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια))")
            latestEpisodesSection?.select(".kira-grid-listing > div")?.mapNotNull { it.toLatestEpisodeResult() } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing latest episodes: ${e.message}", e)
            emptyList()
        }

        val latestAnimeItems = try {
            document.select("li").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing latest anime: ${e.message}", e)
            emptyList()
        }

        val trendingEnriched = enrichCardListWithAniList(trendingItems)
        val latestEpisodesEnriched = enrichCardListWithAniList(latestEpisodeItems)
        val latestAnimeEnriched = enrichCardListWithAniList(latestAnimeItems)

        if (trendingEnriched.isNotEmpty()) homePages.add(HomePageList("Τάσεις", trendingEnriched, isHorizontalImages = false))
        if (latestEpisodesEnriched.isNotEmpty()) homePages.add(HomePageList("Καινούργια Επεισόδια", latestEpisodesEnriched))
        if (latestAnimeEnriched.isNotEmpty()) homePages.add(HomePageList("Καινούργια Anime", latestAnimeEnriched))

        return newHomePageResponse(homePages)
    }

    // ---------------- Search ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document
        val items = document.select("#first_load_result > div")
        val results = mutableListOf<AnimeSearchResponse>()

        for (item in items) {
            try {
                val link = item.selectFirst("a[href*='/anime/']") ?: continue
                val href = fixUrl(link.attr("href"))
                val en = item.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
                val other = item.selectFirst("span[data-nt-title]")?.text()
                val siteTitle = en ?: other ?: continue
                val posterUrl = fixUrlNull(item.selectFirst("img")?.resolveImageUrl())

                val resp = newAnimeSearchResponse(siteTitle, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                }

                results.add(resp)
            } catch (e: Exception) {
                android.util.Log.e("An1me_Search", "Error parsing search item: ${e.message}", e)
            }
        }

        val enrichedResults = mutableListOf<SearchResponse>()
        for (r in results) {
            try {
                if (r is AnimeSearchResponse) {
                    val siteTitle = r.name
                    val lookup = cleanTitleForAniList(siteTitle) ?: siteTitle
                    val ani = lookup.let { fetchAniListByTitle(it) }

                    val aniTitle = ani?.let { pickAniTitle(it) } ?: r.name
                    val cover = ani?.optJSONObject("coverImage")?.optString("large", null)
                        ?: ani?.optJSONObject("coverImage")?.optString("medium", null)
                    val posterToUse = if (!cover.isNullOrBlank()) fixUrl(cover) 
                        else (fetchMalCoverByTitle(siteTitle) ?: r.posterUrl)

                    val newResp = newAnimeSearchResponse(aniTitle, r.url, TvType.Anime) {
                        this.posterUrl = posterToUse
                    }
                    enrichedResults.add(newResp)
                } else {
                    enrichedResults.add(r)
                }
            } catch (e: Exception) {
                android.util.Log.e("An1me_SearchEnrich", "Error enriching search result ${r.name}: ${e.message}", e)
                enrichedResults.add(r)
            }
        }

        return enrichedResults
    }

    // ---------------- Load (anime page) ----------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val enTitle = document.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val ntTitle = document.selectFirst("span[data-nt-title]")?.text()
        val siteTitle = enTitle ?: ntTitle ?: document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"

        val sitePoster = fixUrlNull(
            document.selectFirst(".entry-thumb img")?.resolveImageUrl()
                ?: document.selectFirst(".anime-thumb img")?.resolveImageUrl()
                ?: document.selectFirst("img")?.resolveImageUrl()
                ?: document.ogImage()
        )

        val siteDescription = document.selectFirst("div[data-synopsis]")?.text()

        var bannerUrl = document.selectFirst("img[src*='anilistcdn/media/anime/banner']")?.attr("src") ?: sitePoster
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }

        val lookupTitle = cleanTitleForAniList(enTitle ?: ntTitle ?: siteTitle) ?: cleanTitleForAniList(siteTitle)
        val anilist = lookupTitle?.let { fetchAniListByTitle(it) }

        var finalTitle = siteTitle
        var finalPoster = sitePoster
        if (anilist != null) {
            pickAniTitle(anilist)?.let { finalTitle = it }
            anilist.optJSONObject("coverImage")?.optString("large", null)?.let { finalPoster = it }
            anilist.optJSONObject("coverImage")?.optString("medium", null)?.let { if (finalPoster.isNullOrBlank()) finalPoster = it }
            anilist.optString("bannerImage", null)?.let { b ->
                if (b.isNotBlank()) bannerUrl = b
            }
        } else {
            val malCover = fetchMalCoverByTitle(siteTitle)
            if (!malCover.isNullOrBlank()) finalPoster = malCover
        }

        val avgScore = anilist?.optInt("averageScore", -1)?.takeIf { it > 0 }
        val meanScore = anilist?.optInt("meanScore", -1)?.takeIf { it > 0 }
        val displayScore = avgScore ?: meanScore
        
        // Extract trailer
        val trailerObj = anilist?.optJSONObject("trailer")
        val trailerSite = trailerObj?.optString("site")
        val trailerId = trailerObj?.optString("id")
        val trailerUrl = if (trailerSite == "youtube" && !trailerId.isNullOrBlank()) {
            "https://www.youtube.com/watch?v=$trailerId"
        } else null
        
        val charactersArr = anilist?.optJSONObject("characters")?.optJSONArray("edges")
        val staffArr = anilist?.optJSONObject("staff")?.optJSONArray("edges")
        val charList = mutableListOf<String>()
        val staffList = mutableListOf<String>()
        
        charactersArr?.let {
            android.util.Log.d("An1me_AniList", "Processing ${it.length()} characters")
            for (i in 0 until it.length()) {
                try {
                    val edge = it.getJSONObject(i)
                    val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full")
                    val role = edge.optString("role")
                    val vaArr = edge.optJSONArray("voiceActors")
                    val vaNames = mutableListOf<String>()
                    if (vaArr != null) {
                        for (j in 0 until vaArr.length()) {
                            val va = vaArr.getJSONObject(j)
                            val vaName = va.optJSONObject("name")?.optString("full")
                            val vaLang = va.optString("language")
                            if (!vaName.isNullOrBlank()) {
                                vaNames.add(if (vaLang == "Japanese") vaName else "$vaName ($vaLang)")
                            }
                        }
                    }
                    val vaPart = if (vaNames.isNotEmpty()) " — VA: ${vaNames.joinToString(", ")}" else ""
                    if (!name.isNullOrBlank()) charList.add("$name ($role)$vaPart")
                } catch (e: Exception) {
                    android.util.Log.e("An1me_AniList", "Error parsing character $i: ${e.message}", e)
                }
            }
        }
        
        staffArr?.let {
            android.util.Log.d("An1me_AniList", "Processing ${it.length()} staff")
            for (i in 0 until it.length()) {
                try {
                    val edge = it.getJSONObject(i)
                    val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full")
                    val role = edge.optString("role")
                    if (!name.isNullOrBlank()) staffList.add("$name ($role)")
                } catch (e: Exception) {
                    android.util.Log.e("An1me_AniList", "Error parsing staff $i: ${e.message}", e)
                }
            }
        }

        val enhancedDescription = buildString {
            siteDescription?.let { append(it).append("\n\n") }
            displayScore?.let { append("⭐ AniList Score: $it/100\n") }
            trailerUrl?.let { append("🎬 Trailer: $it\n") }
            if (charList.isNotEmpty()) {
                append("👥 Characters:\n")
                charList.take(10).forEach { append("  • $it\n") }
            }
            if (staffList.isNotEmpty()) {
                append("🎨 Staff:\n")
                staffList.take(8).forEach { append("  • $it\n") }
            }
            append("━━━━━━━━━━━━━━━━\n")
            append("Source: $name\n")
        }

        // Extract total episode count from the anime page
        // Looking for links with "E \d+" pattern in the episode list
        val totalEpisodes = try {
            val episodeLinks = document.select("a[href*='/watch/']")
            val episodeNumbers = episodeLinks.mapNotNull { link ->
                val text = link.text().trim()
                if (text.matches(Regex("""^E\s*\d+$""", RegexOption.IGNORE_CASE))) {
                    text.replace(Regex("""[^\d]"""), "").toIntOrNull()
                } else null
            }
            val count = episodeNumbers.maxOrNull() ?: 0
            
            android.util.Log.d("An1me_EpCount", "Found episode count: $count (from ${episodeNumbers.size} episode links)")
            count
        } catch (e: Exception) {
            android.util.Log.e("An1me_EpCount", "Error getting episode count: ${e.message}", e)
            0
        }

        android.util.Log.d("An1me_Episodes", "Total episodes found: $totalEpisodes for URL: $url")

        val episodes = mutableListOf<Episode>()
        
        if (totalEpisodes > 0) {
            // Extract the anime slug from URL
            // URL format: https://an1me.to/anime/kimetsu-no-yaiba/ or https://an1me.to/anime/kimetsu-no-yaiba
            val animeSlug = url.replace(mainUrl, "")
                .replace("/anime/", "")
                .trim('/')
            
            android.util.Log.d("An1me_Episodes", "Anime slug: $animeSlug")
            
            // Generate episodes based on the count
            for (i in 1..totalEpisodes) {
                val epUrl = "$mainUrl/watch/$animeSlug-episode-$i/"
                episodes.add(newEpisode(epUrl) {
                    this.name = "Episode $i"
                    this.episode = i
                    this.posterUrl = finalPoster
                })
            }
        } else {
            android.util.Log.w("An1me_Episodes", "No episodes found, falling back to scraping")
            // Fallback: try to scrape visible episodes
            val seen = mutableSetOf<String>()
            document.select("a[href*='/watch/']").forEach { ep ->
                try {
                    val epUrl = fixUrl(ep.attr("href"))
                    if (epUrl.contains("/anime/") || !seen.add(epUrl)) return@forEach
                    
                    val number = Regex("""episode-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                        ?: episodes.size + 1
                    
                    episodes.add(newEpisode(epUrl) {
                        this.name = "Episode $number"
                        this.episode = number
                        this.posterUrl = finalPoster
                    })
                } catch (e: Exception) {
                    android.util.Log.e("An1me_EpParse", "Error parsing episode: ${e.message}", e)
                }
            }
        }

        episodes.sortBy { it.episode }

        // Enrich episode names with MAL titles
        if (episodes.isNotEmpty()) {
            try {
                val lookup = lookupTitle ?: cleanTitleForAniList(siteTitle) ?: siteTitle
                android.util.Log.d("An1me_EpEnrich", "Looking up MAL episodes for: $lookup (${episodes.size} episodes)")
                val malEps = fetchMalEpisodeTitlesByTitle(lookup)
                if (!malEps.isNullOrEmpty()) {
                    android.util.Log.d("An1me_EpEnrich", "Found ${malEps.size} episode titles from MAL")
                    var enrichedCount = 0
                    for (ep in episodes) {
                        val n = ep.episode
                        val malName = malEps[n]
                        if (!malName.isNullOrBlank()) {
                            ep.name = "Episode $n: $malName"
                            enrichedCount++
                        }
                    }
                    android.util.Log.d("An1me_EpEnrich", "Enriched $enrichedCount episode names")
                } else {
                    android.util.Log.w("An1me_EpEnrich", "No MAL episode titles found")
                }
            } catch (e: Exception) {
                android.util.Log.e("An1me_EpEnrich", "Error enriching episode names: ${e.message}", e)
            }
        }

        return newAnimeLoadResponse(siteTitle, url, TvType.Anime) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = bannerUrl
            this.plot = enhancedDescription
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------- loadLinks ----------------

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
                    android.util.Log.e("An1me_Video", "WeTransfer error: ${e.message}", e)
                    return false
                }
            }

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

                    android.util.Log.d("An1me_Video", "Base Google Photos URL: $baseUrl")

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
                    android.util.Log.e("An1me_Video", "Google Photos error: ${e.message}", e)
                    return false
                }
            }

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
                    android.util.Log.e("An1me_Video", "M3U8 error: ${e.message}", e)
                    return false
                }
            }

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
            android.util.Log.e("An1me_Video", "Error loading links: ${e.message}", e)
            return false
        }
    }
}