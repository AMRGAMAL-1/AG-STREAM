package com.shahedpro

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ShahedProProvider : MainAPI() {
    override var mainUrl = "https://www.shahedpro.com"
    override var name = "ShahedPro"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val TAG = "ShahedPro"

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/tvshows/" to "أحدث المسلسلات",
        "$mainUrl/most-viewed/" to "الأكثر مشاهدة",
        "$mainUrl/genres/أفلام-اجنبي/" to "أفلام أجنبية",
        "$mainUrl/genres/أفلام-عربية/" to "أفلام عربية",
        "$mainUrl/genres/أفلام-تركية/" to "أفلام تركية",
        "$mainUrl/genres/أفلام-هندية/" to "أفلام هندية",
        "$mainUrl/genres/أفلام-اسيوية/" to "أفلام آسيوية",
        "$mainUrl/genres/افلام-انمى/" to "أفلام أنمي",
        "$mainUrl/genres/افلام-مدبلجة-للعربية/" to "أفلام مدبلجة",
        "$mainUrl/genres/مسلسلات-اجنبية/" to "مسلسلات أجنبية",
        "$mainUrl/genres/مسلسلات-تركى/" to "مسلسلات تركية",
        "$mainUrl/genres/مسلسلات-هندى/" to "مسلسلات هندية",
        "$mainUrl/genres/مسلسلات-اسيوي/" to "مسلسلات آسيوية",
        "$mainUrl/genres/مسلسلات-انمي/" to "مسلسلات أنمي",
        "$mainUrl/genres/مسلسلات-رمضان-2026/" to "مسلسلات رمضان 2026",
        "$mainUrl/genres/دراما/" to "دراما",
        "$mainUrl/genres/اكشن/" to "أكشن",
        "$mainUrl/genres/كوميديا/" to "كوميديا",
        "$mainUrl/genres/رعب/" to "رعب",
        "$mainUrl/genres/رومنسية/" to "رومانسية",
        "$mainUrl/genres/برامج-تلفزيونية/" to "برامج تلفزيونية",
        "$mainUrl/genres/عروض-المصارعة-الحرة/" to "عروض المصارعة",
    )

    private fun Element.toCardResult(): SearchResponse? {
        val link = this.selectFirst("a.mc2-poster-link[href]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return null
        val fullUrl = fixUrl(link)

        var title = this.selectFirst(".mc2-ht-title")?.text()?.trim()
        if (title.isNullOrBlank()) title = this.selectFirst("img.mc2-img")?.attr("alt")?.trim()
        if (title.isNullOrBlank()) title = this.attr("aria-label")?.trim()
        if (title.isNullOrBlank()) return null

        var poster = this.selectFirst("img.mc2-img")?.attr("src")?.takeIf { it.isNotBlank() }
        if (poster != null) poster = fixUrl(poster)

        val year = Regex("""(19\d{2}|20\d{2})""").find(link)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val dtype = this.attr("data-type").lowercase()
        val isSeries = link.contains("/tvshows/") || link.contains("/episodes/") || dtype.contains("tv")
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
        return base.trimEnd('/') + "/page/$page/"
    }

    private suspend fun fetchDoc(url: String, referer: String? = null) = try {
        app.get(url, referer = referer ?: mainUrl, timeout = 25).document
    } catch (e: Exception) {
        Log.d(TAG, "retry $url after: ${e.message}")
        delay(1500)
        app.get(url, referer = referer ?: mainUrl, timeout = 25).document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        Log.d(TAG, "getMainPage ${request.name} -> $url")
        val doc = fetchDoc(url)
        val items = doc.select("article.mc2.media-card").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }.distinctBy { it.url }
        Log.d(TAG, "getMainPage ${request.name} found ${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val url = "$mainUrl/?s=$enc"
        Log.d(TAG, "search -> $url")
        val doc = fetchDoc(url)
        return doc.select("article.mc2.media-card").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }.distinctBy { it.url }
    }

    private fun arabicSeasonToNumber(text: String): Int? {
        val ordinals = mapOf(
            "الأول" to 1, "الاول" to 1,
            "الثاني" to 2, "الثالث" to 3, "الرابع" to 4, "الخامس" to 5,
            "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
            "العاشر" to 10, "الحادي عشر" to 11, "الثاني عشر" to 12
        )
        for ((k, v) in ordinals) if (text.contains(k)) return v
        return Regex("""الموسم\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load $url")
        if (url.contains("/episodes/")) {
            val doc = fetchDoc(url)
            val seriesUrl = doc.select("a[href*=\"/tvshows/\"]").map { it.attr("href") }
                .firstOrNull { it.isNotBlank() && !it.contains("/episodes/") }?.let { fixUrl(it) }
            if (!seriesUrl.isNullOrBlank()) return load(seriesUrl)
        }

        val doc = fetchDoc(url)
        val isSeries = url.contains("/tvshows/") || url.contains("/episodes/")

        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        var rawTitle = doc.selectFirst("h1.sp-title")?.text()?.trim()
        if (rawTitle.isNullOrBlank()) rawTitle = doc.selectFirst("h1")?.text()?.trim()
        if (rawTitle.isNullOrBlank()) rawTitle = doc.title().substringBefore("|").substringBefore("–").trim()
        val finalTitle = rawTitle!!.trim()
        if (finalTitle.isBlank()) throw ErrorLoadingException("No title for $url")

        val poster = ogImage?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: doc.selectFirst("[data-image]")?.attr("data-image")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val plot = ogDesc?.takeIf { it.isNotBlank() }
        val year = doc.selectFirst("a[href*=\"/dtyear/\"]")?.text()
            ?.filter { it.isDigit() }?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("a.sp-ic-link").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(10)

        val recommendations = doc.select("article.mc2.media-card").mapNotNull { a ->
            try { a.toCardResult() } catch (_: Exception) { null }
        }.filter { it.url != url }.distinctBy { it.url }.take(24)

        if (!isSeries) {
            Log.d(TAG, "movie $finalTitle")
            val watchUrl = url.trimEnd('/') + "/watch/"
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations.ifEmpty { null }
            }
        }
        val episodes = mutableListOf<Episode>()
        doc.select("a[href*=\"/episodes/\"]").map { it.attr("href") }.filter { it.isNotBlank() }
            .distinct().forEach { h ->
                val full = fixUrl(h)
                val epText = h
                val epNum = Regex("""الحلقة-(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""[الح]لقة[ -]?(\d+)""").find(
                        try { java.net.URLDecoder.decode(epText, "UTF-8") } catch (_: Exception) { epText }
                    )?.groupValues?.get(1)?.toIntOrNull()
                val seasonNum = arabicSeasonToNumber(
                    try { java.net.URLDecoder.decode(epText, "UTF-8") } catch (_: Exception) { epText }
                ) ?: arabicSeasonToNumber(finalTitle) ?: 1
                episodes.add(newEpisode(full) {
                    this.name = if (epNum != null) "الحلقة $epNum" else "حلقة"
                    this.episode = epNum
                    this.season = seasonNum
                    this.posterUrl = poster
                })
            }

        val distinct = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        Log.d(TAG, "show $finalTitle -> ${distinct.size} episodes")
        return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, distinct) {
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
            clean.contains("2k") || clean.contains("1440") -> 1440
            clean.contains("fhd") || clean.contains("1080") -> 1080
            clean.contains("hd") || clean.contains("720") -> 720
            clean.contains("sd") || clean.contains("480") -> 480
            else -> Qualities.Unknown.value
        }
    }

    private fun decodeB64(s: String): String? {
        return try {
            var t = s.trim().replace("-", "+").replace("_", "/")
            while (t.length % 4 != 0) t += "="
            String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun cleanUrl(u: String): String {
        var s = u.trim().replace("\\/", "/").replace("\\u0026", "&")
        var prev: String
        do {
            prev = s
            s = s.replace("&amp;", "&")
        } while (s != prev)
        return s
    }

    /** Hosts whose pages are packed/slow: harvest files before trying app extractors. */
    private val HARVEST_FIRST = listOf(
        "morencius", "bysekoze", "bysejikuar", "playmogo", "luluvdo", "okhd",
        "film77", "vidoba", "mp4plus", "anafast", "vidspeed", "upzur", "abstream",
        "1vid", "liivideo", "vidmoly", "filemoon", "uqload", "dsvplay", "ds2play",
        "doodstream", "dood.watch", "dood.li", "dooood", "d0o0d", "listeamed",
        "vidhideplus", "vidrock", "abyssplayer", "hgcloud", "turbovid", "streamhls",
        "dsvplay", "stmruby", "vidshar", "uqload.org", "voe", "megankiddifference"
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
                timeout = 20,
                json = mapOf("filecode" to code, "device" to "web", "codecs" to emptyList<String>())
            )
            val txt = res.text
            if (txt.isBlank()) return false
            val stream = Regex(""""streaming_url"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.takeIf { it.startsWith("http") } ?: return false
            Log.d(TAG, "vidara $code -> ${stream.take(100)}")
            val baseName = label?.let { "$name $it" } ?: "$name Vidara"
            if (emitM3u8Variants(
                    stream, baseName, eurl,
                    mapOf("Referer" to eurl, "User-Agent" to BROWSER_UA),
                    callback
                )
            ) return true
            callback(newExtractorLink(source = this.name, name = baseName, url = stream) {
                this.referer = eurl
                this.quality = extractQuality(label)
                this.type = ExtractorLinkType.M3U8
            })
            return true
        } catch (e: Exception) {
            Log.d(TAG, "vidara resolve failed: ${e.message}")
            return false
        }
    }

    private val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

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

    /** Placeholder/sample videos some mirrors embed (e.g. VOE's 10s Big Buck Bunny):
     *  must never be emitted as playable links. */
    private fun isSampleUrl(u: String): Boolean {        val l = u.lowercase()
        return l.contains("test-videos.co.uk") || l.contains("bigbuckbunny") ||
            l.contains("sample.mp4") || l.contains("sample-") || l.contains("placeholder") ||
            l.contains("dummy.mp4")
    }

    /** VOE mirrors (voe.sx + rotating hosts): real stream is an obfuscated
     *  <script type="application/json"> blob; the page also carries a 10s
     *  sample file. Chain (from loader.js): ROT13 -> token->'_' -> strip '_'
     *  -> base64 -> charcode-3 -> reverse -> base64 -> JSON {source}. */
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

    /** DoodStream mirrors (playmogo, mxdrop, myvidplay, dood.watch, ...): /e/<id> pages call
     *  $.get('/pass_md5/<path>/<token>') -> base URL; final = base + rand10
     *  + "?token=<token>&expiry=<nowMillis>". */
    private suspend fun resolveDoodPage(
        page: String,
        host: String,
        eurl: String,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        try {
            val passPath = Regex("""\$.get\('/pass_md5/([^']+)'""").find(page)?.groupValues?.get(1)
                ?: Regex("""/pass_md5/([^"' ]+)""").find(page)?.groupValues?.get(1)
                ?: return false
            val data = app.get("https://$host/pass_md5/$passPath", referer = eurl, timeout = 20).text.trim()
            if (data.isBlank() || data.length > 500 || data.contains("<") ||
                data.contains("RELOAD") || !data.startsWith("http")
            ) return false
            if (isSampleUrl(data)) return false
            val token = passPath.substringAfterLast("/")
            val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val rand = (1..10).map { alpha.random() }.joinToString("")
            val final = "$data$rand?token=$token&expiry=${System.currentTimeMillis()}"
            Log.d(TAG, "dood $host -> ${final.take(100)}")
            val baseName = label?.let { "$name $it" } ?: "$name Dood"
            callback(newExtractorLink(source = this.name, name = baseName, url = final) {
                this.referer = eurl
                this.quality = extractQuality(label)
                this.type = ExtractorLinkType.VIDEO
            })
            return true
        } catch (e: Exception) {
            Log.d(TAG, "dood resolve failed: ${e.message}")
            return false
        }
    }

    private suspend fun resolveDood(
        eurl: String,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        val host = Regex("""https?://([^/]+)""").find(eurl)?.groupValues?.get(1)?.lowercase() ?: return false
        if (!host.contains("dood") && !host.contains("playmogo") && !host.contains("mxdrop") &&
            !host.contains("myvidplay") && !host.contains("vidplay") &&
            !host.contains("dooood") && !host.contains("d0o0d") && !host.contains("doods")
        ) return false
        return try {
            val page = app.get(eurl, referer = eurl, timeout = 20).text
            if (page.isBlank()) false
            else resolveDoodPage(page, host, eurl, callback, label)
        } catch (e: Exception) {
            Log.d(TAG, "dood resolve failed: ${e.message}")
            false
        }
    }

    /** XUpload-family packed player: base-36/62 Dean-Edwards eval (JsUnpacker can't). */
    private fun unpackXUpload(html: String): String? {
        try {
            val evalIdx = html.indexOf("eval(function(p,a,c,k,e,d)")
            if (evalIdx == -1) return null
            val splitIdx = html.indexOf(".split('|')", evalIdx)
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
                    if (p.contains("m3u8") && (p.contains("jwplayer") || p.lowercase().contains("playerjs") || p.contains("file") || p.contains("source"))) {
                        Log.d(TAG, "xupload unpacked radix $radix len=${p.length}")
                        return p
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    /** Follow JS-redirect stubs: window.location.replace / meta refresh. */
    private fun jsRedirect(body: String, base: String): String? {
        val r = Regex("""window\.location\.replace\(\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
            ?: Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
            ?: Regex("""http-equiv=["']refresh["'][^>]+?url=(https?://[^"'\s>]+)""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        if (r == null || r == base) return null
        return r
    }

    /** Fetch an embed page and hunt playable files: packed JS, direct files, nested iframes. */
    private suspend fun harvestEmbed(
        eurl: String,
        referer: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        val pageHeaders = mapOf("User-Agent" to BROWSER_UA)
        try {
            val res = app.get(eurl, headers = pageHeaders, referer = referer, timeout = 20)
            if (harvestPage(res.text, res.document, eurl, quality, subtitleCallback, callback, label)) return true
            val redir = jsRedirect(res.text, eurl)
            if (redir != null) {
                Log.d(TAG, "harvest redirect ${redir.take(100)}")
                try {
                    val r2 = app.get(redir, headers = pageHeaders, referer = eurl, timeout = 20)
                    if (harvestPage(r2.text, r2.document, redir, quality, subtitleCallback, callback, label)) return true
                } catch (e: Exception) {
                    Log.d(TAG, "harvest redirect failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "harvest failed $eurl: ${e.message}")
        }
        return false
    }

    private suspend fun harvestPage(
        body: String,
        doc: org.jsoup.nodes.Document,
        base: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        var ok = false
        if (body.isBlank()) {
            Log.d(TAG, "harvest empty $base")
            return false
        }
        val linkName = label?.takeIf { it.isNotBlank() }?.let { "$name $it" } ?: "$name Server"
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
                        this.quality = quality
                        this.type = if (real.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    })
                    return true
                } catch (_: Exception) {}
            }
        }
        if (body.contains("/pass_md5/")) {
            val h = Regex("""https?://([^/]+)""").find(base)?.groupValues?.get(1)?.lowercase() ?: ""
            try {
                if (resolveDoodPage(body, h, base, callback, label)) return true
            } catch (_: Exception) {}
        }
        val raw = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        var scripts = try { JsUnpacker(raw).unpack() ?: raw } catch (_: Exception) { raw }
        unpackXUpload(raw)?.let { scripts += "\n$it" }
        val files = Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(scripts)
            .map { cleanUrl(it.groupValues[1]) }.filter { it.startsWith("http") && !isSampleUrl(it) }.distinct().toList()
        for (f in files) {
            val isM3u8 = f.contains(".m3u8")
            try {
                callback(newExtractorLink(source = this.name, name = linkName, url = f) {
                    this.referer = base
                    this.headers = headers
                    this.quality = quality
                    this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                })
                ok = true
            } catch (_: Exception) {}
        }
        if (ok) {
            Log.d(TAG, "harvest $base -> ${files.size} files")
            return true
        }
        doc.select("iframe[src]").forEach { fr ->
            val s = cleanUrl(fr.attr("src").trim())
            if (s.isBlank() || s.contains("youtube") || s == base) return@forEach
            val nu = if (s.startsWith("//")) "https:$s" else s
            if (!nu.startsWith("http") || nu == base) return@forEach
            if (emitUrl(nu, base, quality, subtitleCallback, callback)) ok = true
        }
        if (!ok) {
            Log.d(TAG, "harvest yielded nothing $base len=${body.length} snippet=${body.take(200).replace("\n", " ")}")
        }
        return ok
    }

    private suspend fun emitUrl(
        eurl: String,
        referer: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        if (resolveVidara(eurl, subtitleCallback, callback, label)) return true
        if (resolveDood(eurl, callback, label)) return true
        try {
            val host = Regex("""https?://([^/]+)""").find(eurl)?.groupValues?.get(1)?.lowercase() ?: ""
            if (HARVEST_FIRST.any { host.contains(it) }) {
                if (harvestEmbed(eurl, referer, quality, subtitleCallback, callback, label)) return true
            }
        } catch (_: Exception) {}
        var emitted = false
        try {
            loadExtractor(eurl, referer, subtitleCallback) { link ->
                if (isSampleUrl(link.url)) {
                    Log.d(TAG, "skipping sample ${link.url.take(80)}")
                    return@loadExtractor
                }
                if (link.quality == Qualities.Unknown.value && quality != Qualities.Unknown.value) {
                    link.quality = quality
                }
                emitted = true
                callback(link)
            }
        } catch (e: Exception) {
            Log.d(TAG, "extractor failed $eurl: ${e.message}")
        }
        if (!emitted) {
            if (harvestEmbed(eurl, referer, quality, subtitleCallback, callback, label)) return true
        }
        return emitted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks $data")
        val watchCandidates = linkedSetOf<String>()
        if (data.endsWith("/watch/") || data.contains("?embedScreen=") || data.contains("embedScreen")) {
            watchCandidates.add(data)
        } else {
            watchCandidates.add(data.trimEnd('/') + "/watch/")
            watchCandidates.add(data.trimEnd('/') + "?embedScreen=true")
            watchCandidates.add(data)
        }
        try {
            for (wurl in watchCandidates) {
                try {
                    val doc = fetchDoc(wurl, mainUrl)
                    val servers = doc.select("button.wSrvBtn[data-enc]")
                    Log.d(TAG, "$wurl servers=${servers.size}")
                    if (servers.isEmpty()) {
                        val found = linkedSetOf<String>()
                        doc.select("#wPBox iframe[src], iframe[src]").forEach { f ->
                            val src = cleanUrl(f.attr("src").trim())
                            if (src.isNotBlank() && !src.contains("youtube")) {
                                found.add(if (src.startsWith("//")) "https:$src" else fixUrl(src))
                            }
                        }
                        doc.selectFirst("meta[property=\"og:video\"]")?.attr("content")?.trim()
                            ?.takeIf { it.isNotBlank() }?.let { found.add(fixUrl(it)) }
                        for (u in found) {
                            try {
                                loadExtractor(u, wurl, subtitleCallback, callback)
                            } catch (e: Exception) {
                                Log.d(TAG, "extractor failed $u: ${e.message}")
                            }
                        }
                        if (found.isNotEmpty()) return true
                        continue
                    }
                    val count = java.util.concurrent.atomic.AtomicInteger(0)
                    val safeCb: (ExtractorLink) -> Unit = { link ->
                        synchronized(this) { callback(link) }
                        count.incrementAndGet()
                    }
                    val safeSub: (SubtitleFile) -> Unit = { sub ->
                        synchronized(this) { subtitleCallback(sub) }
                    }
                    coroutineScope {
                        servers.map { btn ->
                            async {
                                val enc = btn.attr("data-enc")
                                var emb = decodeB64(enc)
                                if (emb == null) {
                                    Log.d(TAG, "server undecodable enc=${enc.take(60)}")
                                    return@async
                                }
                                emb = cleanUrl(emb)
                                if (emb.startsWith("//")) emb = "https:$emb"
                                else if (emb.startsWith("/")) emb = fixUrl(emb)
                                if (emb.contains("<iframe", ignoreCase = true)) {
                                    emb = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(emb)?.groupValues?.get(1)?.let { cleanUrl(it) } ?: return@async
                                    if (emb.startsWith("//")) emb = "https:$emb"
                                    else if (emb.startsWith("/")) emb = fixUrl(emb)
                                }
                                if (!emb.startsWith("http")) {
                                    Log.d(TAG, "server non-http ${emb.take(90)}")
                                    return@async
                                }
                                val dtype = btn.attr("data-type").trim().lowercase()
                                val label = btn.text().trim().replace(Regex("""\s+"""), " ").takeIf { it.isNotBlank() }
                                val q = extractQuality(label)
                                Log.d(TAG, "server=$label type=$dtype ${emb.take(90)}")
                                if (dtype == "mp4") {
                                    try {
                                        safeCb(newExtractorLink(source = this@ShahedProProvider.name, name = label?.let { "${this@ShahedProProvider.name} $it" } ?: this@ShahedProProvider.name, url = emb) {
                                            this.referer = wurl
                                            this.quality = q
                                            this.type = ExtractorLinkType.VIDEO
                                        })
                                    } catch (_: Exception) {}
                                    return@async
                                }
                                if (emitUrl(cleanUrl(emb), wurl, q, safeSub, safeCb, label)) return@async
                                Log.d(TAG, "server yielded nothing $emb")
                            }
                        }.awaitAll()
                    }
                    if (count.get() > 0) return true
                } catch (e: Exception) {
                    Log.d(TAG, "watch page $wurl failed: ${e.message}")
                }
            }
            try {
                val doc = fetchDoc(data, mainUrl)
                val rawScripts = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
                var scripts = try { JsUnpacker(rawScripts).unpack() ?: rawScripts } catch (_: Exception) { rawScripts }
                unpackXUpload(rawScripts)?.let { scripts += "\n$it" }
                Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(scripts).forEach { m ->
                    val u = cleanUrl(m.groupValues[1])
                    if (!u.startsWith("http") || isSampleUrl(u)) return@forEach
                    val q = extractQuality(u)
                    val tag = if (q != Qualities.Unknown.value) " (${qLabel(q)})" else ""
                    try {
                        callback(newExtractorLink(source = this.name, name = "$name Server$tag", url = u) {
                            this.referer = data
                            this.quality = q
                            this.type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        })
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
        }
        return true
    }
}
