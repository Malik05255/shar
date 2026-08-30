package com.malik.alshurti

import kotlin.random.Random

/**
 * Session-local cinematic scheduler with a semantic gate.
 *
 * Selection is not random spectacle. The current conversation context constrains the candidate
 * set first; randomness is used only to vary timing among scenes that already match that context.
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
        SceneContextRegistry.reset()
    }

    fun shouldStandForReply(completedPoliceTurns: Int, mood: DogMood): Boolean {
        val context = SceneContextRegistry.snapshot()
        val focusLocked = mood == DogMood.SERIOUS || context.domain in FOCUS_LOCKED_DOMAINS

        // A safety/fear/conflict exchange should not turn into a visual showcase.
        if (focusLocked) return false

        // The first normal child reply still demonstrates that the character is physically alive.
        if (completedPoliceTurns == 1) {
            nextStandingTurn = completedPoliceTurns + random.nextInt(7, 11)
            return true
        }

        if (completedPoliceTurns < nextStandingTurn) return false

        nextStandingTurn = completedPoliceTurns + random.nextInt(7, 11)
        return true
    }

    fun nextBeat(mood: DogMood): Beat {
        tickCooldowns()
        val context = SceneContextRegistry.snapshot()
        val focusLocked = mood == DogMood.SERIOUS || context.domain in FOCUS_LOCKED_DOMAINS

        if (focusLocked) {
            // Consume any literal cue so it cannot leak into a later unrelated turn. In a focused
            // exchange the child remains the only priority; no door/phone/approach interruption.
            SceneContextRegistry.consumeExplicitCue()
            majorEventGap = (majorEventGap - 1).coerceAtLeast(0)
            return remember(
                if (context.domain == ConversationDomain.SAFETY) Beat.QUIET
                else if (random.nextInt(5) == 0) Beat.PAPER
                else Beat.QUIET
            )
        }

        if (majorEventGap > 0) {
            SceneContextRegistry.consumeExplicitCue()
            majorEventGap -= 1
            return remember(quietOrPaper(context))
        }

        val explicit = SceneContextRegistry.consumeExplicitCue()
        val explicitBeat = explicit.toBeatOrNull()
        if (explicitBeat != null && isAllowed(explicitBeat, context) && isAvailable(explicitBeat)) {
            registerMajorIfNeeded(explicitBeat)
            return remember(explicitBeat)
        }

        val candidates = candidatesFor(context)
        var selected = candidates[random.nextInt(candidates.size)]
        repeat(4) {
            if (selected == Beat.QUIET || selected != lastBeat) return@repeat
            selected = candidates[random.nextInt(candidates.size)]
        }

        registerMajorIfNeeded(selected)
        return remember(selected)
    }

    private fun candidatesFor(context: SceneContext): List<Beat> = buildList {
        when (context.domain) {
            ConversationDomain.SCHOOL -> {
                add(Beat.QUIET)
                add(Beat.PAPER)
                add(Beat.PAPER)
                add(Beat.PAPER)
            }
            ConversationDomain.FAMILY,
            ConversationDomain.BEHAVIOR -> {
                add(Beat.QUIET)
                add(Beat.QUIET)
                add(Beat.PAPER)
            }
            ConversationDomain.PLAYFUL -> {
                add(Beat.QUIET)
                add(Beat.PAPER)
                if (isAvailable(Beat.APPROACH)) {
                    add(Beat.APPROACH)
                    add(Beat.APPROACH)
                }
                if (isAvailable(Beat.PHONE)) add(Beat.PHONE)
                if (isAvailable(Beat.DOOR)) add(Beat.DOOR)
            }
            ConversationDomain.SAFETY,
            ConversationDomain.CONFLICT,
            ConversationDomain.EMOTIONAL -> {
                // Normally handled by focusLocked above. Keep this branch defensive.
                add(Beat.QUIET)
                add(Beat.PAPER)
            }
            ConversationDomain.GENERAL -> {
                add(Beat.QUIET)
                add(Beat.QUIET)
                add(Beat.PAPER)
                if (isAvailable(Beat.PHONE)) add(Beat.PHONE)
                if (isAvailable(Beat.APPROACH)) add(Beat.APPROACH)
                if (isAvailable(Beat.DOOR)) add(Beat.DOOR)
            }
        }
    }

    private fun quietOrPaper(context: SceneContext): Beat = when (context.domain) {
        ConversationDomain.SCHOOL -> if (random.nextInt(4) == 0) Beat.QUIET else Beat.PAPER
        else -> if (random.nextBoolean()) Beat.QUIET else Beat.PAPER
    }

    private fun isAllowed(beat: Beat, context: SceneContext): Boolean {
        if (beat == Beat.QUIET || beat == Beat.PAPER) return true
        if (context.suppressMajorEvents) return false
        if (beat == Beat.APPROACH && context.suppressApproach) return false
        return context.domain == ConversationDomain.GENERAL || context.domain == ConversationDomain.PLAYFUL
    }

    private fun isAvailable(beat: Beat): Boolean =
        beat !in MAJOR_BEATS || cooldowns.getValue(beat) == 0

    private fun registerMajorIfNeeded(beat: Beat) {
        if (beat !in MAJOR_BEATS) return
        cooldowns[beat] = random.nextInt(3, 6)
        majorEventGap = random.nextInt(1, 3)
    }

    private fun tickCooldowns() {
        cooldowns.replaceAll { _, turns -> (turns - 1).coerceAtLeast(0) }
    }

    private fun remember(beat: Beat): Beat {
        lastBeat = beat
        return beat
    }

    private fun ExplicitSceneCue.toBeatOrNull(): Beat? = when (this) {
        ExplicitSceneCue.NONE -> null
        ExplicitSceneCue.PAPER -> Beat.PAPER
        ExplicitSceneCue.PHONE -> Beat.PHONE
        ExplicitSceneCue.DOOR -> Beat.DOOR
        ExplicitSceneCue.APPROACH -> Beat.APPROACH
    }

    private companion object {
        val MAJOR_BEATS = setOf(Beat.PHONE, Beat.APPROACH, Beat.DOOR)
        val FOCUS_LOCKED_DOMAINS = setOf(
            ConversationDomain.SAFETY,
            ConversationDomain.CONFLICT,
            ConversationDomain.EMOTIONAL
        )
    }
}
