package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import java.net.URLEncoder

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // Helper function to create extractor links without deprecation
    private fun createLink(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String,
        quality: Int,
        isM3u8: Boolean = false
    ): ExtractorLink {
        return ExtractorLink(sourceName, linkName, url, referer, quality, isM3u8)
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
            
            // Decode the base64 to get the video URL
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            android.util.Log.d("An1me_Video", "Decoded URL: $decodedUrl")
            
            // The URL is already percent-encoded (%5B = [, %5D = ]), so it should work
            // But if M3u8Helper has issues, we keep the URL as-is since it's already encoded
            val videoUrl = decodedUrl
            
            when {
                // Direct M3U8 file
                videoUrl.contains(".m3u8") -> {
                    android.util.Log.d("An1me_Video", "Direct M3U8 link found")
                    android.util.Log.d("An1me_Video", "Video URL: $videoUrl")
                    
                    // Fetch the M3U8 content and manually parse to avoid URI encoding issues
                    try {
                        val m3u8Response = app.get(videoUrl).text
                        android.util.Log.d("An1me_Video", "M3U8 content (first 200 chars): ${m3u8Response.take(200)}")
                        
                        // Parse quality variants manually
                        val lines = m3u8Response.lines()
                        var currentQuality = Qualities.Unknown.value
                        var currentName = "Unknown"
                        
                        lines.forEachIndexed { index, line ->
                            if (line.startsWith("#EXT-X-STREAM-INF")) {
                                // Extract resolution/quality info
                                val resolutionMatch = """RESOLUTION=\d+x(\d+)""".toRegex().find(line)
                                val height = resolutionMatch?.groupValues?.get(1)?.toIntOrNull()
                                
                                currentQuality = when (height) {
                                    2160 -> Qualities.P2160.value
                                    1440 -> Qualities.P1440.value
                                    1080 -> Qualities.P1080.value
                                    720 -> Qualities.P720.value
                                    480 -> Qualities.P480.value
                                    360 -> Qualities.P360.value
                                    else -> Qualities.Unknown.value
                                }
                                currentName = "${height}p"
                                
                                // Next line should be the URL
                                if (index + 1 < lines.size) {
                                    val urlLine = lines[index + 1]
                                    if (!urlLine.startsWith("#")) {
                                        val fullUrl = if (urlLine.startsWith("http")) {
                                            urlLine
                                        } else {
                                            // Relative URL - construct full path
                                            val baseUrl = videoUrl.substringBeforeLast("/")
                                            "$baseUrl/$urlLine"
                                        }
                                        
                                        android.util.Log.d("An1me_Video", "Adding quality link: $currentName - $fullUrl")
                                        
                                        callback.invoke(
                                            createLink(
                                                name,
                                                "$name $currentName",
                                                fullUrl,
                                                mainUrl,
                                                currentQuality,
                                                true
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        
                        // If no variants found, add the master playlist directly
                        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
                            android.util.Log.d("An1me_Video", "No quality variants, adding master playlist")
                            callback.invoke(
                                createLink(
                                    name,
                                    name,
                                    videoUrl,
                                    mainUrl,
                                    Qualities.Unknown.value,
                                    true
                                )
                            )
                        }
                        
                        return true
                    } catch (e: Exception) {
                        android.util.Log.e("An1me_Video", "Failed to parse M3U8: ${e.message}")
                        // Last resort fallback
                        callback.invoke(
                            createLink(
                                name,
                                name,
                                videoUrl,
                                mainUrl,
                                Qualities.Unknown.value,
                                true
                            )
                        )
                        return true
                    }
                }
                
                // If it's not a direct video URL, it might be an iframe page
                videoUrl.startsWith("http") && !videoUrl.contains(".m3u8") -> {
                    android.util.Log.d("An1me_Video", "Loading iframe page to extract video")
                    
                    val iframeDoc = app.get(videoUrl).document
                    val scripts = iframeDoc.select("script")
                    android.util.Log.d("An1me_Video", "Found ${scripts.size} script tags")
                    
                    val scriptText = scripts.firstOrNull { 
                        it.data().contains("params") || it.data().contains("sources") 
                    }?.data()
                    
                    if (scriptText == null) {
                        android.util.Log.d("An1me_Video", "No script with video sources found")
                        return false
                    }
                    
                    // Try to extract video URL from JavaScript
                    val patterns = listOf(
                        """"url"\s*:\s*"([^"]+)"""",
                        """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
                    )
                    
                    for (pattern in patterns) {
                        val match = pattern.toRegex().find(scriptText)
                        if (match != null) {
                            val extractedUrl = match.groupValues.getOrNull(1)?.let {
                                it.replace("\\/", "/").replace("\\", "")
                            } ?: match.value
                            
                            android.util.Log.d("An1me_Video", "Extracted video URL: $extractedUrl")
                            
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = extractedUrl,
                                referer = videoUrl
                            ).forEach(callback)
                            return true
                        }
                    }
                    
                    android.util.Log.d("An1me_Video", "Could not extract video URL from iframe")
                    return false
                }
                
                else -> {
                    android.util.Log.d("An1me_Video", "Unknown URL format")
                    return false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}", e)
            return false
        }
    }
}