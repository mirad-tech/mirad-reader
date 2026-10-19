package com.mirad.reader.data.imports

/**
 * 从外部 Intent（`ACTION_VIEW` / `ACTION_SEND`）里挑出可导入的文件 URI。
 *
 * 纯逻辑（不依赖 Android 类型），便于 JVM 单测；Activity 负责把 Intent 拆成这些参数，
 * 再把返回的字符串转回 `Uri` 交给 [ImportPipeline]。
 */
object ExternalImportUris {

    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"

    /** `Intent.FLAG_GRANT_READ_URI_PERMISSION` 的常量值，写死以免把 Android 依赖带进 JVM 单测。 */
    const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001

    /** Intent 是否声明了临时读授权；声明后 data / ClipData 里的 URI 都在授权范围内。 */
    fun carriesReadGrant(flags: Int): Boolean = (flags and FLAG_GRANT_READ_URI_PERMISSION) != 0

    /** 规范化并保序去重（先出现的优先），供"先试有授权的、再试其余的"使用。 */
    fun prioritize(uris: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        uris.filter { it.isNotBlank() }.forEach { out.add(it) }
        return out.toList()
    }

    /**
     * @param action Intent action
     * @param data `intent.data`（VIEW 的文件 URI）
     * @param clipUris `ClipData` 里的 URI（部分分享方只放这里）
     * @param streamUris `EXTRA_STREAM` 展开后的 URI（可能是单个也可能是多个）
     * @param text `EXTRA_TEXT`；仅用于判断"这是纯文字分享"，不当作文件
     * @return 去重后的 `content://` / `file://` URI 列表；不是这两种 scheme 的一律忽略
     */
    fun collect(
        action: String?,
        data: String?,
        clipUris: List<String>,
        streamUris: List<String>,
        text: String?
    ): List<String> {
        if (action != ACTION_VIEW && action != ACTION_SEND) return emptyList()
        val out = LinkedHashSet<String>()
        if (action == ACTION_VIEW && !data.isNullOrBlank()) out.add(data)
        if (action == ACTION_SEND) {
            out.addAll(streamUris.filter { it.isNotBlank() })
            // 有些分享方只填 ClipData，不填 EXTRA_STREAM
            out.addAll(clipUris.filter { it.isNotBlank() })
        }
        // 纯文字分享（只有 EXTRA_TEXT）不是文件导入，不要伪造成功
        if (out.isEmpty() && !text.isNullOrBlank()) return emptyList()
        return out.filter { it.startsWith("content://") || it.startsWith("file://") }
    }
}
