package com.stickerstudio

internal object AnimationEncoding {
    // Give a near-budget 8 fps clip a quality-only fallback before reducing
    // motion again. Correct absolute sampling emits more frames than before.
    val attempts = listOf(85f to 20, 65f to 15, 45f to 12, 30f to 8, 25f to 8, 15f to 6)

    // A grossly oversized result is unlikely to fit with one small quality step.
    // Keep the high-quality first attempt for simple artwork, and always retain
    // the final fallback rather than giving up early on difficult inputs.
    fun nextAttempt(current: Int, bytes: Int): Int {
        val step = if (bytes.toLong() > Limits.ANIMATED_BYTES * 4L) 2 else 1
        return minOf(current + step, attempts.lastIndex)
    }
}

/** Select frames against an absolute clock, without accumulating rounding drift. */
internal class AnimationSampler(private val start: Int, private val fps: Int) {
    private var nextIndex = 0L

    init {
        require(fps in 1..20)
    }

    fun select(frameEnd: Int): Boolean {
        val elapsed = (frameEnd.toLong() - start) * fps
        if (elapsed <= nextIndex * 1000L) return false
        // All target instants covered by this source frame have been consumed.
        // Emit it once, preserving low-rate and variable-delay source frames.
        nextIndex = (elapsed + 999L) / 1000L
        return true
    }
}
