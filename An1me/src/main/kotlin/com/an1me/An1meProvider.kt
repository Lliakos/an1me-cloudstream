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

            // Handle M3U8 files
            if (decodedUrl.contains(".m3u8")) {
                return handleM3u8(decodedUrl, callback)
            }

            // Handle Google Photos videos
            if (decodedUrl.contains("photos.google.com")) {
                android.util.Log.d("An1me_Video", "Detected Google Photos link, fetching MP4...")

                val photoPage = app.get(decodedUrl, referer = mainUrl).text
                val regex = Regex("https://video\\.googleusercontent\\.com/[^\"']+")
                val videoMatch = regex.find(photoPage)?.value

                if (videoMatch != null) {
                    android.util.Log.d("An1me_Video", "Found Google Photos MP4: $videoMatch")
                    callback.invoke(
                        createLink(
                            sourceName = name,
                            linkName = "$name (Google Photos)",
                            url = videoMatch,
                            referer = decodedUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.Other
                        )
                    )
                    return true
                } else {
                    android.util.Log.d("An1me_Video", "No MP4 found in Google Photos page")
                }
            }

            // Handle WeTransfer links (more robust extraction)
            if (decodedUrl.contains("wetransfer.com") || decodedUrl.contains("collect.wetransfer.com")) {
                android.util.Log.d("An1me_Video", "Detected WeTransfer link, attempting robust extraction...")

                val weTransferPage = app.get(decodedUrl, referer = mainUrl).text

                // 1) Search for typical direct MP4 URLs
                val directMp4Regex = Regex("https?://[^\"'\\s>]+\\.(mp4|m4v)(\\?[^\"'\\s>]*)?", RegexOption.IGNORE_CASE)
                val directMatch = directMp4Regex.find(weTransferPage)?.value
                if (directMatch != null) {
                    android.util.Log.d("An1me_Video", "Found direct MP4 via simple regex: $directMatch")
                    callback.invoke(
                        createLink(
                            sourceName = name,
                            linkName = "$name (WeTransfer)",
                            url = directMatch,
                            referer = decodedUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.Other
                        )
                    )
                    return true
                }

                // 2) Look for JSON-like url fields (escaped or unescaped). Example: "url":"https://...mp4" or url":"https:\/\/...mp4
                val jsonUrlRegex = Regex("""["']url["']\s*:\s*["'](https?:\\?/\\?/[^"']+?\.(mp4|m4v)[^"']*)["']""", RegexOption.IGNORE_CASE)
                val jsonMatch = jsonUrlRegex.find(weTransferPage)?.groups?.get(1)?.value
                val unescapedJsonMatch = jsonMatch?.replace("\\/", "/")
                if (unescapedJsonMatch != null) {
                    android.util.Log.d("An1me_Video", "Found MP4 in JSON-like content: $unescapedJsonMatch")
                    callback.invoke(
                        createLink(
                            sourceName = name,
                            linkName = "$name (WeTransfer JSON)",
                            url = unescapedJsonMatch,
                            referer = decodedUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.Other
                        )
                    )
                    return true
                }

                // 3) Another JSON pattern without escaping
                val jsonUrlRegex2 = Regex("url\\s*[:=]\\s*\"(https?://[^\"]+\\.(mp4|m4v)[^\"]*)\"", RegexOption.IGNORE_CASE)
                val jsonMatch2 = jsonUrlRegex2.find(weTransferPage)?.groups?.get(1)?.value
                if (jsonMatch2 != null) {
                    android.util.Log.d("An1me_Video", "Found MP4 in JSON-like content (2): $jsonMatch2")
                    callback.invoke(
                        createLink(
                            sourceName = name,
                            linkName = "$name (WeTransfer JSON2)",
                            url = jsonMatch2,
                            referer = decodedUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.Other
                        )
                    )
                    return true
                }

                // 4) Look for og:video meta
                val metaOgVideo = Regex("<meta[^>]+property=[\"']og:video[\"'][^>]+content=[\"']([^\"']+\\.(mp4|m4v)[^\"']*)[\"'][^>]*>", RegexOption.IGNORE_CASE)
                    .find(weTransferPage)?.groups?.get(1)?.value
                if (metaOgVideo != null) {
                    android.util.Log.d("An1me_Video", "Found MP4 in og:video meta: $metaOgVideo")
                    callback.invoke(
                        createLink(
                            sourceName = name,
                            linkName = "$name (WeTransfer og:video)",
                            url = metaOgVideo,
                            referer = decodedUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.Other
                        )
                    )
                    return true
                }

                // 5) Look for <video> or <source> tags
                val doc = org.jsoup.Jsoup.parse(weTransferPage)
                val videoSrc = doc.selectFirst("video[src]")?.attr("src")
                    ?: doc.selectFirst("video source[src]")?.attr("src")
                    ?: doc.selectFirst("source[src]")?.attr("src")
                if (!videoSrc.isNullOrEmpty()) {
                    val resolved = if (videoSrc.startsWith("http")) videoSrc else resolveRelativeUrl(decodedUrl, videoSrc)
                    android.util.Log.d("An1me_Video", "Found MP4 in video/source tag: $resolved")
                    callback.invoke(
                        createLink(
                            sourceName = name,
                            linkName = "$name (WeTransfer video tag)",
                            url = resolved,
                            referer = decodedUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.Other
                        )
                    )
                    return true
                }

                // 6) Try an unescaped JSON search for https://...mp4 where quotes might be escaped differently
                val relaxedJsonRegex = Regex("""(https?:\\?/\\?/[^"'\s>]+?\.(mp4|m4v)(\?[^"'\s>]*)?)""", RegexOption.IGNORE_CASE)
                val relaxedMatch = relaxedJsonRegex.find(weTransferPage)?.value?.replace("\\/", "/")
                if (!relaxedMatch.isNullOrEmpty()) {
                    android.util.Log.d("An1me_Video", "Found MP4 via relaxed regex: $relaxedMatch")
                    callback.invoke(
                        createLink(
                            sourceName = name,
                            linkName = "$name (WeTransfer relaxed)",
                            url = relaxedMatch,
                            referer = decodedUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.Other
                        )
                    )
                    return true
                }

                android.util.Log.d("An1me_Video", "No MP4 found in WeTransfer page after multiple strategies")
            }

            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}", e)
            return false
        }
    }

    private suspend fun handleM3u8(videoUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val m3u8Response = app.get(videoUrl).text
        val lines = m3u8Response.lines()
        var currentQuality = Qualities.Unknown.value
        var currentName = "Unknown"

        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
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

                if (index + 1 < lines.size) {
                    var urlLine = lines[index + 1]
                    if (!urlLine.startsWith("#")) {
                        val fullUrl = if (urlLine.startsWith("http")) urlLine else {
                            val baseUrl = videoUrl.substringBeforeLast("/")
                            "$baseUrl/$urlLine"
                        }

                        val safeUrl = fullUrl.replace(" ", "%20")
                            .replace("[", "%5B")
                            .replace("]", "%5D")

                        callback.invoke(
                            createLink(
                                sourceName = name,
                                linkName = "$name $currentName",
                                url = safeUrl,
                                referer = mainUrl,
                                quality = currentQuality,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                }
            }
        }

        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
            val safeUrl = videoUrl.replace(" ", "%20")
                .replace("[", "%5B")
                .replace("]", "%5D")

            callback.invoke(
                createLink(
                    sourceName = name,
                    linkName = name,
                    url = safeUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }

        return true
    }

    // Resolve relative URLs against a base (simple)
    private fun resolveRelativeUrl(base: String, relative: String): String {
        return if (relative.startsWith("/")) {
            val baseRoot = base.substringBefore("//") + "//" + base.substringAfter("//").substringBefore("/")
            baseRoot + relative
        } else {
            val baseDir = base.substringBeforeLast("/")
            "$baseDir/$relative"
        }
    }
}
