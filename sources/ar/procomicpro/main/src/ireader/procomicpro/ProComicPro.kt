package ireader.procomicpro

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
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
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.Page
import ireader.core.source.model.Text
import kotlinx.serialization.json.Json
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
    // LIST / SEARCH - using public JSON APIs
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        val query = filters.findInstance<Filter.Title>()?.value?.takeIf { it.isNotBlank() }
        val typeIdx = filters.findInstance<Filter.Select>()?.value ?: 0
        val sortIdx = filters.findInstance<Filter.Sort>()?.value?.index ?: 0
        val type = typeValues.getOrElse(typeIdx) { "" }
        val sort = if (sortIdx == 1) "popular" else "latest"

        // Try search first
        if (!query.isNullOrBlank()) {
            val searchUrl = buildString {
                append(baseUrl)
                append("/api/public/series/search?status=approved&limit=18&page=$page&sort=$sort")
                if (type.isNotEmpty()) {
                    append("&type=$type")
                }
                append("&search=${encode(query)}")
            }
            val searched = parseListPage(searchUrl)
            if (searched.mangas.isNotEmpty()) return searched
        }

        // Regular catalog
        val catalogUrl = buildString {
            append(baseUrl)
            append("/api/public/series/search?status=approved&limit=18&page=$page&sort=$sort")
            if (type.isNotEmpty()) {
                append("&type=$type")
            }
        }
        return parseListPage(catalogUrl)
    }

    private suspend fun parseListPage(url: String): MangasPageInfo {
        return try {
            val response = client.get(requestBuilder(url))
            if (response.status.value >= 400) {
                Log.error { "ProComicPro API error: ${response.status.value} for $url" }
                return MangasPageInfo(emptyList(), false)
            }
            val jsonData = json.parseToJsonElement(response.bodyAsText())
            val data = jsonData.jsonObject["data"]?.jsonArray ?: return MangasPageInfo(emptyList(), false)
            val meta = jsonData.jsonObject["meta"]?.jsonObject
            
            val items = mutableListOf<MangaInfo>()
            data.forEach { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLong() ?: return@forEach
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                val description = obj["description"]?.jsonPrimitive?.contentOrNull
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                val status = obj["status"]?.jsonPrimitive?.contentOrNull
                val thumbnail = obj["thumbnail"]?.jsonPrimitive?.contentOrNull
                val coverImage = obj["coverImage"]?.jsonPrimitive?.contentOrNull
                val cover = coverImage ?: thumbnail ?: ""

                // Build the series URL
                val key = if (slug.isNotEmpty()) {
                    "$baseUrl/ar/$slug-$id"
                } else {
                    "$baseUrl/ar/series-$id"
                }

                items.add(MangaInfo(
                    key = key,
                    title = title,
                    cover = absImage(cover),
                    description = description,
                    author = extractAuthorFromDescription(description),
                    genres = extractGenresFromDescription(description),
                    status = if (status == "approved") MangaInfo.Companion.ONGOING else MangaInfo.Companion.UNKNOWN
                ))
            }

            val totalPages = meta?.["pages"]?.jsonPrimitive?.intOrNull ?: 1
            val hasNextPage = page < totalPages

            MangasPageInfo(items = items, hasNextPage = hasNextPage)
        } catch (e: Exception) {
            Log.error { "ProComicPro list error: ${e.message}" }
            MangasPageInfo(emptyList(), false)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DETAIL - using content API
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo {
        return try {
            // Try to extract ID from the URL
            val url = manga.key
            val id = url.substringAfterLast("/").toLongOrNull() ?: return manga

            val response = client.get(requestBuilder("$baseUrl/api/content/$id"))
            if (response.status.value >= 400) {
                return manga
            }
            
            val jsonData = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val title = jsonData["title"]?.jsonPrimitive?.contentOrNull ?: manga.title
            val description = jsonData["description"]?.jsonPrimitive?.contentOrNull ?: manga.description
            val thumbnail = jsonData["thumbnail"]?.jsonPrimitive?.contentOrNull
            val coverImage = jsonData["coverImage"]?.jsonPrimitive?.contentOrNull
            val cover = coverImage ?: thumbnail ?: manga.cover
            
            val metadata = jsonData["metadata"]?.jsonObject
            val author = metadata?.["author"]?.jsonPrimitive?.contentOrNull ?: extractAuthorFromDescription(description)
            val genres = extractGenresFromMetadata(metadata)

            manga.copy(
                title = title,
                cover = absImage(cover),
                description = description,
                author = author,
                genres = if (genres.isNotEmpty()) genres else manga.genres,
                status = MangaInfo.Companion.ONGOING
            )
        } catch (e: Exception) {
            Log.error { "ProComicPro detail error: ${e.message}" }
            manga
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CHAPTERS - using public chapters API
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*>>): List<ChapterInfo> {
        return try {
            // Extract ID from manga URL
            val id = manga.key.substringAfterLast("/").toLongOrNull() ?: return emptyList()

            val chapters = mutableListOf<ChapterInfo>()
            var page = 1
            var hasMore = true

            while (hasMore) {
                val response = client.get(requestBuilder(
                    "$baseUrl/api/public/chapters?contentId=$id&page=$page&limit=50"
                ))
                if (response.status.value >= 400) {
                    hasMore = false
                    break
                }
                
                val jsonData = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val chapterArray = jsonData["chapters"]?.jsonArray ?: emptyList<JsonObject>()
                val meta = jsonData["meta"]?.jsonObject
                hasMore = meta?.["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false

                chapterArray.forEach { chapterJson ->
                    val obj = chapterJson.jsonObject
                    val chapterId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val chapterNumber = obj["chapter_number"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val language = obj["language"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "ar"

                    // Build chapter URL
                    val slug = manga.key.substringAfterLast("/").replace("-$id", "")
                    val url = buildString {
                        append("$baseUrl/")
                        append(if (language == "ar") "ar" else "en")
                        append("/chapter/$slug-$chapterNumber-$chapterId")
                    }

                    chapters.add(ChapterInfo(
                        name = if (title.isNotBlank()) title else "الفصل $chapterNumber",
                        key = url
                    ))
                }

                page++
            }

            // Deduplicate by URL
            chapters.distinctBy { it.key }.sortedWith(compareBy { chapterNumber(it.name) })
        } catch (e: Exception) {
            Log.error { "ProComicPro chapters error: ${e.message}" }
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTENT - handle locked/safe-browsing content
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getContents(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        return try {
            val response = client.get(requestBuilder(chapter.key))
            val html = response.bodyAsText()

            // Check for locked content
            if (html.contains("افتح الفصل الآن") || html.contains("محتوى مميز") || 
                html.contains("التصفح الآمن مطلوب") || html.contains("safe-browsing")) {
                return listOf(
                    Text(
                        "🔒 هذا الفصل مقفل على ProComic ويحتاج إلى فتح/عملات لقراءته."
                    )
                )
            }

            // Try to extract images from the HTML
            val pages = mutableListOf<Page>()
            
            // Pattern for images in chapter content
            val imgRegex = "<img[^>]*src=[\"]([^\"]*)[\""][^>]*>".toRegex()
            val matches = imgRegex.findAll(html)
            
            matches.forEach { match ->
                val src = match.groupValues[1]
                if (isContentImage(src)) {
                    pages.add(ImageUrl(absImage(src)))
                }
            }

            if (pages.isNotEmpty()) {
                return pages
            }

            // If no images found, check for text content
            val textRegex = "<p[^>]*>(.*?)</p>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val textMatches = textRegex.findAll(html)
            val texts = mutableListOf<String>()
            
            textMatches.forEach { match ->
                val text = match.groupValues[1].replace("\n", " ").trim()
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

            // No content found
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

    private fun extractAuthorFromDescription(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val match = Regex("المؤلف\s+(\S+)").find(description) ?: return null
        val candidate = match.groupValues.getOrNull(1)?.trim()
        return candidate?.takeIf {
            it.isNotBlank() && it.length in 2..60 &&
                it !in setOf("العاملون", "عدد", "آخر", "المؤلف", "الرسام")
        }
    }

    private fun extractGenresFromDescription(description: String?): List<String> {
        if (description.isNullOrBlank()) return emptyList()
        return Regex("#([^#\s]+)").findAll(description)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() && it.length < 30 }
            .distinct()
    }

    private fun extractGenresFromMetadata(metadata: JsonObject?): List<String> {
        if (metadata == null) return emptyList()
        val genres = metadata["genres"]?.jsonPrimitive?.contentOrNull ?: ""
        if (genres.isBlank()) return emptyList()
        return genres.split(",").map { it.trim() }.filter { it.isNotBlank() }
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

    // Filter option values
    private val typeValues = arrayOf("", "novels", "manga", "manhwa", "manhua")
}