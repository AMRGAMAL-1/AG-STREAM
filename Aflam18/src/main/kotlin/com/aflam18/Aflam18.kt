package com.aflam18

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Aflam18Provider : MainAPI() {
    override var mainUrl = "https://aflam18.media"
    override var name = "Aflam18"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الأفلام",
        "$mainUrl/category/افلام-اجنبي-للكبار-فقط/" to "أفلام أجنبي",
        "$mainUrl/category/افلام-اسيوية-للكبار-فقط/" to "أفلام آسيوية",
    )

    private fun postToResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a.thumbnail-link") ?: element.selectFirst("h2.entry-title a") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.isBlank()) return null
        val title = element.selectFirst("h2.entry-title a")?.text()?.trim()
            ?: element.selectFirst("h2.entry-title")?.text()?.trim()
            ?: link.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        if (title.isBlank()) return null
        val img = element.selectFirst(".thumbnail-wrap img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = img
            this.year = year
        }
    }

    private fun parseListing(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val posts = document.select("#recent-content .post, article.post").mapNotNull { postToResult(it) }
        if (posts.isNotEmpty()) return posts.distinctBy { it.url }
        // Fallback: widget cards (thumbnail-link + entry-wrap)
        return document.select("a.thumbnail-link[href]").mapNotNull { a ->
            val href = fixUrl(a.attr("href"))
            if (href.isBlank() || href == mainUrl || href == "$mainUrl/") return@mapNotNull null
            val li = a.closest("li") ?: a.parent() ?: a
            val title = li.selectFirst(".entry-wrap a")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val img = a.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = img
                this.year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
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

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val title = rawTitle.substringBefore(" | ").substringBefore(" - ").trim().ifBlank { rawTitle }
        if (title.isBlank()) return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst(".movie-meta-poster img")?.attr("src")?.let { fixUrl(it) }
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()?.takeIf { it.length > 20 }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
            ?: document.selectFirst(".meta-item.release-year .meta-value")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags = document.select(".meta-item.genre .meta-value a, .entry-category a").map { it.text().trim() }.filter { it.isNotBlank() }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.ifEmpty { null }
        }
    }

    /** video-tabs-generator obfuscation: "v1:" + base64(XOR(bytes, "vTg2026#kx")) */
    private fun vtgDecode(v: String): String {
        if (!v.startsWith("v1:")) return v
        return try {
            val bytes = Base64.decode(v.removePrefix("v1:"), Base64.DEFAULT)
            val key = "vTg2026#kx".toByteArray(Charsets.UTF_8)
            val out = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
            String(out, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            v
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
        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            return false
        }
        val servers = mutableListOf<Pair<String, String>>()
        // Video tabs: <a class="vtg-tab" data-video-url="v1:...">ServerName</a>
        document.select("a.vtg-tab[data-video-url], .vtg-tabs-list a[data-video-url]").forEach { a ->
            val raw = a.attr("data-video-url").trim()
            if (raw.isBlank()) return@forEach
            val decoded = vtgDecode(raw).trim()
            if (!decoded.startsWith("http") && !decoded.startsWith("//")) return@forEach
            val label = a.text().trim().takeIf { it.isNotBlank() } ?: "سيرفر مشاهدة"
            val url = if (decoded.startsWith("//")) "https:$decoded" else decoded
            servers.add(url to label)
        }
        // Backup: JSON blob in <script class="vtg-video-urls">
        document.select("script.vtg-video-urls").forEach { script ->
            try {
                parseJson<List<String>>(script.data()).forEach { raw ->
                    val decoded = vtgDecode(raw.trim()).trim()
                    if (decoded.startsWith("http") || decoded.startsWith("//")) {
                        val url = if (decoded.startsWith("//")) "https:$decoded" else decoded
                        servers.add(url to "سيرفر مشاهدة")
                    }
                }
            } catch (_: Exception) { }
        }
        if (servers.isEmpty()) return false

        coroutineScope {
            servers.distinctBy { it.first }.map { (embedUrl, serverName) ->
                async {
                    val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    try {
                        loadExtractor(embedUrl, data, subtitleCallback) { collected.add(it) }
                    } catch (_: Exception) { }
                    if (collected.isEmpty()) {
                        try {
                            harvestEmbed(embedUrl, serverName, data, subtitleCallback, callback)
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
                }
            }.awaitAll()
        }
        return true
    }
}
