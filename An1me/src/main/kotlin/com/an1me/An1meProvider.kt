package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.net.URLEncoder

class An1meProvider : MainAPI() {
    override var mainUrl: String = "https://an1me.to"
    override var name: String = "An1me"
    override var lang: String = "ko"

    override val hasMainPage: Boolean = true

    // Helper function to encode URLs safely for URI
    private fun encodeUrlPath(url: String): String {
        val parts = url.split("/").map { 
            URLEncoder.encode(it, "UTF-8").replace("+", "%20") 
        }
        return parts.joinToString("/")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val decodedUrl = String(Base64.getDecoder().decode(data))
        val safeUrl = encodeUrlPath(decodedUrl)

        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = safeUrl,
            referer = mainUrl
        ) { link ->
            callback(link)
        }

        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = app.get(mainUrl + "/search?q=$encodedQuery").text

        return parseSearch(html)
    }

    private fun parseSearch(html: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val regex = Regex("""<a href="(/kr-video/.*?)">.*?<img src="(.*?)".*?title="(.*?)"""")
        regex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val poster = match.groupValues[2]
            val title = match.groupValues[3]

            results.add(
                newAnimeSearchResponse(title, mainUrl + url) {
                    this.posterUrl = poster
                }
            )
        }
        return results
    }

    override suspend fun loadMainPage(page: Int): List<HomePageResponse> {
        val html = app.get(mainUrl + "/").text
        val list = mutableListOf<HomePageResponse>()
        val regex = Regex("""<a href="(/kr-video/.*?)">.*?<img src="(.*?)".*?title="(.*?)"""")
        regex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val poster = match.groupValues[2]
            val title = match.groupValues[3]

            list.add(
                newHomePageResponse(title, mainUrl + url) {
                    this.posterUrl = poster
                }
            )
        }
        return list
    }

    override suspend fun loadEpisodes(animeUrl: String): List<Episode> {
        val html = app.get(animeUrl).text
        val episodes = mutableListOf<Episode>()
        val regex = Regex("""<a href="(/kr-video/.*?)".*?>(.*?)</a>""")
        regex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val title = match.groupValues[2]

            episodes.add(
                newEpisode(title, mainUrl + url)
            )
        }
        return episodes
    }
}
