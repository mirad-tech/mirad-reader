package com.mirad.reader.preview

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** 仅预览包保留有限状态，用于验证短页面确有位移且松手/后台后归零。 */
internal class PreviewBounceDiagnostics {
    private data class Sample(var offset: Float = 0f, var peak: Float = 0f, var settling: Boolean = false)
    private val values = linkedMapOf<Int, Sample>()
    fun record(id: Int, offset: Float, settling: Boolean) {
        val value = values.getOrPut(id) { Sample() }
        value.offset = offset
        value.peak = max(value.peak, abs(offset))
        value.settling = settling
    }
    fun resetPeaks() { values.values.forEach { it.peak = abs(it.offset) } }
    fun snapshot(): JSONArray = JSONArray().apply {
        values.forEach { (id, value) -> put(JSONObject().put("id", id).put("offsetPx", value.offset)
            .put("peakPx", value.peak).put("settling", value.settling)) }
    }
}
