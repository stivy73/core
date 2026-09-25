package org.simpmusic.aiservice

import kotlin.test.Test
import kotlin.test.assertEquals

class AiServiceTest {
    @Test
    fun limitsLongExplanationAtCompleteSentenceWithinTargetRange() {
        val firstPart = "a".repeat(879) + "."
        val result = limitSongExplanation(firstPart + " " + "b".repeat(200) + ".")

        assertEquals(firstPart, result)
    }

    @Test
    fun preservesLongExplanationWhenNoSafeSentenceBoundaryExists() {
        val explanation = "a".repeat(1_000)

        assertEquals(explanation, limitSongExplanation(explanation))
    }
}
