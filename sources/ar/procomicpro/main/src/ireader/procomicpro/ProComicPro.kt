package ireader.procomicpro

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomix.annotations.AutoSourceId
import tachiyomix.annotations.Extension
import tachiyomix.annotations.GenerateTests
import tachiyomix.annotations.TestExpectations
import tachiyomix.annotations.TestFixture

/**
 * ProComic Pro — procomic.pro (Arabic)
 *
 * Uses the public JSON API (verified 2026-11-14):
 *  - Series list:  /api/public/series/search?status=approved&limit=18&page=N&sort=latest
 *  - Content:      /api/content/{id}
 *  - Chapters:     /api/public/chapters?contentId={id}&page=N&limit=50
 *
 * Series URL:  /ar/{slug}-{id}
 * Chapter URL: /ar/chapter/{slug}-{chapterNumber}-{chapterId}
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
    novelUrl = "https://procomic.pro/ar/sister-do-i-have-to-be-a-maid-together-with-you-too-929",
    chapterUrl = "https://procomic.pro/ar/chapter/sister-do-i-have-to-be-a-maid-together-with-you-too-355-247925",
    expectedTitle = "Sister, Do I Have to Be a Maid Together With You Too?",
    expectedAuthor = "电子熊猫"
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val typeValues = arrayOf("", "novels", "manga", "manhwa", "manhua")

    override fun getFilters(): FilterList = listOf(
        Filter.Title(),
        Filter.Select("النوع", arrayOf("الكل", "روايات", "مانجا", "مانهوا", "مانها")),
        Filter.Sort("الترتيب", arrayOf("الأحدث", "الأكثر شعبية"))
    )

    override fun getCommands(): CommandList = listOf(
        Command.Detail.Fetch(),
        Command.Chapter.Fetch(),
        Command.Content.Fetch(),
    )

    // ═══════════════════════════════════════════════════════════════
    // LIST / SEARCH
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        val query = filters.findInstance<Filter.Title>()?.value?.takeIf { it.isNotBlank() }
        val typeIdx = filters.findInstance<Filter.Select>()?.value ?: 0
        val sortIdx = filters.findInstance<Filter.Sort>()?.value?.index ?: 0
        val type = typeValues.getOrElse(typeIdx) { "" }
        val sort = if (sortIdx == 1) "popular" else "latest"

        val url = buildString {
            append(baseUrl)
            append("/api/public/series/search?status=approved&limit=18&page=$page&sort=$sort")
            if (type.isNotEmpty()) {
                append("&type=$type")
            }
            if (!query.isNullOrBlank()) {
                append("&search=")
                append(encode(query))
            }
        }
        return parseListPage(url)
    }

    private suspend fun parseListPage(url: String): MangasPageInfo {
        return try {
            val response = client.get(requestBuilder(url))
            if (response.status.value >= 400) {
                Log.error { "ProComicPro API error: ${response.status.value} for $url" }
                return MangasPageInfo(emptyList(), false)
            }
            val jsonData = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = jsonData["data"]?.jsonArray ?: return MangasPageInfo(emptyList(), false)
            val hasNextPage = jsonData["hasMore"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

            val items = mutableListOf<MangaInfo>()
            data.forEach { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                val description = obj["description"]?.jsonPrimitive?.contentOrNull
                val thumbnail = obj["thumbnail"]?.jsonPrimitive?.contentOrNull
                val coverImage = obj["coverImage"]?.jsonPrimitive?.contentOrNull
                val cover = coverImage ?: thumbnail ?: ""

                val key = if (slug.isNotEmpty()) {
                    "$baseUrl/ar/$slug-$id"
                } else {
                    "$baseUrl/ar/series-$id"
                }

                items.add(MangaInfo(
                    key = key,
                    title = title,
                    cover = absImage(cover),
                    description = description ?: ""
                ))
            }

            MangasPageInfo(mangas = items, hasNextPage = hasNextPage)
        } catch (e: Exception) {
            Log.error { "ProComicPro list error: ${e.message}" }
            MangasPageInfo(emptyList(), false)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DETAIL
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo {
        return try {
            val id = extractIdFromUrl(manga.key) ?: return manga

            val response = client.get(requestBuilder("$baseUrl/api/content/$id"))
            if (response.status.value >= 400) return manga

            val jsonData = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val title = jsonData["title"]?.jsonPrimitive?.contentOrNull ?: manga.title
            val description = jsonData["description"]?.jsonPrimitive?.contentOrNull ?: manga.description
            val thumbnail = jsonData["thumbnail"]?.jsonPrimitive?.contentOrNull
            val coverImage = jsonData["coverImage"]?.jsonPrimitive?.contentOrNull
            val cover = coverImage ?: thumbnail ?: manga.cover

            val metadata = jsonData["metadata"]?.jsonObject
            val author = metadata?.get("author")?.jsonPrimitive?.contentOrNull
            val genres = metadata?.get("genres")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            manga.copy(
                title = title,
                cover = absImage(cover),
                description = description ?: "",
                author = author ?: "",
                genres = if (genres.isNotEmpty()) genres else manga.genres,
                status = ONGOING
            )
        } catch (e: Exception) {
            Log.error { "ProComicPro detail error: ${e.message}" }
            manga
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CHAPTERS
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*>>): List<ChapterInfo> {
        return try {
            val id = extractIdFromUrl(manga.key) ?: return emptyList()
            val slug = extractSlugFromUrl(manga.key) ?: return emptyList()

            val chapters = mutableListOf<ChapterInfo>()
            var page = 1
            var hasMore = true

            while (hasMore) {
                val response = client.get(requestBuilder(
                    "$baseUrl/api/public/chapters?contentId=$id&page=$page&limit=50"
                ))
                if (response.status.value >= 400) break

                val jsonData = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val chapterArray = jsonData["chapters"]?.jsonArray ?: break
                hasMore = jsonData["hasMore"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

                chapterArray.forEach { chapterJson ->
                    val obj = chapterJson.jsonObject
                    val chapterId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val chapterNumber = obj["chapter_number"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val language = obj["language"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "ar"

                    val lang = if (language == "ar") "ar" else "en"
                    val chapterUrl = "$baseUrl/$lang/chapter/$slug-$chapterNumber-$chapterId"

                    chapters.add(ChapterInfo(
                        name = if (title.isNotBlank()) title else "الفصل $chapterNumber",
                        key = chapterUrl
                    ))
                }

                page++
            }

            chapters.distinctBy { it.key }.sortedWith(compareBy { chapterNumber(it.name) })
        } catch (e: Exception) {
            Log.error { "ProComicPro chapters error: ${e.message}" }
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTENT
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getContents(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        return try {
            val response = client.get(requestBuilder(chapter.key))
            val html = response.bodyAsText()

            // Check for locked content
            if (html.contains("افتح الفصل الآن") || html.contains("محتوى مميز") ||
                html.contains("التصفح الآمن مطلوب") || html.contains("safe-browsing")) {
                return listOf(
                    Text("🔒 هذا الفصل مقفل على ProComic ويحتاج إلى فتح/عملات لقراءته.")
                )
            }

            val pages = mutableListOf<Page>()

            // Extract images
            val imgRegex = "<img[^>]*src=[\"']([^\"']*)[\"'][^>]*>".toRegex()
            imgRegex.findAll(html).forEach { match ->
                val src = match.groupValues[1]
                if (isContentImage(src)) {
                    pages.add(ImageUrl(absImage(src)))
                }
            }

            if (pages.isNotEmpty()) return pages

            // Extract text content
            val textRegex = "<p[^>]*>(.*?)</p>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val texts = mutableListOf<String>()
            textRegex.findAll(html).forEach { match ->
                val text = match.groupValues[1]
                    .replace(Regex("<[^>]*>"), "")
                    .replace("\n", " ")
                    .trim()
                if (text.length > 10 && !isBoilerplate(text)) {
                    texts.add(text)
                }
            }

            if (texts.isNotEmpty()) {
                texts.take(50).forEach { text ->
                    pages.add(Text(text))
                }
                return pages
            }

            listOf(Text("لا يوجد محتوى نصي متاح لهذا الفصل."))
        } catch (e: Exception) {
            Log.error { "ProComicPro content error: ${e.message}" }
            listOf(Text("خطأ في تحميل محتوى الفصل: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun isContentImage(src: String): Boolean {
        if (src.isBlank()) return false
        val lower = src.lowercase()
        if (lower.contains("logo") || lower.contains("icon") || lower.contains("avatar")) return false
        if (lower.startsWith("data:")) return false
        return lower.contains("cdn") || lower.contains("prochan") || lower.contains("procomic") || lower.contains("/image_")
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

    private fun chapterNumber(name: String): Double {
        val matches = Regex("\\d+(?:\\.\\d+)?").findAll(name).toList()
        return matches.lastOrNull()?.value?.toDoubleOrNull() ?: 0.0
    }

    private fun absImage(src: String): String {
        val s = src.trim()
        return when {
            s.startsWith("http") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "$baseUrl$s"
            s.isBlank() -> ""
            else -> "$baseUrl/$s"
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** Extracts the numeric ID from a URL like /ar/slug-929 → 929 */
    private fun extractIdFromUrl(url: String): Long? {
        val lastPart = url.trimEnd('/').substringAfterLast('/')
        return lastPart.substringAfterLast('-').toLongOrNull()
    }

    /** Extracts the slug from a URL like /ar/slug-929 → slug */
    private fun extractSlugFromUrl(url: String): String? {
        val lastPart = url.trimEnd('/').substringAfterLast('/')
        val idPart = lastPart.substringAfterLast('-')
        if (idPart.isEmpty() || idPart.any { !it.isDigit() }) return null
        return lastPart.substringBeforeLast("-$idPart").ifBlank { null }
    }
}