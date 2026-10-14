package com.egydead

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

/**
 * Resolver for the rotating MegaMax mirror hosts used by Egydead
 * (`{host}/iframe/{fileId}`, `{host}/download/{fileId}`).
 *
 * The pages are Laravel/Inertia Vue shells; the real file hosts come from an
 * Inertia partial reload:
 *   GET  /download/{fileId}            -> XSRF cookie + attachments[{hashId,label}]
 *   POST /download/{fileId} {attach}   -> props.download.data.mirrors[{driver,link}]
 * with X-XSRF-TOKEN + X-Inertia-Partial-* headers. Mirror links are handed to
 * CloudStream's built-in extractors (uqload, doodstream, voe, mixdrop ...).
 */
object MegaMaxMirror {
    private val COMPONENT = Regex(""""component"\s*:\s*"files/mirror/(video|download)"""")
    private val VERSION = Regex(""""version"\s*:\s*"([^"]+)"""")
    private val ATTACHMENT = Regex(
        """\{\s*"hashId"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)""""
    )
    private val QUALITY_NUM = Regex("""(\d{3,4})""")

    fun looksLikeMirror(url: String): Boolean {
        return try {
            val u = java.net.URL(url)
            val path = u.path.trimEnd('/')
            (path.startsWith("/iframe/") || path.startsWith("/download/")) &&
                path.count { it == '/' } == 2 &&
                !u.host.contains("egydead")
        } catch (_: Exception) {
            false
        }
    }

    private fun qualityOf(label: String?): Int {
        return label?.let { QUALITY_NUM.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: Qualities.Unknown.value
    }

    suspend fun resolve(
        pageUrl: String,
        serverName: String?,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val host = try {
                java.net.URL(pageUrl).host
            } catch (_: Exception) {
                return false
            }
            val base = "https://$host"
            val fileId = pageUrl.trimEnd('/').substringAfterLast('/')
                .substringBefore('?').substringBefore('#')
            if (fileId.isBlank() || fileId.contains(".")) return false

            val dlRes = try {
                app.get("$base/download/$fileId", referer = pageUrl, timeout = 20)
            } catch (_: Exception) {
                return false
            }
            val dlHtml = dlRes.text
            if (!COMPONENT.containsMatchIn(dlHtml)) return false

            val version = VERSION.find(dlHtml)?.groupValues?.get(1).orEmpty()
            val cookies = dlRes.cookies
            val xsrf = cookies["XSRF-TOKEN"]?.let {
                try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (_: Exception) {
                    it
                }
            } ?: return false
            val cookieHeader = cookies.entries
                .filter { it.value.isNotBlank() }
                .joinToString("; ") { "${it.key}=${it.value}" }

            val attachments = ATTACHMENT.findAll(dlHtml).mapNotNull { m ->
                val id = m.groupValues[1].trim()
                val label = m.groupValues[2].trim()
                if (id.isBlank()) null else id to label
            }.distinctBy { it.first }.take(4).toList()
            if (attachments.isEmpty()) return false

            var found = false
            for ((attachId, label) in attachments) {
                val mirrors = postMirrors(base, fileId, attachId, version, xsrf, cookieHeader, pageUrl)
                val q = qualityOf(label)
                for ((driver, link) in mirrors) {
                    var url = link.trim()
                    if (url.startsWith("//")) url = "https:$url"
                    if (!url.startsWith("http")) continue
                    try {
                        loadExtractor(url, pageUrl, subtitleCallback) { ext ->
                            callback.invoke(
                                newExtractorLink(
                                    source = "EgyDead",
                                    name = "MegaMax ${driver.takeIf { it.isNotBlank() } ?: serverName.orEmpty()}".trim(),
                                    url = ext.url
                                ) {
                                    this.referer = ext.referer
                                    this.headers = ext.headers
                                    this.quality = if (q != Qualities.Unknown.value) q else ext.quality
                                    this.type = ext.type
                                }
                            )
                            found = true
                        }
                    } catch (_: Exception) { }
                }
            }
            return found
        } catch (_: Exception) {
            return false
        }
    }

    private suspend fun postMirrors(
        base: String,
        fileId: String,
        attachId: String,
        version: String,
        xsrf: String,
        cookieHeader: String,
        referer: String
    ): List<Pair<String, String>> {
        return try {
            val res = app.post(
                "$base/download/$fileId",
                data = mapOf("attach" to attachId),
                headers = mapOf(
                    "Accept" to "text/html, application/xhtml+xml",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to "files/mirror/download",
                    "X-Inertia-Partial-Data" to "download",
                    "X-XSRF-TOKEN" to xsrf,
                    "Cookie" to cookieHeader
                ),
                referer = referer,
                timeout = 20
            )
            parseMirrors(res.text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMirrors(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        Regex("""\{\s*"driver"\s*:\s*"([^"]*)"\s*,\s*"link"\s*:\s*"([^"]+)"""")
            .findAll(text).forEach { m ->
                val link = m.groupValues[2].trim()
                if (link.isNotBlank()) out.add(m.groupValues[1].trim() to link)
            }
        return out.distinctBy { it.second }
    }
}
