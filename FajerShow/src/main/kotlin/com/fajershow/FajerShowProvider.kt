package com.fajershow

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FajerShowProvider : MainAPI() {
    override var mainUrl = "https://fajer.show"
    override var name = "FajerShow"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "FajerShow"
    private val cfKiller by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/genre/arabic-movies/page/" to "أفلام عربية",
        "$mainUrl/genre/english-movies/page/" to "أفلام أجنبية",
        "$mainUrl/genre/turkish-movies/page/" to "أفلام تركية",
        "$mainUrl/genre/arabic-series/page/" to "مسلسلات عربية",
        "$mainUrl/genre/english-series/page/" to "مسلسلات أجنبية",
        "$mainUrl/genre/turkish-series/page/" to "مسلسلات تركية",
        "$mainUrl/genre/indian-series/page/" to "مسلسلات هندية",
        "$mainUrl/genre/animation/page/" to "أنمي",
    )

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    private fun Element.toCardResult(): SearchResponse? {
        val card = this
        val link = card.selectFirst("a[href]")?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        if (link.contains("/genre/") || link.contains("/category/")) return null
        val fullUrl = fixUrl(link)
        var title = card.selectFirst("h2, h3, .title, .name")?.text()?.trim()
        if (title.isNullOrBlank()) title = card.selectFirst("a[title]")?.attr("title")?.trim()
        if (title.isNullOrBlank()) return null
        var poster = card.selectFirst("div.poster > img")?.let {
            it.attr("data-src").takeIf { v -> v.isNotBlank() } ?: it.attr("src").takeIf { v -> v.isNotBlank() }
        } ?: card.selectFirst("img.lazy")?.let {
            it.attr("data-src").takeIf { v -> v.isNotBlank() } ?: it.attr("src").takeIf { v -> v.isNotBlank() }
        } ?: card.selectFirst("img")?.let {
            it.attr("data-src").takeIf { v -> v.isNotBlank() } ?: it.attr("src").takeIf { v -> v.isNotBlank() }
        }
        if (poster != null) poster = fixUrl(poster)
        val year = Regex("""(19\d{2}|20\d{2})""").find(card.text())?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(link)?.groupValues?.get(1)?.toIntOrNull()
        val low = link.lowercase()
        val isTv = low.contains("/serie") || low.contains("series") || low.contains("مسلسل") ||
            title.contains("مسلسل") || card.text().contains("حلقة")
        return if (isTv) {
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        var items = doc.select("article.item").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }
        if (items.isEmpty()) {
            items = doc.select(".result-item > article, .owl-item article, article").mapNotNull {
                try { it.toCardResult() } catch (_: Exception) { null }
            }
        }
        return items.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        Log.d(TAG, "getMainPage ${request.name} -> $url")
        return try {
            val doc = app.get(url, referer = mainUrl, interceptor = cfKiller, timeout = 25).document
            newHomePageResponse(request.name, parseCards(doc))
        } catch (e: Exception) {
            Log.d(TAG, "getMainPage ${request.name} failed: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val url = "$mainUrl/?s=$enc"
        Log.d(TAG, "search -> $url")
        return try {
            val doc = app.get(url, referer = mainUrl, interceptor = cfKiller, timeout = 25).document
            parseCards(doc)
        } catch (e: Exception) {
            Log.d(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load $url")
        val doc = app.get(url, referer = mainUrl, interceptor = cfKiller, timeout = 25).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore("|").substringBefore("-").trim().takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("No title for $url")
        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div.poster > img, div.poster img")?.let {
                it.attr("data-src").takeIf { v -> v.isNotBlank() } ?: it.attr("src").takeIf { v -> v.isNotBlank() }
            }
        if (poster != null) poster = fixUrl(poster)
        val plot = doc.selectFirst("div.wp-content > p, .description p, .story p, div.entry-content p")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val year = doc.selectFirst(".year, span.year, a[href*=release]")?.text()
            ?.filter { it.isDigit() }?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("a[href*=genre], a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(10)

        val epLinks = doc.select("a[href]").map { it.attr("href") }.filter { h ->
            h.isNotBlank() && (h.contains("/episode/", true) || h.contains("episode-", true)) &&
                !h.contains("/genre/") && !h.contains("actor")
        }.distinct()
        if (epLinks.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            epLinks.forEachIndexed { idx, h ->
                val full = fixUrl(h)
                val epNum = Regex("""episode[-/](\d+)""", RegexOption.IGNORE_CASE).find(h)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                episodes.add(newEpisode(full) {
                    this.name = "الحلقة $epNum"
                    this.episode = epNum
                    this.season = 1
                    this.posterUrl = poster
                })
            }
            Log.d(TAG, "show $title -> ${episodes.size} episodes")
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags.ifEmpty { null }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks $data")
        return try {
            val doc = app.get(data, referer = mainUrl, interceptor = cfKiller, timeout = 25).document
            var emitted = 0
            val files = Regex("""["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
                .findAll(doc.html()).map { it.groupValues[1] }.distinct().toList()
            for (f in files) {
                try {
                    callback(newExtractorLink(source = this.name, name = "$name Server", url = f) {
                        this.referer = data
                        this.quality = getQualityFromName(f)
                        this.type = if (f.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    })
                    emitted++
                } catch (_: Exception) {}
            }
            doc.select("iframe[src]").forEach { fr ->
                var src = fr.attr("src").trim()
                if (src.isBlank() || src.contains("youtube", true)) return@forEach
                if (src.startsWith("//")) src = "https:$src"
                else if (src.startsWith("/")) src = fixUrl(src)
                if (!src.startsWith("http")) return@forEach
                try {
                    loadExtractor(src, data, subtitleCallback) {
                        emitted++
                        callback(it)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "extractor failed $src: ${e.message}")
                }
            }
            Log.d(TAG, "loadLinks emitted=$emitted")
            true
        } catch (e: Exception) {
            Log.d(TAG, "loadLinks error: ${e.message}")
            true
        }
    }
}
