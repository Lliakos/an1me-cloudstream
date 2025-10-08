package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty

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
        // The site uses a JavaScript-loaded search system
        // Results are embedded in the page as JSON in a script tag
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document
        
        // Try to parse results from the rendered HTML first
        val results = document.select("#first_load_result > div").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            
            val enTitle = item.selectFirst("span[data-en-title]")?.text()
            val ntTitle = item.selectFirst("span[data-nt-title]")?.text()
            val title = enTitle ?: ntTitle ?: return@mapNotNull null
            
            val posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()
        
        // Get genres
        val tags = document.select("a[href*='/genre/']").map { it.text().trim() }
        
        // Get episodes from swiper
        val episodes = document.select("div.swiper-slide a[href*='/watch/'], a[href*='/watch/'][class*='anime']").mapNotNull { ep ->
            val episodeUrl = fixUrl(ep.attr("href"))
            if (episodeUrl.isEmpty() || episodeUrl.contains("/anime/")) return@mapNotNull null
            
            val episodeTitle = ep.attr("title")
            val episodeNumber = Regex("Episode\\s*(\\d+)|E\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(episodeTitle)?.groupValues?.filterNot { it.isEmpty() }?.lastOrNull()?.toIntOrNull() ?: 1
            
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
                
                // WeTransfer link - needs special handling
                decodedUrl.contains("wetransfer.com") -> {
                    // WeTransfer links don't work directly in players
                    // Try to extract the actual file if possible
                    try {
                        val wetransferDoc = app.get(decodedUrl).document
                        // Look for direct download links
                        val downloadLinks = wetransferDoc.select("a[href*='download']")
                        downloadLinks.forEach { link ->
                            val downloadUrl = link.attr("href")
                            if (downloadUrl.isNotEmpty() && downloadUrl.startsWith("http")) {
                                M3u8Helper.generateM3u8(
                                    source = name,
                                    streamUrl = downloadUrl,
                                    referer = decodedUrl
                                ).forEach(callback)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                
                // Google Drive/Photos or other sources
                else -> {
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}