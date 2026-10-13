package com.larozaa

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URLEncoder

class LarozaaProvider : MainAPI() {
    override var mainUrl = "https://larozaa.casa"
    override var name = "Larozaa"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val TAG = "Larozaa"

    private val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

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
            "User-Agent" to BROWSER_UA,
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

    override val mainPage = mainPageOf(
        "$mainUrl/home.24" to "أحدث الإضافات",
        "$mainUrl/newvideos.php" to "أحدث الفيديوهات",
        "$mainUrl/category.php?cat=arabic-movies35" to "أفلام عربية",
        "$mainUrl/category.php?cat=all_movies_13" to "أفلام أجنبية",
        "$mainUrl/category.php?cat=arabic-series46" to "مسلسلات عربية",
        "$mainUrl/category.php?cat=english-series10" to "مسلسلات أجنبية",
        "$mainUrl/category.php?cat=turkish-3isk-seriess48" to "مسلسلات تركية",
        "$mainUrl/category.php?cat=6-anime-series" to "مسلسلات أنمي",
    )

    private fun Element.toCardResult(): SearchResponse? {
        val a = this.selectFirst("a[href*=\"video.php?vid=\"]") ?: return null
        val fullUrl = fixUrl(a.attr("href"))
        var title = a.attr("title").trim().takeIf { it.isNotBlank() }
        if (title.isNullOrBlank()) title = this.selectFirst("h3 a")?.text()?.trim()
        if (title.isNullOrBlank()) title = this.selectFirst("img")?.attr("alt")?.trim()
        if (title.isNullOrBlank()) return null

        val img = this.selectFirst("img")
        var poster = listOf("data-echo", "data-original-thumbnail", "data-src", "src").mapNotNull { attr ->
            img?.attr(attr)?.trim()?.takeIf { it.isNotBlank() && !it.contains("placeholder") }
        }.firstOrNull()
        if (poster == null) {
            poster = img?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
        }
        if (poster != null) poster = fixUrl(poster)

        val duration = this.selectFirst(".pm-label-duration")?.text()?.trim()
        val year = Regex("""(19\d{2}|20\d{2})""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = title.contains("مسلسل") || title.contains("حلقة")
        return if (isSeries) {
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

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) "$base&page=$page&order=DESC" else "$base?page=$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        Log.d(TAG, "getMainPage ${request.name} -> $url")
        val doc = app.get(url, referer = mainUrl).document
        val items = doc.select("ul.pm-ul-browse-videos li").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }.distinctBy { it.url }
        Log.d(TAG, "getMainPage ${request.name} found ${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val url = "$mainUrl/search.php?keywords=$enc"
        Log.d(TAG, "search -> $url")
        val doc = app.get(url, referer = mainUrl).document
        return doc.select("ul.pm-ul-browse-videos li").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }.distinctBy { it.url }
    }

    private fun playUrl(videoUrl: String): String {
        val vid = Regex("""vid=([A-Za-z0-9]+)""").find(videoUrl)?.groupValues?.get(1)
        return if (vid != null) "$mainUrl/play.php?vid=$vid" else videoUrl
    }

    private fun cleanUrl(u: String): String {
        return u.trim().replace("\\/", "/").replace("&amp;", "&")
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load $url")
        val doc = app.get(url, referer = mainUrl).document

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        var rawTitle = doc.selectFirst("h1")?.text()?.trim()
        if (rawTitle.isNullOrBlank()) rawTitle = doc.title().substringBefore("-").trim()
        if (rawTitle.isNullOrBlank()) rawTitle = ogTitle
        val finalTitle = rawTitle!!.trim()
        if (finalTitle.isBlank()) throw ErrorLoadingException("No title for $url")

        val poster = ogImage?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val year = Regex("""(19\d{2}|20\d{2})""").find(finalTitle)?.groupValues?.get(1)?.toIntOrNull()

        val recommendations = doc.select("ul.pm-ul-browse-videos li").mapNotNull { li ->
            try { li.toCardResult() } catch (_: Exception) { null }
        }.filter { it.url != url }.distinctBy { it.url }.take(24)
        Log.d(TAG, "video $finalTitle -> ${playUrl(url)}")
        return newMovieLoadResponse(finalTitle, url, TvType.Movie, playUrl(url)) {
            this.posterUrl = poster
            this.plot = ogDesc?.takeIf { it.isNotBlank() }
            this.year = year
            this.recommendations = recommendations.ifEmpty { null }
        }
    }

    data class VidaraSub(
        @JsonProperty("file_path") val filePath: String? = null,
        @JsonProperty("language") val language: String? = null
    )

    data class VidaraResp(
        @JsonProperty("streaming_url") val streamingUrl: String? = null,
        @JsonProperty("subtitles") val subtitles: List<VidaraSub>? = null
    )

    private fun qLabel(q: Int): String = if (q == Qualities.Unknown.value) "Auto" else "${q}p"

    private fun resolveStreamUrl(base: String, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("http")) return r
        if (r.startsWith("//")) return "https:$r"
        return try { java.net.URL(java.net.URL(base), r).toString() } catch (_: Exception) { r }
    }

    private fun parseM3u8Levels(body: String, base: String): List<Pair<Int, String>> {
        if (!body.contains("#EXTM3U")) return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        val lines = body.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                val uri = lines.getOrNull(j)?.trim().orEmpty()
                if (uri.isNotBlank() && !uri.startsWith("#")) {
                    val q = if (h != null && h in 144..4320) h else Qualities.Unknown.value
                    out.add(q to resolveStreamUrl(base, uri))
                }
                i = j
            }
            i++
        }
        return out.distinctBy { it.second }
    }

    private suspend fun emitM3u8Variants(
        masterUrl: String,
        baseName: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val txt = app.get(masterUrl, referer = referer, headers = headers, timeout = 15).text
            val levels = parseM3u8Levels(txt, masterUrl)
            if (levels.isEmpty()) return false
            var ok = false
            for ((q, u) in levels) {
                try {
                    callback(newExtractorLink(source = this.name, name = "$baseName (${qLabel(q)})", url = u) {
                        this.referer = referer
                        this.headers = headers
                        this.quality = q
                        this.type = ExtractorLinkType.M3U8
                    })
                    ok = true
                } catch (_: Exception) {}
            }
            ok
        } catch (_: Exception) { false }
    }

    /** vidara.to / gosbagotui.com embeds: POST /api/stream {filecode} -> {streaming_url}. */
    private suspend fun resolveVidara(
        eurl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        try {
            val host = Regex("""https?://([^/]+)""").find(eurl)?.groupValues?.get(1)?.lowercase() ?: return false
            if (!host.contains("vidara") && !host.contains("gosbagotui")) return false
            val code = eurl.trim().trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('#')
            if (code.isBlank() || code.length > 64 || code.contains(".")) return false
            val origin = "https://" + eurl.substringAfter("://").substringBefore('/')
            val res = app.post(
                "$origin/api/stream",
                headers = mapOf("Origin" to origin),
                referer = eurl,
                json = mapOf("filecode" to code, "device" to "web", "codecs" to emptyList<String>())
            )
            val parsed = try { AppUtils.parseJson<VidaraResp>(res.text) } catch (_: Exception) { null } ?: return false
            val stream = parsed.streamingUrl?.trim()?.takeIf { it.startsWith("http") } ?: return false
            Log.d(TAG, "vidara $code -> ${stream.take(100)}")
            parsed.subtitles?.forEach { s ->
                val f = s.filePath?.takeIf { it.isNotBlank() } ?: return@forEach
                try { subtitleCallback(newSubtitleFile(s.language ?: "sub", fixUrl(f))) } catch (_: Exception) {}
            }
            val baseName = label?.let { "$name $it" } ?: "$name Vidara"
            if (emitM3u8Variants(
                    stream, baseName, eurl,
                    mapOf("Referer" to eurl, "User-Agent" to BROWSER_UA),
                    callback
                )
            ) return true
            callback(newExtractorLink(source = this.name, name = baseName, url = stream) {
                this.referer = eurl
                this.quality = getQualityFromName(label)
                this.type = ExtractorLinkType.M3U8
            })
            return true
        } catch (e: Exception) {
            Log.d(TAG, "vidara resolve failed: ${e.message}")
            return false
        }
    }

