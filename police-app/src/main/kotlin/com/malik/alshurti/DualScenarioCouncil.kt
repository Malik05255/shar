package com.malik.alshurti

/**
 * Scenario AIs are planners only. They may select from the runtime 3D vocabulary, but they never
 * generate images, videos, meshes, audio, or arbitrary asset names.
 */
fun interface ScenarioProvider {
    suspend fun propose(context: SceneContext, recentPlans: List<RuntimeScenarioPlan>): RuntimeScenarioPlan?
}

class ScenarioPlanValidator {
    fun validate(plan: RuntimeScenarioPlan, context: SceneContext): RuntimeScenarioPlan? {
        if (plan.commands.isEmpty()) return null
        if (plan.durationHintMs !in 500L..30_000L) return null
        if (plan.commands.size > 6) return null

        val allowedDogClips = Runtime3DAssetCatalog.dogCoreClips
        val allowedStaffClips = Runtime3DAssetCatalog.staffCoreClips

        val safeCommands = plan.commands.filter { command ->
            when (command.actor) {
                SceneActorId.POLICE_DOG -> command.clip in allowedDogClips
                SceneActorId.STAFF_MALE_01,
                SceneActorId.STAFF_FEMALE_01 -> command.clip in allowedStaffClips
                SceneActorId.DOOR -> command.clip in setOf("OpenDoor", "CloseDoor")
                SceneActorId.PHONE -> command.clip in setOf("Ring", "Idle")
                SceneActorId.FILE -> command.clip in setOf("Idle", "MoveToDesk", "MoveToHand")
                SceneActorId.DESK,
                SceneActorId.OFFICE_SHELL -> false
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
                it.actor == SceneActorId.POLICE_DOG && it.clip in setOf("Walk", "UsePhone")
            }
        } else safeCommands

        return filtered.takeIf { it.isNotEmpty() }?.let { plan.copy(commands = it) }
    }
}

/**
 * Exactly two scenario planners are consulted. The council picks the first valid, context-safe,
 * non-repetitive plan; if both fail, the deterministic local director remains the fallback.
 */
class DualScenarioCouncil(
    private val first: ScenarioProvider,
    private val second: ScenarioProvider,
    private val validator: ScenarioPlanValidator = ScenarioPlanValidator()
) {
    private val recent = ArrayDeque<RuntimeScenarioPlan>()

    suspend fun next(context: SceneContext): RuntimeScenarioPlan? {
        val snapshot = recent.toList()
        val candidates = listOfNotNull(
            first.propose(context, snapshot),
            second.propose(context, snapshot)
        )

        val selected = candidates
            .asSequence()
            .mapNotNull { validator.validate(it, context) }
            .firstOrNull { candidate ->
                recent.none { previous ->
                    previous.commands.map { it.actor to it.clip } ==
                        candidate.commands.map { it.actor to it.clip }
                }
            }
            ?: candidates.asSequence().mapNotNull { validator.validate(it, context) }.firstOrNull()

        selected?.let {
            recent.addLast(it)
            while (recent.size > 5) recent.removeFirst()
        }
        return selected
    }
}
