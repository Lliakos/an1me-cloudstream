package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        // Find all anime links on homepage
        val home = document.select("a[href*='/anime/']").mapNotNull {
            val href = it.attr("href")
            if (!href.contains("/anime/") || href.contains("/watch/")) return@mapNotNull null
            
            val title = it.attr("data-tippy-content-to").takeIf { t -> t.isNotEmpty() }
                ?: it.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null
            
            val posterUrl = it.selectFirst("img")?.attr("src")
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.url }
        
        return newHomePageResponse("Latest Anime", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        
        return document.select("a[href*='/anime/']").mapNotNull {
            val href = it.attr("href")
            if (!href.contains("/anime/") || href.contains("/watch/")) return@mapNotNull null
            
            val title = it.attr("data-tippy-content-to").takeIf { t -> t.isNotEmpty() }
                ?: it.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null
            
            val posterUrl = it.selectFirst("img")?.attr("src")
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text() 
            ?: document.selectFirst("h1")?.text()
            ?: "Unknown"
        
        val poster = document.selectFirst("div.anime-poster img")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")
        
        val description = document.selectFirst("div.description")?.text()
            ?: document.selectFirst("div.entry-content")?.text()
        
        // Get episodes using the actual structure
        val episodes = document.select("a.episode-list-item").mapNotNull { ep ->
            val episodeUrl = ep.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val episodeNumber = ep.selectFirst("span.episode-list-item-number")?.text()?.toIntOrNull() ?: 0
            val episodeTitle = ep.selectFirst("span.episode-list-item-title")?.text() ?: "Episode $episodeNumber"
            
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
        
        // Find the kr-video iframe
        val iframe = document.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false
        
        // Extract the base64 encoded part
        val base64Part = iframe.substringAfter("/kr-video/").substringBefore("?")
        
        return try {
            // Decode base64 to get the actual video URL
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    decodedUrl,
                    mainUrl,
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            
            true
        } catch (e: Exception) {
            false
        }
    }
}