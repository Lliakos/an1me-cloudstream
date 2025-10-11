package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // Helper suspend function to safely create extractor links
    private suspend fun createLink(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String,
        quality: Int
    ): ExtractorLink {
        return newExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = quality
        }
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

        return document.select("#first_load_result > div").mapNotNull { item ->
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
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()

        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map {
            it.text().trim()
        }

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

    package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

@Suppress("DEPRECATION")
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

        return document.select("#first_load_result > div").mapNotNull { item ->
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
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()

        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map {
            it.text().trim()
        }

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
                ?: return false.also { android.util.Log.d("An1me_Video", "No iframe found") }

            android.util.Log.d("An1me_Video", "Iframe src: $iframeSrc")

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) return false.also {
                android.util.Log.d("An1me_Video", "No base64 part found")
            }

            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            android.util.Log.d("An1me_Video", "Decoded URL: $decodedUrl")

            when {
                // M3U8 files - parse and handle
                decodedUrl.contains(".m3u8") -> {
                    android.util.Log.d("An1me_Video", "M3U8 file detected")
                    
                    val m3u8Response = app.get(decodedUrl).text
                    val lines = m3u8Response.lines()

                    lines.forEachIndexed { index, line ->
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val resolutionMatch = """RESOLUTION=\d+x(\d+)""".toRegex().find(line)
                            val height = resolutionMatch?.groupValues?.get(1)?.toIntOrNull()
                            val quality = when (height) {
                                2160 -> Qualities.P2160.value
                                1440 -> Qualities.P1440.value
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                360 -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            val qualityName = "${height}p"

                            if (index + 1 < lines.size) {
                                val urlLine = lines[index + 1]
                                if (!urlLine.startsWith("#")) {
                                    val fullUrl = if (urlLine.startsWith("http")) {
                                        urlLine
                                    } else {
                                        val baseUrl = decodedUrl.substringBeforeLast("/")
                                        "$baseUrl/$urlLine"
                                    }

                                    val safeUrl = fullUrl
                                        .replace(" ", "%20")
                                        .replace("[", "%5B")
                                        .replace("]", "%5D")

                                    callback.invoke(
                                        ExtractorLink(
                                            name,
                                            "$name $qualityName",
                                            safeUrl,
                                            mainUrl,
                                            quality,
                                            true
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Fallback if no variants found
                    if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
                        val safeUrl = decodedUrl
                            .replace(" ", "%20")
                            .replace("[", "%5B")
                            .replace("]", "%5D")
                        
                        callback.invoke(
                            ExtractorLink(
                                name,
                                name,
                                safeUrl,
                                mainUrl,
                                Qualities.Unknown.value,
                                true
                            )
                        )
                    }
                    return true
                }

                // Google Photos links
                decodedUrl.contains("photos.google.com") || 
                decodedUrl.contains("googleusercontent.com") -> {
                    android.util.Log.d("An1me_Video", "Google Photos link detected")
                    
                    try {
                        val photosDoc = app.get(decodedUrl).document
                        
                        val videoUrl = photosDoc.selectFirst("video source")?.attr("src")
                            ?: photosDoc.selectFirst("meta[property='og:video']")?.attr("content")
                            ?: photosDoc.selectFirst("meta[property='og:video:secure_url']")?.attr("content")
                        
                        if (!videoUrl.isNullOrEmpty()) {
                            android.util.Log.d("An1me_Video", "Found video URL in Google Photos: $videoUrl")
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    "$name Google",
                                    videoUrl,
                                    decodedUrl,
                                    Qualities.Unknown.value,
                                    false
                                )
                            )
                            return true
                        }
                        
                        android.util.Log.d("An1me_Video", "No direct video found, trying loadExtractor")
                        return loadExtractor(decodedUrl, data, subtitleCallback, callback)
                        
                    } catch (e: Exception) {
                        android.util.Log.e("An1me_Video", "Google Photos extraction failed: ${e.message}")
                        return loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    }
                }

                // WeTransfer links
                decodedUrl.contains("wetransfer.com") -> {
                    android.util.Log.d("An1me_Video", "WeTransfer link detected")
                    return try {
                        loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        android.util.Log.e("An1me_Video", "WeTransfer failed: ${e.message}")
                        false
                    }
                }

                // Any other HTTP URL
                decodedUrl.startsWith("http") -> {
                    android.util.Log.d("An1me_Video", "Unknown HTTP URL, trying loadExtractor")
                    return loadExtractor(decodedUrl, data, subtitleCallback, callback)
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