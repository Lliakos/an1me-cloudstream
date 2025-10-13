package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private val cache = mutableMapOf<String, LoadResponse>() // caching for reopening app

    private suspend fun newLink(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ): ExtractorLink {
        return ExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            referer = referer,
            quality = quality,
            type = type
        )
    }

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
            ?: this.selectFirst("span[data-nt-title]")?.text() ?: return null
        val bannerUrl = this.selectFirst("img[src*='banner']")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(bannerUrl)
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide").mapNotNull { it.toTrendingResult() }
            if (trendingItems.isNotEmpty()) {
                homePages.add(HomePageList("Trending", trendingItems, isHorizontalImages = true))
            }
        } catch (_: Exception) { }

        try {
            val latestSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια))")
            val latest = latestSection?.select(".kira-grid-listing > div")?.mapNotNull { it.toSearchResult() } ?: emptyList()
            if (latest.isNotEmpty()) {
                homePages.add(HomePageList("Latest Episodes", latest))
            }
        } catch (_: Exception) { }

        try {
            val items = document.select("li").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePages.add(HomePageList("New Anime", items))
            }
        } catch (_: Exception) { }

        return HomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document

        return document.select("#first_load_result > div").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = item.selectFirst("span[data-en-title]")?.text()
                ?: item.selectFirst("span[data-nt-title]")?.text() ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        cache[url]?.let { return it } // return cached if exists

        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()

        val episodes = mutableListOf<Episode>()
        document.select("div.episode-list-display-box a.episode-list-item[href*='/watch/']").forEach { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epNum = ep.selectFirst(".episode-list-item-number")?.text()?.toIntOrNull()
            val epTitle = ep.selectFirst(".episode-list-item-title")?.text() ?: "Episode $epNum"
            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.episode = epNum ?: episodes.size + 1
                this.posterUrl = poster
            })
        }

        val anilistData = getAnilistData(title)
        val response = newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster ?: anilistData?.poster
            this.backgroundPosterUrl = anilistData?.banner
            this.plot = description ?: anilistData?.description
            this.tags = anilistData?.genres
            this.rating = anilistData?.score
            this.trailer = anilistData?.trailer
            addEpisodes(DubStatus.Subbed, episodes)
        }

        cache[url] = response
        return response
    }

    private suspend fun getAnilistData(title: String): AnilistInfo? {
        val query = """
            {
              Page(perPage: 1) {
                media(search: "$title", type: ANIME) {
                  title { romaji english native }
                  description(asHtml: false)
                  coverImage { large }
                  bannerImage
                  genres
                  averageScore
                  trailer { id site }
                }
              }
            }
        """.trimIndent()

        val body = JSONObject(mapOf("query" to query)).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("https://graphql.anilist.co", requestBody = body).text
        val json = JSONObject(res)
        val media = json.getJSONObject("data").getJSONObject("Page").getJSONArray("media").optJSONObject(0)
            ?: return null

        return AnilistInfo(
            title = media.getJSONObject("title").optString("english")
                ?: media.getJSONObject("title").optString("romaji"),
            description = media.optString("description"),
            poster = media.getJSONObject("coverImage").optString("large"),
            banner = media.optString("bannerImage"),
            genres = media.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            score = media.optInt("averageScore", 0),
            trailer = media.optJSONObject("trailer")?.let {
                if (it.optString("site") == "youtube")
                    "https://www.youtube.com/watch?v=${it.optString("id")}" else null
            }
        )
    }

    data class AnilistInfo(
        val title: String?,
        val description: String?,
        val poster: String?,
        val banner: String?,
        val genres: List<String>,
        val score: Int?,
        val trailer: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))

            if (decodedUrl.contains(".m3u8", true)) {
                val lines = app.get(decodedUrl).text.lines()
                lines.forEachIndexed { i, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val height = """RESOLUTION=\d+x(\d+)""".toRegex()
                            .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                            val fullUrl = if (lines[i + 1].startsWith("http")) lines[i + 1]
                            else "${decodedUrl.substringBeforeLast("/")}/${lines[i + 1]}"
                            callback(
                                newLink(
                                    name,
                                    "${height ?: "Unknown"}p",
                                    fullUrl,
                                    data,
                                    when (height) {
                                        1080 -> Qualities.P1080.value
                                        720 -> Qualities.P720.value
                                        else -> Qualities.Unknown.value
                                    },
                                    ExtractorLinkType.M3U8
                                )
                            )
                        }
                    }
                }
                return true
            }

            if (decodedUrl.endsWith(".mp4")) {
                callback(
                    newLink(name, "$name (MP4)", decodedUrl, data, Qualities.Unknown.value, ExtractorLinkType.VIDEO)
                )
                return true
            }

            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}")
            return false
        }
    }
}
