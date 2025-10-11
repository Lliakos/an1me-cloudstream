package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import org.json.JSONObject

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

            // Extract base64 part (same as before) but do NOT automatically use decoded URL for WeTransfer
            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) {
                android.util.Log.d("An1me_Video", "No base64 part found")
                return false
            }

            // Decode for later use (non-WeTransfer)
            val decodedUrl = try {
                String(Base64.getDecoder().decode(base64Part))
            } catch (e: Exception) {
                android.util.Log.e("An1me_Video", "Base64 decode failed: ${e.message}", e)
                ""
            }
            android.util.Log.d("An1me_Video", "Decoded URL: $decodedUrl")

            // If this is a WeTransfer iframe, DO NOT use decodedUrl for extraction.
            // Instead fetch the kr-video iframe URL (iframeSrc) and find const params = {...} inside it.
            if (iframeSrc.contains("wetransfer", true) || decodedUrl.contains("wetransfer", true)) {
                android.util.Log.d("An1me_Video", "Detected WeTransfer source — using iframeSrc HTML to extract params")

                try {
                    // Fetch the iframeSrc HTML (do not decode) and extract the JS params object
                    val iframeHtml = app.get(iframeSrc, referer = data).text

                    val cleanedHtml = iframeHtml
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("\\u003c", "<")
                        .replace("\\u003e", ">")
                        .replace("\\\\", "\\")
                        .replace("\\/", "/")

                    val jsonMatch = Regex("""const\s+params\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                        .find(cleanedHtml)

                    if (jsonMatch == null) {
                        android.util.Log.d("An1me_Video", "No 'const params' JSON found in WeTransfer iframe HTML")
                        return false
                    }

                    val jsonString = jsonMatch.groupValues[1]
                    val json = JSONObject(jsonString)
                    val sources = json.optJSONArray("sources") ?: return false.also {
                        android.util.Log.d("An1me_Video", "No sources found in params JSON")
                    }

                    for (i in 0 until sources.length()) {
                        val srcObj = sources.getJSONObject(i)
                        val videoUrl = srcObj.optString("url")
                        if (videoUrl.isNullOrEmpty()) continue

                        android.util.Log.d("An1me_Video", "Found WeTransfer video URL: $videoUrl")

                        callback(
                            createLink(
                                sourceName = name,
                                linkName = "$name (WeTransfer)",
                                url = videoUrl,
                                referer = iframeSrc,
                                quality = when {
                                    videoUrl.contains("1080", true) -> Qualities.P1080.value
                                    videoUrl.contains("720", true) -> Qualities.P720.value
                                    videoUrl.contains("480", true) -> Qualities.P480.value
                                    else -> Qualities.Unknown.value
                                },
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }

                    return true
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Error extracting WeTransfer iframe HTML: ${e.message}", e)
                    return false
                }
            }

            // -------------------------
            // Non-WeTransfer flow (preserve original decode behavior)
            // -------------------------
            // If the decoded URL already points to an m3u8 or mp4, return it directly.
            if (!decodedUrl.isEmpty()) {
                if (decodedUrl.contains(".m3u8", true)) {
                    android.util.Log.d("An1me_Video", "Decoded URL is an m3u8, returning as M3U8")
                    callback(
                        createLink(
                            sourceName = name,
                            linkName = name,
                            url = decodedUrl,
                            referer = iframeSrc,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return true
                }
                if (decodedUrl.contains(".mp4", true) || decodedUrl.contains("googleusercontent", true) || decodedUrl.contains("googleapis", true)) {
                    android.util.Log.d("An1me_Video", "Decoded URL looks like direct video, returning as VIDEO")
                    callback(
                        createLink(
                            sourceName = name,
                            linkName = name,
                            url = decodedUrl,
                            referer = iframeSrc,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    return true
                }

                // If decoded URL is a page (e.g. contains its own iframe), try to fetch it and find a video or m3u8 inside.
                try {
                    val decodedDocResponse = app.get(decodedUrl, referer = iframeSrc)
                    val decodedDoc = decodedDocResponse.document
                    // try to find video source tags or iframes
                    val videoTag = decodedDoc.selectFirst("video source[src], video[src]")
                    if (videoTag != null) {
                        val videoUrl = videoTag.attr("src")
                        android.util.Log.d("An1me_Video", "Video source found inside decoded page: $videoUrl")
                        callback(
                            createLink(
                                sourceName = name,
                                linkName = name,
                                url = videoUrl,
                                referer = decodedUrl,
                                quality = Qualities.Unknown.value,
                                type = if (videoUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        return true
                    }

                    // Some providers return an iframe inside the decoded page. Follow it if present.
                    val innerIframe = decodedDoc.selectFirst("iframe[src]")
                    val innerIframeSrc = innerIframe?.attr("src")
                    if (!innerIframeSrc.isNullOrEmpty()) {
                        android.util.Log.d("An1me_Video", "Found inner iframe inside decoded page: $innerIframeSrc")
                        // If inner iframe points to m3u8 or mp4 directly, return it
                        if (innerIframeSrc.contains(".m3u8", true)) {
                            callback(createLink(name, name, innerIframeSrc, decodedUrl, Qualities.Unknown.value, ExtractorLinkType.M3U8))
                            return true
                        } else {
                            val innerDoc = app.get(innerIframeSrc, referer = decodedUrl).document
                            val innerVideoTag = innerDoc.selectFirst("video source[src], video[src]")
                            if (innerVideoTag != null) {
                                val vurl = innerVideoTag.attr("src")
                                android.util.Log.d("An1me_Video", "Found video in inner iframe: $vurl")
                                callback(
                                    createLink(
                                        sourceName = name,
                                        linkName = name,
                                        url = vurl,
                                        referer = innerIframeSrc,
                                        quality = Qualities.Unknown.value,
                                        type = if (vurl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    )
                                )
                                return true
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Error following decoded URL: ${e.message}", e)
                    // fallthrough to failure return below
                }
            }

            android.util.Log.d("An1me_Video", "No valid video link found in non-WeTransfer flow.")
            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error loading links: ${e.message}", e)
            return false
        }
    }
}
