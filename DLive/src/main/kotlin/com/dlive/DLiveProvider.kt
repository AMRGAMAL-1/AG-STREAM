package com.dlive

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DLiveProvider : MainAPI() {
    override var mainUrl = "https://dlive.sx"
    override var name = "DLive"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val TAG = "DLive"
    private val GROUP_ORDER = listOf(
        "beIN Sports",
        "رياضة",
        "أفلام ومسلسلات",
        "أخبار",
        "أطفال",
        "وثائقي",
        "موسيقى",
        "ترفيه ومنوعات",
    )

    override val mainPage = mainPageOf(
        "beIN Sports" to "beIN Sports",
        "all" to "كل القنوات",
        "رياضة" to "رياضة",
        "أفلام ومسلسلات" to "أفلام ومسلسلات",
        "أخبار" to "أخبار",
        "أطفال" to "أطفال",
        "وثائقي" to "وثائقي",
        "موسيقى" to "موسيقى",
        "ترفيه ومنوعات" to "ترفيه ومنوعات",
    )

    private fun DCh.toLive(): SearchResponse {
        return newLiveSearchResponse(name = this.name, url = "$mainUrl/watch.php?id=${this.id}") {
            this.posterUrl = DLOGOS[this@toLive.id]
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = if (request.data == "all") {
            DCHANNELS.sortedWith(compareBy({ GROUP_ORDER.indexOf(it.group).takeIf { i -> i >= 0 } ?: 99 }, { it.name }))
        } else {
            DCHANNELS.filter { it.group == request.data }.sortedBy { it.name }
        }
        Log.d(TAG, "getMainPage ${request.name} -> ${list.size}")
        return newHomePageResponse(request.name, list.map { it.toLive() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return DCHANNELS.filter { it.name.contains(query, ignoreCase = true) }.map { it.toLive() }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1)
        val ch = DCHANNELS.find { it.id == id }
        val name = ch?.name ?: "DLive"
        return newLiveStreamLoadResponse(name = name, url = url, dataUrl = url) {
            this.posterUrl = ch?.id?.let { DLOGOS[it] }
        }
    }
    private suspend fun emitM3u8(
        m3u8: String,
        chName: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val txt: String
        try {
            txt = app.get(m3u8, referer = referer, timeout = 15).text
        } catch (_: Exception) {
            return false
        }
        if (txt.length < 50) return false
        val low = txt.lowercase()
        if (low.contains("hackerman") || low.contains("tiktok") || low.contains(".image?")) {
            Log.d(TAG, "poisoned playlist, skipping ${m3u8.take(100)}")
            return false
        }
        if (!low.contains("#extm3u")) return false
        Log.d(TAG, "m3u8 ${m3u8.take(100)}")
        val levels = parseM3u8Levels(txt, m3u8)
        if (levels.isNotEmpty()) {
            var ok = false
            for ((q, u) in levels) {
                val tag = if (q == Qualities.Unknown.value) "Auto" else "${q}p"
                try {
                    callback(newExtractorLink(source = this.name, name = "$chName ($tag)", url = u) {
                        this.referer = referer
                        this.quality = q
                        this.type = ExtractorLinkType.M3U8
                        this.headers = headers
                    })
                    ok = true
                } catch (_: Exception) {}
            }
            return ok
        }
        try {
            callback(newExtractorLink(source = this.name, name = chName, url = m3u8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
                this.headers = headers
            })
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun resolveStreamUrl(base: String, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("http")) return r
        if (r.startsWith("//")) return "https:$r"
        return try { java.net.URL(java.net.URL(base), r).toString() } catch (_: Exception) { r }
    }

    private fun parseM3u8Levels(body: String, base: String): List<Pair<Int, String>> {
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
    private suspend fun resolveWideIptv(iframeUrl: String, referer: String): String? {
        return try {
            val doc = app.get(iframeUrl, referer = referer, timeout = 15).document
            var inner = doc.selectFirst("iframe[src]")?.attr("src")?.trim() ?: return null
            if (inner.startsWith("//")) inner = "https:$inner"
            else if (inner.startsWith("/")) inner = "https://wideiptv.top$inner"
            if (!inner.contains("wideiptv.top")) return null
            val p = app.get(inner, referer = iframeUrl, timeout = 15).text
            Regex("""streamUrl\s*:\s*"([^"]+)"""").find(p)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.trim()?.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks $data")
        val id = Regex("""[?&]id=(\d+)""").find(data)?.groupValues?.get(1) ?: return false
        val chName = DCHANNELS.find { it.id == id }?.name ?: id
        val dembedHeaders = mapOf(
            "Referer" to "https://dembed.st/",
            "Origin" to "https://dembed.st",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        )
        var found = false
        try {
            val watch = app.get(data, referer = mainUrl, timeout = 20).text
            val frames = Regex("""<iframe[^>]+src=["']([^"']*stream-$id\.php[^"']*)["']""")
                .findAll(watch).map { it.groupValues[1] }.toList()
            val btns = Regex("""data-url=["']([^"']*stream-$id\.php[^"']*)["']""")
                .findAll(watch).map { it.groupValues[1] }.toList()
            val streamPages = (frames + btns).map {
                when {
                    it.startsWith("http") -> it
                    it.startsWith("//") -> "https:$it"
                    it.startsWith("/") -> "$mainUrl$it"
                    else -> "$mainUrl/$it"
                }
            }.distinct()
            Log.d(TAG, "stream pages=${streamPages.size}")
            for (sp in streamPages) {
                try {
                    val sHtml = app.get(sp, referer = data, timeout = 20).text
                    val embeds = Regex("""https?://[A-Za-z0-9./_-]*dembed[A-Za-z0-9./_?=&%-]*""")
                        .findAll(sHtml).map { it.value }.distinct().toList()
                    for (emb in embeds) {
                        try {
                            val eHtml = app.get(emb, referer = sp, timeout = 20).text
                            var m3u8 = Regex("""const SRC\s*=\s*"([^"]+)"""").find(eHtml)?.groupValues?.get(1)
                            if (m3u8.isNullOrBlank()) {
                                m3u8 = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").find(eHtml)?.value
                            }
                            if (m3u8.isNullOrBlank() || !m3u8.startsWith("http")) continue
                            if (emitM3u8(m3u8, chName, "https://dembed.st/", dembedHeaders, callback)) {
                                found = true
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "embed failed $emb: ${e.message}")
                        }
                    }
                    val sDoc = try {
                        org.jsoup.Jsoup.parse(sHtml)
                    } catch (_: Exception) {
                        null
                    }
                    sDoc?.select("iframe[src]")?.forEach { f ->
                        try {
                            var src = f.attr("src").trim()
                            if (src.isBlank() || src.contains("dlive.sx")) return@forEach
                            if (src.startsWith("//")) src = "https:$src"
                            if (!src.startsWith("http")) return@forEach
                            if (src.contains("wideiptv.top")) {
                                val resolved = resolveWideIptv(src, sp)
                                if (!resolved.isNullOrBlank()) {
                                    if (emitM3u8(resolved, chName, "https://wideiptv.top/", emptyMap(), callback)) {
                                        found = true
                                    }
                                }
                                return@forEach
                            }
                            var ok = false
                            loadExtractor(src, sp, subtitleCallback) {
                                ok = true
                                callback(it)
                            }
                            if (ok) found = true
                        } catch (e: Exception) {
                            Log.d(TAG, "iframe failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "stream page failed $sp: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "loadLinks error: ${e.message}")
        }
        Log.d(TAG, "loadLinks found=$found")
        return found
    }
}
