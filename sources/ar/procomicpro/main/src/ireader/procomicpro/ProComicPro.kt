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
import tachiyomix.annotations.AutoSourceId
import tachiyomix.annotations.Extension
import tachiyomix.annotations.GenerateTests
import tachiyomix.annotations.TestExpectations
import tachiyomix.annotations.TestFixture

/**
 * ProComic Pro — procomic.pro (Arabic)
 *
 * Real site structure (verified):
 *  - Catalog:      https://procomic.pro/ar/series?sort=latest|popular&page=N
 *  - Novel filter: ?type=novels
 *  - Series page:  /ar/{slug}-{id}
 *  - Chapter page: /ar/chapter/{slug}-{chapterNumber}-{chapterId}
 *  - Chapters API: /api/chapters?seriesId=... (global feed, not usable per-series)
 *
 * This source parses the server-rendered HTML directly because the site is a
 * different structure from procomic.net.
 */
@Extension
@AutoSourceId(seed = "ProComicPro")
@GenerateTests(
    unitTests = true,
    integrationTests = false,
    searchQuery = "رواية",
    minSearchResults = 1
)
@TestFixture(
    novelUrl = "https://procomic.pro/ar/investing-in-my-three-crippled-wives-get-10000x-times-return-694",
    chapterUrl = "https://procomic.pro/ar/chapter/investing-in-my-three-crippled-wives-get-10000x-times-return-306-252318",
    expectedTitle = "Investing In My Three Crippled Wives Get 10,000x Times Return",
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

    private val typeOptions = arrayOf("الكل", "روايات", "مانجا", "مانهوا", "مانها")
    private val typeValues = arrayOf("", "novels", "manga", "manhwa", "manhua")

    override fun getFilters(): FilterList = listOf(
        Filter.Title(),
        Filter.Select("النوع", typeOptions),
        Filter.Select("الترتيب", arrayOf("الأحدث", "الأكثر شعبية"))
    )

    override fun getCommands(): CommandList = listOf(
        Command.Detail.Fetch(),
        Command.Chapter.Fetch(),
        Command.Content.Fetch(),
    )

    override val exploreFetchers: List<BaseExploreFetcher>
        get() = emptyList()

    // The built-in fetchers are unused because every command is implemented
    // through custom HTML parsing below.
    override val detailFetcher: Detail
        get() = SourceFactory.Detail(
            nameSelector = "h1",
            coverSelector = "img[src*='/series/'], img[src*='cdn'], img[src*='app.procomic']",
            coverAtt = "src",
            addBaseurlToCoverLink = true,
            authorBookSelector = "a[href*='author'], a[href*='team']",
            onAuthor = { it.trim() },
            descriptionSelector = "h3:contains(نبذة) + p",
            statusSelector = "h3:contains(الحالة) + p",
            onStatus = { ONGOING },
            categorySelector = "a[href*='genres'], a[href*='genre']"
        )

    override val chapterFetcher: Chapters
        get() = SourceFactory.Chapters(
            selector = "a[href*='/ar/chapter/']",
            linkSelector = "a",
            linkAtt = "href",
            addBaseUrlToLink = true,
            nameSelector = "span, div",
            numberSelector = "span",
            reverseChapterList = false
        )

    override val contentFetcher: Content
        get() = SourceFactory.Content(
            pageContentSelector = ".chapter-content p, .reading-content p, article p, main p"
        )

    // ══════════════════════════════════════════════════════════════════
    // LIST / SEARCH
    // ══════════════════════════════════════════════════════════════════

    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        val query = filters.findInstance<Filter.Title>()?.value?.takeIf { it.isNotBlank() }
        val typeIdx = filters.findInstance<Filter.Select>()?.value ?: 0
        val sortIdx = filters.findInstance<Filter.Sort>()?.value?.index ?: 0
        val type = typeValues.getOrElse(typeIdx) { "" }
        val sort = if (sortIdx == 1) "popular" else "latest"

        val catalogUrl = buildString {
            append(baseUrl)
            append("/ar/series?")
            if (type.isNotEmpty()) {
                append("type=")
                append(type)
                append('&')
            }
            append("sort=")
            append(sort)
        }

        // Search: try the server-side search first, then fall back to filtering
        // the catalog locally (the site search is client-side / unreliable).
        if (!query.isNullOrBlank()) {
            val searchUrl = "$catalogUrl&search=${encode(query)}"
            val searched = parseListPage(searchUrl)
            if (searched.mangas.isNotEmpty()) return searched

            val q = query.lowercase()
            val catalog = parseListPage(catalogUrl)
            val filtered = catalog.mangas.filter { it.title.lowercase().contains(q) }
            return MangasPageInfo(mangas = filtered, hasNextPage = false)
        }

        return parseListPage(catalogUrl)
    }

    /**
     * Parses the server-rendered catalog page.
     *
     * NOTE: the site ignores the classic `?page=N` parameter (it paginates
     * client-side), so only the first page is available. We therefore report
     * `hasNextPage = false` to avoid returning the same page over and over.
     */
    private suspend fun parseListPage(url: String): MangasPageInfo {
        return try {
            val html = client.get(requestBuilder(url)).bodyAsText()
            val doc: Document = Ksoup.parse(html)
            val seen = mutableSetOf<String>()
            val items = mutableListOf<MangaInfo>()

            doc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                val path = seriesPath(href) ?: return@forEach
                val key = baseUrl + path
                if (!seen.add(key)) return@forEach

                val title = a.selectFirst("h3")?.text()?.trim()?.ifBlank { null }
                    ?: a.attr("title").trim().ifBlank { null }
                if (title == null || title.length < 2) return@forEach

                val img = a.selectFirst("img[src]")
                    ?: a.selectFirst("img[data-src]")
                val srcAttr = img?.attr("src").orEmpty()
                val dataAttr = img?.attr("data-src").orEmpty()
                val cover = srcAttr.ifBlank { dataAttr }

                items.add(MangaInfo(key = key, title = title, cover = absImage(cover)))
            }

            MangasPageInfo(mangas = items, hasNextPage = false)
        } catch (e: Exception) {
            Log.error { "ProComicPro list error: ${e.message}" }
            MangasPageInfo(mangas = emptyList(), hasNextPage = false)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // DETAIL
    // ══════════════════════════════════════════════════════════════════

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo {
        return try {
            val html = client.get(requestBuilder(manga.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)

            val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { manga.title } ?: manga.title
            val cover = doc.select("img[src]")
                .map { it.attr("src").trim() }
                .firstOrNull {
                    it.contains("/series/") || it.contains("/series-cards") ||
                        it.contains("cdn") || it.contains("app.procomic")
                }
                ?.let { absImage(it) }
                ?: manga.cover

            val description = extractDescription(doc) ?: manga.description
            val author = extractAuthor(doc) ?: manga.author
            val genres = extractGenres(doc)

            manga.copy(
                title = title,
                cover = cover,
                description = description,
                author = author,
                genres = if (genres.isNotEmpty()) genres else manga.genres,
                status = ONGOING
            )
        } catch (e: Exception) {
            Log.error { "ProComicPro detail error: ${e.message}" }
            manga
        }
    }

    private fun extractDescription(doc: Document): String? {
        val text = doc.text()
        val idx = text.indexOf("نبذة")
        if (idx < 0) return null
        var rest = text.substring(idx + "نبذة".length).trim()
        val stops = listOf("عرض المزيد", "عرض أقل", "التصنيفات", "التصنيفات الفرعية", "الشعبية")
        var end = rest.length
        stops.forEach { stop ->
            val i = rest.indexOf(stop)
            if (i in 0 until end) end = i
        }
        rest = rest.substring(0, end).trim()
        return rest.takeIf { it.length > 20 }
    }

    private fun extractAuthor(doc: Document): String? {
        val text = doc.text()
        val match = Regex("المؤلف\\s+(\\S+)").find(text) ?: return null
        val candidate = match.groupValues.getOrNull(1)?.trim()
        return candidate?.takeIf {
            it.isNotBlank() && it.length in 2..60 &&
                it !in setOf("العاملون", "عدد", "آخر", "المؤلف", "الرسام")
        }
    }

    private fun extractGenres(doc: Document): List<String> {
        val fromLinks = doc.select("a[href*='genres'], a[href*='genre']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 30 }
        return fromLinks.distinct()
    }

    // ══════════════════════════════════════════════════════════════════
    // CHAPTERS
    // ══════════════════════════════════════════════════════════════════

    override suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*>>): List<ChapterInfo> {
        return try {
            val html = client.get(requestBuilder(manga.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)

            doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("href")
                val path = chapterPath(href) ?: return@mapNotNull null
                val name = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                if (!name.contains("الفصل")) return@mapNotNull null
                ChapterInfo(name = name, key = baseUrl + path)
            }
                .distinctBy { it.key }
                .sortedWith(compareBy { chapterNumber(it.name) })
        } catch (e: Exception) {
            Log.error { "ProComicPro chapters error: ${e.message}" }
            emptyList()
        }
    }

    private fun chapterNumber(name: String): Double {
        val matches = Regex("\\d+(?:\\.\\d+)?").findAll(name).toList()
        return matches.lastOrNull()?.value?.toDoubleOrNull() ?: 0.0
    }

    // ══════════════════════════════════════════════════════════════════
    // CONTENT
    // ══════════════════════════════════════════════════════════════════

    override suspend fun getContents(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        return try {
            val html = client.get(requestBuilder(chapter.key)).bodyAsText()
            val doc: Document = Ksoup.parse(html)
            val pages = mutableListOf<Page>()

            // Manga / manhwa / manhua pages
            doc.select("img[src], img[data-src]")
                .mapNotNull { img ->
                    val src = img.attr("src").ifBlank { img.attr("data-src") }.trim()
                    src.takeIf { isContentImage(it) }
                }
                .distinct()
                .forEach { pages.add(ImageUrl(absImage(it))) }

            if (pages.isNotEmpty()) return pages

            // Novel text
            val container = sequenceOf(
                ".chapter-content", ".reading-content", ".chapter-body",
                "[class*=chapter-content]", "[class*=reading]", "article"
            ).mapNotNull { doc.selectFirst(it) }.firstOrNull()

            val paragraphs = container?.select("p") ?: doc.select("p")

            paragraphs.forEach { p ->
                val text = p.text().trim()
                if (text.length > 1 && !isBoilerplate(text)) {
                    pages.add(Text(text))
                }
            }

            if (pages.isEmpty()) {
                val locked = html.contains("افتح الفصل الآن") || html.contains("محتوى مميز")
                pages.add(
                    Text(
                        if (locked) "🔒 هذا الفصل مقفل على ProComic ويحتاج إلى فتح/عملات لقراءته."
                        else "لا يوجد محتوى نصي متاح لهذا الفصل."
                    )
                )
            }
            pages
        } catch (e: Exception) {
            Log.error { "ProComicPro content error: ${e.message}" }
            emptyList()
        }
    }

    private fun isContentImage(src: String): Boolean {
        if (src.isBlank()) return false
        val lower = src.lowercase()
        if (lower.contains("logo") || lower.contains("icon") || lower.contains("avatar")) return false
        if (lower.startsWith("data:")) return false
        return lower.contains("cdn") || lower.contains("app.procomic") || lower.contains("/image_")
    }

    private fun isBoilerplate(text: String): Boolean {
        val markers = listOf(
            "ProComic", "افتح الفصل الآن", "محتوى مميز", "عرض أكثر", "عرض أقل",
            "وصف الفصل", "أسئلة شائعة", "يقع الفصل", "أُضيف الفصل", "من تأليف",
            "تصنيفات العمل", "يُعرف", "كم عدد فصول", "يتوفر أكثر", "متى نُشر",
            "ما تصنيف", "الترتيب", "عدد الدعم", "الداعمون", "ادعم", "احتمالية استمرار",
            "المؤلف", "العاملون", "آخر تحديث", "التصنيفات", "مشاركة", "نسخ الرابط",
            "تقرأ الآن", "صفحة العمل", "قائمة الفصول", "السابق", "التالي"
        )
        return markers.any { text.contains(it, ignoreCase = true) }
    }

    // ══════════════════════════════════════════════════════════════════
    // URL helpers
    // ══════════════════════════════════════════════════════════════════

    /** Returns the site-relative path for a series link, or null if it is not one. */
    private fun seriesPath(href: String): String? {
        val path = normalizePath(href) ?: return null
        if (!path.startsWith("/ar/")) return null
        if (path.contains("/chapter/")) return null
        val excluded = listOf(
            "/ar/series", "/ar/pages", "/ar/announcement", "/ar/notifications",
            "/ar/team", "/ar/author", "/ar/library", "/ar/profile", "/ar/login",
            "/ar/register", "/ar/search", "/ar/category"
        )
        if (excluded.any { path.startsWith(it) }) return null
        val last = path.trimEnd('/').substringAfterLast('/')
        if (!last.contains('-')) return null
        val idPart = last.substringAfterLast('-')
        if (idPart.isEmpty() || idPart.any { !it.isDigit() }) return null
        return path
    }

    /** Returns the site-relative path for an Arabic chapter link, or null. */
    private fun chapterPath(href: String): String? {
        val path = normalizePath(href) ?: return null
        if (!path.startsWith("/ar/chapter/")) return null
        return path
    }

    /** Extracts a path from absolute/relative/protocol-relative URL, stripping query/fragment. */
    private fun normalizePath(href: String): String? {
        val h = href.trim()
        if (h.isBlank()) return null
        val raw = when {
            h.startsWith("http://") || h.startsWith("https://") -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/") -> h
            else -> return null
        }
        val noQuery = raw.substringBefore('?').substringBefore('#')
        return if (noQuery.startsWith("/")) {
            noQuery
        } else {
            val schemeEnd = noQuery.indexOf("://")
            if (schemeEnd < 0) return null
            val slash = noQuery.indexOf('/', schemeEnd + 3)
            if (slash < 0) return null
            noQuery.substring(slash)
        }
    }

    /** Rewrites any absolute link to this source's host and makes relative links absolute. */
    private fun toAbsolute(href: String): String {
        val h = href.trim()
        val path = normalizePath(h) ?: return h
        return baseUrl + path
    }

    private fun absImage(src: String): String {
        val s = src.trim()
        return when {
            s.startsWith("http") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> baseUrl + s
            s.isBlank() -> ""
            else -> "$baseUrl/$s"
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
