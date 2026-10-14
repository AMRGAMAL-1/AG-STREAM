package com.mbcrotana

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

class MbcRotanaProvider : MainAPI() {
    override var mainUrl = "https://www.qanwatlive.com"
    override var name = "MbcRotana"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl/feeds/posts/default?q=MBC" to "قنوات MBC",
        "$mainUrl/feeds/posts/default?q=روتانا" to "قنوات روتانا",
    )

    private fun bannerUrl(thumb: String?): String? {
        if (thumb.isNullOrBlank()) return null
        return thumb.replace(Regex("""/s\d+(-c)?(-rw)?/"""), "/s400/")
    }

    private fun isMbc(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("mbc") || title.contains("ام بي سي") || title.contains("إم بي سي")
    }

    private fun isRotana(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("rotana") || title.contains("روتانا")
    }

    private fun entryToResult(entry: FeedEntry, filter: (String) -> Boolean): SearchResponse? {
        val title = entry.title?.text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!filter(title)) return null
        val href = entry.links?.firstOrNull { it.rel == "alternate" }?.href
            ?: entry.links?.firstOrNull()?.href ?: return null
        return newLiveSearchResponse(title, href, TvType.Live) {
            this.posterUrl = bannerUrl(entry.thumbnail?.url)
        }
    }

    private suspend fun fetchFeed(feedUrl: String, filter: (String) -> Boolean): List<SearchResponse> {
        return try {
            val text = app.get(feedUrl, referer = mainUrl).text
            val feed = parseJson<FeedRoot>(text).feed
            feed?.entries?.mapNotNull { entryToResult(it, filter) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun filterFor(request: MainPageRequest): (String) -> Boolean {
        return if (request.name.contains("روتانا")) this::isRotana else this::isMbc
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val limit = 60
        val startIndex = (page - 1) * limit + 1
        val url = "${request.data}&alt=json&max-results=$limit&start-index=$startIndex"
        val items = fetchFeed(url, filterFor(request))
        return newHomePageResponse(request.name, items, hasNext = items.size >= limit)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val text = try {
            app.get("$mainUrl/feeds/posts/default?alt=json&max-results=60&q=$encoded", referer = mainUrl).text
        } catch (e: Exception) {
            return emptyList()
        }
        return try {
            val feed = parseJson<FeedRoot>(text).feed
            feed?.entries?.mapNotNull {
                val title = it.title?.text?.trim()?.takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                val href = it.links?.firstOrNull { l -> l.rel == "alternate" }?.href
                    ?: it.links?.firstOrNull()?.href ?: return@mapNotNull null
                newLiveSearchResponse(title, href, TvType.Live) {
                    this.posterUrl = bannerUrl(it.thumbnail?.url)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        if (title.isBlank()) return null
        val banner = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val iframeSrc = document.selectFirst("iframe#iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: document.select("iframe[src]").firstOrNull {
                val src = it.attr("src")
                src.isNotBlank() && src.startsWith("http") && !src.contains("comment-editor")
            }?.attr("src")
            ?: return null
        val playerUrl = fixUrl(iframeSrc)
        return newMovieLoadResponse(title, url, TvType.Live, playerUrl) {
            this.posterUrl = banner
            this.plot = plot ?: "بث مباشر لقناة $title"
        }
    }

    private fun extractQualityInt(text: String?): Int {
        val t = text?.lowercase()?.trim() ?: return Qualities.Unknown.value
        Regex("""(\d{3,4})\s*[xp]""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            t.contains("4k") || t.contains("2160") -> 2160
            t.contains("1440") -> 1440
            t.contains("1080") -> 1080
            t.contains("720") -> 720
            t.contains("480") -> 480
            t.contains("360") -> 360
            t.contains("240") -> 240
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerHtml = try {
            app.get(data, referer = mainUrl).text
        } catch (e: Exception) {
            return false
        }
        val setupUrl = Regex("""setupJWPlayer\(\s*"([^"]+)"""").find(playerHtml)?.groupValues?.get(1)?.trim()
        val scraped = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(playerHtml).map { it.value }.toList().distinct()
        val candidates = listOfNotNull(setupUrl?.takeIf { it.startsWith("http") }) + scraped
        if (candidates.isEmpty()) return false

        val referer = data
        var emitted = false
        for (streamUrl in candidates) {
            try {
                if (streamUrl.contains(".mp4")) {
                    callback.invoke(
                        newExtractorLink(source = name, name = "$name - مباشر", url = streamUrl) {
                            this.referer = referer
                            this.quality = extractQualityInt(streamUrl)
                        }
                    )
                    emitted = true
                    continue
                }
                val master = try {
                    app.get(streamUrl, headers = mapOf("Referer" to referer)).text
                } catch (_: Exception) { null }
                if (master.isNullOrBlank() || !master.contains("#EXT-X-STREAM-INF")) {
                    callback.invoke(
                        newExtractorLink(source = name, name = "$name - تلقائي", url = streamUrl) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    emitted = true
                    continue
                }
                val basePath = streamUrl.substringBeforeLast("/")
                for (m in Regex("""#EXT-X-STREAM-INF:([^\n]+)\n([^\n#][^\n]*)""").findAll(master)) {
                    val attrs = m.groupValues[1]
                    var variantUri = m.groupValues[2].trim()
                    if (variantUri.isBlank()) continue
                    if (!variantUri.startsWith("http")) variantUri = "$basePath/$variantUri"
                    val res = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.get(1)
                    val qInt = res?.toIntOrNull() ?: extractQualityInt(attrs)
                    val qLabel = if (res != null) "${res}p" else "جودة عالية"
                    callback.invoke(
                        newExtractorLink(source = name, name = "$name - $qLabel", url = variantUri) {
                            this.referer = referer
                            this.quality = qInt
                        }
                    )
                }
                callback.invoke(
                    newExtractorLink(source = name, name = "$name - تلقائي", url = streamUrl) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted = true
            } catch (_: Exception) { }
        }
        return emitted
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FeedRoot(
        @JsonProperty("feed") val feed: Feed?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Feed(
        @JsonProperty("entry") val entries: List<FeedEntry>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FeedEntry(
        @JsonProperty("title") val title: FeedText?,
        @JsonProperty("link") val links: List<FeedLink>?,
        @JsonProperty("media\$thumbnail") val thumbnail: FeedThumb?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FeedText(
        @JsonProperty("\$t") val text: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FeedLink(
        @JsonProperty("rel") val rel: String?,
        @JsonProperty("href") val href: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FeedThumb(
        @JsonProperty("url") val url: String?
    )
}
