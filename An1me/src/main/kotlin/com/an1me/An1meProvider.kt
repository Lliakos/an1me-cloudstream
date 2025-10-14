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
        return ExtractorLink(
            source = sourceName,
            name = linkName,
            url = url,
            referer = referer,
            quality = quality,
            type = type
        )
    }

    // ===============================
    // 🔸 SPOTLIGHT / TRENDING PARSERS
    // ===============================

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null

        val title = this.selectFirst("span[data-nt-title]")?.text()
            ?: this.selectFirst("span[data-en-title]")?.text()
            ?: link.attr("title")
            ?: this.selectFirst("img")?.attr("alt")
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("span[data-nt-title]")?.text()
            ?: this.selectFirst("span[data-en-title]")?.text()
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("span[data-nt-title]")?.text()
            ?: this.selectFirst("span[data-en-title]")?.text()
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ==========================
    // 🔸 HOME PAGE (SPOTLIGHT FIX)
    // ==========================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // 🟡 Spotlight
        try {
            val spotlightItems = document.select(".swiper-spotlight .swiper-slide").mapNotNull {
                val link = it.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
                val href = fixUrl(link.attr("href"))
                val title = it.selectFirst("span[data-nt-title]")?.text()
                    ?: it.selectFirst("span[data-en-title]")?.text()
                    ?: return@mapNotNull null
                val image = it.selectFirst("img")?.attr("src")
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = fixUrlNull(image)
                }
            }
            if (spotlightItems.isNotEmpty()) {
                homePages.add(0, HomePageList("Spotlight", spotlightItems, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Spotlight error: ${e.message}")
        }

        // 🟢 Trending
        try {
            val trendingItems = document.select(".swiper-trending .swiper-slide").mapNotNull {
                it.toTrendingResult()
            }
            if (trendingItems.isNotEmpty()) {
                homePages.add(HomePageList("Τάσεις", trendingItems))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Trending error: ${e.message}")
        }

        // 🟢 Latest Episodes
        try {
            val latestEpisodesSection = document.selectFirst("section:has(h2:contains(Καινούργια Επεισόδια))")
            val latestEpisodeItems = latestEpisodesSection?.select(".kira-grid-listing > div")?.mapNotNull {
                it.toLatestEpisodeResult()
            } ?: emptyList()
            if (latestEpisodeItems.isNotEmpty()) {
                homePages.add(HomePageList("Καινούργια Επεισόδια", latestEpisodeItems))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Latest Episodes error: ${e.message}")
        }

        // 🟢 Latest Anime
        try {
            val items = document.select("li").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePages.add(HomePageList("Καινούργια Anime", items))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Latest Anime error: ${e.message}")
        }

        return HomePageResponse(homePages)
    }

    // ======================
    // 🔸 SEARCH (unchanged)
    // ======================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/?s_keyword=$query").document
        return document.select("#first_load_result > div").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = item.selectFirst("span[data-nt-title]")?.text()
                ?: item.selectFirst("span[data-en-title]")?.text()
                ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ==========================
    // 🔸 LOAD (EPISODES FIXED)
    // ==========================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val titleRomaji = document.selectFirst("span[data-nt-title]")?.text()
        val titleEnglish = document.selectFirst("span[data-en-title]")?.text()
        val title = titleRomaji ?: titleEnglish ?: document.selectFirst("h1")?.text() ?: "Unknown"

        val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div[data-synopsis]")?.text()
        val tags = document.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text() }

        val episodes = mutableListOf<Episode>()

        // ✅ Directly scrape episodes
        try {
            val episodeLinks = document.select("a[href*='/watch/']")
                .filter { it.attr("href").contains("-episode-") }

            for (el in episodeLinks) {
                val href = fixUrl(el.attr("href"))
                val epText = el.text().trim()
                val epNum = epText.filter { it.isDigit() }.toIntOrNull() ?: episodes.size + 1
                val titleText = "Episode $epNum"
                episodes.add(newEpisode(href) {
                    this.name = titleText
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }

            episodes.sortBy { it.episode }
            android.util.Log.d("An1me_Episodes", "Loaded ${episodes.size} episodes from page")
        } catch (e: Exception) {
            android.util.Log.e("An1me_Episodes", "Error parsing episodes: ${e.message}")
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ============================
    // 🔸 VIDEO LINK RESOLUTION
    // ============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            val iframeSrc = document.selectFirst("iframe[src*='kr-video']")?.attr("src")
                ?: return false

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))

            // Handle M3U8
            if (decodedUrl.contains(".m3u8")) {
                callback(
                    createLink(
                        sourceName = name,
                        linkName = "Main Source",
                        url = decodedUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                return true
            }

            // Handle MP4
            if (decodedUrl.contains(".mp4")) {
                callback(
                    createLink(
                        sourceName = name,
                        linkName = "Direct MP4",
                        url = decodedUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }

            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "Error loading links: ${e.message}")
            return false
        }
    }
}
