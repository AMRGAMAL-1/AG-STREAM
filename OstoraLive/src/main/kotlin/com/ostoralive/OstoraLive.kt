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
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        val sections = fetchSections().filter { isLiveSection(it) }
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
        val episodes = items.map { ch ->
            val ed = LinkData("channel", ch.id ?: "", data?.id, ch.name ?: "Unknown", ch.image).toJson()
            newEpisode(ed) {
                this.name = ch.name
                this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
            }
        }
        return newTvSeriesLoadResponse(data?.name ?: "Live", url, TvType.Live, episodes) {
            this.posterUrl = data?.poster
        }
    }

    private fun extractUrls(src: String): List<String> {
        return Regex("""https?://[^\s"'<>\\]+""").findAll(src)
            .map { it.value.trim().trimEnd(';', ',', '.', ')') }
            .filter { it.startsWith("http") }
            .filter {
                val host = try { java.net.URL(it).host } catch (_: Exception) { "" }
                host.isNotBlank() && host.length <= 64
            }
            .distinct()
            .toList()
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
        val headers = mapOf("User-Agent" to ua, "Referer" to referer)
        var emitted = false
        for (u in extractUrls(src)) {
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
                        callback.invoke(
                            newExtractorLink(this.name, "${ch.name} - تلقائي", u) {
                                this.referer = referer
                                this.headers = headers
                                this.quality = Qualities.Unknown.value
                                this.type = ExtractorLinkType.DASH
                            }
                        )
                        emitted = true
                    }
                    else -> {
                        callback.invoke(
                            newExtractorLink(this.name, "${ch.name} - تلقائي", u) {
                                this.referer = referer
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
