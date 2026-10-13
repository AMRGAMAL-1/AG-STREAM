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

    // Free HLS endpoints (verified, require rotana.net referer).
    // NOTE: SearchResponse URLs must NOT end in .m3u8 — the desktop client
    // hijacks raw .m3u8 links into its yt-dlp resolver (shows a fake
    // "playlist <date>" series with 0 episodes) and never calls load().
    // So cards carry a slug URL and load()/loadLinks() map it to the HLS.
    // Some are geo-restricted outside MENA. Logos: Wikimedia Commons (verified).
    private val channels = listOf(
        Ch("Rotana Khalijiah - خليجية", "khaleejiya", "https://rotana.hibridcdn.net/rotananet/khaleejiya_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D8%AE%D9%84%D9%8A%D8%AC%D9%8A%D8%A9.png")),
        Ch("Rotana Drama - دراما", "drama", "https://rotana.hibridcdn.net/rotananet/drama_net-7Y83PP5adWixDF93/playlist.m3u8", logo("Rotana%20Drama.png")),
        Ch("Rotana Cinema - سينما", "cinema", "https://rotana.hibridcdn.net/rotananet/cinema_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D8%B3%D9%8A%D9%86%D9%85%D8%A7%20ksa.jpg")),
        Ch("Rotana Cinema Egypt - سينما مصر", "cinemamasr", "https://rotana.hibridcdn.net/rotananet/cinemamasr_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D8%B3%D9%8A%D9%86%D9%85%D8%A7%20%D9%85%D8%B5%D8%B1.jpg")),
        Ch("Rotana Comedy - كوميدي", "comedy", "https://rotana.hibridcdn.net/rotananet/comedy_net-7Y83PP5adWixDF93/playlist.m3u8", logo("Rotana%20Comedy.png")),
        Ch("Rotana Music - موسيقى", "music", "https://rotana.hibridcdn.net/rotananet/music_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D9%85%D9%88%D8%B3%D9%8A%D9%82%D9%89.png")),
        Ch("Rotana Clip - كليب", "clip", "https://rotana.hibridcdn.net/rotananet/clip_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D8%A3%D9%81%D9%84%D8%A7%D9%85.png")),
        Ch("Rotana Classic - كلاسيك", "classic", "https://rotana.hibridcdn.net/rotananet/classical_net-7Y83PP5adWixDF93/playlist.m3u8", logo("%D8%B1%D9%88%D8%AA%D8%A7%D9%86%D8%A7%20%D9%83%D9%84%D8%A7%D8%B3%D9%8A%D9%83.png")),
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
        // Cards carry slug URLs; accept legacy raw .m3u8 URLs too.
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

    // hibridcdn 403s segment requests without a rotana.net Referer
    // (playlists pass, .ts fails) — forward full headers via the link.
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
        // data may be a slug URL (new) or a raw .m3u8 (legacy cached cards).
        val streamUrl = channels.find { slugUrl(it.slug) == data }?.url
            ?: channels.find { it.url == data }?.url
            ?: data
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = streamUrl,
            ) {
                this.referer = referer
                this.headers = streamHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
