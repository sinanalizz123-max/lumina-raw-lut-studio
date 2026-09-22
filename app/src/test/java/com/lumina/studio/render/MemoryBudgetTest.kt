package com.lumina.studio.render

import com.lumina.studio.core.render.MemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {
    @Test
    fun tiffBudgetRejectsOverflowAndHugeFrames() {
        assertTrue(MemoryBudget.exceedsTiffBudget(1, 1, 1))
        assertTrue(MemoryBudget.exceedsTiffBudget(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE / 2))
    }

    @Test
    fun tiffBudgetAllowsTinyFrameWithReasonableCap() {
        assertFalse(MemoryBudget.exceedsTiffBudget(16, 16, 16L * 16L * 20L))
    }

    @Test
    fun sampleForUsesPowerOfTwoAndCannotOverflow() {
        assertEquals(1, MemoryBudget.sampleFor(1000, 1000))
        assertEquals(2, MemoryBudget.sampleFor(2001, 1000))
        assertEquals(1 shl 30, MemoryBudget.sampleFor(Int.MAX_VALUE, 1))
    }
}
