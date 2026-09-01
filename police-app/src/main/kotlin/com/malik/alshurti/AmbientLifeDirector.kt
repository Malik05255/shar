package com.malik.alshurti

import kotlin.random.Random

/**
 * Non-dialogue office-life scheduler used while the child is silent.
 *
 * These beats never generate speech and never pretend that a real police event is happening. They
 * simply keep the fictional office visually occupied so the dog behaves like a working character
 * instead of staring into the camera waiting for input.
 */
class AmbientLifeDirector(seed: Long = System.nanoTime()) {
    enum class Beat {
        REVIEW_FILE,
        CHECK_PHONE,
        CHECK_DOOR
    }

    private var random = Random(seed)
    private var lastBeat: Beat? = null
    private var majorCooldown = 0

    fun reset(seed: Long = System.nanoTime()) {
        random = Random(seed)
        lastBeat = null
        majorCooldown = 0
    }

    fun nextBeat(): Beat {
        if (majorCooldown > 0) majorCooldown -= 1

        val candidates = buildList {
            // Reviewing paperwork is the normal desk activity and therefore intentionally common.
            add(Beat.REVIEW_FILE)
            add(Beat.REVIEW_FILE)
            add(Beat.REVIEW_FILE)
            if (majorCooldown == 0) {
                add(Beat.CHECK_PHONE)
                add(Beat.CHECK_DOOR)
            }
        }

        var selected = candidates[random.nextInt(candidates.size)]
        repeat(5) {
            if (selected != lastBeat || candidates.distinct().size == 1) return@repeat
            selected = candidates[random.nextInt(candidates.size)]
        }

        if (selected != Beat.REVIEW_FILE) majorCooldown = random.nextInt(2, 5)
        lastBeat = selected
        return selected
    }

    fun holdAfterReviewMs(): Long = random.nextLong(1_600L, 4_200L)
    fun pauseBetweenBeatsMs(): Long = random.nextLong(450L, 1_450L)
}
