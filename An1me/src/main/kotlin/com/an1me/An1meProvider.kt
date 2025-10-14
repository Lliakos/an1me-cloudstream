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

    private val aniListCache = ConcurrentHashMap<String, JSONObject>()
    private val malCache = ConcurrentHashMap<String, String?>()

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
    }

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
            if (media != null) aniListCache[key] = media
            return media
        } catch (e: Exception) {
            android.util.Log.e("An1me_AniList", "AniList fetch failed: ${e.message}", e)
            return null
        }
    }

    // ✅ MyAnimeList cover fallback
    private suspend fun fetchMalCoverByTitle(title: String): String? {
        val key = title.trim().lowercase()
        malCache[key]?.let { return it }

        return try {
            val url = "https://api.jikan.moe/v4/anime?q=${title.encodeUrl()}&limit=1"
            val res = app.get(url).text
            val json = JSONObject(res)
            val img = json.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optJSONObject("images")
                ?.optJSONObject("jpg")
                ?.optString("image_url")
            malCache[key] = img
            img
        } catch (e: Exception) {
            android.util.Log.e("An1me_MAL", "MAL fetch failed: ${e.message}", e)
            null
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
        return newExtractorLink(source = sourceName, name = linkName, url = url, type = type) {
            this.referer = referer
            this.quality = quality
        }
    }

    // ---------------- Card helpers ----------------

    private suspend fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null
        val en = this.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val other = this.selectFirst("span[data-nt-title]")?.text()
        val titleFinal = en ?: other ?: link.attr("title") ?: this.selectFirst("img")?.attr("alt") ?: return null

        var posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        if (posterUrl == null) posterUrl = fetchMalCoverByTitle(titleFinal)
        posterUrl = posterUrl ?: "https://img.anilist.co/user/avatar/large/default.png"

        return newAnimeSearchResponse(titleFinal, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val en = this.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val other = this.selectFirst("span[data-nt-title]")?.text()
        val titleFinal = en ?: other ?: return null
        var posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        if (posterUrl == null) posterUrl = fetchMalCoverByTitle(titleFinal)
        posterUrl = posterUrl ?: "https://img.anilist.co/user/avatar/large/default.png"
        return newAnimeSearchResponse(titleFinal, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val en = this.selectFirst("span[data-en-title]")?.text()?.takeIf { it.isNotBlank() }
        val other = this.selectFirst("span[data-nt-title]")?.text()
        val titleFinal = en ?: other ?: return null
        var posterUrl = fixUrlNull(this.selectFirst("img")?.resolveImageUrl())
        if (posterUrl == null) posterUrl = fetchMalCoverByTitle(titleFinal)
        posterUrl = posterUrl ?: "https://img.anilist.co/user/avatar/large/default.png"
        return newAnimeSearchResponse(titleFinal, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------- Main page ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide").mapNotNull { it.toTrendingResult() }
            if (trendingItems.isNotEmpty()) homePages.add(HomePageList("Τάσεις", trendingItems))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing trending: ${e.message}")
        }

        try {
            val latestEpisodesSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια))")
            val latestEpisodeItems =
                latestEpisodesSection?.select(".kira-grid-listing > div")?.mapNotNull { it.toLatestEpisodeResult() } ?: emptyList()
            if (latestEpisodeItems.isNotEmpty()) homePages.add(HomePageList("Καινούργια Επεισόδια", latestEpisodeItems))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Error parsing latest episodes: ${e.message}")
        }

        try {
            val latestAnimeItems = document.select("li").mapNotNull { it.toSearchResult() }
            if (latestAnimeItems.isNotEmpty()) homePages.add(HomePageList("Καινούργια Anime", latestAnimeItems))
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
            var posterUrl = fixUrlNull(item.selectFirst("img")?.resolveImageUrl())
            if (posterUrl == null) posterUrl = fetchMalCoverByTitle(titleFinal)
            posterUrl = posterUrl ?: "https://img.anilist.co/user/avatar/large/default.png"
            newAnimeSearchResponse(titleFinal, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    // ---------------- Load ----------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return newAnimeLoadResponse("Unknown", url, TvType.Anime) {}

        val aniTitle = cleanTitleForAniList(title)
        val aniInfo = aniTitle?.let { fetchAniListByTitle(it) }

        val poster = fixUrlNull(doc.ogImage())
            ?: aniInfo?.optJSONObject("coverImage")?.optString("large")
            ?: fetchMalCoverByTitle(title)
        val banner = aniInfo?.optString("bannerImage")

        val description = aniInfo?.optString("description")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val rating = aniInfo?.optInt("averageScore")
        val staff = aniInfo?.optJSONObject("staff")?.optJSONArray("edges")?.let { arr ->
            (0 until arr.length()).joinToString(", ") { arr.getJSONObject(it).optJSONObject("node")?.optString("name") ?: "" }
        }
        val characters = aniInfo?.optJSONObject("characters")?.optJSONArray("edges")?.let { arr ->
            (0 until arr.length()).joinToString(", ") { arr.getJSONObject(it).optJSONObject("node")?.optString("name") ?: "" }
        }

        val episodes = doc.select(".episodes a").map {
            Episode(fixUrl(it.attr("href"))) {
                name = it.text().trim()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
            if (rating != null) this.rating = rating
            if (!staff.isNullOrBlank()) this.directors = staff
            if (!characters.isNullOrBlank()) this.actors = characters
        }
    }

    // ---------------- Load Links ----------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data).document
            val iframe = doc.selectFirst("iframe")?.attr("src") ?: return false
            val frameUrl = fixUrl(iframe)
            val frameDoc = app.get(frameUrl).document

            val scriptTag = frameDoc.selectFirst("script:containsData(sources)")?.data()
            val regex = Regex("sources:\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(scriptTag ?: "")?.groupValues?.get(1) ?: return false

            val sources = Regex("\"file\":\"(.*?)\"").findAll(match).map { it.groupValues[1] }.toList()
            sources.forEach { src ->
                callback(
                    createLink(
                        "An1me",
                        "An1me Stream",
                        src,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }

            return true
        } catch (e: Exception) {
            android.util.Log.e("An1me_Links", "loadLinks error: ${e.message}", e)
            return false
        }
    }
}
