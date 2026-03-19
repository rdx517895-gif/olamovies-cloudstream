package com.rdx.olamovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OlaMoviesProvider : MainAPI() {

    override var mainUrl        = "https://n1.olamovies.info"
    override var name           = "OlaMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang           = "hi"
    override val hasMainPage    = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/category/2160p/page/"  to "2160p 4K",
        "$mainUrl/category/1080p/page/"  to "1080p Full HD",
        "$mainUrl/category/720p/page/"   to "720p HD",
        "$mainUrl/category/bluray/page/" to "BluRay",
        "$mainUrl/page/"                 to "Latest Uploads"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val doc = app.get(url).document
        val items = doc.parsePostList()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.encodeUri()}").document
        return doc.parsePostList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url).document
        val title  = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("div.entry-content img, .post-thumbnail img, img.wp-post-image")?.attr("abs:src")
        val plot   = doc.selectFirst("div.entry-content p")?.text()?.trim()
        val tags   = doc.select("a[rel=tag]").map { it.text() }
        val year   = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val isSeries = tags.any { it.lowercase().contains("season") }
                    || title.lowercase().contains("season")
                    || title.contains(Regex("""S\d{2}"""))

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, doc.parseEpisodes(url)) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.extractAndCallbackLinks(callback)
        return true
    }

    private fun Document.parsePostList(): List<SearchResponse> {
        return select("article, div.post-item, div.result-item").mapNotNull { el ->
            val a      = el.selectFirst("h2 a, h3 a, .entry-title a, a.lnk-blk") ?: return@mapNotNull null
            val href   = a.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title  = a.text().trim().ifBlank { el.selectFirst("h2,h3")?.text()?.trim() } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("abs:src")
            val isSeries = title.lowercase().contains("season") || title.contains(Regex("""S\d{2}"""))
            if (isSeries)
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            else
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun Document.parseEpisodes(pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val headings = select("h3, h4, strong").filter { el ->
            el.text().matches(Regex("""(?i)(episode|ep\.?)\s*\d+.*"""))
        }
        if (headings.isEmpty()) {
            episodes.add(newEpisode(pageUrl) {
                this.name = "Full Episode"; this.episode = 1; this.season = 1
            })
        } else {
            headings.forEachIndexed { index, heading ->
                val epNum = Regex("""\d+""").find(heading.text())?.value?.toIntOrNull() ?: (index + 1)
                val epLinks = heading.nextElementSiblings()
                    .takeWhile { it.tagName() != heading.tagName() }
                    .flatMap { it.select("a[href*=drive.google.com], a[href*=gdrive]") }
                    .map { it.attr("abs:href") }
                val dataUrl = epLinks.firstOrNull() ?: pageUrl
                episodes.add(newEpisode(dataUrl) {
                    this.name = heading.text().trim(); this.episode = epNum; this.season = 1
                })
            }
        }
        return episodes
    }

    private suspend fun Document.extractAndCallbackLinks(callback: (ExtractorLink) -> Unit) {
        val allLinks = select(
            "a[href*=drive.google.com], a[href*=gdrive], " +
            "a[href*=ol-am.top], a[href*=olamovies], " +
            "div.entry-content a[href]"
        )
        for (el in allLinks) {
            val href    = el.attr("abs:href").trim()
            val label   = el.text().trim()
            val quality = resolveQuality(label, el)
            when {
                href.contains("drive.google.com") -> {
                    val fileId    = href.extractGDriveId() ?: continue
                    val directUrl = resolveGDriveLink(fileId) ?: continue
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name ${qualityLabel(quality, label)}",
                            url    = directUrl,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
                        }
                    )
                }
                href.contains("ol-am.top") || href.contains("olamovies") -> {
                    try {
                        val location = app.get(href, allowRedirects = false).headers["location"] ?: continue
                        if (location.contains("drive.google.com")) {
                            val fileId    = location.extractGDriveId() ?: continue
                            val directUrl = resolveGDriveLink(fileId) ?: continue
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name   = "$name ${qualityLabel(quality, label)}",
                                    url    = directUrl,
                                    type   = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = quality
                                }
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun resolveGDriveLink(fileId: String): String? {
        val baseUrl = "https://drive.google.com/uc?export=download&id=$fileId"
        return try {
            val resp    = app.get(baseUrl, headers = mapOf("User-Agent" to USER_AGENT), allowRedirects = false)
            val confirm = resp.document
                .selectFirst("a#uc-download-link, form#downloadForm, a[href*=confirm]")
                ?.attr("href")
            when {
                confirm != null && confirm.startsWith("https") -> confirm
                confirm != null -> "https://drive.google.com$confirm"
                resp.code in 200..299 -> baseUrl
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun String.extractGDriveId() =
        Regex("""(?:/file/d/|id=|/open\?id=)([a-zA-Z0-9_-]{25,})""")
            .find(this)?.groupValues?.get(1)

    private fun resolveQuality(label: String, el: Element): Int {
        val text = (label + " " +
            (el.parents().firstOrNull { it.tagName() in listOf("div","p","li") }?.text() ?: "")
        ).lowercase()
        return when {
            text.contains("2160") || text.contains("4k") -> 2160
            text.contains("1080") -> 1080
            text.contains("720")  -> 720
            text.contains("480")  -> 480
            text.contains("360")  -> 360
            else                  -> -1
        }
    }

    private fun qualityLabel(quality: Int, fallback: String): String = when (quality) {
        2160 -> "2160p 4K"
        1080 -> "1080p"
        720  -> "720p"
        480  -> "480p"
        360  -> "360p"
        else -> fallback.take(40).ifBlank { "Unknown" }
    }

    private fun String.encodeUri() = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; SM-G973F) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
