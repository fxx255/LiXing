package com.example.lixing.domain.planning

/** A check-in reports the current cumulative completion, not an increment. */
data class ContentProgressInput(val ranges: List<ContentInterval>? = null) {
    val isRange: Boolean get() = ranges != null
    fun quantity(fallbackCount: Int): Int = ranges?.let { IntervalMath.merge(it).sumOf(ContentInterval::size) }
        ?: fallbackCount
}

object ContentRangeText {
    private val separator = Regex("[,，、;；\\s]+")
    private val range = Regex("^(\\d+)\\s*[-–—~～至]\\s*(\\d+)$")

    fun parse(text: String): List<ContentInterval>? {
        if (text.isBlank()) return emptyList()
        val result = ArrayList<ContentInterval>()
        val normalized = text.trim().replace(Regex("\\s*([-–—~～至])\\s*"), "$1")
        for (part in normalized.split(separator).filter(String::isNotBlank)) {
            val match = range.matchEntire(part)
            val first = (match?.groupValues?.get(1) ?: part).toIntOrNull() ?: return null
            val last = (match?.groupValues?.get(2) ?: part).toIntOrNull() ?: return null
            if (first <= 0 || last < first) return null
            result += ContentInterval(first, last)
        }
        return IntervalMath.merge(result)
    }

    fun format(ranges: List<ContentInterval>): String = IntervalMath.merge(ranges)
        .joinToString("、") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
}
