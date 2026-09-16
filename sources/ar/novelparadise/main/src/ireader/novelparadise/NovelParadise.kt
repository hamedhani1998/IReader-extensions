package ireader.novelparadise

import android.util.Log
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import ireader.core.source.Dependencies
import ireader.core.source.SourceFactory
import ireader.core.source.findInstance
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Command
import ireader.core.source.model.CommandList
import ireader.core.source.model.Filter
import ireader.core.source.model.FilterList
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.Page
import ireader.core.source.model.Text
import tachiyomix.annotations.Extension


/**
 * NovelParadise (جنة الروايات) — WordPress `lightnovel` theme behind Cloudflare.
 *
 * The whole site answers HTTP 403 + `Server: cloudflare` to OkHttp without a cf_clearance
 * cookie, so the app's [CloudflareInterceptor] is the real fix-path: on Android it spins a
 * WebView to run the Turnstile challenge, captures a fresh cf_clearance, retries, and caches
 * it in the shared jar. We therefore make `cloudflareClient` (the interceptor-wrapped OkHttp)
 * our ONLY primary channel. `deps.httpClients.browser.fetch` is a secondary rescue when the
 * interceptor can't trigger (e.g. desktop where there's no WebView) — and we always pass it a
 * non-null *content* selector so contentReady() waits for the real page instead of returning
 * the challenge stub.
 *
 * All diagnostics here go through [diag] which logs at `android.util.Log` (vis. logcat with
 * `adb logcat -s NovelParadise:*`) — the Kermit-backed ireader Log drops Info/Debug by default,
 * so those levels never show up on the phone.
 */
