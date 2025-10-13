package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*
import org.jsoup.Jsoup
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "kr"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = doc.select("div.item").mapNotNull { el ->
            val title = el.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val href = fixUrl(el.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse("Latest", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return res.select("div.item").mapNotNull {
            val title = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = fixUrlNull(it.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = fixUrlNull(doc.selectFirst("img.cover")?.attr("src"))
        val episodes = doc.select("ul.episodes a").map {
            val epTitle = it.text()
            val epUrl = fixUrl(it.attr("href"))
            newEpisode(epUrl) { name = epTitle }
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.episodes = mapOf(DubStatus.Subbed to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("An1me_Video", "Iframe src: $data")

        val iframeUrl = data
        val iframeDoc = app.get(iframeUrl).document
        val script = iframeDoc.selectFirst("script:containsData(sources)")?.data() ?: ""
        val match = Regex("https:\\\\/\\\\/download\\.wetransfer\\.com\\\\/[^\\\"]+\\.mp4[^\\\"]*")
            .find(script)?.value
            ?.replace("\\/", "/")

        if (match != null) {
            Log.d("An1me_Video", "Extracted MP4: $match")
            callback.invoke(
                ExtractorLink(
                    source = "An1me",
                    name = "WeTransfer",
                    url = match,
                    referer = iframeUrl,
                    quality = Qualities.P1080.value,
                    isM3u8 = false
                )
            )
            return true
        }

        Log.d("An1me_Video", "No MP4 found in iframe script")
        return false
    }

    private suspend fun fetchAnilistMetadata(title: String): String? {
        val query = """
            {"query": "query { Media(search: \"$title\", type: ANIME) { title { romaji english native } description coverImage { large } } }"}
        """.trimIndent()

        val body = query.toRequestBody("application/json".toMediaType())

        val response = try {
            app.post(
                url = "https://graphql.anilist.co",
                requestBody = body,
                headers = mapOf("Content-Type" to "application/json")
            )
        } catch (e: Exception) {
            Log.e("An1me_Anilist", "Anilist request failed: ${e.message}")
            null
        }

        return response?.text
    }
}
