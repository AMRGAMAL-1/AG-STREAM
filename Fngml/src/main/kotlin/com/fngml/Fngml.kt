package com.fngml

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FngmlProvider : MainAPI() {
    override var mainUrl = "https://fngml.com"
    override var name = "Fngml"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الفيديوهات",
        "$mainUrl/egyptian-porn" to "مصري",
        "$mainUrl/arab-sex" to "عربي",
        "$mainUrl/syria-porn" to "سوري",
        "$mainUrl/lebanese-girls-porn" to "لبناني",
        "$mainUrl/iraqi-porn-2" to "عراقي",
        "$mainUrl/hijab-girls-porn" to "محجبات",
        "$mainUrl/porn-movies-translated-in-arabic" to "أفلام مترجمة",
        "$mainUrl/live-sex" to "بث مباشر",
    )

    private fun postToResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a.thumbnail-link") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.isBlank()) return null
        val title = element.selectFirst("h2.entry-title a")?.text()?.trim()
            ?: element.selectFirst("h2.entry-title")?.text()?.trim()
            ?: return null
        if (title.isBlank()) return null
        val img = element.selectFirst(".thumbnail-wrap img")?.attr("src")?.let { fixUrl(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = img
        }
    }

    private fun parseListing(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("#recent-content .post, article.post")
            .mapNotNull { postToResult(it) }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.removeSuffix("/")
        val url = if (page <= 1) request.data else "$base/page/$page"
        val document = app.get(url).document
        val items = parseListing(document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // Site search is a JS-only Google CSE block with no server-side results.
    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
            ?: return null
        if (title.isBlank()) return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("video#video-player")?.attr("poster")?.let { fixUrl(it) }
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()?.takeIf { it.length > 20 }
        val tags = document.select(".entry-category a").map { it.text().trim() }.filter { it.isNotBlank() }
        val recommendations = document.select(".entry-content a[href]").mapNotNull {
            val href = fixUrl(it.attr("href"))
            if (!href.contains("fngml.com/")) return@mapNotNull null
            if (!Regex("""/\d+/?$""").containsMatchIn(href)) return@mapNotNull null
            if (href == url) return@mapNotNull null
            val name = it.text().trim().takeIf { s -> s.length in 4..120 } ?: return@mapNotNull null
            newMovieSearchResponse(name, href, TvType.Movie)
        }.distinctBy { it.url }.take(24)
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags.ifEmpty { null }
            this.recommendations = recommendations.ifEmpty { null }
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
            else -> Qualities.Unknown.value
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
        // Direct <video><source> links (host requires the post URL as Referer -> 403 otherwise)
        val sources = document.select("video source[src], video[src]").mapNotNull {
            val src = (it.attr("src").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null).let { u -> fixUrl(u) }
            if (!src.startsWith("http")) return@mapNotNull null
            src
        }.distinct()
        // Backup: any direct mp4 links in the post body (mirrors)
        val bodyMp4 = Regex("""https?://[^\s"'<>\\]+\.mp4(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(document.html()).map { it.value }.filter { it.startsWith("http") }.distinct()
        val all = (sources + bodyMp4).distinct().filter { it.contains(".mp4") }
        if (all.isEmpty()) return false
        all.forEachIndexed { index, videoUrl ->
            val label = if (all.size > 1) "سيرفر ${index + 1}" else "مشاهدة مباشرة"
            callback.invoke(
                newExtractorLink(source = name, name = "$name - $label", url = videoUrl) {
                    this.referer = data
                    this.quality = extractQualityInt(videoUrl)
                }
            )
        }
        return true
    }
}
