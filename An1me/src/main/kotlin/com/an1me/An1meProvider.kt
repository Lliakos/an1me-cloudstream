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
        
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()
        
        // Get genres from links containing /genre/
        val tags = document.select("a[href*='/genre/']").map { it.text().trim() }
        
        // Get episodes from swiper carousel
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
        
        // Get iframe src
        val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false
        
        // Extract base64 part (between /kr-video/ and ?)
        val base64Part = iframeSrc
            .substringAfter("/kr-video/")
            .substringBefore("?")
        
        if (base64Part.isEmpty()) return false
        
        return try {
            // Decode base64
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            
            if (decodedUrl.isEmpty()) return false
            
            // Add the M3U8 link directly
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = decodedUrl,
                referer = mainUrl
            ).forEach(callback)
            
            true
        } catch (e: Exception) {
            // If M3u8Helper fails, try loadExtractor
            try {
                val decodedUrl = String(Base64.getDecoder().decode(base64Part))
                loadExtractor(decodedUrl, data, subtitleCallback, callback)
            } catch (e2: Exception) {
                false
            }
        }
    }
}