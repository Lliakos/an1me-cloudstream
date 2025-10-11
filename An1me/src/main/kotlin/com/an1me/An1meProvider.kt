package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.util.Base64

class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var hasMainPage = true
    override var lang = "en"
    override var supportedTypes = setOf(TvType.Anime)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=${query.replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("div.film-list div.flw-item").mapNotNull {
            val title = it.selectFirst(".film-detail .film-name a")?.text() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst(".film-detail .film-name a")?.attr("href") ?: return@mapNotNull null)
            val poster = it.selectFirst("img")?.attr("data-src")
            newAnimeSearchResponse(title, href, TvType.Anime) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h2")?.text() ?: "Unknown"
        val poster = doc.selectFirst(".film-poster-img")?.attr("data-src")
        val description = doc.selectFirst(".description, .film-description")?.text()

        val episodes = doc.select("div#episode_related li a").mapNotNull {
            val epHref = fixUrl(it.attr("href"))
            val epName = it.text()
            newEpisode(epHref) {
                name = epName
            }
        }

        // ✅ no reassignment — all inside builder
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
        try {
            val document: Document = app.get(data).document
            val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
                ?: return false.also { android.util.Log.d("An1me_Video", "No iframe found") }

            android.util.Log.d("An1me_Video", "Iframe src: $iframeSrc")

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) return false.also {
                android.util.Log.d("An1me_Video", "No base64 part found")
            }

            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            android.util.Log.d("An1me_Video", "Decoded URL: $decodedUrl")

            val videoUrl = decodedUrl

            // 🟢 Handle direct .m3u8
            if (videoUrl.contains(".m3u8")) {
                val m3u8Text = app.get(videoUrl).text
                val lines = m3u8Text.lines()

                var currentQuality = Qualities.Unknown.value

                lines.forEachIndexed { i, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val resMatch = """RESOLUTION=\d+x(\d+)""".toRegex().find(line)
                        val height = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        currentQuality = when (height) {
                            2160 -> Qualities.P2160.value
                            1440 -> Qualities.P1440.value
                            1080 -> Qualities.P1080.value
                            720 -> Qualities.P720.value
                            480 -> Qualities.P480.value
                            360 -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }

                        if (i + 1 < lines.size) {
                            val nextUrl = lines[i + 1]
                            if (!nextUrl.startsWith("#")) {
                                val fullUrl = if (nextUrl.startsWith("http")) nextUrl else {
                                    val base = videoUrl.substringBeforeLast("/")
                                    "$base/$nextUrl"
                                }

                                val safeUrl = fullUrl
                                    .replace(" ", "%20")
                                    .replace("[", "%5B")
                                    .replace("]", "%5D")

                                callback(
                                    ExtractorLink(
                                        this.name,
                                        "${this.name} ${height}p",
                                        safeUrl,
                                        "",
                                        quality = currentQuality,
                                        isM3u8 = true
                                    )
                                )
                            }
                        }
                    }
                }

                // if playlist is simple, just use it directly
                if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
                    val safeUrl = videoUrl
                        .replace(" ", "%20")
                        .replace("[", "%5B")
                        .replace("]", "%5D")
                    callback(
                        ExtractorLink(
                            this.name,
                            this.name,
                            safeUrl,
                            "",
                            isM3u8 = true
                        )
                    )
                }

                return true
            }

            // 🟡 Handle Google Photos embeds
            if (videoUrl.contains("photos.google.com")) {
                val photoDoc = app.get(videoUrl, referer = mainUrl).document
                val directVideo =
                    photoDoc.selectFirst("meta[property=og:video]")?.attr("content")
                        ?: photoDoc.selectFirst("video")?.attr("src")

                if (!directVideo.isNullOrEmpty()) {
                    callback(
                        ExtractorLink(
                            this.name,
                            "${this.name} GoogleVideo",
                            directVideo,
                            "",
                            isM3u8 = false
                        )
                    )
                    return true
                }
            }

            android.util.Log.d("An1me_Video", "Unsupported video format: $videoUrl")
            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error: ${e.message}", e)
            return false
        }
    }
}
