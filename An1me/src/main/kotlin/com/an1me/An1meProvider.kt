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
        // The site uses /search/?s_keyword= instead of /?s=
        val searchUrl = "$mainUrl/search/?s_keyword=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        // Try different selectors for search results
        val selectors = listOf(
            "li",
            "article",
            "div.anime-card",
            "div.anime-item",
            "div.post",
            "div[class*='anime']",
            "div[class*='item']",
            "a[href*='/anime/']"
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
        
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()
        
        // Get genres
        val tags = document.select("a[href*='/genre/']").map { it.text().trim() }
        
        // Get episodes
        val episodes = document.select("div.swiper-slide a[href*='/watch/']").mapNotNull { ep ->
            val episodeUrl = fixUrl(ep.attr("href"))
            if (episodeUrl.isEmpty()) return@mapNotNull null
            
            val episodeTitle = ep.attr("title")
            val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            
            newEpisode(episodeUrl) {
                this.name = "Episode $episodeNumber"
                this.episode = episodeNumber
            }
        }.sortedBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
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
        val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false
        
        val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
        if (base64Part.isEmpty()) return false
        
        return try {
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            
            when {
                // Direct M3U8 file
                decodedUrl.endsWith(".m3u8") -> {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = decodedUrl,
                        referer = mainUrl
                    ).forEach(callback)
                    true
                }
                
                // WeTransfer link
                decodedUrl.contains("wetransfer.com") -> {
                    extractWeTransfer(decodedUrl, callback)
                }
                
                // Google Drive/Photos
                decodedUrl.contains("googlevideo.com") || decodedUrl.contains("googleusercontent.com") -> {
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    true
                }
                
                // Try generic extractor for other sources
                else -> {
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractWeTransfer(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            // Fetch the WeTransfer page
            val doc = app.get(url).document
            
            // Look for download links in the page
            // WeTransfer boards have files listed with download buttons
            val downloadLinks = doc.select("a[href*='download'], button[data-url]")
            
            downloadLinks.forEach { link ->
                val downloadUrl = link.attr("href").ifEmpty { link.attr("data-url") }
                if (downloadUrl.isNotEmpty()) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "WeTransfer",
                            downloadUrl,
                            url,
                            Qualities.Unknown.value,
                        )
                    )
                }
            }
            
            // Alternative: Look for file info in JSON/scripts
            val scripts = doc.select("script:containsData(files)")
            scripts.forEach { script ->
                val scriptText = script.data()
                // Try to extract file URLs from JavaScript
                Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(scriptText).forEach { match ->
                    val fileUrl = match.groupValues[1]
                    if (fileUrl.contains("http") && !fileUrl.contains("wetransfer.com")) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "WeTransfer File",
                                fileUrl,
                                url,
                                Qualities.Unknown.value,
                            )
                        )
                    }
                }
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
}