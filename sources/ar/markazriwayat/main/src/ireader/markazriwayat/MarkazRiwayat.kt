package ireader.markazriwayat

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import ireader.core.source.Dependencies
import ireader.core.source.asJsoup
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
import ireader.core.source.SourceFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Instant
import tachiyomix.annotations.Extension
import tachiyomix.annotations.GenerateTests
import tachiyomix.annotations.TestExpectations
import tachiyomix.annotations.TestFixture

@Extension
@GenerateTests(
    unitTests = true,
    integrationTests = false,
    searchQuery = "sword",
    minSearchResults = 1
)
@TestFixture(
    novelUrl = "https://markazriwayat.com/novel/زوجتي-هي-حاكمة-السيف/",
    chapterUrl = "https://markazriwayat.com/novel/زوجتي-هي-حاكمة-السيف/الفصل-1/",
    expectedTitle = "زوجتي هي حاكمة السيف",
    expectedAuthor = "لورد غامض"
)
@TestExpectations(
    minLatestNovels = 10,
    minChapters = 100,
    supportsPagination = true,
    requiresLogin = false
)
@OptIn(kotlin.time.ExperimentalTime::class)
abstract class MarkazRiwayat(deps: Dependencies) : SourceFactory(
    deps = deps,
) {
    override val lang: String
        get() = "ar"

    override val baseUrl: String
        get() = "https://markazriwayat.com"

    override val id: Long
        get() = 842746329  // Unique ID for MarkazRiwayat

    override val name: String
        get() = "MarkazRiwayat"

    // JSON parser for API responses
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun getFilters(): FilterList = listOf(
        Filter.Title(),
    )

    override fun getCommands(): CommandList = listOf(
        Command.Detail.Fetch(),
        Command.Chapter.Fetch(),
        Command.Content.Fetch(),
    )

    override val exploreFetchers: List<BaseExploreFetcher>
        get() = listOf(
            BaseExploreFetcher(
                "الأكثر شهرة",
                endpoint = "/popular/",
                selector = "a.lib-card",
                nameSelector = ".lib-card__title",
                coverSelector = ".lib-card__img img",
                coverAtt = "data-src",
                addBaseurlToCoverLink = true,
                linkSelector = "a.lib-card",
                linkAtt = "href",
                addBaseUrlToLink = true,
            ),
            BaseExploreFetcher(
                "أحدث الفصول",
                endpoint = "/",
                selector = "article.latest-card",
                nameSelector = "a.latest-title",
                coverSelector = "a.latest-cover img",
                coverAtt = "data-src",
                addBaseurlToCoverLink = true,
                linkSelector = "a.latest-title",
                linkAtt = "href",
                addBaseUrlToLink = true,
            ),
            BaseExploreFetcher(
                "أضيف حديثاً",
                endpoint = "/new/",
                selector = "a.lib-card",
                nameSelector = ".lib-card__title",
                coverSelector = ".lib-card__img img",
                coverAtt = "data-src",
                addBaseurlToCoverLink = true,
                linkSelector = "a.lib-card",
                linkAtt = "href",
                addBaseUrlToLink = true,
            ),
            BaseExploreFetcher(
                "Search",
                endpoint = "/?s={query}",
                selector = "a.lib-card",
                nameSelector = ".lib-card__title",
                coverSelector = ".lib-card__img img",
                coverAtt = "data-src",
                addBaseurlToCoverLink = true,
                linkSelector = "a.lib-card",
                linkAtt = "href",
                addBaseUrlToLink = true,
                type = SourceFactory.Type.Search
            ),
        )

    override val detailFetcher: Detail
        get() = SourceFactory.Detail(
            nameSelector = "h1.manga-title",
            coverSelector = ".manga-cover-wrap img",
            coverAtt = "data-src",
            addBaseurlToCoverLink = true,
            authorBookSelector = ".manga-author",
            descriptionSelector = ".manga-summary",
            statusSelector = ".manga-status-pill",
            onStatus = { status ->
                val lowerStatus = status.lowercase()
                when {
                    lowerStatus.contains("complete") || lowerStatus.contains("مكتملة") -> COMPLETED
                    lowerStatus.contains("ongoing") || lowerStatus.contains("جارية") -> ONGOING
                    else -> ONGOING
                }
            },
            categorySelector = ".pill-list .pill",
        )

    override val chapterFetcher: Chapters
        get() = SourceFactory.Chapters(
            selector = ".ch-row",
            nameSelector = ".ch-title",
            linkSelector = "a",
            linkAtt = "href",
            reverseChapterList = false,
            addBaseUrlToLink = true,
        )

    override val contentFetcher: Content
        get() = SourceFactory.Content(
            pageContentSelector = ".reading-content .text-right p",
        )

    // ═══════════════════════════════════════════════════════════════
    // CUSTOM API-BASED SEARCH
    // ═══════════════════════════════════════════════════════════════
    
    private var cachedNonce: String? = null

    private suspend fun getNonce(): String {
        cachedNonce?.let { return it }
        try {
            val html = client.get(requestBuilder(baseUrl)).bodyAsText()
            val theamAppStart = html.indexOf("THEAM_APP")
            if (theamAppStart != -1) {
                val nonceKey = "\"nonce\":\""
                val nonceStart = html.indexOf(nonceKey, theamAppStart)
                if (nonceStart != -1) {
                    val nonceEnd = html.indexOf("\"", nonceStart + nonceKey.length)
                    if (nonceEnd != -1) {
                        cachedNonce = html.substring(nonceStart + nonceKey.length, nonceEnd)
                        return cachedNonce!!
                    }
                }
            }
        } catch (e: Exception) {
        }
        return ""
    }

    /**
     * Custom search using MarkazRiwayat's JSON API
     * API endpoint: /wp-json/theam/v1/novel-search?term={query}&per_page=20
     */
    private suspend fun searchViaApi(query: String, perPage: Int = 20): MangasPageInfo {
        val encodedQuery = query.encodeURLParameter()
        val apiUrl = "$baseUrl/wp-json/theam/v1/novel-search?term=$encodedQuery&per_page=$perPage"
        
        val request = requestBuilder(apiUrl)
        val nonce = getNonce()
        if (nonce.isNotBlank()) {
            request.headers.append("X-WP-Nonce", nonce)
        }
        
        val response = client.get(request).bodyAsText()
        val jsonObj = json.parseToJsonElement(response).jsonObject
        
        // Parse the items array
        val items = jsonObj["items"]?.jsonArray ?: emptyList()
        
        val novels = items.mapNotNull { element ->
            val item = element.jsonObject
            
            // Extract required fields
            val id = item["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = item["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = item["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val cover = item["cover"]?.jsonPrimitive?.contentOrNull ?: ""
            
            // Extract genres (optional)
            val genres = item["genres"]?.jsonArray?.mapNotNull { 
                it.jsonPrimitive.contentOrNull 
            } ?: emptyList()
            
            // Extract chapter count (optional)
            val chaptersCount = item["chapters_count"]?.jsonPrimitive?.intOrNull ?: 0
            
            MangaInfo(
                key = link,
                title = title,
                cover = cover,
                genres = genres,
                // Add chapter count to description if available
                description = if (chaptersCount > 0) {
                    "عدد الفصول: $chaptersCount"
                } else {
                    ""
                }
            )
        }
        
        // API doesn't provide pagination info, so assume no next page for search
        return MangasPageInfo(novels, hasNextPage = false)
    }

    // ═══════════════════════════════════════════════════════════════
    // CUSTOM API-BASED CHAPTER FETCHING
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Extract manga ID from the novel detail page
     * Selector: #manga-chapters-list with data-manga-id attribute
     */
    private suspend fun extractMangaId(novelUrl: String): String? {
        val document = client.get(requestBuilder(novelUrl)).asJsoup()
        return document.select("#manga-chapters-list").attr("data-manga-id").takeIf { it.isNotBlank() }
    }
    
    /**
      * Fetch chapters via API with pagination
      * API endpoint: /wp-json/theam/v1/manga-chapters?manga_id={id}&order=DESC&page={page}&per_page=100
      */
    private suspend fun fetchChaptersViaApi(
        mangaId: String,
        order: String = "DESC",
        perPage: Int = 100
    ): List<ChapterInfo> {
        val allChapters = mutableListOf<ChapterInfo>()
        var currentPage = 1
        var hasMore = true
        
        while (hasMore) {
            val apiUrl = "$baseUrl/wp-json/theam/v1/manga-chapters?manga_id=$mangaId&order=$order&page=$currentPage&per_page=$perPage"
            
            try {
                val response = client.get(requestBuilder(apiUrl)).bodyAsText()
                val jsonObj = json.parseToJsonElement(response).jsonObject
                
                val items = jsonObj["items"]?.jsonArray ?: emptyList()
                
                val pageChapters = items.mapNotNull { element ->
                    val item = element.jsonObject
                    
                    val label = item["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val url = item["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val dateStr = item["date"]?.jsonPrimitive?.contentOrNull ?: ""
                    
                    val dateUpload = try {
                        Instant.parse(dateStr).toEpochMilliseconds()
                    } catch (e: Exception) {
                        0L
                    }
                    
                    ChapterInfo(
                        name = label,
                        key = url,
                        dateUpload = dateUpload
                    )
                }
                
                allChapters.addAll(pageChapters)
                
                hasMore = jsonObj["has_more"]?.jsonPrimitive?.booleanOrNull == true
                currentPage++
                
            } catch (e: Exception) {
                hasMore = false
            }
        }
        
        return allChapters
    }

    /**
     * Override getMangaList to use API search when query is present
     */
    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        // Check if there's a search query
        val query = filters.findInstance<Filter.Title>()?.value
        
        if (!query.isNullOrBlank()) {
            // Use API-based search
            return searchViaApi(query)
        }
        
        // Fall back to default HTML-based fetching for listings
        return super.getMangaList(filters, page)
    }

    /**
     * Override getChapterList to use API-based fetching
     * Priority:
     * 1. API-based fetching (extract manga_id and fetch via API with pagination)
     * 2. HTML-based fallback (default behavior)
     */
    override suspend fun getChapterList(
        manga: MangaInfo,
        commands: List<Command<*>>
    ): List<ChapterInfo> {
        try {
            val mangaId = extractMangaId(manga.key)
            if (mangaId != null) {
                val chapters = fetchChaptersViaApi(mangaId)
                if (chapters.isNotEmpty()) {
                    return chapters
                }
            }
        } catch (e: Exception) {
        }

        return super.getChapterList(manga, commands)
    }

    override suspend fun getContents(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        try {
            val document = client.get(requestBuilder(chapter.key)).asJsoup()
            val readingContent = document.selectFirst(".reading-content") ?: return emptyList()

            readingContent.select(".theam-chobf").remove()

            val pages = mutableListOf<Page>()

            val paragraphs = readingContent.select("p")
            for (p in paragraphs) {
                val text = p.text()?.trim() ?: continue
                if (text.isNotBlank() && text.length > 1) {
                    pages.add(Text(text))
                }
            }

            val images = readingContent.select("img")
            for (img in images) {
                val src = img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: continue
                pages.add(ImageUrl(src))
            }

            if (pages.isEmpty()) {
                pages.add(Text(""))
            }

            return pages
        } catch (e: Exception) {
            return emptyList()
        }
    }
}

// force rebuild
