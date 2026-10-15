package icu.intervalsync.karoo.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CadenceTargetsTest {
    @Test
    fun keepsOnlyStepsWithACadenceAndTheirRepeatCount() {
        val description = """
            Warmup
            - 10m 60% 90rpm
            - 5m 65%

            Main Set 3x
            - 4m 105% 75-80rpm
            - 3m 50%

            Cooldown
            - 5m 55%
        """.trimIndent()
        assertEquals(listOf("10m 60% 90rpm", "3× 4m 105% 75-80rpm"), CadenceTargets.from(description))
    }

    @Test
    fun nothingWithoutCadence() {
        assertEquals(emptyList(), CadenceTargets.from("- 25m 195w\n- 3m 215w"))
        assertEquals(emptyList(), CadenceTargets.from(null))
    }
}
