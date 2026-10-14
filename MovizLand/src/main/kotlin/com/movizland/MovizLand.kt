package com.movizland

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MovizLandProvider : MainAPI() {
    override var mainUrl = "https://www.moviz.land"
    override var name = "MovizLand"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/series/" to "أحدث المسلسلات",
        "$mainUrl/anime/" to "أنمي",
        "$mainUrl/category/movies/أفلام-أجنبي/" to "أفلام أجنبي",
        "$mainUrl/category/movies/أفلام-تركي-موفيزلاند/" to "أفلام تركي",
        "$mainUrl/category/movies/أفلام-صيني/" to "أفلام صيني",
        "$mainUrl/category/movies/افلام-مدبلجة/" to "أفلام مدبلجة",
        "$mainUrl/category/movies/أفلام-كوري/" to "أفلام كوري",
    )

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a[href]") ?: return null
        var href = linkTag.attr("href")?.trim() ?: return null
        if (href.isBlank() || href == "#") return null
        if (href.startsWith("/")) href = mainUrl + href
        if (!href.contains("moviz.land")) return null
        if (href.contains("/category/") || href.contains("/genre/") || href.contains("/series/") && href.endsWith("/series/")) return null

        val title = element.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: linkTag.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: element.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        if (title.length < 3) return null

        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("data:") }

        val isSeries = href.contains("مسلسل") || title.contains("مسلسل") ||
            title.contains("الحلقة") || title.contains("الموسم") ||
            element.selectFirst(".episode-card, .mita-single-season-card") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
            }
        }
    }

    private fun parseListing(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val selectors = listOf(
            "article a[href]",
            ".movie-card a[href]",
            ".related-card-link",
            ".episodes-grid a[href]",
            "a.related-card-link",
            ".grid a[href*=مشاهدة-]"
        )
        val found = linkedMapOf<String, SearchResponse>()
        document.select("a[href*=مشاهدة-فيلم], a[href*=مشاهدة-مسلسل]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            if (found.containsKey(href)) return@forEach
            val card = a.closest("article") ?: a.parent() ?: a
            val title = a.selectFirst("img")?.attr("alt")
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: card.text().trim().takeIf { it.length in 4..200 }
                ?: return@forEach
            val img = a.selectFirst("img") ?: card.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotBlank() && it.startsWith("http") }
            val isSeries = href.contains("مسلسل") || title.contains("مسلسل") || title.contains("الحلقة")
            val res = if (isSeries) {
                newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title.trim(), href, TvType.Movie) { this.posterUrl = poster }
            }
            found[href] = res
        }
        if (found.isEmpty()) {
            selectors.forEach { sel ->
                document.select(sel).forEach { el ->
                    toSearchResult(el)?.let { found[it.url] = it }
                }
            }
        }
        return found.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val items = parseListing(document)
        val hasNext = document.select("a.next, .pagination a, .page-numbers").any {
            it.text().contains("${page + 1}") || it.attr("href").contains("/page/${page + 1}")
        } || items.size >= 20
        return newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return parseListing(document)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url).document
        val items = parseListing(document)
        return newSearchResponseList(items, items.isNotEmpty())
    }

    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(19|20)\d{2}""").find(text)?.value?.toIntOrNull()
    }

    private fun extractEpisodeNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun extractSeasonNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        Regex("""الموسم\s*(\d+|الأول|الثاني|الثالث|الرابع|الخامس)""").find(text)?.groupValues?.get(1)?.let {
            return when (it) {
                "الأول" -> 1; "الثاني" -> 2; "الثالث" -> 3; "الرابع" -> 4; "الخامس" -> 5
                else -> it.toIntOrNull()
            }
        }
        Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.video-title a, h1.video-title")?.text()?.trim()
            ?: document.selectFirst("h2.detail-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" |")?.trim()
            ?: return null

        val posterBg = document.selectFirst(".content-area")?.attr("style")?.let {
            Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1)?.trim('\'', '"', ' ')
        }
        val poster = posterBg?.let { fixUrl(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        val plot = document.selectFirst(".detail-story")?.text()?.trim()
            ?: document.selectFirst(".video-description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val ratingText = document.selectFirst(".detail-meta .rating")?.text()
        val score = Regex("""(\d+(\.\d+)?)""").find(ratingText ?: "")?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            Score.from10(it)
        }
        val qualityText = document.select(".detail-meta span").map { it.text() }.firstOrNull {
            it.contains("HD", true) || it.contains("WEB-DL", true) || it.contains("720p") || it.contains("1080p")
        }
        val year = extractYear(title + " " + document.select(".detail-meta").text())
        val episodeCards = document.select("#episodes a.episode-card[href]")
        if (episodeCards.isNotEmpty()) {
            val seasonFromTitle = extractSeasonNumber(title) ?: 1
            val episodes = episodeCards.mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                if (href.isBlank()) return@mapNotNull null
                val epNum = extractEpisodeNumber(a.attr("aria-label") + " " + a.text()) ?: extractEpisodeNumber(href)
                val epTitle = a.attr("aria-label")?.trim()?.takeIf { it.isNotBlank() }
                    ?: "الحلقة ${epNum ?: ""}"
                newEpisode(href) {
                    this.name = epTitle.trim()
                    this.season = seasonFromTitle
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.sortedWith(compareBy({ it.season }, { it.episode }))
            val cleanTitle = title.replace(Regex("""\s*الحلقة\s*\d+.*"""), "").replace(Regex("""\s*الموسم.*الحلقة.*"""), "").trim()
            return newTvSeriesLoadResponse(cleanTitle.ifBlank { title }, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.quality = getQualityFromString(qualityText)
            }
        }

        val isSeriesPost = url.contains("مسلسل") || title.contains("مسلسل") || title.contains("الحلقة")
        return if (isSeriesPost) {
            val epNum = extractEpisodeNumber(title)
            val seasonNum = extractSeasonNumber(title) ?: 1
            val watchUrl = if (url.endsWith("/")) "${url}watch/" else "$url/watch/"
            val episode = newEpisode(watchUrl) {
                this.name = title
                this.season = seasonNum
                this.episode = epNum
                this.posterUrl = poster
            }
            val cleanTitle = title.replace(Regex("""\s*الحلقة\s*\d+.*"""), "").trim()
            newTvSeriesLoadResponse(cleanTitle.ifBlank { title }, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.quality = getQualityFromString(qualityText)
            }
        } else {
            val watchUrl = if (url.endsWith("/")) "${url}watch/" else "$url/watch/"
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.quality = getQualityFromString(qualityText)
            }
        }
    }

    private fun getQualityFromString(text: String?): SearchQuality? {
        if (text.isNullOrBlank()) return null
        return when {
            text.contains("1080", true) -> SearchQuality.HD
            text.contains("720", true) -> SearchQuality.HD
            text.contains("HD", true) -> SearchQuality.HD
            text.contains("WEB-DL", true) -> SearchQuality.HD
            text.contains("CAM", true) -> SearchQuality.Cam
            else -> null
        }
    }

    private fun extractQualityInt(text: String?): Int {
        val t = text?.lowercase() ?: return Qualities.Unknown.value
        Regex("""(\d{3,4})\s*p""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            t.contains("4k") || t.contains("2160") -> 2160
            t.contains("1080") -> 1080
            t.contains("720") -> 720
            t.contains("480") -> 480
            t.contains("360") -> 360
            else -> Qualities.Unknown.value
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class QualityVideo(
        @JsonProperty("url") val url: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("info") val info: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchDoc = try {
            app.get(data).document
        } catch (e: Exception) {
            return false
        }
        val serverCards = watchDoc.select("button.server-card[data-src]")
        if (serverCards.isEmpty()) {
            val iframe = watchDoc.selectFirst("iframe#videoPlayer")?.attr("src")
            if (!iframe.isNullOrBlank()) {
                loadExtractor(fixUrl(iframe), data, subtitleCallback, callback)
                return true
            }
            return false
        }

        coroutineScope {
            serverCards.map { card ->
                async {
                    val src = card.attr("data-src")?.trim() ?: return@async
                    if (src.isBlank()) return@async
                    val serverName = card.selectFirst(".server-text strong")?.text()?.trim()
                        ?: card.attr("aria-label")?.trim()?.takeIf { it.isNotBlank() }
                        ?: "سيرفر موفيز لاند"
                    val isQualityServer = card.attr("data-quality") == "1"
                    val url = if (src.startsWith("//")) "https:$src" else src

                    if (isQualityServer) {
                        try {
                            val apiUrl = "$mainUrl/get_quality.php?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
                            val jsonText = app.get(apiUrl, referer = data).text
                            val videos = tryParseJson<List<QualityVideo>>(jsonText) ?: emptyList()
                            if (videos.isNotEmpty()) {
                                videos.forEach { v ->
                                    val vUrl = v.url ?: return@forEach
                                    val qLabel = listOfNotNull(v.quality, v.info).joinToString(" ").ifBlank { serverName }
                                    val qInt = extractQualityInt(v.quality + " " + v.info)
                                    if (vUrl.contains(".m3u8") || vUrl.contains(".mp4")) {
                                        callback.invoke(
                                            newExtractorLink(
                                                source = name,
                                                name = "$serverName - $qLabel",
                                                url = vUrl
                                            ) {
                                                this.referer = data
                                                this.quality = qInt
                                            }
                                        )
                                    } else {
                                        val collectedQ = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                                        try {
                                            loadExtractor(vUrl, data, subtitleCallback) { collectedQ.add(it) }
                                        } catch (_: Exception) { }
                                        collectedQ.forEach { ext ->
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = name,
                                                    name = "$serverName - $qLabel - ${ext.name}",
                                                    url = ext.url
                                                ) {
                                                    this.referer = ext.referer
                                                    this.headers = ext.headers
                                                    this.quality = if (ext.quality == Qualities.Unknown.value) qInt else ext.quality
                                                    this.type = ext.type
                                                }
                                            )
                                        }
                                    }
                                }
                                return@async
                            }
                        } catch (_: Exception) { }
                    }

                    try {
                        val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        try {
                            loadExtractor(url, data, subtitleCallback) { collected.add(it) }
                        } catch (_: Exception) { }
                        if (collected.isEmpty() && (url.contains(".m3u8") || url.contains(".mp4"))) {
                            callback.invoke(
                                newExtractorLink(source = name, name = serverName, url = url) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        } else {
                            collected.forEach { ext ->
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverName - ${ext.name}",
                                        url = ext.url
                                    ) {
                                        this.referer = ext.referer
                                        this.headers = ext.headers
                                        this.quality = ext.quality
                                        this.type = ext.type
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) { }
                }
            }.awaitAll()
        }
        return true
    }
}
