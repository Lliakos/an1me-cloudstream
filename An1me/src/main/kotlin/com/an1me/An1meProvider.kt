package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.util.Base64
import java.util.Date
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log

@Suppress("DEPRECATION")
class An1meProvider : MainAPI() {
    override var mainUrl = "https://an1me.to"
    override var name = "An1me"
    override var lang = "gr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // ------------------------------
    // Cache settings
    // ------------------------------
    // Persistent cache file: external storage path (human-readable JSON)
    // NOTE: On some newer Androids external write requires permissions; this path is standard for many users.
    private val cacheFilePath = "/storage/emulated/0/Android/data/com.lagradost.cloudstream3/files/an1me_cache.json"
    private val cacheExpiryMillis = TimeUnit.DAYS.toMillis(7) // 7 days before refresh

    // In-memory cache mirror for fast access
    private val anilistCache = mutableMapOf<String, JSONObject>() // key = normalized title
    private val anilistCacheTimestamps = mutableMapOf<String, Long>()

    // Utility: persist in-memory cache to disk (atomic write)
    private fun persistCacheToDisk() {
        try {
            val file = File(cacheFilePath)
            file.parentFile?.mkdirs()
            val root = JSONObject()
            for ((k, v) in anilistCache) {
                val entry = JSONObject()
                entry.put("data", v)
                entry.put("ts", anilistCacheTimestamps[k] ?: System.currentTimeMillis())
                root.put(k, entry)
            }
            FileOutputStream(file).use { out ->
                out.write(root.toString(2).toByteArray(Charset.forName("utf-8")))
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_Cache", "Failed to persist cache: ${e.message}")
        }
    }

    // Utility: load cache from disk into memory
    private fun loadCacheFromDisk() {
        try {
            val file = File(cacheFilePath)
            if (!file.exists()) return
            val text = FileInputStream(file).use { it.readBytes() }.toString(Charset.forName("utf-8"))
            val root = JSONObject(text)
            root.keys().forEach { k ->
                try {
                    val entry = root.optJSONObject(k) ?: return@forEach
                    val data = entry.optJSONObject("data") ?: return@forEach
                    val ts = entry.optLong("ts", System.currentTimeMillis())
                    anilistCache[k] = data
                    anilistCacheTimestamps[k] = ts
                } catch (_: Exception) { /* skip malformed */ }
            }
        } catch (e: Exception) {
            android.util.Log.e("An1me_Cache", "Failed to read cache: ${e.message}")
        }
    }

    // Normalize title for cache key (lowercase, trimmed, ascii-ish)
    private fun normalizeTitleForKey(title: String?): String {
        if (title == null) return ""
        return title.trim().lowercase()
    }

    // Attempt to fetch AniList metadata, using persistent cache when possible
    // Returns JSONObject (AniList Media object) or null
    private suspend fun getAniListMediaForTitle(title: String?): JSONObject? {
        if (title.isNullOrBlank()) return null
        // load cache if empty
        if (anilistCache.isEmpty()) loadCacheFromDisk()

        val key = normalizeTitleForKey(title)
        val now = System.currentTimeMillis()
        val cached = anilistCache[key]
        val ts = anilistCacheTimestamps[key] ?: 0L
        if (cached != null && (now - ts) < cacheExpiryMillis) {
            return cached
        }

        // Build GraphQL query (search by title)
        val gql = """
            query (\$search: String) {
              Media(search: \$search, type: ANIME) {
                id
                title { romaji english native }
                description(asHtml: false)
                coverImage { extraLarge large medium }
                bannerImage
                averageScore
                genres
                status
                episodes
                duration
                startDate { year month day }
                trailer { id site thumbnail }
                studios { nodes { name } }
              }
            }
        """.trimIndent()

        val variables = JSONObject().put("search", title)
        val bodyJson = JSONObject().put("query", gql).put("variables", variables)

        try {
            // Cloudstream's app.post wants requestBody as okhttp3.RequestBody
            val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())

            val response = try {
                app.post(
                    url = "https://graphql.anilist.co",
                    requestBody = requestBody,
                    headers = mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                android.util.Log.e("An1me_Anilist", "Network error when calling AniList: ${e.message}")
                null
            }

            if (response == null) return null
            val parsed = try {
                JSONObject(response.text)
            } catch (e: Exception) {
                android.util.Log.e("An1me_Anilist", "Failed to parse AniList response: ${e.message}")
                return null
            }

            val media = parsed.optJSONObject("data")?.optJSONObject("Media") ?: return null

            // Save to cache
            try {
                anilistCache[key] = media
                anilistCacheTimestamps[key] = now
                persistCacheToDisk()
            } catch (e: Exception) {
                android.util.Log.e("An1me_Cache", "Failed to cache AniList result: ${e.message}")
            }

            return media
        } catch (e: Exception) {
            android.util.Log.e("An1me_Anilist", "Error preparing AniList request: ${e.message}")
            return null
        }
    }

    // ------------------------------
    // Helpers for UI / parsing
    // ------------------------------
    private fun ensureHttps(url: String?): String? {
        if (url == null) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            else -> url
        }
    }

