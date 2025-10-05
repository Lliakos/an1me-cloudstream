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
        val encodedQuery = query.replace(" ", "+")
        val document = app.get("$mainUrl/?s=$encodedQuery").document
        
        // Try multiple selectors for search results
        val selectors = listOf(
            "li", 
            "div.anime-item", 
            "div.search-item",
            "div.item",
            "article"
        )
        
        for (selector in selectors) {
            val results = document.select(selector).mapNotNull { it.toSearchResult() }
            if (results.isNotEmpty()) {
                return results
            }
        }
        
        return emptyList()
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
        
        // Enhanced episode detection with multiple selectors
        val episodes = ArrayList<Episode>()
        
        // Try multiple selectors for episode lists
        val episodeSelectors = listOf(
            "a.episode-list-item",
            "div.episodes-list a",
            "div.episode-list a",
            "ul.episodes-list li a",
            "div.episodes a",
            "div.episode a"
        )
        
        for (selector in episodeSelectors) {
            val foundEpisodes = document.select(selector).mapNotNull { ep ->
                val episodeUrl = fixUrl(ep.attr("href"))
                if (episodeUrl.isEmpty()) return@mapNotNull null
                
                // Try multiple ways to get episode number
                val episodeNumber = ep.selectFirst("span.episode-list-item-number")?.text()?.trim()?.toIntOrNull()
                    ?: ep.selectFirst("span.episode-number")?.text()?.trim()?.toIntOrNull()
                    ?: ep.text().trim().replace("Episode", "").trim().toIntOrNull()
                    ?: Regex("Episode\\s*(\\d+)").find(ep.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("EP\\s*(\\d+)").find(ep.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                
                val episodeTitle = ep.selectFirst("span.episode-list-item-title")?.text()
                    ?: ep.selectFirst("span.episode-title")?.text()
                    ?: "Episode $episodeNumber"
                
                newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.episode = episodeNumber
                }
            }
            
            if (foundEpisodes.isNotEmpty()) {
                episodes.addAll(foundEpisodes)
                break
            }
        }
        
        // If no episodes found, create a dummy episode for testing
        if (episodes.isEmpty()) {
            val watchUrl = document.selectFirst("a.watch-button")?.attr("href")
                ?: document.selectFirst("a.btn-watch")?.attr("href")
                ?: document.selectFirst("a[href*='/watch/']")?.attr("href")
                
            if (!watchUrl.isNullOrEmpty()) {
                episodes.add(newEpisode(fixUrl(watchUrl)) {
                    this.name = "Episode 1"
                    this.episode = 1
                })
            }
        }
        
        // Sort episodes in descending order
        val sortedEpisodes = episodes.sortedByDescending { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
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
            loadExtractor(decodedUrl, data, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            false
        }
    }
}