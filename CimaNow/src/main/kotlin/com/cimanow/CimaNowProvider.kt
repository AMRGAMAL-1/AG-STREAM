package com.cimanow

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    /** Strip Arabic wrappers (فيلم/مسلسل/مترجم/مدبلج/...) so external lookups use the English core. */
    private fun cleanEnglishTitle(raw: String): Pair<String, Int?> {
        var s = raw.trim()
        val year = Regex("""(19\d{2}|20\d{2})""").find(s)?.groupValues?.get(1)?.toIntOrNull()
        s = s.replace(Regex("""^(فيلم|مسلسل|مسرحية|برنامج|الحلقة|حلقة)\s+"""), "")
        s = s.replace(Regex("""\s*(مترجم|مترجمة|مترجمه|مدبلج|مدبلجة|مدبلجه|مصري|مصرية|مصرى|بالعربية|للعربية|اون\s*لاين|اونلاين|بجودة\s*عالية|بجوده\s*عاليه|كامل|كاملة|HD|FHD|CAM|TS|2026|رمضان)\s*""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""[|–—_/:]+"""), " ")
        s = s.replace(Regex("""\s*(19\d{2}|20\d{2})\s*"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '|')
        return s to year
    }

    private fun normTitle(s: String): String {
        return s.lowercase().replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private data class SolvedTrap(val finalHtml: String, val pigUrl: String?)

    /**
     * Pure-JVM solver for freex2line stage-1 trap pages (no WebView/JS engine needed).
     * Page contains: trap div (w/h in style) + b64 chunks array + `var e=[...]; var k=N;`
     * d = e[i] xor k  ->  JS holding `kStr = (w*h)+OFFSET`; final = b64decode(chunks) xor kStr.
     */
    private fun solveTrapStage1(body: String): SolvedTrap? {
        try {
            val trap = Regex("""<div id='trap_[^']+'[^>]*style='[^']*width:(\d+)px[^']*height:(\d+)px""").find(body) ?: return null
            val w = trap.groupValues[1].toIntOrNull() ?: return null
            val h = trap.groupValues[2].toIntOrNull() ?: return null
            val arrMatch = Regex("""window\["([A-Za-z0-9]+)"\] = \[(.*?)\];""", RegexOption.DOT_MATCHES_ALL).find(body) ?: return null
            val chunks = Regex(""""([^"]{20,})"""").findAll(arrMatch.groupValues[2]).map { it.groupValues[1] }.toList()
            if (chunks.isEmpty()) return null
            val eMatch = Regex("""var e = \[(.*?)\];\s*var k = (\d+);""", RegexOption.DOT_MATCHES_ALL).find(body) ?: return null
            val eArr = eMatch.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
            val k = eMatch.groupValues[2].toIntOrNull() ?: return null
            if (eArr.isEmpty()) return null
            val d = String(CharArray(eArr.size) { i -> (eArr[i] xor k).toChar() })
            val off = Regex("""\(\s*w\s*\*\s*h\s*\)\s*\+\s*(\d+)""").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val kStr = (w * h + off).toString()
            val decBytes = try { Base64.decode(chunks.joinToString(""), Base64.DEFAULT) } catch (_: Exception) { return null }
            val decStr = String(decBytes, Charsets.ISO_8859_1)
            val out = ByteArray(decStr.length) { i -> (decStr[i].code xor kStr[i % kStr.length].code).toByte() }
            val finalHtml = String(out, Charsets.UTF_8)
            if (!finalHtml.contains("downloadbtn")) return null
            val pig = Regex("""<a[^>]+id=["']downloadbtn["'][^>]+href=["']([^"']+)["']""").find(finalHtml)?.groupValues?.get(1)
            Log.d(TAG, "trap solved w=$w h=$h off=$off finalLen=${finalHtml.length} pig=${pig?.take(80)}")
            return SolvedTrap(finalHtml, pig?.takeIf { it.startsWith("http") })
        } catch (e: Exception) {
            Log.d(TAG, "trap solve failed: ${e.message}")
            return null
        }
    }
    private val vidMapper = ObjectMapper()
    private val VIDROCK_GCM_KEY_HEX =
        "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
    private val VID_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private fun vidrockKey(): SecretKeySpec {
        val hx = VIDROCK_GCM_KEY_HEX
        val b = ByteArray(hx.length / 2)
        for (i in b.indices) b[i] = hx.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        return SecretKeySpec(b, "AES")
    }

    private fun vidrockDecrypt(token: String): String? {
        return try {
            var t = token.trim().replace('-', '+').replace('_', '/')
            while (t.length % 4 != 0) t += "="
            val raw = Base64.decode(t, Base64.NO_WRAP)
            if (raw.size <= 28) return null
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, vidrockKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    /** Cleaned English title -> Cinemeta -> moviedb_id (no API key needed). */
    private suspend fun cinemetaMoviedbId(clean: String, year: Int?, isSeries: Boolean): Int? {
        try {
            if (clean.length < 3 || !clean.any { it.code < 128 && it.isLetter() }) return null
            val kind = if (isSeries) "series" else "movie"
            val enc = try { URLEncoder.encode(clean, "UTF-8") } catch (_: Exception) { clean }
            val txt = app.get("https://v3-cinemeta.strem.io/catalog/$kind/top/search=$enc.json", timeout = 15).text
            if (txt.isBlank()) return null
            val root = try { vidMapper.readTree(txt) } catch (_: Exception) { return null }
            val metas = root.get("metas") ?: return null
            if (!metas.isArray || metas.size() == 0) return null
            val want = normTitle(clean)
            var bestId: String? = null
            for (m in metas) {
                val id = m.get("id")?.asText() ?: continue
                if (!id.startsWith("tt")) continue
                val nm = m.get("name")?.asText() ?: continue
                if (normTitle(nm) == want) { bestId = id; break }
            }
            if (bestId == null) {
                bestId = metas.firstOrNull { it.get("id")?.asText()?.startsWith("tt") == true }
                    ?.get("id")?.asText() ?: return null
            }
            val metaTxt = app.get("https://v3-cinemeta.strem.io/meta/$kind/$bestId.json", timeout = 15).text
            if (metaTxt.isBlank()) return null
            val meta = try { vidMapper.readTree(metaTxt).get("meta") } catch (_: Exception) { return null } ?: return null
            if (year != null) {
                val my = meta.get("year")?.asText()?.filter { it.isDigit() }?.toIntOrNull()
                    ?: meta.get("releaseInfo")?.asText()?.let { Regex("""(19\d{2}|20\d{2})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (my != null && kotlin.math.abs(my - year) > 1) {
                    Log.d(TAG, "cinemeta year mismatch $my vs $year for $clean")
                    return null
                }
            }
            return meta.get("moviedb_id")?.asInt()
        } catch (e: Exception) {
            Log.d(TAG, "cinemeta lookup failed for '$clean': ${e.message}")
            return null
        }
    }

    private suspend fun emitVidrockUrl(
        dec: String,
        tag: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (!dec.startsWith("http") || dec.contains("sample", true) || dec.contains("bigbuckbunny", true)) return false
            if (dec.contains("/playlist/") && !dec.contains(".m3u8") && !Regex("""\.mp4([?#]|$)""").containsMatchIn(dec)) {
                try {
                    val t = app.get(dec, referer = "https://vidrock.net/", timeout = 20, headers = mapOf("Accept" to "application/json,*/*")).text
                    if (!t.isNullOrBlank() && !t.contains("#EXTM3U")) {
                        val arr = try { vidMapper.readTree(t) } catch (_: Exception) { null }
                        if (arr != null && arr.isArray && arr.size() > 0) {
                            var ok = false
                            for (cdn in arr) {
                                val cu = cdn.get("url")?.asText()?.trim()?.takeIf { it.startsWith("http") } ?: continue
                                val res = cdn.get("resolution")?.asText()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                                val q = if (res in 144..4320) res else Qualities.Unknown.value
                                try {
                                    callback(newExtractorLink(source = this.name, name = "$name Vidrock $tag", url = cu) {
                                        this.referer = "https://vidrock.net/"
                                        this.headers = mapOf("Referer" to "https://vidrock.net/", "Origin" to "https://vidrock.net", "User-Agent" to VID_BROWSER_UA)
                                        this.quality = q
                                        this.type = if (cu.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    })
                                    ok = true
                                } catch (_: Exception) {}
                            }
                            if (ok) return true
                        }
                    }
                } catch (_: Exception) {}
            }
            if (dec.contains(".m3u8") || Regex("""\.mp4([?#]|$)""").containsMatchIn(dec)) {
                callback(newExtractorLink(source = this.name, name = "$name Vidrock $tag", url = dec) {
                    this.referer = "https://vidrock.net/"
                    this.headers = mapOf("Referer" to "https://vidrock.net/", "Origin" to "https://vidrock.net", "User-Agent" to VID_BROWSER_UA)
                    this.quality = Qualities.Unknown.value
                    this.type = if (dec.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                })
                return true
            }
            return false
        } catch (_: Exception) { return false }
    }

    /** External fallback: title -> Cinemeta moviedb_id -> Vidrock streams. Returns true if anything emitted. */
    private suspend fun resolveViaVidrock(
        rawTitle: String,
        isSeries: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val (clean, year) = cleanEnglishTitle(rawTitle)
            Log.d(TAG, "vidrock fallback clean='$clean' year=$year series=$isSeries")
            val tmdb = cinemetaMoviedbId(clean, year, isSeries) ?: run {
                Log.d(TAG, "vidrock fallback: no moviedb_id for '$clean'")
                return false
            }
            val api = if (isSeries) "https://vidrock.net/api/tv/$tmdb/${season ?: 1}/${episode ?: 1}"
            else "https://vidrock.net/api/movie/$tmdb"
            Log.d(TAG, "vidrock api $api")
            val txt = try {
                app.get(api, referer = "https://vidrock.net/", timeout = 20, headers = mapOf("Accept" to "application/json", "Origin" to "https://vidrock.net")).text
            } catch (e: Exception) {
                Log.d(TAG, "vidrock api failed: ${e.message}")
                return false
            }
            if (txt.isBlank()) return false
            val root = try { vidMapper.readTree(txt) } catch (_: Exception) { return false }
            if (!root.isObject || root.size() == 0) return false
            var ok = false
            val fields = root.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                val node = entry.value ?: continue
                if (!node.isObject) continue
                val enc = node.get("url")?.asText()?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val dec = vidrockDecrypt(enc) ?: continue
                if (dec.equals("Unavailable", ignoreCase = true)) continue
                if (emitVidrockUrl(dec, entry.key, callback)) ok = true
            }
            if (ok) {
                try {
                    val subApi = if (isSeries) "https://sub.vdrk.site/v2/tv/$tmdb/${season ?: 1}/${episode ?: 1}"
                    else "https://sub.vdrk.site/v2/movie/$tmdb"
                    val stxt = app.get(subApi, referer = "https://vidrock.net/", timeout = 10).text
                    if (!stxt.isNullOrBlank()) {
                        val sarr = try { vidMapper.readTree(stxt) } catch (_: Exception) { null }
                        if (sarr != null && sarr.isArray) {
                            for (s in sarr) {
                                val f = s.get("file")?.asText()?.takeIf { it.isNotBlank() } ?: continue
                                val l = s.get("label")?.asText()?.takeIf { it.isNotBlank() } ?: "Vidrock"
                                try { subtitleCallback(newSubtitleFile(l, fixUrl(f))) } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            Log.d(TAG, "vidrock fallback ok=$ok for '$clean'")
            return ok
        } catch (e: Exception) {
            Log.d(TAG, "vidrock fallback error: ${e.message}")
            return false
        }
    }

    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val tokenRefCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun mintToken(detailUrl: String, btn: String): String? {
        tokenCache[detailUrl]?.let { return it }
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
                        if (body.length > 20000 && body.contains("trap_")) {
                            try {
                                solveTrapStage1(body)?.let { solved ->
                                    Log.d(TAG, "trap stage-1 solved pig=${solved.pigUrl?.take(120)}")
                                    val pig = solved.pigUrl
                                    if (!pig.isNullOrBlank()) {
                                        try {
                                            val pr = app.get(
                                                pig, headers = browserHeaders, referer = finalUrl,
                                                interceptor = cfKiller, timeout = 15, allowRedirects = false
                                            )
                                            val loc = pr.headers["Location"] ?: pr.headers["location"] ?: ""
                                            if (loc.contains("token=") && loc.contains("watching")) {
                                                val abs = if (loc.startsWith("http")) loc else fixUrl(loc)
                                                Log.d(TAG, "minted via pig ${abs.take(140)}")
                                                tokenCache[detailUrl] = abs
                                                tokenRefCache[detailUrl] = finalUrl
                                                return abs
                                            }
                                            Log.d(TAG, "pig step loc=${loc.take(160)} (proof-gated countdown still required)")
                                        } catch (pe: Exception) {
                                            Log.d(TAG, "pig step failed: ${pe.message}")
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "mint $host failed: ${e.message}")
            }
        }
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
            var nativeEmitted = false
            val counting: (ExtractorLink) -> Unit = { nativeEmitted = true; callback(it) }
            for ((u, q) in candidates) {
                try {
                    loadExtractor(u, watchUrl, subtitleCallback) { link ->
                        if (link.quality == Qualities.Unknown.value && q != Qualities.Unknown.value) {
                            link.quality = q
                        }
                        counting(link)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "extractor failed $u: ${e.message}")
                }
            }
            if (nativeEmitted) return true
            try {
                val detailUrl = if (data.contains("/watching/")) detailOf(data) else data
                val isSeries = detailUrl.contains("/selary/")
                var rawTitle: String? = null
                try {
                    val dDoc = app.get(detailUrl, headers = browserHeaders, referer = mainUrl, interceptor = cfKiller, timeout = 15).document
                    rawTitle = dDoc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {}
                if (rawTitle.isNullOrBlank()) {
                    rawTitle = try { URLDecoder.decode(detailUrl.substringAfter("cc/").substringBefore("/watching").replace("-", " "), "UTF-8") } catch (_: Exception) { null }
                }
                if (!rawTitle.isNullOrBlank()) {
                    val season = try { seasonFromSlug(detailUrl) } catch (_: Exception) { null }
                    val episode = try { episodeFromSlug(detailUrl) } catch (_: Exception) { null }
                    if (resolveViaVidrock(rawTitle!!, isSeries, season, episode, subtitleCallback, callback)) {
                        return true
                    }
                } else {
                    Log.d(TAG, "fallback skipped: no title for $detailUrl")
                }
            } catch (e: Exception) {
                Log.d(TAG, "fallback failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
        }
        return false
    }
}
