package com.starzplaylive

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StarzPlayLiveProvider : MainAPI() {
    override var mainUrl = "https://starzplay.com"
    override var name = "StarzPlay Live"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val TAG = "StarzPlayLive"

    private val epgBase = "https://sp-new-api.aws.playco.com/api/v1.1"
    private val authBase = "https://app-api.starzplay.com/api/auth"
    private val spxV3Base = "https://api.aws.playco.com/api/v1.1"
    private val HASH_KEY = "BUrO7X!HQsXU39XH"
    private val WIDEVINE_LICENSE = "https://widevine-license.vudrm.tech/proxy"
    private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    // ---------- API models ----------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ConfigResp(
        @JsonProperty("data") val data: List<CatConfig>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatConfig(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("count") val count: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EventsResp(
        @JsonProperty("data") val data: List<EpgChannel>? = null,
        @JsonProperty("total") val total: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpgChannel(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("paid") val paid: Boolean? = null,
        @JsonProperty("images") val images: List<EpgImage>? = null,
        @JsonProperty("events") val events: List<EpgEvent>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpgImage(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpgEvent(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("guid") val guid: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("tsStart") val tsStart: Long? = null,
        @JsonProperty("tsEnd") val tsEnd: Long? = null,
        @JsonProperty("streamingUrl") val streamingUrl: List<StreamUrl>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamUrl(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("pid") val pid: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("assetTypes") val assetTypes: List<String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AiringResp(
        @JsonProperty("data") val data: List<AiringChannel>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AiringChannel(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("images") val images: List<EpgImage>? = null,
        @JsonProperty("events") val events: List<EpgEvent>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GuestResp(
        @JsonProperty("spx") val spx: String? = null,
        @JsonProperty("user") val user: GuestUser? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GuestUser(
        @JsonProperty("globalUserId") val globalUserId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DrmTokenResp(
        @JsonProperty("token") val token: String? = null
    )

    private data class ChInfo(
        val slug: String,
        val title: String,
        val category: String,
        val paid: Boolean,
        val logo: String?,
        val description: String?
    )

    // ---------- category labels (ar) ----------
    private val categoryLabels = mapOf(
        "all" to "كل القنوات",
        "featured" to "متميز",
        "news" to "أخبار",
        "sports" to "رياضات",
        "live-tv" to "ADMN TV",
        "entertainment" to "ترفيه",
        "kids" to "أطفال",
        "southasian" to "جنوب آسيا",
        "movies" to "أفلام",
        "lifestyle" to "نمط الحياة",
        "religious" to "ديني",
        "radio" to "راديو"
    )

    private val allCategorySlugs =
        listOf("news", "sports", "live-tv", "entertainment", "kids", "southasian", "movies", "lifestyle", "religious", "radio")

    override val mainPage = mainPageOf(
        "all" to "كل القنوات",
        "featured" to "متميز",
        "news" to "أخبار",
        "sports" to "رياضات",
        "live-tv" to "ADMN TV",
        "entertainment" to "ترفيه",
        "kids" to "أطفال",
        "southasian" to "جنوب آسيا",
        "movies" to "أفلام",
        "lifestyle" to "نمط الحياة",
        "religious" to "ديني",
        "radio" to "راديو",
    )

    // ---------- cache ----------
    private var cachedChannels: List<ChInfo> = emptyList()
    private var cacheTime = 0L
    private val CACHE_TTL = 6 * 60 * 60 * 1000L

    private var guestSpx: String? = null
    private var guestGuid: String? = null
    private var guestTime = 0L

    private fun slugUrl(slug: String) = "$mainUrl/ar/live/$slug"

    private fun logoOf(images: List<EpgImage>?): String? {
        if (images.isNullOrEmpty()) return null
        // prefer active png/svg logo
        val active = images.firstOrNull { it.url?.contains("logos/live") == true }
        if (active?.url != null) return active.url
        return images.firstOrNull()?.url
    }

    private suspend fun fetchCategoryChannels(category: String, lang: String = "ar"): List<ChInfo> {
        return try {
            val now = System.currentTimeMillis() / 1000
            val end = now + 86400
            val url = "$epgBase/epg/category/events/live?category=$category&ts_start=$now&ts_end=$end&lang=$lang"
            val txt = app.get(url, headers = mapOf("User-Agent" to ua, "Accept" to "application/json"), timeout = 20).text
            val resp = parseJson<EventsResp>(txt)
            resp.data?.mapNotNull { c ->
                val slug = c.slug ?: return@mapNotNull null
                val title = c.title ?: slug
                ChInfo(
                    slug = slug,
                    title = title,
                    category = c.category ?: category,
                    paid = c.paid ?: false,
                    logo = logoOf(c.images),
                    description = c.description
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "fetchCategory $category failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun ensureAllChannels(): List<ChInfo> {
        val nowMs = System.currentTimeMillis()
        if (cachedChannels.isNotEmpty() && nowMs - cacheTime < CACHE_TTL) return cachedChannels
        val map = LinkedHashMap<String, ChInfo>()
        for (cat in allCategorySlugs) {
            val list = fetchCategoryChannels(cat, "ar")
            for (c in list) {
                if (!map.containsKey(c.slug)) map[c.slug] = c
            }
        }
        // fallback: featured
        if (map.isEmpty()) {
            for (c in fetchCategoryChannels("featured", "ar")) {
                if (!map.containsKey(c.slug)) map[c.slug] = c
            }
        }
        cachedChannels = map.values.toList()
        cacheTime = nowMs
        Log.d(TAG, "cached ${cachedChannels.size} channels")
        return cachedChannels
    }

    private fun ChInfo.toLive(): SearchResponse {
        return newLiveSearchResponse(name = this.title, url = slugUrl(this.slug)) {
            this.posterUrl = this@toLive.logo
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = ensureAllChannels()
        val list = if (request.data == "all") {
            all
        } else {
            // category slug requested; channels already carry their own category,
            // but featured overlaps so re-fetch that category directly for accuracy
            if (request.data == "featured") {
                val feat = fetchCategoryChannels("featured", "ar")
                if (feat.isNotEmpty()) feat else all.filter { it.category == request.data }
            } else {
                val filtered = all.filter { it.category == request.data }
                if (filtered.isNotEmpty()) filtered else fetchCategoryChannels(request.data, "ar")
            }
        }
        val label = categoryLabels[request.data] ?: request.name
        Log.d(TAG, "getMainPage $label -> ${list.size}")
        return newHomePageResponse(label, list.map { it.toLive() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val all = ensureAllChannels()
        return all.filter { it.title.contains(query, ignoreCase = true) }.map { it.toLive() }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/").substringBefore("?").ifBlank { url }
        val all = try { ensureAllChannels() } catch (_: Exception) { emptyList() }
        val ch = all.find { it.slug == slug }
        val title = ch?.title ?: slug
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = slug
        ) {
            this.posterUrl = ch?.logo
            this.plot = ch?.description
        }
    }

    // ---------- guest + drm ----------
    private suspend fun ensureGuest(): Pair<String, String>? {
        val now = System.currentTimeMillis()
        if (guestSpx != null && guestGuid != null && now - guestTime < 20 * 60 * 60 * 1000L) {
            return guestSpx!! to guestGuid!!
        }
        return try {
            val uniq = UUID.randomUUID().toString()
            val body = mapOf("uniqueReference" to uniq, "country" to "EG", "lang" to "ar")
            val res = app.post(
                "$authBase/v2/guest/signup",
                json = body,
                headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                timeout = 20
            ).text
            val g = parseJson<GuestResp>(res)
            val spx = g.spx
            val guid = g.user?.globalUserId
            if (!spx.isNullOrBlank() && !guid.isNullOrBlank()) {
                guestSpx = spx
                guestGuid = guid
                guestTime = now
                Log.d(TAG, "guest ok guid=$guid")
                spx to guid
            } else {
                Log.d(TAG, "guest failed: $res".take(300))
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "guest error: ${e.message}")
            null
        }
    }

    private fun hmacSha512Hex(data: String, key: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA512"))
            val out = mac.doFinal(data.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(out.size * 2)
            for (b in out) sb.append(String.format("%02x", b))
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun getWidevineToken(pid: String, spx: String, guid: String, country: String = "EG"): String? {
        return try {
            val ts = System.currentTimeMillis()
            val base = "client-type=website&guid=$guid&country=$country&timestamp=$ts"
            val sign = hmacSha512Hex(base, HASH_KEY)
            val url = "$spxV3Base/externalAuthorization/drmToken/vualto?globalUserId=$guid&country=$country&timestamp=$ts"
            val body = mapOf("releasePids" to listOf(pid), "protectionScheme" to "widevine")
            val res = app.post(
                url,
                json = body,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "application/json",
                    "Authorization" to "Bearer $spx",
                    "SPX-Auth-Sign" to sign,
                    "Client-Type" to "website"
                ),
                timeout = 20
            ).text
            val t = parseJson<DrmTokenResp>(res).token
            if (t.isNullOrBlank()) Log.d(TAG, "drm token empty for $pid: ${res.take(300)}")
            t
        } catch (e: Exception) {
            Log.d(TAG, "drm token error $pid: ${e.message}")
            null
        }
    }

    private suspend fun fetchAiring(slug: String, spx: String?): AiringChannel? {
        return try {
            val now = System.currentTimeMillis() / 1000
            val url = "$epgBase/epg/channel/events/airing?channels=$slug&when=$now&lang=ar"
            val headers = mutableMapOf("User-Agent" to ua, "Accept" to "application/json", "xtype" to "portal")
            if (!spx.isNullOrBlank()) headers["Authorization"] = "Bearer $spx"
            val txt = app.get(url, headers = headers, timeout = 20).text
            parseJson<AiringResp>(txt).data?.firstOrNull()
        } catch (e: Exception) {
            Log.d(TAG, "airing $slug failed: ${e.message}")
            null
        }
    }

    private fun pickDash(streams: List<StreamUrl>): StreamUrl? {
        // prefer Widevine DASH (Android). Fallback to any DASH/MPD.
        streams.firstOrNull { it.assetTypes?.contains("dash_widevine_spa") == true && !it.url.isNullOrBlank() }?.let { return it }
        streams.firstOrNull { it.format == "MPEG-DASH" && !it.url.isNullOrBlank() }?.let { return it }
        streams.firstOrNull { it.url?.contains(".mpd") == true }?.let { return it }
        return null
    }

    private fun pickHls(streams: List<StreamUrl>): StreamUrl? {
        streams.firstOrNull { it.assetTypes?.contains("hls_fairplay_spa") == true && !it.url.isNullOrBlank() }?.let { return it }
        streams.firstOrNull { it.format == "M3U" && !it.url.isNullOrBlank() }?.let { return it }
        streams.firstOrNull { it.url?.contains(".m3u8") == true }?.let { return it }
        return null
    }

    private fun resolveUrl(base: String, ref: String): String {
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
                    out.add(q to resolveUrl(base, uri))
                }
                i = j
            }
            i++
        }
        return out.distinctBy { it.second }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfterLast("/").substringBefore("?").ifBlank { data }
        val chName = try {
            ensureAllChannels().find { it.slug == slug }?.title ?: slug
        } catch (_: Exception) { slug }

        val guest = ensureGuest()
        val spx = guest?.first
        val guid = guest?.second

        val airing = fetchAiring(slug, spx)
        val events = airing?.events ?: emptyList()
        val live = events.firstOrNull { it.status == "live" } ?: events.firstOrNull()
        val streams = live?.streamingUrl?.filter { !it.url.isNullOrBlank() } ?: emptyList()

        if (streams.isEmpty()) {
            Log.d(TAG, "no streams for $slug")
            return false
        }

        val dash = pickDash(streams)
        val hls = pickHls(streams)

        val mediaHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to "https://starzplay.com/",
            "Origin" to "https://starzplay.com"
        )

        var emitted = false

        // ---- 1) DASH Widevine (main, contains all qualities 1080/720/540/360 adaptive) ----
        if (dash?.url != null) {
            val pid = dash.pid
            var token: String? = null
            if (pid != null && spx != null && guid != null) {
                token = getWidevineToken(pid, spx, guid)
            }
            if (token != null) {
                val licenseWithToken = "$WIDEVINE_LICENSE?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
                try {
                    callback.invoke(
                        newDrmExtractorLink(
                            source = this.name,
                            name = "$chName - تلقائي (Widevine - كل الجودات 1080/720/540/360)",
                            url = dash.url,
                            type = ExtractorLinkType.DASH,
                            uuid = WIDEVINE_UUID,
                        ) {
                            this.referer = "https://starzplay.com/"
                            this.headers = mediaHeaders
                            this.quality = Qualities.Unknown.value
                            this.licenseUrl = licenseWithToken
                            this.keyRequestParameters = hashMapOf(
                                "x-vudrm-token" to token,
                                "Content-Type" to "application/octet-stream"
                            )
                        }
                    )
                    emitted = true
                    Log.d(TAG, "drm dash emitted for $slug pid=$pid")
                } catch (e: Exception) {
                    Log.d(TAG, "newDrmExtractorLink failed: ${e.message}")
                }
            }
            // fallback plain DASH (will fail without license, but keeps qualities visible)
            if (!emitted) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$chName - تلقائي (DASH)",
                        url = dash.url,
                    ) {
                        this.referer = "https://starzplay.com/"
                        this.headers = mediaHeaders
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.DASH
                    }
                )
                emitted = true
            }
        }

        // ---- 2) HLS variants (each quality as separate link = بجميع الجودات) ----
        if (hls?.url != null) {
            try {
                val master = app.get(hls.url, headers = mediaHeaders, timeout = 15).text
                val levels = parseM3u8Levels(master, hls.url)
                if (levels.isNotEmpty()) {
                    // try to reuse same Widevine token for HLS Widevine if needed,
                    // but HLS here is FairPlay (iOS). We still expose qualities;
                    // Android will prefer DASH above. Expose as M3U8 plain so user sees qualities.
                    for ((q, u) in levels) {
                        val tag = if (q == Qualities.Unknown.value) "تلقائي" else "${q}p"
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$chName ($tag)",
                                url = u,
                            ) {
                                this.referer = "https://starzplay.com/"
                                this.headers = mediaHeaders
                                this.quality = q
                                this.type = ExtractorLinkType.M3U8
                            }
                        )
                        emitted = true
                    }
                    Log.d(TAG, "hls qualities for $slug -> ${levels.size}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "hls parse failed $slug: ${e.message}")
            }
            // always emit master as well if nothing emitted from it
            if (!emitted) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$chName - تلقائي (HLS)",
                        url = hls.url,
                    ) {
                        this.referer = "https://starzplay.com/"
                        this.headers = mediaHeaders
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                    }
                )
                emitted = true
            }
        }

        // ---- 3) any remaining streams as fallback ----
        if (!emitted) {
            for (s in streams) {
                val u = s.url ?: continue
                val isDash = u.contains(".mpd") || s.format == "MPEG-DASH"
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = chName,
                        url = u,
                    ) {
                        this.referer = "https://starzplay.com/"
                        this.headers = mediaHeaders
                        this.quality = Qualities.Unknown.value
                        this.type = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                    }
                )
                emitted = true
            }
        }

        // program info for logs
        if (live != null) Log.d(TAG, "live now $slug: ${live.title} (${live.status})")
        return emitted
    }
}
