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

    // --- Helper to create links with newExtractorLink ---
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

    // --- Search Result Parsing ---
    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null

        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("span[data-nt-title]")?.text()
            ?: link.attr("title")
            ?: selectFirst("img")?.attr("alt")
            ?: return null

        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("span[data-nt-title]")?.text() ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("span[data-nt-title]")?.text() ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // --- Home Page ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Trending Section
        document.select(".swiper-trending .swiper-slide").mapNotNull { it.toTrendingResult() }
            .takeIf { it.isNotEmpty() }?.let {
                homePages.add(HomePageList("Trending", it, isHorizontalImages = true))
            }

        // Latest Episodes Section
        document.select("section:has(h2:contains(Καινούργια Επεισόδια)) .kira-grid-listing > div")
            .mapNotNull { it.toLatestEpisodeResult() }
            .takeIf { it.isNotEmpty() }?.let {
                homePages.add(HomePageList("New Episodes", it))
            }

        // Latest Anime Section
        document.select("li").mapNotNull { it.toSearchResult() }.takeIf { it.isNotEmpty() }?.let {
            homePages.add(HomePageList("New Anime", it))
        }

        return HomePageResponse(homePages)
    }

    // --- Search ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document
        return document.select("#first_load_result > div").mapNotNull {
            val link = it.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = it.selectFirst("span[data-en-title]")?.text()
                ?: it.selectFirst("span[data-nt-title]")?.text() ?: return@mapNotNull null
            val poster = fixUrlNull(it.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) {
                posterUrl = poster
            }
        }
    }

    // --- Load (Anilist integrated metadata) ---
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(doc.selectFirst("img")?.attr("src"))

        // 🔹 Fetch AniList metadata
        val aniQuery = """
            query (${"$"}title: String) {
                Media(search: ${"$"}title, type: ANIME) {
                    id
                    title { romaji english native }
                    description(asHtml: false)
                    coverImage { extraLarge large medium color }
                    bannerImage
                    averageScore
                    genres
                    status
                    episodes
                    duration
                    startDate { year month day }
                    trailer { id site }
                    studios { nodes { name } }
                }
            }
        """.trimIndent()

        val body = JSONObject()
        body.put("query", aniQuery)
        body.put("variables", JSONObject().put("title", title))
        val anilistResponse = app.post(
            "https://graphql.anilist.co",
            requestBody = body.toString().toRequestBody("application/json".toMediaTypeOrNull())
        ).parsedSafe<JSONObject>()

        var description = doc.selectFirst("div[data-synopsis]")?.text()
        var banner: String? = null
        var trailerUrl: String? = null
        val tags = mutableListOf<String>()
        var studio: String? = null

        anilistResponse?.let { json ->
            val data = json.optJSONObject("data")?.optJSONObject("Media")
            if (data != null) {
                description = data.optString("description", description ?: "")
                banner = data.optJSONObject("coverImage")?.optString("extraLarge")
                    ?: data.optString("bannerImage") ?: banner
                data.optJSONArray("genres")?.let {
                    for (i in 0 until it.length()) tags.add(it.getString(i))
                }
                studio = data.optJSONObject("studios")
                    ?.optJSONArray("nodes")
                    ?.optJSONObject(0)
                    ?.optString("name")
                val trailer = data.optJSONObject("trailer")
                if (trailer != null && trailer.optString("site") == "youtube") {
                    trailerUrl = "https://www.youtube.com/watch?v=${trailer.optString("id")}"
                }
            }
        }

        // --- Episodes (unlimited, full list) ---
        val episodes = mutableListOf<Episode>()
        doc.select("div.episode-list-display-box a.episode-list-item[href*='/watch/']").forEach {
            val epUrl = fixUrl(it.attr("href"))
            val epNumber = it.selectFirst(".episode-list-item-number")?.text()?.toIntOrNull() ?: 0
            val epTitle = it.selectFirst(".episode-list-item-title")?.text()?.trim()
                ?: "Episode $epNumber"
            val epThumb = banner ?: poster
            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.episode = epNumber
                this.posterUrl = epThumb
            })
        }

        if (episodes.isEmpty()) {
            doc.select("div.swiper-slide a[href*='/watch/']").forEach {
                val epUrl = fixUrl(it.attr("href"))
                val epNum = Regex("""Episode\s*(\d+)""").find(it.attr("title"))?.groupValues?.get(1)?.toIntOrNull()
                val epTitle = it.attr("title") ?: "Episode ${epNum ?: 0}"
                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum ?: episodes.size + 1
                    this.posterUrl = banner ?: poster
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = banner ?: poster
            this.plot = description
            this.tags = tags
            this.trailerUrl = trailerUrl
            this.showStatus = ShowStatus.Ongoing
            this.studio = studio
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
        }
    }

    // --- Load Links (iframe + Wetransfer fix) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false
            android.util.Log.d("An1me_Video", "Iframe src: $iframeSrc")

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) return false
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            android.util.Log.d("An1me_Video", "Decoded URL: $decodedUrl")

            // WeTransfer extractor
            if (decodedUrl.contains("wetransfer", true)) {
                val iframePage = app.get(iframeSrc).text
                val cleaned = iframePage
                    .replace("&quot;", "\"")
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                val match = Regex("""const\s+params\s*=\s*(\{.*?"sources".*?\});""", RegexOption.DOT_MATCHES_ALL)
                    .find(cleaned)?.groupValues?.get(1)
                if (match != null) {
                    val json = JSONObject(match)
                    val sources = json.getJSONArray("sources")
                    if (sources.length() > 0) {
                        val videoUrl = sources.getJSONObject(0).getString("url")
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
                }
            }

            // Fallback: M3U8
            if (decodedUrl.endsWith(".m3u8")) {
                callback(
                    createLink(
                        sourceName = name,
                        linkName = "$name (M3U8)",
                        url = decodedUrl,
                        referer = data,
                        quality = Qualities.Unknown.value
                    )
                )
                return true
            }

            // MP4 direct
            if (decodedUrl.endsWith(".mp4")) {
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

        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}", e)
        }
        return false
    }
}
