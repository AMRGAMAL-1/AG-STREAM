package com.bristeg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class BrstejProvider : MainAPI() {
    override var mainUrl = "https://brstej.com"
    override var name = "برستيج"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "cat03.php?cat=movies2-2224" to "أفلام",
        "cat03.php?cat=aflamajnby3-2024" to "أفلام أجنبية",
        "cat03.php?cat=aflam02-2024" to "أفلام عربية",
        "cat03.php?cat=turkish3-movies2024" to "أفلام تركية",
        "cat03.php?cat=hindi1-moviess" to "أفلام هندية",
        "cat03.php?cat=anime1" to "أفلام أنمي",
        "cat03.php?cat=prss7-2025" to "مسلسلات برستيج",
        "cat03.php?cat=arab8-2025" to "مسلسلات عربية",
        "cat03.php?cat=eg8-2025" to "مسلسلات مصرية",
        "cat03.php?cat=syy5-2025" to "مسلسلات شامية",
        "cat03.php?cat=ty9-2025" to "مسلسلات تركية",
        "cat03.php?cat=english1-2025" to "مسلسلات أجنبية",
        "cat03.php?cat=anmei" to "مسلسلات أنمي",
        "cat03.php?cat=ramdan2026" to "مسلسلات رمضان 2026",
    )

    private fun fixUrlLocal(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (u.startsWith("/")) u = mainUrl + u
        if (!u.startsWith("http")) return null
        return u
    }

    private fun isSeriesTitle(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("مسلسل") || t.contains("حلقة") || t.contains("موسم")
    }

    private fun cardFromLink(link: Element, title: String?, poster: String?): SearchResponse? {
        val href = fixUrlLocal(link.attr("href")) ?: return null
        val name = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (isSeriesTitle(name) || href.contains("view-serie.php") || href.contains("series1.php")) {
            newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(name, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        // Category / search cards.
        document.select("article.pmc-card, article.prs-card").forEach { card ->
            try {
                val link = card.selectFirst("a.pmc-photo, a.prs-image, a[href*=watch.php?vid=]")
                    ?: return@forEach
                val title = card.selectFirst("div.pmc-caption h3 a, div.prs-caption h3 a")?.text()
                    ?: link.attr("aria-label")
                val img = card.selectFirst("picture img[src], img[src]")
                val poster = fixUrlLocal(img?.attr("src"))
                cardFromLink(link, title, poster)?.let { out.add(it) }
            } catch (_: Exception) { }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "home") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val document = app.get("$mainUrl/home03").document
            val lists = mutableListOf<HomePageList>()
            // Hero picks.
            val hero = document.select("article.pbrs-slide").mapNotNull { slide ->
                try {
                    val link = slide.selectFirst("a.pbrs-watch") ?: return@mapNotNull null
                    val title = slide.selectFirst("h2")?.text()
                    val poster = fixUrlLocal(slide.selectFirst("img.pbrs-background")?.attr("src"))
                    cardFromLink(link, title, poster)
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.url }
            if (hero.isNotEmpty()) lists.add(HomePageList("اختيارات برستيج", hero))
            // Today's series.
            val today = document.select("a.ptoday-card").mapNotNull { a ->
                try {
                    val title = a.selectFirst("span")?.text()
                    val poster = fixUrlLocal(a.selectFirst("img")?.attr("src"))
                    cardFromLink(a, title, poster)
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.url }
            if (today.isNotEmpty()) lists.add(HomePageList("مسلسلات اليوم", today))
            return newHomePageResponse(lists)
        }
        val document = try {
            app.get("${mainUrl.trimEnd('/')}/${request.data.trimStart('/')}").document
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = parseListing(document)
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search.php?keywords=$encoded").document
        return parseListing(document)
    }

    private fun episodeNumberOf(title: String?): Int? {
        if (title.isNullOrBlank()) return null
        return Regex("""الحلقة\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse? {
        // Series hub page.
        if (url.contains("view-serie.php") || url.contains("series1.php")) {
            return loadSeriesPage(url)
        }
        val document = try {
            app.get(url, timeout = 15).document
        } catch (e: Exception) {
            return null
        }
        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { fixUrlLocal(it) }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }
        val vid = Regex("vid=([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
        val playerUrl = if (vid != null) "$mainUrl/play.php?vid=$vid" else url

        if (!isSeriesTitle(title)) {
            return newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        // Episode page: climb to the parent series via breadcrumbs.
        val seriesUrl = document.select("nav[class*=breadcrumb] a").mapNotNull {
            val href = fixUrlLocal(it.attr("href")) ?: return@mapNotNull null
            if (href.contains("view-serie.php") || href.contains("series1.php")) href else null
        }.lastOrNull()
        if (seriesUrl != null) {
            return loadSeriesPage(seriesUrl)
        }
        val episode = newEpisode(playerUrl) {
            this.name = title
            this.season = 1
            this.episode = episodeNumberOf(title) ?: 1
            this.posterUrl = poster
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun loadSeriesPage(url: String): LoadResponse? {
        val document = try {
            app.get(url, timeout = 15).document
        } catch (e: Exception) {
            return null
        }
        val title = document.selectFirst("h1#pds-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst(".pds-art img")?.attr("src")
            ?.let { fixUrlLocal(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.let { fixUrlLocal(it) }
        val plot = document.selectFirst(".pds-description")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val episodes = mutableListOf<Episode>()
        document.select("section.pds-season").forEach { section ->
            val seasonNum = section.attr("id").substringAfter("pds-season-").toIntOrNull()
                ?: section.selectFirst(".pds-season-heading")?.text()
                    ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: 1
            section.select("article.pds-episode").forEach { article ->
                val link = article.selectFirst("h4 a[href], a.pds-thumb[href]") ?: return@forEach
                val href = fixUrlLocal(link.attr("href")) ?: return@forEach
                val epTitle = link.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                    ?: link.text().trim().takeIf { it.isNotBlank() }
                    ?: "الحلقة"
                val epNum = episodeNumberOf(link.attr("title") + " " + link.text())
                val vid = Regex("vid=([A-Za-z0-9]+)").find(href)?.groupValues?.get(1)
                val data = if (vid != null) "$mainUrl/play.php?vid=$vid" else href
                episodes.add(newEpisode(data) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
        }
        if (episodes.isEmpty()) {
            // Fallback: any watch links on the page.
            document.select("a[href*=watch.php?vid=]").forEach { a ->
                val href = fixUrlLocal(a.attr("href")) ?: return@forEach
                val vid = Regex("vid=([A-Za-z0-9]+)").find(href)?.groupValues?.get(1) ?: return@forEach
                episodes.add(newEpisode("$mainUrl/play.php?vid=$vid") {
                    this.name = a.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                        ?: a.text().trim().takeIf { it.isNotBlank() }
                        ?: "الحلقة"
                    this.season = 1
                    this.posterUrl = poster
                })
            }
        }
        if (episodes.isEmpty()) return null
        val sorted = episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
        sorted.forEachIndexed { index, ep ->
            if (ep.episode == null) ep.episode = index + 1
            if (ep.season == null) ep.season = 1
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
            this.posterUrl = poster
            this.plot = plot
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
        val pageUrl = data.substringBefore("#")
        val document = try {
            app.get(pageUrl, timeout = 15).document
        } catch (e: Exception) {
            return false
        }
        val servers = mutableListOf<Pair<String, String>>()
        document.select("button.watchButton[data-embed-url]").forEach { btn ->
            val embed = btn.attr("data-embed-url").trim()
            if (embed.isBlank()) return@forEach
            val url = if (embed.startsWith("//")) "https:$embed" else embed
            if (!url.startsWith("http")) return@forEach
            val label = btn.text().trim().replace(Regex("""\s+"""), " ")
                .takeIf { it.isNotBlank() } ?: "سيرفر مشاهدة"
            servers.add(url to label)
        }
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isBlank() || src.startsWith("data:")) return@forEach
            var url = src
            if (url.startsWith("//")) url = "https:$url"
            if (!url.startsWith("http")) return@forEach
            val low = url.lowercase()
            if (low.contains("recaptcha") || low.contains("googletagmanager")) return@forEach
            servers.add(url to "المشغل الافتراضي")
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
                        loadExtractor(embedUrl, pageUrl, subtitleCallback) { collected.add(it) }
                    } catch (_: Exception) { }
                    if (collected.isEmpty()) {
                        try {
                            harvestEmbed(embedUrl, label, pageUrl, subtitleCallback, callback)
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
