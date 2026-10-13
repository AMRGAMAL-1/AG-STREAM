package com.cimanow

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class CimaNowProvider : MainAPI() {
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val TAG = "CimaNow"
    private val cfKiller by lazy { CloudflareKiller() }

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/الافلام/" to "أحدث الأفلام",
        "$mainUrl/category/افلام-اجنبية/" to "أفلام أجنبية",
        "$mainUrl/category/افلام-عربية/" to "أفلام عربية",
        "$mainUrl/category/الافلام/افلام-انيميشن/" to "أفلام انيميشن",
        "$mainUrl/category/الافلام/افلام-تركية/" to "أفلام تركية",
        "$mainUrl/category/الافلام/افلام-كورية/" to "أفلام كورية",
        "$mainUrl/category/الافلام/افلام-هندية/" to "أفلام هندية",
        "$mainUrl/category/الافلام/افلام-نتفليكس/" to "أفلام نتفليكس",
        "$mainUrl/category/الافلام/افلام-مارفل/" to "أفلام مارفل",
        "$mainUrl/category/المسلسلات/" to "المسلسلات",
        "$mainUrl/category/مسلسلات-اجنبية/" to "مسلسلات أجنبية",
        "$mainUrl/category/المسلسلات/مسلسلات-عربية/" to "مسلسلات عربية",
        "$mainUrl/category/المسلسلات/مسلسلات-تركية/" to "مسلسلات تركية",
        "$mainUrl/category/المسلسلات/مسلسلات-كورية/" to "مسلسلات كورية",
        "$mainUrl/category/المسلسلات/مسلسلات-انيميشن/" to "مسلسلات انيميشن",
        "$mainUrl/category/المسلسلات/مسلسلات-نتفليكس/" to "مسلسلات نتفليكس",
        "$mainUrl/category/مسرحيات/" to "مسرحيات",
        "$mainUrl/category/برامج-تلفزيونية/البرامج-التلفزيونية/" to "برامج تلفزيونية",
        "$mainUrl/category/رمضان/رمضان-2026/" to "رمضان 2026",
        "$mainUrl/الاحدث/" to "أحدث الإضافات",
    )

    private fun Element.toCardResult(): SearchResponse? {
        val link = this.selectFirst("picture a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("ul.btns a.shine[href]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return null
        if (link.contains("/category/") || link.contains("/actor/") || link.contains("/release-year/")) return null
        val fullUrl = fixUrl(link)

        var title = this.selectFirst("picture img")?.attr("alt")?.trim()
        if (title.isNullOrBlank()) title = this.selectFirst("img")?.attr("alt")?.trim()
        if (title.isNullOrBlank()) return null
        var poster = this.selectFirst("figure img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("figure img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("picture img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("picture img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        if (poster != null) {
            val pl = poster.lowercase()
            if (poster.contains("/wp-content/themes/") || pl.endsWith(".svg") ||
                pl.contains("/logo") || pl.contains("logo-") || pl.contains("-logo.") ||
                pl.contains("_logo") || pl.contains("placeholder")
            ) {
                poster = null
            }
        }
        if (poster != null) poster = fixUrl(poster)

        val year = this.selectFirst("a[href*=\"/release-year/\"]")?.text()
            ?.filter { it.isDigit() }?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(link)?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = link.contains("/selary/") || link.contains("مسلسل")
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        Log.d(TAG, "getMainPage ${request.name} -> $url")
        val doc = app.get(url).document
        val items = doc.select("article").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }.distinctBy { it.url }
        Log.d(TAG, "getMainPage ${request.name} found ${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val url = "$mainUrl/?s=$enc"
        Log.d(TAG, "search -> $url")
        val doc = app.get(url).document
        return doc.select("article").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }.distinctBy { it.url }
    }

    private fun decodeWatchLink(href: String): String? {
        return try {
            val encoded = href.substringAfter("link=").substringBefore("&").trim()
            if (encoded.isBlank() || encoded == href) return null
            val b64 = URLDecoder.decode(encoded, "UTF-8")
            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.startsWith("http")) fixUrl(decoded) else null
        } catch (e: Exception) {
            Log.d(TAG, "decodeWatchLink failed: ${e.message}")
            null
        }
    }

    private fun seasonFromSlug(url: String): Int? {
        val decoded = try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
        return Regex("""-ج(\d+)-""").find(decoded)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun episodeFromSlug(url: String): Int? {
        val decoded = try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
        return Regex("""-ح(\d+)-""").find(decoded)?.groupValues?.get(1)?.toIntOrNull()
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
            ?: Regex("""موسم\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load $url")
        val doc = app.get(url).document
        val isSeasonPage = url.contains("/selary/")

        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        var rawTitle = doc.selectFirst("h1")?.text()?.trim()
        if (rawTitle.isNullOrBlank()) rawTitle = doc.title().substringBefore("|").trim()
        val finalTitle = rawTitle!!.trim()
        if (finalTitle.isBlank()) throw ErrorLoadingException("No title for $url")

        val poster = ogImage?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val plot = doc.selectFirst("li[aria-label=story] p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: ogDesc?.takeIf { it.isNotBlank() }
        val year = doc.selectFirst("a[href*=\"/release-year/\"]")?.text()
            ?.filter { it.isDigit() }?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("a[href*=\"/category/\"]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(8)

        val recommendations = doc.select("article").mapNotNull { a ->
            try { a.toCardResult() } catch (_: Exception) { null }
        }.filter { it.url != url }.distinctBy { it.url }.take(24)

        if (!isSeasonPage) {
            val watchBtn = doc.select("ul.btns a.shine[href]").map { it.attr("href") }
                .firstOrNull { it.contains("link=") }
                ?: doc.select("a[href*=\"loadon\"]").map { it.attr("href") }
                    .firstOrNull { it.contains("link=") }
            val watchUrl = watchBtn?.let { decodeWatchLink(it) }
            Log.d(TAG, "movie $finalTitle -> watch $watchUrl")
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, watchUrl ?: url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations.ifEmpty { null }
            }
        }
        val seasonNum = seasonFromSlug(url)
            ?: arabicSeasonToNumber(finalTitle) ?: 1
        val episodes = mutableListOf<Episode>()
        doc.select("a[href]").map { it.attr("href") }.filter { h ->
            h.isNotBlank() && h.contains("-ح") && (episodeFromSlug(h) != null)
        }.distinct().forEach { h ->
            val full = fixUrl(h)
            val epNum = episodeFromSlug(h)
            val epSeason = seasonFromSlug(h) ?: seasonNum
            if (epSeason == seasonNum) {
                episodes.add(newEpisode(full) {
                    this.name = if (epNum != null) "الحلقة $epNum" else "حلقة"
                    this.episode = epNum
                    this.season = seasonNum
                    this.posterUrl = poster
                })
            }
        }
        val seasonLinks = doc.select("a[href*=\"/selary/\"]").map { it.attr("href") }
            .filter { it.isNotBlank() }.distinct()
            .map { fixUrl(it) }.filter { it != url }
            .filter { (seasonFromSlug(it) ?: -1) != seasonNum }
            .distinctBy { seasonFromSlug(it) }

        for (sUrl in seasonLinks) {
            try {
                val sNum = seasonFromSlug(sUrl) ?: continue
                val sDoc = app.get(sUrl).document
                sDoc.select("a[href]").map { it.attr("href") }.filter { h ->
                    h.isNotBlank() && episodeFromSlug(h) != null && (seasonFromSlug(h) ?: sNum) == sNum
                }.distinct().forEach { h ->
                    val full = fixUrl(h)
                    val epNum = episodeFromSlug(h)
                    episodes.add(newEpisode(full) {
                        this.name = "الحلقة $epNum"
                        this.episode = epNum
                        this.season = sNum
                        this.posterUrl = poster
                    })
                }
            } catch (e: Exception) {
                Log.d(TAG, "season $sUrl failed: ${e.message}")
            }
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
    private fun decryptPlayer(html: String): String? {
        try {
            val marker = "window[\"_vc12f6\"]"
            val start = html.indexOf(marker)
            if (start == -1) return null
            val open = html.indexOf('(', start + marker.length)
            if (open == -1) return null
            val close = html.indexOf(");", open)
            if (close == -1 || close - open > 2_000_000) return null
            val seg = html.substring(open + 1, close)
            val b64 = Regex("\"([A-Za-z0-9+/=]{20,})\"").findAll(seg)
                .map { it.groupValues[1] }.joinToString("")
            if (b64.length < 1000) return null
            val dec = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) { return null }
            val key = "138701n".toByteArray(Charsets.UTF_8)
            for (i in dec.indices) {
                dec[i] = (dec[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            val txt = String(dec, Charsets.UTF_8)
            if (txt.length <= 48) return null
            val player = txt.substring(48)
            if (!player.contains("aria-label") && !player.contains("data-index")) {
                Log.d(TAG, "decrypt validation failed len=${player.length}")
                return null
            }
            Log.d(TAG, "decrypt ok len=${player.length}")
            return player
        } catch (e: Exception) {
            Log.d(TAG, "decrypt failed: ${e.message}")
            return null
        }
    }

    private fun decodeJetload(href: String): String? {
        return try {
            val enc = href.substringAfter("link=").substringBefore("&").trim()
            if (enc.isBlank()) return null
            val raw = String(Base64.decode(URLDecoder.decode(enc, "UTF-8"), Base64.DEFAULT), Charsets.UTF_8).trim()
            if (raw.startsWith("http")) fixUrl(raw) else null
        } catch (_: Exception) { null }
    }

    private suspend fun emitPlayerLinks(
        playerHtml: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = 0
        val doc = try {
            org.jsoup.Jsoup.parse(playerHtml)
        } catch (_: Exception) { return false }
        suspend fun emit(url: String, quality: Int, label: String?) {
            try {
                val nm = if (!label.isNullOrBlank()) "$name $label" else "$name Server"
                callback(newExtractorLink(source = this.name, name = nm, url = url) {
                    this.referer = referer
                    this.quality = quality
                    this.type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                })
                emitted++
            } catch (_: Exception) {}
        }
        suspend fun emitExt(url: String, quality: Int) {
            try {
                var ok = false
                loadExtractor(url, referer, subtitleCallback) {
                    if (it.quality == Qualities.Unknown.value && quality != Qualities.Unknown.value) {
                        it.quality = quality
                    }
                    ok = true
                    callback(it)
                }
                if (ok) emitted++
            } catch (e: Exception) {
                Log.d(TAG, "extractor failed $url: ${e.message}")
            }
        }
        for (f in doc.select("li[aria-label=\"embed\"] iframe[src]")) {
            var src = f.attr("src").trim()
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("http") && !src.contains("youtube")) {
                Log.d(TAG, "embed $src")
                emitExt(src, Qualities.Unknown.value)
            }
        }
        try {
            val ffRaw = Regex("""ffQualities\s*=\s*(\{.+?\});\s*var data""", RegexOption.DOT_MATCHES_ALL)
                .find(playerHtml)?.groupValues?.get(1)
            if (!ffRaw.isNullOrBlank()) {
                @Suppress("UNCHECKED_CAST")
                val ff = AppUtils.parseJson<Map<String, Map<String, String>>>(ffRaw)
                for (q in ff.values) {
                    val lines = listOf("1080p" to q["Ff1080"], "720p" to q["Ff720"], "480p" to q["Ff480"], "360p" to q["Ff360"])
                        .filter { !it.second.isNullOrBlank() }
                        .joinToString("\n") { "${it.first}|${it.second}" }
                    if (lines.isNotBlank()) {
                        val b64 = Base64.encodeToString(lines.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        val purl = "https://player.forafile.com/?data=" + URLEncoder.encode(b64, "UTF-8")
                        Log.d(TAG, "forafile player ${purl.take(80)}")
                        emitExt(purl, Qualities.Unknown.value)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "forafile map failed: ${e.message}")
        }
        for (a in doc.select("li[aria-label=\"quality\"].box a[href], li[aria-label=\"download\"] a[href]")) {
            var href = a.attr("href").trim()
            if (href.isBlank()) continue
            val label = a.text().trim()
            val q = extractQuality(label)
            if (href.contains("jetload") && href.contains("link=")) {
                decodeJetload(href)?.let { emit(it, q, "Jetload $label") }
                continue
            }
            var u = href
            if (u.startsWith("https://href.li/?")) u = u.removePrefix("https://href.li/?")
            if (u.startsWith("//")) u = "https:$u"
            if (!u.startsWith("http")) continue
            if (u.contains("youtube.com") || u.contains("youtu.be")) continue
            if (u.contains(".mp4") && !u.contains(".html") && !u.contains("vk.com") &&
                !u.contains("forafile.com") && !u.contains("frdl.my") && !u.contains("upns") &&
                !u.contains("bysetayico") && !u.contains("streamhls")
            ) {
                emit(u, q, label)
            } else {
                emitExt(u, q)
            }
        }
        Log.d(TAG, "player emitted=$emitted")
        return emitted > 0
    }
    private val MINTER_HOSTS = listOf(
        "https://rm.freex2line.online"
    )

    private fun diagnoseChallenge(body: String, tag: String) {
        try {
            Log.d(TAG, "$tag diag len: ${body.length} cimanow: ${body.contains("cimanow")} watching: ${body.contains("watching")} tokCount: ${Regex("tok", RegexOption.IGNORE_CASE).findAll(body).count()} cookie: ${body.contains("cookie", true)} form: ${body.contains("<form", true)}")
            val srcs = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(body).map { it.groupValues[1] }.take(10).toList()
            Log.d(TAG, "$tag diag scripts: $srcs")
            val locs = Regex("""(?:window\s*\[\s*["']location["']\s*\]|document\s*\.\s*location|top\s*\.\s*location|window\.location(?:\.href)?|location)\s*(?:=|\.replace\s*\(|\.assign\s*\()\s*["']([^"']{1,500})["']""")
                .findAll(body).map { it.groupValues[1] }.take(10).toList()
            Log.d(TAG, "$tag diag locs: ${locs.map { it.take(150) }}")
            val hrefs = Regex("""(?:href|data-url|data-href|action)\s*=\s*["']([^"']{1,300})["']""", RegexOption.IGNORE_CASE)
                .findAll(body).map { it.groupValues[1] }.distinct()
                .filter { it.contains("blog") || it.contains("loadon") || it.contains("redirect") || it.contains("cima") || it.contains("watch") || it.contains("tok") }
                .take(10).toList()
            Log.d(TAG, "$tag diag hrefs: ${hrefs.map { it.take(150) }}")
            val cimas = Regex("""https?://[^"'\s<>]*cimanow[^"'\s<>]*""", RegexOption.IGNORE_CASE)
                .findAll(body).map { it.value }.distinct().take(10).toList()
            Log.d(TAG, "$tag diag cimas: ${cimas.map { it.take(180) }}")
            val forms = Regex("""<form[^>]+action=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(body).map { it.groupValues[1] }.take(5).toList()
            if (forms.isNotEmpty()) Log.d(TAG, "$tag diag forms: $forms")
            var n = 0
            var idx = body.indexOf("tok", ignoreCase = true)
            while (idx >= 0 && n < 5) {
                n++
                val s = maxOf(0, idx - 60)
                val e = minOf(body.length, idx + 90)
                Log.d(TAG, "$tag diag tCtx$n: ${body.substring(s, e).replace(Regex("""\s+"""), " ").take(180)}")
                idx = body.indexOf("tok", idx + 3, ignoreCase = true)
            }
            val hexes = Regex("""["'][A-Fa-f0-9]{32,128}["']""").findAll(body)
                .map { it.value.take(70) }.distinct().take(8).toList()
            Log.d(TAG, "$tag diag hexes=${hexes.take(8)}")
        } catch (e: Exception) {
            Log.d(TAG, "$tag diag failed: ${e.message}")
        }
    }

    private fun extractJsRedirect(body: String): String? {
        val pats = listOf(
            Regex("""window\.location\.(?:href|replace)\s*\(\s*["']([^"']+)["']"""),
            Regex("""window\.location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""location\.replace\s*\(\s*["']([^"']+)["']"""),
            Regex("""location\.href\s*=\s*["']([^"']+)["']""")
        )
        for (p in pats) {
            val v = p.find(body)?.groupValues?.get(1)?.trim()
            if (!v.isNullOrBlank() && v.startsWith("http") && v.length < 2000) return v
        }
        return null
    }

    private fun extractMetaRefresh(body: String): String? {
        val m = Regex("""content\s*=\s*["']\d+;\s*url=([^"']+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.trim()
        if (m.isNullOrBlank()) return null
        return m
    }

    private fun extractTokenizedWatch(body: String): String? {
        var tok = Regex("""https?://[^"'\s<>]+\?token=[A-Za-z0-9]+""").find(body)?.value
        if (!tok.isNullOrBlank()) return tok
        tok = Regex("""(?:location|href|url)\s*=\s*["']([^"']*watching/[^"']*token=[A-Za-z0-9]+[^"']*)["']""")
            .find(body)?.groupValues?.get(1)
        if (!tok.isNullOrBlank()) return tok
        return null
    }

    private suspend fun fetchHop(url: String, referer: String, tag: String): Pair<String, String>? {
        return try {
            val via = app.get(url, headers = browserHeaders, referer = referer,
                interceptor = cfKiller, timeout = 15, allowRedirects = true)
            Log.d(TAG, "$tag final=${via.url.take(200)}")
            if (via.url.contains("token=") && via.url.contains("watching")) {
                return Pair(via.url, via.text)
            }
            val body = via.text
            Log.d(TAG, "$tag body len=${body.length} hasWatching=${body.contains("watching")}")
            Log.d(TAG, "$tag snippet=${body.replace(Regex("""\s+"""), " ").take(300)}")
            Pair(via.url, body)
        } catch (e: Exception) {
            Log.d(TAG, "$tag failed: ${e.message}")
            null
        }
    }
    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val tokenRefCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val FAIL_COOLDOWN_MS = 10 * 60 * 1000L

    private suspend fun mintToken(detailUrl: String, btn: String): String? {
        tokenCache[detailUrl]?.let { return it }
        failCache[detailUrl]?.let { ts ->
            if (System.currentTimeMillis() - ts < FAIL_COOLDOWN_MS) {
                Log.d(TAG, "mint skipped (recent fail)")
                return null
            } else failCache.remove(detailUrl)
        }
        val path = btn.substringAfter("freex2line.online", btn)
        for (host in MINTER_HOSTS) {
            val url = if (btn.contains("freex2line.online")) host + path else btn
            try {
                Log.d(TAG, "mint via $host ...")
                val hop1 = fetchHop(url, detailUrl, "minter $host")
                    ?: continue
                var finalUrl = hop1.first
                var body = hop1.second
                if (finalUrl.contains("token=") && finalUrl.contains("watching")) {
                    Log.d(TAG, "minted direct ${finalUrl.take(140)}")
                    tokenCache[detailUrl] = finalUrl
                    tokenRefCache[detailUrl] = url
                    return finalUrl
                }
                extractTokenizedWatch(body)?.let { tok ->
                    val abs = if (tok.startsWith("/")) "https://cimanow.cc$tok" else tok
                    Log.d(TAG, "minted from body [watch-url] ${abs.take(140)}")
                    tokenCache[detailUrl] = abs
                    tokenRefCache[detailUrl] = url
                    return abs
                }
                var next = extractJsRedirect(body) ?: extractMetaRefresh(body)
                var hops = 0
                var referer = url
                var redirectingFreeUrl: String? = null
                while (!next.isNullOrBlank() && hops < 3) {
                    hops++
                    var nxt = next!!.trim()
                    if (nxt.startsWith("/")) nxt = host + nxt
                    Log.d(TAG, "mint hop$hops -> ${nxt.take(200)}")
                    if (nxt.contains("redirectingfree")) redirectingFreeUrl = nxt
                    if (nxt.contains("href.li")) {
                        val inner = nxt.substringAfter("?", "").trim()
                        if (inner.startsWith("http")) {
                            Log.d(TAG, "mint hop$hops href.li inner ${inner.take(200)}")
                            nxt = inner
                            if (nxt.contains("redirectingfree")) redirectingFreeUrl = nxt
                        } else {
                            val h = fetchHop(nxt, referer, "mint hrefl $host")
                            if (h != null) {
                                extractTokenizedWatch(h.second)?.let { tok ->
                                    val abs = if (tok.startsWith("/")) "https://cimanow.cc$tok" else tok
                                    tokenCache[detailUrl] = abs
                                    tokenRefCache[detailUrl] = referer
                                    return abs
                                }
                                next = extractMetaRefresh(h.second)
                                    ?: extractJsRedirect(h.second)
                                referer = h.first
                                continue
                            }
                            break
                        }
                    }
                    val h = fetchHop(nxt, referer, "mint hop$hops $host") ?: break
                    finalUrl = h.first
                    body = h.second
                    if (finalUrl.contains("token=") && finalUrl.contains("watching")) {
                        Log.d(TAG, "minted via hops ${finalUrl.take(140)}")
                        tokenCache[detailUrl] = finalUrl
                        tokenRefCache[detailUrl] = referer
                        return finalUrl
                    }
                    extractTokenizedWatch(body)?.let { tok ->
                        val abs = if (tok.startsWith("/")) "https://cimanow.cc$tok" else tok
                        Log.d(TAG, "minted hop body [watch-url] ${abs.take(140)}")
                        tokenCache[detailUrl] = abs
                        tokenRefCache[detailUrl] = referer
                        return abs
                    }
                    next = extractJsRedirect(body) ?: extractMetaRefresh(body)
                    referer = finalUrl.takeIf { it.startsWith("http") } ?: nxt
                    if (next.isNullOrBlank()) {
                        Log.d(TAG, "mint hops exhausted at ${finalUrl.take(140)}")
                        diagnoseChallenge(body, "mint $host")
                        if (redirectingFreeUrl != null && body.length > 100000) {
                            try {
                                Log.d(TAG, "mint retry $redirectingFreeUrl")
                                val r = fetchHop(redirectingFreeUrl!!, referer, "mint retry $host")
                                if (r != null) {
                                    if (r.first.contains("token=") && r.first.contains("watching")) {
                                        tokenCache[detailUrl] = r.first
                                        tokenRefCache[detailUrl] = referer
                                        return r.first
                                    }
                                    extractTokenizedWatch(r.second)?.let { tok ->
                                        val abs = if (tok.startsWith("/")) "https://cimanow.cc$tok" else tok
                                        tokenCache[detailUrl] = abs
                                        tokenRefCache[detailUrl] = referer
                                        return abs
                                    }
                                    Log.d(TAG, "mint retry final=${r.first.take(200)} len=${r.second.length}")
                                }
                            } catch (_: Exception) {}
                            try {
                                val mobBlog = finalUrl.trimEnd('/') + "?m=1"
                                Log.d(TAG, "mint mobile $mobBlog")
                                val m = fetchHop(mobBlog, referer, "mint mobile $host")
                                if (m != null) {
                                    if (m.first.contains("token=") && m.first.contains("watching")) {
                                        tokenCache[detailUrl] = m.first
                                        tokenRefCache[detailUrl] = referer
                                        return m.first
                                    }
                                    extractTokenizedWatch(m.second)?.let { tok ->
                                        val abs = if (tok.startsWith("/")) "https://cimanow.cc$tok" else tok
                                        Log.d(TAG, "minted mobile [watch-url] ${abs.take(140)}")
                                        tokenCache[detailUrl] = abs
                                        tokenRefCache[detailUrl] = referer
                                        return abs
                                    }
                                    Log.d(TAG, "mint mobile final=${m.first.take(200)} len=${m.second.length} cimanow=${m.second.contains("cimanow")} watching=${m.second.contains("watching")}")
                                    Log.d(TAG, "mint mobile snippet=${m.second.replace(Regex("""\s+"""), " ").take(300)}")
                                }
                                val mobRed = redirectingFreeUrl!!.trimEnd('/') + "?m=1"
                                Log.d(TAG, "mint mobile2 $mobRed")
                                val m2 = fetchHop(mobRed, referer, "mint mobile2 $host")
                                if (m2 != null) {
                                    if (m2.first.contains("token=") && m2.first.contains("watching")) {
                                        tokenCache[detailUrl] = m2.first
                                        tokenRefCache[detailUrl] = referer
                                        return m2.first
                                    }
                                    extractTokenizedWatch(m2.second)?.let { tok ->
                                        val abs = if (tok.startsWith("/")) "https://cimanow.cc$tok" else tok
                                        Log.d(TAG, "minted mobile2 [watch-url] ${abs.take(140)}")
                                        tokenCache[detailUrl] = abs
                                        tokenRefCache[detailUrl] = referer
                                        return abs
                                    }
                                    Log.d(TAG, "mint mobile2 final=${m2.first.take(200)} len=${m2.second.length} cimanow=${m2.second.contains("cimanow")} watching=${m2.second.contains("watching")}")
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "mint $host failed: ${e.message}")
            }
        }
        failCache[detailUrl] = System.currentTimeMillis()
        return null
    }

    /** Detail URL for a watching URL (used to find the watch button). */
    private fun detailOf(watchUrl: String): String {
        return watchUrl.substringBefore("/watching/")
            .let { if (it.endsWith("/")) it.dropLast(1) else it } + "/"
    }

    private suspend fun harvestDetailPage(
        detailHtml: String,
        detailUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = 0
        try {
            if (emitPlayerLinks(detailHtml, detailUrl, subtitleCallback, callback)) emitted++
        } catch (_: Exception) {}
        try {
            val doc = org.jsoup.Jsoup.parse(detailHtml)
            val found = linkedSetOf<String>()
            doc.select("iframe[src], iframe[data-src]").forEach { f ->
                var src = f.attr("src").ifBlank { f.attr("data-src") }.trim()
                if (src.isBlank() || src.contains("youtube", true)) return@forEach
                if (src.startsWith("//")) src = "https:$src"
                else if (src.startsWith("/")) src = fixUrl(src)
                if (src.startsWith("http")) found.add(src)
            }
            val rawScripts = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
            Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(rawScripts).forEach {
                val u = it.groupValues[1].replace("\\/", "/")
                if (u.startsWith("http")) found.add(u)
            }
            Regex("""["']((?:https?:)?//[^"']*?/embed/[^"']*)["']""").findAll(rawScripts).forEach {
                var u = it.groupValues[1].replace("\\/", "/")
                if (u.startsWith("//")) u = "https:$u"
                if (u.startsWith("http")) found.add(u)
            }
            for (u in found) {
                try {
                    if (u.contains(".m3u8") || u.contains(".mp4")) {
                        callback(newExtractorLink(source = this.name, name = "$name Server", url = u) {
                            this.referer = detailUrl
                            this.quality = extractQuality(u)
                            this.type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        })
                        emitted++
                    } else {
                        loadExtractor(u, detailUrl, subtitleCallback) { callback(it); emitted++ }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "detail candidate failed $u: ${e.message}")
                }
            }
        } catch (_: Exception) {}
        Log.d(TAG, "detail harvest emitted=$emitted")
        return emitted > 0
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
            if (!watchUrl.contains("token=")) {
                try {
                    val detailUrl = if (watchUrl.contains("/watching/")) detailOf(watchUrl) else data
                    val d0 = app.get(detailUrl, headers = browserHeaders, referer = mainUrl, interceptor = cfKiller, timeout = 15)
                    val detailHtml = d0.text
                    try {
                        if (harvestDetailPage(detailHtml, detailUrl, subtitleCallback, callback)) return true
                    } catch (e: Exception) {
                        Log.d(TAG, "detail harvest failed: ${e.message}")
                    }
                    val btn = d0.document.select("ul.btns a.shine[href], a[href*=\"loadon\"]")
                        .map { it.attr("href") }.firstOrNull { it.contains("link=") }
                    if (btn != null) {
                        val minted = mintToken(detailUrl, btn)
                        if (minted != null) {
                            watchUrl = minted
                        } else {
                            decodeWatchLink(btn)?.let { w -> watchUrl = w }
                        }
                    }
                    Log.d(TAG, "resolved watchUrl=$watchUrl")
                } catch (e: Exception) {
                    Log.d(TAG, "watch resolve failed: ${e.message}")
                }
            }
            val detailKey = if (data.contains("/watching/")) detailOf(data) else data
            val watchReferer = tokenRefCache[detailKey] ?: mainUrl
            try {
                val noRedir = app.get(
                    watchUrl, headers = browserHeaders, referer = watchReferer,
                    interceptor = cfKiller, timeout = 15, allowRedirects = false
                )
                val loc = noRedir.headers["Location"] ?: noRedir.headers["location"] ?: ""
                if (loc.contains("token=")) {
                    watchUrl = if (loc.startsWith("http")) loc else fixUrl(loc)
                    Log.d(TAG, "token redirect -> $watchUrl")
                }
            } catch (_: Exception) {}

            val res = app.get(
                watchUrl, headers = browserHeaders, referer = watchReferer,
                interceptor = cfKiller, timeout = 15, allowRedirects = true
            )
            Log.d(TAG, "final url=${res.url} len=${res.text.length}")
            val player = decryptPlayer(res.text)
            if (player != null) {
                if (emitPlayerLinks(player, res.url, subtitleCallback, callback)) {
                    return true
                }
            } else {
                Log.d(TAG, "no encrypted player; trying plain page servers")
                try {
                    if (emitPlayerLinks(res.text, res.url, subtitleCallback, callback)) {
                        return true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "plain emit failed: ${e.message}")
                }
            }
            val doc = res.document
            val candidates = linkedMapOf<String, Int>()
            fun add(u: String, q: Int = Qualities.Unknown.value) {
                var v = u.trim().replace("\\/", "/")
                if (v.isBlank() || v == "about:blank") return
                if (v.contains("youtube.com") || v.contains("youtu.be")) return
                if (v.startsWith("//")) v = "https:$v"
                if (!v.startsWith("http")) return
                val prev = candidates[v]
                if (prev == null || (prev == Qualities.Unknown.value && q != Qualities.Unknown.value)) {
                    candidates[v] = q
                }
            }
            doc.select("button[data-server], button[data-src], button[data-embed], button[data-url], a.server-pill, .server-pill, select.server-select option, [data-server], [data-embed]").forEach { el ->
                val label = el.text().trim().ifBlank { el.attr("title").trim() }.ifBlank { null }
                val q = extractQuality(label)
                listOf("data-server", "data-embed", "data-src", "data-url", "data-href", "value", "href").forEach { attr ->
                    val v = el.attr(attr).trim()
                    if (v.startsWith("http")) add(v, q)
                    else if (v.startsWith("/") && v.length > 1 && !v.startsWith("//")) add(fixUrl(v), q)
                }
            }
            doc.select("iframe[src], iframe[data-src]").forEach { f ->
                val src = (f.attr("src").ifBlank { f.attr("data-src") }).trim()
                if (src.isNotBlank()) add(src)
            }
            val rawScripts = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
            val scripts = try { JsUnpacker(rawScripts).unpack() ?: rawScripts } catch (_: Exception) { rawScripts }
            Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(scripts).forEach {
                add(it.groupValues[1], extractQuality(it.groupValues[1]))
            }
            Regex("""["']((?:https?:)?//[^"']*?/embed/[^"']*)["']""").findAll(scripts).forEach {
                var u = it.groupValues[1].replace("\\/", "/")
                if (u.startsWith("//")) u = "https:$u"
                add(u)
            }
            Log.d(TAG, "candidates: ${candidates.keys}")
            for ((u, q) in candidates) {
                try {
                    loadExtractor(u, watchUrl, subtitleCallback) { link ->
                        if (link.quality == Qualities.Unknown.value && q != Qualities.Unknown.value) {
                            link.quality = q
                        }
                        callback(link)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "extractor failed $u: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
        }
        return true
    }
}
