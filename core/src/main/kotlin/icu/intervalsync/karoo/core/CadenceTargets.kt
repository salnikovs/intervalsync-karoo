package icu.intervalsync.karoo.core

/**
 * The steps of an Intervals.icu workout that carry a cadence target, from its text.
 *
 * Intervals.icu writes a step as a line such as `- 4m 120% 95rpm` or `- 10m 60% 85-95rpm`,
 * under section lines such as `Main Set 3x`. Karoo's own workout sync drops the cadence, so
 * the Plan tab shows these steps as written, each with its section's repeat count.
 */
object CadenceTargets {
    private val Rpm = Regex("""\b\d{2,3}(?:\s*-\s*\d{2,3})?\s*rpm\b""", RegexOption.IGNORE_CASE)
    private val Repeat = Regex("""\b(\d+)\s*x\b""", RegexOption.IGNORE_CASE)

    fun from(description: String?): List<String> {
        if (description.isNullOrBlank()) return emptyList()
        var section: String? = null
        val steps = mutableListOf<String>()
        for (raw in description.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("-")) {
                section = Repeat.find(line)?.groupValues?.get(1)?.let { "${it}×" }
                continue
            }
            if (!Rpm.containsMatchIn(line)) continue
            val step = line.removePrefix("-").trim()
            steps += listOfNotNull(section, step).joinToString(" ")
        }
        return steps
    }
}
