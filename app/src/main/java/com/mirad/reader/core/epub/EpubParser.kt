package com.mirad.reader.core.epub

import com.mirad.reader.core.html.Html
import com.mirad.reader.core.model.TocJson
import com.mirad.reader.core.model.TocNode
import com.mirad.reader.core.text.ChapterRules
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * EPUB 解析器：container.xml → OPF（metadata/manifest/spine）→ 目录（EPUB3 nav / EPUB2 NCX）。
 *
 * 只读取 OPF、目录与封面，正文 XHTML 交给 WebView 渲染，因此不会破坏原书排版与图片。
 */
class EpubParser(private val files: ZipEntryReader) {

    fun parse(): EpubBookInfo {
        val containerText = files.readText("META-INF/container.xml")
            ?: throw EpubParseException("不是有效的 EPUB：缺少 META-INF/container.xml")
        val containerDoc = runCatching { parseXml(containerText) }
            .getOrElse { throw EpubParseException("container.xml 解析失败", it) }
        val rootfile = descendants(containerDoc.documentElement, "rootfile").firstOrNull()
            ?: throw EpubParseException("container.xml 缺少 rootfile 节点")
        val opfPath = rootfile.getAttribute("full-path").takeIf { it.isNotBlank() }
            ?: throw EpubParseException("rootfile 缺少 full-path")

        val opfText = files.readText(opfPath, maxBytes = 8 * 1024 * 1024)
            ?: throw EpubParseException("找不到 OPF 文件：$opfPath")
        val opfDoc = runCatching { parseXml(opfText) }
            .getOrElse { throw EpubParseException("OPF 解析失败：$opfPath", it) }
        val pkg = opfDoc.documentElement
        val opfRoot = ZipEntryReader.normalize(opfPath).substringBeforeLast('/', "")

        // metadata
        val metadata = descendants(pkg, "metadata").firstOrNull()
        val title = metadata?.let { firstElementText(it, "title") }
        val author = metadata?.let { firstElementText(it, "creator") }
        val language = metadata?.let { firstElementText(it, "language") }
        val publisher = metadata?.let { firstElementText(it, "publisher") }
        val description = metadata?.let { firstElementText(it, "description") }

        // manifest
        val manifestEl = descendants(pkg, "manifest").firstOrNull()
        val manifest = (manifestEl?.let { childElements(it, "item") } ?: emptyList()).mapNotNull { el ->
            val id = attr(el, "id") ?: return@mapNotNull null
            val href = attr(el, "href") ?: return@mapNotNull null
            EpubManifestItem(
                id = id,
                href = ZipEntryReader.resolve(opfRoot, href),
                mediaType = attr(el, "media-type") ?: guessMediaType(href),
                properties = attr(el, "properties")
            )
        }
        val byId = manifest.associateBy { it.id }

        // spine
        val spineEl = descendants(pkg, "spine").firstOrNull()
        val itemRefs = spineEl?.let { childElements(it, "itemref") } ?: emptyList()
        val spine = itemRefs.mapNotNull { el ->
            val idref = attr(el, "idref") ?: return@mapNotNull null
            val item = byId[idref] ?: return@mapNotNull null
            EpubSpineItem(index = -1, idref = idref, href = item.href, mediaType = item.mediaType)
        }.mapIndexed { i, item -> item.copy(index = i) }

        if (spine.isEmpty()) {
            throw EpubParseException("EPUB 的 spine 为空，无法确定阅读顺序")
        }

        val tocId = spineEl?.let { attr(it, "toc") }
        val toc = buildToc(manifest, byId, spine, tocId)
        val chapterTitles = buildChapterTitles(toc, spine)

        return EpubBookInfo(
            title = title?.takeIf { it.isNotBlank() },
            author = author?.takeIf { it.isNotBlank() },
            language = language?.takeIf { it.isNotBlank() },
            publisher = publisher?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            opfPath = ZipEntryReader.normalize(opfPath),
            opfRoot = opfRoot,
            spine = spine,
            coverHref = findCover(metadata, manifest),
            toc = toc,
            chapterTitles = chapterTitles
        )
    }

    // ------------------------------------------------------------------ 目录

