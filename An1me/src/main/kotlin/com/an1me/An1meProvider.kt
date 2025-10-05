package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

@Suppress("DEPRECATION")
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
        
        // Try multiple poster sources
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-lazy-src")
        )
        
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
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl).document
        
        // Try multiple selectors for search results
        val selectors = listOf(
            "li",
            "div.anime-item", 
            "div.search-item",
            "div.item",
            "article",
            "div.post",
            "div.result-item"
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
        
        // Enhanced poster detection
        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img")?.attr("data-src")
                ?: document.selectFirst("div.anime-poster img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img.wp-post-image")?.attr("data-src")
                ?: document.selectFirst("img.wp-post-image")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")
        )
        
        val description = document.selectFirst("div.description")?.text()
            ?: document.selectFirst("div.entry-content p")?.text()
            ?: document.selectFirst("div.entry-content")?.text()
        
        // Enhanced episode detection - look for all episode links
        val episodes = ArrayList<Episode>()
        
        // Primary selectors for episode detection
        val episodeSelectors = listOf(
            "div.entry-content a[href*='/watch/']",
            "div.episodes-list a[href*='/watch/']",
            "a.episode-link",
            "div.episode-list a",
            "ul.episodes li a",
            "div.episodes a",
            "a[href*='/watch/']"
        )
        
        for (selector in episodeSelectors) {
            val foundEpisodes = document.select(selector).mapNotNull { ep ->
                val episodeUrl = ep.attr("href")
                if (episodeUrl.isNullOrEmpty() || !episodeUrl.contains("/watch/")) return@mapNotNull null
                
                val fullUrl = fixUrl(episodeUrl)
                
                // Enhanced episode number extraction
                val episodeText = ep.text()
                val episodeNumber = ep.selectFirst("span.episode-number")?.text()?.trim()?.toIntOrNull()
                    ?: Regex("(?:Episode|Επεισόδιο|EP)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1
                
                val episodeTitle = ep.selectFirst("span.episode-title")?.text()
                    ?: "Episode $episodeNumber"
                
                newEpisode(fullUrl) {
                    this.name = episodeTitle
                    this.episode = episodeNumber
                }
            }
            
            if (foundEpisodes.isNotEmpty()) {
                episodes.addAll(foundEpisodes)
                break
            }
        }
        
        // Fallback: If no episodes found, try to find a single watch button
        if (episodes.isEmpty()) {
            val watchButton = document.selectFirst("a.watch-button, a.btn-watch, a[href*='/watch/']")
            watchButton?.let {
                val watchUrl = it.attr("href")
                if (!watchUrl.isNullOrEmpty()) {
                    episodes.add(newEpisode(fixUrl(watchUrl)) {
                        this.name = "Episode 1"
                        this.episode = 1
                    })
                }
            }
        }
        
        // Sort episodes by number
        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

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
        
        // Try to find iframe
        val iframe = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
            ?: document.selectFirst("iframe")?.attr("src")
            ?: return false
        
        // Extract base64 part from URL
        val base64Part = when {
            iframe.contains("/kr-video/") -> {
                iframe.substringAfter("/kr-video/").substringBefore("?")
            }
            else -> return false
        }
        
        return try {
            // Decode base64
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            
            // Handle different URL types
            when {
                decodedUrl.endsWith(".m3u8") || decodedUrl.contains(".m3u8") -> {
                    // Direct M3U8 link
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = decodedUrl,
                        referer = mainUrl
                    ).forEach(callback)
                    true
                }
                decodedUrl.contains("googlevideo.com") || decodedUrl.contains("googleusercontent.com") -> {
                    // Google Drive/Photos link - try extractor
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    true
                }
                decodedUrl.contains("wetransfer.com") -> {
                    // WeTransfer link - try to extract direct link
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    true
                }
                else -> {
                    // Try generic extractor
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}