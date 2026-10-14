package com.mbclive

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder
import java.util.UUID

class MbcLiveProvider : MainAPI() {
    override var mainUrl = "https://shahid.mbc.net"
    override var name = "MBC Live"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val TAG = "MbcLive"
    private data class Ch(val name: String, val group: String, val slug: String, val url: String, val logo: String?)

    private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ShahidNext(
        @JsonProperty("props") val props: ShahidProps? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ShahidProps(
        @JsonProperty("pageProps") val pageProps: ShahidPageProps? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ShahidPageProps(
        @JsonProperty("requiredSubscription") val requiredSubscription: Boolean? = null,
        @JsonProperty("playout") val playout: ShahidPlayout? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ShahidPlayout(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("drm") val drm: Boolean? = null,
        @JsonProperty("mediaFormat") val mediaFormat: String? = null,
        @JsonProperty("drmInfo") val drmInfo: ShahidDrmInfo? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ShahidDrmInfo(
        @JsonProperty("signature") val signature: String? = null,
        @JsonProperty("fairplay") val fairplay: String? = null
    )

    private data class DynCh(
        val name: String,
        val group: String,
        val slug: String,
        val pageSlug: String,
        val channelId: String,
        val logo: String?
    )

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

    private fun shahidLogo(id: String) =
        "https://shahid.mbc.net/mediaObject/$id?height=253&width=450&croppingPoint=&version=1"

    private val dynChannels = listOf(
        DynCh("MBC 3", "MBC العامة", "mbc-3", "mbc3", "816771", shahidLogo("42dcf4e4-17e3-466a-8289-2951b7d48361")),
        DynCh("الشرق Bloomberg", "الإخبارية", "asharq-bloomberg", "الشرق-bloomberg", "862837", shahidLogo("d6bf9900-29a6-4edb-a39e-8b513a5bdb3f")),
        DynCh("SBC", "السعودية", "sbc", "sbc", "946940", shahidLogo("b3e92644-efd5-40d4-874c-b4987da33b39")),
        DynCh("ذكريات", "دراما", "thikrayat", "ذكريات", "946945", shahidLogo("1bf50e9c-cbe0-4f52-b736-fb1a83e424ea")),
        DynCh("الإخبارية", "السعودية", "alekhbaria", "الإخبارية", "946948", shahidLogo("2794d7e6-7f13-40a8-badb-692223afbcb0")),
        DynCh("السعودية", "السعودية", "saudi-tv", "السعودية", "946938", shahidLogo("e31676cd-b604-4daf-88a4-710598ed23bd")),
        DynCh("السعودية الآن", "السعودية", "ksa-now", "السعودية-الآن", "999927", shahidLogo("80d24dd4-d332-42e3-a6fb-0f2e7519b327")),
        DynCh("القرآن الكريم", "ديني", "quran-tv", "القرآن-الكريم", "946946", shahidLogo("14e16773-f7fe-47b8-ab7b-7d5cd4a862d1")),
        DynCh("السنة النبوية", "ديني", "sunna-tv", "السنة-النبوية", "946942", shahidLogo("568aebb3-4d34-4fec-95e4-5a495f2ee17e")),
    )

    private fun dynPageUrl(d: DynCh): String {
        val enc = URLEncoder.encode(d.pageSlug, "UTF-8").replace("+", "%20")
        return "https://shahid.mbc.net/ar/livestream/$enc/livechannel-${d.channelId}"
    }

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
        "السعودية" to "السعودية",
        "ديني" to "ديني",
        "منوعات" to "منوعات",
        "راديو" to "راديو",
    )

    private fun Ch.toLive(): SearchResponse {
        return newLiveSearchResponse(name = this.name, url = slugUrl(this.slug)) {
            this.posterUrl = this@toLive.logo
        }
    }

    private fun DynCh.toLive(): SearchResponse {
        return newLiveSearchResponse(name = this.name, url = slugUrl(this.slug)) {
            this.posterUrl = this@toLive.logo
        }
    }

    private suspend fun fetchPlayout(d: DynCh): ShahidPlayout? {
        return try {
            val txt = app.get(dynPageUrl(d), headers = streamHeaders, timeout = 20).text
            val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(txt)?.groupValues?.get(1) ?: return null
            val page = parseJson<ShahidNext>(json)
            if (page.props?.pageProps?.requiredSubscription == true) {
                Log.d(TAG, "requires subscription: ${d.slug}")
                return null
            }
            page.props?.pageProps?.playout?.takeIf { !it.url.isNullOrBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "playout fetch failed ${d.slug}: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "all") {
            val all = channels.map { it.toLive() } + dynChannels.map { it.toLive() }
            Log.d(TAG, "getMainPage ${request.name} -> ${all.size}")
            return newHomePageResponse(request.name, all)
        }
        val list = channels.filter { it.group == request.data }.map { it.toLive() } +
            dynChannels.filter { it.group == request.data }.map { it.toLive() }
        Log.d(TAG, "getMainPage ${request.name} -> ${list.size}")
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return (channels.filter { it.name.contains(query, ignoreCase = true) }.map { it.toLive() } +
            dynChannels.filter { it.name.contains(query, ignoreCase = true) }.map { it.toLive() })
    }

    override suspend fun load(url: String): LoadResponse {
        val dyn = dynChannels.find { slugUrl(it.slug) == url }
        if (dyn != null) {
            return newLiveStreamLoadResponse(
                name = dyn.name,
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = dyn.logo
            }
        }
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
        val dyn = dynChannels.find { slugUrl(it.slug) == data }
        if (dyn != null) return loadDynLinks(dyn, callback)
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

    private suspend fun loadDynLinks(
        dyn: DynCh,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pl = fetchPlayout(dyn) ?: return false
        val streamUrl = pl.url ?: return false
        if (pl.drm == true) {
            val license = pl.drmInfo?.signature
            if (license.isNullOrBlank()) {
                Log.d(TAG, "drm without license: ${dyn.slug}")
                return false
            }
            return try {
                callback.invoke(
                    newDrmExtractorLink(
                        source = this.name,
                        name = "${dyn.name} - تلقائي (Widevine - كل الجودات)",
                        url = streamUrl,
                        type = ExtractorLinkType.DASH,
                        uuid = WIDEVINE_UUID,
                    ) {
                        this.referer = "https://shahid.mbc.net/"
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                        this.licenseUrl = license
                    }
                )
                Log.d(TAG, "drm dash emitted for ${dyn.slug}")
                true
            } catch (e: Exception) {
                Log.d(TAG, "drm link failed ${dyn.slug}: ${e.message}")
                false
            }
        }
        return try {
            val txt = app.get(streamUrl, headers = streamHeaders, timeout = 15).text
            val levels = parseM3u8Levels(txt, streamUrl)
            if (levels.isEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = dyn.name,
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
            for ((q, u) in levels) {
                val tag = if (q == Qualities.Unknown.value) "Auto" else "${q}p"
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${dyn.name} ($tag)",
                        url = u,
                    ) {
                        this.referer = ""
                        this.headers = streamHeaders
                        this.quality = q
                        this.type = ExtractorLinkType.M3U8
                    }
                )
            }
            Log.d(TAG, "variants for ${dyn.name} -> ${levels.size}")
            true
        } catch (e: Exception) {
            Log.d(TAG, "dyn probe failed ${dyn.slug}: ${e.message}")
            false
        }
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
