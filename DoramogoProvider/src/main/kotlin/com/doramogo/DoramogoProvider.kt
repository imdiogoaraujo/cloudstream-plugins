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
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
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

        val title = document.selectFirst("h1.font-bold")?.text() ?: "Sem título"

        val poster = document.selectFirst("div.thumbnail img")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }

        val backgroundStyle = document.selectFirst("div#info_drama div[style*=background]")
            ?.attr("style") ?: ""
        val background = Regex("""url\(['"]?(.*?)['"]?\)""").find(backgroundStyle)
            ?.groupValues?.get(1)

        val plot = document.selectFirst("p#sinopse-text")?.text()

        val infoText = document.selectFirst("p.text-opacity-75")?.text() ?: ""
        val year = Regex("""(\d{4})""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("p.gens a").map { it.text() }.filter { it.isNotBlank() }

        val audio = document.select("div.casts div").firstOrNull {
            it.text().contains("Áudio")
        }?.text()?.replace("Áudio:", "")?.trim()

        val episodes = document.select("a.dorama-one-episode-item").mapNotNull { epEl ->
            val epUrl = epEl.attr("href").ifBlank { return@mapNotNull null }
            val epText = epEl.selectFirst("span.episode-title")?.text()?.trim()
                ?: epEl.text().trim()
            val seasonNum = Regex("""temporada[- ]?(\d+)""", RegexOption.IGNORE_CASE)
                .find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epNum = Regex("""episodio[- ]?0*(\d+)""", RegexOption.IGNORE_CASE)
                .find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epUrl) {
                this.name = epText
                this.season = seasonNum
                this.episode = epNum
            }
        }

        val plotFull = if (audio != null) "$plot\n\nÁudio: $audio" else plot

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plotFull
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
        // URL do episódio ex: /series/papel-de-rainha/temporada-1/episodio-01
        // Extrai o slug, temporada e episódio da URL
        val regex = Regex("""/series/([^/]+)/temporada-(\d+)/episodio-0*(\d+)""")
        val match = regex.find(data) ?: return false

        val slug = match.groupValues[1]
        val tempNum = match.groupValues[2].padStart(2, '0')
        val epNum = match.groupValues[3].padStart(2, '0')
        val inicial = slug.first().uppercaseChar()

        // Busca o base URL da página do episódio
        val document = app.get(data).document
        val pageSource = document.html()
        val baseUrl = Regex("""base:\s*"([^"]+)"""").find(pageSource)?.groupValues?.get(1)
            ?: "https://forks-doramas.ondemax.shop"

        val m3u8Url = "$baseUrl/$inicial/$slug/${tempNum}-temporada/$epNum/stream.m3u8"

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {		
		this.referer = data
                this.quality = Qualities.Unknown.value
                this.headers = map0f(
		    "Origin" to mainUrl,
		    "Referer" to data,
		    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebkit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
		)
            }
        )
        return true
    }
}
