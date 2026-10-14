package com.ddramacafe

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DDramaCafeProvider : MainAPI() {
    override var mainUrl = "https://ddramacafe-tv.casa"
    override var name = "دراما كافيه"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "newvideos.php" to "الأحدث",
        "moslslat.php" to "مسلسلات",
        "category.php?cat=aflam-araby2" to "أفلام عربي",
        "category.php?cat=moslslat-turkya" to "مسلسلات تركية",
        "category.php?cat=Ramdan-Series-2026" to "رمضان 2026",
        "topvideos.php" to "الأكثر مشاهدة",
    )

    private fun cardToResult(card: Element): SearchResponse? {
        val link = card.selectFirst("a[href*=watch.php?vid=]") ?: return null
        val href = fixUrl(link.attr("href").trim())
        if (href.isBlank()) return null
        val title = card.selectFirst("div.caption h3 a")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val img = card.selectFirst("div.pm-video-thumb img, img")
        var poster = img?.attr("data-echo")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        if (poster != null && poster.contains("echo-lzld.png")) poster = null
        if (poster != null) poster = fixUrl(poster)
        return if (isSeriesTitle(title)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun isSeriesTitle(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("مسلسل") || t.contains("حلقة") || t.contains("برنامج")
    }

    private fun parseListing(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("ul.pm-ul-browse-videos li, ul.pm-ul-carousel-videos li")
            .mapNotNull {
                try {
                    cardToResult(it)
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}${sep}page=$page"
        val document = app.get(url).document
        val items = parseListing(document)
        val hasNext = document.selectFirst("ul.pagination a") != null && items.isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search.php?keywords=$encoded").document
        return parseListing(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val vid = Regex("vid=([A-Za-z0-9]+)").find(url)?.groupValues?.get(1) ?: return null
        val document = app.get(url).document
        val title = document.selectFirst("div.caption h1, h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { fixUrl(it) }
        val playerUrl = "$mainUrl/play.php?vid=$vid"
        return if (isSeriesTitle(title)) {
            val episode = newEpisode(playerUrl) {
                this.name = title
                this.season = 1
                this.episode = 1
                this.posterUrl = poster
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
                this.posterUrl = poster
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
        document.select("ul.WatchList li[data-embed-url]").forEach { li ->
            val embed = li.attr("data-embed-url").trim()
            if (embed.isBlank()) return@forEach
            val url = if (embed.startsWith("//")) "https:$embed" else embed
            if (!url.startsWith("http")) return@forEach
            val label = li.selectFirst("strong")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: li.text().trim().replace(Regex("""\s+"""), " ")
                    .takeIf { it.isNotBlank() } ?: "سيرفر مشاهدة"
            servers.add(url to label)
        }
        document.selectFirst("#Playerholder iframe[src]")?.let { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http")) servers.add(src to "المشغل الافتراضي")
        }
        document.select("ul.downloadlist li[data-download-url]").forEach { li ->
            val dl = li.attr("data-download-url").trim()
            if (dl.isBlank()) return@forEach
            val url = if (dl.startsWith("//")) "https:$dl" else dl
            if (!url.startsWith("http")) return@forEach
            servers.add(url to "تحميل")
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
