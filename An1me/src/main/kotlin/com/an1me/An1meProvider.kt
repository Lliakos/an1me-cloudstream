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

    private val cache = mutableMapOf<String, LoadResponse>()

    // ✅ Use suspend newExtractorLink
    private suspend fun makeLink(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ): ExtractorLink {
        return newExtractorLink(
            source = source,
            name = name,
            url = url,
            type = type
        ) {
            this.referer = referer
            this.quality = quality
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("span[data-nt-title]")?.text() ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
    }

    // ✅ Spotlight (Top Slider)
    private fun Element.toSpotlight(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("span[data-nt-title]")?.text() ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val lists = mutableListOf<HomePageList>()

        // Spotlight / top carousel
        doc.select(".swiper-spotlight .swiper-slide").mapNotNull { it.toSpotlight() }.let {
            if (it.isNotEmpty()) lists.add(HomePageList("Spotlight", it))
        }

        // Trending
        doc.select(".swiper-trending .swiper-slide").mapNotNull { it.toSpotlight() }.let {
            if (it.isNotEmpty()) lists.add(HomePageList("Trending", it))
        }

        // Latest episodes
        doc.select("section:has(h2:contains(Καινούργια Επεισόδια)) .kira-grid-listing > div")
            .mapNotNull { it.toSearchResult() }.let {
                if (it.isNotEmpty()) lists.add(HomePageList("Latest Episodes", it))
            }

        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?s_keyword=$query"
        val doc = app.get(url).document
        return doc.select("#first_load_result > div").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        cache[url]?.let { return it }

        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(doc.selectFirst("img")?.attr("src"))
        val desc = doc.selectFirst("div[data-synopsis]")?.text()

        // ✅ Full episode list
        val episodes = doc.select("a.episode-list-item[href*='/watch/']").mapIndexed { index, ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epNum = ep.selectFirst(".episode-list-item-number")?.text()?.toIntOrNull() ?: index + 1
            val epName = ep.selectFirst(".episode-list-item-title")?.text() ?: "Episode $epNum"
            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum
                this.posterUrl = poster
            }
        }

        val info = getAnilistData(title)
        val res = newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster ?: info?.poster
            backgroundPosterUrl = info?.banner
            plot = desc ?: info?.description
            tags = info?.genres
            rating = info?.score
            trailer = info?.trailer
            addEpisodes(DubStatus.Subbed, episodes)
        }

        cache[url] = res
        return res
    }

    private suspend fun getAnilistData(title: String): AnilistInfo? {
        val query = """
            { Page(perPage:1){ media(search:"$title",type:ANIME){ 
              title{romaji english native} description(asHtml:false)
              coverImage{large} bannerImage genres averageScore
              trailer{id site} } } }
        """.trimIndent()

        val body = JSONObject(mapOf("query" to query)).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())

        val res = app.post("https://graphql.anilist.co", requestBody = body).text
        val media = JSONObject(res)
            .getJSONObject("data").getJSONObject("Page")
            .getJSONArray("media").optJSONObject(0) ?: return null

        return AnilistInfo(
            title = media.getJSONObject("title").optString("english"),
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
            val doc = app.get(data).document
            val iframe = doc.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false
            val base64 = iframe.substringAfter("/kr-video/").substringBefore("?")
            val decoded = String(Base64.getDecoder().decode(base64))

            if (decoded.contains(".m3u8")) {
                val lines = app.get(decoded).text.lines()
                lines.forEachIndexed { i, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val height = """RESOLUTION=\d+x(\d+)""".toRegex()
                            .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                            val full = if (lines[i + 1].startsWith("http")) lines[i + 1]
                            else "${decoded.substringBeforeLast("/")}/${lines[i + 1]}"
                            callback(
                                makeLink(
                                    source = name,
                                    name = "${height ?: "Unknown"}p",
                                    url = full,
                                    referer = data,
                                    quality = when (height) {
                                        1080 -> Qualities.P1080.value
                                        720 -> Qualities.P720.value
                                        else -> Qualities.Unknown.value
                                    }
                                )
                            )
                        }
                    }
                }
                return true
            }

            if (decoded.endsWith(".mp4")) {
                callback(makeLink(name, "$name (MP4)", decoded, data, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                return true
            }
        } catch (e: Exception) {
            logError(e)
        }
        return false
    }
}
