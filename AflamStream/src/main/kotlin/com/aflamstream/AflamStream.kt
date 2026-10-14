package com.aflamstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AflamStreamProvider : MainAPI() {
    override var mainUrl = "https://aflamstream.com"
    override var name = "AflamStream"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/category/مشاهدة-افلام-مترجمة-للعربي-اونلاين/" to "كل الأفلام",
        "$mainUrl/category/أفلام-أجنبية-مترجمة-للعربي/" to "أفلام أجنبية",
        "$mainUrl/category/أفلام-تركية-مترجمة-ومدبلجة/" to "أفلام تركية",
        "$mainUrl/category/أفلام-هندية-مترجمة/" to "أفلام هندية",
        "$mainUrl/category/أفلام-كرتون-مترجمة-ومدبلجة/" to "أفلام كرتون",
        "$mainUrl/category/مسلسلات-أجنبية-مترجمة-كاملة-أون-لاين/" to "مسلسلات أجنبية",
        "$mainUrl/category/مسلسلات-تركية-مترجمة-ومدبلجة/" to "مسلسلات تركية",
        "$mainUrl/category/مسلسلات-عربية-كاملة-وجديدة/" to "مسلسلات عربية",
        "$mainUrl/category/أحدث-حلقات-المسلسلات-المترجمة/" to "أحدث الحلقات",
    )

    private fun msCardToResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a.ms-card-link") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.isBlank()) return null
        val title = link.selectFirst("h3.ms-title")?.text()?.trim() ?: return null
        if (title.isBlank()) return null
        val img = link.selectFirst(".ms-poster img")?.attr("src")
        val year = link.selectFirst(".ms-year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val isSeries = title.contains("مسلسل") || title.contains("الحلقة") || title.contains("الموسم")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = img?.let { fixUrl(it) }
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = img?.let { fixUrl(it) }
                this.year = year
            }
        }
    }

    private fun postToResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a.thumbnail-link") ?: element.selectFirst("h2.entry-title a") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.isBlank()) return null
        val title = element.selectFirst("h2.entry-title a")?.text()?.trim()
            ?: element.selectFirst("h2.entry-title")?.text()?.trim()
            ?: link.attr("title")?.trim()
            ?: return null
        if (title.isBlank()) return null
        val img = element.selectFirst("img")?.attr("src")
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val isSeries = title.contains("مسلسل") || title.contains("الحلقة") || title.contains("الموسم")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = img?.let { fixUrl(it) }
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = img?.let { fixUrl(it) }
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        if (baseUrl == "$mainUrl/" || baseUrl == mainUrl) {
            if (page > 1) {
                val doc = app.get("$mainUrl/page/$page/").document
                val items = doc.select("#recent-content .post, article.post").mapNotNull { postToResult(it) }
                    .distinctBy { it.url }
                return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            val document = app.get(mainUrl).document
            val sections = document.select(".ms-slider-section")
            val lists = mutableListOf<HomePageList>()
            sections.forEach { section ->
                val sectionTitle = section.selectFirst(".ms-section-title")?.text()?.trim() ?: return@forEach
                val cards = section.select(".ms-card").mapNotNull { msCardToResult(it) }.distinctBy { it.url }
                if (cards.isNotEmpty()) lists.add(HomePageList(sectionTitle, cards))
            }
            if (lists.isNotEmpty()) return newHomePageResponse(lists)
            val fallback = document.select("#recent-content .post, article.post").mapNotNull { postToResult(it) }
            return newHomePageResponse(request.name, fallback)
        }
        val url = if (page <= 1) baseUrl else "${baseUrl.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val msItems = document.select(".ms-card").mapNotNull { msCardToResult(it) }
        val postItems = document.select("#recent-content .post, article.post").mapNotNull { postToResult(it) }
        val items = (msItems + postItems).distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        val msItems = document.select(".ms-card").mapNotNull { msCardToResult(it) }
        val postItems = document.select("#recent-content .post, article.post").mapNotNull { postToResult(it) }
        return (msItems + postItems).distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url).document
        val msItems = document.select(".ms-card").mapNotNull { msCardToResult(it) }
        val postItems = document.select("#recent-content .post, article.post").mapNotNull { postToResult(it) }
        val items = (msItems + postItems).distinctBy { it.url }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    private fun extractPostId(document: org.jsoup.nodes.Document, url: String): String? {
        document.selectFirst("link[rel=shortlink]")?.attr("href")?.let {
            Regex("""[?&]p=(\d+)""").find(it)?.groupValues?.get(1)?.let { id -> return id }
        }
        document.selectFirst("link[type=application/json]")?.attr("href")?.let {
            Regex("""/posts/(\d+)""").find(it)?.groupValues?.get(1)?.let { id -> return id }
        }
        Regex("""/(\d+)/?$""").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val postId = extractPostId(document, url) ?: return null
        val watchUrl = "$mainUrl/watch/?id=$postId"

        val isSeries = title.contains("مسلسل") || title.contains("الحلقة") || title.contains("الموسم")
        return if (isSeries) {
            val epNum = Regex("""الحلقة\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("""الموسم\s*(الأول|الثاني|الثالث|الرابع|الخامس|\d+)""").find(title)?.groupValues?.get(1)?.let {
                when (it) {
                    "الأول" -> 1; "الثاني" -> 2; "الثالث" -> 3; "الرابع" -> 4; "الخامس" -> 5
                    else -> it.toIntOrNull() ?: 1
                }
            } ?: 1
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
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
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
        val buttons = watchDoc.select("a.server-btn.watch[data-url], button.server-btn.watch[data-url]")
        val iframeSrc = watchDoc.selectFirst("iframe#video-frame")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        if (buttons.isEmpty()) {
            if (!iframeSrc.isNullOrBlank()) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                return true
            }
            return false
        }

        coroutineScope {
            buttons.map { btn ->
                async {
                    val rawUrl = btn.attr("data-url")?.trim() ?: return@async
                    if (rawUrl.isBlank()) return@async
                    val serverLabel = btn.text()?.trim()?.takeIf { it.isNotBlank() } ?: "سيرفر مشاهدة"
                    val qualityHint = when {
                        serverLabel.contains("عالية") -> 1080
                        serverLabel.contains("متعدد الجودة") -> Qualities.Unknown.value
                        serverLabel.contains("متوسطة") -> 720
                        else -> getQualityFromName(serverLabel)
                    }
                    try {
                        val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        try {
                            loadExtractor(rawUrl, data, subtitleCallback) { collected.add(it) }
                        } catch (_: Exception) { }
                        if (collected.isEmpty() && (rawUrl.contains(".m3u8") || rawUrl.contains(".mp4"))) {
                            callback.invoke(
                                newExtractorLink(source = name, name = serverLabel, url = rawUrl) {
                                    this.referer = data
                                    this.quality = qualityHint
                                }
                            )
                        } else {
                            collected.forEach { ext ->
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverLabel - ${ext.name}",
                                        url = ext.url
                                    ) {
                                        this.referer = ext.referer
                                        this.headers = ext.headers
                                        this.quality = if (ext.quality == Qualities.Unknown.value && qualityHint != Qualities.Unknown.value) qualityHint else ext.quality
                                        this.type = ext.type
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) { }
                }
            }.awaitAll()
        }
        if (!iframeSrc.isNullOrBlank()) {
            try {
                val collectedDefault = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                try {
                    loadExtractor(iframeSrc, data, subtitleCallback) { collectedDefault.add(it) }
                } catch (_: Exception) { }
                collectedDefault.forEach { ext ->
                    callback.invoke(
                        newExtractorLink(source = name, name = "سيرفر افتراضي - ${ext.name}", url = ext.url) {
                            this.referer = ext.referer
                            this.headers = ext.headers
                            this.quality = ext.quality
                            this.type = ext.type
                        }
                    )
                }
            } catch (_: Exception) { }
        }
        return true
    }
}
