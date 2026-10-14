package com.mbclive

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class RotanaLiveProvider : MainAPI() {
    override var mainUrl = "https://rotana.net"
    override var name = "Rotana Live"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val TAG = "RotanaLive"
    private val referer = "https://rotana.net/"

    private data class Ch(val name: String, val slug: String, val url: String, val logo: String?)

    private val W = "https://commons.wikimedia.org/wiki/Special:FilePath/"
    private fun logo(file: String) = "$W$file?width=400"
    // Channel cards pulled from QanwatLive (blogger banners, s400)
    private val QB = "https://blogger.googleusercontent.com/img/b/R29vZ2xl/"
    private fun qanwat(path: String) = "$QB$path"
    private val channels = listOf(
        Ch("Rotana Khalijiah - خليجية", "khaleejiya", "https://rotana.hibridcdn.net/rotananet/khaleejiya_net-7Y83PP5adWixDF93/playlist.m3u8", qanwat("AVvXsEjTY6n84kOiIIrX8xT0tlSFbk4iijam3uZYSD5eImNnzexOfXdGRgZl9arAD099sdIwdUDYQCv8TBMQPxxTTON8g9KQrqvxP5c3OVw-cpvi0MdWaJPoLQvtG7mR33UJhMifhyUyAwW2PIMEwHixsO2enpurVZaK8sbx36atb3DPptipBFZ29WwIS6Ctr737/s400/rotanakhalejia.jpg")),
        Ch("Rotana Drama - دراما", "drama", "https://rotana.hibridcdn.net/rotananet/drama_net-7Y83PP5adWixDF93/playlist.m3u8", qanwat("AVvXsEgN8CgLz1Hix0PNldk5oeL1lpui3DPNBeWQ82xSi7vrAmUWpJBW5bmtLyz4Q3j30NzwbDxczSsGLwpgxWkRspjGqLPjx673FAK4bdR-MKNClum2tCG7UxJUF4Gta07ybO0xfhNrVTluGLJNUwzLJ-v-668Y8c3YuvIJwk1st0zcw5kkF-YEBQbf16ATOFEN/s400/rotanadrama.jpg")),
        Ch("Rotana Cinema - سينما", "cinema", "https://rotana.hibridcdn.net/rotananet/cinema_net-7Y83PP5adWixDF93/playlist.m3u8", qanwat("AVvXsEiohwCmmpRzp0olKuDNJYBbFYouXMn6oRjOstLS1p8KsFX4B7DZyKzhn5lOJGII3r9rv9Potqyzwy1QWSu5ZvhiZwYeVVZRvqMOO6TT0xgZzV3IKVektZNszEyWO3EkErRSs3LYmprFng3KZ4KKyx148C9UMkjlweb5P2bCtPPlgDxH45aGUF0FVc39otFw/s400/rotanacinema.jpg")),
        Ch("Rotana Cinema Egypt - سينما مصر", "cinemamasr", "https://rotana.hibridcdn.net/rotananet/cinemamasr_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D8%B3%D9%8A%D9%86%D9%85%D8%A7%20%D9%85%D8%B5%D8%B1.jpg")),
        Ch("Rotana Comedy - كوميدي", "comedy", "https://rotana.hibridcdn.net/rotananet/comedy_net-7Y83PP5adWixDF93/playlist.m3u8", qanwat("AVvXsEi3yE4tUcv3CDrVMSN7hqkkJzfyIjeovxwy1xADCwyUMu7AJfXMyyr4b_lhyZCGVwVyR7ua9v3OLJBJ0i9Hfqe5VVMmxguHBCEOC9sr5DwJNv5N1EIm0V8W26Q8UUCpdhSm1dc4QsS3S0YpeB7dqtt3k5irxY15ojWV3GmlAmHBvjhU1PCF_PBVPNGbSzDZ/s400/rotanacomedy.jpg")),
        Ch("Rotana Music - موسيقى", "music", "https://rotana.hibridcdn.net/rotananet/music_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D9%85%D9%88%D8%B3%D9%8A%D9%82%D9%89.png")),
        Ch("Rotana Clip - كليب", "clip", "https://rotana.hibridcdn.net/rotananet/clip_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D8%A3%D9%81%D9%84%D8%A7%D9%85.png")),
        Ch("Rotana Classic - كلاسيك", "classic", "https://rotana.hibridcdn.net/rotananet/classical_net-7Y83PP5adWixDF93/playlist.m3u8", qanwat("AVvXsEgDUV_d4oDKPde7qqyb9PqeO2_sfoyQrRCbgRqpUjjeytzxq5L33fOOMkHxOjk9fcCnDqRnSZqAyfF_ZA7vKMJjTXcJQjJxb-l5m33D8WMW2OynPaec1TtfwuZmjKyUAk4Xbl4rqMf76Z970UMVMapTMFAv15triA_HkwrLhjzBUZVE5QfETHZcOvEuI5V3/s400/rotanaclassic.jpg")),
        Ch("Rotana LBC", "lbc", "https://rotana.hibridcdn.net/rotananet/lbc_net-7Y83PP5adWixDF93/playlist.m3u8", logo("Logo%20LBC%20International%20%28Lebanon%29.png")),
        Ch("Rotana Risala - رسالة", "risala", "https://rotana.hibridcdn.net/rotananet/risala_net-7Y83PP5adWixDF93/playlist.m3u8", logo("AlResalah%20TV.png")),
    )

    private fun slugUrl(slug: String) = "$mainUrl/live/$slug"

    override val mainPage = mainPageOf(
        "all" to "كل القنوات",
    )

    private fun Ch.toLive(): SearchResponse {
        return newLiveSearchResponse(name = this.name, url = slugUrl(this.slug)) {
            this.posterUrl = this@toLive.logo
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage ${request.name} -> ${channels.size}")
        return newHomePageResponse(request.name, channels.map { it.toLive() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return channels.filter { it.name.contains(query, ignoreCase = true) }
            .map { it.toLive() }
    }

    override suspend fun load(url: String): LoadResponse {
        val ch = channels.find { slugUrl(it.slug) == url } ?: channels.find { it.url == url }
        val streamUrl = ch?.url ?: url
        return newLiveStreamLoadResponse(
            name = ch?.name ?: "Rotana Live",
            url = url,
            dataUrl = streamUrl
        ) {
            this.posterUrl = ch?.logo
        }
    }
    private val streamHeaders = mapOf(
        "Referer" to referer,
        "Origin" to "https://rotana.net",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ch = channels.find { slugUrl(it.slug) == data } ?: channels.find { it.url == data }
        val streamUrl = ch?.url ?: data
        val baseName = ch?.name ?: this.name
        try {
            val txt = app.get(streamUrl, headers = streamHeaders, timeout = 15).text
            val levels = parseM3u8Levels(txt, streamUrl)
            if (levels.isNotEmpty()) {
                for ((q, u) in levels) {
                    val tag = if (q == Qualities.Unknown.value) "Auto" else "${q}p"
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$baseName ($tag)",
                            url = u,
                        ) {
                            this.referer = referer
                            this.headers = streamHeaders
                            this.quality = q
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                }
                Log.d(TAG, "variants for $baseName -> ${levels.size}")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "variant probe failed: ${e.message}")
        }
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = baseName,
                url = streamUrl,
            ) {
                this.referer = referer
                this.headers = streamHeaders
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
            }
        )
        return true
    }

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
}
