package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
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

    // API endpoint discovered from network inspection
    private val apiUrl = "$mainUrl/wp-json/kiranime/v1"

    override val mainPage = mainPageOf(
        "$apiUrl/anime/latest?page=" to "Latest Episodes",
        "$apiUrl/anime/trending?page=" to "Trending",
        "$apiUrl/anime/popular?page=" to "Popular"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val response = app.get(url).parsedSafe<ApiResponse>()
        
        val home = response?.data?.mapNotNull { anime ->
            newAnimeSearchResponse(
                name = anime.title ?: return@mapNotNull null,
                url = "$mainUrl/anime/${anime.slug}",
                type = getType(anime.type)
            ) {
                this.posterUrl = anime.image
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Using the API endpoint found in network tab
        val searchUrl = "$apiUrl/anime/search?query=$query&lang=jp&_locale=user"
        val response = app.get(searchUrl).parsedSafe<ApiResponse>()

        return response?.data?.mapNotNull { anime ->
            newAnimeSearchResponse(
                name = anime.title ?: return@mapNotNull null,
                url = "$mainUrl/anime/${anime.slug}",
                type = getType(anime.type)
            ) {
                this.posterUrl = anime.image
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract anime information from the page
        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: throw ErrorLoadingException("No title found")
        
        val poster = document.selectFirst("div.anime-poster img")?.attr("src")
            ?: document.selectFirst("img[alt*='$title']")?.attr("src")
        
        val description = document.selectFirst("div.anime-description")?.text()
            ?: document.selectFirst("div.entry-content p")?.text()
        
        val tags = document.select("a[rel='tag']").map { it.text() }
        
        val year = document.selectFirst("span:contains(Year)")
            ?.parent()?.text()?.substringAfter(":")?.trim()?.toIntOrNull()
        
        // Extract episodes
        val episodes = document.select("div.episode-list-item").mapNotNull {
            val episodeTitle = it.selectFirst("span.episode-list-item-title")?.text()
                ?: return@mapNotNull null
            val episodeUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            
            // Use newEpisode constructor as per documentation
            newEpisode(episodeUrl) {
                this.name = episodeTitle
            }
        }.reversed() // Reverse to show Episode 1 first

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
        
        // Find the iframe with video source
        // From investigation: <iframe src="https://an1me.to/kr-video/{base64}?params">
        val iframe = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
            ?: return false
        
        // The iframe src contains a base64 encoded m3u8 URL
        // Format: https://an1me.to/kr-video/{base64EncodedUrl}?params
        val base64Part = iframe.substringAfter("/kr-video/").substringBefore("?")
        
        return try {
            // Decode the base64 to get actual video URL
            // Example decoded: https://cdn2.an1me.io/simple/ova-special-random/....m3u8
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - HLS",
                    url = decodedUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            
            true
        } catch (e: Exception) {
            // Fallback: If base64 decoding fails, try to load the iframe directly
            try {
                val iframeDoc = app.get(iframe, referer = data).document
                val videoSrc = iframeDoc.selectFirst("video source")?.attr("src")
                    ?: iframeDoc.selectFirst("video")?.attr("src")
                
                if (videoSrc != null) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name - Direct",
                            url = videoSrc,
                            referer = iframe,
                            quality = Qualities.Unknown.value,
                            isM3u8 = videoSrc.contains(".m3u8")
                        )
                    )
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun getType(type: String?): TvType {
        return when (type?.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova" -> TvType.OVA
            "special" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    // Data classes for API responses
    // Using Jackson annotations as per documentation
    data class ApiResponse(
        @JsonProperty("data") val data: List<AnimeData>?
    )

    data class AnimeData(
        @JsonProperty("title") val title: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("type") val type: String?
    )
}
