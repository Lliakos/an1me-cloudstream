package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64

class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Simple test: just show some hardcoded anime to verify the plugin works
        val testAnime = listOf(
            newAnimeSearchResponse("Test Anime 1", "$mainUrl/anime/test1", TvType.Anime),
            newAnimeSearchResponse("Test Anime 2", "$mainUrl/anime/test2", TvType.Anime),
            newAnimeSearchResponse("Test Anime 3", "$mainUrl/anime/test3", TvType.Anime)
        )
        
        return newHomePageResponse("Latest Anime", testAnime)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // For now, return test results
        return listOf(
            newAnimeSearchResponse("Search Result 1", "$mainUrl/anime/result1", TvType.Anime),
            newAnimeSearchResponse("Search Result 2", "$mainUrl/anime/result2", TvType.Anime)
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // Load the actual page
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst("div.description, div.entry-content p")?.text()
        
        val episodes = document.select("a.episode-list-item, a[href*='/watch/']").mapNotNull { ep ->
            val episodeUrl = ep.attr("href").takeIf { it.isNotEmpty() && it.contains("/watch/") } ?: return@mapNotNull null
            val episodeNumber = ep.selectFirst("span.episode-list-item-number")?.text()?.trim()?.toIntOrNull()
                ?: ep.text().filter { it.isDigit() }.toIntOrNull()
                ?: 0
            val episodeTitle = ep.selectFirst("span.episode-list-item-title")?.text() 
                ?: "Episode $episodeNumber"
            
            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.episode = episodeNumber
            }
        }

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
            
            M3u8Helper.generateM3u8(
                name,
                decodedUrl,
                mainUrl
            ).forEach(callback)
            
            true
        } catch (e: Exception) {
            false
        }
    }
}