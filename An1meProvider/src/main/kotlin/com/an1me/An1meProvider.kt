package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select("li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse("Latest Anime", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text() 
            ?: document.selectFirst("h1")?.text()
            ?: "Unknown"
        
        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")
        )
        
        val description = document.selectFirst("div.description")?.text()
            ?: document.selectFirst("div.entry-content")?.text()
        
        val episodes = document.select("a.episode-list-item").mapNotNull { ep ->
            val episodeUrl = ep.attr("href")
            if (episodeUrl.isEmpty()) return@mapNotNull null
            
            val episodeNumber = ep.selectFirst("span.episode-list-item-number")?.text()
                ?.trim()?.toIntOrNull() ?: 0
            val episodeTitle = ep.selectFirst("span.episode-list-item-title")?.text() 
                ?: "Episode $episodeNumber"
            
            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.episode = episodeNumber
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframe = document.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false
        val base64Part = iframe.substringAfter("/kr-video/").substringBefore("?")
        
        return try {
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            M3u8Helper.generateM3u8(name, decodedUrl, mainUrl).forEach(callback)
            true
        } catch (e: Exception) {
            false
        }
    }
}