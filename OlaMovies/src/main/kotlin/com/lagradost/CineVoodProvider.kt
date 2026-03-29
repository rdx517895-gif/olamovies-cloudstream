package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CineVoodProvider : MainAPI() {
    override var mainUrl = "https://1cinevood.watch"
    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies" to "Bollywood Movies",
        "category/south-indian-movies" to "South Indian Movies",
        "category/hollywood-movies" to "Hollywood Movies",
        "category/web-series" to "Web Series",
        "category/dual-audio-movies" to "Dual Audio Movies",
        "category/animated-movies" to "Animated Movies",
        "category/punjabi-movies" to "Punjabi Movies",
        "category/gujarati-movies" to "Gujarati Movies",
        "category/korean-drama" to "Korean Drama",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            "$mainUrl/page/$page/"
        } else {
            "$mainUrl/${request.data}/page/$page/"
        }
        val document = app.get(url).document
        val home = document.select("article.post-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.post-title a")?.text()?.trim() ?: return null
        val href = this.selectFirst("h2.post-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("div.post-thumb img")?.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.post-title, h2.post-title")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst("div.post-thumb img, div.entry-content img")?.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        }
        val description = document.selectFirst("div.entry-content p")?.text()?.trim()
        val year = Regex("(\\d{4})").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val actors = document.select("div.entry-content")
            .text()
            .let { text ->
                Regex("(?:Stars?|Cast|Starring)\\s*:\\s*([^\\n]+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)
                    ?.split(",")?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.map { ActorData(Actor(it)) }
            } ?: emptyList()

        // Check if this is a series (has multiple episodes/seasons)
        val contentDiv = document.selectFirst("div.entry-content")
        val links = contentDiv?.select("a[href]")
            ?.filter { it.attr("href").contains(Regex("(oxxfile|hubcloud|gdrive|filepress)", RegexOption.IGNORE_CASE)) }
            ?: emptyList()

        val isSeries = links.size > 2 ||
                title.contains(Regex("(Season|S\\d|Episode|Complete)", RegexOption.IGNORE_CASE))

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            var seasonNum = 1
            var episodeNum = 1

            links.forEach { link ->
                val linkText = link.text().trim()
                val linkHref = link.attr("href")

                // Try to extract season/episode info from link text
                val seMatch = Regex("S(\\d+)\\s*E?(\\d+)", RegexOption.IGNORE_CASE).find(linkText)
                val epMatch = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(linkText)

                val (s, e) = when {
                    seMatch != null -> Pair(
                        seMatch.groupValues[1].toIntOrNull() ?: seasonNum,
                        seMatch.groupValues[2].toIntOrNull() ?: episodeNum
                    )
                    epMatch != null -> Pair(seasonNum, epMatch.groupValues[1].toIntOrNull() ?: episodeNum)
                    else -> Pair(seasonNum, episodeNum)
                }
                seasonNum = s
                episodeNum = e + 1

                episodes.add(
                    Episode(
                        data = linkHref,
                        name = linkText.ifEmpty { "Episode $e" },
                        season = s,
                        episode = e,
                    )
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                addActors(actors)
            }
        } else {
            val downloadLinks = links.map { it.attr("href") }
            val dataStr = downloadLinks.joinToString(",")

            newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split(",").filter { it.startsWith("http") }

        links.forEach { link ->
            try {
                loadExtractor(link, subtitleCallback, callback)
            } catch (e: Exception) {
                // Try direct link as fallback
                val quality = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                    .find(link)?.groupValues?.get(1)?.toIntOrNull()

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        referer = mainUrl,
                        quality = quality ?: Qualities.Unknown.value,
                    )
                )
            }
        }
        return links.isNotEmpty()
    }
}
