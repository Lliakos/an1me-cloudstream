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

    // AniList cache
    private val aniListCache = ConcurrentHashMap<String, JSONObject>()

    // --- Helpers ---

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

    private suspend fun fetchAniListByTitle(title: String): JSONObject? {
        aniListCache[title]?.let { return it } // cached version

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
                aniListCache[title] = media // cache it
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

    // --- Card Builders ---

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text()
            ?: link.attr("title")
            ?: this.selectFirst("img")?.attr("alt")
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // --- MainPage ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide").mapNotNull { it.toTrendingResult() }
            if (trendingItems.isNotEmpty()) {
                homePages.add(HomePageList("Τάσεις", trendingItems, isHorizontalImages = false))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing trending: ${e.message}")
        }

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

    // --- Search ---

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document
        return document.select("#first_load_result > div").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = item.selectFirst("span[data-en-title]")?.text()
                ?: item.selectFirst("span[data-nt-title]")?.text() ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.selectFirst("img")?.resolveImageUrl())
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    // --- Load Page ---

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"

        val poster = fixUrlNull(
            document.selectFirst(".entry-thumb img")?.resolveImageUrl()
                ?: document.selectFirst(".anime-thumb img")?.resolveImageUrl()
                ?: document.selectFirst("img")?.resolveImageUrl()
                ?: document.ogImage()
        )

        val description = document.selectFirst("div[data-synopsis]")?.text()
        var bannerUrl = document.selectFirst("img[src*='anilistcdn/media/anime/banner']")?.attr("src") ?: poster
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }

        // Get AniList data (cached)
        val anilist = fetchAniListByTitle(title)
        val avgScore = anilist?.optInt("averageScore", -1)?.takeIf { it > 0 }
        val anilistBanner = anilist?.optString("bannerImage", null)
        if (!anilistBanner.isNullOrEmpty()) bannerUrl = anilistBanner

        val charactersArr = anilist?.optJSONObject("characters")?.optJSONArray("edges")
        val staffArr = anilist?.optJSONObject("staff")?.optJSONArray("edges")
        val charList = mutableListOf<String>()
        val staffList = mutableListOf<String>()
        charactersArr?.let {
            for (i in 0 until it.length()) {
                val edge = it.getJSONObject(i)
                val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full")
                val role = edge.optString("role")
                if (!name.isNullOrEmpty()) charList.add("$name ($role)")
            }
        }
        staffArr?.let {
            for (i in 0 until it.length()) {
                val edge = it.getJSONObject(i)
                val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full")
                val role = edge.optString("role")
                if (!name.isNullOrEmpty()) staffList.add("$name ($role)")
            }
        }

        val enhancedDescription = buildString {
            description?.let { append(it).append("\n\n") }
            avgScore?.let { append("⭐ AniList Score: $it\n") }
            if (charList.isNotEmpty()) append("👥 Characters: ${charList.take(6).joinToString(", ")}\n")
            if (staffList.isNotEmpty()) append("🎨 Staff: ${staffList.take(4).joinToString(", ")}\n")
        }

        // Collect episodes (all links)
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        document.select("a[href*='/watch/']").forEach { ep ->
            val href = fixUrl(ep.attr("href"))
            if (href.isEmpty() || href.contains("/anime/") || !seen.add(href)) return@forEach
            val text = ep.text()
            val num = Regex("""(\d{1,4})""").find(text)?.value?.toIntOrNull() ?: episodes.size + 1
            val nameEp = ep.selectFirst(".episode-list-item-title")?.text() ?: "Episode $num"
            episodes.add(newEpisode(href) {
                this.name = nameEp
                this.episode = num
                this.posterUrl = poster
            })
        }
        episodes.sortBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bannerUrl
            this.plot = enhancedDescription
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // --- Links extraction unchanged (works fine) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // (Your original loadLinks code here - unchanged)
        return super.loadLinks(data, isCasting, subtitleCallback, callback)
    }
}