    private fun buildToc(
        manifest: List<EpubManifestItem>,
        byId: Map<String, EpubManifestItem>,
        spine: List<EpubSpineItem>,
        tocId: String?
    ): List<TocNode> {
        // EPUB3：properties 含 nav 的 XHTML
        val navItem = manifest.firstOrNull { item ->
            item.properties?.split(Regex("[\\s,]+"))?.any { it.equals("nav", true) } == true
        }
        if (navItem != null) {
            val navDoc = files.readText(navItem.href, maxBytes = 2 * 1024 * 1024)
                ?.let { runCatching { parseXml(it) }.getOrNull() }
            if (navDoc != null) {
                val navs = descendants(navDoc.documentElement, "nav")
                val tocNav = navs.firstOrNull { el ->
                    attr(el, "type")?.split(Regex("\\s+"))?.any { it.equals("toc", true) } == true
                } ?: navs.firstOrNull()
                val list = tocNav?.let { childElements(it, "ol").firstOrNull() ?: descendants(it, "ol").firstOrNull() }
                if (list != null) {
                    val nodes = parseNavList(list, navItem.href.substringBeforeLast('/', ""), spine, 0)
                    if (nodes.isNotEmpty()) return nodes
                }
            }
        }

        // EPUB2：NCX
        val ncxItem = tocId?.let { byId[it] }
            ?: manifest.firstOrNull { it.mediaType.contains("ncx", true) || it.href.endsWith(".ncx", true) }
        if (ncxItem != null) {
            val ncxDoc = files.readText(ncxItem.href, maxBytes = 4 * 1024 * 1024)
                ?.let { runCatching { parseXml(it) }.getOrNull() }
            if (ncxDoc != null) {
                val navMap = descendants(ncxDoc.documentElement, "navMap").firstOrNull()
                if (navMap != null) {
                    val nodes = parseNavPoints(navMap, ncxItem.href.substringBeforeLast('/', ""), spine, 0)
                    if (nodes.isNotEmpty()) return nodes
                }
            }
        }
        return emptyList()
    }

    private fun parseNavList(
        list: Element,
        baseDir: String,
        spine: List<EpubSpineItem>,
        depth: Int
    ): List<TocNode> {
        val result = ArrayList<TocNode>()
        for (li in childElements(list, "li")) {
            val link = childElements(li, "a").firstOrNull() ?: childElements(li, "span").firstOrNull()
            val title = link?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                ?: li.textContent?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue
            val childList = childElements(li, "ol").firstOrNull()
            val children = childList?.let { parseNavList(it, baseDir, spine, depth + 1) } ?: emptyList()
            val (path, anchor) = splitHref(link?.let { attr(it, "href") }, baseDir)
            result.add(
                TocNode(
                    title = title,
                    href = path,
                    spineIndex = spineIndexOf(path, spine),
                    anchor = anchor,
                    depth = depth,
                    isVolume = isVolumeNode(title, depth, children),
                    children = children
                )
            )
        }
        return result
    }

    private fun parseNavPoints(
        parent: Element,
        baseDir: String,
        spine: List<EpubSpineItem>,
        depth: Int
    ): List<TocNode> {
        val result = ArrayList<TocNode>()
        for (navPoint in childElements(parent, "navPoint")) {
            val label = childElements(navPoint, "navLabel").firstOrNull()
            val title = label?.let { firstElementText(it, "text") }?.trim().orEmpty()
            val contentEl = childElements(navPoint, "content").firstOrNull()
                ?: descendants(navPoint, "content").firstOrNull()
            val (path, anchor) = splitHref(contentEl?.let { attr(it, "src") }, baseDir)
            val children = parseNavPoints(navPoint, baseDir, spine, depth + 1)
            if (title.isBlank() && children.isEmpty()) continue
            result.add(
                TocNode(
                    title = title.ifBlank { "未命名" },
                    href = path,
                    spineIndex = spineIndexOf(path, spine),
                    anchor = anchor,
                    depth = depth,
                    isVolume = isVolumeNode(title, depth, children),
                    children = children
                )
            )
        }
        return result
    }

