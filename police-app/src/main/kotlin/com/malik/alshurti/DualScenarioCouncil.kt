package com.malik.alshurti

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

        // Human realism guard: avoid making several people walk at exactly the same instant.
        val simultaneousWalkers = filtered.count { it.channel == AnimationChannel.LOCOMOTION && it.delayMs < 250L }
        if (simultaneousWalkers > 2) return null

        // No artificial ambience bed. All sounds must be tied to a physical event/zone.
        val safeSounds = plan.sounds.filter {
            it.gain in 0f..0.55f && it.delayMs >= 0L &&
                (it.sound != OfficeSoundId.DISTANT_STAFF_SPEECH || !it.spokenLine.isNullOrBlank())
        }

        return plan.copy(commands = filtered, sounds = safeSounds)
    }
}

/**
 * Exactly two AI planners are consulted. Rather than taking the first valid response, the council
 * scores both plans for continuity, physical plausibility and low repetition. The deterministic
 * LivingOfficeWorld remains the offline/failure fallback.
 */
class DualScenarioCouncil(
    private val continuityPlanner: ScenarioProvider,
    private val realismPlanner: ScenarioProvider,
    private val validator: ScenarioPlanValidator = ScenarioPlanValidator()
) {
    private val recent = ArrayDeque<RuntimeScenarioPlan>()

    suspend fun next(context: SceneContext): RuntimeScenarioPlan? {
        val snapshot = recent.toList()
        val candidates = listOfNotNull(
            continuityPlanner.propose(context, snapshot),
            realismPlanner.propose(context, snapshot)
        ).mapNotNull { validator.validate(it, context) }

        val selected = candidates.maxByOrNull { score(it) }
        selected?.let {
            recent.addLast(it)
            while (recent.size > 8) recent.removeFirst()
        }
        return selected
    }

    private fun score(candidate: RuntimeScenarioPlan): Int {
        var score = 100
        val signature = signature(candidate)

        // Strong penalty for repeating the same actor/animation choreography.
        recent.forEachIndexed { index, previous ->
            if (signature(previous) == signature) score -= 80 - index * 6
        }

        // Multiple independent actors make the room feel alive, but cap complexity.
        val actors = candidate.commands.map { it.actor }.distinct()
        score += actors.size.coerceAtMost(5) * 7

        // Staggered actions read more naturally than synchronized robotic movement.
        val distinctDelays = candidate.commands.map { it.delayMs / 250L }.distinct().size
        score += distinctDelays.coerceAtMost(6) * 4

        // Prefer subtle background sound tied to the scenario; penalize excessive audio clutter.
        score += candidate.sounds.size.coerceAtMost(3) * 3
        if (candidate.sounds.size > 5) score -= 12

        // Camera attention must be earned by actual interaction, not used as an idle spectacle.
        val cameraLooks = candidate.commands.count {
            it.actor == SceneActorId.POLICE_DOG && it.clip == "LookAtCamera"
        }
        score -= cameraLooks * 10

        // Too many locomotion commands at once looks staged.
        val walkers = candidate.commands.count { it.channel == AnimationChannel.LOCOMOTION }
        if (walkers > 2) score -= (walkers - 2) * 15
        return score
    }

    private fun signature(plan: RuntimeScenarioPlan): List<Triple<SceneActorId, AnimationChannel, String>> =
        plan.commands.map { Triple(it.actor, it.channel, it.clip) }
}
