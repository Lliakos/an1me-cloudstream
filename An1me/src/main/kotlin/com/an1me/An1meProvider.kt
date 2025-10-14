package com.an1me

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

class An1meProvider : MainAPI() {
    override var name = "An1me"
    override var mainUrl = "https://an1me.to"
    override var lang = "el"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistCache = ConcurrentHashMap<String, AniListData>()

    data class AniListData(
        val banner: String?,
        val cover: String?,
        val rating: String?,
        val studio: String?,
        val genres: List<String>?,
        val description: String?,
        val characters: List<String>?
    )

    private fun getAniListData(title: String): AniListData? {
        val key = title.lowercase()
        anilistCache[key]?.let { return it }

        return try {
            val query = """
                query (${'$'}search: String) {
                  Media(search: ${'$'}search, type: ANIME) {
                    bannerImage
                    coverImage { large }
                    averageScore
                    description(asHtml: false)
                    genres
                    studios { nodes { name } }
                    characters(perPage: 5) {
                      edges { node { name { full } } }
                    }
                  }
                }
            """.trimIndent()

            val response = khttp.post(
                url = "https://graphql.anilist.co",
                json = mapOf("query" to query, "variables" to mapOf("search" to title))
            )

            val media = response.jsonObject
                .getJSONObject("data")
                .optJSONObject("Media") ?: return null

            val banner = media.optString("bannerImage", null)
            val cover = media.optJSONObject("coverImage")?.optString("large", null)
            val rating = media.optInt("averageScore", 0).toString()
            val description = media.optString("description", null)
            val genres = media.optJSONArray("genres")?.let { arr ->
                List(arr.length()) { arr.getString(it) }
            }
            val studio = media.optJSONObject("studios")
                ?.optJSONArray("nodes")
                ?.optJSONObject(0)
                ?.optString("name", null)
            val characters = media.optJSONObject("characters")
                ?.optJSONArray("edges")
                ?.let { arr ->
                    List(arr.length()) {
                        arr.getJSONObject(it)
                            .getJSONObject("node")
                            .getJSONObject("name")
                            .getString("full")
                    }
                }

            val data = AniListData(banner, cover, rating, studio, genres, description, characters)
            anilistCache[key] = data
            data
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document

        val trending = document.select(".swiper-wrapper a, .kira-grid-listing a").mapNotNull {
            val href = it.attr("href")
            val title = it.attr("title").ifEmpty { it.text() }
            val poster = it.selectFirst("img")?.attr("src")
            if (href.isNullOrEmpty() || title.isNullOrEmpty()) return@mapNotNull null
            AnimeSearchResponse(
                name = title,
                url = fixUrl(href),
                apiName = this.name,
                type = TvType.Anime,
                posterUrl = poster,
            )
        }

        return newHomePageResponse("Τάσεις", trending, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document

        return document.select("div.animepost a").mapNotNull {
            val href = it.attr("href")
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            AnimeSearchResponse(
                name = title,
                url = fixUrl(href),
                apiName = this.name,
                type = TvType.Anime,
                posterUrl = poster
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("div.thumb img")?.attr("src")
        val aniData = getAniListData(title)

        val episodes = doc.select("div#episodes a, div.episodelist a").mapIndexed { index, ep ->
            val name = ep.text().trim().ifEmpty { "Episode ${index + 1}" }
            val link = ep.attr("href")
            Episode(link, name = name, posterUrl = poster)
        }

        val plot = buildString {
            aniData?.description?.let { appendLine(it) }
            aniData?.studio?.let { appendLine("Studio: $it") }
            aniData?.genres?.let { appendLine("Genres: ${it.joinToString(", ")}") }
            aniData?.characters?.let { appendLine("Characters: ${it.joinToString(", ")}") }
        }.ifEmpty { "No synopsis available." }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = aniData?.cover ?: poster
            backgroundPosterUrl = aniData?.banner ?: aniData?.cover ?: poster
            addEpisodes(DubStatus.Subbed, episodes)
            plotSummary = plot
            rating = aniData?.rating?.toDoubleOrNull()
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }
}
