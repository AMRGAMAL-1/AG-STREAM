package com.screenify

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * Screenify+ — same sources as the real app (com.screenifyplus.app v1.8.0).
 *
 * Reverse-engineered from the APK (flutter config + libapp.so strings),
 * live traffic captures, and verified Supabase responses:
 *
 * LIVE TV = Supabase project tqnzsusumewkudmihxta.supabase.co
 * (publishable key shipped in the app, same pattern as other providers):
 * - categories {id, name, logo, parent_id, position} — 52 packages ordered
 *   by position (YEMEN .. beIN, beIN (S1), NM Package, ...), verbatim names.
 * - channels {id, name, category_id, logo, position} — 1043 channels.
 * - streams {id, channel_id, label, url, referrer, user_agent, cookie,
 *   license, license_scheme, requires_auth, auth_token, ...} — 2399 rows.
 *   URLs are direct .m3u8/.mp4/.ts, .mpd with Clearkey (license=kid:key),
 *   URL-encoded links, or links with embedded h_User-Agent/h_Referer params.
 *
 * VOD (movies / series / anime / episodes) = myychann PHP API, endpoints
 * taken verbatim from the APK:
 * - /api/v2/home.php?app_version=15 , /api/anime/home.php
 * - /api/movies/?sort_by=trending&page=1&limit=20&app_version=15
 * - /api/series/?page=1&limit=20 , /api/series/?sort_by=trending&page=1&limit=20
 * - /api/movies/show.php?id= , /api/series/show.php?id= ,
 *   /api/anime/show.php?id= , /api/episodes/?season_id= ,
 *   /api/anime/episodes/?season_id= , /api/episodes/show.php?id= ,
 *   /api/movies/?search= , /api/series/?search=
 * - Stream resolving uses the hosts/config resolver table
 *   (hrrejhp.com/egybestantojdid/public/api/hosts/config) plus the scrape
 *   helpers found in the APK (scrapefinal/testflech.php?api= ,
 *   scripttestfasel.php?api=).
 *
 * No placeholder rows: every list comes from a real response.
 */
class Screenify : MainAPI() {
    companion object {
        private const val WEB = "https://screenifyplus.online"
        private const val MY = "https://api.myychann.site"
        private const val APP_VERSION = "15"

        private const val SB_URL = "https://tqnzsusumewkudmihxta.supabase.co"
        private const val SB_KEY = "sb_publishable_mtWkxctRuavqk2gl0PD4Nw_V2fJzX1P"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0 Mobile Safari/537.36"

        private const val LIVE_PREFIX = "screenify:live:"
        private const val VODM_PREFIX = "screenify:vodmovie:"
        private const val VODS_PREFIX = "screenify:vodseries:"
        private const val VODEP_PREFIX = "screenify:vodep:"

        private val CLEARKEY_UUID: UUID =
            UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
    }

