package com.malik.alshurti

import kotlin.random.Random

/**
 * Session-local cinematic scheduler.
 *
 * It deliberately avoids a visible modulo pattern: major beats have cooldowns, a quiet gap is
 * inserted after intrusive events, and serious conversations stay visually calm. The first real
 * child reply still gets a guaranteed stand-up performance so the feature is immediately visible.
 */
class CinematicSceneDirector(seed: Long = System.nanoTime()) {
    enum class Beat {
        QUIET,
        PAPER,
        PHONE,
        APPROACH,
        DOOR
    }

    private var random = Random(seed)
    private val cooldowns = mutableMapOf(
        Beat.PHONE to 0,
        Beat.APPROACH to 0,
        Beat.DOOR to 0
    )
    private var lastBeat = Beat.QUIET
    private var majorEventGap = 0
    private var nextStandingTurn = 1

    fun reset(seed: Long = System.nanoTime()) {
        random = Random(seed)
        cooldowns.keys.forEach { cooldowns[it] = 0 }
        lastBeat = Beat.QUIET
        majorEventGap = 0
        nextStandingTurn = 1
    }

    fun shouldStandForReply(completedPoliceTurns: Int, mood: DogMood): Boolean {
        // The first child reply after the greeting is a guaranteed showcase.
        if (completedPoliceTurns == 1) {
            nextStandingTurn = completedPoliceTurns + random.nextInt(7, 11)
            return true
        }

        if (mood == DogMood.SERIOUS || completedPoliceTurns < nextStandingTurn) return false

        nextStandingTurn = completedPoliceTurns + random.nextInt(7, 11)
        return true
    }

    fun nextBeat(mood: DogMood): Beat {
        tickCooldowns()

        // Do not let office spectacle compete with an emotionally serious exchange.
        if (mood == DogMood.SERIOUS) {
            majorEventGap = (majorEventGap - 1).coerceAtLeast(0)
            return remember(if (random.nextInt(4) == 0) Beat.PAPER else Beat.QUIET)
        }

        if (majorEventGap > 0) {
            majorEventGap -= 1
            return remember(if (random.nextBoolean()) Beat.QUIET else Beat.PAPER)
        }

        val candidates = buildList {
            // Quiet is intentionally weighted highest; an office feels more real when not every
            // conversational turn triggers a spectacle.
            add(Beat.QUIET)
            add(Beat.QUIET)
            add(Beat.PAPER)
            if (cooldowns.getValue(Beat.PHONE) == 0) add(Beat.PHONE)
            if (cooldowns.getValue(Beat.APPROACH) == 0) add(Beat.APPROACH)
            if (cooldowns.getValue(Beat.DOOR) == 0) add(Beat.DOOR)
        }

        var selected = candidates[random.nextInt(candidates.size)]
        repeat(4) {
            if (selected == Beat.QUIET || selected != lastBeat) return@repeat
            selected = candidates[random.nextInt(candidates.size)]
        }

        if (selected in MAJOR_BEATS) {
            cooldowns[selected] = random.nextInt(3, 6)
            majorEventGap = random.nextInt(1, 3)
        }
        return remember(selected)
    }

    private fun tickCooldowns() {
        cooldowns.replaceAll { _, turns -> (turns - 1).coerceAtLeast(0) }
    }

    private fun remember(beat: Beat): Beat {
        lastBeat = beat
        return beat
    }

    private companion object {
        val MAJOR_BEATS = setOf(Beat.PHONE, Beat.APPROACH, Beat.DOOR)
    }
}
