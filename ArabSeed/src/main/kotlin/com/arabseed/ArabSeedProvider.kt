package com.arabseed

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ArabSeedProvider : MainAPI() {
    override var mainUrl = "https://www.arabseed.wine"
    override var name = "ArabSeed"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val TAG = "ArabSeed"
    private val cfKiller by lazy { CloudflareKiller() }
    private val seededHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/films/foreign-movies/" to "أفلام أجنبية",
        "$mainUrl/category/films/asian-movies/" to "أفلام آسيوية",
        "$mainUrl/category/films/turkish-movies/" to "أفلام تركية",
        "$mainUrl/category/films/korean-movies/" to "أفلام كورية",
        "$mainUrl/category/tv/foreign-series/" to "مسلسلات أجنبية",
        "$mainUrl/category/tv/turkish-series/" to "مسلسلات تركية",
        "$mainUrl/category/tv/korean-series/" to "مسلسلات كورية",
        "$mainUrl/category/anime/anime-movies/" to "أفلام أنمي",
    )

    private suspend fun getDoc(url: String, referer: String? = null) =
        app.get(url, headers = browserHeaders, referer = referer ?: mainUrl, interceptor = cfKiller)

    /** Placeholder/sample videos some mirrors embed (e.g. VOE's 10s Big Buck Bunny):
     *  must never be emitted as playable links. */
    private fun isSampleUrl(u: String): Boolean {
        val l = u.lowercase()
        return l.contains("test-videos.co.uk") || l.contains("bigbuckbunny") ||
            l.contains("sample.mp4") || l.contains("sample-") || l.contains("placeholder") ||
            l.contains("dummy.mp4")
    }

    private fun voeRot13(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(when {
                c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                else -> c
            })
        }
        return sb.toString()
    }

    private fun b64decodeSafe(s: String): ByteArray? {
        return try {
            var t = s.trim()
            while (t.length % 4 != 0) t += "="
            Base64.decode(t, Base64.DEFAULT)
        } catch (_: Exception) { null }
    }

    /** VOE mirrors: real stream is an obfuscated <script type="application/json"> blob. */
    private fun voeDecodeSource(blob: String): String? {
        try {
            var s = voeRot13(blob)
            for (t in listOf("@\$", "^^", "~@", "%?", "*~", "!!", "#&")) s = s.replace(t, "_")
            s = s.split("_").joinToString("")
            val b1 = b64decodeSafe(s) ?: return null
            if (b1.isEmpty()) return null
            val shifted = b1.map { (((it.toInt() and 0xFF) - 3).toChar()) }.joinToString("").reversed()
            val b2 = b64decodeSafe(shifted) ?: return null
            val json = String(b2, Charsets.UTF_8)
            val src = Regex(""""source"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.trim()?.takeIf { it.startsWith("http") } ?: return null
            if (isSampleUrl(src)) return null
            return src
        } catch (_: Exception) { return null }
    }

    /** JS-planted cookies (e.g. liiivideo's $.cookie('file_id'/'aff')): forward them
     *  so the file CDN doesn't 403 the player/proxy request. */
    private fun pageCookies(body: String): String {
        return Regex("""\$\.cookie\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]*)['"]""")
            .findAll(body)
            .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
            .distinct().joinToString("; ")
    }

    /** Browser-faithful headers for harvested file links (anti-403): mirror a
     *  Chromium XHR (fetch of the m3u8 by the embed player) as closely as
     *  possible — some file CDNs reject requests missing Sec-Fetch-*. */
    private fun linkHeaders(base: String, body: String): Map<String, String> {
        val origin = "https://" + base.substringAfter("://").substringBefore('/')
        val m = mutableMapOf(
            "Referer" to base,
            "Origin" to origin,
            "User-Agent" to (browserHeaders["User-Agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"),
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\""
        )
        val ck = pageCookies(body)
        if (ck.isNotBlank()) m["Cookie"] = ck
        return m
    }

    private fun Element.toCardResult(): SearchResponse? {
        val links = this.select("a[href]").map { it.attr("href") }.filter { it.isNotBlank() }
        val link = links.firstOrNull { h ->
            !h.contains("/category/") && !h.contains("/tag/") && !h.contains("/author/") &&
                !h.startsWith("#") && !h.contains("?s=") && h != "/" &&
                !h.contains("facebook") && !h.contains("twitter") && !h.contains("telegram") &&
                !h.contains("whatsapp") && !h.contains("dmca")
        } ?: return null
        val fullUrl = fixUrl(link)

        var title = this.selectFirst("img")?.attr("alt")?.trim()
        if (title.isNullOrBlank()) title = this.selectFirst("h2, h3, h4")?.text()?.trim()
        if (title.isNullOrBlank()) title = this.selectFirst("a[title]")?.attr("title")?.trim()
        if (title.isNullOrBlank()) {
            title = this.text().trim().take(90).ifBlank { null }
        }
        if (title.isNullOrBlank() || title.length < 2) return null

        var poster = this.selectFirst("img")?.let { img ->
            listOf("data-src", "data-lazy-src", "data-original", "src").mapNotNull { a ->
                img.attr(a).trim().takeIf { it.isNotBlank() }
            }.firstOrNull()
        }
        if (poster != null) poster = fixUrl(poster)

        val year = Regex("""(19\d{2}|20\d{2})""").find(link)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        return newMovieSearchResponse(title, fullUrl, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        Log.d(TAG, "getMainPage ${request.name} -> $url")
        val doc = getDoc(url).document
        var items = doc.select("article").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }
        if (items.isEmpty()) {
            items = doc.select("a[href]").mapNotNull { a ->
                try {
                    if (a.selectFirst("img") == null) return@mapNotNull null
                    val wrap = a.parent()?.parent()
                    (wrap as? Element)?.toCardResult() ?: a.toCardResultSingle()
                } catch (_: Exception) { null }
            }.distinctBy { it.url }
        } else {
            items = items.distinctBy { it.url }
        }
        Log.d(TAG, "getMainPage ${request.name} found ${items.size}")
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toCardResultSingle(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (href.contains("/category/") || href.contains("/tag/") || href.startsWith("#")) return null
        val fullUrl = fixUrl(href)
        val title = this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = this.selectFirst("img")?.let { img ->
            listOf("data-src", "data-lazy-src", "src").mapNotNull { a ->
                img.attr(a).trim().takeIf { it.isNotBlank() }
            }.firstOrNull()?.let { fixUrl(it) }
        }
        return newMovieSearchResponse(title, fullUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val url = "$mainUrl/?s=$enc"
        Log.d(TAG, "search -> $url")
        val doc = getDoc(url).document
        var items = doc.select("article").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }
        if (items.isEmpty()) {
            items = doc.select("a[href]").mapNotNull { a ->
                try {
                    if (a.selectFirst("img") == null) return@mapNotNull null
                    a.toCardResultSingle()
                } catch (_: Exception) { null }
            }
        }
        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load $url")
        val doc = getDoc(url).document

        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        var rawTitle = doc.selectFirst("h1")?.text()?.trim()
        if (rawTitle.isNullOrBlank()) rawTitle = doc.title().substringBefore("|").substringBefore("–").trim()
        val finalTitle = rawTitle!!.trim()
        if (finalTitle.isBlank()) throw ErrorLoadingException("No title for $url")

        val poster = ogImage?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: doc.selectFirst(".post-thumbnail img, .entry-content img")?.let { img ->
                listOf("data-src", "src").mapNotNull { a ->
                    img.attr(a).trim().takeIf { it.isNotBlank() }
                }.firstOrNull()?.let { fixUrl(it) }
            }
        val plot = doc.selectFirst(".entry-content p, .post-story, [class*=story]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: ogDesc?.takeIf { it.isNotBlank() }
        val year = Regex("""(19\d{2}|20\d{2})""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(finalTitle)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("a[rel=tag], a[href*=\"/category/\"]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(10)

        val recommendations = doc.select("article").mapNotNull { a ->
            try { a.toCardResult() } catch (_: Exception) { null }
        }.filter { it.url != url }.distinctBy { it.url }.take(24)
        val watchUrl = doc.selectFirst("a.watch__btn[href], a[href$=\"/watch/\"]")?.attr("href")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        if (!watchUrl.isNullOrBlank()) {
            Log.d(TAG, "post $finalTitle -> watch $watchUrl")
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations.ifEmpty { null }
            }
        }
        val epAnchors = doc.select("a[href]").filter { a ->
            val h = a.attr("href")
            val t = a.text()
            h.isNotBlank() && (h.contains("episode") || h.contains("/ep/") || t.contains("الحلقة"))
        }.map { it.attr("href") }.filter { it.isNotBlank() }.distinct()
            .map { fixUrl(it) }.filter { it != url && !it.contains("/category/") }

        if (epAnchors.isEmpty()) {
            Log.d(TAG, "movie $finalTitle (no watch btn)")
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations.ifEmpty { null }
            }
        }

        val episodes = epAnchors.mapIndexed { idx, epUrl ->
            val epNum = Regex("""الحلقة\s*(\d+)""").find(
                doc.select("a[href]").firstOrNull { fixUrl(it.attr("href")) == epUrl }?.text() ?: ""
            )?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d{1,4})""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: (idx + 1)
            newEpisode(epUrl) {
                this.name = "الحلقة $epNum"
                this.episode = epNum
                this.season = 1
                this.posterUrl = poster
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        Log.d(TAG, "show $finalTitle -> ${episodes.size} episodes")
        return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations.ifEmpty { null }
        }
    }

    private fun extractQuality(label: String?): Int {
        val clean = label?.lowercase()?.trim() ?: return Qualities.Unknown.value
        val digits = clean.filter { it.isDigit() }.toIntOrNull()
        if (digits != null && digits >= 144 && digits <= 4320) return digits
        return when {
            clean.contains("4k") || clean.contains("2160") -> 2160
            clean.contains("fhd") || clean.contains("1080") -> 1080
            clean.contains("hd") || clean.contains("720") -> 720
            clean.contains("sd") || clean.contains("480") -> 480
            else -> Qualities.Unknown.value
        }
    }

    /** Generic packed-host resolver: fetch page, unpack JS, hunt m3u8/mp4. */
    private suspend fun resolvePacked(
        eurl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val edoc = getDoc(eurl, referer).document
            val found = linkedSetOf<String>()
            edoc.select("iframe[src]").forEach { f ->
                val s = f.attr("src").trim()
                if (s.isNotBlank() && !s.contains("youtube")) {
                    found.add(if (s.startsWith("//")) "https:$s" else fixUrl(s))
                }
            }
            val raw = edoc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
            val scripts = try { JsUnpacker(raw).unpack() ?: raw } catch (_: Exception) { raw }
            Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(scripts).forEach {
                found.add(it.groupValues[1].replace("\\/", "/"))
            }
            Log.d(TAG, "packed $eurl -> $found")
            var ok = false
            for (u in found) {
                if (u == eurl) continue
                try {
                    if (u.contains(".m3u8") || u.contains(".mp4")) {
                        val isM3u8 = u.contains(".m3u8")
                        callback(newExtractorLink(source = this.name, name = "$name Server", url = u) {
                            this.referer = eurl
                            this.quality = Qualities.Unknown.value
                            this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        })
                        ok = true
                    } else {
                        loadExtractor(u, eurl, subtitleCallback, callback)
                        ok = true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "packed target failed $u: ${e.message}")
                }
            }
            ok
        } catch (e: Exception) {
            Log.d(TAG, "packed resolve failed $eurl: ${e.message}")
            false
        }
    }

    private val embedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\""
    )

    /** Watch servers via the site's own AJAX: POST get__watch__server/ per server index. */
    private data class AsAjaxResp(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("message") val message: String? = null
    )

    private suspend fun ajaxServerUrl(
        postId: String,
        quality: String,
        serverIdx: String,
        csrf: String,
        referer: String
    ): String? {
        return try {
            val res = app.post(
                "${mainUrl}/get__watch__server/",
                headers = browserHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = referer,
                interceptor = cfKiller,
                timeout = 20,
                data = mapOf(
                    "post_id" to postId,
                    "quality" to quality,
                    "server" to serverIdx,
                    "csrf_token" to csrf
                )
            )
            val parsed = try { AppUtils.parseJson<AsAjaxResp>(res.text) } catch (_: Exception) { null }
            if (parsed?.type == "success") parsed.server?.trim()?.takeIf { it.isNotBlank() }
            else {
                Log.d(TAG, "ajax server=$serverIdx failed: ${res.text.take(120)}")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "ajax server=$serverIdx error: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks $data")
        try {
            var watchUrl = data
            if (!data.endsWith("/watch/")) {
                try {
                    val d0 = getDoc(data).document
                    d0.selectFirst("a.watch__btn[href], a[href$=\"/watch/\"]")?.attr("href")
                        ?.takeIf { it.isNotBlank() }?.let { watchUrl = fixUrl(it) }
                } catch (e: Exception) {
                    Log.d(TAG, "watch resolve failed: ${e.message}")
                }
            }
            val wres = getDoc(watchUrl, data)
            val html = wres.text
            val wdoc = wres.document
            val serverLis = wdoc.select("li[data-server]")
            Log.d(TAG, "servers=${serverLis.size}")
            val targets = linkedMapOf<String, String?>()
            val csrf = Regex("""csrf__token['"]?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""csrf_token['"]?\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
            val postId = serverLis.firstOrNull()?.attr("data-post")?.takeIf { it.isNotBlank() }
                ?: Regex("""psot_id\s*:\s*['"]?(\d+)""").find(html)?.groupValues?.get(1)
            Log.d(TAG, "ajax post=$postId csrf=${csrf?.take(6)}...")
            if (!postId.isNullOrBlank() && !csrf.isNullOrBlank()) {
                coroutineScope {
                    serverLis.map { li ->
                        async {
                            val idx = li.attr("data-server").trim()
                            val qu = li.attr("data-qu").trim().ifBlank { "720" }
                            val label = li.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotBlank() }
                                ?: "سيرفر $idx"
                            val url = ajaxServerUrl(postId, qu, idx, csrf, watchUrl)
                            Log.d(TAG, "ajax server=$idx ($label) -> ${url?.take(100)}")
                            if (!url.isNullOrBlank() && url.startsWith("http")) {
                                synchronized(targets) { targets.putIfAbsent(url, label) }
                            }
                        }
                    }.awaitAll()
                }
            } else {
                serverLis.forEach { li ->
                    val label = li.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: "سيرفر ${li.attr("data-server")}"
                    val pu = li.attr("data-player-url").trim()
                    if (pu.startsWith("http")) targets[pu] = label
                }
            }
            wdoc.select(".arabseed-deferred-iframe[data-src], iframe[src]").forEach { el ->
                val s = (el.attr("data-src").ifBlank { el.attr("src") }).trim()
                if (s.isBlank() || s.contains("youtube")) return@forEach
                val u = if (s.startsWith("//")) "https:$s" else fixUrl(s)
                targets.putIfAbsent(u, null)
            }
            Log.d(TAG, "targets=$targets")
            val count = java.util.concurrent.atomic.AtomicInteger(0)
            val safeCb: (ExtractorLink) -> Unit = { link ->
                synchronized(this) { callback(link) }
                count.incrementAndGet()
            }
            suspend fun emitT(label: String, url: String, isM3u8: Boolean, referer: String, headers: Map<String, String>? = null) {
                try {
                    val link = newExtractorLink(source = this.name, name = label, url = url) {
                        this.referer = referer
                        if (headers != null) this.headers = headers
                        this.quality = extractQuality(label)
                        this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                    safeCb(link)
                } catch (_: Exception) {}
            }
            fun unpackXUploadLocal(pageHtml: String): String? {
                try {
                    val evalIdx = pageHtml.indexOf("eval(function(p,a,c,k,e,d)")
                    if (evalIdx == -1) return null
                    val splitIdx = pageHtml.indexOf(".split('|')", evalIdx)
                    if (splitIdx == -1) return null
                    val payloadStart = pageHtml.indexOf("}('", evalIdx)
                    if (payloadStart == -1 || payloadStart + 3 >= splitIdx) return null
                    var dend = splitIdx - 1
                    while (dend > payloadStart && pageHtml[dend].isWhitespace()) dend--
                    if (dend <= payloadStart || pageHtml[dend] != '\'') return null
                    var dopen = dend - 1
                    while (dopen > payloadStart) {
                        if (pageHtml[dopen] == '\'' && pageHtml[dopen - 1] != '\\') break
                        dopen--
                    }
                    val argProbe = pageHtml.substring(maxOf(payloadStart + 3, dopen - 32), dopen)
                    val argMatch = Regex(""",(\d+),(\d+),$""").find(argProbe)
                    val payload: String
                    val radices: List<Int>
                    if (argMatch != null) {
                        val argStart = dopen - argMatch.value.length
                        if (argStart - 1 <= payloadStart + 3 || pageHtml[argStart - 1] != '\'') return null
                        payload = pageHtml.substring(payloadStart + 3, argStart - 1)
                        val r = argMatch.groupValues[1].toIntOrNull() ?: 36
                        radices = listOf(r) + listOf(36, 62).filter { it != r }
                    } else {
                        if (dopen - 2 <= payloadStart + 3 || pageHtml[dopen - 1] != ',' || pageHtml[dopen - 2] != '\'') return null
                        payload = pageHtml.substring(payloadStart + 3, dopen - 2)
                        radices = listOf(36, 62)
                    }
                    val dict = pageHtml.substring(dopen + 1, dend).split('|')
                    if (dict.size < 10 || payload.length < 100) return null
                    val alpha = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    fun toBase(n: Int, radix: Int): String {
                        if (n < radix) return alpha[n].toString()
                        var v = n
                        var s = ""
                        while (v > 0) {
                            s = alpha[v % radix] + s
                            v /= radix
                        }
                        return s
                    }
                    for (radix in radices) {
                        try {
                            var p = payload
                            for (idx in dict.size - 1 downTo 0) {
                                p = Regex("\\b" + Regex.escape(toBase(idx, radix)) + "\\b").replace(p, dict[idx])
                            }
                            if (p.contains("m3u8") && (p.contains("jwplayer") || p.lowercase().contains("playerjs") || p.contains("file") || p.contains("source"))) return p
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                return null
            }
            coroutineScope {
                targets.entries.map { (u, label) ->
                    async {
                        try {
                            try {
                                val host = Regex("""https?://([^/]+)""").find(u)?.groupValues?.get(1) ?: ""
                                if (host.isNotBlank() && (u.contains("/iframe/") || host.contains("megamax") || host.contains("q7v3k"))) {
                                    val seeded = synchronized(seededHosts) {
                                        if (seededHosts.contains(host)) false
                                        else {
                                            seededHosts.add(host)
                                            true
                                        }
                                    }
                                    if (seeded) {
                                        try {
                                            app.get("https://$host/", headers = embedHeaders, referer = watchUrl, interceptor = cfKiller, timeout = 15)
                                            Log.d(TAG, "seeded cookies for $host")
                                        } catch (_: Exception) {}
                                    }
                                }
                            } catch (_: Exception) {}
                            var res = app.get(u, headers = embedHeaders, referer = watchUrl, interceptor = cfKiller, timeout = 15)
                            var body = res.text
                            var base = u
                            val redir = Regex("""window\.location\.replace\(\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
                                ?: Regex("""location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
                            if (redir != null && redir != u) {
                                Log.d(TAG, "target redirect -> ${redir.take(100)}")
                                try {
                                    res = app.get(redir, headers = embedHeaders, referer = u, interceptor = cfKiller, timeout = 15)
                                    body = res.text
                                    base = redir
                                } catch (_: Exception) {}
                            }
                            Log.d(TAG, "target ${u.take(80)} -> ${res.code} len=${body.length} final=${res.url.take(80)}")
                            if (res.code == 403 || res.code == 401) {
                                Log.d(TAG, "target wall ${u.take(80)} snippet=${body.replace(Regex("""\s+"""), " ").take(200)}")
                            }
                            if (body.isBlank()) return@async
                            val headers = linkHeaders(base, body)
                            val voeBlob = Regex("""<script type="application/json">\["(.*?)"\]""", RegexOption.DOT_MATCHES_ALL)
                                .find(body)?.groupValues?.get(1)?.replace("\\/", "/")
                            if (!voeBlob.isNullOrBlank()) {
                                val real = try { voeDecodeSource(voeBlob) } catch (_: Exception) { null }
                                if (!real.isNullOrBlank()) {
                                    Log.d(TAG, "voe real -> ${real.take(100)}")
                                    emitT(label ?: "$name Server", real, real.contains(".m3u8"), base, headers)
                                    return@async
                                }
                            }
                            val scripts = try {
                                val raw = res.document.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
                                var s = try { JsUnpacker(raw).unpack() ?: raw } catch (_: Exception) { raw }
                                unpackXUploadLocal(raw)?.let { s += "\n$it" }
                                s
                            } catch (_: Exception) { body }
                            val files = Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(scripts)
                                .map { it.groupValues[1].replace("\\/", "/").replace("&amp;", "&") }
                                .filter { it.startsWith("http") && !isSampleUrl(it) }.distinct().toList()
                            for (f in files) {
                                emitT(label ?: "$name Server", f, f.contains(".m3u8"), base, headers)
                            }
                            if (files.isNotEmpty()) return@async
                            res.document.select("iframe[src]").forEach { f ->
                                val s = f.attr("src").trim()
                                if (s.isBlank() || s.contains("youtube")) return@forEach
                                val nu = if (s.startsWith("//")) "https:$s" else fixUrl(s)
                                try {
                                    loadExtractor(nu, base, subtitleCallback, safeCb)
                                } catch (_: Exception) {}
                            }
                            try {
                                loadExtractor(base, watchUrl, subtitleCallback, safeCb)
                            } catch (_: Exception) {}
                        } catch (e: Exception) {
                            Log.d(TAG, "target failed $u: ${e.message}")
                        }
                    }
                }.awaitAll()
            }
            var emitted = count.get()
            Log.d(TAG, "loadLinks emitted=$emitted")
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
        }
        return true
    }
}
