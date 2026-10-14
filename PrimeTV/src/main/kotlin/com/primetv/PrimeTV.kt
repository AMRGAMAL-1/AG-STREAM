package com.primetv

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PrimeTVProvider : MainAPI() {
    companion object {
        private const val TAG = "PrimeTV"

        private const val FB_PROJECT_ID = "primetv-ott"
        private const val FB_PROJECT_NUMBER = "4279425740"
        private const val FB_APP_ID = "1:4279425740:android:5d10b40557a95920ab8069"
        private const val FB_API_KEY = "AIzaSyC_5FBCQ6YQFODLseprxggyn6bCEonfBuw"
        private const val FB_PKG = "primetv.ott"
        private const val FB_CERT = "1DBA452074B7357165C6B38C19F82096F6D41D10"

        private const val FALLBACK_SB_URL = "https://lqqaxskbpfiakfcbzxsx.supabase.co"
        private const val FALLBACK_SB_KEY = "sb_publishable_5o_0QZaWXioNETZ1jRcyXg_NMATs69S"

        private const val DALVIK_UA = "Dalvik/2.1.0 (Linux; U; Android 14)"

        @Volatile private var cachedFid: String? = null
        @Volatile private var cachedToken: String? = null
        @Volatile private var cachedSbUrl: String? = null
        @Volatile private var cachedSbKey: String? = null
    }

    override var mainUrl = FALLBACK_SB_URL
    override var name = "AMR TV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    data class LinkData(
        val id: String,
        val name: String,
        val poster: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Pkg(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo_url") val logo: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Chan(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("package_id") val packageId: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo_url") val logo: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Src(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("user_agent") val userAgent: String? = null,
        @JsonProperty("referrer") val referrer: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FisResp(
        @JsonProperty("authToken") val authToken: FisToken? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class FisToken(
            @JsonProperty("token") val token: String? = null
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RcResp(
        @JsonProperty("state") val state: String? = null,
        @JsonProperty("entries") val entries: Map<String, String>? = null
    )

    @Volatile private var pkgsCache: List<Pkg>? = null
    @Volatile private var chansCache: List<Chan>? = null
    @Volatile private var cacheTime = 0L
    private val cacheTtl = 6 * 60 * 60 * 1000L

    private fun generateFid(): String {
        val randomBytes = ByteArray(17)
        SecureRandom().nextBytes(randomBytes)
        randomBytes[0] = ((randomBytes[0].toInt() and 0x0F) or 0x70).toByte()
        return Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).take(22)
    }

    private suspend fun fisToken(): String? {
        cachedToken?.let { return it }
        return try {
            val fid = generateFid()
            val res = app.post(
                "https://firebaseinstallations.googleapis.com/v1/projects/$FB_PROJECT_ID/installations",
                json = mapOf(
                    "fid" to fid,
                    "appId" to FB_APP_ID,
                    "authVersion" to "FIS_v2",
                    "sdkVersion" to "a:18.0.0"
                ),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Android-Package" to FB_PKG,
                    "X-Android-Cert" to FB_CERT,
                    "x-goog-api-key" to FB_API_KEY,
                    "User-Agent" to DALVIK_UA
                ),
                timeout = 20
            ).text
            val token = parseJson<FisResp>(res).authToken?.token
            if (!token.isNullOrBlank()) {
                cachedFid = fid
                cachedToken = token
            }
            token
        } catch (e: Exception) {
            Log.d(TAG, "fis failed: ${e.message}")
            null
        }
    }

    private suspend fun supabase(): Pair<String, String> {
        cachedSbUrl?.let { u -> cachedSbKey?.let { k -> return u to k } }
        try {
            val token = fisToken()
            if (!token.isNullOrBlank()) {
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
                val fid = cachedFid ?: generateFid()
                val res = app.post(
                    "https://firebaseremoteconfig.googleapis.com/v1/projects/$FB_PROJECT_NUMBER/namespaces/firebase:fetch",
                    json = mapOf(
                        "appVersion" to "1.0",
                        "firstOpenTime" to iso,
                        "timeZone" to "Africa/Cairo",
                        "appInstanceIdToken" to token,
                        "languageCode" to "ar-EG",
                        "appBuild" to "1",
                        "appInstanceId" to fid,
                        "countryCode" to "EG",
                        "analyticsUserProperties" to emptyMap<String, String>(),
                        "appId" to FB_APP_ID,
                        "platformVersion" to "34",
                        "sdkVersion" to "22.0.0",
                        "packageName" to FB_PKG
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Goog-Api-Key" to FB_API_KEY,
                        "X-Android-Package" to FB_PKG,
                        "X-Android-Cert" to FB_CERT,
                        "X-Goog-Firebase-Installations-Auth" to token,
                        "X-Firebase-RC-Fetch-Type" to "BASE/1",
                        "User-Agent" to DALVIK_UA
                    ),
                    timeout = 20
                ).text
                val entries = try { parseJson<RcResp>(res).entries } catch (_: Exception) { null }
                val u = entries?.get("supabase_url")?.takeIf { it.isNotBlank() }
                val k = entries?.get("supabase_key")?.takeIf { it.isNotBlank() }
                if (u != null && k != null) {
                    cachedSbUrl = u.trimEnd('/')
                    cachedSbKey = k
                    Log.d(TAG, "supabase dynamic config ok")
                    return cachedSbUrl!! to cachedSbKey!!
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "remote config failed: ${e.message}")
        }
        return FALLBACK_SB_URL to FALLBACK_SB_KEY
    }

    private suspend fun sbGet(path: String): String? {
        val (base, key) = supabase()
        return try {
            app.get(
                "$base/rest/v1/$path",
                headers = mapOf(
                    "apikey" to key,
                    "Authorization" to "Bearer $key",
                    "Accept" to "application/json"
                ),
                timeout = 25
            ).text
        } catch (e: Exception) {
            Log.d(TAG, "sbGet failed $path: ${e.message}")
            null
        }
    }

    private suspend fun fetchPackages(): List<Pkg> {
        val txt = sbGet("packages?select=id,name,logo_url&is_active=eq.true&is_deleted=eq.false&order=sort_order")
            ?: return emptyList()
        return try { parseJson<List<Pkg>>(txt) } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchAllChannels(): List<Chan> {
        val out = mutableListOf<Chan>()
        var offset = 0
        while (offset < 10000) {
            val txt = sbGet("channels?select=id,package_id,name,logo_url&is_active=eq.true&is_deleted=eq.false&order=sort_order&limit=1000&offset=$offset")
                ?: break
            val page = try { parseJson<List<Chan>>(txt) } catch (_: Exception) { break }
            if (page.isEmpty()) break
            out.addAll(page)
            if (page.size < 1000) break
            offset += 1000
        }
        return out
    }

    private suspend fun ensureCatalog(): Pair<List<Pkg>, List<Chan>> {
        val now = System.currentTimeMillis()
        val p = pkgsCache
        val c = chansCache
        if (p != null && c != null && now - cacheTime < cacheTtl) return p to c
        val pkgs = fetchPackages()
        val chans = if (pkgs.isNotEmpty()) fetchAllChannels() else emptyList()
        pkgsCache = pkgs
        chansCache = chans
        cacheTime = now
        Log.d(TAG, "catalog cached: ${pkgs.size} packages, ${chans.size} channels")
        return pkgs to chans
    }

    override val mainPage = mainPageOf("all" to "كل القنوات")

    private fun Chan.toLive(): SearchResponse {
        val data = LinkData(this.id ?: "", this.name ?: "Unknown", this.logo).toJson()
        return newLiveSearchResponse(this.name ?: "Unknown", data, TvType.Live) {
            this.posterUrl = this@toLive.logo?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (pkgs, chans) = ensureCatalog()
        if (request.data != "all") {
            val list = chans.filter { it.packageId == request.data }.map { it.toLive() }
            val label = pkgs.find { it.id == request.data }?.name ?: request.name
            return newHomePageResponse(label, list)
        }
        val byPkg = chans.groupBy { it.packageId }
        val rows = pkgs.mapNotNull { pkg ->
            val list = byPkg[pkg.id]?.map { it.toLive() } ?: return@mapNotNull null
            if (list.isEmpty()) return@mapNotNull null
            HomePageList(pkg.name ?: "AMR TV", list)
        }
        return newHomePageResponse(rows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val (_, chans) = ensureCatalog()
        return chans.filter { it.name?.contains(query, ignoreCase = true) == true }.map { it.toLive() }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = try { parseJson<LinkData>(url) } catch (_: Exception) { null }
        return newLiveStreamLoadResponse(
            name = data?.name ?: "AMR TV",
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = data?.poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = try { parseJson<LinkData>(data) } catch (_: Exception) { return false }
        if (link.id.isBlank()) return false
        val txt = sbGet("channel_sources?select=name,url,user_agent,referrer&channel_id=eq.${link.id}&is_active=eq.true&is_deleted=eq.false&order=sort_order")
            ?: return false
        val sources = try { parseJson<List<Src>>(txt) } catch (_: Exception) { return false }
        if (sources.isEmpty()) {
            Log.d(TAG, "no sources for ${link.name}")
            return false
        }
        var emitted = false
        sources.forEachIndexed { index, src ->
            var u = (src.url ?: "").trim().replace("www.elahmad.coo", "www.elahmad.com")
            if (u.isBlank() || u.equals("null", ignoreCase = true)) return@forEachIndexed
            if (u.startsWith("//")) u = "https:$u"
            if (!u.startsWith("http")) return@forEachIndexed
            val headers = mutableMapOf<String, String>()
            src.userAgent?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.let {
                headers["User-Agent"] = it
            }
            src.referrer?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.let {
                headers["Referer"] = it
            }
            val type = when {
                u.contains(".mpd") -> ExtractorLinkType.DASH
                u.contains(".m3u8") || u.contains("/hls/") || u.contains("extension=ts") -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.M3U8
            }
            callback.invoke(
                newExtractorLink(
                    this.name,
                    src.name?.takeIf { it.isNotBlank() } ?: "Server ${index + 1}",
                    u
                ) {
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                    this.referer = headers["Referer"] ?: ""
                    this.type = type
                }
            )
            emitted = true
        }
        Log.d(TAG, "sources for ${link.name} -> ${sources.size}")
        return emitted
    }
}
