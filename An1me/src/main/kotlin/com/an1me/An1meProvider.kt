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
    private val malEpisodesCache = ConcurrentHashMap<String, Map<Int, String>>() // titleLowercase -> map(ep->title)

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
            .replace(Regex("\\(.*?\\)"), "") // remove parentheses
            .replace(Regex("\\[.*?]"), "") // remove brackets
            .replace(Regex("[^\\p{L}\\p{N}\\s:]"), " ") // keep letters, numbers, spaces, colon
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
                    characters(page: 1, perPage: 50) {
                      edges {
                        role
                        node { name { full } }
                        voiceActors { name { full } }
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

            val episodesMap = mutableMapOf<Int, String>()
            var page = 1
            loop@ while (true) {
                val epsUrl = "https://api.jikan.moe/v4/anime/$malId/episodes?page=$page"
                val epsRes = app.get(epsUrl).text
                val epsJson = JSONObject(epsRes)
                val epsArr = epsJson.optJSONArray("data") ?: JSONArray()
                if (epsArr.length() == 0) break
                for (i in 0 until epsArr.length()) {
                    val obj = epsArr.getJSONObject(i)
                    val epNo = obj.optInt("episode")
                    val epTitle = obj.optString("title").takeIf { it.isNotBlank() } ?: obj.optString("title_japanese").takeIf { it.isNotBlank() }
                    if (epNo > 0 && !epTitle.isNullOrBlank()) episodesMap[epNo] = epTitle
                }
                val pagination = epsJson.optJSONObject("pagination")
                val last = pagination?.optInt("last_visible_page", page) ?: page
                if (page >= last) break@loop
                page += 1
                if (page > 50) break // sanity limit
            }

            malEpisodesCache[key] = episodesMap
            return episodesMap
        } catch (e: Exception) {
            android.util.Log.e("An1me_MAL", "MAL episodes fetch failed: ${e.message}", e)
            malEpisodesCache[key] = emptyMap()
            return emptyMap()
        }
    }

    // ---------------- Small helper to choose best AniList title ----------------    private fun pickAniTitle(ani: JSONObject?): String? {
    private fun pickAniTitle(ani: JSONObject?): String? {
        if (ani == null) return null
        val titleObj = ani.optJSONObject("title") ?: return null
        return titleObj.optString("english", null)
            ?: titleObj.optString("romaji", null)
            ?: titleObj.optString("native", null)
    }

    // ---------------- Create extractor link helper (kept same semantics) ----------------

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

    // ---------------- Card helpers (original) ----------------

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

    // ---------------- Main page ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Trending -> normal cards (not wide)
        val trendingItems = try {
            document.select(".swiper-trending .swiper-slide").mapNotNull { it.toTrendingResult() }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing trending: ${e.message}", e)
            emptyList()
        }

        // Latest Episodes
        val latestEpisodeItems = try {
            val latestEpisodesSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια))")
            latestEpisodesSection?.select(".kira-grid-listing > div")?.mapNotNull { it.toLatestEpisodeResult() } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing latest episodes: ${e.message}", e)
            emptyList()
        }

        // Latest Anime
        val latestAnimeItems = try {
            document.select("li").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing latest anime: ${e.message}", e)
            emptyList()
        }

        // ---------------- AniList enrichment for each card list (replace title & poster) ----------------
        // We enrich the lists first to avoid referencing HomePageList internals and to keep scope clear.

        suspend fun enrichCardListWithAniList(cards: List<AnimeSearchResponse>): List<AnimeSearchResponse> {
            if (cards.isEmpty()) return cards
            val enriched = mutableListOf<AnimeSearchResponse>()
            for (card in cards) {
                try {
                    val siteTitle = card.title
                    val lookupTitle = cleanTitleForAniList(siteTitle) ?: siteTitle
                    val ani = lookupTitle.let { fetchAniListByTitle(it) }
                    if (ani != null) {
                        // Replace poster and use AniList title (but keep site title in a fallback variable)
                        val aniTitle = pickAniTitle(ani)
                        val cover = ani.optJSONObject("coverImage")?.optString("large", null)
                            ?: ani.optJSONObject("coverImage")?.optString("medium", null)
                        if (!aniTitle.isNullOrBlank()) {
                            // Replace display title with AniList title
                            card.title = aniTitle
                        }
                        if (!cover.isNullOrBlank()) {
                            card.posterUrl = fixUrl(cover)
                        } else {
                            // MAL fallback for cover
                            val malCover = fetchMalCoverByTitle(siteTitle)
                            if (!malCover.isNullOrBlank()) card.posterUrl = fixUrl(malCover)
                        }
                        // Attach averageScore to plot field (small, safe) if available
                        val score = ani.optInt("averageScore", -1).takeIf { it > 0 }
                        score?.let { card.plot = "⭐ $it" }
                    } else {
                        // If AniList not found, try MAL cover
                        val malCover = fetchMalCoverByTitle(card.title)
                        if (!malCover.isNullOrBlank()) card.posterUrl = fixUrl(malCover)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("An1me_EnrichCard", "Error enriching card ${card.title}: ${e.message}", e)
                } finally {
                    enriched.add(card)
                }
            }
            return enriched
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

                // Build response using site title (so search remains compatible)
                val resp = newAnimeSearchResponse(siteTitle, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                }

                results.add(resp)
            } catch (e: Exception) {
                android.util.Log.e("An1me_Search", "Error parsing search item: ${e.message}", e)
            }
        }

        // Enrich search results with AniList posters/titles (replace title & poster), but keep original siteTitle in logs if needed
        val enrichedResults = mutableListOf<SearchResponse>()
        for (r in results) {
            try {
                if (r is AnimeSearchResponse) {
                    val siteTitle = r.title
                    val lookup = cleanTitleForAniList(siteTitle) ?: siteTitle
                    val ani = lookup.let { fetchAniListByTitle(it) }
                    if (ani != null) {
                        val aniTitle = pickAniTitle(ani)
                        val cover = ani.optJSONObject("coverImage")?.optString("large", null)
                            ?: ani.optJSONObject("coverImage")?.optString("medium", null)
                        if (!aniTitle.isNullOrBlank()) r.title = aniTitle
                        if (!cover.isNullOrBlank()) r.posterUrl = fixUrl(cover)
                        else {
                            val mal = fetchMalCoverByTitle(siteTitle)
                            if (!mal.isNullOrBlank()) r.posterUrl = fixUrl(mal)
                        }
                    } else {
                        val mal = fetchMalCoverByTitle(r.title)
                        if (!mal.isNullOrBlank()) r.posterUrl = fixUrl(mal)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("An1me_SearchEnrich", "Error enriching search result ${r.title}: ${e.message}", e)
            } finally {
                enrichedResults.add(r)
            }
        }

        return enrichedResults
    }

    // ---------------- Load (anime page) ----------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Titles: prefer explicit English data attributes on site (but we will keep siteTitle for search/link stability)
        val enTitle = document.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val ntTitle = document.selectFirst("span[data-nt-title]")?.text()
        val siteTitle = enTitle ?: ntTitle ?: document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"

        // Poster resolution and fallback to og:image (site poster)
        val sitePoster = fixUrlNull(
            document.selectFirst(".entry-thumb img")?.resolveImageUrl()
                ?: document.selectFirst(".anime-thumb img")?.resolveImageUrl()
                ?: document.selectFirst("img")?.resolveImageUrl()
                ?: document.ogImage()
        )

        // Preserve site description exactly as requested
        val siteDescription = document.selectFirst("div[data-synopsis]")?.text()

        var bannerUrl = document.selectFirst("img[src*='anilistcdn/media/anime/banner']")?.attr("src") ?: sitePoster
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }

        // AniList enrichment: find AniList by cleaned site title
        val lookupTitle = cleanTitleForAniList(enTitle ?: ntTitle ?: siteTitle) ?: cleanTitleForAniList(siteTitle)
        val anilist = lookupTitle?.let { fetchAniListByTitle(it) }

        // Replace poster/banner/title from AniList where available, but keep description from site
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
            // MAL fallback for cover
            val malCover = fetchMalCoverByTitle(siteTitle)
            if (!malCover.isNullOrBlank()) finalPoster = malCover
        }

        // Extract AniList score, characters, staff (if present)
        val avgScore = anilist?.optInt("averageScore", -1)?.takeIf { it > 0 }
        val charactersArr = anilist?.optJSONObject("characters")?.optJSONArray("edges")
        val staffArr = anilist?.optJSONObject("staff")?.optJSONArray("edges")
        val charList = mutableListOf<String>()
        val staffList = mutableListOf<String>()
        charactersArr?.let {
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
                            va.optJSONObject("name")?.optString("full")?.let { vaNames.add(it) }
                        }
                    }
                    val vaPart = if (vaNames.isNotEmpty()) " — VA: ${vaNames.joinToString(", ")}" else ""
                    if (!name.isNullOrBlank()) charList.add("$name ($role)$vaPart")
                } catch (_: Exception) {
                }
            }
        }
        staffArr?.let {
            for (i in 0 until it.length()) {
                try {
                    val edge = it.getJSONObject(i)
                    val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full")
                    val role = edge.optString("role")
                    if (!name.isNullOrBlank()) staffList.add("$name ($role)")
                } catch (_: Exception) {
                }
            }
        }

        // Build enhanced description: **use site description** first, then append AniList score/credits
        val enhancedDescription = buildString {
            siteDescription?.let { append(it).append("\n\n") }
            avgScore?.let { append("⭐ AniList Score: $it\n") }
            if (charList.isNotEmpty()) append("👥 Characters: ${charList.take(8).joinToString(", ")}\n")
            if (staffList.isNotEmpty()) append("🎨 Staff: ${staffList.take(6).joinToString(", ")}\n")
            append("━━━━━━━━━━━━━━━━\n")
            append("Source: $name\n")
        }

        // ---------------- Episodes extraction (original logic preserved) ----------------
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        fun collectEpisodesFromDoc(doc: Document) {
            doc.select("a[href*='/watch/']").forEach { ep ->
                try {
                    val raw = ep.attr("href")
                    val epUrl = fixUrl(raw)
                    if (epUrl.isBlank() || epUrl.contains("/anime/")) return@forEach
                    if (!seen.add(epUrl)) return@forEach

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
                        this.posterUrl = finalPoster // use AniList poster for episodes
                    })
                } catch (e: Exception) {
                    android.util.Log.e("An1me_EpParse", "Error parsing episode: ${e.message}", e)
                }
            }
        }

        // Collect from main document
        collectEpisodesFromDoc(document)

        // Try paginated endpoints if <= 30 episodes
        if (episodes.size <= 30) {
            val triedUrls = mutableSetOf<String>()
            val pageVariants = listOf("?page=", "?p=", "?pg=", "/page/")
            for (p in 2..12) {
                var foundNew = false
                for (variant in pageVariants) {
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
                        if (seen.size > beforeCount) foundNew = true
                    } catch (e: Exception) {
                        // ignore per-candidate failures
                    }
                }
                if (!foundNew) break
            }
        }

        // Final sort
        episodes.sortBy { it.episode }

        // ---------------- Episode names enrichment using MAL (Jikan) ----------------
        try {
            val lookup = lookupTitle ?: cleanTitleForAniList(siteTitle) ?: siteTitle
            val malEps = fetchMalEpisodeTitlesByTitle(lookup)
            if (!malEps.isNullOrEmpty()) {
                for (ep in episodes) {
                    val n = ep.episode
                    val malName = malEps[n]
                    if (!malName.isNullOrBlank()) ep.name = malName
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_EpEnrich", "Error enriching episode names: ${e.message}", e)
        }

        // ---------------- Return response ----------------
        return newAnimeLoadResponse(siteTitle, url, TvType.Anime) {
            // NOTE: The user requested we keep the An1me description unchanged; we use siteDescription in enhancedDescription building above.
            this.posterUrl = finalPoster
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
                    android.util.Log.e("An1me_Video", "Error parsing M3U8: ${e.message}", e)
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
