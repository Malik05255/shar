package com.malik.alshurti

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** Scenario AIs are planners only; they never generate media or arbitrary asset names. */
fun interface ScenarioProvider {
    suspend fun propose(context: SceneContext, recentPlans: List<RuntimeScenarioPlan>): RuntimeScenarioPlan?
}

class ScenarioPlanValidator {
    fun validate(plan: RuntimeScenarioPlan, context: SceneContext): RuntimeScenarioPlan? {
        if (plan.commands.isEmpty()) return null
        if (plan.durationHintMs !in 500L..30_000L) return null
        if (plan.commands.size > 10 || plan.sounds.size > 8) return null

        val allowedDogClips = Runtime3DAssetCatalog.dogCoreClips
        val allowedStaffClips = Runtime3DAssetCatalog.staffCoreClips
        val propClips = Runtime3DAssetCatalog.propClips

        val safeCommands = plan.commands.filter { command ->
            when (command.actor) {
                SceneActorId.POLICE_DOG -> command.clip in allowedDogClips
                SceneActorId.STAFF_MALE_01,
                SceneActorId.STAFF_MALE_02,
                SceneActorId.STAFF_FEMALE_01,
                SceneActorId.VISITOR_01 -> command.clip in allowedStaffClips
                SceneActorId.DOOR,
                SceneActorId.PHONE,
                SceneActorId.FILE,
                SceneActorId.CHAIR,
                SceneActorId.PRINTER,
                SceneActorId.COFFEE_CUP -> command.clip in propClips[command.actor].orEmpty()
                SceneActorId.DESK,
                SceneActorId.OFFICE_SHELL,
                SceneActorId.MONITOR,
                SceneActorId.KEYBOARD -> false
            }
        }
        if (safeCommands.isEmpty()) return null

        val serious = context.domain in setOf(
            ConversationDomain.SAFETY,
            ConversationDomain.CONFLICT,
            ConversationDomain.EMOTIONAL
        )
        val filtered = if (serious) {
            safeCommands.filterNot {
                it.actor == SceneActorId.POLICE_DOG && it.clip in setOf("Walk", "UsePhone", "LeanBack")
            }
        } else safeCommands
        if (filtered.isEmpty()) return null

        val simultaneousWalkers = filtered.count {
            it.channel == AnimationChannel.LOCOMOTION && it.delayMs < 250L
        }
        if (simultaneousWalkers > 2) return null

        val safeSounds = plan.sounds.filter {
            it.gain in 0f..0.55f && it.delayMs >= 0L &&
                (it.sound != OfficeSoundId.DISTANT_STAFF_SPEECH || !it.spokenLine.isNullOrBlank())
        }
        return plan.copy(commands = filtered, sounds = safeSounds)
    }
}

/**
 * Exactly two AI planners are consulted in parallel. Neither is allowed to stall the office: the
 * council has a hard deadline and returns null on timeout so LivingOfficeWorld continues instantly.
 */
class DualScenarioCouncil(
    private val continuityPlanner: ScenarioProvider,
    private val realismPlanner: ScenarioProvider,
    private val validator: ScenarioPlanValidator = ScenarioPlanValidator()
) {
    private val recent = ArrayDeque<RuntimeScenarioPlan>()

    suspend fun next(context: SceneContext): RuntimeScenarioPlan? {
        val snapshot = recent.toList()
        val rawCandidates = withTimeoutOrNull(PLANNING_DEADLINE_MS) {
            supervisorScope {
                val continuity = async { continuityPlanner.propose(context, snapshot) }
                val realism = async { realismPlanner.propose(context, snapshot) }
                listOfNotNull(continuity.await(), realism.await())
            }
        }.orEmpty()

        val candidates = rawCandidates.mapNotNull { validator.validate(it, context) }
        val selected = candidates.maxByOrNull { score(it) }
        selected?.let {
            recent.addLast(it)
            while (recent.size > 8) recent.removeFirst()
        }
        return selected
    }

    fun reset() {
        recent.clear()
    }

    private fun score(candidate: RuntimeScenarioPlan): Int {
        var score = 100
        val signature = signature(candidate)
        recent.forEachIndexed { index, previous ->
            if (signature(previous) == signature) score -= 80 - index * 6
        }

        val actors = candidate.commands.map { it.actor }.distinct()
        score += actors.size.coerceAtMost(5) * 7

        val distinctDelays = candidate.commands.map { it.delayMs / 250L }.distinct().size
        score += distinctDelays.coerceAtMost(6) * 4

        score += candidate.sounds.size.coerceAtMost(3) * 3
        if (candidate.sounds.size > 5) score -= 12

        val cameraLooks = candidate.commands.count {
            it.actor == SceneActorId.POLICE_DOG && it.clip == "LookAtCamera"
        }
        score -= cameraLooks * 18

        val walkers = candidate.commands.count { it.channel == AnimationChannel.LOCOMOTION }
        if (walkers > 2) score -= (walkers - 2) * 15
        return score
    }

    private fun signature(plan: RuntimeScenarioPlan): List<Triple<SceneActorId, AnimationChannel, String>> =
        plan.commands.map { Triple(it.actor, it.channel, it.clip) }

    private companion object {
        const val PLANNING_DEADLINE_MS = 8_500L
    }
}