@Extension
abstract class NovelParadise(private val deps: Dependencies) : SourceFactory(
    deps = deps,
) {

    override val lang: String get() = "ar"
    override val baseUrl: String get() = "https://www.novelsparadise.site"
    override val id: Long get() = 7_351_326_936_191_662_005L
    override val name: String get() = "NovelParadise"

    // The interceptor-wrapped OkHttp client (solves CF via WebView on device).
    override val client: HttpClient get() = deps.httpClients.cloudflareClient

    // Real site root of the lightnovel theme:
    val root: String get() = "$baseUrl/np-light"

    // ── diagnostics ─────────────────────────────────────────────────────────────
    private fun diag(msg: String) = Log.w(TAG, msg)

    /**
     * Resolve any relative key/href to an absolute URL. The app hands us the key as
     * stored in the list, which can be a bare path ("/np-light/series/slug/…") when the
     * theme emits relative hrefs; using that directly makes OkHttp hit localhost:80 and
     * the WebView hang forever — the #1 cause of "chapters never load".
     */
    private fun absolute(url: String): String {
        val u = url.trim()
        if (u.isEmpty() || u.startsWith("http", ignoreCase = true)) return u
        return if (u.startsWith("/")) baseUrl + u else "$baseUrl/$u"
    }

    // ── listings ────────────────────────────────────────────────────────────────
    override fun getFilters(): FilterList = listOf(Filter.Title())
    override fun getCommands(): CommandList = listOf(
        Command.Detail.Fetch(),
        Command.Chapter.Fetch(),
        Command.Content.Fetch(),
    )

    // Real home sections (verified on-device, v2.18): the lightnovel home page has 4 content
    // sections — "اخر التحديثات" (latest updated, 54 series), "اجدد الروايات" (newly added,
    // 12), "الرائجة اليوم" (trending today, 12) and "الروايات المكتملة" (completed, 12) — each
    // a <section> whose heading (h2) names it. We expose exactly those + the full archive
    // (قائمة السلاسل) and بحث.
    class LatestUpdatesListing : Listing("أحدث الروايات")
    class NewSeriesListing : Listing("اجدد الروايات")
    class TrendingListing : Listing("الرائجة اليوم")
    class CompletedListing : Listing("الروايات المكتملة")
    class SeriesListListing : Listing("كل الروايات")

    override fun getListings(): List<Listing> = listOf(
        LatestUpdatesListing(),
        NewSeriesListing(),
        TrendingListing(),
        CompletedListing(),
        SeriesListListing(),
    )

    override suspend fun getMangaList(sort: Listing?, page: Int): MangasPageInfo {
        return when (sort) {
            // Each listing reads its OWN full, paginated archive page — NOT the limited home
            // carousel — so "عرض المزيد" keeps loading real content.
            is LatestUpdatesListing -> fetchGrid("$root/series-list/", page, filter = null)
            is NewSeriesListing -> fetchGrid("$root/series-list/?orderby=date", page, filter = null)
            is TrendingListing -> fetchGrid("$root/trending/", page, filter = null)
            is CompletedListing -> fetchGrid("$root/series-list/", page, filter = { it.isCompleted() })
            is SeriesListListing -> fetchGrid("$root/series-list/", page, filter = null)
            else -> fetchGrid("$root/series-list/", page, filter = null)
        }
    }

    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        val query = filters.findInstance<Filter.Title>()?.value
        if (!query.isNullOrBlank()) return search(query, page)
        return MANGAS_EMPTY
    }

    private suspend fun fetchGrid(
        url: String,
        page: Int,
        home: Boolean = false,
        filter: ((MangaInfo) -> Boolean)? = null,
    ): MangasPageInfo {
        // Keep any query string (?orderby=…) when building the paged URL.
        val base = url.substringBefore("?")
        val query = url.substringAfter("?", missingDelimiterValue = "")
        val target = when {
            page <= 1 -> url
            query.isNotEmpty() -> "$base?${query}&page=$page"
            else -> "$base/page/$page/"
        }
        return try {
            val html = fetchPage(target, SERIES_ANCHOR_SELECTOR)
            val doc = Ksoup.parse(html)

            // On-device STRUCTURE dump (v2.9) proved the theme does NOT use the classic
            // .listupd/.lexa/.maindet grids — it uses <article>-based cards. So we extract
            // series links directly, dedupe by detail URL, and pull cover + title from the
            // card region.
            val all = doc.select(SERIES_ANCHOR_SELECTOR).mapNotNull { parseSeriesAnchor(it) }
                .distinctBy { it.key }
            // Secondary dedup by title: the same novel can appear with Arabic & English slugs
            // giving different keys — dedup by normalised title as a safety net.
            val byTitle = linkedMapOf<String, MangaInfo>()
            all.forEach { m -> byTitle.putIfAbsent(normalizedTitle(m.title), m) }
            val novels = if (filter == null) byTitle.values.toList() else byTitle.values.filter(filter)

            if (page <= 1) {
                diagPagination(doc, target)
                diagSortControls(doc, target)
                val preview = byTitle.values.take(6).map { it.title.take(28) }.joinToString(" | ")
                diag("PG1 $target titles: $preview")
            }
            if (novels.isEmpty() && html.length > 1_000) {
                diagnoseHtml(html)
            }
            if (home) diagHome(html)

            val hasNext = doc.selectFirst("a.next, a.next.page-numbers, a.nextpostslink, .pagination a.next") != null

            diag("grid ${if (home) "home" else url}: ${all.size} raw → ${novels.size} novels, hasNext=$hasNext (html=${html.length}B)")
            MangasPageInfo(novels, hasNext)
        } catch (e: Exception) {
            diag("grid error on $target: ${e.message}")
            MANGAS_EMPTY
        }
    }

    /**
     * Scrape one series from any <a> whose href is a /np-light/series/{slug}/ detail URL.
     * Skips chapter links and the "series-list" archive links (real detail URLs only), and
     * walks to the nearest card container (article) for the cover image. Status anchors
     * ("مستمرة") fall back to a sibling h2/h3 title.
     */
    private fun parseSeriesAnchor(a: Element): MangaInfo? {
        val href = a.absUrl("href").ifBlank { a.attr("href") }
        if (href.isBlank() || !href.contains(SERIES_KEY)) return null
        if (href.contains(CHAPTER_KEY) || href.endsWith("series-list/")) return null

        // The same series URL appears multiple times in a card: once as the real title
        // (in an h2/h3 or a "serie-s" anchor) and sometimes with only status text
        // ("مستمرة" / "متابعة"). Choose the LONGEST anchor text for this href inside the
        // card, ignoring status words — that is the real title.
        val container = a.closest("article") ?: a.parent()?.parent() ?: a.parent() ?: a
        var title = bestTitleForHref(container, href)
        title = cleanTitle(title)
        if (title.isBlank()) {
            diag("parseSeriesAnchor REJECT href=$href container.article=${a.closest("article") != null} " +
                "anchorText='${a.text().trim().take(24)}' parents=${(a.parent()?.tagName() ?: "")}/${
                    (a.parent()?.parent()?.tagName() ?: "")}")
            return null
        }

        // Normalize key: resolve to an absolute URL (relative hrefs are Common in the
        // theme's HTML), drop trailing slash and decode %XX so raw-Arabic and
        // percent-encoded variants of the same URL dedupe to one novel.
        val key = absolute(href).trimEnd('/')
            .let { try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } }

        val cover = container.selectFirst("img")?.let { img ->
            listOf("data-src", "data-lazy-src", "data-lazy", "src")
                .firstNotNullOfOrNull { attr -> img.attr(attr).takeIf { it.isNotBlank() } }
        } ?: ""

        val status = readStatus(container)
        return MangaInfo(key = key, title = title, cover = cover, status = status)
    }

    /**
     * Read the series status from the card's `.series-status-badge` — either from its
     * `is-{class}` (e.g. `is-completed`) or its Arabic label ("مكتملة" / "مستمرة").
     * Returns [MangaInfo.UNKNOWN] when the card carries no badge.
     */
    private fun readStatus(container: Element): Long {
        val badge = container.selectFirst(".series-status-badge") ?: return MangaInfo.UNKNOWN
        val cls = badge.classNames().firstOrNull { it.startsWith("is-") }?.removePrefix("is-")
        val text = badge.text().trim().lowercase()
        return when {
            cls?.contains("complet", ignoreCase = true) == true || text.contains("مكتمل") ||
                text == "completed" || cls == "finish" -> MangaInfo.COMPLETED
            cls?.contains("ongoing", ignoreCase = true) == true || text.contains("مستمر") ||
                text == "ongoing" -> MangaInfo.ONGOING
            else -> MangaInfo.UNKNOWN
        }
    }

    /** Longest "real name" among anchors pointing at [href] within [container]. */
    private fun bestTitleForHref(container: Element, href: String): String {
        val candidates = mutableListOf<Pair<String, Int>>() // text, score
        for (el in container.select("a[href]")) {
            val elHref = el.absUrl("href").ifBlank { el.attr("href") }
            if (elHref != href) continue
            val text = el.text().trim()
            if (text.isBlank()) continue
            // Prefer heading anchors / serie-s, and long descriptive text.
            val inHeading = el.closest("h1, h2, h3") != null || el.hasClass("serie-s")
            val score = if (inHeading) 100 else 0
            candidates.add(text to score)
        }
        return candidates
            .filter { (text, _) -> text.length >= 4 && !isStatusWord(text) }
            .maxByOrNull { (text, score) -> score * 1000 + text.length }
            ?.first
            ?: ""
    }

    private fun isStatusWord(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "مستمرة" || t == "متابعة" || t == "مكتملة" ||
            t.contains("فصل") || t.startsWith("0 ") ||
            t.matches(Regex("^[0-9]+\\s*فصل$"))
    }

    private suspend fun search(query: String, page: Int): MangasPageInfo {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val target = "$root/?s=$encoded"
        return try {
            val html = fetchPage(target, SERIES_ANCHOR_SELECTOR)
            val doc = Ksoup.parse(html)
            val novels = doc.select(SERIES_ANCHOR_SELECTOR).mapNotNull { parseSeriesAnchor(it) }
                .distinctBy { it.key }
            diag("search '$query': ${novels.size} results")
            MangasPageInfo(novels, false)
        } catch (e: Exception) {
            diag("search error: ${e.message}")
            MANGAS_EMPTY
        }
    }

    // ── details / chapters / content (theme selectors) ──────────────────────────
    override val detailFetcher: Detail = SourceFactory.Detail(
        nameSelector = "h1, h1.entry-title",
        coverSelector = ".sertothumb img, .infseries .sertothumb img",
        coverAtt = "src",
        authorBookSelector = ".serl .serval, .infseries .serl .serval, [class*=author] a",
        categorySelector = ".sertogenre a, .infseries .sertogenre a, a[rel=category]",
        descriptionSelector = ".sersys, .sersysfull, .entry-content",
        onDescription = { list -> list.flatMap { it.split("\n") }.map { it.trim() }.filter { it.isNotBlank() } },
    )

    override suspend fun getMangaDetailsRequest(
        manga: MangaInfo,
        commands: List<Command<*>>,
    ): Document {
        return try {
            val html = fetchPage(manga.key, CONTENT_MARKER_SELECTOR)
            if (html.isBlank()) {
                diag("getMangaDetailsRequest: blank for ${manga.key}")
                Ksoup.parse("")
            } else {
                Ksoup.parse(html)
            }
        } catch (e: Exception) {
            diag("getMangaDetailsRequest error: ${e.message}")
            Ksoup.parse("")
        }
    }

    override val chapterFetcher: Chapters = SourceFactory.Chapters(
        selector = ".eplisterfull ul li, .eplister ul li, ul.list-chapters li",
        nameSelector = ".epl-num, a.chapternum, a span",
        linkSelector = "a",
        linkAtt = "href",
    )

    /**
     * Fetch the chapter list from the detail page. The lightnovel theme puts chapters in an
     * `.eplister` list, but like the grid, the live DOM may differ — so if the named list
     * yields nothing we extract chapter links directly, then diagnose the structure on-device.
     */
    override suspend fun getChapterList(
        manga: MangaInfo,
        commands: List<Command<*>>,
    ): List<ChapterInfo> {
        diag("CHL START key=${manga.key}")

        // 1) Pre-fetched HTML (WebView) command, if the app actually hands one over.
        //    Log EVERYTHING so we can see what the WebView saw, even on the empty path.
        commands.findInstance<Command.Chapter.Fetch>()?.let { cmd ->
            diag("  CHL cmd Chapter.Fetch: url=${cmd.url.take(140).ifBlank { "<none>" }} html=${cmd.html.length}B")
            if (cmd.html.isNotBlank()) {
                diagChaptersDoc("  [cmd] ", cmd.html)
                val chapters = cleanChapters(chaptersParse(Ksoup.parse(cmd.html)))
                if (chapters.isNotEmpty()) {
                    diag("  CHL returns ${chapters.size} via [cmd] fetcher")
                    return applyChapterSorting(chapters)
                }
                val fallback = chapterLinksFromDoc(Ksoup.parse(cmd.html))
                if (fallback.isNotEmpty()) {
                    diag("  CHL returns ${fallback.size} via [cmd] link-fallback")
                    return applyChapterSorting(fallback)
                }
                diagnoseHtml(cmd.html)
                diag("  CHL [cmd] html parsed 0 chapters — continuing to direct fetch")
            }
        }

        // 2) Direct fetch of the detail page (the normal path when no command is set).
        return try {
            // The CF challenge door may need a moment — retry a few times when the page comes
            // back as a bare challenge stub (no real <a> links) so we don't show 0 chapters.
            var html = ""
            for (attempt in 1..3) {
                diag("  CHL direct fetch ${manga.key} (try $attempt) …")
                html = fetchPage(manga.key, CONTENT_MARKER_SELECTOR)
                diagChaptersDoc("  [drt] ", html)
                if (html.isNotBlank() && challengeStubOnly(html)) {
                    diag("  [drt] challenge stub; waiting for CF bypass before retry…")
                    kotlinx.coroutines.delay(1_500L)
                    continue
                }
                break
            }

            val doc = Ksoup.parse(html)

            val chapters = cleanChapters(chaptersParse(doc))
            if (chapters.isNotEmpty()) {
                diag("  CHL returns ${chapters.size} via [drt] fetcher")
                return applyChapterSorting(chapters)
            }

            // Fallback: every real chapter is an <a> whose href contains /chapter-N/.
            val fallback = chapterLinksFromDoc(doc)
            if (fallback.isEmpty()) {
                if (html.isNotBlank() && challengeStubOnly(html)) {
                    diag("  CHL: page is still a CF stub — returning empty (cached?)")
                } else {
                    diagnoseHtml(html)
                    diag("  CHL EMPTY on ${manga.key}")
                }
            } else {
                diag("  CHL returns ${fallback.size} via [drt] link-fallback")
            }
            applyChapterSorting(fallback)
        } catch (e: Exception) {
            diag("  CHL error on ${manga.key}: ${e.message}")
            emptyList()
        }
    }

    /** True when [html] is a Cloudflare/Turnstile stub rather than a real page: it has no <a> anchors. */
    private fun challengeStubOnly(html: String): Boolean {
        if (html.isBlank()) return false
        return try {
            Ksoup.parse(html).selectFirst("a[href]") == null
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Extract every real chapter link (`a[href*='/chapter-N/']`) from [doc] as [ChapterInfo].
     */
    private fun chapterLinksFromDoc(doc: Document): List<ChapterInfo> =
        doc.select("a[href*='$CHAPTER_KEY']").mapNotNull { a ->
            val raw = a.attr("href").trim().let { if (it.isBlank()) a.absUrl("href") else it }
            if (raw.isBlank() || !raw.contains(CHAPTER_KEY)) return@mapNotNull null
            val href = absolute(raw)
            val name = a.text().trim()
            val title = if (name.isBlank() || name.length < 2) {
                href.substringAfterLast("/").replace('-', ' ').trim()
            } else cleanChapterName(name)
            ChapterInfo(key = href, name = title)
        }.distinctBy { it.key }

    /** Apply [cleanChapterName] to every chapter, whichever parser produced it. */
    private fun cleanChapters(chapters: List<ChapterInfo>): List<ChapterInfo> =
        chapters.map { it.copy(name = cleanChapterName(it.name)) }

    /**
     * Strip the publish date that the theme renders inside the chapter <a> next to the title
     * ("الفصل 12  2024-05-20" / "الفصل 1 · 20 يوليو 2024"). Normalises Arabic-Indic digits
     * (٢٠٢٤-٠٥-٢٠) to ASCII first, then removes trailing (and leading) date+optional time from
     * both the title and the whole list, whichever parser produced the chapters.
     */
    private fun cleanChapterName(name: String): String {
        var t = normalizedDigits(name).trim()
        // Trailing numeric date (+ optional time). The theme GLUES the date to the title with
        // NO separator (logcat-proven: "الظهور الإلهي2025-12-12 04:29"), but it can also be
        // separated by a space / '+'; allow ZERO or more separator chars. Requiring a 4-digit
        // year keeps this safe — no real Arabic chapter title ends in YYYY-MM-DD itself.
        // Also covers "… 2024-05-20", "… 20/05/2024 14:30", "…+2026-09-14 10:50".
        t = t.replace(
            Regex("""(?:[\s +·:،,/\-]*)(?:20\d{2}[\-/.]\d{1,2}[\-/.]\d{1,2})(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?\s*$"""),
            ""
        )
        // Tidy leftovers from the removal ("الفصل 12 -", "الفصل 12 ·").
        t = t.replace(Regex("""[\s·\-:,/—]+\s*$"""), "").trim()
        // Trailing named-month date (Arabic/English): "… 20 يوليو 2024" / "… July 2, 2024"
        t = t.replace(
            Regex("""[\s +]+(?:\d{1,2}[\s .,]+\p{L}+[\s .]+\d{2,4}|\p{L}+[\s ]+\d{1,2}[\s .,]*\d{2,4})(?:\s+\d{1,2}:\d{2})?\s*$"""),
            ""
        ).trim()
        // Leading date (nicknames like "2024-05-20 · الفصل 1" / "20 يوليو 2024 · …")
        t = t.replace(
            Regex("""^(?:\d{1,2}[\-/.]\d{1,2}[\-/.]\d{2,4}|\d{2,4}[\-/.]\d{1,2}[\-/.]\d{1,2}|\d{1,2}[\s +.,]+\p{L}+[\s .]+\d{2,4}|\p{L}+[\s +]+\d{1,2}[\s +.,]*\d{2,4})(?:\s+\d{1,2}:\d{2})?[\s ·]+\s*"""),
            ""
        ).trim()
        return t.ifBlank { name }
    }

    /**
     * Strip the rating the theme renders next to a series name inside the card anchor
     * ("ملحمة النجمة 4.0★★★★★★★★★★"). The anchor text is "name + rating + a run of ★/☆
     * stars" — keep only the real name. Arabic-Indic digits are normalised first so the
     * decimal rating ("٤٫٥") is recognised too.
     */
    private fun cleanTitle(title: String): String {
        var t = normalizedDigits(title).trim()
        // 1) Solid run of star glyphs at the very end (with optional separators before it).
        t = t.replace(Regex("""[\s·:,/()\-]*\s*[★☆✦✧★☆]{1,12}\s*$"""), "").trim()
        // 2) A standalone decimal rating left over after the stars were removed ("… 4.0").
        t = t.replace(Regex("""\s+\d{1,2}[.,]\d{1,2}\s*$"""), "").trim()
        // 3) A "4.0/5" style rating.
        t = t.replace(Regex("""\s+\d{1,2}[.,]\d{1,2}\s*/\s*[0-5]\s*$"""), "").trim()
        return t
    }

    /** Normalise a title for dedup: lowercase, collapse whitespace, strip punctuation. */
    private fun normalizedTitle(title: String): String = title.lowercase().trim()
        .replace(Regex("[\\s\\p{Punct}]+"), " ")
        .trim()

    /** Map Arabic-Indic (٠-٩) and Persian (۰-۹) digits to ASCII so date regexes match. */
    private fun normalizedDigits(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                in '٠'..'٩' -> append('0' + (c - '٠'))
                in '۰'..'۹' -> append('0' + (c - '۰'))
                else -> append(c)
            }
        }
    }

    /**
     * Fingerprint a chapter-list body so we can tell what the WebView actually delivered:
     * chapter-link count, lis, name-anchor count, first few chapter links, and whether the
     * page looks like a challenge stub rather than a real chapter list.
     */
    private fun diagChaptersDoc(label: String, html: String) {
        if (html.isBlank()) {
            diag("$label body BLANK")
            return
        }
        val doc = Ksoup.parse(html)
        val chapterLinks = doc.select("a[href*='$CHAPTER_KEY']")
        val ulLis = doc.select("ul li").size
        val eplisters = doc.select(".eplisterfull, .eplister, ul.list-chapters").size
        val heads = doc.select("h1:not(:empty)").map { it.text().trim().take(50) }
        val pageTitle = titleOf(html)
        diag("$label html=${html.length}B chapterLinks=${chapterLinks.size} ul>li=$ulLis eplisters=$eplisters " +
            "title=$pageTitle challenge=${isChallenge(pageTitle)}")
        chapterLinks.take(6).forEach { a ->
            diag("$label  ${a.text().trim().take(40)} -> ${a.absUrl("href").ifBlank { a.attr("href") }}")
        }
        if (heads.isNotEmpty()) diag("$label h1=${heads.joinToString(" | ")}")
    }

    override val contentFetcher: Content = SourceFactory.Content(
        pageTitleSelector = "h1, h1.chapter-heading",
        pageContentSelector = "$CPAGE_CLASS, .entry-content, .chapter-content",
    )

    // ── page list (content paragraphs) ──────────────────────────────────────────
    override suspend fun getPageList(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        diag("PGL START key=${chapter.key.take(120)} name=${chapter.name.take(60)}")
        commands.filterIsInstance<Command.Content.Fetch>().firstOrNull()?.let { cmd ->
            if (cmd.html.isNotBlank()) {
                diag("  PGL via Content.Fetch (WebView) html=${cmd.html.length}B")
                diagChaptersDoc("  [pg-cmd] ", cmd.html)
                return pageContentParse(Ksoup.parse(cmd.html))
            }
        }

        return try {
            val html = fetchPage(chapter.key, CONTENT_MARKER_SELECTOR)
            diagChaptersDoc("  [pg-drt] ", html)
            if (html.isBlank()) {
                diag("  PGL blank body for ${chapter.key.take(120)}")
                return listOf(Text("المحتوى غير متوفر. جرّب فتح الفصل مرة أخرى."))
            }
            val pages = pageContentParse(Ksoup.parse(html))
            if (pages.isEmpty()) {
                diag("  PGL parsed 0 pages; dumping structure for ${chapter.key.take(120)}")
                diagnoseHtml(html)
            } else {
                diag("  PGL returns ${pages.size} pages for ${chapter.key.take(120)}")
            }
            pages
        } catch (e: Exception) {
            diag("  PGL error: ${e.message}")
            listOf(Text("المحتوى غير متوفر. جرّب فتح الفصل مرة أخرى."))
        }
    }

    /**
     * Extract the chapter body from a detail/chapter document:
     * 1. Find the .epcontent / .entry-content / .chapter-content container
     * 2. Pull its <p> paragraphs as pages
     * 3. If none, use the whole container text split into lines
     */
    override fun pageContentParse(document: Document): List<Page> {
        // Remove everything that isn't content.
        document.select(
            "script, style, noscript, iframe, nav, footer, header, " +
                ".sidebar, .comments, .ads, [class*=ad-], .announ",
        ).remove()

        // Strip advert placeholders ("Advertise here" etc.) the theme injects mid-chapter.
        document.select("div[class*='ad'], div[id*='ad'], p[class*='ad']").forEach { el ->
            val txt = el.text().trim()
            if (txt.length <= 40 && AD_TEXT_MARKERS.any { it in txt.lowercase() }) el.remove()
        }

        val content = mutableListOf<String>()
        val title = document.selectFirst("h1, h1.chapter-heading")?.text()?.trim()
        if (!title.isNullOrBlank()) content.add(title)

        // Paragraph granularity, then line granularity, then a last-chance fallback.
        val paragraphSelectors = listOf(
            "$CPAGE_CLASS p, .entry-content p, .chapter-content p, .reading-content p, #chapter-content p, .text-left p",
            "$CPAGE_CLASS div, .entry-content div, .chapter-content div, .reading-content div",
        )
        for (selector in paragraphSelectors) {
            val paragraphs = document.select(selector)
                .map { it.text().trim() }
                .filter { it.isNotBlank() && it.length > 3 }
                .distinct()
            if (paragraphs.size >= 2) {
                content.addAll(paragraphs)
                return content.map { Text(it) }
            }
        }

        for (selector in listOf(CPAGE_CLASS, ".entry-content", ".chapter-content", ".reading-content", ".text-left")) {
            val container = document.selectFirst(selector) ?: continue
            container.select("script, style, noscript, .ads").remove()
            val text = container.text().trim()
            if (text.isNotBlank() && text.length > 50) {
                val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() && it.length > 3 }.distinct()
                if (lines.isNotEmpty()) {
                    content.addAll(lines)
                    return content.map { Text(it) }
                }
            }
        }

        if (content.isNotEmpty()) return content.map { Text(it) }
        return listOf(Text("جاري تحميل المحتوى... حاول مرة أخرى"))
    }

    // ── CF-aware fetch ──────────────────────────────────────────────────────────
    /**
     * Fetch [url] and require the returned HTML to contain a real *content* marker:
     * [SERIES_ANCHOR_SELECTOR] (which matches only on pages that actually have a series link,
     * never on the Cloudflare challenge stub). Returns "" if no real content.
     */
    private suspend fun fetchPage(url: String, selector: String): String {
        // Defensive: always resolve to an absolute URL — stale/cached keys from a previous
        // version may still be bare paths (a relative URL hits localhost:80 / hangs the WebView).
        val abs = absolute(url)

        // 1. Primary: cloudflareClient — the OkHttp CloudflareInterceptor solves the challenge
        //    inside a HIDDEN WebView (running the Turnstile JS, which plants cf_clearance in the
        //    shared Chrome cookie store) and then re-fetches. On a warm cookie this returns real
        //    content instantly, invisibly, with NO visible challenge. When the cookie is cold the
        //    interceptor reports "Failed to bypass" — but its hidden WebView just warmed the
        //    session, which makes the browser (step 2) pass almost immediately.
        try {
            val body = client.get(requestBuilder(abs)).bodyAsText()
            if (isRealContent(body)) {
                diag("OK client ($abs) ${body.length}B")
                return body
            }
            diag("client returned ${body.length}B non-real; falling back to browser")
        } catch (e: Exception) {
            diag("client GET failed: ${e.message}")
        }

        // 2. BrowserEngine WebView — solves the challenge visibly and, crucially, waits for the
        //    *content* selector. A non-null selector means contentReady() only fires once real
        //    chapter/series links exist, so it usually returns the true page. Turnstile can take
        //    a few seconds, so retry up to 3× with a short pause when we get a challenge stub.
        for (attempt in 1..3) {
            try {
                diag("browser.fetch($abs, selector=…, try $attempt)…")
                val result = deps.httpClients.browser.fetch(
                    url = abs,
                    selector = selector,
                    timeout = 60_000L,
                )
                val body = result.responseBody
                if (result.isSuccess && isRealContent(body)) {
                    diag("OK browser ($abs) ${body.length}B")
                    return body
                }
                diag("browser gave no content (${result.statusCode} ${result.error ?: ""})")
            } catch (e: Exception) {
                diag("browser.fetch threw: ${e.message}")
            }
            if (attempt < 3) {
                kotlinx.coroutines.delay(2_000L)
                diag("  retrying browser (Turnstile may still be solving)…")
            }
        }
        return ""
    }

    private fun isRealContent(body: String): Boolean {
        if (body.isBlank() || body.length < 4_000) return false
        // A Turnstile challenge stub ("Just a moment…") has ZERO real <a> links — but it DOES
        // echo the requested URL (including /np-light/series/…) inside its scripts, so a
        // text-contains check gets fooled into accepting the stub. Require actual <a> structure.
        try {
            val doc = Ksoup.parse(body)
            if (doc.selectFirst("a[href]") == null) return false
        } catch (e: Exception) {
            return false
        }
        if (body.contains(SERIES_KEY)) return true
        return !isChallenge(titleOf(body))
    }

    /**
     * Called when a real page yielded no novels. Prints a DOM fingerprint to logcat:
     * how many nodes match each candidate selector, plus the first series-ish <a> links
     * (href + text + surrounding tag). This reveals the actual structure the theme uses,
     * since the CF gate blocks us from viewing raw HTML from the desktop.
     */
    private fun diagnoseHtml(body: String) {
        try {
            val doc = Ksoup.parse(body)
            fun count(sel: String) = doc.select(sel).size
            diag("STRUCTURE: html=${body.length}B, links=${count("a")}, " +
                "a.serie-s=${count("a.serie-s")}, a[href*='series']=${count("a[href*='series']")}, " +
                "a[href*='/np-light/']=${count("a[href*='/np-light/']")}, " +
                ".listupd=${count(".listupd")}, .lexa=${count(".lexa")}, .maindet=${count(".maindet")}, " +
                ".utao=${count(".utao")}, .uta=${count(".uta")}, .dtl=${count(".dtl")}, " +
                ".mdinfo=${count(".mdinfo")}, .luf=${count(".luf")}, h2=${count("h2")}, h3=${count("h3")}, " +
                "article=${count("article")}, .entry-content=${count(".entry-content")}, " +
                "img=${count("img")}")

            // First real (non-menu) <a> candidates sorted by presence of a long path.
            val links = doc.select("a[href]").asSequence()
                .map { it to it.attr("href") }
                .filter { it.second.isNotBlank() && !it.second.startsWith("#") && !it.second.startsWith("javascript:") }
                .take(40)
                .toList()
            diag("STRUCTURE-LINKS: ${links.size} candidates:")
            links.forEach { (a, href) ->
                if (href.contains("series") || a.text().trim().length > 5) {
                    diag("  [$href] << ${a.text().trim().take(40)}")
                }
            }
        } catch (e: Exception) {
            diag("diagnoseHtml failed: ${e.message}")
        }
    }

    /**
     * Fingerprint the novelparadise HOME page specifically: how many top-level home sections,
     * their headings/section titles, and the structure/status badges of the rendered cards.
     * Prints to logcat so we can split the home page into the real listing categories
     * ("أحدث الروايات" / "الرائجة اليوم" / "الروايات المكتملة" ...) on-device instead of guessing.
     */
    private fun diagHome(body: String) {
        try {
            val doc = Ksoup.parse(body)
            fun count(sel: String) = doc.select(sel).size
            diag("HOME-BASE: html=${body.length}B, sections=${count("section, .home-section, .widget, .sec")}, " +
                "h1=${count("h1")}, h2=${count("h2")}, h3=${count("h3")}, " +
                "series-card=${count(".series-card")}, series-card-main=${count(".series-card-main")}, " +
                "status-badge=${count(".series-status-badge")}, new-pill=${count(".new-pill")}, " +
                "data-rating=${count("[data-series-rating]")}, trending-link=${count("a[href*='trending']")}")

            val headings = doc.select("section .section-head h1, section .section-head h2, section h1, section h2")
                .map { it.text().trim().take(50) }.filter { it.isNotBlank() }
            if (headings.isNotEmpty()) diag("HOME-HEADINGS: ${headings.distinct().joinToString(" | ")}")

            val badges = doc.select(".series-status-badge").take(8).map { it.text().trim().take(20) }
            if (badges.isNotEmpty()) diag("HOME-BADGES: ${badges.distinct().joinToString(" | ")}")

            val trending = doc.selectFirst("a[href*='trending']")?.let { it.attr("href").trim() }
            if (trending != null) diag("HOME-TRENDING: link=$trending")

            // How many real series anchors fall inside each <section>? Group by closest section h2.
            val sections = doc.select("section")
            val map = LinkedHashMap<String, Int>()
            doc.select(SERIES_ANCHOR_SELECTOR).forEach { a ->
                val sec = a.closest("section")
                val h2 = sec?.selectFirst("h2")?.text()?.trim() ?: "(outside section)"
                map[h2] = (map[h2] ?: 0) + 1
            }
            map.forEach { (title, n) -> diag("HOME-SEC: [$title] = $n series") }
        } catch (e: Exception) {
            diag("diagHome failed: ${e.message}")
        }
    }

    /** Dump real pagination links (page numbers / next) to logcat for [target]. */
    private fun diagPagination(doc: Document, target: String) {
        try {
            val raw = doc.select("a.page-numbers, .pagination a, a.next, a.prev").map { a ->
                "${a.text().trim().take(10)} => ${a.attr("href").trim().take(90)}"
            }
            if (raw.isNotEmpty()) diag("PG $target: ${raw.take(15).joinToString(" | ")}")
            else diag("PG $target: (no pagination links found)")
        } catch (e: Exception) {
            diag("PG diag failed: ${e.message}")
        }
    }

    /** Dump form/select sort controls (&orderby options) to logcat for [target]. */
    private fun diagSortControls(doc: Document, target: String) {
        try {
            val opts = doc.select("select option").map { it.text().trim() to it.attr("value") }
            if (opts.isEmpty()) {
                diag("SORT $target: no <select> controls")
                return
            }
            diag("SORT $target: ${opts.take(20).joinToString(" | ") { "${it.first}=${it.second}" }}")
        } catch (e: Exception) {
            diag("SORT diag failed: ${e.message}")
        }
    }

    private fun titleOf(body: String): String {
        val start = body.indexOf("<title>", ignoreCase = true)
        if (start < 0) return ""
        val end = body.indexOf("</title>", start + 7, ignoreCase = true)
        if (end < 0) return ""
        return body.substring(start + 7, end)
    }

    private fun isChallenge(title: String): Boolean {
        if (title.isBlank()) return false
        val t = title.lowercase()
        return t.contains("just a moment") || t.contains("attention required") ||
            t.contains("security check") || t.contains("لحظة")
    }

    companion object {
        private const val TAG = "NovelParadise"

        // The theme does NOT use the classic lightnovel card classes on the live site
        // (verified by the on-device STRUCTURE dump in v2.9: .listupd/.lexa/.maindet all = 0).
        // Cards are <article>-based; extraction goes through series anchors directly.
        const val ANY_CARD_SELECTOR = ".listupd .lexa, .maindet, .utao .uta"

        // Density match: every real listing/search page contains series <a>s whose hrefs are
        // /np-light/series/{slug}/. Also serves as the browser.fetch content marker — the
        // challenge stub has none, so contentReady() waits out Turnstile.
        const val SERIES_ANCHOR_SELECTOR = "a[href*='/np-light/series/']"

        // Novel detail / chapter / content selectors (lightnovel theme).
        const val CPAGE_CLASS = ".epcontent"
        const val SERIES_KEY = "/np-light/series/"
        const val CHAPTER_KEY = "/chapter-"

        // Marker for detail pages: a real novel detail always links to at least one chapter.
        const val CONTENT_MARKER_SELECTOR = "a[href*='$CHAPTER_KEY']"

        private val AD_TEXT_MARKERS = listOf("advertise here", "advertisement", "ad space", "ad start", "ad end")
        private val MANGAS_EMPTY = MangasPageInfo(emptyList(), false)
    }
}