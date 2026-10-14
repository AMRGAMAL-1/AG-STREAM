package com.movizland

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URLDecoder
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

    private fun decodeHref(href: String): String {
        return try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
    }

    private fun linkToResult(a: Element): SearchResponse? {
        var href = a.attr("href").trim()
        if (href.isBlank() || href == "#") return null
        if (href.startsWith("/")) href = mainUrl + href
        if (!href.contains("moviz.land")) return null
        val decoded = decodeHref(href)
        if (!decoded.contains("مشاهد")) return null
        if (decoded.contains("/category/") || decoded.contains("/genre/")) return null
        if (href.endsWith("/watch/") || href.contains("/page/")) return null

        val card = a.closest("article") ?: a.parent() ?: a
        val title = a.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: a.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: a.text()?.trim()?.takeIf { it.length in 4..200 }
            ?: return null

        val img = a.selectFirst("img") ?: card.selectFirst("img")
        val posterUrl = img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrl(it) }

        val isSeries = decoded.contains("مسلسل") || title.contains("مسلسل") ||
            title.contains("الحلقة") || title.contains("الموسم")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a[href]") ?: return null
        return linkToResult(linkTag)
    }

    private fun parseListing(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val found = linkedMapOf<String, SearchResponse>()
        document.select("a[href]").forEach { a ->
            try {
                linkToResult(a)?.let { if (!found.containsKey(it.url)) found[it.url] = it }
            } catch (_: Exception) { }
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
            }
        } else {
            val watchUrl = if (url.endsWith("/")) "${url}watch/" else "$url/watch/"
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
            }
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

    /**
     * Generic embed harvester for hosts without a dedicated extractor
     * (e.g. upbom): unpacks Dean-Edwards packed JS and extracts direct
     * <source>/file:/.m3u8/.mp4 links with per-quality labels.
     */
    private suspend fun harvestEmbed(
        embedUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val rawHtml = app.get(embedUrl, referer = referer).text
            val html = try { JsUnpacker(rawHtml).unpack() ?: rawHtml } catch (_: Exception) { rawHtml }
            var found = false
            val seen = mutableSetOf<String>()
            suspend fun emit(raw: String, hint: String?) {
                var u = raw.trim().replace("\\/", "/")
                if (u.startsWith("//")) u = "https:$u"
                if (!u.startsWith("http") || !seen.add(u)) return
                val low = u.lowercase()
                if (low.contains(".css") || low.contains(".js") || low.contains(".png") ||
                    low.contains(".jpg") || low.contains(".jpeg") || low.contains(".gif") ||
                    low.contains(".webp") || low.contains(".svg") || low.contains(".woff") ||
                    low.contains("plyr.io") || low.contains("vast") || low.contains("google") ||
                    low.contains("gstatic") || low.contains("facebook") || low.contains("analytics") ||
                    low.contains("plausible") || low.contains("histats")
                ) return
                val qInt = extractQualityInt((hint ?: "") + " " + u)
                val qLabel = hint?.takeIf { it.isNotBlank() } ?: "Auto"
                callback.invoke(
                    newExtractorLink(source = name, name = "$serverName - $qLabel", url = u) {
                        this.referer = embedUrl
                        this.quality = qInt
                    }
                )
                found = true
            }
            for (m in Regex("""<source[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
                val tag = m.value
                val size = Regex("""size\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)?.let { "${it}p" }
                emit(m.groupValues[1], size)
            }
            for (m in Regex(
                """file\s*:\s*["']([^"']+)["'][^}]{0,200}?label\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(html)) {
                emit(m.groupValues[1], m.groupValues[2])
            }
            for (m in Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
                emit(m.groupValues[1], null)
            }
            for (m in Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE).findAll(html)) {
                emit(m.value, null)
            }
            for (m in Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
                    var src = m.groupValues[1].trim()
                    if (src.isBlank() || src.startsWith("data:")) continue
                    if (src.startsWith("//")) src = "https:$src"
                    if (!src.startsWith("http")) continue
                    val low = src.lowercase()
                    if (low.contains("recaptcha") || low.contains("google")) continue
                    val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    try {
                        loadExtractor(src, embedUrl, subtitleCallback) { collected.add(it) }
                    } catch (_: Exception) { }
                    collected.forEach { ext ->
                        var u = ext.url
                        if (!seen.add(u)) return@forEach
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$serverName - ${ext.name}",
                                url = u
                            ) {
                                this.referer = ext.referer
                                this.headers = ext.headers
                                this.quality = ext.quality
                                this.type = ext.type
                            }
                        )
                        found = true
                    }
            }
            found
        } catch (_: Exception) {
            false
        }
    }

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
        val serverCards = watchDoc.select(".server-card[data-src]")
        val servers = serverCards.mapNotNull { card ->
            val src = card.attr("data-src").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = card.selectFirst(".server-text strong")?.text()?.trim()
                ?: card.attr("aria-label")?.trim()?.takeIf { it.isNotBlank() }
                ?: "سيرفر موفيز لاند"
            Triple(if (src.startsWith("//")) "https:$src" else src, label, card.attr("data-quality") == "1")
        }.toMutableList()
        if (servers.isEmpty()) {
            val raw = try { watchDoc.html() } catch (_: Exception) { "" }
            Regex("""data-src\s*=\s*"([^"]+)"""").findAll(raw).forEach { m ->
                val u = m.groupValues[1].trim()
                if (u.startsWith("http") || u.startsWith("//")) {
                    servers.add(Triple(if (u.startsWith("//")) "https:$u" else u, "سيرفر موفيز لاند", false))
                }
            }
            Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(raw).forEach { m ->
                    val u = m.groupValues[1].trim()
                    if (u.startsWith("http") || u.startsWith("//")) {
                        servers.add(Triple(if (u.startsWith("//")) "https:$u" else u, "سيرفر موفيز لاند", false))
                    }
                }
        }
        if (servers.isEmpty()) return false

        coroutineScope {
            servers.distinctBy { it.first }.map { (url, serverName, isQualityServer) ->
                async {
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
                                        if (collectedQ.isEmpty()) {
                                            try {
                                                harvestEmbed(vUrl, "$serverName - $qLabel", data, subtitleCallback, callback)
                                            } catch (_: Exception) { }
                                        } else {
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
                        if (collected.isEmpty()) {
                            try {
                                harvestEmbed(url, serverName, data, subtitleCallback, callback)
                            } catch (_: Exception) { }
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
