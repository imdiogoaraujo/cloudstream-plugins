package com.doramogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DoramogoProvider : MainAPI() {

    override var mainUrl = "https://www.doramogo.net"
    override var name = "Doramogo"
    override val hasMainPage = true
    override var lang = "pt"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/episodios",             "Episódios Recentes"),
        Pair("$mainUrl/dorama",                "Todos os Doramas"),
        Pair("$mainUrl/genero/dorama-drama",   "Drama"),
        Pair("$mainUrl/genero/dorama-romance", "Romance"),
        Pair("$mainUrl/genero/dorama-comedia", "Comédia"),
        Pair("$mainUrl/genero/dorama-acao",    "Ação"),
        Pair("$mainUrl/genero/dorama-fantasia","Fantasia"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document
        val items = document.select("article, div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?q=${query.encodeUrl()}"
        val document = app.get(url).document
        return document.select("article, div.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val titleEl = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(titleEl.attr("href"))
        val title = this.selectFirst("h3, h2, .title, .nome")?.text()
            ?: titleEl.attr("title").ifBlank { return null }
        val poster = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .title_dorama, .entry-title")?.text() ?: "Sem título"
        val poster = document.selectFirst("div.poster img, img.poster, img.capa")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot = document.selectFirst("div.sinopse, div.synopsis, .description, p.desc")?.text()
        val year = document.selectFirst(".ano, .year, span[class*=year]")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("a[href*='/genero/']").map { it.text() }.filter { it.isNotBlank() }
        val rating = document.selectFirst(".nota, .rating, span[class*=imdb]")?.text()?.toRatingInt()

        val episodes = document.select("a[href*='/episodio/'], a[href*='/ep-'], ul.episodios li a")
            .mapNotNull { epEl ->
                val epUrl = fixUrl(epEl.attr("href"))
                if (epUrl.isBlank()) return@mapNotNull null
                val epText = epEl.text()
                val seasonNum = Regex("""[Tt](\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("""[Ee][Pp]?\.?\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(epUrl) {
                    this.name = epText.ifBlank { null }
                    this.season = seasonNum
                    this.episode = epNum
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.rating = rating
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val iframes = document.select("iframe[src], iframe[data-src]")
            .mapNotNull { it.attr("src").ifBlank { it.attr("data-src") } }
            .filter { it.isNotBlank() }

        val directLinks = document.select("source[src], a[href$='.mp4']")
            .mapNotNull { it.attr("src").ifBlank { it.attr("href") } }
            .filter { it.isNotBlank() }

        iframes.forEach { iframeUrl ->
            loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
        }

        directLinks.forEach { videoUrl ->
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = fixUrl(videoUrl),
                    referer = data,
                    quality = -1,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }

        return iframes.isNotEmpty() || directLinks.isNotEmpty()
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
