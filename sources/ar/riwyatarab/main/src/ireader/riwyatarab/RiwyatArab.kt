package ireader.riwyatarab

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.parseToJsonElement
import tachiyomix.annotations.AutoSourceId
import tachiyomix.annotations.Extension
import tachiyomix.annotations.GenerateTests
import tachiyomix.annotations.TestExpectations
import tachiyomix.annotations.TestFixture

@Extension
@AutoSourceId(seed = "RiwyatArab")
@GenerateTests(
    unitTests = true,
    integrationTests = false,
    searchQuery = "عبد",
    minSearchResults = 1
)
@TestFixture(
    novelUrl = "https://riwayatarab.com/novel/صعود-براندر/",
    chapterUrl = "https://riwayatarab.com/novel/صعود-براندر/الفصل-1/",
    expectedTitle = "صعود براندر",
    expectedAuthor = ""
)
@TestExpectations(
    minLatestNovels = 5,
    minChapters = 10,
    supportsPagination = true,
    requiresLogin = false
)
abstract class RiwyatArab(deps: Dependencies) : SourceFactory(deps = deps) {

    override val lang: String get() = "ar"

    override val baseUrl: String get() = "https://riwayatarab.com"

    override val id: Long get() = RiwyatArabSourceId.ID

    override val name: String get() = "RiwyatArab"

