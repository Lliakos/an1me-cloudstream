package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import android.util.Log

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
        log("Loading: $url")
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        log("Title: $title")
        
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        
        val description = document.selectFirst("div[data-synopsis]")?.text()
        
        // Fix genres selector - should be /genre/ not /genres/
        val tags = document.select("a[href*='/genre/']").map { it.text().trim() }
        log("Genres found: ${tags.size}")
        
        // Try to get anime ID from URL or page data to fetch ALL episodes
        val animeSlug = url.substringAfterLast("/anime/").substringBefore("/")
        log("Anime slug: $animeSlug")
        
        val episodes = ArrayList<Episode>()
        
        // Method 1: Try to get episodes from API (check if there's a JSON endpoint)
        try {
            val apiUrl = "$mainUrl/wp-json/kiranime/v1/anime/episodes/$animeSlug"
            log("Trying API: $apiUrl")
            val apiResponse = app.get(apiUrl).parsedSafe<EpisodesApiResponse>()
            
            apiResponse?.episodes?.forEach { ep ->
                val episodeUrl = fixUrl("/watch/${ep.slug}/")
                episodes.add(newEpisode(episodeUrl) {
                    this.name = "Episode ${ep.number}"
                    this.episode = ep.number
                })
            }
            log("API method: Found ${episodes.size} episodes")
        } catch (e: Exception) {
            log("API method failed: ${e.message}")
        }
        
        // Method 2: If API fails, scrape from page
        if (episodes.isEmpty()) {
            val swiperEpisodes = document.select("div.swiper-slide a[href*='/watch/'], a[href*='/watch/'][title*='Episode']")
            log("Swiper method: Found ${swiperEpisodes.size} episodes")
            
            swiperEpisodes.forEach { ep ->
                val episodeUrl = fixUrl(ep.attr("href"))
                if (episodeUrl.isNotEmpty()) {
                    val episodeTitle = ep.attr("title")
                    val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: episodes.size + 1
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = "Episode $episodeNumber"
                        this.episode = episodeNumber
                    })
                }
            }
        }
        
        // Method 3: Look for a "Load More" button or pagination for episodes
        if (episodes.isEmpty()) {
            document.select("a[href*='/watch/']").forEach { ep ->
                val episodeUrl = ep.attr("href")
                if (episodeUrl.isNotEmpty() && !episodeUrl.contains("/anime/")) {
                    val fullUrl = fixUrl(episodeUrl)
                    val episodeNumber = Regex("\\d+").findAll(ep.text()).lastOrNull()?.value?.toIntOrNull()
                        ?: episodes.size + 1
                    
                    episodes.add(newEpisode(fullUrl) {
                        this.name = "Episode $episodeNumber"
                        this.episode = episodeNumber
                    })
                }
            }
            log("Fallback method: Found ${episodes.size} episodes")
        }
        
        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }
        log("Total unique episodes: ${sortedEpisodes.size}")

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
        log("Loading video for: $data")
        
        val document = app.get(data).document
        
        val iframe = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
        if (iframe.isNullOrEmpty()) {
            log("No kr-video iframe found")
            return false
        }
        
        log("Iframe found: $iframe")
        
        val base64Part = iframe.substringAfter("/kr-video/").substringBefore("?")
        log("Base64 part: $base64Part")
        
        return try {
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            log("Decoded URL: $decodedUrl")
            
            when {
                decodedUrl.contains(".m3u8") -> {
                    log("Processing M3U8 stream")
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = decodedUrl,
                        referer = mainUrl
                    ).forEach { link ->
                        log("Adding M3U8 link: ${link.name} - ${link.quality}")
                        callback(link)
                    }
                    true
                }
                else -> {
                    log("Using generic extractor for: $decodedUrl")
                    val result = loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    log("Extractor result: $result")
                    result
                }
            }
        } catch (e: Exception) {
            log("Error in loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // Data classes for API responses
    data class EpisodesApiResponse(
        val episodes: List<EpisodeData>?
    )
    
    data class EpisodeData(
        val number: Int,
        val slug: String
    )
}