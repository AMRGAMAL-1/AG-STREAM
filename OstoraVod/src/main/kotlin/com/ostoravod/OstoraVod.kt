package com.ostoravod

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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class OstoraVodProvider : MainAPI() {
    override var mainUrl = "https://www.ostora.tv"
    override var name = "Ostora"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "OstoraVod"
    private val apiHost = "https://ctyv15wkomzjeg09e33345xw0rnjfqu4rgdtzh2iao7zl1e7q1.sa041.shop"
    private val keyHex = "4e5c6d1a8b3fe8137a3b9df26a9c4de195267b8e6f6c0b4e1c3ae1d27f2b4e6f"
    private val ivHex = "a9c21f8d7e6b4a9db12e4f9d5c1a7b8e"
    private val defaultUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private val referer = "https://www.ostora.tv/"
    private val widevineUuid: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
    private val clearkeyUuid: UUID = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
    private val widevineBrand = "2be49af0-6fbd-4511-8e11-3d6523185bb4"
    private val deadHosts = setOf("random-cdn.link", "x.random-cdn.link", "tv.radarapp.me")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiRoot(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("data") val data: List<Section>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Section(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("image") val image: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatResp(
        @JsonProperty("data") val data: CatData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("items") val items: List<Item>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Item(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("agent") val agent: String? = null,
        @JsonProperty("download") val download: String? = null
    )

    data class LinkData(
        val kind: String,
        val id: String,
        val parent: String?,
        val name: String,
        val poster: String?
    )

    @Volatile private var sectionsCache: List<Section>? = null
    @Volatile private var subsCache: Map<String, List<Item>>? = null
    @Volatile private var cacheTime = 0L
    private val cacheTtl = 6 * 60 * 60 * 1000L

    private fun hexToBytes(h: String): ByteArray {
        val out = ByteArray(h.length / 2)
        for (i in out.indices) out[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun decryptApi(raw: String): String {
        var clean = raw.trim().replace("-", "+").replace("_", "/")
        while (clean.length % 4 != 0) clean += "="
        val ct = Base64.decode(clean, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(keyHex), "AES"), IvParameterSpec(hexToBytes(ivHex)))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private suspend fun api(path: String): String? {
        return try {
            val txt = app.get(
                "$apiHost/api/v6.2/$path",
                headers = mapOf("User-Agent" to defaultUa),
                timeout = 20
            ).text.trim()
            if (txt.isEmpty()) null else decryptApi(txt)
        } catch (e: Exception) {
            Log.d(TAG, "api failed $path: ${e.message}")
            null
        }
    }

    private suspend fun fetchSections(): List<Section> {
        val txt = api("main?page=1") ?: return emptyList()
        return try {
            parseJson<ApiRoot>(txt).data ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun isLiveSection(s: Section): Boolean {
        val n = s.name ?: return false
        return n.contains("سيرفر") || n.contains("بث") || n.contains("راديو") || n.contains("محطات")
    }

    private suspend fun fetchSubs(sectionId: String): List<Item> {
        val txt = api("category/$sectionId") ?: return emptyList()
        return try {
            parseJson<CatResp>(txt).data?.items ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun ensureCatalog(): Pair<List<Section>, Map<String, List<Item>>> {
        val now = System.currentTimeMillis()
        val s = sectionsCache
        val m = subsCache
        if (s != null && m != null && now - cacheTime < cacheTtl) return s to m
        val sections = fetchSections().filter { !isLiveSection(it) }
        val map = mutableMapOf<String, List<Item>>()
        coroutineScope {
            sections.map { sec ->
                async {
                    val sid = sec.id ?: return@async
                    map[sid] = fetchSubs(sid)
                }
            }.awaitAll()
        }
        sectionsCache = sections
        subsCache = map
        cacheTime = now
        Log.d(TAG, "catalog: ${sections.size} sections, ${map.values.sumOf { it.size }} subs")
        return sections to map
    }

    override val mainPage = mainPageOf("all" to "الكل")

    private fun subToResult(secName: String, sub: Item): SearchResponse {
        val data = LinkData("sub", sub.id ?: "", null, sub.name ?: "Unknown", sub.image).toJson()
        return newTvSeriesSearchResponse(sub.name ?: "Unknown", data) {
            this.posterUrl = sub.image?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (sections, subs) = ensureCatalog()
        val rows = sections.mapNotNull { sec ->
            val sid = sec.id ?: return@mapNotNull null
            val list = (subs[sid] ?: emptyList()).map { subToResult(sec.name ?: "", it) }
            if (list.isEmpty()) return@mapNotNull null
            HomePageList(sec.name ?: "Ostora", list)
        }
        return newHomePageResponse(rows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val (sections, subs) = ensureCatalog()
        val out = mutableListOf<SearchResponse>()
        out.addAll(sections.filter { it.name?.contains(query, ignoreCase = true) == true }
            .flatMap { sec -> (subs[sec.id] ?: emptyList()).map { subToResult(sec.name ?: "", it) } })
        val matchedSubs = subs.values.flatten().filter { it.name?.contains(query, ignoreCase = true) == true }
        out.addAll(matchedSubs.map { sub ->
            val secName = sections.find { s -> subs[s.id]?.any { it.id == sub.id } == true }?.name ?: ""
            subToResult(secName, sub)
        })
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = try { parseJson<LinkData>(url) } catch (_: Exception) { null }
            ?: return newTvSeriesLoadResponse("Ostora", url, TvType.TvSeries, emptyList())
        val items = if (!data.id.isBlank()) fetchSubs(data.id) else emptyList()
        val episodes = items.map { it ->
            val ed = LinkData("item", it.id ?: "", data.id, it.name ?: "Unknown", it.image).toJson()
            newEpisode(ed) {
                this.name = it.name
                this.posterUrl = it.image?.takeIf { img -> img.isNotBlank() }
            }
        }
        return newTvSeriesLoadResponse(data.name, url, TvType.TvSeries, episodes) {
            this.posterUrl = data.poster
            this.plot = "شاهد ${data.name}"
        }
    }

    private fun hostOf(url: String): String {
        return try { java.net.URL(url).host.lowercase() } catch (_: Exception) { "" }
    }

    private fun isDeadUrl(url: String): Boolean {
        val host = hostOf(url)
        if (host.isBlank() || host.length > 64) return true
        if (host in deadHosts) return true
        if (host.endsWith(".shop") && host[0].isDigit() && host.length > 40) return true
        if (url.trimEnd('/') == "https://3arabflix.com" || url.trimEnd('/') == "http://3arabflix.com") return true
        return false
    }

    private fun toB64UrlPadded(s: String): String {
        var t = s.trim().replace("+", "-").replace("/", "_")
        t = t.trimEnd('=')
        while (t.length % 4 != 0) t += "="
        return t
    }

    private fun parseSources(src: String): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        val chunks = src.split("<<R>>")
        for (rawPart in chunks) {
            var chunk = rawPart.trim()
            if (chunk.isEmpty()) continue
            if ("<F>" in chunk) chunk = chunk.substringAfter("<F>")
            var token: String? = null
            if ("###" in chunk) {
                token = chunk.substringAfter("###").trim().split(Regex("\\s")).firstOrNull()?.trim()?.takeIf { it.length >= 8 }
                chunk = chunk.substringBefore("###")
            }
            if ("|" in chunk) chunk = chunk.substringBefore("|")
            chunk = chunk.trim()
            if (chunk.isEmpty()) continue
            val urls = Regex("""https?://[^\s"'<>\\!]+""").findAll(chunk)
                .map { it.value.trim().trimEnd(';', ',', '.', ')', '!') }
                .filter { it.startsWith("http") }
                .distinct()
                .toList()
            var first = true
            for (u in urls) {
                if (isDeadUrl(u)) continue
                out.add(u to if (first) token else null)
                first = false
            }
        }
        return out.distinct()
    }

    private suspend fun emitDirect(
        title: String,
        url: String,
        ua: String,
        token: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to ua, "Referer" to referer)
        val low = url.lowercase()
        return try {
            when {
                ".mpd" in low -> {
                    if (token != null && ":" in token) {
                        val parts = token.split(":", limit = 2).map { it.trim() }
                        if (parts.size == 2 && parts[0].length >= 8 && parts[1].length >= 8) {
                            val keyB64 = toB64UrlPadded(parts[0])
                            val kidB64 = toB64UrlPadded(parts[1])
                            try {
                                app.get(url, headers = headers, timeout = 15).text
                            } catch (e: Exception) {
                                val msg = (e.message ?: "").lowercase()
                                if ("403" in msg || "404" in msg || "forbidden" in msg || "not found" in msg) {
                                    Log.d(TAG, "dead mpd skipped ($url): ${e.message}")
                                    return false
                                }
                            }
                            callback.invoke(
                                newDrmExtractorLink(
                                    source = this.name,
                                    name = "$title - تلقائي",
                                    url = url,
                                    type = ExtractorLinkType.DASH,
                                    uuid = clearkeyUuid,
                                ) {
                                    this.referer = referer
                                    this.headers = headers
                                    this.quality = Qualities.Unknown.value
                                    this.kid = kidB64
                                    this.key = keyB64
                                    this.kty = "oct"
                                }
                            )
                            return true
                        }
                    }
                    var mpd: String? = null
                    var dead = false
                    try {
                        mpd = app.get(url, headers = headers, timeout = 15).text
                    } catch (e: Exception) {
                        val msg = (e.message ?: "").lowercase()
                        if ("403" in msg || "404" in msg || "forbidden" in msg || "not found" in msg) dead = true
                    }
                    if (dead) {
                        Log.d(TAG, "dead mpd skipped ($url)")
                        return false
                    }
                    if (mpd != null && mpd.contains("ContentProtection") && mpd.contains("edef8ba9")) {
                        Log.d(TAG, "drm mpd without key, skipped ($url)")
                        return false
                    }
                    callback.invoke(
                        newExtractorLink(this.name, "$title - تلقائي", url) {
                            this.referer = referer
                            this.headers = headers
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.DASH
                        }
                    )
                    true
                }
                else -> {
                    callback.invoke(
                        newExtractorLink(this.name, "$title - تلقائي", url) {
                            this.referer = referer
                            this.headers = headers
                            this.quality = Qualities.Unknown.value
                            this.type = if (".mp4" in low) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                        }
                    )
                    true
                }
            }
        } catch (_: Exception) { false }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = try { parseJson<LinkData>(data) } catch (_: Exception) { return false }
        val items = if (!link.parent.isNullOrBlank()) fetchSubs(link.parent!!) else emptyList()
        val it = items.find { it.id == link.id } ?: return false
        val src = it.source ?: return false
        val ua = it.agent?.takeIf { a -> a.isNotBlank() } ?: defaultUa
        val sources = parseSources(src)
        if (sources.isEmpty()) return false
        var emitted = false
        for ((u, token) in sources) {
            val low = u.lowercase()
            try {
                if ("youtube.com" in low || "youtu.be" in low) {
                    val collected = mutableListOf<ExtractorLink>()
                    try {
                        loadExtractor(u, referer, subtitleCallback) { collected.add(it) }
                    } catch (_: Exception) { }
                    collected.forEach { ext ->
                        callback.invoke(
                            newExtractorLink(this.name, "${it.name} - ${ext.name}", ext.url) {
                                this.referer = ext.referer
                                this.headers = ext.headers
                                this.quality = ext.quality
                                this.type = ext.type
                            }
                        )
                        emitted = true
                    }
                } else if (low.contains(".m3u8") || low.contains(".mpd") || low.contains(".mp4") ||
                    low.contains("/hls/") || low.contains("extension=ts") || low.endsWith(".json")
                ) {
                    if (emitDirect(it.name ?: "Ostora", u, ua, token, callback)) emitted = true
                } else {
                    val collected = mutableListOf<ExtractorLink>()
                    try {
                        loadExtractor(u, referer, subtitleCallback) { collected.add(it) }
                    } catch (_: Exception) { }
                    if (collected.isEmpty()) {
                        if (emitDirect(it.name ?: "Ostora", u, ua, token, callback)) emitted = true
                    } else {
                        collected.forEach { ext ->
                            callback.invoke(
                                newExtractorLink(this.name, "${it.name} - ${ext.name}", ext.url) {
                                    this.referer = ext.referer
                                    this.headers = ext.headers
                                    this.quality = ext.quality
                                    this.type = ext.type
                                }
                            )
                            emitted = true
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        Log.d(TAG, "links for ${it.name}: $emitted")
        return emitted
    }
}
