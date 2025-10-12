package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.Base64

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private suspend fun createLink(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ): ExtractorLink {
        return newExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            type = type
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

            // 🟩 Handle WeTransfer (use original iframe HTML, not decoded)
            if (decodedUrl.contains("wetransfer.com", true) || decodedUrl.contains("collect.wetransfer.com", true)) {
                android.util.Log.d("An1me_Video", "Detected WeTransfer link, attempting extraction...")

                try {
                    val iframeHtml = app.get(iframeSrc).text
                    val cleanedHtml = iframeHtml
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("\\u003c", "<")
                        .replace("\\u003e", ">")
                        .replace("\\\\", "\\")
                        .replace("\\/", "/")

                    val match = Regex("""const\s+params\s*=\s*(\{.*?"sources".*?\});""", RegexOption.DOT_MATCHES_ALL)
                        .find(cleanedHtml)
                        ?.groupValues?.get(1)

                    if (match == null) {
                        android.util.Log.d("An1me_Video", "No JSON params found in WeTransfer iframe")
                        return false
                    }

                    val json = JSONObject(match)
                    val sources = json.optJSONArray("sources")
                    if (sources != null && sources.length() > 0) {
                        val videoUrl = sources.getJSONObject(0).getString("url")
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")
                            .replace("\\u003d", "=")

                        android.util.Log.d("An1me_Video", "Found WeTransfer video URL: $videoUrl")

                        callback(
                            createLink(
                                sourceName = name,
                                linkName = "$name (WeTransfer)",
                                url = videoUrl,
                                referer = iframeSrc,
                                quality = if (videoUrl.contains("1080")) Qualities.P1080.value else Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        return true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Error parsing WeTransfer iframe: ${e.message}", e)
                    return false
                }
            }

            // 🟨 Handle Google Photos
            if (decodedUrl.contains("photos.google.com", true)) {
                try {
                    android.util.Log.d("An1me_Video", "Detected Google Photos source — trying to extract direct video link")

                    val photoHtml = app.get(decodedUrl, referer = iframeSrc).text
                    val regex = Regex("""https:\/\/(lh3|video)\.googleusercontent\.com\/[^\"]+""")
                    val match = regex.find(photoHtml)
                    if (match != null) {
                        val rawUrl = match.value
                            .replace("\\u003d", "=")
                            .replace("\\u0026", "&")
                            .replace("\\/", "/")

                        android.util.Log.d("An1me_Video", "Found Google Photos video URL: $rawUrl")

                        callback(
                            createLink(
                                sourceName = name,
                                linkName = "$name (Google Photos)",
                                url = rawUrl,
                                referer = decodedUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        return true
                    } else {
                        android.util.Log.d("An1me_Video", "No googleusercontent link found in Photos page")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Error extracting Google Photos video: ${e.message}", e)
                }
            }

            // 🟦 Handle M3U8 and direct MP4
            if (decodedUrl.contains(".m3u8", true)) {
                android.util.Log.d("An1me_Video", "Detected M3U8 stream — playing directly")
                callback(
                    createLink(
                        sourceName = name,
                        linkName = "$name (M3U8)",
                        url = decodedUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                return true
            }

            if (decodedUrl.contains(".mp4", true)) {
                android.util.Log.d("An1me_Video", "Detected direct MP4 video — playing directly")
                callback(
                    createLink(
                        sourceName = name,
                        linkName = "$name (MP4)",
                        url = decodedUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }

            android.util.Log.d("An1me_Video", "No valid video link found.")
            return false

        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error loading links: ${e.message}", e)
            return false
        }
    }
}
