package com.movy

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import java.net.URLEncoder

class MovyProvider : MainAPI() {
    override var mainUrl = "https://www.movy.sx"
    override var name = "Movy"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "Movy"
    private val streamOrigin = "https://api.wecollege.net"
    private val cities = listOf("miami", "boise", "seattle")

    /** Custom stream cipher ported from the movy web player (see StreamCrypto).
     *  NOTE: implemented with Long-masked uint32 arithmetic (no UInt/UIntArray)
     *  to stay binary-compatible with hosts running an older Kotlin stdlib
     *  (UIntArray.set-VXSXFK8 NoSuchMethodError on CloudStream Desktop). */
    private object StreamCrypto {
        private const val MASK = 0xFFFFFFFFL
        private val MAGIC = byteArrayOf(109, 118, 109, 49) // "mvm1"

        private fun o(e: Long): Long {
            var x = e and MASK
            x = x xor (x ushr 16)
            x = (x * 0x85EBCA6BL) and MASK
            x = x xor (x ushr 13)
            x = (x * 0xC2B2AE35L) and MASK
            x = x xor (x ushr 16)
            return x and MASK
        }

        private fun u(e: Long, a: Int): Long {
            val s = a and 31
            if (s == 0) return e and MASK
            return (((e shl s) or (e ushr (32 - s))) and MASK)
        }

        private fun fnv(s: String): Long {
            var a = 0x811C9DC5L
            for (c in s) a = ((a xor c.code.toLong()) * 0x1000193L) and MASK
            return o(a)
        }

        private class St(
            val s: LongArray,
            var acc: Long,
            val filled: MutableSet<Int>,
            var ctr: Int = 0,
        ) {
            fun word(): Long {
                val acc0 = acc and MASK
                val l = (acc0 % 61L).toInt()
                val exists = l in filled
                val r = if (exists) s[l] else 0L
                val c = (0x9E3779B9L * (ctr + 1).toLong()) and MASK
                ctr++
                val sv = (r xor c) and MASK
                val i = if (exists) 0xFFFFFFFFL else 0L
                val b = ((acc0 xor sv) or (acc0 and sv and i)) and MASK
                val b2 = (u((b + acc0) and MASK, (31 and l)) xor u(acc0, (31 and (l * 7)))) and MASK
                val w = o((b2 + 0x9E3779B9L) and MASK)
                s[l] = w
                filled.add(l)
                acc = w
                return w
            }
        }

        fun decrypt(blob: String, seed: String, mediaId: Int): String? {
            return try {
                var t = blob.trim().replace('-', '+').replace('_', '/')
                while (t.length % 4 != 0) t += "="
                val raw = Base64.decode(t, Base64.NO_WRAP)
                if (raw.size <= 4) return null
                val f = fnv(seed)
                var n = o(f xor o(((mediaId.toLong() and MASK) xor 0x9E3779B9L) and MASK))
                val s = LongArray(61)
                val filled = mutableSetOf<Int>()
                for (e in 0 until 8) {
                    val a = (n % 61L).toInt()
                    n = u(((n + 0x9E3779B9L) and MASK), (7 + (7 and e)))
                    s[a] = (n xor o(n)) and MASK
                    filled.add(a)
                    n = o((n + a.toLong()) and MASK)
                }
                val st = St(s, o((0xA5A5A5A5L xor n) and MASK), filled)
                val ks = ByteArray(raw.size)
                var e = 0
                while (e < raw.size) {
                    val w = st.word()
                    for (sh in listOf(0, 8, 16, 24)) {
                        if (e < raw.size) {
                            ks[e] = ((w ushr sh) and 0xFFL).toByte()
                            e++
                        }
                    }
                }
                val out = ByteArray(raw.size)
                for (i in raw.indices) out[i] = (raw[i].toInt() xor ks[i].toInt()).toByte()
                for (j in MAGIC.indices) if (out[j] != MAGIC[j]) return null
                String(out, 4, out.size - 4, Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
            } catch (_: Throwable) { null }
        }
    }
    private val seedCache = mutableMapOf<String, Pair<String, Long>>()