    private val apiBase = "https://api.riwayatarab.com/api/novels"

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
                endpoint = "/novels?sort=latest",
                selector = "a.group[href*='/novel/']",
                nameSelector = "h1",
                coverSelector = "img[alt]",
                coverAtt = "src",
                addBaseurlToCoverLink = true,
                linkSelector = "a",
                linkAtt = "href",
                addBaseUrlToLink = true
            ),
            BaseExploreFetcher(
                "الأكثر شعبية",
                endpoint = "/novels?sort=popular",
                selector = "a.group[href*='/novel/']",
                nameSelector = "h1",
                coverSelector = "img[alt]",
                coverAtt = "src",
                addBaseurlToCoverLink = true,
                linkSelector = "a",
                linkAtt = "href",
                addBaseUrlToLink = true
            ),
            BaseExploreFetcher(
                "Search",
                endpoint = "/novels?search={query}",
                selector = "a.group[href*='/novel/']",
                nameSelector = "h1",
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
            authorBookSelector = "span:contains(بقلم)",
            onAuthor = { it.removePrefix("بقلم").trim() },
            descriptionSelector = "div.chapter-content",
            statusSelector = ".bg-blue-100.text-blue-800, .bg-green-500",
            onStatus = { status ->
                val s = status.lowercase()
                when {
                    s.contains("مكتمل") || s.contains("completed") -> COMPLETED
                    s.contains("مستمرة") || s.contains("ongoing") -> ONGOING
                    else -> ONGOING
                }
            },
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
            pageContentSelector = ".chapter-content p"
        )

    // ════════════════════════════════════════════════════════════════
    // CUSTOM API-BASED SEARCH / LISTINGS
    // ════════════════════════════════════════════════════════════════

    private data class NovelDto(
        val id: String? = null,
        val title: String? = null,
        val slug: String? = null,
        val author: String? = null,
        val description: String? = null,
        val cover_image: String? = null,
        val status: String? = null,
        val category_slug: String? = null
    )

    private suspend fun fetchApiNovels(
        search: String = "",
        category: String = "",
        sort: String = "",
        page: Int = 1,
        limit: Int = 20
    ): MangasPageInfo {
        return try {
            val url = "$apiBase?search=${java.net.URLEncoder.encode(search, "UTF-8")}" +
                "&category=${java.net.URLEncoder.encode(category, "UTF-8")}" +
                "&sort=${java.net.URLEncoder.encode(sort, "UTF-8")}" +
                "&page=$page&limit=$limit"
            val response = client.get(requestBuilder(url)).bodyAsText()
            val obj = json.parseToJsonElement(response).jsonObject
            val arr = obj["novels"]?.jsonArray ?: emptyList()

            val novels = arr.mapNotNull { element ->
                val o = element.jsonObject
                val slug = o["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                MangaInfo(
                    key = "$baseUrl/novel/$slug/",
                    title = title,
                    cover = o["cover_image"]?.jsonPrimitive?.contentOrNull ?: "",
                    author = o["author"]?.jsonPrimitive?.contentOrNull ?: "",
                    description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    genres = o["category_slug"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) } ?: emptyList(),
                    status = parseStatus(o["status"]?.jsonPrimitive?.contentOrNull)
                )
            }

            val pagination = obj["pagination"]?.jsonObject
            val hasNext = pagination?.get("hasNext")?.jsonPrimitive?.booleanOrNull ?: false
            MangasPageInfo(novels, hasNextPage = hasNext)
        } catch (e: Exception) {
            Log.error { "RiwyatArab fetchApiNovels error: ${e.message}" }
            MangasPageInfo(emptyList(), false)
        }
    }

    private fun parseStatus(status: String?): Long {
        val s = status?.lowercase() ?: ""
        return when {
            s.contains("مكتمل") || s.contains("completed") -> COMPLETED
            else -> ONGOING
        }
    }

    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        val query = filters.findInstance<Filter.Title>()?.value?.takeIf { it.isNotBlank() }
        return if (query != null) {
            fetchApiNovels(search = query, page = page)
        } else {
            val sortIdx = filters.findInstance<Filter.Sort>()?.value?.index
            val sort = when (sortIdx) { 1 -> "popular"; else -> "latest" }
            fetchApiNovels(sort = sort, page = page)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CUSTOM DETAIL PARSING
    // ════════════════════════════════════════════════════════════════

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo {
        return try {
            val html = client.get(requestBuilder(manga.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)

            val title = doc.selectFirst("h1")?.text()?.trim().takeIf { it.isNotBlank() } ?: manga.title

            val author = doc.select("span").firstOrNull { it.text().contains("بقلم") }
                ?.text()?.removePrefix("بقلم")?.trim().ifBlank { manga.author }

            val description = doc.selectFirst("div.chapter-content")?.text()?.trim()
                ?: oAuthDescription(doc) ?: manga.description

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
            Log.error { "RiwyatArab getMangaDetails error: ${e.message}" }
            manga
        }
    }

    private fun oAuthDescription(doc: Document): String? {
        val script = doc.select("script[type='application/ld+json']").firstOrNull { it.data().contains("Book") }
        return script?.data()?.let { data ->
            val start = data.indexOf("\"description\":")
            if (start == -1) null else {
                val s = data.substring(start + "\"description\":".length).trim()
                val end = s.indexOf('"', 1)
                if (end == -1) null else s.substring(1, end)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CUSTOM CHAPTER LIST (HTML only — chapters API returns 404)
    // ════════════════════════════════════════════════════════════════

    override suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*> >): List<ChapterInfo> {
        return try {
            val slug = manga.key.substringAfterLast("/novel/").removeSuffix("/")
            val allChapters = mutableListOf<ChapterInfo>()
            var currentPage = 1
            var hasMore = true
            while (hasMore && currentPage <= 50) {
                val url = "$baseUrl/novel/$slug/chapters?page=$currentPage"
                val html = client.get(requestBuilder(url)).bodyAsText()
                val doc: Document = Ksoup.parse(html)
                val pageChapters = doc.select("section#chapters ol li a").mapNotNull { a ->
                    val name = a.selectFirst("span.text-ink")?.text()?.trim()
                        ?: a.text().trim()
                    if (name.isBlank()) return@mapNotNull null
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ChapterInfo(name = name, key = if (href.startsWith("http")) href else "$baseUrl$href")
                }
                allChapters.addAll(pageChapters)
                val nextRel = doc.selectFirst("link[rel=next]") ?: doc.selectFirst("a[rel=next]")
                hasMore = pageChapters.isNotEmpty() && nextRel != null
                currentPage++
            }
            allChapters
        } catch (e: Exception) {
            Log.error { "RiwyatArab getChapterList error: ${e.message}" }
            emptyList()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CUSTOM CONTENT FETCHING
    // ════════════════════════════════════════════════════════════════

    override suspend fun getContents(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        return try {
            val html = client.get(requestBuilder(chapter.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)
            val content = doc.selectFirst(".chapter-content") ?: return emptyList()
            content.select("div.theam-chobf, .ads, .advertisement").remove()

            val pages = mutableListOf<Page>()
            content.select("p, div").forEach { el ->
                val text = el.text().trim()
                if (text.isNotBlank()) pages.add(Text(text))
            }
            content.select("img").forEach { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }?: return@forEach
                if (src.isNotBlank()) pages.add(ImageUrl(src))
            }
            if (pages.isEmpty()) listOf(Text("")) else pages
        } catch (e: Exception) {
            Log.error { "RiwyatArab getContents error: ${e.message}" }
            emptyList()
        }
    }
}