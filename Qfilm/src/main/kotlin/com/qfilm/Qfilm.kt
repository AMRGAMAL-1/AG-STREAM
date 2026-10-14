package com.qfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class QfilmProvider : MainAPI() {
    override var mainUrl = "https://a.qfilm.tv"
    override var name = "Qfilm"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php" to "جديد الموقع",
        "$mainUrl/category.php?cat=2026-movies" to "أفلام 2026",
        "$mainUrl/category.php?cat=foreign-movies" to "أفلام أجنبي",
        "$mainUrl/category.php?cat=arabic-movies" to "أفلام عربي",
        "$mainUrl/category.php?cat=indian-movies" to "أفلام هندي",
        "$mainUrl/category.php?cat=turkish-movies" to "أفلام تركية",
        "$mainUrl/category.php?cat=anime-movies" to "أفلام أنيميشن",
        "$mainUrl/category.php?cat=asian-movies" to "أفلام آسيوية",
        "$mainUrl/category.php?cat=dubbed-movies" to "أفلام مدبلجة",
    )

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a[href*=watch.php]") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        if (href.isBlank()) return null
        val title = element.selectFirst("h3.caption")?.text()?.trim()
            ?: linkTag.attr("title")?.trim()
            ?: return null
        if (title.isBlank()) return null
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("data-echo")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("echo-lzld") }
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
            this.year = year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        }
        val document = app.get(url).document
        val items = document.select("ul.pm-ul-browse-videos li, ul.pm-ul-carousel-videos li")
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
        val hasNext = document.select("ul.pagination a").any {
            it.text().trim() == "${page + 1}" || it.attr("href").contains("page=${page + 1}")
        }
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search.php?keywords=$encoded").document
        return document.select("ul.pm-ul-browse-videos li").mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/search.php?keywords=$encoded"
        else "$mainUrl/search.php?keywords=$encoded&page=$page"
        val document = app.get(url).document
        val items = document.select("ul.pm-ul-browse-videos li").mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() && !it.contains("social-thumb") }
            ?: document.selectFirst(".poster")?.attr("style")?.let {
                Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1)?.trim('\'', '"', ' ')
            }?.let { fixUrl(it) }
            ?: document.selectFirst(".pm-video-thumb img, .watch-poster img")?.let {
                it.attr("data-echo").ifBlank { it.attr("src") }
            }?.let { fixUrl(it) }
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val durationText = document.selectFirst(".pm-label-duration, .duration")?.text()?.trim()
        val duration = durationText?.split(":")?.mapNotNull { it.toIntOrNull() }?.let {
            when (it.size) {
                3 -> it[0] * 3600 + it[1] * 60 + it[2]
                2 -> it[0] * 60 + it[1]
                else -> null
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.duration = duration
        }
    }

    private fun extractVid(url: String): String? {
        return Regex("""vid=([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
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
        val vid = extractVid(data) ?: return false
        val embedUrl = "$mainUrl/embed.php?vid=$vid"
        val doc = try {
            app.get(embedUrl, referer = data).document
        } catch (e: Exception) {
            return false
        }
        val options = doc.select("select#server-select option")
        if (options.isEmpty()) return false

        val servers = options.mapNotNull {
            val link = it.attr("value")?.trim() ?: return@mapNotNull null
            if (link.isBlank() || !link.startsWith("http")) return@mapNotNull null
            val label = it.text()?.trim()?.takeIf { s -> s.isNotBlank() } ?: "سيرفر"
            link to label
        }
        if (servers.isEmpty()) return false

        coroutineScope {
            servers.map { (link, label) ->
                async {
                    val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    try {
                        loadExtractor(link, embedUrl, subtitleCallback) { collected.add(it) }
                    } catch (_: Exception) { }
                    if (collected.isEmpty()) {
                        try {
                            if (!harvestEmbed(link, label, embedUrl, subtitleCallback, callback)) {
                                if (link.contains(".m3u8") || link.contains(".mp4")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = label,
                                            url = link
                                        ) {
                                            this.referer = embedUrl
                                            this.quality = getQualityFromName(label)
                                        }
                                    )
                                }
                            }
                        } catch (_: Exception) { }
                    } else {
                        collected.forEach { extLink ->
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$label - ${extLink.name}",
                                    url = extLink.url
                                ) {
                                    this.referer = extLink.referer
                                    this.headers = extLink.headers
                                    this.quality = if (extLink.quality == Qualities.Unknown.value) {
                                        getQualityFromName(label)
                                    } else extLink.quality
                                    this.type = extLink.type
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
