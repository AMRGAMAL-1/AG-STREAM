package com.mbclive

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MbcLiveProvider : MainAPI() {
    override var mainUrl = "https://shahid.mbc.net"
    override var name = "MBC Live"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val TAG = "MbcLive"
    private data class Ch(val name: String, val group: String, val slug: String, val url: String, val logo: String?)

    private val W = "https://commons.wikimedia.org/wiki/Special:FilePath/"
    private fun logo(file: String) = "$W$file?width=400"
    private val QB = "https://blogger.googleusercontent.com/img/b/R29vZ2xl/"
    private fun qanwat(path: String) = "$QB$path"

    private val channels = listOf(
        Ch("MBC 1", "MBC العامة", "mbc-1", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-1/15cf99af5de54063fdabfefe66adc075/index.m3u8", qanwat("AVvXsEgXqXbGozEXxNDlelzjuSkDdFC4o1-6Ooq10kZQRoOj2-h_IhdZ5wYDRCIZ0sPKZs242uVDH-JrcPRi519vnFKRB27OelCr72epTT6dN9_Ef5uSq2wOO604KdKXKNeYZcHniyLWQdXo1_VoWDUmFqq9ThOpwHAQkEiQacn7RMW8j6GTgNyntMxXTZMj8kF2/s400/mbc-1.jpg")),
        Ch("MBC 1 مصر", "MBC العامة", "mbc-1-egypt", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-1-na/eec141533c90dd34722c503a296dd0d8/index.m3u8", logo("MBC%201%20Logo.svg")),
        Ch("MBC 4", "MBC العامة", "mbc-4", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-4/24f134f1cd63db9346439e96b86ca6ed/index.m3u8", qanwat("AVvXsEig5in9dw2u28W8lTQlkBqR-ZL4Nn9ThR7MCrQxo2RKHDJRl5bdAAd3jViK0oGg4vDeHOT7TrMOZ28_7yYBBzmqzlfsQ0zRX8OWVKeEILAFc_EiHisDAEpOSMmnhvWgCxg5wCmg_75x6WM8zP-3_gfXKDS337C2VUBBhnWJUOIv6eAFVhFsuzqRl0l4kT95/s400/mbc4.jpg")),
        Ch("MBC 5", "MBC العامة", "mbc-5", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-5/ee6b000cee0629411b666ab26cb13e9b/index.m3u8", qanwat("AVvXsEjzrFTi1z5NAJKvrWvziUQrXFVUM8MuYydHcFxwri0OEjSl8v2dUFEqHkdUvaax5g7pH-_pBSTvGXkwVNZE9QwjpAX5yTHpkvKgZsbOElf6K1zMDEWt7alEKf6L0T87N4hHXPRWNIGSwbgwPpYQ9yTbcVtpj86cZTD-pDLUfg3hoeNuYDUng1BOBRzAfz2C/s400/mbc5.jpg")),
        Ch("MBC العراق", "MBC العامة", "mbc-iraq", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-iraq/e38c44b1b43474e1c39cb5b90203691e/index.m3u8", qanwat("AVvXsEhb0eQN6w7Xsk7Px91Eu9gVIXowbpP94HeyponWdYDBJ2zvHBxyHX9hB1_Qj7qBcK76AG7rVJSoADCb-SGsZf2bN8q2DxoHJRRKdJ5Xzl8EfJNxjeUee8L5hJ68JVGhIw2W7QCeKXTvSEeBgUaT8Fk3NcAGrILNQZaOhJneuC1U5-7tPhNLNCUFLps94JGD/s400/MBCIraq.jpg")),
        Ch("MBC Persia - فارسية", "MBC العامة", "mbc-persia", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-persia/818ee8e4b592dc497608f066d825bfb4/index.m3u8", logo("MBC%20Persia%20Logo.png")),
        Ch("MBC Masr - مصر", "MBC مصر", "mbc-masr", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr/956eac069c78a35d47245db6cdbb1575/index.m3u8", qanwat("AVvXsEjrNFIsCSIGk3QhyCwOvH-YNNnW_h2PaZiuCG2rmBywNkvCnWAvEphceoRua1Qo-6qZ1mMNxV2r5RQCmqZyRy8QMOUmq2GZpLV_9pN48GXi-TXQRmb1ONsQHQZMeL9cqMM5FvykdR05JOfVtFIpi7ZoSUVZ-vGbF2djZmNtQ_F9MB06VmA7TsXERV1CrPmM/s400/mbcmasr.jpg")),
        Ch("MBC Masr 2", "MBC مصر", "mbc-masr-2", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-2/754931856515075b0aabf0e583495c68/index.m3u8", qanwat("AVvXsEj3YvzX0Ypq98BoQm696Pg1n5wOy_FeFGbZqpPLqlL-XQI7XvC2iwd-ddeWmIOrNsCKG_oN8ktpPSxAmk7AUkhV83J5hyphenhyphenj0QrcJQu8fpd0pd4EFPJJODPSdnHimeQAfKSQNs3RtjdBon1MS6jaC7aQRX9mcl4M4H-xlg6qxG14LbnhR2Ej6awxJ5hwa73Ii/s400/mbcmasr2.jpg")),
        Ch("MBC Masr Drama", "MBC مصر", "mbc-masr-drama", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-drama/567b703c19ede6598222de81b0e4508b/index.m3u8", qanwat("AVvXsEh1gmDutdtX8QOTeIjr0H1jkxmaVJL_uaTRJoZdEBmhJr7tQGJSXQroIcP05nTogIUNGeU8zkDEvIse24mxKZCX083MCkbwC4hRIAyOR6d56Qv8BBev2XQ4Bc49lsezTBaaMhxuftpA9wvIDQWrAYN3nrkpRpYnijMZPZZua73xFIn_dnHOj6kY_hOm9ZdH/s400/MBC-Masr-Drama.jpg")),
        Ch("MBC Drama", "دراما", "mbc-drama", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-drama/2c28a458e2f3253e678b07ac7d13fe71/index.m3u8", qanwat("AVvXsEjCd9quyhgDaxCKpyylh0-ztbUTmeC8rqnjE3CMLLvOF9Svs3t7-9MsbTOl0ZhF0BKgWY6KWDriR7-4ToM3hGaVBPKvtJ7gN8iJF21wiWG7ZvnUeEHzNJ-o-AcNj2CxsYSYYuQOKcp0YVcPIgiy5cU6ON-uHWhdv9kWtVzsSZ86ehiNXq_nGk0rLC831tv4/s400/mbcdrama.jpg")),
        Ch("MBC Drama+", "دراما", "mbc-drama-plus", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-plus-drama/e37251ec2aac8f6c98f75cd0fa37cd28/index.m3u8", logo("MBC%20Drama%20Logo.svg")),
        Ch("MBC Bollywood", "منوعات", "mbc-bollywood", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-bollywood/546eb40d7dcf9a209255dd2496903764/index.m3u8", qanwat("AVvXsEiCxlILC9xfS044eqlXFYlFMljkn0w6a8hRqDtcSXe_j5hhc1IgMBGZwTmW0v6tlAIP7_Y2iv6f7bRn6RXIG50VXsNUaqI87X0zXkxQ-nbO4AuQdlesXcxMgbPIXdMURePicpFRGrtQhEwsF4dI2nbcFd9ArS-KYlJHG2IBPcGkXblVu94fSUzxnsVNqWGX/s400/mbcbollywood.jpg")),
        Ch("MBC Mood - ونسة", "منوعات", "mbc-mood", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-mood/78367bf48ccdba501d0d014a10c21031/index.m3u8", logo("Wanasah%20Logo.svg")),
        Ch("العربية - Al Arabiya", "الإخبارية", "alarabiya", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-alarabiya/7f90de73d777d04f3dada92f90d35c44/index.m3u8", logo("Al-Arabiya%20new%20logo.svg")),
        Ch("الحدث - Al Hadath", "الإخبارية", "alhadath", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-hadath/2ff87ec4c2f3ede35295a20637d9f8fd/index.m3u8", logo("Al%20Hadath%20TV%20logo%202023.svg")),
        Ch("MBC FM", "راديو", "mbc-fm", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-fm/3f36f7db6086acf058dc51681c87f8ad/index.m3u8", logo("MBC%20FM%20Logo.svg")),
        Ch("Panorama FM - بانوراما", "راديو", "panorama-fm", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-panorama-fm/ea22f3856a1b772f793c43eb5d1c0636/index.m3u8", logo("Panorama%20FM%20Logo.svg")),
    )
    private fun slugUrl(slug: String) = "$mainUrl/live/$slug"

    private val streamHeaders = mapOf(
        "Referer" to "https://shahid.mbc.net/",
        "Origin" to "https://shahid.mbc.net",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "all" to "كل القنوات",
        "MBC العامة" to "MBC العامة",
        "MBC مصر" to "MBC مصر",
        "دراما" to "دراما",
        "الإخبارية" to "الإخبارية",
        "منوعات" to "منوعات",
        "راديو" to "راديو",
    )

    private fun Ch.toLive(): SearchResponse {
        return newLiveSearchResponse(name = this.name, url = slugUrl(this.slug)) {
            this.posterUrl = this@toLive.logo
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = if (request.data == "all") channels
        else channels.filter { it.group == request.data }
        Log.d(TAG, "getMainPage ${request.name} -> ${list.size}")
        return newHomePageResponse(request.name, list.map { it.toLive() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return channels.filter { it.name.contains(query, ignoreCase = true) }
            .map { it.toLive() }
    }

    override suspend fun load(url: String): LoadResponse {
        val ch = channels.find { slugUrl(it.slug) == url } ?: channels.find { it.url == url }
        val streamUrl = ch?.url ?: url
        return newLiveStreamLoadResponse(
            name = ch?.name ?: "MBC Live",
            url = url,
            dataUrl = streamUrl
        ) {
            this.posterUrl = ch?.logo
        }
    }

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
                            this.referer = ""
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
                this.referer = ""
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
