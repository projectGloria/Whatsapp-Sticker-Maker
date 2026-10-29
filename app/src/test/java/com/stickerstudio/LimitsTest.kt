package com.stickerstudio

import org.junit.Assert.*
import org.junit.Test

class LimitsTest {
    @Test
    fun draftAndFullPackBoundaries() {
        assertEquals(2, Limits.issues(0, false).size)
        assertTrue(Limits.issues(2, true).single().contains("1 more"))
        assertTrue(Limits.issues(3, true).isEmpty())
        assertTrue(Limits.issues(30, true).isEmpty())
        assertTrue(Limits.issues(31, true).single().contains("30"))
        assertTrue(Limits.issues(3, true, false).single().contains("missing"))
    }
}
