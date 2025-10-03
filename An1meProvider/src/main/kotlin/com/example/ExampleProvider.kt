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
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "/recent" to "Latest Episodes",
        "/trending" to "Trending",
        "/popular" to "Popular"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl${request.data}").document
        
        val home = document.select("div.anime-card, div.film_list-wrap > div.flw-item").mapNotNull {
            it.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document

        return document.select("div.anime-card, div.search-result-item, div.flw-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val title = this.selectFirst("h3, h2, div.film-name a, a.title")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("src")
        )
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1.anime-title, h1")?.text()
            ?: throw ErrorLoadingException("No title found")
        
        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img, img.film-poster-img")?.attr("src")
        )
        
        val description = document.selectFirst("div.anime-description, div.description, div.film-description")?.text()
        
        val tags = document.select("a[rel='tag'], div.genres a").map { it.text() }
        
        val year = document.selectFirst("span:contains(Year), div.item:contains(Released)")
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        val episodes = document.select("div.episode-list-item, ul.episodes-list li, div.ss-list a").mapIndexed { index, ep ->
            val episodeTitle = ep.selectFirst("span.episode-list-item-title, span")?.text() 
                ?: "Episode ${index + 1}"
            val episodeUrl = fixUrl(ep.selectFirst("a")?.attr("href") ?: ep.attr("href"))
            
            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.episode = index + 1
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
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
        
        // Find iframe with video source
        val iframe = document.selectFirst("iframe[src*='kr-video'], iframe[src*='player']")?.attr("src")
            ?: return false
        
        val fullIframeUrl = fixUrl(iframe)
        
        // Check if it's the base64 encoded format
        if (iframe.contains("/kr-video/")) {
            try {
                val base64Part = iframe.substringAfter("/kr-video/").substringBefore("?")
                val decodedUrl = String(Base64.getDecoder().decode(base64Part))
                
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = decodedUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                
                return true
            } catch (e: Exception) {
                // If decoding fails, continue to fallback
            }
        }
        
        // Fallback: Load iframe and extract video
        try {
            val iframeDoc = app.get(fullIframeUrl, referer = data).document
            
            val videoUrl = iframeDoc.selectFirst("source")?.attr("src")
                ?: iframeDoc.selectFirst("video")?.attr("src")
                ?: return false
            
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = fullIframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
            
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
