package com.example.lixing.domain.planning

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Inclusive numeric content interval, scoped by resource, chapter and learning round. */
@Serializable
data class ContentInterval(val first: Int, val last: Int) {
    init { require(first > 0 && last >= first) }
    val size: Int get() = last - first + 1
}

@Serializable
data class ContentSelection(
    val version: Int = 1,
    val resourceName: String = "",
    val edition: String = "",
    val chapter: String = "",
    val section: String = "",
    /** QUESTION, PAGE or UNIT. */
    val kind: String = "UNIT",
    val intervals: List<ContentInterval> = emptyList(),
    val unitIds: List<String> = emptyList(),
    val roundKey: String = "FIRST",
) {
    init { require(version == 1) }

    fun quantity(): Int = IntervalMath.merge(intervals).sumOf { it.size }

    fun displayText(): String = buildList {
        resourceName.takeIf { it.isNotBlank() }?.let(::add)
        chapter.takeIf { it.isNotBlank() }?.let(::add)
        section.takeIf { it.isNotBlank() }?.let(::add)
        if (intervals.isNotEmpty()) {
            val prefix = if (kind == "PAGE") "页" else "题"
            add(IntervalMath.merge(intervals).joinToString("、") { interval ->
                if (interval.first == interval.last) "$prefix${interval.first}"
                else "$prefix${interval.first}–${interval.last}"
            })
        }
    }.joinToString(" · ")
}

object IntervalMath {
    fun intersect(left: List<ContentInterval>, right: List<ContentInterval>): List<ContentInterval> = buildList {
        for (a in merge(left)) for (b in merge(right)) {
            val first = maxOf(a.first, b.first)
            val last = minOf(a.last, b.last)
            if (first <= last) add(ContentInterval(first, last))
        }
    }
    fun merge(input: List<ContentInterval>): List<ContentInterval> {
        val output = ArrayList<ContentInterval>()
        for (interval in input.sortedWith(compareBy({ it.first }, { it.last }))) {
            val last = output.lastOrNull()
            if (last != null && interval.first.toLong() <= last.last.toLong() + 1) {
                output[output.lastIndex] = ContentInterval(last.first, maxOf(last.last, interval.last))
            } else output += interval
        }
        return output
    }

    fun remaining(planned: List<ContentInterval>, completed: List<ContentInterval>): List<ContentInterval> {
        val done = merge(completed)
        val remaining = ArrayList<ContentInterval>()
        for (range in merge(planned)) {
            var next = range.first.toLong()
            for (finished in done) {
                if (finished.last.toLong() < next || finished.first > range.last) continue
                if (finished.first.toLong() > next) remaining += ContentInterval(next.toInt(), finished.first - 1)
                next = maxOf(next, finished.last.toLong() + 1)
                if (next > range.last) break
            }
            if (next <= range.last) remaining += ContentInterval(next.toInt(), range.last)
        }
        return remaining
    }
}

object ContentSelectionCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(content: ContentSelection): String = json.encodeToString(content)
    fun decode(raw: String): ContentSelection? =
        raw.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<ContentSelection>(it) }.getOrNull() }
}
