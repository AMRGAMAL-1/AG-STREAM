package com.dexter

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class DexterProvider : MainAPI() {
    override var mainUrl = "https://dexter.pw"
    override var name = "Dexter"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "Dexter"

    // API posters are TMDB paths (e.g. /qJ2tW6WMUDux911r6m7haRef0WH.jpg).
    private val imgW500 = "https://image.tmdb.org/t/p/w500"
    private fun img(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return imgW500 + (if (path.startsWith("/")) path else "/$path")
    }

    // ---------- API models ----------

    data class ApiItem(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("poster") val poster: String? = null,
    )

    data class SearchResp(
        @JsonProperty("results") val results: List<ApiItem>? = null,
    )

    data class EmbedSource(
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    data class MovieDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("sources") val sources: List<EmbedSource>? = null,
    )

    data class SeasonInfo(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
    )

    data class TvDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("seasonList") val seasonList: List<SeasonInfo>? = null,
    )

    data class EpisodeInfo(
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still") val still: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
    )

    data class SeasonResp(
        @JsonProperty("episodes") val episodes: List<EpisodeInfo>? = null,
    )

    // ---------- Home ----------

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending",
    )

    private fun pageUrl(id: Int, title: String, isTv: Boolean): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .takeIf { it.isNotBlank() } ?: if (isTv) "show" else "movie"
        return if (isTv) "$mainUrl/show/$id-$slug" else "$mainUrl/movie/$id-$slug"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Homepage embeds an SEO JSON-LD ItemList with trending titles.
        val doc = app.get(request.data, referer = mainUrl).document
        val items = mutableListOf<SearchResponse>()
        doc.select("script[type=\"application/ld+json\"]").forEach { s ->
            val txt = s.data().trim()
            if (!txt.contains("\"ItemList\"")) return@forEach
            try {
                val root = AppUtils.parseJson<Map<String, Any?>>(txt)
                @Suppress("UNCHECKED_CAST")
                val elements = root["itemListElement"] as? List<Map<String, Any?>> ?: return@forEach
                for (el in elements) {
                    val u = (el["url"] as? String)?.trim() ?: continue
                    val title = (el["name"] as? String)?.trim() ?: continue
                    val isTv = u.contains("/show/")
                    if (!u.contains("/movie/") && !isTv) continue
                    val id = Regex("""/(?:movie|show)/(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull()
                        ?: continue
                    val full = fixUrl(u)
                    if (isTv) {
                        items.add(newTvSeriesSearchResponse(title, full, TvType.TvSeries))
                    } else {
                        items.add(newMovieSearchResponse(title, full, TvType.Movie))
                    }
                }
            } catch (_: Exception) {}
        }
        Log.d(TAG, "getMainPage ${request.name} found ${items.size}")
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    // ---------- Search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val url = "$mainUrl/api/search?q=$enc"
        Log.d(TAG, "search -> $url")
        val txt = app.get(url, referer = mainUrl, headers = mapOf("Accept" to "application/json")).text
        val resp = try { AppUtils.parseJson<SearchResp>(txt) } catch (_: Exception) { return emptyList() }
        return (resp.results ?: emptyList()).mapNotNull { r ->
            val id = r.id ?: return@mapNotNull null
            val title = r.title?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val isTv = r.type?.lowercase() == "tv"
            if (isTv) {
                newTvSeriesSearchResponse(title, pageUrl(id, title, true), TvType.TvSeries) {
                    this.posterUrl = img(r.poster)
                    this.year = r.year
                }
            } else {
                newMovieSearchResponse(title, pageUrl(id, title, false), TvType.Movie) {
                    this.posterUrl = img(r.poster)
                    this.year = r.year
                }
            }
        }.distinctBy { it.url }
    }

    // ---------- Details ----------

    private fun apiId(url: String): Int? {
        return Regex("""/(?:movie|show)/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load $url")
        val id = apiId(url) ?: throw ErrorLoadingException("No id in $url")
        val isTv = url.contains("/show/")
        if (!isTv) {
            val txt = app.get(
                "$mainUrl/api/movie/$id", referer = mainUrl,
                headers = mapOf("Accept" to "application/json")
            ).text
            val d = AppUtils.parseJson<MovieDetail>(txt)
            val title = d.title?.trim()?.takeIf { it.isNotBlank() } ?: "Movie $id"
            Log.d(TAG, "movie $title")
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/api/movie/$id") {
                this.posterUrl = img(d.poster)
                this.backgroundPosterUrl = img(d.backdrop)
                this.plot = d.overview?.takeIf { it.isNotBlank() }
                this.year = d.year
                this.tags = d.genres?.filter { it.isNotBlank() }?.distinct()?.take(10)
            }
        }
        val txt = app.get(
            "$mainUrl/api/tv/$id", referer = mainUrl,
            headers = mapOf("Accept" to "application/json")
        ).text
        val d = AppUtils.parseJson<TvDetail>(txt)
        val title = d.title?.trim()?.takeIf { it.isNotBlank() } ?: "Show $id"
        val episodes = mutableListOf<Episode>()
        for (s in (d.seasonList ?: emptyList()).sortedBy { it.number ?: 0 }) {
            val sn = s.number ?: continue
            try {
                val stxt = app.get(
                    "$mainUrl/api/tv/$id/season/$sn", referer = mainUrl,
                    headers = mapOf("Accept" to "application/json")
                ).text
                val season = AppUtils.parseJson<SeasonResp>(stxt)
                for (ep in (season.episodes ?: emptyList()).sortedBy { it.episode ?: 0 }) {
                    val en = ep.episode ?: continue
                    episodes.add(newEpisode("$mainUrl/watch/tv/$id/$sn/$en") {
                        this.name = ep.title?.trim()?.takeIf { it.isNotBlank() }
                            ?.let { "E$en: $it" } ?: "Episode $en"
                        this.episode = en
                        this.season = sn
                        this.posterUrl = img(ep.still)
                        this.description = ep.overview?.takeIf { it.isNotBlank() }
                    })
                }
            } catch (e: Exception) {
                Log.d(TAG, "season $sn failed: ${e.message}")
            }
        }
        Log.d(TAG, "show $title -> ${episodes.size} episodes")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = img(d.poster)
            this.backgroundPosterUrl = img(d.backdrop)
            this.plot = d.overview?.takeIf { it.isNotBlank() }
            this.year = d.year
            this.tags = d.genres?.filter { it.isNotBlank() }?.distinct()?.take(10)
        }
    }

    // ---------- Streams ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks $data")
        // Movie data: /api/movie/{id}. Episode data: /watch/tv/{id}/{s}/{e}.
        val mid = Regex("""/api/movie/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        val ep = Regex("""/watch/tv/(\d+)/(\d+)/(\d+)""").find(data)
        return try {
            if (ep != null) {
                val (id, s, e) = Triple(
                    ep.groupValues[1].toInt(), ep.groupValues[2].toInt(), ep.groupValues[3].toInt()
                )
                // NOTE: dexter's own HLS (Server 21) is currently down (502),
                // so only the third-party embeds are used.
                val targets = listOf(
                    "Cinesrc" to "https://cinesrc.st/embed/tv/$id?s=$s&e=$e",
                    "Vidfast" to "https://vidfast.vc/tv/$id/$s/$e",
                )
                coroutineScope {
                    targets.map { (label, u) ->
                        async {
                            try {
                                loadExtractor(u, mainUrl, subtitleCallback) { callback(it) }
                            } catch (ex: Exception) {
                                Log.d(TAG, "extractor failed $label: ${ex.message}")
                            }
                        }
                    }.awaitAll()
                }
            } else if (mid != null) {
                val targets = listOf(
                    "Cinesrc" to "https://cinesrc.st/embed/movie/$mid",
                    "Vidfast" to "https://vidfast.vc/movie/$mid",
                )
                coroutineScope {
                    targets.map { (label, u) ->
                        async {
                            try {
                                loadExtractor(u, mainUrl, subtitleCallback) { callback(it) }
                            } catch (ex: Exception) {
                                Log.d(TAG, "extractor failed $label: ${ex.message}")
                            }
                        }
                    }.awaitAll()
                }
            } else {
                Log.d(TAG, "loadLinks: no id in $data")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
            true
        }
    }
}
