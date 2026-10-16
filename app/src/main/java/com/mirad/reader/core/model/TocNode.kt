package com.mirad.reader.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** EPUB 目录节点（嵌套）。 */
@Serializable
data class TocNode(
    val title: String,
    /** zip 内相对路径（已去除锚点）。 */
    val href: String? = null,
    /** 对应 spine 序号，定位用；无法解析时为 -1。 */
    val spineIndex: Int = -1,
    val anchor: String? = null,
    val depth: Int = 0,
    val isVolume: Boolean = false,
    val children: List<TocNode> = emptyList()
)

object TocJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(nodes: List<TocNode>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TocNode.serializer()), nodes)

    fun decode(text: String?): List<TocNode> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TocNode.serializer()), text)
        }.getOrElse { emptyList() }
    }

    /** 深度优先展开为扁平列表（含层级）。 */
    fun flatten(nodes: List<TocNode>): List<TocNode> {
        val out = ArrayList<TocNode>()
        fun walk(list: List<TocNode>) {
            for (n in list) {
                out.add(n)
                walk(n.children)
            }
        }
        walk(nodes)
        return out
    }
}
