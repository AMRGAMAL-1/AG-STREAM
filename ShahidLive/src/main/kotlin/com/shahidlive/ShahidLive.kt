package com.shahidlive

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ShahidLiveProvider : MainAPI() {
    override var mainUrl = "https://shahid.live"
    override var name = "ShahidLive"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbKey = "8e82bcb5db3dcddb25b9b2af100ffdde"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val imgApi = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "$tmdbApi/trending/all/day" to "ترند الآن",
        "$tmdbApi/discover/movie?sort_by=popularity.desc" to "أحدث الأفلام",
        "$tmdbApi/discover/tv?sort_by=popularity.desc" to "أحدث المسلسلات",
        "$tmdbApi/discover/movie?with_original_language=ar&sort_by=popularity.desc" to "أفلام عربية",
        "$tmdbApi/discover/tv?with_original_language=ar&sort_by=popularity.desc" to "مسلسلات عربية",
        "$tmdbApi/discover/tv?with_original_language=tr&sort_by=popularity.desc" to "مسلسلات تركية",
        "$tmdbApi/discover/tv?with_genres=16&with_original_language=ja" to "أنمي",
        "$tmdbApi/movie/top_rated" to "الأعلى تقييماً: أفلام",
        "$tmdbApi/tv/top_rated" to "الأعلى تقييماً: مسلسلات",
    )

    private fun tmdbUrl(path: String, page: Int, extra: String = ""): String {
        val sep = if (path.contains("?")) "&" else "?"
        return "$path${sep}language=ar&api_key=$tmdbKey&page=$page$extra"
    }

    private fun guessType(requestPath: String, mediaType: String?): String {
        if (mediaType == "movie" || mediaType == "tv") return mediaType
        return if (requestPath.contains("/tv") || requestPath.contains("tv/")) "tv" else "movie"
    }

    private fun itemToResult(item: TmdbItem, defaultType: String): SearchResponse? {
        val type = guessType("", item.mediaType ?: defaultType)
        if (type != "movie" && type != "tv") return null
        val title = (item.title ?: item.name)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val id = item.id ?: return null
        val url = "$mainUrl/watch/$type/$id"
        val poster = item.posterPath?.let { "$imgApi/w500$it" }
        val year = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
        return if (type == "movie") {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private suspend fun fetchList(url: String, defaultType: String): List<SearchResponse> {
        return try {
            val text = app.get(url).text
            val page = parseJson<TmdbPage>(text)
            page.results?.mapNotNull { itemToResult(it, defaultType) }?.distinctBy { it.url } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val defaultType = if (request.data.contains("/tv") && !request.data.contains("movie")) "tv" else "movie"
        val items = fetchList(tmdbUrl(request.data, page), defaultType)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return fetchList("$tmdbApi/search/multi?language=ar&api_key=$tmdbKey&query=$encoded&page=1", "")
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val items = fetchList("$tmdbApi/search/multi?language=ar&api_key=$tmdbKey&query=$encoded&page=$page", "")
        return newSearchResponseList(items, items.isNotEmpty())
    }

    private fun parseWatchUrl(url: String): Triple<String, Int, List<Int>>? {
        val tail = url.substringAfter("/watch/").substringBefore("?").trim().trim('/')
        if (tail.isBlank()) return null
        val parts = tail.split("/")
        if (parts.size < 2) return null
        val type = parts[0]
        if (type != "movie" && type != "tv") return null
        val id = parts[1].toIntOrNull() ?: return null
        val rest = parts.drop(2).mapNotNull { it.toIntOrNull() }
        return Triple(type, id, rest)
    }

    override suspend fun load(url: String): LoadResponse? {
        val (type, id, _) = parseWatchUrl(url) ?: return null
        val detailText = try {
            app.get("$tmdbApi/$type/$id?language=ar&api_key=$tmdbKey&append_to_response=external_ids").text
        } catch (e: Exception) {
            return null
        }
        val detail = try {
            parseJson<TmdbDetail>(detailText)
        } catch (e: Exception) {
            return null
        }
        val title = (detail.title ?: detail.name)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = detail.posterPath?.let { "$imgApi/w500$it" }
        val background = detail.backdropPath?.let { "$imgApi/original$it" }
        val plot = detail.overview?.takeIf { it.isNotBlank() }
        val year = (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.toIntOrNull()
        val score = detail.voteAverage?.let { Score.from10(it) }
        val tags = detail.genres?.mapNotNull { it.name }

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/watch/movie/$id") {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        detail.seasons?.filter { (it.seasonNumber ?: 0) >= 1 }?.sortedBy { it.seasonNumber }?.forEach { season ->
            val sNum = season.seasonNumber ?: return@forEach
            val seasonText = try {
                app.get("$tmdbApi/tv/$id/season/$sNum?language=ar&api_key=$tmdbKey").text
            } catch (e: Exception) {
                return@forEach
            }
            val seasonDetail = try {
                parseJson<TmdbSeason>(seasonText)
            } catch (e: Exception) {
                return@forEach
            }
            seasonDetail.episodes?.sortedBy { it.episodeNumber }?.forEach { ep ->
                val epNum = ep.episodeNumber ?: return@forEach
                episodes.add(
                    newEpisode("$mainUrl/watch/tv/$id/$sNum/$epNum") {
                        this.name = ep.name?.takeIf { it.isNotBlank() } ?: "الحلقة $epNum"
                        this.season = sNum
                        this.episode = epNum
                        this.posterUrl = ep.stillPath?.let { "$imgApi/w500$it" } ?: poster
                    }
                )
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = tags
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
                if (low.contains("streamcash.to")) return
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
                    if (ext.url.lowercase().contains("streamcash.to")) return@forEach
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

    private suspend fun imdbId(tmdbId: Int): String? {
        return try {
            val text = app.get("$tmdbApi/movie/$tmdbId?language=ar&api_key=$tmdbKey&append_to_response=external_ids").text
            parseJson<TmdbDetail>(text).externalIds?.imdbId
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun seriesImdbId(tmdbId: Int): String? {
        return try {
            val text = app.get("$tmdbApi/tv/$tmdbId?language=ar&api_key=$tmdbKey&append_to_response=external_ids").text
            parseJson<TmdbDetail>(text).externalIds?.imdbId
        } catch (e: Exception) {
            null
        }
    }

    /** Standard embed: dedicated extractors first, generic harvester fallback. */
    private suspend fun resolveGeneric(
        embedUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            loadExtractor(embedUrl, referer, subtitleCallback) { collected.add(it) }
        } catch (_: Exception) { }
        if (collected.isEmpty()) {
            try {
                harvestEmbed(embedUrl, serverName, referer, subtitleCallback, callback)
            } catch (_: Exception) { }
        } else {
            collected.filterNot { it.url.lowercase().contains("streamcash.to") }.forEach { ext ->
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

    /**
     * VidSrc TO chain: vidsrc.to page -> vsembed.ru iframe ->
     * /vs_src.php JSON {"src": stellarUrl} -> harvest the player page.
     */
    private suspend fun resolveVsEmbed(
        vidsrcUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val pageHtml = app.get(vidsrcUrl, referer = mainUrl).text
            val iframeSrc = Regex("""<iframe[^>]+src\s*=\s*["']([^"']*vsembed\.ru[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(pageHtml)?.groupValues?.get(1)?.trim()
                ?: Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(pageHtml)?.groupValues?.get(1)?.trim()
            if (iframeSrc.isNullOrBlank()) {
                resolveGeneric(vidsrcUrl, serverName, mainUrl, subtitleCallback, callback)
                return
            }
            var vsembedUrl = iframeSrc.replace("&amp;", "&").trim()
            if (vsembedUrl.startsWith("//")) vsembedUrl = "https:$vsembedUrl"
            if (!vsembedUrl.startsWith("http")) {
                vsembedUrl = try {
                    java.net.URL(java.net.URL(vidsrcUrl), vsembedUrl).toString()
                } catch (_: Exception) {
                    resolveGeneric(vidsrcUrl, serverName, mainUrl, subtitleCallback, callback)
                    return
                }
            }
            val vsembedHtml = try {
                app.get(vsembedUrl, referer = vidsrcUrl).text
            } catch (_: Exception) {
                resolveGeneric(vidsrcUrl, serverName, mainUrl, subtitleCallback, callback)
                return
            }
            var apiPath = Regex("""data-api\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(vsembedHtml)?.groupValues?.get(1)?.replace("&amp;", "&")?.trim()
            if (apiPath.isNullOrBlank()) {
                harvestEmbed(vsembedUrl, serverName, vidsrcUrl, subtitleCallback, callback)
                return
            }
            if (apiPath.startsWith("//")) apiPath = "https:$apiPath"
            val apiUrl = if (apiPath.startsWith("http")) apiPath else try {
                java.net.URL(java.net.URL(vsembedUrl), apiPath).toString()
            } catch (_: Exception) {
                harvestEmbed(vsembedUrl, serverName, vidsrcUrl, subtitleCallback, callback)
                return
            }
            val playerUrl = try {
                val apiText = app.get(apiUrl, referer = vsembedUrl).text
                parseJson<VsSrc>(apiText).src?.trim()
            } catch (_: Exception) {
                null
            }
            if (playerUrl.isNullOrBlank()) {
                harvestEmbed(vsembedUrl, serverName, vidsrcUrl, subtitleCallback, callback)
                return
            }
            var done = false
            try {
                done = harvestEmbed(playerUrl, serverName, vsembedUrl, subtitleCallback, callback)
            } catch (_: Exception) { }
            if (!done) {
                resolveGeneric(playerUrl, serverName, vsembedUrl, subtitleCallback, callback)
            }
        } catch (_: Exception) {
            try {
                resolveGeneric(vidsrcUrl, serverName, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) { }
        }
    }

    /**
     * VidSrc Buzz / VidEm player family (vidsrc.buzz, videm.xyz):
     * page embeds var Q = {type,id,s,e,t,ssr:{servers:[{ref,k,name}]}};
     * mint each server via {apiBase}?a=play&ref=..&t=.. -> {"url":"/_stream?id=..","type":"hls"}.
     * Arabic servers are emitted first so fast-play picks them.
     */
    private fun extractBalancedJson(html: String, marker: String): String? {
        val start = html.indexOf(marker)
        if (start < 0) return null
        val open = html.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        var i = open
        while (i < html.length) {
            val ch = html[i]
            if (inStr) {
                if (esc) esc = false
                else if (ch == '\\') esc = true
                else if (ch == '"') inStr = false
            } else {
                if (ch == '"') inStr = true
                else if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return html.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun isBuzzFamily(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("vidsrc.buzz") || h.contains("videm.xyz")
    }

    private suspend fun resolveBuzzFamily(
        embedUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val pageHtml = app.get(embedUrl, referer = referer).text
            val qJson = extractBalancedJson(pageHtml, "var Q =") ?: run {
                resolveGeneric(embedUrl, serverName, referer, subtitleCallback, callback)
                return
            }
            val q = try {
                parseJson<BuzzQ>(qJson)
            } catch (_: Exception) {
                resolveGeneric(embedUrl, serverName, referer, subtitleCallback, callback)
                return
            }
            val t = q.t?.takeIf { it.isNotBlank() } ?: run {
                resolveGeneric(embedUrl, serverName, referer, subtitleCallback, callback)
                return
            }
            val origin = embedUrl.substringBefore("/embed/")
            val host = try {
                java.net.URL(embedUrl).host.lowercase()
            } catch (_: Exception) {
                ""
            }
            val apiBase = if (host.contains("videm.xyz")) "$origin/api.php" else "$origin/pl/api.php"
            val streamOrigin = apiBase.substringBefore("/api.php")
            var servers = q.ssr?.servers?.filter { !it.ref.isNullOrBlank() } ?: emptyList()
            if (servers.isEmpty()) {
                try {
                    val qs = "type=${q.type ?: "movie"}&id=${URLEncoder.encode(q.id ?: "", "UTF-8")}" +
                        "&s=${q.s ?: 0}&e=${q.e ?: 0}&t=${URLEncoder.encode(t, "UTF-8")}"
                    val srcText = app.get("$apiBase?a=sources&$qs", referer = embedUrl).text
                    servers = try {
                        parseJson<BuzzSources>(srcText).servers?.filter { !it.ref.isNullOrBlank() } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                } catch (_: Exception) { }
            }
            if (servers.isEmpty()) {
                resolveGeneric(embedUrl, serverName, referer, subtitleCallback, callback)
                return
            }
            val ordered = servers.sortedWith(
                compareByDescending<BuzzServer> {
                    val n = (it.name ?: "").lowercase()
                    n.contains("arab")
                }.thenBy {
                    val n = (it.name ?: "").lowercase()
                    if (n.contains("english")) 1 else 0
                }
            )
            for (sv in ordered) {
                val ref = sv.ref ?: continue
                val label = sv.name?.trim()?.takeIf { it.isNotBlank() } ?: "Auto"
                try {
                    val playUrl = "$apiBase?a=play&ref=${URLEncoder.encode(ref, "UTF-8")}&t=${URLEncoder.encode(t, "UTF-8")}"
                    val playText = app.get(playUrl, referer = embedUrl).text
                    val play = try {
                        parseJson<BuzzPlay>(playText)
                    } catch (_: Exception) {
                        null
                    }
                    val streamPath = play?.url?.trim()
                    if (streamPath.isNullOrBlank()) continue
                    val streamUrl = if (streamPath.startsWith("http")) streamPath else streamOrigin + streamPath
                    if (streamUrl.lowercase().contains("streamcash.to")) continue
                    val isMp4 = (play?.type ?: "hls").equals("mp4", ignoreCase = true)
                    callback.invoke(
                        newExtractorLink(source = name, name = "$serverName - $label", url = streamUrl) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
                            this.type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.HLS
                        }
                    )
                } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            try {
                resolveGeneric(embedUrl, serverName, referer, subtitleCallback, callback)
            } catch (_: Exception) { }
        }
    }

    /**
     * 2Embed pages carry inner players in data-src/src iframes AND
     * onclick go('...') handlers (streamsrcs swish, vidsrc.buzz, videm).
     * Buzz/VidEm inners use the dedicated play-mint resolver; anything
     * else goes through the generic extractor+harvester path.
     * IDs are passed through untouched (buzz accepts TMDB or IMDb).
     */
    private suspend fun resolve2Embed(
        pageUrl: String,
        serverName: String,
        imdb: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val pageHtml = app.get(pageUrl, referer = mainUrl).text
            val inners = mutableListOf<String>()
            Regex("""data-src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(pageHtml).forEach { m ->
                    val u = m.groupValues[1].trim()
                    if (u.startsWith("http") || u.startsWith("//")) inners.add(u)
                }
            Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(pageHtml).forEach { m ->
                    val u = m.groupValues[1].trim()
                    if (u.startsWith("http") || u.startsWith("//")) inners.add(u)
                }
            Regex("""go\(\s*['"]([^'"]+)['"]\s*\)""", RegexOption.IGNORE_CASE)
                .findAll(pageHtml).forEach { m ->
                    val u = m.groupValues[1].trim()
                    if (u.startsWith("http") || u.startsWith("//")) inners.add(u)
                }
            val cleaned = inners.map { it.replace("&amp;", "&").trim() }
                .map { if (it.startsWith("//")) "https:$it" else it }
                .filter { it.startsWith("http") && !it.contains("about:blank") }
                .filterNot {
                    try {
                        val h = java.net.URL(it).host.lowercase()
                        h == "www.2embed.cc" || h == "2embed.cc"
                    } catch (_: Exception) {
                        true
                    }
                }
                .distinct()
            if (cleaned.isEmpty()) return
            for (raw in cleaned) {
                val inner = raw
                try {
                    val host = try {
                        java.net.URL(inner).host.lowercase()
                    } catch (_: Exception) {
                        ""
                    }
                    if (isBuzzFamily(host)) {
                        resolveBuzzFamily(inner, serverName, pageUrl, subtitleCallback, callback)
                    } else {
                        resolveGeneric(inner, serverName, pageUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseWatchUrl(data) ?: return false
        val type = parsed.first
        val id = parsed.second
        val rest = parsed.third
        // Working lineup: VidSrcBuzz + VidEm (play-mint API, TMDB ids) +
        // VidSrc TO (vsembed chain) + 2Embed (inner embeds resolved manually).
        // Dropped: vaplayer (JS anti-bot, zero links server-side),
        // vidsrc.me / multiembed / autoembed (time out on all networks),
        // core 2Embed extractor (fabricates dead streamcash.to URL -> filtered).
        val tasks = mutableListOf<suspend () -> Unit>()
        if (type == "movie") {
            val imdb = imdbId(id)
            tasks.add({ resolveBuzzFamily("https://vidsrc.buzz/embed/movie/$id", "VidSrcBuzz", mainUrl, subtitleCallback, callback) })
            tasks.add({ resolveBuzzFamily("https://videm.xyz/embed/movie/$id", "VidEm", mainUrl, subtitleCallback, callback) })
            tasks.add({ resolveVsEmbed("https://vidsrc.to/embed/movie/${imdb ?: id}", "VidSrc TO", subtitleCallback, callback) })
            if (!imdb.isNullOrBlank()) {
                tasks.add({ resolve2Embed("https://www.2embed.cc/embed/$imdb", "2Embed", imdb, null, null, subtitleCallback, callback) })
            }
        } else {
            val season = rest.getOrNull(0)
            val episode = rest.getOrNull(1)
            if (season == null || episode == null) return false
            tasks.add({ resolveBuzzFamily("https://vidsrc.buzz/embed/tv/$id/$season/$episode", "VidSrcBuzz", mainUrl, subtitleCallback, callback) })
            tasks.add({ resolveBuzzFamily("https://videm.xyz/embed/tv/$id/$season/$episode", "VidEm", mainUrl, subtitleCallback, callback) })
            tasks.add({ resolveVsEmbed("https://vidsrc.to/embed/tv/$id/$season/$episode?lang=ar", "VidSrc TO", subtitleCallback, callback) })
            val seriesImdb = seriesImdbId(id)
            tasks.add({ resolve2Embed("https://www.2embed.cc/embedtv/$id&s=$season&e=$episode", "2Embed", seriesImdb, season, episode, subtitleCallback, callback) })
        }
        if (tasks.isEmpty()) return false
        coroutineScope {
            tasks.map { task ->
                async { task() }
            }.awaitAll()
        }
        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsSrc(
        @JsonProperty("src") val src: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BuzzQ(
        @JsonProperty("type") val type: String?,
        @JsonProperty("id") val id: String?,
        @JsonProperty("s") val s: Int?,
        @JsonProperty("e") val e: Int?,
        @JsonProperty("t") val t: String?,
        @JsonProperty("ssr") val ssr: BuzzSsr?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BuzzSsr(
        @JsonProperty("servers") val servers: List<BuzzServer>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BuzzServer(
        @JsonProperty("ref") val ref: String?,
        @JsonProperty("k") val k: String?,
        @JsonProperty("name") val name: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BuzzSources(
        @JsonProperty("servers") val servers: List<BuzzServer>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BuzzPlay(
        @JsonProperty("url") val url: String?,
        @JsonProperty("type") val type: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbPage(
        @JsonProperty("results") val results: List<TmdbItem>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbDetail(
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("genres") val genres: List<TmdbGenre>?,
        @JsonProperty("seasons") val seasons: List<TmdbSeasonInfo>?,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbGenre(
        @JsonProperty("name") val name: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeasonInfo(
        @JsonProperty("season_number") val seasonNumber: Int?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeason(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("still_path") val stillPath: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String?
    )
}
