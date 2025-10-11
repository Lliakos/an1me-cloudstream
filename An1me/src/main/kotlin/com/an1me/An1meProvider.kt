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
        val searchUrl = "$mainUrl/search/?s_keyword=$query"
        val document = app.get(searchUrl).document
        
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
        
        // Get genres - only from the anime page itself, not from the sidebar
        // Look for genre links that are inside the anime details section
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { 
            it.text().trim() 
        }
        
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
        try {
            val document = app.get(data).document
            val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
            
            if (iframeSrc.isNullOrEmpty()) {
                android.util.Log.d("An1me_Video", "No iframe found")
                return false
            }
            
            android.util.Log.d("An1me_Video", "Iframe src: $iframeSrc")
            
            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) {
                android.util.Log.d("An1me_Video", "No base64 part found")
                return false
            }
            
            android.util.Log.d("An1me_Video", "Base64 part: ${base64Part.take(50)}...")
            
            // Step 1: Decode the base64 to get the iframe URL
            val iframeUrl = String(Base64.getDecoder().decode(base64Part))
            android.util.Log.d("An1me_Video", "Decoded iframe URL: $iframeUrl")
            
            // Step 2: Load the iframe page itself
            val iframeDoc = app.get(iframeUrl).document
            android.util.Log.d("An1me_Video", "Loaded iframe page, title: ${iframeDoc.title()}")
            
            // Step 3: Extract video sources from the JavaScript in the iframe
            // Look for: const params = {"sources":[{"url":"..."}]}
            val scripts = iframeDoc.select("script")
            android.util.Log.d("An1me_Video", "Found ${scripts.size} script tags")
            
            val scriptText = scripts.firstOrNull { 
                it.data().contains("params") || it.data().contains("sources") 
            }?.data()
            
            if (scriptText == null) {
                android.util.Log.d("An1me_Video", "No script with params/sources found")
                // Log all script content to see what's there
                scripts.forEachIndexed { index, script ->
                    android.util.Log.d("An1me_Video", "Script $index: ${script.data().take(200)}")
                }
                return false
            }
            
            android.util.Log.d("An1me_Video", "Script content (first 500 chars): ${scriptText.take(500)}")
            
            // Try multiple regex patterns
            val patterns = listOf(
                """"sources"\s*:\s*\[\s*\{\s*"url"\s*:\s*"([^"]+)"""",
                """"url"\s*:\s*"([^"]+)"""",
                """sources.*?url.*?["']([^"']+)["']""",
                """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
            )
            
            var videoUrl: String? = null
            for (pattern in patterns) {
                val match = pattern.toRegex().find(scriptText)
                if (match != null) {
                    videoUrl = match.groupValues[1]
                        .replace("\\/", "/")
                        .replace("\\", "")
                    android.util.Log.d("An1me_Video", "Found URL with pattern: $pattern")
                    android.util.Log.d("An1me_Video", "Video URL: $videoUrl")
                    break
                }
            }
            
            if (videoUrl == null) {
                android.util.Log.d("An1me_Video", "No video URL found in script")
                return false
            }
            
            when {
                // M3U8 files work perfectly
                videoUrl.contains(".m3u8") -> {
                    android.util.Log.d("An1me_Video", "Generating M3U8 links")
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = videoUrl,
                        referer = iframeUrl
                    ).forEach(callback)
                    return true
                }
                
                // WeTransfer direct download links
                videoUrl.contains("download.wetransfer.com") || videoUrl.contains("wetransfer.com") -> {
                    android.util.Log.d("An1me_Video", "WeTransfer link, trying M3u8Helper")
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = videoUrl,
                        referer = iframeUrl
                    ).forEach(callback)
                    return true
                }
                
                // Google Drive/Photos links
                videoUrl.contains("googlevideo.com") || videoUrl.contains("googleusercontent.com") -> {
                    android.util.Log.d("An1me_Video", "Google video link, using loadExtractor")
                    return loadExtractor(videoUrl, iframeUrl, subtitleCallback, callback)
                }
                
                // Any other video URL
                else -> {
                    android.util.Log.d("An1me_Video", "Unknown video type, trying loadExtractor")
                    return loadExtractor(videoUrl, iframeUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}", e)
            return false
        }
    }
}