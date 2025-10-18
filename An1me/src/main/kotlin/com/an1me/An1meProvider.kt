package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private val aniListCache = ConcurrentHashMap<String, JSONObject>()
    private val malCache = ConcurrentHashMap<String, String?>()

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
        return try {
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
                    streamingEpisodes {
                      title
                      thumbnail
                      url
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
            media
        } catch (e: Exception) {
            android.util.Log.e("An1me_AniList", "AniList fetch failed: ${e.message}")
            null
        }
    }

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
            android.util.Log.e("An1me_MAL", "MAL fetch failed: ${e.message}")
            null
        }
    }

    // ---------------- Main Page ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homeLists = mutableListOf<HomePageList>()

        fun parseSection(title: String, selector: String): List<AnimeSearchResponse> {
            val section = document.select(selector)
            val results = mutableListOf<AnimeSearchResponse>()
            for (el in section) {
                val link = el.selectFirst("a[href*='/anime/']") ?: continue
                val href = fixUrl(link.attr("href"))
                val rawTitle = el.selectFirst("span[data-en-title]")?.text()
                    ?: el.selectFirst("span[data-nt-title]")?.text()
                    ?: link.attr("title")
                    ?: continue
                val cleanTitle = cleanTitleForAniList(rawTitle)
                val aniInfo = cleanTitle?.let { runBlockingSafe { fetchAniListByTitle(it) } }
                val poster = aniInfo?.optJSONObject("coverImage")?.optString("large")
                    ?: el.selectFirst("img")?.resolveImageUrl()
                    ?: fetchMalCoverByTitle(rawTitle)
                val score = aniInfo?.optInt("averageScore")?.let { "⭐ $it" }
                val finalTitle =
                    aniInfo?.optJSONObject("title")?.optString("english")?.ifEmpty { aniInfo.optJSONObject("title")?.optString("romaji") }
                        ?: rawTitle
                results.add(
                    newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
                        this.posterUrl = poster
                        this.plot = score
                    }
                )
            }
            return results
        }

        val trending = parseSection("Τάσεις", ".swiper-trending .swiper-slide")
        val latestEpisodes = parseSection("Καινούργια Επεισόδια", "section:has(h2:contains(Καινούργια Επεισόδια)) .kira-grid-listing > div")
        val latestAnime = parseSection("Καινούργια Anime", "li")

        if (trending.isNotEmpty()) homeLists.add(HomePageList("Τάσεις", trending))
        if (latestEpisodes.isNotEmpty()) homeLists.add(HomePageList("Καινούργια Επεισόδια", latestEpisodes))
        if (latestAnime.isNotEmpty()) homeLists.add(HomePageList("Καινούργια Anime", latestAnime))

        return newHomePageResponse(homeLists)
    }

    // ---------------- Search ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document
        val results = mutableListOf<SearchResponse>()

        for (item in document.select("#first_load_result > div")) {
            val link = item.selectFirst("a[href*='/anime/']") ?: continue
            val href = fixUrl(link.attr("href"))
            val rawTitle = item.selectFirst("span[data-en-title]")?.text()
                ?: item.selectFirst("span[data-nt-title]")?.text()
                ?: link.attr("title")
                ?: continue

            val cleanTitle = cleanTitleForAniList(rawTitle)
            val aniInfo = cleanTitle?.let { fetchAniListByTitle(it) }

            val poster = aniInfo?.optJSONObject("coverImage")?.optString("large")
                ?: item.selectFirst("img")?.resolveImageUrl()
                ?: fetchMalCoverByTitle(rawTitle)
            val finalTitle =
                aniInfo?.optJSONObject("title")?.optString("english")?.ifEmpty { aniInfo.optJSONObject("title")?.optString("romaji") }
                    ?: rawTitle

            results.add(newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
                this.posterUrl = poster
                this.plot = aniInfo?.optInt("averageScore")?.let { "⭐ $it" }
            })
        }

        return results
    }

    // ---------------- Load ----------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cleanTitle = cleanTitleForAniList(title)
        val aniInfo = cleanTitle?.let { fetchAniListByTitle(it) }

        val finalTitle =
            aniInfo?.optJSONObject("title")?.optString("english")?.ifEmpty { aniInfo.optJSONObject("title")?.optString("romaji") }
                ?: title
        val poster = aniInfo?.optJSONObject("coverImage")?.optString("large")
            ?: doc.ogImage()
            ?: fetchMalCoverByTitle(title)
        val banner = aniInfo?.optString("bannerImage")
        val description = aniInfo?.optString("description")
        val rating = aniInfo?.optInt("averageScore")

        val characters = aniInfo?.optJSONObject("characters")?.optJSONArray("edges")?.let { arr ->
            (0 until arr.length()).joinToString(", ") {
                arr.getJSONObject(it).optJSONObject("node")?.optString("name") ?: ""
            }
        }
        val staff = aniInfo?.optJSONObject("staff")?.optJSONArray("edges")?.let { arr ->
            (0 until arr.length()).joinToString(", ") {
                arr.getJSONObject(it).optJSONObject("node")?.optString("name") ?: ""
            }
        }

        val episodes = mutableListOf<Episode>()
        val streamingEpisodes = aniInfo?.optJSONArray("streamingEpisodes")
        if (streamingEpisodes != null && streamingEpisodes.length() > 0) {
            for (i in 0 until streamingEpisodes.length()) {
                val ep = streamingEpisodes.getJSONObject(i)
                val epTitle = ep.optString("title")
                val epUrl = ep.optString("url")
                val epThumb = ep.optString("thumbnail")
                episodes.add(
                    Episode(epUrl) {
                        name = epTitle
                        posterUrl = epThumb
                    }
                )
            }
        } else {
            // fallback to site parsing
            doc.select(".episodes a").forEach {
                episodes.add(
                    Episode(fixUrl(it.attr("href"))) {
                        name = it.text().trim()
                    }
                )
            }
        }

        return newAnimeLoadResponse(finalTitle, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
            if (rating != null) this.rating = rating
            if (!characters.isNullOrBlank()) this.actors = characters
            if (!staff.isNullOrBlank()) this.directors = staff
        }
    }

    // ---------------- Links ----------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
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
                    ExtractorLink(
                        source = "An1me",
                        name = "An1me Stream",
                        url = src,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("An1me_Links", "Error loading links: ${e.message}")
            false
        }
    }
}