    override var name = "Screenify"
    override var lang = "ar"
    override var mainUrl = WEB
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val jsonHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
    )
    private val sbHeaders = mapOf(
        "apikey" to SB_KEY,
        "Authorization" to "Bearer $SB_KEY",
        "Accept" to "application/json",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LiveLink(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VodLink(
        @JsonProperty("kind") val kind: String? = null, // movie | series | anime | episode
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("sid") val sid: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SbCategory(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("parent_id") val parentId: Int? = null,
        @JsonProperty("position") val position: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SbChannel(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("category_id") val categoryId: Int? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("position") val position: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SbStream(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("channel_id") val channelId: Int? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("referrer") val referrer: String? = null,
        @JsonProperty("requires_auth") val requiresAuth: Boolean? = null,
        @JsonProperty("auth_token") val authToken: String? = null,
        @JsonProperty("cookie") val cookie: String? = null,
        @JsonProperty("license") val license: String? = null,
        @JsonProperty("license_scheme") val licenseScheme: String? = null,
        @JsonProperty("user_agent") val userAgent: String? = null,
        @JsonProperty("position") val position: Int? = null,
    )

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private suspend fun getText(url: String, headers: Map<String, String>): String? {
        return try {
            val txt = app.get(url, headers = headers, timeout = 20).text.trim()
            if (txt.isEmpty()) null else txt
        } catch (_: Throwable) { null }
    }

    private suspend fun sbGet(path: String): String? {
        return getText("$SB_URL/rest/v1/$path", sbHeaders)
    }

    private suspend fun myGet(path: String): String? {
        return getText("$MY$path", jsonHeaders)
    }

    @Volatile private var catsCache: List<SbCategory>? = null
    @Volatile private var chansCache: List<SbChannel>? = null
    @Volatile private var cacheTime = 0L
    private val cacheTtl = 6 * 60 * 60 * 1000L

    private suspend fun fetchCategories(): List<SbCategory> {
        val txt = sbGet("categories?select=id,name,logo,parent_id,position&order=position&limit=200")
            ?: return emptyList()
        return try {
            parseJson<List<SbCategory>>(txt).sortedBy { it.position ?: 0 }
        } catch (_: Throwable) { emptyList() }
    }

    private suspend fun fetchAllChannels(): List<SbChannel> {
        val out = mutableListOf<SbChannel>()
        var offset = 0
        while (offset < 5000) {
            val txt = sbGet(
                "channels?select=id,name,category_id,logo,position" +
                    "&order=position&limit=1000&offset=$offset"
            ) ?: break
            val page = try { parseJson<List<SbChannel>>(txt) } catch (_: Throwable) { break }
            if (page.isEmpty()) break
            out.addAll(page)
            if (page.size < 1000) break
            offset += 1000
        }
        return out
    }

    private suspend fun ensureLiveCatalog(): Pair<List<SbCategory>, List<SbChannel>> {
        val now = System.currentTimeMillis()
        val c = catsCache
        val h = chansCache
        if (c != null && h != null && now - cacheTime < cacheTtl) return c to h
        val cats = fetchCategories()
        val chans = if (cats.isNotEmpty()) fetchAllChannels() else emptyList()
        if (cats.isNotEmpty() && chans.isNotEmpty()) {
            catsCache = cats
            chansCache = chans
            cacheTime = now
        }
        return cats to chans
    }

    private suspend fun fetchStreams(channelId: String): List<SbStream> {
        val id = channelId.toIntOrNull() ?: return emptyList()
        val txt = sbGet(
            "streams?select=id,channel_id,label,url,referrer,requires_auth,auth_token," +
                "cookie,license,license_scheme,user_agent,position" +
                "&channel_id=eq.$id&order=position&limit=50"
        ) ?: return emptyList()
        return try { parseJson<List<SbStream>>(txt) } catch (_: Throwable) { emptyList() }
    }

    private fun SbChannel.toLive(): SearchResponse {
        val data = LiveLink(this.id?.toString(), this.name ?: "قناة", this.logo).toJson()
        return newLiveSearchResponse(this.name ?: "قناة", "$LIVE_PREFIX$data", TvType.Live) {
            this.posterUrl = this@toLive.logo?.takeIf { it.isNotBlank() }
        }
    }

    private fun pickStr(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun myItem(o: JSONObject): Triple<String, String, String?>? {
        val id = pickStr(o, "id", "movie_id", "series_id", "anime_id", "episode_id")
            ?: return null
        val title = pickStr(
            o, "title", "name", "movie_title", "series_title", "anime_title",
            "episode_title", "title_en", "anime_title_en"
        ) ?: return null
        val poster = pickStr(
            o, "poster", "image", "movie_poster", "series_poster", "anime_poster",
            "thumbnail", "cover", "poster_path"
        )
        return Triple(id, title, poster)
    }

    private fun mySections(text: String): List<Pair<String, List<Triple<String, String, String?>>>> {
        val out = mutableListOf<Pair<String, List<Triple<String, String, String?>>>>()
        try {
            val t = text.trim()
            if (t.startsWith("[")) {
                val items = mutableListOf<Triple<String, String, String?>>()
                val arr = JSONArray(t)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { myItem(it)?.let { it2 -> items.add(it2) } }
                }
                if (items.isNotEmpty()) out.add("" to items)
                return out
            }
            val root = JSONObject(t)
            val metaKeys = setOf(
                "status", "message", "code", "success", "total", "page",
                "total_pages", "current_page", "per_page", "app_version"
            )
            var anySection = false
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in metaKeys) continue
                val arr = root.optJSONArray(k) ?: continue
                val items = mutableListOf<Triple<String, String, String?>>()
                for (i in 0 until arr.length()) {
                    val raw = arr.optJSONObject(i) ?: continue
                    val sub = raw.optJSONArray("items")
                        ?: raw.optJSONArray("data")
                        ?: raw.optJSONArray("list")
                    if (sub != null) {
                        val st = raw.optString("title").ifBlank { raw.optString("name") }
                        val subItems = mutableListOf<Triple<String, String, String?>>()
                        for (j in 0 until sub.length()) {
                            sub.optJSONObject(j)?.let { myItem(it)?.let { it2 -> subItems.add(it2) } }
                        }
                        if (subItems.isNotEmpty()) {
                            out.add(st.ifBlank { k } to subItems)
                            anySection = true
                        }
                        continue
                    }
                    myItem(raw)?.let { items.add(it) }
                }
                if (items.isNotEmpty()) {
                    out.add(k to items)
                    anySection = true
                }
            }
            if (!anySection) {
                for (k in listOf("data", "results", "movies", "series", "episodes", "animes", "list", "items")) {
                    val arr = root.optJSONArray(k) ?: continue
                    val items = mutableListOf<Triple<String, String, String?>>()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { myItem(it)?.let { it2 -> items.add(it2) } }
                    }
                    if (items.isNotEmpty()) out.add("" to items)
                }
            }
        } catch (_: Throwable) { }
        return out
    }

    private fun mySearchItems(text: String): List<Triple<String, String, String?>> {
        val out = mutableListOf<Triple<String, String, String?>>()
        try {
            val t = text.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else {
                val root = JSONObject(t)
                root.optJSONArray("data")
                    ?: root.optJSONArray("results")
                    ?: root.optJSONArray("movies")
                    ?: root.optJSONArray("series")
                    ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { myItem(it)?.let { it2 -> out.add(it2) } }
            }
        } catch (_: Throwable) { }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val rows = mutableListOf<HomePageList>()
        try {
            val (cats, chans) = ensureLiveCatalog()
            if (cats.isNotEmpty() && chans.isNotEmpty()) {
                val byCat = chans.groupBy { it.categoryId }
                for (cat in cats) {
                    val list = byCat[cat.id]?.sortedBy { it.position ?: 0 }?.map { it.toLive() }
                        ?: continue
                    if (list.isEmpty()) continue
                    rows.add(HomePageList(cat.name ?: "بث مباشر", list))
                }
            }
        } catch (_: Throwable) { }
        try {
            val txt = myGet("/api/v2/home.php?app_version=$APP_VERSION")
            if (txt != null) {
                for ((title, items) in mySections(txt)) {
                    if (items.isEmpty()) continue
                    val seriesLike = title.contains("series", true) ||
                        title.contains("episode", true) ||
                        title.contains("anime", true) ||
                        title.contains("مسلسل") ||
                        title.contains("حلقة")
                    val cards = items.map { (id, nm, poster) ->
                        if (seriesLike) {
                            val data = VodLink("series", id, null, nm, poster).toJson()
                            newTvSeriesSearchResponse(nm, "$VODS_PREFIX$data", TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        } else {
                            val data = VodLink("movie", id, null, nm, poster).toJson()
                            newMovieSearchResponse(nm, "$VODM_PREFIX$data", TvType.Movie) {
                                this.posterUrl = poster
                            }
                        }
                    }
                    rows.add(HomePageList(title.ifBlank { "الرئيسية" }, cards))
                }
            }
        } catch (_: Throwable) { }
        coroutineScope {
            listOf(
                async {
                    val txt = try {
                        myGet("/api/movies/?sort_by=trending&page=1&limit=20&app_version=$APP_VERSION")
                    } catch (_: Throwable) { null } ?: return@async null
                    val items = mySearchItems(txt)
                    if (items.isEmpty()) return@async null
                    HomePageList(
                        "أفلام رائجة",
                        items.map { (id, nm, poster) ->
                            val data = VodLink("movie", id, null, nm, poster).toJson()
                            newMovieSearchResponse(nm, "$VODM_PREFIX$data", TvType.Movie) {
                                this.posterUrl = poster
                            }
                        }
                    )
                },
                async {
                    val txt = try {
                        myGet("/api/series/?sort_by=trending&page=1&limit=20")
                    } catch (_: Throwable) { null } ?: return@async null
                    val items = mySearchItems(txt)
                    if (items.isEmpty()) return@async null
                    HomePageList(
                        "مسلسلات رائجة",
                        items.map { (id, nm, poster) ->
                            val data = VodLink("series", id, null, nm, poster).toJson()
                            newTvSeriesSearchResponse(nm, "$VODS_PREFIX$data", TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        }
                    )
                }
            ).awaitAll().filterNotNull().let { rows.addAll(it) }
        }

        return newHomePageResponse(rows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        try {
            val (_, chans) = ensureLiveCatalog()
            out.addAll(
                chans.filter { it.name?.contains(query, ignoreCase = true) == true }.map { it.toLive() }
            )
        } catch (_: Throwable) { }
        for (path in listOf(
            "/api/movies/?search=${enc(query)}",
            "/api/series/?search=${enc(query)}"
        )) {
            try {
                val txt = myGet(path) ?: continue
                val isSeries = path.contains("/series/")
                for ((id, nm, poster) in mySearchItems(txt)) {
                    if (isSeries) {
                        val data = VodLink("series", id, null, nm, poster).toJson()
                        out.add(
                            newTvSeriesSearchResponse(nm, "$VODS_PREFIX$data", TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        )
                    } else {
                        val data = VodLink("movie", id, null, nm, poster).toJson()
                        out.add(
                            newMovieSearchResponse(nm, "$VODM_PREFIX$data", TvType.Movie) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            } catch (_: Throwable) { }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains(LIVE_PREFIX)) {
            val payload = url.substringAfter(LIVE_PREFIX)
            val data = try { parseJson<LiveLink>(payload) } catch (_: Throwable) { null }
            val title = data?.name?.ifBlank { null } ?: "بث مباشر"
            return newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = data?.logo
                this.plot = "بث مباشر — $title"
            }
        }
        if (url.contains(VODM_PREFIX)) {
            val payload = url.substringAfter(VODM_PREFIX)
            val data = try { parseJson<VodLink>(payload) } catch (_: Throwable) { null }
            val id = data?.id ?: throw ErrorLoadingException("no id")
            val kind = data.kind ?: "movie"
            val detailPath = when {
                kind == "anime" -> "/api/anime/show.php?id=${enc(id)}"
                else -> "/api/movies/show.php?id=${enc(id)}"
            }
            val txt = myGet(detailPath) ?: throw ErrorLoadingException("empty detail")
            val root = try { JSONObject(txt.trim()) } catch (_: Throwable) { null }
                ?: throw ErrorLoadingException("bad detail")
            val o = root.optJSONObject("data")
                ?: root.optJSONObject("movie")
                ?: root.optJSONObject("anime")
                ?: root
            val title = pickStr(o, "title", "name", "movie_title", "anime_title")
                ?: (data.name ?: "فيلم")
            val poster = pickStr(o, "poster", "image", "movie_poster", "anime_poster", "thumbnail", "cover")
                ?: data.logo
            val plot = pickStr(o, "description", "overview", "story", "plot")
            val year = pickStr(o, "year", "release_date", "release_year")?.take(4)?.toIntOrNull()
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
        if (url.contains(VODS_PREFIX)) {
            val payload = url.substringAfter(VODS_PREFIX)
            val data = try { parseJson<VodLink>(payload) } catch (_: Throwable) { null }
            val id = data?.id ?: throw ErrorLoadingException("no id")
            val kind = data.kind ?: "series"
            val title0 = data.name?.ifBlank { null } ?: "مسلسل"
            var title = title0
            var poster = data.logo
            var plot: String? = null
            var year: Int? = null
            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
            val detailPath = when (kind) {
                "anime" -> "/api/anime/show.php?id=${enc(id)}"
                else -> "/api/series/show.php?id=${enc(id)}"
            }
            val txt = myGet(detailPath) ?: throw ErrorLoadingException("empty detail")
            try {
                val root = JSONObject(txt.trim())
                val o = root.optJSONObject("data")
                    ?: root.optJSONObject("series")
                    ?: root.optJSONObject("anime")
                    ?: root
                title = pickStr(o, "title", "name", "series_title", "anime_title") ?: title
                poster = pickStr(o, "poster", "image", "series_poster", "anime_poster", "thumbnail", "cover") ?: poster
                plot = pickStr(o, "description", "overview", "story", "plot")
                year = pickStr(o, "year", "release_date", "first_air_date")?.take(4)?.toIntOrNull()
                val seasons = o.optJSONArray("seasons")
                if (seasons != null) {
                    for (s in 0 until seasons.length()) {
                        val so = seasons.optJSONObject(s) ?: continue
                        val sn = so.optInt("season_number", so.optInt("season", s + 1))
                        val sid = so.optString("season_id").ifBlank { so.optString("id") }
                        val eps = so.optJSONArray("episodes")
                        if (eps != null) {
                            addMyEpisodes(eps, sn, poster, episodes)
                        } else if (!sid.isNullOrBlank()) {
                            val epath = if (kind == "anime") "/api/anime/episodes/?season_id=${enc(sid)}"
                            else "/api/episodes/?season_id=${enc(sid)}"
                            val stxt = myGet(epath)
                            if (stxt != null) {
                                val sarr = myEpisodesArray(stxt)
                                if (sarr != null) addMyEpisodes(sarr, sn, poster, episodes)
                            }
                        }
                    }
                } else {
                    val eps = o.optJSONArray("episodes") ?: o.optJSONArray("data")
                    if (eps != null) addMyEpisodes(eps, 1, poster, episodes)
                }
            } catch (_: Throwable) { }
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
        throw ErrorLoadingException("unknown url")
    }

    private fun myEpisodesArray(text: String): JSONArray? {
        return try {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t)
            else {
                val root = JSONObject(t)
                root.optJSONArray("episodes")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("results")
            }
        } catch (_: Throwable) { null }
    }

    private fun addMyEpisodes(
        eps: JSONArray,
        season: Int,
        poster: String?,
        out: MutableList<com.lagradost.cloudstream3.Episode>
    ) {
        for (e in 0 until eps.length()) {
            val eo = eps.optJSONObject(e) ?: continue
            val eid = pickStr(eo, "episode_id", "id") ?: continue
            val enum = eo.optInt("episode_num", eo.optInt("episode_number", e + 1))
            val sid = eo.optString("season_id").ifBlank { null }
            val edata = VodLink("episode", eid, sid, pickStr(eo, "title", "name") ?: "الحلقة $enum", null).toJson()
            out.add(
                newEpisode("$VODEP_PREFIX$edata") {
                    this.name = pickStr(eo, "title", "name") ?: "الحلقة $enum"
                    this.season = season
                    this.episode = enum
                    this.posterUrl = poster
                }
            )
        }
    }

    private fun isDirect(url: String): Boolean =
        Regex("\\.(m3u8|mp4|webm|mkv|ts|mpd)(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private suspend fun emitDirect(
        name: String,
        streamUrl: String,
        headers: Map<String, String>,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val type = when {
                streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(name, name, streamUrl, type) {
                    this.headers = headers
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Throwable) { false }
    }

    private fun scrapeMedia(text: String): List<String> {
        val found = mutableListOf<String>()
        for (re in listOf(
            Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|webm|mkv)(?:\?[^"']*)?)["']"""),
            Regex("""(?:file|source|src|url|link|stream)\s*[:=]\s*["']([^"']+)["']"""),
        )) {
            for (m in re.findAll(text)) {
                val u = m.groupValues[1].replace("\\/", "/").trim()
                if (u.startsWith("http") && u !in found) found.add(u)
            }
        }
        return found
    }

    /** Split embedded h_User-Agent / h_Referer params (dlhd-style links). */
    private fun splitEmbeddedParams(raw: String): Triple<String, Map<String, String>, String> {
        var url = raw.trim()
        if (!url.contains("://") && url.contains("%3A")) {
            try { url = URLDecoder.decode(url, "UTF-8") } catch (_: Throwable) { }
        }
        val headers = mutableMapOf<String, String>()
        var referer = WEB
        val parts = url.split("&h_")
        if (parts.size > 1) {
            url = parts[0]
            for (p in parts.drop(1)) {
                val k = p.substringBefore("=").trim()
                val v = try {
                    URLDecoder.decode(p.substringAfter("=", ""), "UTF-8")
                } catch (_: Throwable) { p.substringAfter("=", "") }
                when (k) {
                    "User-Agent" -> headers["User-Agent"] = v
                    "Referer" -> { headers["Referer"] = v; referer = v }
                    "Cookie" -> headers["Cookie"] = v
                    "Origin" -> headers["Origin"] = v
                    else -> headers[k] = v
                }
            }
        }
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = UA
        }
        return Triple(url, headers, referer)
    }

    private fun toB64UrlPadded(s: String): String {
        var t = s.trim().replace("+", "-").replace("/", "_").trimEnd('=')
        while (t.length % 4 != 0) t += "="
        return t
    }

    /** Emit one Supabase live stream row (direct / clearkey / extractor). */
    private suspend fun emitSbStream(
        row: SbStream,
        fallbackName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var raw = (row.url ?: "").trim()
        if (raw.isEmpty() || raw.equals("null", true)) return false
        val (url, embedded, ref0) = splitEmbeddedParams(raw)
        val headers = embedded.toMutableMap()
        row.userAgent?.takeIf { it.isNotBlank() && !it.equals("null", true) }?.let {
            headers["User-Agent"] = it
        }
        row.referrer?.takeIf { it.isNotBlank() && !it.equals("null", true) }?.let {
            headers["Referer"] = it
        }
        row.cookie?.takeIf { it.isNotBlank() && !it.equals("null", true) }?.let {
            headers["Cookie"] = it
        }
        var referer = ref0
        headers["Referer"]?.let { referer = it }
        val label = row.label?.takeIf { it.isNotBlank() } ?: fallbackName

        val scheme = row.licenseScheme?.lowercase() ?: ""
        val lic = row.license?.trim().orEmpty()
        if ((scheme.contains("clear") || scheme.contains("clearkey")) && ":" in lic && url.contains(".mpd", true)) {
            val kid = lic.substringBefore(":").trim()
            val key = lic.substringAfter(":").trim()
            if (kid.length >= 8 && key.length >= 8) {
                return try {
                    callback.invoke(
                        newDrmExtractorLink(
                            source = name,
                            name = "$fallbackName - $label",
                            url = url,
                            type = ExtractorLinkType.DASH,
                            uuid = CLEARKEY_UUID
                        ) {
                            this.referer = referer
                            this.headers = headers
                            this.quality = Qualities.Unknown.value
                            this.kid = toB64UrlPadded(kid)
                            this.key = toB64UrlPadded(key)
                            this.kty = "oct"
                        }
                    )
                    true
                } catch (_: Throwable) { false }
            }
        }
        if (isDirect(url)) {
            return emitDirect("$fallbackName - $label", url, headers, referer, callback)
        }
        return try {
            loadExtractor(url, referer, subtitleCallback, callback)
        } catch (_: Throwable) { false }
    }

    private suspend fun emitMyServer(
        entry: JSONObject,
        fallbackName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        hostsRules: List<JSONObject>?
    ): Boolean {
        val raw0 = pickStr(entry, "url", "link", "file", "source", "stream_url", "video_url", "embed", "iframe")?.trim()
            ?: return false
        val label = pickStr(entry, "server", "name", "quality", "label") ?: fallbackName
        val (raw, embedded, ref0) = splitEmbeddedParams(raw0)
        if (isDirect(raw)) {
            val headers = embedded.toMutableMap()
            if (!headers.containsKey("User-Agent")) headers["User-Agent"] = UA
            return emitDirect("$name - $label", raw, headers, ref0, callback)
        }
        if (hostsRules != null) {
            for (rule in hostsRules) {
                val pattern = rule.optString("regex_pattern").replace("\\/", "/")
                if (pattern.isBlank()) continue
                val matches = try {
                    Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(raw)
                } catch (_: Throwable) { continue }
                if (!matches) continue
                val urlsite = rule.optString("urlsite").replace("\\/", "/")
                val postUrl = rule.optString("enableded").split("|").getOrNull(1)
                    ?.takeIf { it.startsWith("http") }
                if (postUrl != null) {
                    try {
                        val html = app.get(raw, headers = mapOf("User-Agent" to UA), timeout = 15).text
                        val res = app.post(
                            postUrl,
                            data = mapOf("content" to html, "url" to raw),
                            headers = mapOf(
                                "Content-Type" to "application/x-www-form-urlencoded",
                                "User-Agent" to UA
                            ),
                            timeout = 20
                        ).text
                        for (u in scrapeMedia(res)) {
                            if (emitDirect("$name - $label", u, mapOf("User-Agent" to UA), WEB, callback)) return true
                        }
                    } catch (_: Throwable) { }
                }
                if (urlsite.isNotBlank() && (urlsite.endsWith("=") || urlsite.contains("?api="))) {
                    try {
                        val res = app.get(urlsite + raw, headers = mapOf("User-Agent" to UA), timeout = 20).text
                        for (u in scrapeMedia(res)) {
                            if (emitDirect("$name - $label", u, mapOf("User-Agent" to UA), WEB, callback)) return true
                        }
                    } catch (_: Throwable) { }
                }
                break
            }
        }
        return try {
            loadExtractor(raw, WEB, subtitleCallback, callback)
        } catch (_: Throwable) { false }
    }

    private suspend fun hostsRules(): List<JSONObject>? {
        return try {
            val txt = getText(
                "https://www.hrrejhp.com/egybestantojdid/public/api/hosts/config",
                jsonHeaders
            ) ?: return null
            val arr = JSONArray(txt.trim())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Throwable) { null }
    }

    private fun myServersOf(text: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val t = text.trim()
            if (t.startsWith("[")) {
                val arr = JSONArray(t)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
                return out
            }
            val root = JSONObject(t)
            for (k in listOf("servers", "streams", "videos", "links", "sources", "data")) {
                val v = root.opt(k) ?: continue
                if (v is JSONArray) {
                    for (i in 0 until v.length()) {
                        val o = v.optJSONObject(i) ?: continue
                        if (o.optString("url").isNotBlank() || o.optString("link").isNotBlank() ||
                            o.optString("file").isNotBlank() || o.optString("source").isNotBlank()
                        ) out.add(o)
                    }
                    if (out.isNotEmpty()) return out
                } else if (v is JSONObject) {
                    if (v.optString("url").isNotBlank() || v.optString("link").isNotBlank()) {
                        out.add(v)
                        return out
                    }
                }
            }
            if (pickStr(root, "url", "link", "file", "source") != null) out.add(root)
        } catch (_: Throwable) { }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(LIVE_PREFIX)) {
            val payload = data.substringAfter(LIVE_PREFIX)
            val link = try { parseJson<LiveLink>(payload) } catch (_: Throwable) { null }
                ?: return false
            val cid = link.id ?: return false
            val rows = try { fetchStreams(cid) } catch (_: Throwable) { emptyList() }
            if (rows.isEmpty()) return false
            var ok = false
            for (row in rows.sortedBy { it.position ?: 0 }) {
                try {
                    if (emitSbStream(row, link.name ?: "بث مباشر", subtitleCallback, callback)) ok = true
                } catch (_: Throwable) { }
            }
            return ok
        }
        if (data.contains(VODM_PREFIX)) {
            val payload = data.substringAfter(VODM_PREFIX)
            val link = try { parseJson<VodLink>(payload) } catch (_: Throwable) { null }
                ?: return false
            val id = link.id ?: return false
            val txt = myGet("/api/movies/show.php?id=${enc(id)}") ?: return false
            val servers = myServersOf(txt)
            if (servers.isEmpty()) {
                try {
                    val root = JSONObject(txt.trim())
                    val o = root.optJSONObject("data") ?: root
                    val direct = pickStr(o, "url", "link", "file", "source", "video_url")
                    if (direct != null) {
                        val (u, embedded, ref0) = splitEmbeddedParams(direct)
                        if (isDirect(u)) {
                            val headers = embedded.toMutableMap()
                            if (!headers.containsKey("User-Agent")) headers["User-Agent"] = UA
                            return emitDirect(link.name ?: "فيلم", u, headers, ref0, callback)
                        }
                    }
                } catch (_: Throwable) { }
                return false
            }
            val rules = try { hostsRules() } catch (_: Throwable) { null }
            var ok = false
            coroutineScope {
                servers.map { entry ->
                    async {
                        try {
                            if (emitMyServer(entry, link.name ?: "سيرفر", subtitleCallback, callback, rules)) {
                                synchronized(this@Screenify) { ok = true }
                            }
                        } catch (_: Throwable) { }
                    }
                }.awaitAll()
            }
            return ok
        }
        if (data.contains(VODEP_PREFIX)) {
            val payload = data.substringAfter(VODEP_PREFIX)
            val link = try { parseJson<VodLink>(payload) } catch (_: Throwable) { null }
                ?: return false
            val eid = link.id ?: return false
            val txt = myGet("/api/episodes/show.php?id=${enc(eid)}") ?: return false
            val servers = myServersOf(txt)
            if (servers.isEmpty()) return false
            val rules = try { hostsRules() } catch (_: Throwable) { null }
            var ok = false
            coroutineScope {
                servers.map { entry ->
                    async {
                        try {
                            if (emitMyServer(entry, link.name ?: "سيرفر", subtitleCallback, callback, rules)) {
                                synchronized(this@Screenify) { ok = true }
                            }
                        } catch (_: Throwable) { }
                    }
                }.awaitAll()
            }
            return ok
        }
        if (data.contains(VODS_PREFIX)) {
            return try {
                val resp = load(data)
                val first = (resp as? com.lagradost.cloudstream3.TvSeriesLoadResponse)
                    ?.episodes?.firstOrNull()?.data
                if (first != null) loadLinks(first, isCasting, subtitleCallback, callback) else false
            } catch (_: Throwable) { false }
        }
        return false
    }
}
