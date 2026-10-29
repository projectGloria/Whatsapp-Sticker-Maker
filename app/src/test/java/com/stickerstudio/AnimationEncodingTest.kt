package com.stickerstudio

import org.junit.Assert.*
import org.junit.Test

class AnimationEncodingTest {
    @Test fun absoluteSamplingDoesNotCollapseFifteenFpsToTen() {
        for (fps in listOf(20, 15, 12, 8, 6)) {
            val sampler = AnimationSampler(0, fps)
            assertEquals(fps, (50..1000 step 50).count { sampler.select(it) })
        }
    }

    @Test fun variableDelayFramesAreNotDuplicated() {
        val sampler = AnimationSampler(0, 20)
        assertTrue(sampler.select(80))
        assertTrue(sampler.select(200))
        assertTrue(sampler.select(400))
    }

    @Test fun trimmedSamplingStartsInsideTheFrameCoveringTheTrim() {
        val sampler = AnimationSampler(125, 15)
        assertFalse(sampler.select(80))
        assertFalse(sampler.select(125))
        assertTrue(sampler.select(200))
        assertFalse(sampler.select(250))
        assertTrue(sampler.select(300))
    }

    @Test fun roundingDoesNotLoseVideoFrames() {
        for (fps in listOf(20, 15, 12, 8, 6)) {
            val sampler = AnimationSampler(123, fps)
            assertEquals(fps, (1..fps).count { sampler.select(123 + it * 1000 / fps) })
        }
    }

    @Test fun adaptiveRetriesKeepTheLastFallback() {
        assertEquals(1, AnimationEncoding.nextAttempt(0, Limits.ANIMATED_BYTES + 1))
        assertEquals(2, AnimationEncoding.nextAttempt(0, Limits.ANIMATED_BYTES * 5))
        assertEquals(AnimationEncoding.attempts.lastIndex, AnimationEncoding.nextAttempt(4, Int.MAX_VALUE))
    }
}
