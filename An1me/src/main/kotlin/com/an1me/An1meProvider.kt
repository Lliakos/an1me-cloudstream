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

    // Helper function to create extractor links
    private suspend fun createLink(
        sourceName: String,
        linkName: String,
        url: String,
        quality: Int,
        isM3u8: Boolean = false
    ): ExtractorLink {
        return newExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            type = ExtractorLinkType.Other,
            headers = mapOf("Referer" to mainUrl),
            quality = quality,
            isM3u8 = isM3u8
        )
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

            val title = item.selectFirst("span[data-en-title]")?.text()
                ?: item.selectFirst("span[data-nt-title]")?.text()
                ?: return@mapNotNull null

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

        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }

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
            if (iframeSrc.isNullOrEmpty()) return false

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) return false

            val decodedUrl = String(Base64.getDecoder().decode(base64Part))

            // If it ends with m3u8, create quality links
            if (decodedUrl.contains(".m3u8")) {
                val m3u8Response = app.get(decodedUrl).text
                val lines = m3u8Response.lines()

                lines.forEachIndexed { index, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val resolutionMatch = """RESOLUTION=\d+x(\d+)""".toRegex().find(line)
                        val height = resolutionMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val quality = when (height) {
                            2160 -> Qualities.P2160.value
                            1440 -> Qualities.P1440.value
                            1080 -> Qualities.P1080.value
                            720 -> Qualities.P720.value
                            480 -> Qualities.P480.value
                            360 -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        val urlLine = lines.getOrNull(index + 1) ?: return@forEachIndexed
                        if (!urlLine.startsWith("#")) {
                            val fullUrl = if (urlLine.startsWith("http")) urlLine else decodedUrl.substringBeforeLast("/") + "/" + urlLine
                            callback.invoke(createLink(name, "$name ${height}p", fullUrl, quality, true))
                        }
                    }
                }
                return true
            } else {
                // Fallback for iframe pages
                val iframeDoc = app.get(decodedUrl).document
                val scripts = iframeDoc.select("script")
                val scriptText = scripts.firstOrNull { it.data().contains("params") || it.data().contains("sources") }?.data()
                if (scriptText != null) {
                    val patterns = listOf(
                        """"url"\s*:\s*"([^"]+)"""",
                        """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
                    )
                    for (pattern in patterns) {
                        val match = pattern.toRegex().find(scriptText)
                        if (match != null) {
                            val extractedUrl = match.groupValues.getOrNull(1)?.replace("\\/", "/") ?: match.value
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = extractedUrl,
                                referer = decodedUrl
                            ).forEach(callback)
                            return true
                        }
                    }
                }
                return false
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}", e)
            return false
        }
    }
}
