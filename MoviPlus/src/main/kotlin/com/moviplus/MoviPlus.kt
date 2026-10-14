package com.moviplus

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

class MoviPlusProvider : MainAPI() {
    override var mainUrl = "https://movi-plus.com"
    override var name = "MoviPlus"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/api/catalog?type=MOVIE" to "أحدث الأفلام",
        "$mainUrl/api/catalog?type=SERIES" to "المسلسلات",
    )

    private fun posterUrl(storageKey: String?): String? {
        if (storageKey.isNullOrBlank()) return null
        return "$mainUrl/api/public/media?key=${URLEncoder.encode(storageKey, "UTF-8")}"
    }

    private fun toSearchResult(item: CatalogItem): SearchResponse? {
        val title = item.titleAr?.takeIf { it.isNotBlank() }
            ?: item.titleEn?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = posterUrl(item.poster?.storageKey)
        val year = item.year
        return if (item.type == "SERIES") {
            newTvSeriesSearchResponse(title, "$mainUrl/api/catalog/${item.id}", TvType.TvSeries) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/api/catalog/${item.id}", TvType.Movie) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}limit=24&page=$page"
        val items = try {
            val text = app.get(url, referer = mainUrl).text
            parseJson<List<CatalogItem>>(text).mapNotNull { toSearchResult(it) }
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 24)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return try {
            val text = app.get("$mainUrl/api/catalog?search=$encoded&limit=24&page=1", referer = mainUrl).text
            parseJson<List<CatalogItem>>(text).mapNotNull { toSearchResult(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val items = try {
            val text = app.get("$mainUrl/api/catalog?search=$encoded&limit=24&page=$page", referer = mainUrl).text
            parseJson<List<CatalogItem>>(text).mapNotNull { toSearchResult(it) }
        } catch (e: Exception) {
            emptyList()
        }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return null
        val detail = try {
            val text = app.get("$mainUrl/api/catalog/$id", referer = mainUrl).text
            parseJson<CatalogDetail>(text)
        } catch (e: Exception) {
            return null
        }
        val title = detail.titleAr?.takeIf { it.isNotBlank() }
            ?: detail.titleEn?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = posterUrl(detail.poster?.storageKey)
        val background = posterUrl(detail.backdrop?.storageKey)
        val plot = detail.descriptionAr?.takeIf { it.isNotBlank() } ?: detail.descriptionEn
        val year = detail.year
        val rating = detail.rating?.toDoubleOrNull()?.let { Score.from10(it) }
        val tags = detail.genres?.mapNotNull { it.genre?.nameAr }

        return if (detail.type == "SERIES") {
            val episodes = mutableListOf<Episode>()
            detail.seasons?.sortedBy { it.seasonNumber ?: 0 }?.forEach { season ->
                val sNum = season.seasonNumber ?: 1
                season.episodes?.sortedBy { it.episodeNumber ?: 0 }?.forEach { ep ->
                    val epId = ep.id ?: return@forEach
                    val epNum = ep.episodeNumber
                    val epTitle = ep.titleAr?.takeIf { it.isNotBlank() }
                        ?: ep.titleEn?.takeIf { it.isNotBlank() && it != "Episode $epNum" }
                        ?: "الحلقة $epNum"
                    episodes.add(
                        newEpisode("$mainUrl/api/catalog/$id|$epId") {
                            this.name = epTitle
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            }
            if (episodes.isEmpty()) return null
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.score = rating
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/api/catalog/$id") {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.score = rating
                this.tags = tags
            }
        }
    }

    private fun extractQualityInt(resolution: String?): Int {
        val t = resolution?.lowercase()?.trim() ?: return Qualities.Unknown.value
        Regex("""(\d{3,4})\s*[xp]""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""(\d{3,4})""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it in listOf(360, 480, 720, 1080, 1440, 2160)) return it
        }
        return when {
            t.contains("4k") || t.contains("2160") -> 2160
            t.contains("1440") -> 1440
            t.contains("1080") -> 1080
            t.contains("720") -> 720
            t.contains("480") -> 480
            t.contains("360") -> 360
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data may arrive as a bare id ("abc|ep1") or absolutized by the client
        // ("https://movi-plus.com/api/catalog/abc|ep1") -> always take the id
        // segment after the last "/" so playback never 404s on a full URL.
        val (contentId, episodeId) = if (data.contains("|")) {
            val parts = data.split("|")
            parts[0].substringAfterLast("/").substringBefore("?").trim() to
                parts.getOrNull(1)?.substringAfterLast("/")?.substringBefore("?")?.trim()
        } else {
            data.substringAfterLast("/").substringBefore("?").trim() to null
        }
        if (contentId.isBlank()) return false

        val sourceUrl = if (!episodeId.isNullOrBlank()) {
            "$mainUrl/api/public/playback/source?contentId=${URLEncoder.encode(contentId, "UTF-8")}&episodeId=${URLEncoder.encode(episodeId, "UTF-8")}"
        } else {
            "$mainUrl/api/public/playback/source?contentId=${URLEncoder.encode(contentId, "UTF-8")}"
        }

        val source = try {
            val text = app.get(sourceUrl, referer = mainUrl).text
            parseJson<PlaybackSource>(text)
        } catch (e: Exception) {
            return false
        }

        val manifestUrl = source.manifestUrl?.takeIf { it.isNotBlank() } ?: return false

        source.captions?.forEach { cap ->
            val capUrl = cap.captionUrl?.takeIf { it.isNotBlank() } ?: return@forEach
            val lang = cap.srclang?.takeIf { it.isNotBlank() } ?: "ar"
            val label = cap.label?.takeIf { it.isNotBlank() } ?: lang
            try {
                subtitleCallback.invoke(SubtitleFile(lang, capUrl))
            } catch (_: Exception) { }
        }

        val headers = mapOf("Referer" to "$mainUrl/")
        try {
            val master = app.get(manifestUrl, headers = headers).text
            val basePath = manifestUrl.substringBeforeLast("/")
            var foundVariant = false
            val variantRegex = Regex("""#EXT-X-STREAM-INF:([^\n]+)\n([^\n#][^\n]*)""")
            variantRegex.findAll(master).forEach { match ->
                val attrs = match.groupValues[1]
                var variantUri = match.groupValues[2].trim()
                if (variantUri.isBlank()) return@forEach
                if (!variantUri.startsWith("http")) variantUri = "$basePath/$variantUri"
                val res = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.get(1)
                val bw = Regex("""BANDWIDTH=(\d+)""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                val qInt = res?.toIntOrNull() ?: extractQualityInt(attrs)
                val qLabel = if (res != null) "${res}p" else (bw?.let { "${it / 1000}kbps" } ?: "Auto")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - $qLabel",
                        url = variantUri
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qInt
                    }
                )
                foundVariant = true
            }
            if (foundVariant) {
                callback.invoke(
                    newExtractorLink(source = name, name = "$name - Auto", url = manifestUrl) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (_: Exception) { }

        callback.invoke(
            newExtractorLink(source = name, name = "$name - Auto", url = manifestUrl) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatalogItem(
        @JsonProperty("id") val id: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("titleAr") val titleAr: String?,
        @JsonProperty("titleEn") val titleEn: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("poster") val poster: MediaAsset?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatalogDetail(
        @JsonProperty("id") val id: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("titleAr") val titleAr: String?,
        @JsonProperty("titleEn") val titleEn: String?,
        @JsonProperty("descriptionAr") val descriptionAr: String?,
        @JsonProperty("descriptionEn") val descriptionEn: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("poster") val poster: MediaAsset?,
        @JsonProperty("backdrop") val backdrop: MediaAsset?,
        @JsonProperty("genres") val genres: List<GenreWrap>?,
        @JsonProperty("seasons") val seasons: List<Season>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaAsset(
        @JsonProperty("storageKey") val storageKey: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GenreWrap(
        @JsonProperty("genre") val genre: Genre?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(
        @JsonProperty("nameAr") val nameAr: String?,
        @JsonProperty("nameEn") val nameEn: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(
        @JsonProperty("seasonNumber") val seasonNumber: Int?,
        @JsonProperty("episodes") val episodes: List<Ep>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Ep(
        @JsonProperty("id") val id: String?,
        @JsonProperty("episodeNumber") val episodeNumber: Int?,
        @JsonProperty("titleAr") val titleAr: String?,
        @JsonProperty("titleEn") val titleEn: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlaybackSource(
        @JsonProperty("manifestUrl") val manifestUrl: String?,
        @JsonProperty("captions") val captions: List<Caption>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Caption(
        @JsonProperty("srclang") val srclang: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("captionUrl") val captionUrl: String?
    )
}
