package com.cimamix

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
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CimaMixProvider : MainAPI() {
    override var mainUrl = "https://cimamix.net"
    override var name = "CimaMix"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val TAG = "CimaMix"
    private val cfKiller by lazy { CloudflareKiller() }

    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/tvshows" to "أحدث المسلسلات",
        "$mainUrl/latest-episodes" to "أحدث الحلقات",
        "$mainUrl/latest-additions" to "آخر الإضافات",
        "$mainUrl/trending" to "الأكثر رواجاً",
        "$mainUrl/top10" to "الأعلى تقييماً",
    )

    private fun fix(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }

    private fun Element.toCardResult(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.contains("/movie/") && !href.contains("/show/") && !href.contains("/watch/") && !href.contains("/watch-show/") && !href.contains("/episode")) return null
        val fullUrl = fixUrl(href)

        var title = this.selectFirst("h3.poster-title")?.text()?.trim()
        if (title.isNullOrBlank()) {
            title = this.selectFirst(".lep-title-main")?.text()?.trim()?.let { main ->
                val meta = this.selectFirst(".lep-title-meta")?.text()?.trim()
                if (!meta.isNullOrBlank()) "$main ($meta)" else main
            }
        }
        if (title.isNullOrBlank()) {
            title = this.selectFirst(".le-title")?.text()?.trim()
        }
        if (title.isNullOrBlank()) {
            title = this.attr("title").trim().takeIf { it.isNotBlank() }
        }
        if (title.isNullOrBlank()) {
            title = this.selectFirst("img.poster-image")?.attr("alt")?.trim()
                ?.removePrefix("فيلم ")?.removePrefix("مسلسل ")?.trim()
        }
        if (title.isNullOrBlank()) {
            title = this.selectFirst("img")?.attr("alt")?.trim()
        }
        if (title.isNullOrBlank()) {
            title = this.text().trim().take(80).ifBlank { null }
        }
        if (title.isNullOrBlank()) return null

        var poster: String? = this.selectFirst("img.poster-image")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".lep-inner img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
        poster = fix(poster)

        val year = Regex("""(19\d{2}|20\d{2})""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(this.text())?.groupValues?.get(1)?.toIntOrNull()
        val isSeries = (href.contains("/show/") || href.contains("/episode")) && !href.contains("/watch/") && !href.contains("/watch-show/")
        val isMovie = !isSeries
        return if (isMovie) {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) "$base&pg=$page" else "$base?pg=$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        Log.d(TAG, "getMainPage ${request.name} -> $url")
        var doc = try {
            app.get(url, referer = mainUrl, interceptor = cfKiller, timeout = 25).document
        } catch (e: Exception) {
            Log.d(TAG, "getMainPage ${request.name} retry after: ${e.message}")
            kotlinx.coroutines.delay(1500)
            app.get(url, referer = mainUrl, interceptor = cfKiller, timeout = 25).document
        }
        var items = doc.select("a.card-hover, a.le-card, a.lep-card").mapNotNull {
            try { it.toCardResult() } catch (_: Exception) { null }
        }
        if (items.isEmpty()) {
            items = doc.select("a[href*=\"/movie/\"], a[href*=\"/show/\"], a[href*=\"/watch/\"], a[href*=\"/watch-show/\"]").mapNotNull { a ->
                try {
                    val hasImg = a.selectFirst("img") != null
                    val hasText = a.text().trim().length > 3
                    if (!hasImg && !hasText) return@mapNotNull null
                    a.toCardResult()
                } catch (_: Exception) { null }
            }.distinctBy { it.url }
        }
        Log.d(TAG, "getMainPage ${request.name} found ${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val candidates = listOf(
            "$mainUrl/search?q=$enc",
            "$mainUrl/search/?q=$enc",
            "$mainUrl/?page=search&q=$enc"
        )
        for (url in candidates) {
            try {
                Log.d(TAG, "search trying $url")
                val doc = app.get(url).document
                var items = doc.select("a.card-hover, a.le-card, a.lep-card").mapNotNull {
                    try { it.toCardResult() } catch (_: Exception) { null }
                }
                if (items.isEmpty()) {
                    items = doc.select("a[href*=\"/movie/\"], a[href*=\"/show/\"], a[href*=\"/watch/\"], a[href*=\"/watch-show/\"]").mapNotNull { a ->
                        if (a.selectFirst("img") == null) return@mapNotNull null
                        try { a.toCardResult() } catch (_: Exception) { null }
                    }.distinctBy { it.url }
                }
                Log.d(TAG, "search $url -> ${items.size}")
                if (items.isNotEmpty()) return items
            } catch (e: Exception) {
                Log.d(TAG, "search $url failed: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun extractYearFromUrl(url: String): Int? {
        return Regex("""-(19\d{2}|20\d{2})(?:-|/|$)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(19\d{2}|20\d{2})""").find(url.substringAfterLast("/"))?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load $url")
        val doc = app.get(url).document

        val isMovie = url.contains("/movie/")
        val isShow = url.contains("/show/")
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        var title: String? = doc.select("h1 span.block").lastOrNull()?.text()?.trim()
        if (title.isNullOrBlank()) title = doc.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrBlank()) title = ogTitle?.substringBefore("—")?.substringBefore("|")?.trim()
            ?.removePrefix("فيلم ")?.removePrefix("مسلسل ")?.trim()
        if (title.isNullOrBlank()) title = doc.title().substringBefore("—").substringBefore("|").trim()
        val finalTitle = title!!.removePrefix("مشاهدة فيلم أون لاين ·").removePrefix("مشاهدة مسلسل أون لاين ·").trim()
        if (finalTitle.isBlank()) throw ErrorLoadingException("No title for $url")

        val poster = fix(ogImage?.takeIf { it.isNotBlank() })
        val plot = ogDesc?.takeIf { it.isNotBlank() }
        val year = extractYearFromUrl(url)
            ?: doc.select("h1 span").firstOrNull()?.text()?.let { Regex("""(19\d{2}|20\d{2})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = doc.select("a[href*=\"/genres?genre=\"]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val ratingText = doc.selectFirst(".netflix-rating-text")?.text()?.trim()
        val score = ratingText?.toFloatOrNull()?.let { Score.from10(it) }
        val recommendations = doc.select("a[href*=\"/movie/\"], a[href*=\"/show/\"]").mapNotNull { a ->
            if (a.selectFirst("img") == null) return@mapNotNull null
            val href = a.attr("href")
            if (href.isBlank() || href == url || !href.contains("/movie/") && !href.contains("/show/")) return@mapNotNull null
            try { a.toCardResult() } catch (_: Exception) { null }
        }.distinctBy { it.url }.take(24)

        if (isMovie || (!isShow && !url.contains("/watch/") && doc.select("a[href*=\"/watch/\"]").isNotEmpty() && !doc.html().contains("المواسم والحلقات"))) {
            var watchUrl: String? = doc.selectFirst("a[href*=\"/watch/\"]")?.attr("href")?.let { fixUrl(it) }
            if (watchUrl.isNullOrBlank()) {
                val ogVideo = doc.selectFirst("meta[property=\"og:video:url\"]")?.attr("content")?.let { fixUrl(it) }
                if (!ogVideo.isNullOrBlank() && !ogVideo.lowercase().contains(".m3u8") && !ogVideo.lowercase().contains(".mp4")) {
                    watchUrl = ogVideo
                }
            }
            if (watchUrl.isNullOrBlank()) {
                watchUrl = url.replace("/movie/", "/watch/")
            }
            Log.d(TAG, "movie $finalTitle -> watch $watchUrl")
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, watchUrl!!) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.recommendations = recommendations.ifEmpty { null }
            }
        }
        if (url.contains("/watch/") || url.contains("/watch-show/")) {
            Log.d(TAG, "direct watch $finalTitle")
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.recommendations = recommendations.ifEmpty { null }
            }
        }
        val navMarks = listOf("/movies", "/tvshows", "/genres", "/actor", "/collection", "/search", "/login", "/register", "/trending", "/top10", "/latest-", "/live-", "/programs", "/plays", "/random", "/year-", "/platforms", "/map", "/actors", "/article")
        val seRe = Regex("""/s(\d+)/e(\d+)""")
        val episodeAnchors = doc.select("a[href]").filter { a ->
            val h = a.attr("href")
            if (h.isBlank() || h == url || h.contains("/movie/") || h == "/") return@filter false
            if (navMarks.any { h == it || h.startsWith("$it/") || h.startsWith("$it?") }) return@filter false
            val t = a.text()
            h.contains("/watch-show/") || h.contains("/watch/") || h.contains("/episode") || h.contains("/ep/") ||
                t.contains("الحلقة") || h.contains("حلقة")
        }

        val episodes = mutableListOf<Episode>()
        for (a in episodeAnchors) {
            val href = fixUrl(a.attr("href"))
            var epText = a.text().trim().ifBlank { a.attr("title").trim() }
            var epNum = seRe.find(a.attr("href"))?.groupValues?.get(2)?.toIntOrNull()
            var seasonNum: Int? = seRe.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            if (epNum == null) {
                epNum = Regex("""الحلقة\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (epNum == null) {
                epNum = Regex("""\b(\d{1,4})\b""").findAll(epText).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            }
            if (seasonNum == null) {
                var parent = a.parent()
                var hops = 0
                while (parent != null && hops < 6 && seasonNum == null) {
                    val sibText = parent.text()
                    seasonNum = Regex("""[Mm]وسم\s*(\d+)""").find(sibText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: arabicSeasonToNumber(sibText)
                    parent = parent.parent()
                    hops++
                }
            }
            val epName = if (epNum != null) "الحلقة $epNum" else epText.ifBlank { "حلقة" }
            episodes.add(newEpisode(href) {
                this.name = epName
                this.episode = epNum
                this.season = seasonNum ?: 1
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
            this.score = score
            this.recommendations = recommendations.ifEmpty { null }
        }
    }

    private fun arabicSeasonToNumber(text: String): Int? {
        val ordinals = mapOf(
            "الأول" to 1, "الاول" to 1,
            "الثاني" to 2, "الثالث" to 3, "الرابع" to 4, "الخامس" to 5,
            "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
            "العاشر" to 10, "الحادي عشر" to 11, "الثاني عشر" to 12
        )
        for ((k, v) in ordinals) {
            if (text.contains(k)) return v
        }
        return null
    }

    private fun extractQuality(label: String?): Int {
        val clean = label?.lowercase()?.trim() ?: return Qualities.Unknown.value
        if (clean.contains("x")) {
            val height = clean.substringAfter("x").filter { it.isDigit() }.toIntOrNull()
            if (height != null && height > 0) return height
        }
        val digits = clean.filter { it.isDigit() }.toIntOrNull()
        if (digits != null && digits >= 144 && digits <= 4320) return digits
        return when {
            clean.contains("4k") || clean.contains("2160") -> 2160
            clean.contains("2k") || clean.contains("1440") -> 1440
            clean.contains("fhd") || clean.contains("1080") -> 1080
            clean.contains("hd") || clean.contains("720") -> 720
            clean.contains("sd") || clean.contains("480") -> 480
            clean.contains("360") -> 360
            else -> Qualities.Unknown.value
        }
    }
    private val PLAYER_SALT_FALLBACK = "dd4a8e5b0d57515d40def6b818fd7e4e"

    data class CmxLink(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("embed_url") val embedUrl: String? = null,
        @JsonProperty("mirror_fallbacks") val mirrorFallbacks: List<String>? = null,
        @JsonProperty("resolve_capable") val resolveCapable: Boolean? = null,
        @JsonProperty("link_id") val linkId: Any? = null
    )

    data class CmxPlayerData(
        @JsonProperty("links") val links: List<CmxLink>? = null,
        @JsonProperty("downloads") val downloads: List<Map<String, Any?>>? = null
    )

    data class CmxSealed(
        @JsonProperty("v") val v: Int? = null,
        @JsonProperty("iv") val iv: String? = null,
        @JsonProperty("tag") val tag: String? = null,
        @JsonProperty("ct") val ct: String? = null,
        @JsonProperty("plain") val plain: Map<String, Any?>? = null,
        @JsonProperty("data") val data: CmxPlayerData? = null
    )

    data class CmxPlayerResp(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("data") val data: CmxPlayerData? = null,
        @JsonProperty("sealed") val sealed: CmxSealed? = null,
        @JsonProperty("message") val message: String? = null
    )

    private fun b64url(s: String): ByteArray {
        var t = s.trim().replace("-", "+").replace("_", "/")
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    /** Flexible fallback: the decrypted payload shape varies; hunt server entries anywhere. */
    private fun extractLinksFlexible(plain: String): List<CmxLink>? {
        try {
            val root = mapper.readTree(plain)
            val keys = if (root.isObject) {
                val names = mutableListOf<String>()
                root.fieldNames().forEach { names.add(it) }
                Log.d(TAG, "payload object keys=$names snippet=${plain.take(220)}")
                names
            } else {
                Log.d(TAG, "payload isArray=${root.isArray} size=${root.size()} snippet=${plain.take(220)}")
                emptyList()
            }
            val out = mutableListOf<CmxLink>()
            fun harvest(node: com.fasterxml.jackson.databind.JsonNode?) {
                if (node == null || node.isNull) return
                if (node.isArray) {
                    node.forEach { harvest(it) }
                    return
                }
                if (!node.isObject) return
                val url = listOf("embed_url", "embedUrl", "embed", "url", "src", "file", "link", "source")
                    .mapNotNull { k -> node.get(k)?.asText()?.takeIf { it.isNotBlank() } }
                    .firstOrNull()
                val name = listOf("server_name", "serverName", "server", "name", "label", "host", "title")
                    .mapNotNull { k -> node.get(k)?.asText()?.takeIf { it.isNotBlank() } }
                    .firstOrNull()
                val mirrors = node.get("mirror_fallbacks")?.takeIf { it.isArray }
                    ?.mapNotNull { it.asText()?.takeIf { s -> s.isNotBlank() } }
                if (url != null) {
                    out.add(CmxLink(serverName = name, embedUrl = url, mirrorFallbacks = mirrors))
                } else {
                    node.fields().forEach { (_, v) ->
                        if (v.isArray || v.isObject) harvest(v)
                    }
                }
            }
            if (root.isArray) {
                harvest(root)
            } else {
                var found = false
                for (k in listOf("links", "servers", "sources", "streams", "embeds", "videos", "data")) {
                    val n = root.get(k)
                    if (n != null && (n.isArray || n.isObject)) {
                        val before = out.size
                        harvest(n)
                        if (out.size > before) found = true
                    }
                }
                if (!found) harvest(root)
            }
            Log.d(TAG, "flexible found ${out.size}")
            return out.ifEmpty { null }
        } catch (e: Exception) {
            Log.d(TAG, "flexible parse failed: ${e.message} snippet=${plain.take(160)}")
            return null
        }
    }

    private fun decryptSealed(sealed: CmxSealed, ticket: String, salt: String): CmxPlayerData? {
        try {
            if (sealed.v == 0 && sealed.plain != null) {
                @Suppress("UNCHECKED_CAST")
                val links = (sealed.plain["links"] as? List<Map<String, Any?>>)?.mapNotNull { m ->
                    val name = m["server_name"] as? String
                    val emb = m["embed_url"] as? String
                    if (emb.isNullOrBlank()) return@mapNotNull null
                    CmxLink(serverName = name, embedUrl = emb)
                }
                return CmxPlayerData(links = links)
            }
            if (sealed.v != 1 || sealed.ct.isNullOrBlank()) return sealed.data
            Log.d(TAG, "sealed v=${sealed.v} ivLen=${sealed.iv?.length} ctLen=${sealed.ct.length} tagLen=${sealed.tag?.length} saltLen=${salt.length}")
            val md = MessageDigest.getInstance("SHA-256")
            val keyBytes = md.digest("$salt|papi-v1|$ticket".toByteArray(Charsets.UTF_8))
            val iv = b64url(sealed.iv ?: return null)
            val ct = b64url(sealed.ct)
            val tag = sealed.tag?.takeIf { it.isNotBlank() }?.let { b64url(it) } ?: ByteArray(0)
            val candidates = if (tag.isNotEmpty()) listOf(ct + tag, ct) else listOf(ct)
            for (buf in candidates) {
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                    val plain = String(cipher.doFinal(buf), Charsets.UTF_8)
                    Log.d(TAG, "decrypt ok len=${plain.length}")
                    val parsed = try { AppUtils.parseJson<CmxPlayerData>(plain) } catch (_: Exception) { null }
                    if (parsed?.links.isNullOrEmpty()) {
                        val flex = extractLinksFlexible(plain)
                        if (!flex.isNullOrEmpty()) return CmxPlayerData(links = flex)
                    }
                    return parsed
                } catch (_: Exception) {}
            }
            Log.d(TAG, "decryptSealed failed: BAD_DECRYPT all variants")
            return sealed.data
        } catch (e: Exception) {
            Log.d(TAG, "decryptSealed failed: ${e.message}")
            return sealed.data
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

    /** vidara.to / gosbagotui.com embeds: POST /api/stream {filecode} -> {streaming_url}. */
    private suspend fun resolveVidara(
        eurl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val host = Regex("""https?://([^/]+)""").find(eurl)?.groupValues?.get(1)?.lowercase() ?: return false
            if (!host.contains("vidara") && !host.contains("gosbagotui")) return false
            val code = eurl.trim().trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('#')
            if (code.isBlank() || code.length > 64 || code.contains(".")) return false
            val origin = "https://" + eurl.substringAfter("://").substringBefore('/')
            val api = "$origin/api/stream"
            val res = app.post(api, headers = mapOf("Origin" to origin), referer = eurl, timeout = 20, json = mapOf("filecode" to code, "device" to "web", "codecs" to emptyList<String>()))
            val txt = res.text
            if (txt.isBlank()) return false
            val parsed = try { AppUtils.parseJson<VidaraResp>(txt) } catch (_: Exception) { null } ?: return false
            val stream = parsed.streamingUrl?.trim()?.takeIf { it.startsWith("http") } ?: return false
            Log.d(TAG, "vidara $code -> ${stream.take(100)}")
            parsed.subtitles?.forEach { s ->
                val f = s.filePath?.takeIf { it.isNotBlank() } ?: return@forEach
                try { subtitleCallback(newSubtitleFile(s.language ?: "sub", fixUrl(f))) } catch (_: Exception) {}
            }
            if (emitM3u8Variants(
                    stream, "$name Vidara", eurl,
                    mapOf("Referer" to eurl, "User-Agent" to BROWSER_UA),
                    callback
                )
            ) return true
            callback(newExtractorLink(source = this.name, name = "$name Vidara", url = stream) {
                this.referer = eurl
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
            })
            return true
        } catch (e: Exception) {
            Log.d(TAG, "vidara resolve failed: ${e.message}")
            return false
        }
    }
    private val VIDROCK_GCM_KEY_HEX =
        "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    private fun vidrockGcmKey(): SecretKeySpec {
        val h = VIDROCK_GCM_KEY_HEX
        val b = ByteArray(h.length / 2)
        for (i in b.indices) b[i] = h.substring(2 * i, 2 * i + 2).toInt(16).toByte()
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
            cipher.init(Cipher.DECRYPT_MODE, vidrockGcmKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

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

    /** Vidrock playlist JSON [{resolution, url}] -> every resolution (Egy-style rows). */
    private suspend fun resolveVidrockVariants(url: String): List<Pair<Int, String>>? {
        if (!url.contains("/playlist/")) return null
        if (url.contains(".m3u8") || Regex("""\.mp4([?#]|$)""").containsMatchIn(url)) return null
        return try {
            val t = app.get(
                url, referer = "https://vidrock.net/", timeout = 20,
                headers = mapOf("Accept" to "application/json,*/*")
            ).text
            if (t.isBlank() || t.contains("#EXTM3U")) return null
            val arr = try { mapper.readTree(t) } catch (_: Exception) { return null }
            if (!arr.isArray || arr.size() == 0) return null
            val out = mutableListOf<Pair<Int, String>>()
            for (cdn in arr) {
                val cu = cdn.get("url")?.asText()?.trim()?.takeIf { it.startsWith("http") } ?: continue
                if (isSampleUrl(cu)) continue
                val res = cdn.get("resolution")?.asText()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                val q = if (res in 144..4320) res else Qualities.Unknown.value
                out.add(q to cu)
            }
            out.distinctBy { it.second }.sortedByDescending { it.first }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** Second hop for playlist-style Vidrock URLs: JSON [{resolution, url}] -> best. */
    private suspend fun resolveVidrockPlaylist(url: String): String? {
        if (!url.contains("/playlist/")) return url
        if (url.contains(".m3u8") || Regex("""\.mp4([?#]|$)""").containsMatchIn(url)) return url
        return try {
            val t = app.get(
                url, referer = "https://vidrock.net/", timeout = 20,
                headers = mapOf("Accept" to "application/json,*/*")
            ).text
            if (t.isBlank() || t.contains("#EXTM3U")) return url
            val arr = try { mapper.readTree(t) } catch (_: Exception) { return url }
            if (!arr.isArray || arr.size() == 0) return url
            var best: String? = null
            var bestRes = -1
            for (cdn in arr) {
                val cu = cdn.get("url")?.asText()?.trim()?.takeIf { it.startsWith("http") } ?: continue
                val res = cdn.get("resolution")?.asText()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                if (res >= bestRes) {
                    bestRes = res
                    best = cu
                }
            }
            best ?: url
        } catch (_: Exception) { url }
    }

    /** Dailymotion Geo embeds: player.html 403s, but the metadata API is open
     *  and carries a direct auto m3u8 (+ mp4 renditions when present). */
    private suspend fun resolveDailymotion(
        eurl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        try {
            val host = Regex("""https?://([^/]+)""").find(eurl)?.groupValues?.get(1)?.lowercase() ?: return false
            if (!host.contains("dailymotion")) return false
            val vid = Regex("""[?&]video=([A-Za-z0-9]+)""").find(eurl)?.groupValues?.get(1)
                ?: Regex("""/video/([A-Za-z0-9]+)""").find(eurl)?.groupValues?.get(1)
                ?: return false
            val meta = "https://www.dailymotion.com/player/metadata/video/$vid"
            val txt = app.get(
                meta, referer = referer, timeout = 20,
                headers = mapOf("Accept" to "application/json", "User-Agent" to BROWSER_UA)
            ).text
            if (txt.isBlank()) return false
            val root = try { mapper.readTree(txt) } catch (_: Exception) { return false }
            val quals = root.get("qualities") ?: return false
            var ok = false
            val qit = quals.fields()
            while (qit.hasNext()) {
                val e = qit.next()
                val arr = e.value
                if (arr == null || !arr.isArray) continue
                for (q in arr) {
                    val u = q.get("url")?.asText()?.trim()?.takeIf { h -> h.startsWith("http") } ?: continue
                    if (isSampleUrl(u)) continue
                    val isM3u8 = u.contains(".m3u8")
                    try {
                        callback(newExtractorLink(source = this.name, name = label?.let { "$name $label" } ?: "$name Dailymotion ${e.key}", url = u) {
                            this.referer = "https://www.dailymotion.com/"
                            this.headers = mapOf(
                                "Referer" to "https://www.dailymotion.com/",
                                "Origin" to "https://www.dailymotion.com",
                                "User-Agent" to BROWSER_UA
                            )
                            this.quality = e.key.filter { c -> c.isDigit() }.toIntOrNull()
                                ?: Qualities.Unknown.value
                            this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        })
                        ok = true
                    } catch (_: Exception) {}
                }
            }
            if (ok) Log.d(TAG, "dailymotion $vid ok")
            return ok
        } catch (e: Exception) {
            Log.d(TAG, "dailymotion resolve failed: ${e.message}")
            return false
        }
    }

    private suspend fun resolveVidrock(
        watchUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val src = "$watchUrl $data"
            val tmdb = Regex("""/(?:movie|show|watch|watch-show)/(\d+)""").find(src)?.groupValues?.get(1)
                ?: return false
            val se = Regex("""/s(\d+)/e(\d+)""").find(watchUrl)
            val isTv = watchUrl.contains("/watch-show/") || watchUrl.contains("/show/") ||
                watchUrl.contains("/episode") || se != null
            val season = se?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = se?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val itemId = if (isTv) "${tmdb}_${season}_${episode}" else tmdb
            val api = if (isTv) "https://vidrock.net/api/tv/$tmdb/$season/$episode"
            else "https://vidrock.net/api/movie/$tmdb"
            Log.d(TAG, "vidrock api $itemId")
            var txt: String? = null
            for (attempt in 1..2) {
                try {
                    txt = app.get(
                        api, referer = "https://vidrock.net/", timeout = 15,
                        headers = mapOf("Accept" to "application/json", "Origin" to "https://vidrock.net")
                    ).text
                    if (!txt.isNullOrBlank()) break
                } catch (e: Exception) {
                    Log.d(TAG, "vidrock api attempt $attempt failed: ${e.message}")
                    if (attempt == 1) {
                        try { kotlinx.coroutines.delay(1200) } catch (_: Exception) {}
                    }
                }
            }
            if (txt.isNullOrBlank()) return false
            val root = try { mapper.readTree(txt) } catch (_: Exception) { return false }
            if (!root.isObject || root.size() == 0) {
                Log.d(TAG, "vidrock empty for $itemId")
                return false
            }
            var ok = false
            val fields = root.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                val node = entry.value
                if (node == null || !node.isObject) continue
                val enc = node.get("url")?.asText()?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val dec = vidrockDecrypt(enc) ?: continue
                if (dec.equals("Unavailable", ignoreCase = true)) continue
                if (!dec.startsWith("http") || isSampleUrl(dec)) continue
                val serverQ = extractQuality(entry.key)
                val serverTag = if (serverQ != Qualities.Unknown.value) " (${qLabel(serverQ)})" else ""
                val variants = try { resolveVidrockVariants(dec) } catch (_: Exception) { null }
                if (!variants.isNullOrEmpty()) {
                    for ((res, vurl) in variants) {
                        val q = if (res != Qualities.Unknown.value) res else serverQ
                        val tag = if (q != Qualities.Unknown.value) " (${qLabel(q)})" else serverTag
                        try {
                            callback(newExtractorLink(source = this.name, name = "$name Vidrock ${entry.key}$tag", url = vurl) {
                                this.referer = "https://vidrock.net/"
                                this.headers = mapOf(
                                    "Referer" to "https://vidrock.net/",
                                    "Origin" to "https://vidrock.net",
                                    "User-Agent" to BROWSER_UA
                                )
                                this.quality = q
                                this.type = if (vurl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            })
                            ok = true
                        } catch (_: Exception) {}
                    }
                    continue
                }
                val surl = resolveVidrockPlaylist(dec) ?: continue
                if (!surl.startsWith("http") || isSampleUrl(surl)) continue
                val isM3u8 = surl.contains(".m3u8")
                try {
                    callback(newExtractorLink(source = this.name, name = "$name Vidrock ${entry.key}$serverTag", url = surl) {
                        this.referer = "https://vidrock.net/"
                        this.headers = mapOf(
                            "Referer" to "https://vidrock.net/",
                            "Origin" to "https://vidrock.net",
                            "User-Agent" to BROWSER_UA
                        )
                        this.quality = serverQ
                        this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    })
                    ok = true
                } catch (_: Exception) {}
            }
            if (!ok) {
                Log.d(TAG, "vidrock $itemId ok=false (no decryptable sources), skipping subs")
                return false
            }
            try {
                val subApi = if (isTv) "https://sub.vdrk.site/v2/tv/$tmdb/$season/$episode"
                else "https://sub.vdrk.site/v2/movie/$tmdb"
                val stxt = app.get(subApi, referer = "https://vidrock.net/", timeout = 10).text
                if (!stxt.isNullOrBlank()) {
                    val sarr = try { mapper.readTree(stxt) } catch (_: Exception) { null }
                    if (sarr != null && sarr.isArray) {
                        for (s in sarr) {
                            val f = s.get("file")?.asText()?.takeIf { it.isNotBlank() } ?: continue
                            val l = s.get("label")?.asText()?.takeIf { it.isNotBlank() } ?: "Vidrock"
                            try { subtitleCallback(newSubtitleFile(l, fixUrl(f))) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
            Log.d(TAG, "vidrock $itemId ok=$ok")
            return ok
        } catch (e: Exception) {
            Log.d(TAG, "vidrock resolve failed: ${e.message}")
            return false
        }
    }

    private suspend fun emitUrl(
        eurl: String,
        referer: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        allowGo: Boolean = true,
        label: String? = null
    ): Boolean {
        if (resolveVidara(eurl, subtitleCallback, callback)) return true
        if (resolveDailymotion(eurl, referer, subtitleCallback, callback, label)) return true
        if (allowGo && isGoLink(eurl)) {
            if (resolveGo(eurl, referer, quality, subtitleCallback, callback, label)) return true
        }
        try {
            val host = Regex("""https?://([^/]+)""").find(eurl)?.groupValues?.get(1)?.lowercase() ?: ""
            if (HARVEST_FIRST.any { host.contains(it) }) {
                if (harvestEmbed(eurl, referer, quality, subtitleCallback, callback, label)) return true
            }
        } catch (_: Exception) {}
        var emitted = false
        try {
            loadExtractor(eurl, referer, subtitleCallback) { link ->
                if (link.quality == Qualities.Unknown.value && quality != Qualities.Unknown.value) {
                    link.quality = quality
                }
                emitted = true
                callback(link)
            }
        } catch (e: Exception) {
            Log.d(TAG, "extractor failed $eurl: ${e.message}")
        }
        return emitted
    }

    private fun isGoLink(url: String): Boolean {
        return Regex("""https?://[^/]*cimamix[^/]*/go/""").containsMatchIn(url.trim())
    }

    /** XUpload-family packed player: base-36/62 Dean-Edwards eval (JsUnpacker can't). */
    private fun toBase36(n: Int): String {
        val alpha = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (n < 36) return alpha[n].toString()
        var v = n
        var s = ""
        while (v > 0) {
            s = alpha[v % 36] + s
            v /= 36
        }
        return s
    }

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
            for (radix in radices) {
                try {
                    var p = payload
                    for (idx in dict.size - 1 downTo 0) {
                        val token = if (radix == 36) toBase36(idx)
                        else {
                            val a62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                            if (idx < 62) a62[idx].toString() else {
                                var v = idx
                                var s = ""
                                while (v > 0) {
                                    s = a62[v % 62] + s
                                    v /= 62
                                }
                                s
                            }
                        }
                        p = Regex("\\b" + Regex.escape(token) + "\\b").replace(p, dict[idx])
                    }
                    if (p.contains("m3u8") && (p.contains("jwplayer") || p.lowercase().contains("playerjs") || p.contains("Playerjs") || p.contains("file") || p.contains("source"))) {
                        Log.d(TAG, "xupload unpacked radix $radix len=${p.length}")
                        return p
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

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

    /** Follow the internal /go/<token> redirector to the real embed host and harvest targets. */
    private suspend fun resolveGo(
        eurl: String,
        referer: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String? = null
    ): Boolean {
        var ok = false
        try {
            Log.d(TAG, "resolving go ${eurl.take(80)}")
            val res = app.get(eurl, referer = referer, interceptor = cfKiller, timeout = 20)
            val finalUrl = res.url
            Log.d(TAG, "go final=${finalUrl.take(120)}")
            if (finalUrl.startsWith("http") && finalUrl != eurl && !isGoLink(finalUrl)) {
                if (emitUrl(finalUrl, eurl, quality, subtitleCallback, callback, allowGo = false, label = label)) ok = true
            }
            val doc = res.document
            val inner = linkedSetOf<String>()
            doc.select("iframe[src]").forEach { f ->
                val s = cleanUrl(f.attr("src").trim())
                if (s.isNotBlank() && !s.contains("youtube")) {
                    inner.add(when {
                        s.startsWith("//") -> "https:$s"
                        s.startsWith("/") -> fixUrl(s)
                        else -> s
                    })
                }
            }
            val raw = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
            var scripts = try { JsUnpacker(raw).unpack() ?: raw } catch (_: Exception) { raw }
            unpackXUpload(raw)?.let { scripts += "\n$it" }
            Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(scripts).forEach {
                inner.add(cleanUrl(it.groupValues[1]))
            }
            Regex("""["']((?:https?:)?//[^"']*?/embed/[^"']*)["']""").findAll(scripts).forEach {
                var u = cleanUrl(it.groupValues[1])
                if (u.startsWith("//")) u = "https:$u"
                inner.add(u)
            }
            Regex("""["'](https?://[^"']+)["']""").findAll(scripts).forEach { m ->
                val u = cleanUrl(m.groupValues[1])
                if (u.contains("vidara") || u.contains("gosbagotui") || u.contains("filemoon") || u.contains("voe") ||
                    u.contains("mixdrop") || u.contains("uqload") || u.contains("dood") || u.contains("streamtape") ||
                    u.contains("abyss") || u.contains("lulustream") || u.contains("firestream") || u.contains("byse") ||
                    u.contains("vidmoly") || u.contains("vidrock") || u.contains("vidsrc") ||
                    u.contains("liivideo") || u.contains("1vid") || u.contains("vidspeed") || u.contains("listeamed") ||
                    u.contains("hgcloud") || u.contains("dsvplay") || u.contains("ds2play") || u.contains("turbovid") ||
                    u.contains("playmogo") || u.contains("luluvd") || u.contains("abyssplayer") || u.contains("ok.ru") ||
                    u.contains("vk.com") || u.contains("dailymotion")
                ) inner.add(u)
            }
            Log.d(TAG, "go inner=$inner")
            for (u in inner) {
                if (!u.startsWith("http") || isGoLink(u)) continue
                if (emitUrl(u, eurl, quality, subtitleCallback, callback, allowGo = false, label = label)) {
                    ok = true
                    continue
                }
                if (harvestEmbed(u, eurl, quality, subtitleCallback, callback, label)) ok = true
            }
        } catch (e: Exception) {
            Log.d(TAG, "go resolve failed: ${e.message}")
        }
        return ok
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
        "luluvdo", "okhd", "film77", "vidoba", "mp4plus", "anafast", "vidspeed",
        "upzur", "abstream", "1vid", "liivideo", "vidmoly", "filemoon", "uqload",
        "dsvplay", "ds2play", "playmogo", "doodstream", "dood.watch", "dood.li",
        "dooood", "d0o0d", "listeamed", "vidhideplus", "vidrock", "abyssplayer",
        "hgcloud", "turbovid", "streamhls"
    )

    /** Fetch an embed page and hunt playable files: packed JS, direct files, nested iframes. */
    /** Follow JS-redirect stubs (listeamed/morencius-style): window.location.replace / meta refresh. */
    private fun jsRedirect(body: String, base: String): String? {
        val r = Regex("""window\.location\.replace\(\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
            ?: Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
            ?: Regex("""http-equiv=["']refresh["'][^>]+?url=(https?://[^"'\s>]+)""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        if (r == null || r == base) return null
        return r
    }

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
            val res = app.get(eurl, headers = pageHeaders, referer = referer, interceptor = cfKiller, timeout = 20)
            if (harvestPage(res.text, res.document, eurl, quality, subtitleCallback, callback, label)) return true
            val redir = jsRedirect(res.text, eurl)
            if (redir != null) {
                Log.d(TAG, "harvest redirect ${redir.take(100)}")
                try {
                    val r2 = app.get(redir, headers = pageHeaders, referer = eurl, interceptor = cfKiller, timeout = 20)
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
        val linkTag = if (quality != Qualities.Unknown.value) " (${qLabel(quality)})" else ""
        val linkName = (label?.takeIf { it.isNotBlank() }?.let { "$name $it" } ?: "$name Server") + linkTag
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
            if (!nu.startsWith("http")) return@forEach
            if (emitUrl(nu, base, quality, subtitleCallback, callback, allowGo = false)) ok = true
        }
        if (!ok) {
            Log.d(TAG, "harvest yielded nothing $base len=${body.length} snippet=${body.take(200).replace("\n", " ")}")
        }
        return ok
    }

    private suspend fun fetchPlayerLinks(
        type: String,
        contentId: String,
        ticket: String,
        salt: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        episodeId: String? = null,
        season: String? = null,
        episode: String? = null
    ): Boolean {
        var ok = false
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val qBase = StringBuilder("type=${enc(type)}&id=${enc(contentId)}")
        if (!episodeId.isNullOrBlank() && episodeId != "0") qBase.append("&episode_id=${enc(episodeId)}")
        if (!season.isNullOrBlank()) qBase.append("&season=${enc(season)}")
        if (!episode.isNullOrBlank()) qBase.append("&episode=${enc(episode)}")
        qBase.append("&ticket=${enc(ticket)}")
        val q = qBase.toString()
        val urls = listOf("$mainUrl/api/player?$q", "$mainUrl/?page=api-player&$q")
        val headers = mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to referer
        )
        for (u in urls) {
            try {
                Log.d(TAG, "player API -> $u")
                val text = app.get(u, headers = apiHeaders, referer = referer, interceptor = cfKiller).text
                if (text.isBlank() || (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("["))) {
                    Log.d(TAG, "player API non-json, trying next")
                    continue
                }
                val resp = AppUtils.parseJson<CmxPlayerResp>(text)
                if (resp.ok == false) {
                    Log.d(TAG, "player API ok=false: ${resp.message}")
                    continue
                }
                var pdata = resp.data
                if (pdata == null && resp.sealed != null) {
                    pdata = decryptSealed(resp.sealed, ticket, salt)
                }
                if (pdata == null) continue
                Log.d(TAG, "player servers=${pdata.links?.size} downloads=${pdata.downloads?.size}")
                val okRef = java.util.concurrent.atomic.AtomicBoolean(false)
                val safeCb: (ExtractorLink) -> Unit = { link ->
                    synchronized(this) { callback(link) }
                }
                val safeSub: (SubtitleFile) -> Unit = { sub ->
                    synchronized(this) { subtitleCallback(sub) }
                }
                coroutineScope {
                    pdata.links?.map { lnk ->
                        async {
                            Log.d(TAG, "server=${lnk.serverName} embed=${lnk.embedUrl?.take(90)} mirrors=${lnk.mirrorFallbacks?.size}")
                            val quality = extractQuality(lnk.serverName)
                            val chain = mutableListOf<String>()
                            lnk.embedUrl?.takeIf { it.isNotBlank() }?.let { chain.add(it) }
                            lnk.mirrorFallbacks?.filter { !it.isNullOrBlank() }?.let { chain.addAll(it) }
                            chain.distinct().forEach { eu ->
                                var eurl = cleanUrl(eu)
                                if (eurl.startsWith("//")) eurl = "https:$eurl"
                                else if (eurl.startsWith("/")) eurl = fixUrl(eurl)
                                if (!eurl.startsWith("http")) return@forEach
                                if (emitUrl(eurl, referer, quality, safeSub, safeCb, label = lnk.serverName)) {
                                    okRef.set(true)
                                } else {
                                    Log.d(TAG, "player link yielded nothing $eurl")
                                }
                            }
                        }
                    }?.awaitAll()
                }
                if (okRef.get()) ok = true
                val skipExt = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".css", ".js", ".srt", ".vtt", ".ass", ".ico", ".svg")
                pdata.downloads?.let { dls ->
                    dls.forEach { m ->
                        m.values.filterIsInstance<String>().forEach { s ->
                            Regex("""https?://[^\s"']+""").findAll(s).forEach { mt ->
                                val du = cleanUrl(mt.value)
                                if (!du.startsWith("http")) return@forEach
                                val low = du.lowercase().substringBefore("?")
                                if (skipExt.any { low.endsWith(it) }) return@forEach
                                Log.d(TAG, "download candidate ${du.take(110)}")
                                if (emitUrl(du, referer, Qualities.Unknown.value, safeSub, safeCb)) {
                                    okRef.set(true)
                                }
                            }
                        }
                    }
                }
                if (okRef.get()) ok = true
                if (ok) return true
            } catch (e: Exception) {
                Log.d(TAG, "player API $u failed: ${e.message}")
            }
        }
        return ok
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
            var html = ""
            if (!data.contains("/watch/") && !data.contains("/watch-show/")) {
                try {
                    val d0 = app.get(data, referer = mainUrl)
                    html = d0.text
                    val w = d0.document.selectFirst("a[href*=\"/watch-show/\"], a[href*=\"/watch/\"]")?.attr("href")
                        ?: Regex("""meta[^>]+property=["']og:video:url["'][^>]+content=["']([^"']+)""").find(html)?.groupValues?.get(1)
                    if (!w.isNullOrBlank()) watchUrl = fixUrl(w)
                } catch (e: Exception) {
                    Log.d(TAG, "watch resolve failed: ${e.message}")
                }
            }
            val wdoc = app.get(watchUrl, referer = mainUrl, interceptor = cfKiller)
            if (html.isBlank()) html = wdoc.text
            val doc = wdoc.document
            try {
                resolveVidrock(watchUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d(TAG, "vidrock path failed: ${e.message}")
            }
            try {
                val ticket = Regex("""playerTicket["']?\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
                val cid = Regex("""contentId["']?\s*[:=]\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
                    ?: Regex("""\bcontent_id["']?\s*[:=]\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
                val ptype = Regex("""fetchPlayer\(\{\s*type:\s*'(\w+)'""").find(html)?.groupValues?.get(1)
                    ?: if (watchUrl.contains("/watch-show/") || watchUrl.contains("/episode") || html.contains("season")) "show" else "movie"
                val seUrl = Regex("""/s(\d+)/e(\d+)""").find(watchUrl)
                var epId = Regex("""activeEpId\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)
                    ?: Regex("""episode_id\s*[:=]\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
                var seasonNum = seUrl?.groupValues?.get(1)
                    ?: Regex("""fallbackSeason\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)
                var epNum = seUrl?.groupValues?.get(2)
                    ?: Regex("""fallbackEpisode\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)
                if (epId.isNullOrBlank() || epId == "0") {
                    try {
                        val epJson = Regex(""""id"\s*:\s*(\d+)\s*,\s*"season_id"\s*:\s*\d+\s*,\s*"season_number"\s*:\s*(\d+)[^}]*?"episode_number"\s*:\s*(\d+)""")
                            .findAll(html).toList()
                        val wantS = seasonNum?.toIntOrNull()
                        val wantE = epNum?.toIntOrNull()
                        val pick = epJson.firstOrNull {
                            (wantS == null || it.groupValues[2].toIntOrNull() == wantS) &&
                                (wantE == null || it.groupValues[3].toIntOrNull() == wantE)
                        } ?: epJson.firstOrNull()
                        if (pick != null) {
                            epId = pick.groupValues[1]
                            if (seasonNum.isNullOrBlank()) seasonNum = pick.groupValues[2]
                            if (epNum.isNullOrBlank()) epNum = pick.groupValues[3]
                        }
                    } catch (_: Exception) {}
                }
                Log.d(TAG, "player ticket found=${!ticket.isNullOrBlank()} cid=$cid type=$ptype epId=$epId s=$seasonNum e=$epNum")
                if (!ticket.isNullOrBlank() && !cid.isNullOrBlank()) {
                    val salt = Regex("""__PLAYER_API_SALT\s*=\s*"([^"]+)""").find(html)?.groupValues?.get(1)
                        ?: PLAYER_SALT_FALLBACK
                    Log.d(TAG, "salt=${salt.take(8)}... len=${salt.length}")
                    if (fetchPlayerLinks(ptype, cid, ticket, salt, watchUrl, subtitleCallback, callback, epId, seasonNum, epNum)) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "player API path failed: ${e.message}")
            }
            val candidates = linkedMapOf<String, Pair<Int, String?>>()
            fun addCandidate(url: String, quality: Int = Qualities.Unknown.value, label: String? = null) {
                val u = cleanUrl(url)
                if (u.isBlank() || u == "about:blank") return
                if (u.contains("youtube.com") || u.contains("youtu.be")) return
                val prev = candidates[u]
                if (prev == null || (prev.first == Qualities.Unknown.value && quality != Qualities.Unknown.value)) {
                    candidates[u] = Pair(quality, label ?: prev?.second)
                }
            }
            doc.select("iframe[src]").forEach { f ->
                val src = f.attr("src").trim()
                if (src.startsWith("//")) addCandidate("https:$src")
                else if (src.startsWith("http")) addCandidate(src)
                else if (src.startsWith("/")) addCandidate(fixUrl(src))
            }
            listOf(
                "meta[property=\"og:video:secure_url\"]",
                "meta[property=\"og:video:url\"]",
                "meta[name=\"twitter:player\"]"
            ).forEach { sel ->
                doc.selectFirst(sel)?.attr("content")?.trim()?.takeIf { it.isNotBlank() }?.let {
                    addCandidate(if (it.startsWith("//")) "https:$it" else it)
                }
            }
            doc.select(".server-pill, button.server-pill, a.server-pill, .server-select option, select.server-select option").forEach { el ->
                val label = el.text().trim().ifBlank { el.attr("title").trim() }.ifBlank { null }
                val q = extractQuality(label)
                listOf("data-server", "data-src", "data-embed", "data-watch", "data-url", "data-href", "value", "href").forEach { attr ->
                    val v = el.attr(attr)?.trim()
                    if (!v.isNullOrBlank() && v.startsWith("http")) addCandidate(v, q, label)
                    else if (!v.isNullOrBlank() && v.startsWith("/embed/")) addCandidate(fixUrl(v), q, label)
                }
            }
            doc.select("[data-server], [data-embed], [data-src]").forEach { el ->
                val label = el.text().trim().ifBlank { null }
                val q = extractQuality(label)
                listOf("data-server", "data-embed", "data-src", "data-url").forEach { attr ->
                    val v = el.attr(attr)?.trim()
                    if (!v.isNullOrBlank() && v.startsWith("http")) addCandidate(v, q, label)
                    else if (!v.isNullOrBlank() && v.startsWith("/embed/")) addCandidate(fixUrl(v), q, label)
                }
            }
            val rawScripts = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
            var scripts = try { JsUnpacker(rawScripts).unpack() ?: rawScripts } catch (_: Exception) { rawScripts }
            unpackXUpload(rawScripts)?.let { scripts += "\n$it" }
            Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(scripts).forEach {
                addCandidate(it.groupValues[1], extractQuality(it.groupValues[1]))
            }
            Regex("""["']((?:https?:)?//[^"']*?/embed/[^"']*)["']""").findAll(scripts).forEach {
                var u = cleanUrl(it.groupValues[1])
                if (u.startsWith("//")) u = "https:$u"
                addCandidate(u)
            }
            Regex("""["'](https?://[^"']+)["']""").findAll(scripts).forEach { m ->
                val u = cleanUrl(m.groupValues[1])
                if (u.contains("/embed/") || u.contains("filemoon") || u.contains("voe") || u.contains("mixdrop") ||
                    u.contains("uqload") || u.contains("dood") || u.contains("streamtape") || u.contains("vidmoly") ||
                    u.contains("vidara") || u.contains("gosbagotui") ||
                    u.contains("vidsrc") || u.contains("ok.ru") || u.contains("mp4") || u.contains("m3u8")
                ) {
                    if (!u.contains("googletag") && !u.contains("facebook") && !u.contains("google-analytics")) {
                        addCandidate(u)
                    }
                }
            }

            Log.d(TAG, "candidates: ${candidates.keys}")
            var emitted = 0

            for ((c, meta) in candidates) {
                val quality = meta.first
                try {
                    if (c.contains("/embed/")) {
                        Log.d(TAG, "resolving embed $c")
                        val edoc = app.get(c, referer = watchUrl).document
                        val inner = linkedSetOf<String>()
                        edoc.select("iframe[src]").forEach { f ->
                            val s = f.attr("src").trim()
                            if (s.isNotBlank() && !s.contains("youtube")) {
                                inner.add(if (s.startsWith("//")) "https:$s" else fixUrl(s))
                            }
                        }
                        val eraw = edoc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
                        var escripts = try { JsUnpacker(eraw).unpack() ?: eraw } catch (_: Exception) { eraw }
                        unpackXUpload(eraw)?.let { escripts += "\n$it" }
                        Regex("""["'](https?://[^"']+(?:m3u8|mp4)[^"']*)["']""").findAll(escripts).forEach {
                            inner.add(cleanUrl(it.groupValues[1]))
                        }
                        Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(edoc.html()).forEach {
                            val s = cleanUrl(it.groupValues[1])
                            if (!s.contains("youtube") && s.startsWith("http")) inner.add(s)
                        }
                        Log.d(TAG, "embed $c -> $inner")
                        for (u in inner) {
                            if (emitUrl(u, c, quality, subtitleCallback, callback, label = meta.second)) emitted++
                            else if (harvestEmbed(u, c, quality, subtitleCallback, callback, meta.second)) emitted++
                        }
                        if (inner.isEmpty()) {
                            if (emitUrl(c, watchUrl, quality, subtitleCallback, callback, label = meta.second)) emitted++
                        }
                    } else if (c.startsWith("http")) {
                        if (emitUrl(c, watchUrl, quality, subtitleCallback, callback, label = meta.second)) emitted++
                        else if (harvestEmbed(c, watchUrl, quality, subtitleCallback, callback, meta.second)) emitted++
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "candidate $c error: ${e.message}")
                }
            }
            Log.d(TAG, "loadLinks fallback emitted=$emitted")
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
        }
        return true
    }
}
