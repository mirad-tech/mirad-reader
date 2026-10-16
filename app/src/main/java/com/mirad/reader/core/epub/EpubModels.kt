package com.mirad.reader.core.epub

import com.mirad.reader.core.model.TocNode

/** 清单项（manifest item）。 */
data class EpubManifestItem(
    val id: String,
    /** zip 内规范化相对路径（已 URL 解码、去掉锚点）。 */
    val href: String,
    val mediaType: String,
    val properties: String?
)

/** spine 中的一项，即阅读顺序中的一章。 */
data class EpubSpineItem(
    val index: Int,
    val idref: String,
    val href: String,
    val mediaType: String
)

/** 解析结果。 */
data class EpubBookInfo(
    val title: String?,
    val author: String?,
    val language: String?,
    val publisher: String?,
    val description: String?,
    /** OPF 在 zip 内的路径。 */
    val opfPath: String,
    /** OPF 所在目录（zip 内前缀，可能为空）。 */
    val opfRoot: String,
    val spine: List<EpubSpineItem>,
    /** 封面图片的 zip 内路径。 */
    val coverHref: String?,
    /** 目录树。 */
    val toc: List<TocNode>,
    /** spineIndex → 章标题（来自目录或正文首个标题）。 */
    val chapterTitles: Map<Int, String>
) {
    val totalChapters: Int get() = spine.size
}

class EpubParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