    /**
     * 仅当「顶层 + 有子节点 + 标题像卷/部/篇」时才视为卷，避免把"第一章（含小节）"误判成卷，
     * 否则章标题会被子节标题覆盖。
     */
    private fun isVolumeNode(title: String, depth: Int, children: List<TocNode>): Boolean =
        depth == 0 && children.isNotEmpty() && ChapterRules.CN_VOLUME_RULE.matches(title)

    private fun splitHref(href: String?, baseDir: String): Pair<String?, String?> {
        if (href.isNullOrBlank()) return null to null
        val anchor = href.substringAfter('#', "").takeIf { it.isNotBlank() }
        val path = ZipEntryReader.resolve(baseDir, href)
        return path.ifBlank { null } to anchor
    }

    private fun spineIndexOf(path: String?, spine: List<EpubSpineItem>): Int {
        if (path.isNullOrBlank()) return -1
        return spine.indexOfFirst { it.href.equals(path, ignoreCase = true) }
    }

    /** spineIndex → 章标题；目录缺失时退化为文件内首个标题或"第 N 节"。 */
    private fun buildChapterTitles(toc: List<TocNode>, spine: List<EpubSpineItem>): Map<Int, String> {
        val map = LinkedHashMap<Int, String>()
        for (node in TocJson.flatten(toc)) {
            if (node.spineIndex < 0 || node.isVolume) continue
            if (map.containsKey(node.spineIndex)) continue
            if (node.title.isNotBlank()) map[node.spineIndex] = node.title
        }
        val needFallback = spine.indices.any { !map.containsKey(it) }
        if (needFallback) {
            val cheap = spine.size <= 400
            for (item in spine) {
                if (map.containsKey(item.index)) continue
                val fallbackTitle = if (cheap) {
                    files.readText(item.href, maxBytes = 64 * 1024)?.let { Html.firstHeadingText(it) }
                } else {
                    null
                }
                map[item.index] = fallbackTitle ?: "第 ${item.index + 1} 节"
            }
        }
        return map
    }

    // ------------------------------------------------------------------ 其他

    private fun findCover(metadata: Element?, manifest: List<EpubManifestItem>): String? {
        val coverId = metadata?.let { meta ->
            descendants(meta, "meta").firstOrNull { el ->
                attr(el, "name")?.equals("cover", ignoreCase = true) == true
            }?.let { attr(it, "content") }
        }
        if (coverId != null) {
            manifest.firstOrNull { it.id == coverId }?.let { return it.href }
        }
        manifest.firstOrNull { item ->
            item.properties?.split(Regex("[\\s,]+"))?.any { it.equals("cover-image", true) } == true
        }?.let { return it.href }
        manifest.firstOrNull { item ->
            item.mediaType.startsWith("image/") &&
                item.href.substringAfterLast('/').startsWith("cover", ignoreCase = true)
        }?.let { return it.href }
        return null
    }

    private fun guessMediaType(href: String): String = when (href.substringAfterLast('.', "").lowercase()) {
        "xhtml", "html", "htm" -> "application/xhtml+xml"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ncx" -> "application/x-dtbncx+xml"
        "js" -> "text/javascript"
        else -> "application/octet-stream"
    }

    private fun parseXml(text: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setAttribute("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(StringReader(Html.sanitizeXml(text))))
    }

    private fun localName(node: Node): String = node.nodeName.substringAfterLast(':').lowercase()

    private fun attr(el: Element, name: String): String? {
        val attrs = el.attributes ?: return null
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) ?: continue
            if (a.nodeName.substringAfterLast(':').equals(name, ignoreCase = true)) return a.nodeValue
        }
        return null
    }

    private fun childElements(el: Element, name: String? = null): List<Element> {
        val wanted = name?.lowercase()
        val out = ArrayList<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (wanted == null || localName(n) == wanted)) out.add(n)
        }
        return out
    }

    private fun descendants(root: Element, name: String): List<Element> {
        val wanted = name.lowercase()
        val out = ArrayList<Element>()
        val all = root.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val n = all.item(i)
            if (n is Element && localName(n) == wanted) out.add(n)
        }
        return out
    }

    private fun firstElementText(el: Element, name: String): String? =
        descendants(el, name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() }

    companion object {
        fun parse(file: File): EpubBookInfo = ZipEntryReader.open(file).use { EpubParser(it).parse() }
    }
}
