package com.mirad.reader.core.model

/** Mirrors TXT h2/p text and reader.js block separators, while retaining source positions. */
class ChapterTextMap(body: String, title: String, titleLineChars: Int, isVolume: Boolean, val sourceStart: Int) {
    data class Segment(val dom: Int, val source: Int?, val text: String)
    val segments: List<Segment>
    val text: String
    init {
        val parts = mutableListOf<Segment>()
        val result = StringBuilder()
        fun add(value: String, source: Int?) {
            if (value.isEmpty()) return
            if (result.isNotEmpty()) result.append('\n')
            parts.add(Segment(result.length, source, value))
            result.append(value)
        }
        val skip = titleLineChars.takeIf { it in 1..body.length } ?: 0
        if (!isVolume && title.isNotBlank()) {
            val heading = body.take(skip).trim()
            val source = if (skip > 0 && heading == title) sourceStart + body.indexOf(title) else null
            add(title, source)
        }
        var pos = skip
        while (pos < body.length) {
            val end = body.indexOf('\n', pos).let { if (it < 0) body.length else it }
            val line = body.substring(pos, end)
            val value = line.trim()
            if (value.isNotEmpty()) add(value, sourceStart + pos + line.indexOf(value))
            pos = end + 1
        }
        segments = parts
        text = result.toString()
    }
    fun sourceAt(offset: Int): Int? {
        if (offset !in 0..text.length) return null
        val segment = segments.lastOrNull { it.dom <= offset } ?: return null
        return segment.source?.plus((offset - segment.dom).coerceAtMost(segment.text.length))
    }
    fun domAt(source: Int): Int? = segments.firstNotNullOfOrNull { segment ->
        segment.source?.let { if (source in it..(it + segment.text.length)) segment.dom + source - it else null }
    }
    data class Range(val chapter: Int, val start: Int, val end: Int, val text: String)
    /** Only emit ranges whose source characters still exist; synthetic headings are never guessed. */
    fun migrateRange(start: Int, end: Int, expected: String, targets: List<ChapterTextMap>): List<Range>? {
        if (start < 0 || end > text.length || end <= start || text.substring(start, end) != expected) return null
        val first = sourceAt(start) ?: return null
        val last = sourceAt(end) ?: return null
        val result = targets.mapIndexedNotNull { index, target ->
            val covered = target.segments.mapNotNull { segment ->
                val raw = segment.source ?: return@mapNotNull null
                val lo = maxOf(first, raw)
                val hi = minOf(last, raw + segment.text.length)
                if (hi <= lo) null else (segment.dom + lo - raw) to (segment.dom + hi - raw)
            }
            if (covered.isEmpty()) null else {
                val lo = covered.first().first
                val hi = covered.last().second
                Range(index, lo, hi, target.text.substring(lo, hi))
            }
        }
        // Block boundaries may move, but non-whitespace source text must remain identical.
        fun compact(s: String) = s.filterNot { it.isWhitespace() }
        return result.takeIf { it.isNotEmpty() && compact(it.joinToString("") { r -> r.text }) == compact(expected) }
    }
}
