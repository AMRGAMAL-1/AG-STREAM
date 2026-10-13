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

    private val channels = listOf(
        Ch("MBC 1", "MBC العامة", "mbc-1", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-1/15cf99af5de54063fdabfefe66adc075/index.m3u8", logo("MBC%201%20Logo.svg")),
        Ch("MBC 1 مصر", "MBC العامة", "mbc-1-egypt", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-1-na/eec141533c90dd34722c503a296dd0d8/index.m3u8", logo("MBC%201%20Logo.svg")),
        Ch("MBC 4", "MBC العامة", "mbc-4", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-4/24f134f1cd63db9346439e96b86ca6ed/index.m3u8", logo("MBC%204%20Logo.svg")),
        Ch("MBC 5", "MBC العامة", "mbc-5", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-5/ee6b000cee0629411b666ab26cb13e9b/index.m3u8", logo("MBC%205%20Logo.svg")),
        Ch("MBC العراق", "MBC العامة", "mbc-iraq", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-iraq/e38c44b1b43474e1c39cb5b90203691e/index.m3u8", logo("MBC%20Iraq.svg")),
        Ch("MBC Persia - فارسية", "MBC العامة", "mbc-persia", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-persia/818ee8e4b592dc497608f066d825bfb4/index.m3u8", logo("MBC%20Persia%20Logo.png")),
        Ch("MBC Masr - مصر", "MBC مصر", "mbc-masr", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr/956eac069c78a35d47245db6cdbb1575/index.m3u8", logo("MBC%20Masr%20Logo.svg")),
        Ch("MBC Masr 2", "MBC مصر", "mbc-masr-2", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-2/754931856515075b0aabf0e583495c68/index.m3u8", logo("MBC%20Masr%202%20Logo.svg")),
        Ch("MBC Masr Drama", "MBC مصر", "mbc-masr-drama", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-drama/567b703c19ede6598222de81b0e4508b/index.m3u8", logo("MBC%20Masr%20Drama%20Logo.svg")),
        Ch("MBC Drama", "دراما", "mbc-drama", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-drama/2c28a458e2f3253e678b07ac7d13fe71/index.m3u8", logo("MBC%20Drama%20Logo.svg")),
        Ch("MBC Drama+", "دراما", "mbc-drama-plus", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-plus-drama/e37251ec2aac8f6c98f75cd0fa37cd28/index.m3u8", logo("MBC%20Drama%20Logo.svg")),
        Ch("MBC Bollywood", "منوعات", "mbc-bollywood", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-bollywood/546eb40d7dcf9a209255dd2496903764/index.m3u8", logo("MBC%20Bollywood%20Logo.svg")),
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
        val streamUrl = channels.find { slugUrl(it.slug) == data }?.url
            ?: channels.find { it.url == data }?.url
            ?: data
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = streamUrl,
            ) {
                this.referer = ""
                this.headers = streamHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
