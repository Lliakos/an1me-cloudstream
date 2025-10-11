package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.json.JSONArray

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // Helper suspend function to safely create extractor links; allow specifying type
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

            // Direct m3u8?
            if (decodedUrl.contains(".m3u8")) {
                android.util.Log.d("An1me_Video", "Decoded URL is m3u8, handling playlist")
                return handleM3u8(decodedUrl, callback)
            }

            // Prepare pages to scan (decodedUrl + inner iframe)
            val pagesToScan = mutableListOf<Pair<String, String>>()
            pagesToScan.add(Pair(decodedUrl, mainUrl))

            try {
                val decodedDoc = app.get(decodedUrl, referer = mainUrl).document
                val innerIframe = decodedDoc.selectFirst("iframe")
                if (innerIframe != null) {
                    val innerSrc = innerIframe.attr("src")
                    if (innerSrc.isNotEmpty()) {
                        val resolved = if (innerSrc.startsWith("http")) innerSrc else fixUrl(innerSrc, decodedUrl)
                        android.util.Log.d("An1me_Video", "Found inner iframe, following: $resolved")
                        pagesToScan.add(Pair(resolved, decodedUrl))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("An1me_Video", "Failed to fetch decoded page: ${e.message}")
            }

            var foundAny = false

            for ((pageUrl, referer) in pagesToScan) {
                android.util.Log.d("An1me_Video", "Scanning page: $pageUrl (referer: $referer)")
                val pageText = try {
                    app.get(pageUrl, referer = referer).text
                } catch (e: Exception) {
                    android.util.Log.d("An1me_Video", "Failed to get page: $pageUrl -> ${e.message}")
                    continue
                }

                // Google Photos
                val gg = Regex("https://video\\.googleusercontent\\.com/[^\"'\\s]+").find(pageText)?.value
                if (gg != null) {
                    android.util.Log.d("An1me_Video", "Found Google Photos video: $gg")
                    callback.invoke(
                        createLink(name, "$name (Google Photos)", gg, pageUrl, Qualities.Unknown.value, ExtractorLinkType.Other)
                    )
                    foundAny = true
                }

                // Direct MP4
                val mp4 = Regex("https?://[^\"'\\s]+\\.mp4[^\"'\\s]*").find(pageText)?.value
                if (mp4 != null) {
                    android.util.Log.d("An1me_Video", "Found direct mp4: $mp4")
                    callback.invoke(
                        createLink(name, "$name (MP4)", mp4, pageUrl, Qualities.Unknown.value, ExtractorLinkType.Other)
                    )
                    foundAny = true
                }

                // m3u8
                val m3u8 = Regex("https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*").find(pageText)?.value
                if (m3u8 != null) {
                    android.util.Log.d("An1me_Video", "Found m3u8 in page: $m3u8")
                    if (handleM3u8(m3u8, callback)) foundAny = true
                }

                // WeTransfer detection
                if (pageUrl.contains("collect.wetransfer.com/board/")) {
                    android.util.Log.d("An1me_Video", "Detected WeTransfer link, attempting extraction...")
                    // Extract JSON from page
                    val jsonRegex = Regex("window\\.__INITIAL_STATE__\\s*=\\s*(\\{.+?\\});")
                    val match = jsonRegex.find(pageText)
                    if (match != null) {
                        try {
                            val json = JSONObject(match.groupValues[1])
                            val filesArray = json.optJSONArray("files") ?: JSONArray()
                            for (i in 0 until filesArray.length()) {
                                val f = filesArray.getJSONObject(i)
                                val fileUrl = f.optString("downloadUrl")
                                val fileName = f.optString("name")
                                if (fileUrl.isNotEmpty()) {
                                    android.util.Log.d("An1me_Video", "WeTransfer file found: $fileUrl")
                                    callback.invoke(
                                        createLink(name, "$name (WeTransfer) $fileName", fileUrl, pageUrl, Qualities.Unknown.value, ExtractorLinkType.Other)
                                    )
                                    foundAny = true
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.d("An1me_Video", "Failed parsing WeTransfer JSON: ${e.message}")
                        }
                    }
                }
            }

            return foundAny
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error in loadLinks: ${e.message}", e)
            return false
        }
    }

    private suspend fun handleM3u8(videoUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val m3u8Response = app.get(videoUrl).text
            val lines = m3u8Response.lines()
            var foundVariant = false

            lines.forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val resolutionMatch = """RESOLUTION=\d+x(\d+)""".toRegex().find(line)
                    val height = resolutionMatch?.groupValues?.get(1)?.toIntOrNull()
                    val currentQuality = when (height) {
                        2160 -> Qualities.P2160.value
                        1440 -> Qualities.P1440.value
                        1080 -> Qualities.P1080.value
                        720 -> Qualities.P720.value
                        480 -> Qualities.P480.value
                        360 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    val currentName = if (height != null) "${height}p" else "Unknown"

                    if (index + 1 < lines.size) {
                        val urlLine = lines[index + 1]
                        if (!urlLine.startsWith("#")) {
                            val fullUrl = if (urlLine.startsWith("http")) urlLine else {
                                val baseUrl = videoUrl.substringBeforeLast("/")
                                "$baseUrl/$urlLine"
                            }
                            val safeUrl = fullUrl.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
                            android.util.Log.d("An1me_Video", "Adding variant: $currentName -> $safeUrl")
                            callback.invoke(
                                createLink(name, "$name $currentName", safeUrl, mainUrl, currentQuality, ExtractorLinkType.M3U8)
                            )
                            foundVariant = true
                        }
                    }
                }
            }

            if (!foundVariant) {
                val safeUrl = videoUrl.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
                android.util.Log.d("An1me_Video", "No variants in m3u8, adding master: $safeUrl")
                callback.invoke(
                    createLink(name, name, safeUrl, mainUrl, Qualities.Unknown.value, ExtractorLinkType.M3U8)
                )
            }

            return true
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error handling m3u8: ${e.message}", e)
            return false
        }
    }
}
