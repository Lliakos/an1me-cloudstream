package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import android.util.Log

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private fun log(msg: String) {
        Log.d("An1meProvider", msg)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null
        
        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text()
            ?: this.selectFirst("h2, h3, h4")?.text()
            ?: link.attr("title")
            ?: this.selectFirst("img")?.attr("alt")
            ?: return null
        
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
        val items = document.select("li, article, div.anime-card, div.anime-item").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse("Latest Anime", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        log("Searching: $query")
        
        // Use the JSON API for search
        val searchUrl = "$mainUrl/wp-json/kiranime/v1/anime/search?query=$query&lang=jp&_locale=user"
        
        return try {
            val response = app.get(searchUrl).parsed<SearchApiResponse>()
            
            response.data?.mapNotNull { anime ->
                val title = anime.title ?: return@mapNotNull null
                val href = fixUrl("/anime/${anime.slug}/")
                val posterUrl = anime.poster
                
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                }
            } ?: emptyList()
        } catch (e: Exception) {
            log("Search API error: ${e.message}")
            emptyList()
        }
    }
    
    // Data classes for search API
    data class SearchApiResponse(
        val data: List<AnimeData>?
    )
    
    data class AnimeData(
        val title: String?,
        val slug: String?,
        val poster: String?
    )

    override suspend fun load(url: String): LoadResponse {
        log("Loading: $url")
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text() 
            ?: document.selectFirst("h1")?.text()
            ?: document.title()
            ?: "Unknown"
        
        log("Title: $title")
        
        // Enhanced poster detection
        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img")?.attr("data-src")
                ?: document.selectFirst("div.anime-poster img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img.wp-post-image")?.attr("data-src")
                ?: document.selectFirst("img.wp-post-image")?.attr("src")
                ?: document.selectFirst("div.post-thumbnail img")?.attr("src")
                ?: document.selectFirst("img[class*='poster']")?.attr("src")
        )
        
        // Enhanced plot detection
        val description = document.selectFirst("div[data-synopsis]")?.text()
            ?: document.selectFirst("div.description")?.text()
            ?: document.selectFirst("div.entry-content > p")?.text()
            ?: document.selectFirst("div.synopsis")?.text()
            ?: document.selectFirst("div.summary")?.text()
            ?: document.selectFirst("div[class*='description']")?.text()
            ?: document.select("div.entry-content p").firstOrNull()?.text()
        
        // Genre detection - look for links in genre section
        val tags = document.select("a[href*='/genre/']").mapNotNull { 
            it.text().trim().takeIf { text -> text.isNotEmpty() }
        }
        
        log("Found ${tags.size} genres")
        
        // COMPREHENSIVE episode detection
        val episodes = ArrayList<Episode>()
        
        // Primary method: Look for swiper-slide episodes (the structure you showed)
        val swiperEpisodes = document.select("div.swiper-slide a[href*='/watch/'], a[href*='/watch/'][title*='Episode']")
        log("Found ${swiperEpisodes.size} episodes in swiper")
        
        swiperEpisodes.forEach { ep ->
            val episodeUrl = ep.attr("href")
            if (episodeUrl.isNotEmpty()) {
                val fullUrl = fixUrl(episodeUrl)
                
                // Extract episode number from title or span
                val episodeText = ep.attr("title")
                    ?: ep.selectFirst("span")?.text()
                    ?: ep.text()
                
                val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").findAll(episodeText).lastOrNull()?.value?.toIntOrNull()
                    ?: episodes.size + 1
                
                episodes.add(newEpisode(fullUrl) {
                    this.name = "Episode $episodeNumber"
                    this.episode = episodeNumber
                })
            }
        }
        
        // Fallback: Look for any watch links if swiper method fails
        if (episodes.isEmpty()) {
            val watchLinks = document.select("a[href*='/watch/']")
            log("Fallback: Found ${watchLinks.size} watch links")
            
            watchLinks.forEach { ep ->
                val episodeUrl = ep.attr("href")
                if (episodeUrl.isNotEmpty() && !episodeUrl.contains("/anime/")) {
                    val fullUrl = fixUrl(episodeUrl)
                    val episodeText = ep.text()
                    val episodeNumber = Regex("\\d+").findAll(episodeText).lastOrNull()?.value?.toIntOrNull()
                        ?: episodes.size + 1
                    
                    episodes.add(newEpisode(fullUrl) {
                        this.name = "Episode $episodeNumber"
                        this.episode = episodeNumber
                    })
                }
            }
        }
        
        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }
        log("Total episodes found: ${sortedEpisodes.size}")
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("Loading links for: $data")
        
        val document = app.get(data).document
        
        // Look for the kr-video iframe
        val iframe = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
        
        if (iframe.isNullOrEmpty()) {
            log("No kr-video iframe found")
            return false
        }
        
        log("Found iframe: $iframe")
        
        // Extract base64 part (everything between /kr-video/ and ?)
        val base64Part = iframe.substringAfter("/kr-video/").substringBefore("?")
        
        return try {
            // Decode the base64 string
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            log("Decoded URL: $decodedUrl")
            
            when {
                decodedUrl.contains(".m3u8") -> {
                    log("M3U8 stream found")
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = decodedUrl,
                        referer = mainUrl
                    ).forEach(callback)
                    true
                }
                else -> {
                    log("Trying generic extractor for: $decodedUrl")
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            log("Error decoding/loading: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}