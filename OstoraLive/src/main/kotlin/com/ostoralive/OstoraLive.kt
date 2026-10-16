package com.ostoralive

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
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class OstoraLiveProvider : MainAPI() {
    override var mainUrl = "https://www.ostora.tv"
    override var name = "Ostora Live"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val TAG = "OstoraLive"
    private val apiHost = "https://ctyv15wkomzjeg09e33345xw0rnjfqu4rgdtzh2iao7zl1e7q1.sa041.shop"
    private val keyHex = "4e5c6d1a8b3fe8137a3b9df26a9c4de195267b8e6f6c0b4e1c3ae1d27f2b4e6f"
    private val ivHex = "a9c21f8d7e6b4a9db12e4f9d5c1a7b8e"
    private val defaultUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private val referer = "https://www.ostora.tv/"
    private val deadHosts = setOf("random-cdn.link", "x.random-cdn.link", "hex-cdn.link", "tv.radarapp.me")
    private val clearkeyUuid: UUID = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
    private val noRefererHosts = setOf(
        "edgenextcdn.net", "mncdn.com", "akamaized.net",
        "starzplayarabia.com", "intigral-ott.net", "shahid.net", "shahid.mbc.net",
        "ments.shop"
    )

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
        @JsonProperty("agent") val agent: String? = null
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
        var lastErr: Exception? = null
        repeat(3) { attempt ->
            try {
                val txt = app.get(
                    "$apiHost/api/v6.2/$path",
                    headers = mapOf("User-Agent" to defaultUa),
                    timeout = 30
                ).text.trim()
                if (txt.isNotEmpty()) return decryptApi(txt)
            } catch (e: Exception) {
                lastErr = e
                Log.d(TAG, "api failed $path (try ${attempt + 1}/3): ${e.message}")
            }
            if (attempt < 2) {
                delay(700L * (attempt + 1) + (0..400).random())
            }
        }
        Log.d(TAG, "api giving up $path: ${lastErr?.message}")
        return null
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
        val sections = fetchSections().filter { isLiveSection(it) }
        if (sections.isEmpty()) {
            Log.d(TAG, "catalog fetch empty, keeping previous cache")
            if (s != null && m != null) return s to m
            return emptyList<Section>() to emptyMap<String, List<Item>>()
        }
        val map = mutableMapOf<String, List<Item>>()
        val gate = Semaphore(4)
        coroutineScope {
            sections.map { sec ->
                async {
                    val sid = sec.id ?: return@async
                    gate.withPermit {
                        map[sid] = fetchSubs(sid)
                    }
                }
            }.awaitAll()
        }
        sectionsCache = sections
        subsCache = map
        cacheTime = now
        Log.d(TAG, "catalog: ${sections.size} sections, ${map.values.sumOf { it.size }} groups")
        return sections to map
    }

    override val mainPage = mainPageOf("all" to "كل القنوات")

    private fun groupToLive(secName: String, g: Item): SearchResponse {
        val data = LinkData("group", g.id ?: "", null, g.name ?: "Unknown", g.image).toJson()
        return newLiveSearchResponse("${secName} - ${g.name}", data, TvType.Live) {
            this.posterUrl = g.image?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (sections, subs) = ensureCatalog()
        val rows = sections.mapNotNull { sec ->
            val sid = sec.id ?: return@mapNotNull null
            val list = (subs[sid] ?: emptyList()).map { groupToLive(sec.name ?: "", it) }
            if (list.isEmpty()) return@mapNotNull null
            HomePageList(sec.name ?: "Live", list)
        }
        return newHomePageResponse(rows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val (sections, subs) = ensureCatalog()
        val out = mutableListOf<SearchResponse>()
        out.addAll(sections.filter { it.name?.contains(query, ignoreCase = true) == true }
            .flatMap { sec -> (subs[sec.id] ?: emptyList()).map { groupToLive(sec.name ?: "", it) } })
        val groups = subs.values.flatten().filter { it.name?.contains(query, ignoreCase = true) == true }
        coroutineScope {
            subs.values.flatten().map { g ->
                async {
                    if (g.id.isNullOrBlank()) return@async emptyList<SearchResponse>()
                    fetchSubs(g.id!!).mapNotNull { ch ->
                        if (ch.name?.contains(query, ignoreCase = true) != true) return@mapNotNull null
                        val data = LinkData("channel", ch.id ?: "", g.id, ch.name ?: "Unknown", ch.image).toJson()
                        newLiveSearchResponse(ch.name ?: "Unknown", data, TvType.Live) {
                            this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }.chunked(15).flatMap { chunk -> chunk.awaitAll().flatten() }.let { out.addAll(it) }
        }
        out.addAll(groups.map { g ->
            val secName = sections.find { s -> subs[s.id]?.any { it.id == g.id } == true }?.name ?: ""
            groupToLive(secName, g)
        })
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = try { parseJson<LinkData>(url) } catch (_: Exception) { null }
        if (data?.kind == "channel") {
            return newLiveStreamLoadResponse(name = data.name, url = url, dataUrl = url) {
                this.posterUrl = data.poster
            }
        }
        val items = if (data != null && !data.id.isBlank()) fetchSubs(data.id) else emptyList()
        val usedNumbers = mutableSetOf<Int>()
        val episodes = items.mapIndexed { idx, ch ->
            val ed = LinkData("channel", ch.id ?: "", data?.id, ch.name ?: "Unknown", ch.image).toJson()
            var num = Regex("""(\d+)""").find(ch.name ?: "")?.groupValues?.get(1)?.toIntOrNull()
                ?: (ch.id?.toIntOrNull()?.let { (it % 900) + 100 } ?: (((ch.id ?: "").hashCode() and 0x7fffffff) % 900 + 100))
            while (usedNumbers.contains(num)) num++
            usedNumbers.add(num)
            newEpisode(ed) {
                this.name = ch.name
                this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                this.episode = num
                this.season = 1
            }
        }
        return newTvSeriesLoadResponse(data?.name ?: "Live", url, TvType.TvSeries, episodes) {
            this.posterUrl = data?.poster
        }
    }

    private fun hostOf(url: String): String {
        return try { java.net.URL(url).host.lowercase() } catch (_: Exception) { "" }
    }

    private fun isDeadUrl(url: String): Boolean {
        val host = hostOf(url)
        if (host.isBlank() || host.length > 64) return true
        if (host in deadHosts || deadHosts.any { h -> host == h || host.endsWith(".$h") }) return true
        return false
    }

    private fun needsNoReferer(url: String): Boolean {
        val host = hostOf(url)
        return noRefererHosts.any { h -> host == h || host.endsWith(".$h") }
    }

    private fun mediaHeaders(url: String, ua: String): Map<String, String> {
        val base = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Accept-Language" to "ar,en;q=0.9,en-US;q=0.8",
            "Connection" to "keep-alive"
        )
        val host = hostOf(url)
        if (host.endsWith("shahid.net") || host.endsWith("shahid.mbc.net")
            || host.endsWith("edgenextcdn.net") || host.endsWith("mncdn.com")
        ) {
            base["Origin"] = "https://shahid.mbc.net"
        }
        if (!needsNoReferer(url)) base["Referer"] = referer
        return base
    }

    private fun mediaReferer(url: String): String? {
        return if (needsNoReferer(url)) null else referer
    }

    private fun toB64UrlPadded(s: String): String {
        var t = s.trim().replace("+", "-").replace("/", "_")
        t = t.trimEnd('=')
        while (t.length % 4 != 0) t += "="
        return t
    }

    private fun isDeadMpdBody(body: String): Boolean {
        if (body.isBlank()) return true
        if (body.contains("denied by region", ignoreCase = true)) return true
        if (body.contains("Access Denied", ignoreCase = true)) return true
        if (body.contains("403 Forbidden", ignoreCase = true)) return true
        val t = body.trimStart()
        if (t.startsWith("<html", ignoreCase = true)) return true
        if (t.startsWith("<!doctype html", ignoreCase = true)) return true
        if (!body.contains("<MPD", ignoreCase = true)) return true
        return false
    }

    private fun isPermanentDeny(body: String): Boolean {
        return body.contains("denied by region", ignoreCase = true)
    }

    private suspend fun probeMpd(url: String, headers: Map<String, String>): String? {
        repeat(3) { attempt ->
            try {
                val body = app.get(url, headers = headers, timeout = 15).text
                if (!isDeadMpdBody(body)) return body
                if (isPermanentDeny(body)) return null
                Log.d(TAG, "mpd probe $url flaky 403 (try ${attempt + 1}/3), retrying")
            } catch (e: Exception) {
                val msg = (e.message ?: "").lowercase()
                if ("region" in msg) return null
                Log.d(TAG, "mpd probe $url err (try ${attempt + 1}/3): ${e.message}")
            }
            if (attempt < 2) kotlinx.coroutines.delay(800)
        }
        return null
    }

    private fun capQuality(url: String): String {
        if (!url.contains(".mpd")) return url
        var u = url.replace("video_height:144-1080", "video_height:144-720")
        u = u.replace("video_height:144- 1080", "video_height:144-720")
        return u
    }

    private fun parseSources(src: String): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        for (rawPart in src.split("<<R>>")) {
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
        val cappedUrl = capQuality(url)
        val headers = mediaHeaders(cappedUrl, ua)
        val ref = mediaReferer(cappedUrl)
        val low = cappedUrl.lowercase()
        return try {
            when {
                ".mpd" in low -> {
                    if (token != null && ":" in token) {
                        val parts = token.split(":", limit = 2).map { it.trim() }
                        if (parts.size == 2 && parts[0].length >= 8 && parts[1].length >= 8) {
                            val keyB64 = toB64UrlPadded(parts[0])
                            val kidB64 = toB64UrlPadded(parts[1])
                            val probe = probeMpd(cappedUrl, headers)
                            if (probe == null) {
                                Log.d(TAG, "drm mpd probe failed but emitting anyway ($cappedUrl)")
                            }
                            callback.invoke(
                                newDrmExtractorLink(
                                    source = this.name,
                                    name = "$title - تلقائي",
                                    url = cappedUrl,
                                    type = ExtractorLinkType.DASH,
                                    uuid = clearkeyUuid,
                                ) {
                                    if (ref != null) this.referer = ref
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
                        mpd = probeMpd(cappedUrl, headers)
                        if (mpd == null) dead = true
                    } catch (_: Exception) {
                        dead = true
                    }
                    if (dead) {
                        Log.d(TAG, "dead mpd skipped ($cappedUrl)")
                        return false
                    }
                    if (mpd != null && mpd.contains("ContentProtection") && mpd.contains("edef8ba9")) {
                        Log.d(TAG, "drm mpd without key, skipped ($cappedUrl)")
                        return false
                    }
                    callback.invoke(
                        newExtractorLink(this.name, "$title - تلقائي", cappedUrl) {
                            if (ref != null) this.referer = ref
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
                            if (ref != null) this.referer = ref
                            this.headers = headers
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.M3U8
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
        val groupId = link.parent
        val channels = if (!groupId.isNullOrBlank()) fetchSubs(groupId) else emptyList()
        val ch = channels.find { it.id == link.id }
            ?: return false
        val src = ch.source ?: return false
        val ua = ch.agent?.takeIf { it.isNotBlank() } ?: defaultUa
        val sources = parseSources(src)
        if (sources.isEmpty()) return false
        var emitted = false
        for ((u, token) in sources) {
            try {
                val low = u.lowercase()
                when {
                    "youtube.com" in low || "youtu.be" in low -> {
                        val collected = mutableListOf<ExtractorLink>()
                        try {
                            loadExtractor(u, referer, subtitleCallback) { collected.add(it) }
                        } catch (_: Exception) { }
                        collected.forEach {
                            callback.invoke(
                                newExtractorLink(this.name, "${ch.name} - ${it.name}", it.url) {
                                    this.referer = it.referer
                                    this.headers = it.headers
                                    this.quality = it.quality
                                    this.type = it.type
                                }
                            )
                            emitted = true
                        }
                    }
                    ".mpd" in low -> {
                        if (emitDirect(ch.name ?: "Live", u, ua, token, callback)) emitted = true
                    }
                    else -> {
                        val headers = mediaHeaders(u, ua)
                        val ref = mediaReferer(u)
                        if (u.contains("manar") || u.contains("hamaza") || u.contains("maziika")) {
                            Log.d(TAG, "manar-style HLS (data:key+PNG), may show placeholder on Desktop: $u")
                        }
                        callback.invoke(
                            newExtractorLink(this.name, "${ch.name} - تلقائي", u) {
                                if (ref != null) this.referer = ref
                                this.headers = headers
                                this.quality = Qualities.Unknown.value
                                this.type = ExtractorLinkType.M3U8
                            }
                        )
                        emitted = true
                    }
                }
            } catch (_: Exception) { }
        }
        Log.d(TAG, "links for ${ch.name}: $emitted")
        return emitted
    }
}
