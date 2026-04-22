package com.doramogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.content.Context

@CloudstreamPlugin
class DoramogoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DoramogoProvider())
    }
}

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
        "$mainUrl/episodios"              to "Episódios Recentes",
        "$mainUrl/series"                 to "Todos os Doramas",
        "$mainUrl/genero/dorama-drama"    to "Drama",
        "$mainUrl/genero/dorama-romance"  to "Romance",
        "$mainUrl/genero/dorama-comedia"  to "Comédia",
        "$mainUrl/genero/dorama-acao"     to "Ação",
        "$mainUrl/genero/dorama-fantasia" to "Fantasia",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document
        val items = document.select("div.episode-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        return document.select("div.episode-card").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val anchor = this.selectFirst("a[href]") ?: return null
        val href = anchor.attr("href")
        if (href.isBlank()) return null
        val title = anchor.attr("title").ifBlank {
            this.selectFirst("div.episode-info, h3, h2")?.text()
        } ?: return null
        val poster = this.selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .title_dorama, .entry-title")?.text() ?: "Sem título"
        val poster = document.selectFirst("div.poster img, img.poster, img.capa")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
        val plot = document.selectFirst("div.sinopse, div.synopsis, .description, p.desc")?.text()
        val year = document.selectFirst(".ano, .year")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("a[href*='/genero/']").map { it.text() }.filter { it.isNotBlank() }

        val episodes = document.select("a[href*='/episodio/'], a[href*='/ep-'], ul.episodios li a")
            .mapNotNull { epEl ->
                val epUrl = epEl.attr("href").ifBlank { return@mapNotNull null }
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
        iframes.forEach { iframeUrl ->
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }
        return iframes.isNotEmpty()
    }
}
