package com.local.mobvoin4225remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseMatchingTest {
    @Test
    fun normalizesCommonPowerliftingAliases() {
        assertEquals(
            "deadlift sumo paused",
            normalizeExerciseName("DL (Sumo, Pause)")
        )
        assertEquals(
            "squat high bar paused",
            normalizeExerciseName("Squat (HB, Paused)")
        )
        assertEquals(
            "bench competition",
            normalizeExerciseName("Bench (Comp)")
        )
    }

    @Test
    fun primaryLiftMismatchCannotAutoMatch() {
        val squat = normalizeExerciseName("Squat Low Bar")
        val deadlift = normalizeExerciseName("Deadlift Low Bar")
        assertEquals(0.0, nameSimilarity(squat, deadlift), 0.0001)
    }

    @Test
    fun closeVariantsScoreHigherThanUnrelatedAccessories() {
        val msb = normalizeExerciseName("Paused Sumo Deadlift")
        val likely = normalizeExerciseName("DL (Sumo, Paused)")
        val unrelated = normalizeExerciseName("Leg Extension")
        assertTrue(nameSimilarity(msb, likely) > nameSimilarity(msb, unrelated))
    }

    @Test
    fun dateOverlapUsesSmallerHistoryAsDenominator() {
        val a = setOf("2026-10-01", "2026-10-03", "2026-10-05")
        val b = setOf("2026-10-01", "2026-10-05")
        assertEquals(1.0, dateOverlap(a, b), 0.0001)
    }
}