    /**
     * XUpload-family packed player (okhd, film77, vidoba, mp4plus, anafast,
     * vidspeed, upzur, abstream...): Dean-Edwards-style eval with a base-36
     * dictionary, which JsUnpacker does not handle. Decode it ourselves.
     */
    private fun toBase(n: Int, radix: Int): String {
        val alpha = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (n < radix) return alpha[n].toString()
        var v = n
        var s = ""
        while (v > 0) {
            s = alpha[v % radix] + s
            v /= radix
        }
        return s
    }

    private fun unpackXUpload(html: String): String? {
        try {
            val evalIdx = html.indexOf("eval(function(p,a,c,k,e,d)")
            if (evalIdx == -1) return null
            val splitTag = ".split('|')"
            val splitIdx = html.indexOf(splitTag, evalIdx)
            if (splitIdx == -1) return null
            val payloadStart = html.indexOf("}('", evalIdx)
            if (payloadStart == -1 || payloadStart + 3 >= splitIdx) return null
            var dend = splitIdx - 1
            while (dend > payloadStart && html[dend].isWhitespace()) dend--
            if (dend <= payloadStart || html[dend] != '\'') return null
            var dopen = dend - 1
            while (dopen > payloadStart) {
                if (html[dopen] == '\'' && html[dopen - 1] != '\\') break
                dopen--
            }
            val argProbe = html.substring(maxOf(payloadStart + 3, dopen - 32), dopen)
            val argMatch = Regex(""",(\d+),(\d+),$""").find(argProbe)
            val payload: String
            val radices: List<Int>
            if (argMatch != null) {
                val argStart = dopen - argMatch.value.length
                if (argStart - 1 <= payloadStart + 3 || html[argStart - 1] != '\'') return null
                payload = html.substring(payloadStart + 3, argStart - 1)
                val r = argMatch.groupValues[1].toIntOrNull() ?: 36
                radices = listOf(r) + listOf(36, 62).filter { it != r }
            } else {
                if (dopen - 2 <= payloadStart + 3 || html[dopen - 1] != ',' || html[dopen - 2] != '\'') return null
                payload = html.substring(payloadStart + 3, dopen - 2)
                radices = listOf(36, 62)
            }
            val dict = html.substring(dopen + 1, dend).split('|')
            if (dict.size < 10 || payload.length < 100) return null
            for (radix in radices) {
                try {
                    var p = payload
                    for (idx in dict.size - 1 downTo 0) {
                        val token = toBase(idx, radix)
                        p = Regex("\\b" + Regex.escape(token) + "\\b").replace(p, dict[idx])
                    }
                    if (p.contains("m3u8") && (p.contains("jwplayer") || p.lowercase().contains("playerjs") || p.contains("file") || p.contains("source"))) {
                        Log.d(TAG, "xupload unpacked with radix $radix len=${p.length}")
                        return p
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * XUpload-style hosts (okhd, film77, vidoba, mp4plus, anafast, vidspeed, upzur...):
     * packed jwplayer setup containing the m3u8 file.
     */
    private suspend fun resolvePacked(
        eurl: String,
        referer: String,
        serverName: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHeaders = mapOf("User-Agent" to BROWSER_UA)
        return try {
            var res = app.get(eurl, headers = pageHeaders, referer = referer, timeout = 20)
            var body = res.text
            val redir = Regex("""window\.location\.replace\(\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
                ?: Regex("""location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
            var base = eurl
            if (redir != null && redir != eurl) {
                Log.d(TAG, "packed redirect -> ${redir.take(100)}")
                try {
                    res = app.get(redir, headers = pageHeaders, referer = eurl, timeout = 20)
                    body = res.text
                    base = redir
                } catch (_: Exception) {}
            }
            var edoc = res.document
            val serverQ = getQualityFromName(serverName)
            val serverTag = if (serverQ != Qualities.Unknown.value) " (${qLabel(serverQ)})" else ""
            val baseLinkName = serverName ?: "$name Server"
            val linkName = baseLinkName + serverTag
            val headers = linkHeaders(base, body)
            val voeBlob = Regex("""<script type="application/json">\["(.*?)"\]""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.replace("\\/", "/")
            if (!voeBlob.isNullOrBlank()) {
                val real = try { voeDecodeSource(voeBlob) } catch (_: Exception) { null }
                if (!real.isNullOrBlank()) {
                    try {
                        Log.d(TAG, "voe real -> ${real.take(100)}")
                        callback(newExtractorLink(source = this.name, name = linkName, url = real) {
                            this.referer = base
                            this.headers = headers
                            this.quality = serverQ
                            this.type = if (real.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        })
                        return true
                    } catch (_: Exception) {}
                }
            }
            val raw = edoc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
            var scripts = try { JsUnpacker(raw).unpack() ?: raw } catch (_: Exception) { raw }
            unpackXUpload(raw)?.let { scripts += "\n$it" }
            val files = Regex("""["'](https?://[^"']+(?:m3u8)[^"']*)["']""").findAll(scripts)
                .map { cleanUrl(it.groupValues[1]) }.filter { it.startsWith("http") && !isSampleUrl(it) }.distinct().toList()
            Log.d(TAG, "packed $eurl -> ${files.size} m3u8")
            var ok = false
            for (u in files) {
                if (emitM3u8Variants(u, baseLinkName, base, headers, callback)) {
                    ok = true
                    continue
                }
                try {
                    callback(newExtractorLink(source = this.name, name = linkName, url = u) {
                        this.referer = base
                        this.headers = headers
                        this.quality = serverQ
                        this.type = ExtractorLinkType.M3U8
                    })
                    ok = true
                } catch (_: Exception) {}
            }
            if (!ok) {
                edoc.select("iframe[src]").forEach { f ->
                    val s = f.attr("src").trim()
                    if (s.isBlank() || s.contains("youtube")) return@forEach
                    val u = if (s.startsWith("//")) "https:$s" else fixUrl(s)
                    try {
                        loadExtractor(u, base, subtitleCallback) { callback(it); ok = true }
                    } catch (_: Exception) {}
                }
            }
            ok
        } catch (e: Exception) {
            Log.d(TAG, "packed resolve failed $eurl: ${e.message}")
            false
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
            var playPage = data
            if (data.contains("video.php?vid=")) playPage = playUrl(data)
            val doc = app.get(playPage, referer = mainUrl).document

            val servers = doc.select("li[data-embed-url]")
            Log.d(TAG, "servers=${servers.size}")
            val count = java.util.concurrent.atomic.AtomicInteger(0)
            val safeCb: (ExtractorLink) -> Unit = { link ->
                synchronized(this) { callback(link) }
            }
            val safeSub: (SubtitleFile) -> Unit = { sub ->
                synchronized(this) { subtitleCallback(sub) }
            }
            coroutineScope {
                servers.map { li ->
                    async {
                        val eu = li.attr("data-embed-url").trim().replace("\\/", "/")
                        if (eu.isBlank() || !eu.startsWith("http")) return@async
                        val label = li.selectFirst("strong")?.text()?.trim()?.takeIf { it.isNotBlank() }
                            ?: "سيرفر ${li.attr("data-embed-id")}"
                        Log.d(TAG, "server=$label $eu")
                        if (resolveVidara(eu, safeSub, safeCb, label)) {
                            count.incrementAndGet()
                            return@async
                        }
                        var done = false
                        try {
                            loadExtractor(eu, playPage, safeSub) { link ->
                                safeCb(link)
                                done = true
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "extractor failed $eu: ${e.message}")
                        }
                        if (done) {
                            count.incrementAndGet()
                            return@async
                        }
                        if (resolvePacked(eu, playPage, "$name $label", safeSub, safeCb)) count.incrementAndGet()
                        else Log.d(TAG, "server yielded nothing $eu")
                    }
                }.awaitAll()
            }
            var emitted = count.get()

            if (emitted == 0) {
                doc.select(".brooks_player iframe[src], iframe[src]").forEach { f ->
                    val s = f.attr("src").trim()
                    if (s.isBlank() || s.contains("youtube")) return@forEach
                    val u = if (s.startsWith("//")) "https:$s" else fixUrl(s)
                    try {
                        loadExtractor(u, playPage, subtitleCallback) { callback(it); emitted++ }
                    } catch (_: Exception) {
                        if (resolvePacked(u, playPage, null, subtitleCallback, callback)) emitted++
                    }
                }
            }
            Log.d(TAG, "loadLinks emitted=$emitted")
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
        }
        return true
    }
}
