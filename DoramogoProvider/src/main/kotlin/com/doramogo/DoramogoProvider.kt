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

    override var mainUrl = "https://doramasonline.co"
    override var name = "Doramas Online"
    override val hasMainPage = true
    override var lang = "pt"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/series"                   to "Todos os Doramas",
        "$mainUrl/category/lancamentos"     to "Lançamentos",
        "$mainUrl/category/drama"           to "Drama",
        "$mainUrl/category/comedia"         to "Comédia",
        "$mainUrl/category/acao"            to "Ação",
        "$mainUrl/category/fantasia"        to "Fantasia",
        "$mainUrl/category/romance"         to "Romance",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document
        val items = document.select("article.movies, article.post").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        return document.select("article.movies, article.post").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val anchor = this.selectFirst("a.lnk-blk") ?: return null
        val href = anchor.attr("href").ifBlank { return null }
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val poster = this.selectFirst("img")?.attr("src")
        val year = this.selectFirst("span.year")?.text()?.toIntOrNull()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1.single-title")?.text() ?: "Sem título"

        val poster = document.selectFirst("div.post-thumbnail img, div.poster img, aside img")
            ?.attr("src")

        val plot = document.selectFirst("div.description, div.sinopsis, div.content p")?.text()

        val year = document.selectFirst("span.year, span.date")?.text()?.toIntOrNull()

        val tags = document.select("div.genres a, span.genres a").map { it.text() }.filter { it.isNotBlank() }

        // O site carrega episódios via JS, mas o iframe do player já está na página
        // Vamos criar um episódio único apontando para a própria URL da série
        val episodes = listOf(
            newEpisode(url) {
                this.name = title
                this.season = 1
                this.episode = 1
            }
        )

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

        val iframes = document.select("iframe[src]")
            .mapNotNull { it.attr("src").ifBlank { null } }
            .filter { it.isNotBlank() && !it.contains("youtube") }

        iframes.forEach { iframeUrl ->
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        return iframes.isNotEmpty()
    }
}
