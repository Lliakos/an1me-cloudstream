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
        
        // Get poster
        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img, img.poster")?.attr("src")
        )
        
        // Get background banner image
        val backgroundPoster = fixUrlNull(
            document.selectFirst("div.watch-section-bg")?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")?.replace("'", "")?.replace("\"", "")
        )
        
        // Get description/plot from data-synopsis attribute
        val description = document.selectFirst("div[data-synopsis]")?.text()
        
        // Get genres/tags
        val genres = document.select("a[href*='/genres/'], a[href*='/genre/']").map { it.text() }
        
        // Get year
        val year = document.selectFirst("span:contains(Έτος), span:contains(Year)")
            ?.parent()?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        // Get trailer
        val trailerButton = document.selectFirst("button[onclick*='youtube']")
        val trailerUrl = trailerButton?.attr("onclick")
            ?.substringAfter("'")?.substringBefore("'")
            ?.let { if (it.contains("youtube.com") || it.contains("youtu.be")) it else null }
        
        // Get episodes from swiper slides
        val episodes = document.select("div.swiper-slide a[href*='/watch/']").mapNotNull { ep ->
            val episodeUrl = fixUrl(ep.attr("href"))
            if (episodeUrl.isEmpty() || !episodeUrl.contains("/watch/")) return@mapNotNull null
            
            val episodeText = ep.selectFirst("span.absolute, span")?.text() 
                ?: ep.attr("title")
                ?: "Episode"
            val episodeNumber = episodeText.filter { it.isDigit() }.toIntOrNull() ?: 1
            
            newEpisode(episodeUrl) {
                this.name = episodeText
                this.episode = episodeNumber
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backgroundPoster
            this.plot = description
            this.tags = genres
            this.year = year
            if (trailerUrl != null) {
                addTrailer(trailerUrl)
            }
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