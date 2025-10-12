package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.util.Base64
import java.util.regex.Pattern

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
        return ExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            referer = referer,
            quality = quality,
            isM3u8 = (type == ExtractorLinkType.M3U8)
        )
    }

    private fun ensureHttps(url: String?): String? {
        if (url == null) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            else -> url
        }
    }

    private fun extractEnglishTitleFromDoc(document: Document, el: Element?): String? {
        el?.selectFirst("span[data-en-title]")?.text()?.let { return it }
        document.selectFirst("meta[property=og:title]")?.attr("content")?.let { return it }
        return document.selectFirst("h1.entry-title, h1")?.text()?.trim()
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null
        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: link.attr("title") ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    private fun Element.toSpotlightResult(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: link.attr("title") ?: return null
        val img = selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = fixUrlNull(img) }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: link.attr("title") ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("span[data-en-title]")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: link.attr("title") ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        val spotlight = document.select(".spotlight .swiper-slide, .featured .swiper-slide")
            .mapNotNull { it.toSpotlightResult() }
        if (spotlight.isNotEmpty()) homePages.add(HomePageList("Featured", spotlight, true))

        val trending = document.select(".swiper-trending .swiper-slide")
            .mapNotNull { it.toTrendingResult() }
        if (trending.isNotEmpty()) homePages.add(HomePageList("Trending", trending))

        val latest = document.select("li").mapNotNull { it.toSearchResult() }
        if (latest.isNotEmpty()) homePages.add(HomePageList("Latest Anime", latest))

        return HomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?s_keyword=$query"
        val doc = app.get(url).document
        return doc.select("#first_load_result > div").mapNotNull { it.toSearchResult() }
    }

    private fun parseEpisodesFromScripts(document: Document): List<Pair<String, Element?>> {
        val list = mutableListOf<Pair<String, Element?>>()
        val pattern = Pattern.compile("(?:\"|')(/watch/[^\"'\\s]+)(?:\"|')", Pattern.CASE_INSENSITIVE)
        document.select("script").forEach { s ->
            val matcher = pattern.matcher(s.data())
            while (matcher.find()) {
                val href = fixUrl(matcher.group(1))
                if (href.isNotEmpty()) list.add(Pair(href, null))
            }
        }
        return list.distinctBy { it.first }
    }

    private suspend fun fetchAnilistMetadata(title: String?): Map<String, Any?> {
        if (title.isNullOrBlank()) return emptyMap()
        val queryJson = """
            {"query":"query (\$search:String){Media(search:\$search,type:ANIME){bannerImage coverImage{large} averageScore characters(perPage:6){edges{node{name{full} image{large}}}}}}","variables":{"search":"${title.replace("\"","\\\"")}"}} 
        """.trimIndent()
        val resp = app.post("https://graphql.anilist.co", requestBody = queryJson, headers = mapOf("Content-Type" to "application/json"))
        val data = JSONObject(resp.text).optJSONObject("data")?.optJSONObject("Media") ?: return emptyMap()
        val banner = data.optString("bannerImage", null)
        val cover = data.optJSONObject("coverImage")?.optString("large", null)
        val avg = data.optInt("averageScore", -1)
        val chars = mutableListOf<Map<String, String?>>()
        val edges = data.optJSONObject("characters")?.optJSONArray("edges")
        if (edges != null) {
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                chars.add(mapOf("name" to node.optJSONObject("name")?.optString("full"), "image" to node.optJSONObject("image")?.optString("large")))
            }
        }
        return mapOf("banner" to banner, "cover" to cover, "avg" to avg, "characters" to chars)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = extractEnglishTitleFromDoc(doc, doc.selectFirst("header")) ?: "Unknown"
        val poster = fixUrlNull(doc.selectFirst("img")?.attr("src"))
        val anilist = fetchAnilistMetadata(title)
        val banner = ensureHttps(anilist["banner"] as? String ?: poster)
        val cover = ensureHttps(anilist["cover"] as? String ?: poster)
        val desc = doc.selectFirst("div[data-synopsis]")?.text()
        val episodes = doc.select("a[href*='/watch/']").mapIndexed { i, el ->
            newEpisode(fixUrl(el.attr("href"))) {
                name = el.text().ifBlank { "Episode ${i + 1}" }
                episode = i + 1
                posterUrl = cover
            }
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = banner
            this.plot = desc
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(data).document
            val iframe = doc.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false
            val base64 = iframe.substringAfter("/kr-video/").substringBefore("?")
            if (base64.isBlank()) return false
            val decoded = String(Base64.getDecoder().decode(base64))
            if (decoded.endsWith(".m3u8")) {
                callback(createLink(name, "M3U8", decoded, data, Qualities.P1080.value))
                true
            } else if (decoded.endsWith(".mp4")) {
                callback(createLink(name, "MP4", decoded, data, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
