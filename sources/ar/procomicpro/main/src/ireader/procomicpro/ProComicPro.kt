package ireader.procomicpro

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import ireader.core.log.Log
import ireader.core.source.Dependencies
import ireader.core.source.SourceFactory
import ireader.core.source.findInstance
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Command
import ireader.core.source.model.CommandList
import ireader.core.source.model.Filter
import ireader.core.source.model.FilterList
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangaInfo.Companion.ONGOING
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.Page
import ireader.core.source.model.Text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import tachiyomix.annotations.AutoSourceId
import tachiyomix.annotations.Extension
import tachiyomix.annotations.GenerateTests
import tachiyomix.annotations.TestExpectations
import tachiyomix.annotations.TestFixture

@Extension
@AutoSourceId(seed = "ProComicPro")
@GenerateTests(
    unitTests = true,
    integrationTests = false,
    searchQuery = "مانهوا",
    minSearchResults = 1
)
@TestFixture(
    novelUrl = "https://procomic.pro/ar/series/ghostly-resurgence-i-can-transform-into-a-great-demon-93",
    chapterUrl = "https://procomic.pro/ar/series/ghostly-resurgence-i-can-transform-into-a-great-demon-93",
    expectedTitle = "Ghostly Resurgence: I Can Transform into a Great Demon",
    expectedAuthor = ""
)
@TestExpectations(
    minLatestNovels = 5,
    minChapters = 10,
    supportsPagination = true,
    requiresLogin = false
)
abstract class ProComicPro(deps: Dependencies) : SourceFactory(deps = deps) {

    override val lang: String get() = "ar"

    override val baseUrl: String get() = "https://procomic.pro"

    override val id: Long get() = ProComicProSourceId.ID

    override val name: String get() = "ProComic Pro"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun getFilters(): FilterList = listOf(
        Filter.Title(),
        Filter.Sort("فرز حسب", arrayOf("الأحدث", "الأكثر شعبية"))
    )

    override fun getCommands(): CommandList = listOf(
        Command.Detail.Fetch(),
        Command.Chapter.Fetch(),
        Command.Content.Fetch(),
    )

    override val exploreFetchers: List<BaseExploreFetcher>
        get() = listOf(
            BaseExploreFetcher(
                "الأحدث",
                endpoint = "/ar/series?sort=latest",
                selector = "a.group[href*='/ar/series/']",
                nameSelector = "h3",
                coverSelector = "img[alt]",
                coverAtt = "src",
                addBaseurlToCoverLink = true,
                linkSelector = "a",
                linkAtt = "href",
                addBaseUrlToLink = true
            ),
            BaseExploreFetcher(
                "الأكثر شعبية",
                endpoint = "/ar/series?sort=popular",
                selector = "a.group[href*='/ar/series/']",
                nameSelector = "h3",
                coverSelector = "img[alt]",
                coverAtt = "src",
                addBaseurlToCoverLink = true,
                linkSelector = "a",
                linkAtt = "href",
                addBaseUrlToLink = true
            ),
            BaseExploreFetcher(
                "Search",
                endpoint = "/ar/series?search={query}",
                selector = "a.group[href*='/ar/series/']",
                nameSelector = "h3",
                coverSelector = "img[alt]",
                coverAtt = "src",
                addBaseurlToCoverLink = true,
                linkSelector = "a",
                linkAtt = "href",
                addBaseUrlToLink = true,
                type = SourceFactory.Type.Search
            )
        )

    override val detailFetcher: Detail
        get() = SourceFactory.Detail(
            nameSelector = "h1",
            coverSelector = "img[alt]",
            coverAtt = "src",
            addBaseurlToCoverLink = true,
            authorBookSelector = "p:contains(بـ), span:contains(مؤلف), .author",
            onAuthor = { it.trim() },
            descriptionSelector = ".description, .excerpt, p.text-ink/80",
            statusSelector = "dd, .status, [class*=status]",
            onStatus = { ONGOING },
            categorySelector = ".genre, .tag, [class*=genre], [class*=tag]"
        )

    override val chapterFetcher: Chapters
        get() = SourceFactory.Chapters(
            selector = "a[href*='/ar/series/'][href*='chapter'], a[href*='/ar/series/'][href*='فصل']",
            linkSelector = "a",
            linkAtt = "href",
            addBaseUrlToLink = true,
            nameSelector = "span.text-ink, h3, div",
            numberSelector = "span.font-mono",
            reverseChapterList = false
        )

    override val contentFetcher: Content
        get() = SourceFactory.Content(
            pageContentSelector = ".chapter-body p[data-tts], .reading-content p, .epcontent p"
        )