    private suspend fun fetchSeed(mediaId: Int): String? {
        val key = "$streamOrigin|$mediaId"
        synchronized(seedCache) {
            val (s, exp) = seedCache[key] ?: ("" to 0L)
            if (s.isNotBlank() && exp - 5000 > System.currentTimeMillis()) return s
        }
        return try {
            val txt = app.get(
                "$streamOrigin/seed?mediaId=$mediaId",
                referer = "$mainUrl/",
                headers = mapOf("Accept" to "application/json"),
                timeout = 15
            ).text
            val seed = Regex(""""seed"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.get(1)
            val ttl = Regex(""""ttlMs"\s*:\s*(\d+)""").find(txt)?.groupValues?.get(1)?.toLongOrNull() ?: 30000L
            if (seed.isNullOrBlank()) return null
            synchronized(seedCache) {
                seedCache[key] = seed to (System.currentTimeMillis() + ttl)
            }
            seed
        } catch (_: Exception) { null }
    }

    data class StreamSource(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class SourcesResp(
        @JsonProperty("sources") val sources: List<StreamSource>? = null,
        @JsonProperty("subtitles") val subtitles: List<Map<String, Any?>>? = null,
        @JsonProperty("playlist") val playlist: String? = null,
    )

    private suspend fun fetchCitySources(
        city: String,
        params: Map<String, String>,
        mediaId: Int
    ): SourcesResp? {
        var seed = fetchSeed(mediaId) ?: return null
        repeat(2) { attempt ->
            try {
                val q = params.entries.joinToString("&") { (k, v) ->
                    "$k=${URLEncoder.encode(v, "UTF-8")}"
                }
                val txt = app.get(
                    "$streamOrigin/$city/sources?$q&enc=2&seed=${URLEncoder.encode(seed, "UTF-8")}",
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Origin" to mainUrl
                    ),
                    timeout = 20
                ).text
                if (txt.isBlank()) return null
                val json = StreamCrypto.decrypt(txt, seed, mediaId)
                if (json == null) {
                    Log.d(TAG, "$city: streamcrypt decrypt failed (len=${txt.length})")
                    return null
                }
                return try {
                    AppUtils.parseJson<SourcesResp>(json)
                } catch (e: Throwable) {
                    Log.d(TAG, "$city: sources parse failed: ${e.message} json=${json.take(120)}")
                    null
                }
            } catch (e: Throwable) {
                Log.d(TAG, "$city attempt ${attempt + 1} failed: ${e.message}")
                synchronized(seedCache) { seedCache.remove("$streamOrigin|$mediaId") }
                seed = fetchSeed(mediaId) ?: return null
                if (attempt == 0) {
                    try { delay(800) } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    private fun qualityOf(label: String?): Int {
        val digits = label?.filter { it.isDigit() }?.toIntOrNull()
        if (digits != null && digits in 144..4320) return digits
        return Qualities.Unknown.value
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/browse/movie" to "Movies",
        "$mainUrl/browse/tv" to "TV Shows",
        "$mainUrl/browse/anime" to "Anime",
    )

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        doc.select(".media-card").forEach { card ->
            try {
                val a = card.selectFirst("a[href]") ?: return@forEach
                val href = a.attr("href").trim()
                val isTv = href.startsWith("/tv/") || href.startsWith("/anime/")
                if (!href.startsWith("/movie/") && !isTv) return@forEach
                val text = a.text().replace(Regex("""\s+"""), " ").trim()
                if (text.isBlank()) return@forEach
                val year = Regex("""(19\d{2}|20\d{2})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                var title = Regex("""^(.*?)\s+\d\.\d+\s*·""").find(text)?.groupValues?.get(1)?.trim()
                if (title.isNullOrBlank()) {
                    title = text.substringBefore("·").trim()
                    title = Regex("""^(.*?)\s+\d\.\d+\s*$""").find(title)?.groupValues?.get(1)?.trim() ?: title
                }
                if (title.isNullOrBlank()) return@forEach
                val poster = card.selectFirst("img[src]")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
                if (isTv) {
                    out.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster?.let { fixUrl(it) }
                        this.year = year
                    })
                } else {
                    out.add(newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster?.let { fixUrl(it) }
                        this.year = year
                    })
                }
            } catch (_: Exception) {}
        }
        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "$mainUrl/") {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            Log.d(TAG, "getMainPage Home -> $mainUrl/")
            return try {
                val doc = app.get(mainUrl, referer = mainUrl).document
                val cards = parseCards(doc)
                val rows = listOf(
                    "Trending Movies" to cards.filter { it.url.contains("/movie/") },
                    "Trending TV Shows" to cards.filter { it.url.contains("/tv/") },
                    "Trending Anime" to cards.filter { it.url.contains("/anime/") },
                ).mapNotNull { (title, items) ->
                    val distinct = items.distinctBy { it.url }
                    if (distinct.isEmpty()) null else HomePageList(title, distinct)
                }
                newHomePageResponse(rows, hasNext = false)
            } catch (e: Exception) {
                Log.d(TAG, "getMainPage Home failed: ${e.message}")
                newHomePageResponse(request.name, emptyList())
            }
        }
        var url = request.data
        if (page > 1) url += if (url.contains("?")) "&page=$page" else "?page=$page"
        Log.d(TAG, "getMainPage ${request.name} -> $url")
        return try {
            val doc = app.get(url, referer = mainUrl).document
            newHomePageResponse(request.name, parseCards(doc))
        } catch (e: Exception) {
            Log.d(TAG, "getMainPage ${request.name} failed: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        Log.d(TAG, "search -> $query")
        val out = mutableListOf<SearchResponse>()
        coroutineScope {
            listOf("movie", "tv", "anime").map { kind ->
                async {
                    try {
                        val doc = app.get("$mainUrl/browse/$kind?query=$enc", referer = mainUrl).document
                        synchronized(out) { out.addAll(parseCards(doc)) }
                    } catch (e: Exception) {
                        Log.d(TAG, "search $kind failed: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        return out.distinctBy { it.url }
    }

    data class SeasonEntry(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    private data class Details(
        val tmdbId: Int,
        val imdbId: String?,
        val title: String,
        val year: Int?,
        val desc: String?,
        val poster: String?,
        val backdrop: String?,
        val isTv: Boolean,
        val seasons: List<SeasonEntry>,
    )

    private suspend fun fetchDetails(url: String): Details? {
        return try {
            val html = app.get(url, referer = mainUrl).text
            val m = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return null
            val root = AppUtils.parseJson<Map<String, Any?>>(m)
            @Suppress("UNCHECKED_CAST")
            val pageProps = (root["props"] as? Map<String, Any?>)?.get("pageProps") as? Map<String, Any?>
                ?: return null
            @Suppress("UNCHECKED_CAST")
            val d = pageProps["details"] as? Map<String, Any?> ?: return null
            val tmdbId = (d["tmdbId"] as? Number)?.toInt() ?: return null
            val isTv = url.contains("/tv/") || (d["mediaType"] as? String)?.contains("tv", true) == true
            @Suppress("UNCHECKED_CAST")
            val seasons = (d["seasons"] as? List<Map<String, Any?>>)?.mapNotNull {
                val sn = (it["season_number"] as? Number)?.toInt() ?: return@mapNotNull null
                SeasonEntry(sn, (it["episode_count"] as? Number)?.toInt(), it["name"] as? String)
            } ?: emptyList()
            Details(
                tmdbId = tmdbId,
                imdbId = (d["imdbId"] as? String)?.takeIf { it.isNotBlank() },
                title = (d["title"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null,
                year = (d["year"] as? Number)?.toInt(),
                desc = (d["desc"] as? String)?.takeIf { it.isNotBlank() },
                poster = (d["poster"] as? String)?.takeIf { it.isNotBlank() },
                backdrop = (d["background"] as? String)?.takeIf { it.isNotBlank() },
                isTv = isTv,
                seasons = seasons,
            )
        } catch (_: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load $url")
        val d = fetchDetails(url) ?: throw ErrorLoadingException("No details for $url")
        if (!d.isTv) {
            return newMovieLoadResponse(d.title, url, TvType.Movie, "$mainUrl/watch/movie/${d.tmdbId}") {
                this.posterUrl = d.poster?.let { fixUrl(it) }
                this.backgroundPosterUrl = d.backdrop?.let { fixUrl(it) }
                this.plot = d.desc
                this.year = d.year
            }
        }
        val episodes = mutableListOf<Episode>()
        for (s in d.seasons.sortedBy { it.seasonNumber ?: 0 }) {
            val sn = s.seasonNumber ?: continue
            val count = s.episodeCount ?: continue
            for (en in 1..count) {
                episodes.add(newEpisode("$mainUrl/watch/tv/${d.tmdbId}/$sn/$en") {
                    this.name = "Episode $en"
                    this.episode = en
                    this.season = sn
                    this.posterUrl = d.poster?.let { fixUrl(it) }
                })
            }
        }
        Log.d(TAG, "show ${d.title} -> ${episodes.size} episodes")
        return newTvSeriesLoadResponse(d.title, url, TvType.TvSeries, episodes) {
            this.posterUrl = d.poster?.let { fixUrl(it) }
            this.backgroundPosterUrl = d.backdrop?.let { fixUrl(it) }
            this.plot = d.desc
            this.year = d.year
        }
    }

    private suspend fun loadCityLinks(
        city: String,
        tmdbId: Int,
        imdbId: String?,
        title: String,
        year: Int?,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        totalSeasons: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val params = mutableMapOf(
            "title" to title,
            "mediaType" to if (isTv) "tv" else "movie",
            "tmdbId" to tmdbId.toString(),
        )
        if (year != null) params["year"] = year.toString()
        if (!imdbId.isNullOrBlank()) params["imdbId"] = imdbId
        if (isTv) {
            if (totalSeasons != null) params["totalSeasons"] = totalSeasons.toString()
            if (season != null) params["seasonId"] = season.toString()
            if (episode != null) params["episodeId"] = episode.toString()
        }
        val resp = fetchCitySources(city, params, tmdbId) ?: run {
            Log.d(TAG, "$city: no sources")
            return
        }
        var emitted = 0
        val pl = resp.playlist?.trim()?.takeIf { it.startsWith("http") }
        if (pl != null && pl.contains(".m3u8")) {
            try {
                callback(newExtractorLink(source = this.name, name = "$name $city", url = pl) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
                })
                emitted++
            } catch (_: Exception) {}
        } else {
            for (s in (resp.sources ?: emptyList())) {
                val u = s.url?.trim()?.takeIf { it.startsWith("http") } ?: continue
                try {
                    callback(newExtractorLink(source = this.name, name = "$name $city ${s.quality ?: ""}".trim(), url = u) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityOf(s.quality)
                        this.type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    })
                    emitted++
                } catch (_: Exception) {}
            }
        }
        for (sub in (resp.subtitles ?: emptyList())) {
            try {
                val u = (sub["url"] as? String)?.trim()?.takeIf { it.startsWith("http") }
                    ?: (sub["file"] as? String)?.trim()?.takeIf { it.startsWith("http") }
                    ?: continue
                val lang = (sub["lang"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (sub["label"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (sub["name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: "sub"
                subtitleCallback(newSubtitleFile(lang, u))
            } catch (_: Exception) {}
        }
        Log.d(TAG, "$city: emitted=$emitted subs=${resp.subtitles?.size ?: 0}")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks $data")
        try {
            val ep = Regex("""/watch/tv/(\d+)/(\d+)/(\d+)""").find(data)
            val mid = Regex("""/watch/movie/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
            val pageUrl = when {
                ep != null -> "$mainUrl/tv/${ep.groupValues[1]}"
                mid != null -> "$mainUrl/movie/$mid"
                else -> {
                    val d0 = fetchDetails(data)
                    if (d0 == null) return true
                    if (d0.isTv) "$mainUrl/tv/${d0.tmdbId}" else "$mainUrl/movie/${d0.tmdbId}"
                }
            }
            val d = fetchDetails(pageUrl) ?: return true
            val season = ep?.groupValues?.get(2)?.toIntOrNull()
            val episode = ep?.groupValues?.get(3)?.toIntOrNull()
            supervisorScope {
                cities.map { city ->
                    async {
                        try {
                            loadCityLinks(
                                city, d.tmdbId, d.imdbId, d.title, d.year, d.isTv,
                                season, episode, d.seasons.size.takeIf { d.isTv },
                                subtitleCallback, callback
                            )
                        } catch (e: Throwable) {
                            Log.d(TAG, "$city failed: ${e.message}")
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}", e)
        }
        return true
    }
}
