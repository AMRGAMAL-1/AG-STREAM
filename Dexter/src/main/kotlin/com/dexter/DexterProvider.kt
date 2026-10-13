package com.dexter

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import java.net.URLEncoder

class DexterProvider : MainAPI() {
    override var mainUrl = "https://dexter.pw"
    override var name = "Dexter"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "Dexter"

    private val imgW500 = "https://image.tmdb.org/t/p/w500"
    private fun img(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return imgW500 + (if (path.startsWith("/")) path else "/$path")
    }

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

    data class BrowseResp(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("results") val results: List<ApiItem>? = null,
    )

    data class Shelf(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("items") val items: List<ApiItem>? = null,
    )

    data class HomeResp(
        @JsonProperty("shelves") val shelves: List<Shelf>? = null,
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

    data class FebSource(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("size") val size: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class FebSub(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("file") val file: String? = null,
    )

    data class FebResp(
        @JsonProperty("sources") val sources: List<FebSource>? = null,
        @JsonProperty("hls") val hls: String? = null,
        @JsonProperty("subtitles") val subtitles: List<FebSub>? = null,
    )

    private val febboxOrigin = "https://fembox.aether.bar"
    private val cinesrcOrigin = "https://cinesrc.st"

    override val mainPage = mainPageOf(
        "$mainUrl/api/home" to "Home",
        "$mainUrl/api/browse?type=movie" to "Movies",
        "$mainUrl/api/browse?type=tv" to "TV Shows",
    )

    private fun pageUrl(id: Int, title: String, isTv: Boolean): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .takeIf { it.isNotBlank() } ?: if (isTv) "show" else "movie"
        return if (isTv) "$mainUrl/show/$id-$slug" else "$mainUrl/movie/$id-$slug"
    }

    private fun ApiItem.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val title = this.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val isTv = this.type?.lowercase() == "tv"
        return if (isTv) {
            newTvSeriesSearchResponse(title, pageUrl(id, title, true), TvType.TvSeries) {
                this.posterUrl = img(this@toSearchResponse.poster)
                this.year = this@toSearchResponse.year
            }
        } else {
            newMovieSearchResponse(title, pageUrl(id, title, false), TvType.Movie) {
                this.posterUrl = img(this@toSearchResponse.poster)
                this.year = this@toSearchResponse.year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage ${request.name} page=$page -> ${request.data}")
        return try {
            if (request.data.contains("/api/home")) {
                if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
                val txt = app.get(
                    request.data, referer = mainUrl,
                    headers = mapOf("Accept" to "application/json"),
                    timeout = 20
                ).text
                val home = AppUtils.parseJson<HomeResp>(txt)
                val lists = (home.shelves ?: emptyList()).mapNotNull { shelf ->
                    val title = shelf.title?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val items = (shelf.items ?: emptyList()).mapNotNull { it.toSearchResponse() }
                        .distinctBy { it.url }
                    if (items.isEmpty()) return@mapNotNull null
                    HomePageList(title, items)
                }
                Log.d(TAG, "home shelves -> ${lists.size} rows")
                newHomePageResponse(lists, hasNext = false)
            } else {
                var url = request.data
                url += if (url.contains("?")) "&page=$page" else "?page=$page"
                val txt = app.get(
                    url, referer = mainUrl,
                    headers = mapOf("Accept" to "application/json"),
                    timeout = 20
                ).text
                val resp = AppUtils.parseJson<BrowseResp>(txt)
                val items = (resp.results ?: emptyList()).mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                val hasNext = (resp.page ?: page) < (resp.totalPages ?: page)
                Log.d(TAG, "browse ${request.name} page=$page -> ${items.size} items hasNext=$hasNext")
                newHomePageResponse(request.name, items, hasNext)
            }
        } catch (e: Exception) {
            Log.d(TAG, "getMainPage ${request.name} failed: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val url = "$mainUrl/api/search?q=$enc"
        Log.d(TAG, "search -> $url")
        val txt = app.get(url, referer = mainUrl, headers = mapOf("Accept" to "application/json")).text
        val resp = try { AppUtils.parseJson<SearchResp>(txt) } catch (_: Exception) { return emptyList() }
        return (resp.results ?: emptyList()).mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

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

    private fun qualityOf(label: String?): Int {
        val digits = label?.filter { it.isDigit() }?.toIntOrNull()
        if (digits != null && digits in 144..4320) return digits
        return Qualities.Unknown.value
    }

    private fun subLang(sub: FebSub): String {
        val raw = (sub.language ?: sub.lang ?: sub.label)?.trim()?.takeIf { it.isNotBlank() }
            ?: return "sub"
        return when (raw.lowercase()) {
            "en", "eng", "english" -> "English"
            "ar", "ara", "arabic" -> "Arabic"
            else -> raw.replaceFirstChar { it.uppercase() }
        }
    }

    private fun emitFebResp(
        resp: FebResp?,
        tag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (resp == null) {
            Log.d(TAG, "$tag: no febbox data")
            return
        }
        var emitted = 0
        val hls = resp.hls?.trim()?.takeIf { it.startsWith("http") }
        if (hls != null) {
            try {
                callback(newExtractorLink(source = this.name, name = "$name $tag HLS", url = hls) {
                    this.referer = "$cinesrcOrigin/"
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
                })
                emitted++
            } catch (_: Exception) {}
        }
        for (s in (resp.sources ?: emptyList())) {
            val u = s.url?.trim()?.takeIf { it.startsWith("http") } ?: continue
            val q = listOfNotNull(s.quality?.trim(), s.size?.trim()?.takeIf { it.isNotBlank() })
                .joinToString(" ").trim()
            try {
                callback(newExtractorLink(source = this.name, name = "$name $tag $q".trim(), url = u) {
                    this.referer = "$cinesrcOrigin/"
                    this.quality = qualityOf(s.quality)
                    this.type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                })
                emitted++
            } catch (_: Exception) {}
        }
        for (sub in (resp.subtitles ?: emptyList())) {
            try {
                val u = (sub.url ?: sub.file)?.trim()?.takeIf { it.startsWith("http") } ?: continue
                subtitleCallback(newSubtitleFile(subLang(sub), u))
            } catch (_: Exception) {}
        }
        Log.d(TAG, "$tag: emitted=$emitted subs=${resp.subtitles?.size ?: 0}")
    }

    private suspend fun loadFebbox(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hlsUrl = if (!isTv) {
            "$febboxOrigin/hls/movie/$tmdbId?ui=cinesrc"
        } else {
            "$febboxOrigin/hls/tv/$tmdbId/${season ?: 1}/${episode ?: 1}?ui=cinesrc"
        }
        val mp4Url = if (!isTv) {
            "$febboxOrigin/movie/$tmdbId?ui=cinesrc"
        } else {
            "$febboxOrigin/tv/$tmdbId-${season ?: 1}-${episode ?: 1}?ui=cinesrc"
        }
        for ((tag, url, isHls) in listOf(
            Triple("FebHLS", hlsUrl, true),
            Triple("FebMP4", mp4Url, false)
        )) {
            try {
                val txt = app.get(
                    url, referer = "$cinesrcOrigin/",
                    headers = mapOf("Accept" to "application/json", "Origin" to cinesrcOrigin),
                    timeout = 20
                ).text
                if (txt.isBlank()) {
                    Log.d(TAG, "$tag: empty body")
                    continue
                }
                val resp = try {
                    AppUtils.parseJson<FebResp>(txt)
                } catch (e: Exception) {
                    Log.d(TAG, "$tag: parse failed: ${e.message} body=${txt.take(120)}")
                    null
                }
                if (!isHls && resp?.sources.isNullOrEmpty() && resp?.hls.isNullOrBlank()) {
                    Log.d(TAG, "$tag: no sources")
                    continue
                }
                emitFebResp(resp, tag, subtitleCallback, callback)
            } catch (e: Throwable) {
                Log.d(TAG, "$tag failed: ${e.message}")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks $data")
        val mid = Regex("""/api/movie/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        val ep = Regex("""/watch/tv/(\d+)/(\d+)/(\d+)""").find(data)
        return try {
            if (ep != null) {
                val id = ep.groupValues[1].toInt()
                val s = ep.groupValues[2].toInt()
                val e = ep.groupValues[3].toInt()
                val targets = listOf(
                    "Cinesrc" to "https://cinesrc.st/embed/tv/$id?s=$s&e=$e",
                    "Vidfast" to "https://vidfast.vc/tv/$id/$s/$e",
                )
                supervisorScope {
                    (targets.map { (label, u) ->
                        async {
                            try {
                                loadExtractor(u, mainUrl, subtitleCallback) { callback(it) }
                            } catch (ex: Throwable) {
                                Log.d(TAG, "extractor failed $label: ${ex.message}")
                            }
                        }
                    } + async {
                        try {
                            loadFebbox(id, true, s, e, subtitleCallback, callback)
                        } catch (ex: Throwable) {
                            Log.d(TAG, "febbox tv failed: ${ex.message}")
                        }
                    }).awaitAll()
                }
            } else if (mid != null) {
                val targets = listOf(
                    "Cinesrc" to "https://cinesrc.st/embed/movie/$mid",
                    "Vidfast" to "https://vidfast.vc/movie/$mid",
                )
                supervisorScope {
                    (targets.map { (label, u) ->
                        async {
                            try {
                                loadExtractor(u, mainUrl, subtitleCallback) { callback(it) }
                            } catch (ex: Throwable) {
                                Log.d(TAG, "extractor failed $label: ${ex.message}")
                            }
                        }
                    } + async {
                        try {
                            loadFebbox(mid, false, null, null, subtitleCallback, callback)
                        } catch (ex: Throwable) {
                            Log.d(TAG, "febbox movie failed: ${ex.message}")
                        }
                    }).awaitAll()
                }
            } else {
                Log.d(TAG, "loadLinks: no id in $data")
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
            true
        }
    }
}
