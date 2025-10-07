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
        Log.d("An1me_Debug", msg)
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
        
        log("Search result: $title -> $href")
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select("li").mapNotNull { it.toSearchResult() }
        log("Main page: Found ${items.size} items")
        return newHomePageResponse("Latest Anime", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        log("=== SEARCH DEBUG ===")
        log("Search query: $query")
        
        val searchUrl = "$mainUrl/?s=$query"
        log("Search URL: $searchUrl")
        
        val document = app.get(searchUrl).document
        
        // Debug: Print the HTML title and body length
        log("Page title: ${document.title()}")
        log("Body length: ${document.body().text().length}")
        
        // Try multiple selectors and log what we find
        val selectors = listOf("li", "article", "div.anime-item", "div.post", "div[class*='anime']")
        
        for (selector in selectors) {
            val elements = document.select(selector)
            log("Selector '$selector' found ${elements.size} elements")
            
            if (elements.size > 0) {
                // Log first element's HTML for inspection
                log("First element HTML: ${elements.first()?.html()?.take(200)}")
            }
        }
        
        val results = document.select("li").mapNotNull { it.toSearchResult() }
        log("Search results: ${results.size} items")
        
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        log("=== LOAD DEBUG ===")
        log("Loading URL: $url")
        
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        log("Title: $title")
        
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        log("Poster: $poster")
        
        val description = document.selectFirst("div[data-synopsis]")?.text()
        log("Description length: ${description?.length ?: 0}")
        
        // DEBUG GENRES
        log("--- Genre Debug ---")
        val genreLinks = document.select("a[href*='/genre/']")
        log("Found ${genreLinks.size} genre links")
        genreLinks.take(5).forEach { link ->
            log("Genre link: ${link.attr("href")} -> ${link.text()}")
        }
        val tags = genreLinks.map { it.text().trim() }.filter { it.isNotEmpty() }
        log("Final tags: $tags")
        
        // DEBUG EPISODES
        log("--- Episode Debug ---")
        val swiperEpisodes = document.select("div.swiper-slide a[href*='/watch/']")
        log("Swiper episodes found: ${swiperEpisodes.size}")
        
        val allWatchLinks = document.select("a[href*='/watch/']")
        log("Total watch links on page: ${allWatchLinks.size}")
        
        // Log first 3 episode links
        swiperEpisodes.take(3).forEach { ep ->
            log("Episode: ${ep.attr("href")} | Title: ${ep.attr("title")}")
        }
        
        val episodes = swiperEpisodes.mapNotNull { ep ->
            val episodeUrl = fixUrl(ep.attr("href"))
            if (episodeUrl.isEmpty()) return@mapNotNull null
            
            val episodeTitle = ep.attr("title")
            val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            
            newEpisode(episodeUrl) {
                this.name = "Episode $episodeNumber"
                this.episode = episodeNumber
            }
        }.sortedBy { it.episode }
        
        log("Total episodes: ${episodes.size}")

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
        log("=== LOADLINKS DEBUG ===")
        log("Episode URL: $data")
        
        val document = app.get(data).document
        
        // Debug: Check all iframes
        val allIframes = document.select("iframe")
        log("Total iframes on page: ${allIframes.size}")
        allIframes.forEach { iframe ->
            log("Iframe src: ${iframe.attr("src")}")
        }
        
        val iframe = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
        if (iframe.isNullOrEmpty()) {
            log("ERROR: No kr-video iframe found!")
            return false
        }
        
        log("Kr-video iframe: $iframe")
        
        val base64Part = iframe.substringAfter("/kr-video/").substringBefore("?")
        log("Base64 extracted: ${base64Part.take(50)}...")
        
        return try {
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            log("Decoded URL: $decodedUrl")
            
            when {
                decodedUrl.contains(".m3u8") -> {
                    log("M3U8 detected - using M3u8Helper")
                    val links = M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = decodedUrl,
                        referer = mainUrl
                    )
                    log("M3U8 generated ${links.size} links")
                    links.forEach { link ->
                        log("Link: ${link.name} | Quality: ${link.quality} | URL: ${link.url.take(50)}")
                        callback(link)
                    }
                    true
                }
                else -> {
                    log("Non-M3U8 URL - using loadExtractor")
                    val result = loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    log("LoadExtractor result: $result")
                    result
                }
            }
        } catch (e: Exception) {
            log("EXCEPTION: ${e.message}")
            log("Stack trace: ${e.stackTraceToString()}")
            false
        }
    }
}