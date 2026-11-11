package ireader.novltime

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
import ireader.core.source.model.MangaInfo.Companion.COMPLETED
import ireader.core.source.model.MangaInfo.Companion.ONGOING
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.Page
import ireader.core.source.model.Text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.parseToJsonElement
import tachiyomix.annotations.AutoSourceId
import tachiyomix.annotations.Extension
import tachiyomix.annotations.GenerateTests
import tachiyomix.annotations.TestExpectations
import tachiyomix.annotations.TestFixture

@Extension
@AutoSourceId(seed = "NovelTime")
@GenerateTests(
    unitTests = true,
    integrationTests = false,
    searchQuery = "رواية",
    minSearchResults = 1
)
@TestFixture(
    novelUrl = "https://novltime.com/ar/novel/test",
    chapterUrl = "https://novltime.com/ar/novel/test/chapter/chapter-1",
    expectedTitle = "test",
    expectedAuthor = ""
)
@TestExpectations(
    minLatestNovels = 5,
    minChapters = 10,
    supportsPagination = true,
    requiresLogin = false
)
abstract class NovelTime(deps: Dependencies) : SourceFactory(deps = deps) {

    override val lang: String get() = "ar"

    override val baseUrl: String get() = "https://novltime.com"

    override val id: Long get() = NovelTimeSourceId.ID

    override val name: String get() = "NovelTime"

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
                endpoint = "/ar/explore?sort=latest",
                selector = "a.group[href*='/ar/novel/']",
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
                endpoint = "/ar/explore?sort=popular",
                selector = "a.group[href*='/ar/novel/']",
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
                endpoint = "/ar/explore?q={query}",
                selector = "a.group[href*='/ar/novel/']",
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
            nameSelector = "h1.font-serif",
            coverSelector = "img[alt]",
            coverAtt = "src",
            addBaseurlToCoverLink = true,
            authorBookSelector = "p:contains(بقلم)",
            onAuthor = { it.removePrefix("بقلم").trim() },
            descriptionSelector = "p.text-ink\\/80",
            statusSelector = ".bg-sage-surface",
            onStatus = { ONGOING },
            categorySelector = ".bg-sage-surface"
        )

    override val chapterFetcher: Chapters
        get() = SourceFactory.Chapters(
            selector = "section#chapters ol li a",
            linkSelector = "a",
            linkAtt = "href",
            addBaseUrlToLink = true,
            nameSelector = "span.text-ink",
            numberSelector = "span.font-mono",
            reverseChapterList = false
        )

    override val contentFetcher: Content
        get() = SourceFactory.Content(
            pageContentSelector = ".chapter-body p[data-tts]"
        )

    // ══════════════════════════════════════════════════════════════════
    // CUSTOM DETAIL PARSING
    // ══════════════════════════════════════════════════════════════════

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo {
        return try {
            val html = client.get(requestBuilder(manga.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)

            val title = doc.selectFirst("h1.font-serif")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: manga.title

            val author = doc.select("p").firstOrNull { it.text().contains("بقلم") }
                ?.text()?.removePrefix("بقلم")?.trim().ifBlank { manga.author }

            val description = doc.selectFirst("p.text-ink/80")?.text()?.trim()
                ?: manga.description

            val coverEl = doc.selectFirst("img[alt]")
            val cover = coverEl?.attr("src")?.takeIf { it.isNotBlank() } ?: manga.cover

            val genres = doc.select(".bg-sage-surface")
                .map { it.text().trim() }.filter { it.isNotBlank() }

            manga.copy(
                title = title,
                author = author,
                description = description,
                genres = genres,
                cover = cover
            )
        } catch (e: Exception) {
            Log.error { "NovelTime getMangaDetails error: ${e.message}" }
            manga
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // CUSTOM CHAPTER LIST
    // ══════════════════════════════════════════════════════════════════

    override suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*> >): List<ChapterInfo> {
        return try {
            val html = client.get(requestBuilder(manga.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)
            doc.select("section#chapters ol li a").mapNotNull { a ->
                val name = a.selectFirst("span.text-ink")?.text()?.trim()
                    ?: a.text().trim()
                if (name.isBlank()) return@mapNotNull null
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ChapterInfo(name = name, key = if (href.startsWith("http")) href else "$baseUrl$href")
            }.sortedWith(compareBy { it.name })
        } catch (e: Exception) {
            Log.error { "NovelTime getChapterList error: ${e.message}" }
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // CUSTOM CONTENT FETCHING
    // ══════════════════════════════════════════════════════════════════

    override suspend fun getContents(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        return try {
            val html = client.get(requestBuilder(chapter.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)
            val content = doc.selectFirst(".chapter-body") ?: return emptyList()

            val pages = mutableListOf<Page>()
            content.select("p[data-tts]").forEach { p ->
                val text = p.text().trim()
                if (text.isNotBlank()) pages.add(Text(text))
            }
            content.select("img").forEach { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }?: return@forEach
                if (src.isNotBlank()) pages.add(ImageUrl(src))
            }
            if (pages.isEmpty()) listOf(Text("")) else pages
        } catch (e: Exception) {
            Log.error { "NovelTime getContents error: ${e.message}" }
            emptyList()
        }
    }
}
