package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import org.json.JSONObject
import org.json.JSONArray

class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private val apiUrl = "$mainUrl/wp-json/kiranime/v1"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Fetch from homepage and parse the rendered content
        val document = app.get(mainUrl).document
        
        // Extract all anime URLs first
        val animeUrls = document.select("a[href*='/anime/']")
            .map { it.attr("href") }
            .filter { it.contains("/anime/") && !it.contains("/watch/") }
            .distinct()
            .take(30)
        
        // For each URL, fetch the page to get full details
        val home = animeUrls.mapNotNull { url ->
            try {
                val doc = app.get(url).document
                val title = doc.selectFirst("h1.entry-title, h1")?.text() ?: return@mapNotNull null
                val poster = doc.selectFirst("img")?.attr("src")
                
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                null
            }
        }
        
        return newHomePageResponse("Latest Anime", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Try using the API endpoint we discovered
        val searchUrl = "$apiUrl/anime/search?query=$query"
        
        return try {
            val response = app.get(searchUrl).text
            val json = JSONObject(response)
            val data = json.optJSONArray("data") ?: return emptyList()
            
            (0 until data.length()).mapNotNull { i ->
                val anime = data.getJSONObject(i)
                val title = anime.optString("title") ?: return@mapNotNull null
                val slug = anime.optString("slug") ?: return@mapNotNull null
                val image = anime.optString("image")
                
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = image
                }
            }
        } catch (e: Exception) {
            // Fallback to HTML scraping
            val document = app.get("$mainUrl/?s=$query").document
            val animeUrls = document.select("a[href*='/anime/']")
                .map { it.attr("href") }
                .filter { it.contains("/anime/") && !it.contains("/watch/") }
                .distinct()
                .take(20)
            
            animeUrls.mapNotNull { url ->
                try {
                    val doc = app.get(url).document
                    val title = doc.selectFirst("h1.entry-title, h1")?.text() ?: return@mapNotNull null
                    val poster = doc.selectFirst("img")?.attr("src")
                    
                    newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = poster
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst("div.description, div.entry-content p")?.text()
        
        val episodes = document.select("a.episode-list-item, a[href*='/watch/']").mapNotNull { ep ->
            val episodeUrl = ep.attr("href").takeIf { it.isNotEmpty() && it.contains("/watch/") } ?: return@mapNotNull null
            val episodeNumber = ep.selectFirst("span.episode-list-item-number")?.text()?.trim()?.toIntOrNull()
                ?: ep.text().filter { it.isDigit() }.toIntOrNull()
                ?: 0
            val episodeTitle = ep.selectFirst("span.episode-list-item-title")?.text() 
                ?: "Episode $episodeNumber"
            
            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.episode = episodeNumber
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
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
            
            M3u8Helper.generateM3u8(
                name,
                decodedUrl,
                mainUrl
            ).forEach(callback)
            
            true
        } catch (e: Exception) {
            false
        }
    }
}