    private fun Element.selectHref(selector: String): String? {
        return try {
            this.selectFirst(selector)?.attr("href")
        } catch (_: Exception) {
            null
        }
    }

    private fun Element.getTextOrNull(selector: String): String? {
        return try {
            this.selectFirst(selector)?.text()
        } catch (_: Exception) {
            null
        }
    }

    // lightweight safe fixUrlNull wrapper
    private fun safeFixUrlNull(url: String?): String? = fixUrlNull(url)

    // ------------------------------
    // Search / home parsing helpers
    // ------------------------------
    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/watch/")) return null

        val title =
            this.selectFirst("span[data-en-title]")?.text()
                ?: this.selectFirst("span[data-nt-title]")?.text()
                ?: link.attr("title")
                ?: this.selectFirst("img")?.attr("alt")
                ?: return null

        val posterUrl = safeFixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSpotlightResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text()
            ?: link.attr("title")
            ?: return null

        val banner = this.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = safeFixUrlNull(banner)
        }
    }

    private fun Element.toTrendingResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text()
            ?: link.attr("title")
            ?: return null

        val poster = safeFixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun Element.toLatestEpisodeResult(): AnimeSearchResponse? {
        val link = this.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span[data-en-title]")?.text()
            ?: this.selectFirst("span[data-nt-title]")?.text()
            ?: link.attr("title")
            ?: return null

        val poster = safeFixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ------------------------------
    // Main page
    // ------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val lists = mutableListOf<HomePageList>()

        try {
            val spotlightSelectors = listOf(
                ".spotlight .swiper-slide",
                ".home-spotlight .swiper-slide",
                ".featured .swiper-slide",
                ".hero .swiper-slide"
            )
            val spotlight = spotlightSelectors.flatMap { doc.select(it).toList() }.distinct().mapNotNull { it.toSpotlightResult() }
            if (spotlight.isNotEmpty()) lists.add(HomePageList("Featured", spotlight, isHorizontalImages = true))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Spotlight parse error: ${e.message}")
        }

        try {
            val trendingItems = doc.select(".swiper-trending .swiper-slide, .trending .swiper-slide").mapNotNull { it.toTrendingResult() }
            if (trendingItems.isNotEmpty()) lists.add(HomePageList("Trending", trendingItems))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Trending parse error: ${e.message}")
        }

        try {
            val latestNodes = doc.select("section:has(h2:contains(Καινούργια Επεισόδια)) .kira-grid-listing > div")
            val latest = latestNodes.mapNotNull { it.toLatestEpisodeResult() }
            if (latest.isNotEmpty()) lists.add(HomePageList("New Episodes", latest))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Latest episodes parse error: ${e.message}")
        }

        try {
            val newest = doc.select("li").mapNotNull { it.toSearchResult() }
            if (newest.isNotEmpty()) lists.add(HomePageList("New Anime", newest))
        } catch (e: Exception) {
            android.util.Log.e("An1me_MainPage", "Latest anime parse error: ${e.message}")
        }

        return HomePageResponse(lists)
    }

    // ------------------------------
    // Search
    // ------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?s_keyword=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl).document
        return doc.select("#first_load_result > div").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = item.selectFirst("span[data-en-title]")?.text() ?: item.selectFirst("span[data-nt-title]")?.text() ?: return@mapNotNull null
            val poster = safeFixUrlNull(item.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
    }

    // ------------------------------
    // Episode parsing helpers
    // ------------------------------
    private fun parseEpisodesFromScripts(doc: Document): List<String> {
        val out = mutableListOf<String>()
        try {
            val pat = Pattern.compile("(?:\"|')(/watch/[^\"'\\s]+)(?:\"|')", Pattern.CASE_INSENSITIVE)
            val scripts = doc.select("script")
            for (s in scripts) {
                val txt = s.data()
                val m = pat.matcher(txt)
                while (m.find()) {
                    val href = fixUrl(m.group(1))
                    if (href.isNotBlank()) out.add(href)
                }
            }
        } catch (_: Exception) { }
        return out.distinct()
    }

    private fun buildWatchCandidatesFromSlug(animeUrl: String, index: Int): String {
        try {
            val slug = animeUrl.substringAfter("/anime/").substringBefore("/").takeIf { it.isNotBlank() } ?: animeUrl.substringAfterLast("/")
            val c1 = "$mainUrl/watch/$slug/$index"
            val c2 = "$mainUrl/watch/$slug-episode-$index"
            val c3 = "$mainUrl/watch/$slug-ep-$index"
            return listOf(c1, c2, c3, "$animeUrl#ep$index").first()
        } catch (_: Exception) {
            return "$animeUrl#ep$index"
        }
    }

    // ------------------------------
    // Load (details + episodes + AniList enrichment + caching)
    // ------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // pick a solid title for AniList search
        val pageTitle = doc.selectFirst("span[data-en-title]")?.text()
            ?: doc.selectFirst("span[data-nt-title]")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1.entry-title, h1")?.text()
            ?: "Unknown"

        // local site poster/banner
        val sitePoster = safeFixUrlNull(doc.selectFirst("img")?.attr("src"))
        val siteBanner = doc.selectFirst("img[src*='anilist.co/file/anilistcdn/media/anime/banner']")?.attr("src")

        // request AniList media (cached)
        val aniMedia = getAniListMediaForTitle(pageTitle)

        // defaults
        var aniBanner: String? = null
        var aniCover: String? = null
        var aniEpisodesCount: Int? = null
        var aniDescription: String? = null
        var aniTrailerUrl: String? = null
        var aniStudio: String? = null
        val aniGenres = mutableListOf<String>()

        aniMedia?.let { media ->
            aniBanner = media.optString("bannerImage", null)
            val coverObj = media.optJSONObject("coverImage")
            aniCover = coverObj?.optString("extraLarge") ?: coverObj?.optString("large") ?: coverObj?.optString("medium")
            aniEpisodesCount = if (media.has("episodes")) media.optInt("episodes", -1).takeIf { it > 0 } else null
            aniDescription = media.optString("description", null)
            media.optJSONArray("genres")?.let { ga ->
                for (i in 0 until ga.length()) aniGenres.add(ga.getString(i))
            }
            val trailer = media.optJSONObject("trailer")
            if (trailer != null) {
                val site = trailer.optString("site", "")
                val id = trailer.optString("id", "")
                if (site.equals("youtube", true) && id.isNotBlank()) {
                    aniTrailerUrl = "https://youtu.be/$id"
                } else {
                    aniTrailerUrl = trailer.optString("thumbnail", null)
                }
            }
            val studios = media.optJSONObject("studios")
            aniStudio = studios?.optJSONArray("nodes")?.optJSONObject(0)?.optString("name", null)
        }

        val finalBanner = ensureHttps(aniBanner ?: siteBanner ?: sitePoster)
        val finalCover = ensureHttps(aniCover ?: sitePoster)

        // description: prefer aniList description (if available), else site description
        val siteDescription = doc.selectFirst("div[data-synopsis]")?.text()
        val finalDescription = (aniDescription ?: siteDescription) ?: ""

        // collect tags (mix site tags + AniList genres)
        val siteTags = doc.select("li:has(span:containsOwn(Είδος:)) a[href*='/genre/']").map { it.text().trim() }
        val tags = (siteTags + aniGenres).distinct()

        // episodes
        val episodes = mutableListOf<Episode>()
        try {
            // 1) episode-list-display-box likely contains full list
            val listBox = doc.selectFirst("div.episode-list-display-box")
            if (listBox != null) {
                listBox.select("a.episode-list-item[href*='/watch/']").forEach { el ->
                    val epHref = fixUrl(el.attr("href"))
                    if (epHref.isNullOrBlank() || epHref.contains("/anime/")) return@forEach
                    val numText = el.selectFirst(".episode-list-item-number")?.text()
                    val epNum = numText?.trim()?.toIntOrNull() ?: (episodes.size + 1)
                    val epTitle = el.selectFirst(".episode-list-item-title")?.text()?.trim() ?: "Episode $epNum"
                    episodes.add(newEpisode(epHref) {
                        name = epTitle
                        episode = epNum
                        posterUrl = finalCover
                    })
                }
            }

            // 2) swiper fallback
            if (episodes.isEmpty()) {
                doc.select("div.swiper-slide a[href*='/watch/']").forEach { el ->
                    val h = fixUrl(el.attr("href"))
                    if (h.isNullOrBlank()) return@forEach
                    val titleAttr = el.attr("title")
                    val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(titleAttr)?.groupValues?.get(1)?.toIntOrNull()
                    val epTitle = titleAttr.ifBlank { "Episode ${epNum ?: episodes.size + 1}" }
                    episodes.add(newEpisode(h) {
                        name = epTitle
                        episode = epNum ?: episodes.size + 1
                        posterUrl = finalCover
                    })
                }
            }

            // 3) script-derived fallback
            if (episodes.isEmpty()) {
                val scriptEpisodes = parseEpisodesFromScripts(doc)
                scriptEpisodes.forEachIndexed { idx, href ->
                    val fixed = fixUrl(href)
                    val num = idx + 1
                    episodes.add(newEpisode(fixed) {
                        name = "Episode $num"
                        episode = num
                        posterUrl = finalCover
                    })
                }
            }

            // 4) If AniList reports more episodes than page lists, generate placeholders up to AniList count
            if (aniEpisodesCount != null && aniEpisodesCount > episodes.size) {
                val existingNumbers = episodes.mapNotNull { it.episode }.toSet()
                for (i in 1..aniEpisodesCount) {
                    if (existingNumbers.contains(i)) continue
                    val genUrl = buildWatchCandidatesFromSlug(url, i)
                    episodes.add(newEpisode(genUrl) {
                        name = "Episode $i"
                        episode = i
                        posterUrl = finalCover
                    })
                }
            }

            // dedupe & sort
            val unique = episodes.distinctBy { it.episode ?: -1 }.sortedBy { it.episode ?: Int.MAX_VALUE }
            episodes.clear()
            episodes.addAll(unique)
        } catch (e: Exception) {
            android.util.Log.e("An1me_EpisodeCollect", "Error collecting episodes: ${e.message}")
        }

        // enhanced description includes trailer link if present
        val enhancedDescription = buildString {
            append(finalDescription)
            if (aniTrailerUrl != null) {
                append("\n\nTrailer: $aniTrailerUrl")
            }
            if (aniStudio != null) {
                append("\nStudio: $aniStudio")
            }
        }

        // Build load response
        return newAnimeLoadResponse(pageTitle, url, TvType.Anime) {
            posterUrl = fixUrlNull(finalBanner ?: finalCover)
            plot = enhancedDescription
            tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ------------------------------
    // loadLinks: extract playable links (WeTransfer, Google Photos, M3U8, MP4)
    // ------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data).document
            val iframeSrc = doc.selectFirst("iframe[src*='kr-video']")?.attr("src") ?: return false
            android.util.Log.d("An1me_Video", "Iframe src: $iframeSrc")

            val base64Part = iframeSrc.substringAfter("/kr-video/").substringBefore("?")
            if (base64Part.isEmpty()) return false.also { android.util.Log.d("An1me_Video", "No base64 part found") }
            val decodedUrl = String(Base64.getDecoder().decode(base64Part))
            android.util.Log.d("An1me_Video", "Decoded URL: $decodedUrl")

            // WeTransfer handling
            if (decodedUrl.contains("wetransfer.com", true) || decodedUrl.contains("collect.wetransfer.com", true)) {
                android.util.Log.d("An1me_Video", "Detected WeTransfer - attempting extraction")
                try {
                    val iframeHtml = app.get(iframeSrc).text
                    val cleaned = iframeHtml
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("\\u003c", "<")
                        .replace("\\u003e", ">")
                        .replace("\\\\", "\\")
                        .replace("\\/", "/")

                    val jsonMatch = Regex("""(?:const|var|let)\s+params\s*=\s*(\{.*?"sources".*?\});""", RegexOption.DOT_MATCHES_ALL)
                        .find(cleaned)?.groupValues?.get(1)
                    if (jsonMatch != null) {
                        val jo = JSONObject(jsonMatch)
                        val sources = jo.optJSONArray("sources")
                        if (sources != null && sources.length() > 0) {
                            for (i in 0 until sources.length()) {
                                val srcObj = sources.getJSONObject(i)
                                val url = srcObj.optString("url", "").replace("\\/", "/")
                                if (url.isNotBlank()) {
                                    callback(createLink(name, "$name (WeTransfer)", url, iframeSrc, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                                }
                            }
                            return true
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "WeTransfer extraction failed: ${e.message}")
                }
            }

            // Google Photos
            if (decodedUrl.contains("photos.google.com", true)) {
                try {
                    val photoHtml = app.get(decodedUrl, referer = iframeSrc).text
                    val videoRegex = Regex("""(https:\/\/[^"'\s]+googleusercontent\.com[^"'\s]+)""")
                    val found = videoRegex.find(photoHtml)?.value
                    if (found != null) {
                        val base = found.replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/")
                        val baseUrl = base.substringBefore("=m").substringBefore("?")
                        val variants = listOf("=m37" to Qualities.P1080.value, "=m22" to Qualities.P720.value, "=m18" to Qualities.P480.value)
                        for ((param, q) in variants) {
                            callback(createLink(name, "$name (Google Photos) ${param}", "$baseUrl$param", decodedUrl, q, ExtractorLinkType.VIDEO))
                        }
                        return true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("An1me_Video", "Google Photos extraction failed: ${e.message}")
                }
            }

            // M3U8
            if (decodedUrl.contains(".m3u8", true)) {
                android.util.Log.d("An1me_Video", "M3U8 detected - returning as m3u8")
                callback(createLink(name, "$name (M3U8)", decodedUrl, data, Qualities.Unknown.value, ExtractorLinkType.M3U8))
                return true
            }

            // MP4
            if (decodedUrl.contains(".mp4", true)) {
                android.util.Log.d("An1me_Video", "MP4 detected - returning mp4")
                callback(createLink(name, "$name (MP4)", decodedUrl, data, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                return true
            }

            android.util.Log.d("An1me_Video", "No playable link found for decodedUrl: $decodedUrl")
            return false
        } catch (e: Exception) {
            android.util.Log.e("An1me_Video", "loadLinks error: ${e.message}", e)
            return false
        }
    }
}