    // ══════════════════════════════════════════════════════════════════
    // CUSTOM SEARCH / LISTINGS (HTML cards)
    // ══════════════════════════════════════════════════════════════════

    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        val query = filters.findInstance<Filter.Title>()?.value?.takeIf { it.isNotBlank() }
        return if (query != null) {
            searchHtml(query, page)
        } else {
            val sortIdx = filters.findInstance<Filter.Sort>()?.value?.index
            val sort = when (sortIdx) { 1 -> "popular"; else -> "latest" }
            exploreHtml(sort, page)
        }
    }

    private suspend fun exploreHtml(sort: String, page: Int): MangasPageInfo {
        return try {
            val url = "$baseUrl/ar/series?sort=$sort&page=$page"
            val html = client.get(requestBuilder(url)).bodyAsText()
            val doc: Document = Ksoup.parse(html)
            val novels = doc.select("a.group[href*='/ar/series/']").mapNotNull { a ->
                val title = a.selectFirst("h3")?.text()?.trim()
                    ?: a.text().trim()
                if (title.isBlank()) return@mapNotNull null
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val coverEl = a.selectFirst("img[alt]")
                val cover = coverEl?.attr("src")?.takeIf { it.isNotBlank() } ?: ""
                MangaInfo(
                    key = if (href.startsWith("http")) href else "$baseUrl$href",
                    title = title,
                    cover = cover
                )
            }.distinctBy { it.key }
            MangasPageInfo(novels, hasNextPage = novels.isNotEmpty())
        } catch (e: Exception) {
            Log.error { "ProComicPro exploreHtml error: ${e.message}" }
            MangasPageInfo(emptyList(), false)
        }
    }

    private suspend fun searchHtml(query: String, page: Int): MangasPageInfo {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/ar/series?search=$encoded&page=$page"
            val html = client.get(requestBuilder(url)).bodyAsText()
            val doc: Document = Ksoup.parse(html)
            val novels = doc.select("a.group[href*='/ar/series/']").mapNotNull { a ->
                val title = a.selectFirst("h3")?.text()?.trim()
                    ?: a.text().trim()
                if (title.isBlank()) return@mapNotNull null
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val coverEl = a.selectFirst("img[alt]")
                val cover = coverEl?.attr("src")?.takeIf { it.isNotBlank() } ?: ""
                MangaInfo(
                    key = if (href.startsWith("http")) href else "$baseUrl$href",
                    title = title,
                    cover = cover
                )
            }.distinctBy { it.key }
            MangasPageInfo(novels, hasNextPage = novels.isNotEmpty())
        } catch (e: Exception) {
            Log.error { "ProComicPro searchHtml error: ${e.message}" }
            MangasPageInfo(emptyList(), false)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CUSTOM DETAIL PARSING
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo {
        return try {
            val html = client.get(requestBuilder(manga.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)

            val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title

            val author = doc.select("p, span, div").firstOrNull {
                it.text().contains("مؤلف") || it.text().contains("بـ") || it.text().contains("Author")
            }?.text()?.trim()?.ifBlank { manga.author } ?: manga.author

            val description = doc.selectFirst(".description, .excerpt, p.text-ink/80")
                ?.text()?.trim()?.ifBlank { manga.description } ?: manga.description

            val coverEl = doc.selectFirst("img[alt]")
            val cover = coverEl?.attr("src")?.takeIf { it.isNotBlank() } ?: manga.cover

            val genres = doc.select(".genre, .tag, [class*=genre], [class*=tag]")
                .map { it.text().trim() }.filter { it.isNotBlank() }

            manga.copy(
                title = title,
                author = author,
                description = description,
                genres = genres,
                cover = cover
            )
        } catch (e: Exception) {
            Log.error { "ProComicPro getMangaDetails error: ${e.message}" }
            manga
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CUSTOM CHAPTER LIST via /api/chapters?seriesId={id}
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*> >): List<ChapterInfo> {
        return try {
            val seriesId = manga.key.substringAfterLast("-").takeWhile { it.isDigit() }.toIntOrNull()
                ?: return emptyList()
            val url = "$baseUrl/api/chapters?seriesId=$seriesId"
            val html = client.get(requestBuilder(url)).bodyAsText()
            val obj = json.decodeFromString<JsonElement>(html).jsonObject
            val arr = obj["chapters"]?.jsonArray ?: emptyList()
            arr.mapNotNull { element ->
                val o = element.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val number = o["chapter_number"]?.jsonPrimitive?.contentOrNull ?: ""
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val name = if (title.isNotBlank()) "$number — $title" else "الفصل $number"
                ChapterInfo(
                    name = name,
                    key = "$baseUrl/api/chapters/$id"
                )
            }.sortedWith(compareBy { it.name })
        } catch (e: Exception) {
            Log.error { "ProComicPro getChapterList error: ${e.message}" }
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CUSTOM CONTENT FETCHING via chapter API
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun getContents(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        return try {
            val chapterId = chapter.key.substringAfterLast("/").toLongOrNull()
                ?: return emptyList()
            val url = "$baseUrl/api/chapters/$chapterId"
            val html = client.get(requestBuilder(url)).bodyAsText()
            val obj = json.decodeFromString<JsonElement>(html).jsonObject
            val pages = obj["pages"]?.jsonArray?.mapNotNull { element ->
                val o = element.jsonObject
                val url = o["url"]?.jsonPrimitive?.contentOrNull
                    ?: o["image"]?.jsonPrimitive?.contentOrNull
                    ?: o["src"]?.jsonPrimitive?.contentOrNull
                if (url.isNullOrBlank()) null else ImageUrl(url)
            } ?: emptyList()
            if (pages.isNotEmpty()) pages else {
                // Fallback: try HTML content page
                fetchHtmlContent(chapter)
            }
        } catch (e: Exception) {
            Log.error { "ProComicPro getContents error: ${e.message}" }
            fetchHtmlContent(chapter)
        }
    }

    private suspend fun fetchHtmlContent(chapter: ChapterInfo): List<Page> {
        return try {
            val html = client.get(requestBuilder(chapter.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)
            val content = doc.selectFirst(".chapter-body, .reading-content, .epcontent") ?: return emptyList()
            val pages = mutableListOf<Page>()
            content.select("p[data-tts], p").forEach { p ->
                val text = p.text().trim()
                if (text.isNotBlank()) pages.add(Text(text))
            }
            content.select("img").forEach { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }?: return@forEach
                if (src.isNotBlank()) pages.add(ImageUrl(src))
            }
            if (pages.isEmpty()) listOf(Text("")) else pages
        } catch (e: Exception) {
            Log.error { "ProComicPro fetchHtmlContent error: ${e.message}" }
            emptyList()
        }
    }
}
