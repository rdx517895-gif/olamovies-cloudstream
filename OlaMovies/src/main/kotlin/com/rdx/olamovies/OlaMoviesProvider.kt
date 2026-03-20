package com.rdx.olamovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OlaMoviesProvider : MainAPI() {

    override var mainUrl        = "https://n1.olamovies.info"
    override var name           = "OlaMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang           = "en"
    override val hasMainPage    = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/page/"                              to "Latest Uploads",
        "$mainUrl/category/movies/page/"              to "Movies",
        "$mainUrl/category/tv-series/page/"           to "TV Series",
        "$mainUrl/category/movies/bollywood/page/"    to "Bollywood",
        "$mainUrl/category/movies/hollywood/page/"    to "Hollywood",
        "$mainUrl/category/movies/south-indian/page/" to "South Indian"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val doc = app.get(
            url, timeout = 120,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive"
            )
        ).document
        val items = doc.parsePostList()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${query.encodeUri()}", timeout = 120,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ).document
        return doc.parsePostList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 120, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title  = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("div.entry-content img, img.wp-post-image")?.let {
            it.attr("abs:src").ifBlank { it.attr("abs:data-src") }
        }
        val plot   = doc.selectFirst("div.entry-content p")?.text()?.trim()
        val tags   = doc.select("a[rel=tag]").map { it.text() }
        val year   = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val isSeries = tags.any { it.lowercase().contains("season") }
                    || title.lowercase().contains("season")
                    || title.contains(Regex("""S\d{2}"""))
                    || url.contains("tv-series")
                    || url.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, doc.parseEpisodes(url)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 120, headers = mapOf("User-Agent" to USER_AGENT)).document
        doc.extractAndCallbackLinks(callback)
        return true
    }

    private fun Document.parsePostList(): List<SearchResponse> {
        return select("article.gridlove-post").mapNotNull { el ->
            val titleEl = el.selectFirst("h2.entry-title a, h3.entry-title a") ?: return@mapNotNull null
            val href    = titleEl.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title   = titleEl.text().trim().ifBlank { return@mapNotNull null }
            val poster  = el.selectFirst("div.entry-image img")?.attr("abs:src")
            val cats    = el.select("div.entry-category a").map { it.text().lowercase() }
            val isSeries = title.lowercase().contains("season") ||
                           title.contains(Regex("""S\d{2}""")) ||
                           cats.any { it.contains("series") || it.contains("shows") }
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
        val allLinks = select("div.entry-content a[href]")
            .filter { el ->
                val href = el.attr("abs:href")
                href.isNotBlank() &&
                !href.contains("olamovies.info/category") &&
                !href.contains("olamovies.info/tag") &&
                !href.contains("olamovies.info/#") &&
                !href.contains("telegram") &&
                !href.contains("javascript")
            }

        for (el in allLinks) {
            val href    = el.attr("abs:href").trim()
            val label   = el.text().trim()
            val quality = resolveQuality(label, el)
            try {
                val finalUrl = resolveAllRedirects(href) ?: continue
                when {
                    finalUrl.contains("drive.google.com") -> {
                        val fileId    = finalUrl.extractGDriveId() ?: continue
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
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to mainUrl
                                )
                            }
                        )
                    }
                    finalUrl.endsWith(".mkv") ||
                    finalUrl.endsWith(".mp4") ||
                    finalUrl.endsWith(".avi") -> {
                        callback(
                            newExtractorLink(
                                source = name,
                                name   = "$name ${qualityLabel(quality, label)}",
                                url    = finalUrl,
                                type   = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                            }
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun resolveAllRedirects(url: String, depth: Int = 0): String? {
        if (depth > 15) return null
        if (url.isBlank()) return null
        if (url.contains("drive.google.com")) return url

        return try {
            when {
                url.contains("links.ol-am.top") ||
                url.contains("anylinks.site") ||
                url.contains("anylinks.in") -> {
                    bypassOlaMoviesLink(url)
                }
                url.contains("tpi.li") ||
                url.contains("aryx.xyz") -> {
                    bypassAryx(url)
                }
                else -> {
                    val resp = app.get(
                        url, timeout = 30,
                        allowRedirects = false,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
                    )
                    val location = resp.headers["location"]
                    when {
                        location == null -> {
                            resp.document
                                .selectFirst("a[href*=drive.google.com]")
                                ?.attr("abs:href") ?: url
                        }
                        location.contains("drive.google.com") -> location
                        else -> resolveAllRedirects(location, depth + 1)
                    }
                }
            }
        } catch (_: Exception) { null }
    }

    private suspend fun bypassOlaMoviesLink(url: String): String? {
        return try {
            val slug = url.substringAfterLast("/")

            // Step 1 — Get reCaptcha anchor token
            val anchorUrl = "https://www.google.com/recaptcha/api2/anchor?" +
                "ar=1&k=6Lcr1ncUAAAAAH3cghg6cOTPGARa8adOf-y9zv2x" +
                "&co=aHR0cHM6Ly9vdW8uaW86NDQz" +
                "&hl=en&v=1B_yv3CBEV10KtI2HJ6eEXhJ" +
                "&size=invisible&cb=4xnsug1vufyr"

            val anchorResp = app.get(
                anchorUrl, timeout = 30,
                headers = mapOf("User-Agent" to USER_AGENT)
            )

            val token = Regex("""id="recaptcha-token" value="([^"]+)"""")
                .find(anchorResp.text)?.groupValues?.get(1) ?: ""

            // Step 2 — Get reCaptcha token
            val recaptchaResp = app.post(
                "https://www.google.com/recaptcha/api2/reload?k=6Lcr1ncUAAAAAH3cghg6cOTPGARa8adOf-y9zv2x",
                timeout = 30,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf(
                    "v"      to "1B_yv3CBEV10KtI2HJ6eEXhJ",
                    "reason" to "q",
                    "c"      to token,
                    "k"      to "6Lcr1ncUAAAAAH3cghg6cOTPGARa8adOf-y9zv2x",
                    "co"     to "aHR0cHM6Ly9vdW8uaW86NDQz"
                )
            )

            val captchaToken = Regex(""""rresp","([^"]+)"""")
                .find(recaptchaResp.text)?.groupValues?.get(1) ?: ""

            // Step 3 — Call OlaMovies download API
            val resp = app.get(
                "https://olamovies.ink/download/",
                timeout = 60,
                headers = mapOf(
                    "User-Agent"       to USER_AGENT,
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "Accept-Language"  to "en-US,en;q=0.5",
                    "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to url
                ),
                params = mapOf(
                    "link"  to slug,
                    "token" to captchaToken
                )
            )

            resp.document.selectFirst("a[href*=drive.google.com]")?.attr("href")
                ?: resp.document.selectFirst("a[href]")?.attr("href")

        } catch (_: Exception) { null }
    }

    private suspend fun bypassAryx(url: String): String? {
        return try {
            val resp = app.get(
                url, timeout = 30,
                allowRedirects = false,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
            )
            val location = resp.headers["location"]
            when {
                location != null && location.contains("drive.google.com") -> location
                location != null -> resolveAllRedirects(location, 0)
                else -> resp.document
                    .selectFirst("a[href*=drive.google.com]")
                    ?.attr("abs:href")
            }
        } catch (_: Exception) { null }
    }

    private suspend fun resolveGDriveLink(fileId: String): String? {
        val baseUrl = "https://drive.google.com/uc?export=download&id=$fileId"
        return try {
            val resp = app.get(
                baseUrl, timeout = 120,
                headers = mapOf("User-Agent" to USER_AGENT),
                allowRedirects = false
            )
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
