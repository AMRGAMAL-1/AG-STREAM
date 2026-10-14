package com.egytbest

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class EgyBestProvider : MainAPI() {
    override var mainUrl = "https://egytbest.live"
    override var name = "EgyBest"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/one/" to "الرئيسية",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/series/" to "المسلسلات",
        "$mainUrl/episodes/" to "أحدث الحلقات",
        "$mainUrl/trending/" to "الأكثر مشاهدة",
        "$mainUrl/category/anime" to "أنمي",
    )

    private fun cardToResult(a: Element): SearchResponse? {
        val href = fixUrl(a.attr("href").trim())
        if (href.isBlank() || href == mainUrl || href == "$mainUrl/") return null
        val rawTitle = a.selectFirst("h3.title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val title = cleanTitle(rawTitle).takeIf { it.isNotBlank() } ?: return null
        val img = a.selectFirst(".block-image img")
        val poster = (img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("src-default") })?.let { fixUrl(it) }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val decoded = try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (_: Exception) {
            href
        }
        val isSeries = decoded.contains("مسلسل") || rawTitle.contains("مسلسل") ||
            decoded.contains("الحلقة") || rawTitle.contains("الحلقة") || rawTitle.contains("الموسم")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun parseListing(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("a.block[href]").mapNotNull {
            try {
                cardToResult(it)
            } catch (_: Exception) {
                null
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.removeSuffix("/")
        val url = if (page <= 1) request.data else "$base/page/$page/"
        val document = app.get(url).document
        val items = parseListing(document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
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
        return null
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("""\(\s*\)"""), "")
        t = t.replace(Regex("""^(مشاهدة|تحميل)\s+(فيلم|مسلسل|انمي|انمى|برنامج|مسرحية)\s+"""), "")
        t = t.replace(Regex("""\s+(الحلقة\s*\d+.*|الموسم\s*\d+.*)$"""), "")
        t = t.replace(Regex("""\s+(مترجم(ة| كامل)?|مترجمة.*|مدبلج(ة)?|مدبلجة.*|كاملة?)$"""), "")
        return t.replace(Regex("""\s{2,}"""), " ").trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val path = try {
            java.net.URI(url).path ?: ""
        } catch (_: Exception) {
            ""
        }
        val firstSeg = path.trim('/').substringBefore("/")
        val isListing = firstSeg in setOf("movies", "series", "episodes", "trending", "category", "page", "tag", "genre")
        if (isListing || document.select("button[data-embed-url]").isEmpty()) {
            val cards = document.select("a.block[href]")
            if (cards.isNotEmpty() && (isListing || !url.contains("مشاهدة-"))) {
                val hubTitle = cleanTitle(
                    document.selectFirst("h1")?.text()?.trim()?.substringBefore("(")?.trim()
                        ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
                        ?: document.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
                        ?: ""
                )
                val hubPoster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
                val episodes = cards.mapNotNull { a ->
                    try {
                        val href = fixUrl(a.attr("href").trim())
                        if (href.isBlank()) return@mapNotNull null
                        val epTitle = a.selectFirst("h3.title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val epNum = extractEpisodeNumber(epTitle) ?: extractEpisodeNumber(href)
                        val seasonNum = extractSeasonNumber(epTitle) ?: extractSeasonNumber(href)
                            ?: extractSeasonNumber(hubTitle) ?: 1
                        val poster = (a.selectFirst(".block-image img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: a.selectFirst(".block-image img")?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("src-default") })?.let { fixUrl(it) }
                        newEpisode(href) {
                            this.name = if (hubTitle.isNotBlank()) "$hubTitle - $epTitle" else epTitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster ?: hubPoster
                        }
                    } catch (_: Exception) {
                        null
                    }
                }.distinctBy { it.data }
                    .sortedWith(compareBy({ it.season }, { it.episode }))
                if (episodes.isNotEmpty()) {
                    return newTvSeriesLoadResponse(hubTitle.ifBlank { "مسلسل" }, url, TvType.TvSeries, episodes) {
                        this.posterUrl = hubPoster
                    }
                }
            }
            if (isListing) return null
        }
        val rawTitle = cleanTitle(
            document.selectFirst("h1")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("h1")?.text()?.trim()?.substringBefore("(")?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
                ?: return null
        )
        val title = rawTitle.ifBlank { return null }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val year = Regex("""(19|20)\d{2}""").find(document.selectFirst("h1")?.text() ?: title)?.value?.toIntOrNull()
            ?: Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()

        val decoded = try {
            java.net.URLDecoder.decode(url, "UTF-8")
        } catch (_: Exception) {
            url
        }
        val isSeries = decoded.contains("مسلسل") || title.contains("مسلسل") ||
            decoded.contains("الحلقة") || title.contains("الحلقة")
        return if (isSeries) {
            val epNum = extractEpisodeNumber(title) ?: extractEpisodeNumber(decoded)
            val seasonNum = extractSeasonNumber(title) ?: extractSeasonNumber(decoded) ?: 1
            val episode = newEpisode(url) {
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
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    private fun extractQualityInt(text: String?): Int {
        val t = text?.lowercase()?.trim() ?: return Qualities.Unknown.value
        Regex("""(\d{3,4})\s*[xp]""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            t.contains("4k") || t.contains("2160") -> 2160
            t.contains("1440") -> 1440
            t.contains("1080") -> 1080
            t.contains("720") -> 720
            t.contains("480") -> 480
            t.contains("360") -> 360
            else -> getQualityFromName(text ?: "")
        }
    }

    /**
     * Generic embed harvester for hosts without a dedicated extractor:
     * unpacks Dean-Edwards packed JS and extracts direct
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
            val unpacked = try { JsUnpacker(rawHtml).unpack() ?: rawHtml } catch (_: Exception) { rawHtml }
            val html = unpacked.replace("\\/", "/")
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
                if (low.contains("recaptcha") || low.contains("google") || low.contains("youtube.com")) continue
                val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                try {
                    loadExtractor(src, embedUrl, subtitleCallback) { collected.add(it) }
                } catch (_: Exception) { }
                collected.forEach { ext ->
                    if (!seen.add(ext.url)) return@forEach
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
        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            return false
        }
        val servers = mutableListOf<Pair<String, String>>()
        document.select("button[data-embed-url]").forEach { btn ->
            val embed = btn.attr("data-embed-url").trim()
            if (embed.isBlank()) return@forEach
            val url = if (embed.startsWith("//")) "https:$embed" else embed
            if (!url.startsWith("http")) return@forEach
            val label = btn.text().trim().replace(Regex("""\s+"""), " ").takeIf { it.isNotBlank() } ?: "سيرفر مشاهدة"
            servers.add(url to label)
        }
        document.select("iframe#mainPlayer[src], iframe.player-iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isBlank() || !src.startsWith("http")) return@forEach
            if (src.contains("youtube.com") || src.contains("youtu.be")) return@forEach
            servers.add(src to "المشغل الافتراضي")
        }
        if (servers.isEmpty()) return false

        coroutineScope {
            servers.distinctBy { it.first }.mapIndexed { index, (embedUrl, serverName) ->
                async {
                    val label = if (servers.count { it.second == serverName } > 1) {
                        "$serverName ${index + 1}"
                    } else {
                        serverName
                    }
                    val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    try {
                        loadExtractor(embedUrl, data, subtitleCallback) { collected.add(it) }
                    } catch (_: Exception) { }
                    if (collected.isEmpty()) {
                        try {
                            harvestEmbed(embedUrl, label, data, subtitleCallback, callback)
                        } catch (_: Exception) { }
                    } else {
                        collected.forEach { ext ->
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$label - ${ext.name}",
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
                }
            }.awaitAll()
        }
        return true
    }
}
