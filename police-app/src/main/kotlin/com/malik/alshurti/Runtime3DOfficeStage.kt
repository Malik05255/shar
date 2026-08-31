package com.malik.alshurti

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.android.filament.gltfio.Animator
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlin.math.max
import kotlin.math.min

/**
 * Persistent multi-actor 3D office.
 *
 * Models are loaded once per pack and remain in the scene. RuntimeScenarioPlan only changes
 * animation targets; there is no concept of a movie ending, seeking, replaying, or holding a final
 * video frame. Multiple clips can be applied in one render frame when the authored glTF clips touch
 * disjoint bones (for example breathing + gaze + hands), which Filament supports naturally.
 *
 * Content contract: each independent GLB is authored in the SAME office coordinate system and
 * preserves its original PBR scale/position. We deliberately do not scaleToUnits individual actors
 * because that would destroy cinematic spatial relationships.
 */
@Composable
fun Runtime3DOfficeStage(
    pack: Runtime3DContentPackManager.Pack,
    modifier: Modifier = Modifier
) {
    val frame by RuntimeOfficePlanBus.frames.collectAsState()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val bindings = remember(pack.version) { mutableMapOf<SceneActorId, RuntimeAnimatorBinding>() }

    val commandsByActor = remember(frame?.revision) {
        frame?.plan?.commands
            ?.groupBy { it.actor }
            ?.mapValues { (_, commands) ->
                commands.sortedWith(
                    compareBy<SceneAnimationCommand> { it.delayMs }
                        .thenBy { channelOrder(it.channel) }
                )
            }
            .orEmpty()
    }

    SceneView(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = null,
        autoCenterContent = true,
        autoFitContent = true,
        framingPadding = 0.035f,
        onFrame = { frameTimeNanos ->
            applyRuntimeFrame(
                frameTimeNanos = frameTimeNanos,
                planFrame = frame,
                commandsByActor = commandsByActor,
                bindings = bindings
            )
        }
    ) {
        Runtime3DAssetCatalog.actors.forEach { asset ->
            val location = pack.uriFor(asset.id) ?: return@forEach
            key(asset.id, pack.version) {
                val modelInstance = rememberModelInstance(
                    modelLoader = modelLoader,
                    fileLocation = location
                )
                modelInstance?.let { instance ->
                    val binding = remember(instance) { RuntimeAnimatorBinding(instance.animator) }
                    DisposableEffect(asset.id, binding) {
                        bindings[asset.id] = binding
                        onDispose { bindings.remove(asset.id, binding) }
                    }

                    val visible = asset.persistent || commandsByActor[asset.id].orEmpty().isNotEmpty()
                    ModelNode(
                        modelInstance = instance,
                        autoAnimate = false,
                        animationName = null,
                        animationLoop = false,
                        position = Position(0f, 0f, 0f),
                        isVisible = visible,
                        isEditable = false,
                        apply = {
                            setCastShadows(true)
                            setReceiveShadows(true)
                        }
                    )
                }
            }
        }
    }
}

private class RuntimeAnimatorBinding(val animator: Animator) {
    val animationIndexes: Map<String, Int> = buildMap {
        for (index in 0 until animator.animationCount) {
            val name = animator.getAnimationName(index)?.trim().orEmpty()
            if (name.isNotEmpty()) put(name, index)
        }
    }
}

private fun applyRuntimeFrame(
    frameTimeNanos: Long,
    planFrame: RuntimeOfficePlanBus.Frame?,
    commandsByActor: Map<SceneActorId, List<SceneAnimationCommand>>,
    bindings: Map<SceneActorId, RuntimeAnimatorBinding>
) {
    val planElapsedSeconds = if (planFrame == null) 0f else {
        max(0L, frameTimeNanos - planFrame.publishedAtNanos) / 1_000_000_000f
    }
    val absoluteSeconds = frameTimeNanos / 1_000_000_000f

    bindings.forEach { (actor, binding) ->
        val commands = commandsByActor[actor].orEmpty()
        var applied = false

        for (command in commands) {
            val delaySeconds = command.delayMs / 1_000f
            if (planElapsedSeconds < delaySeconds) continue
            val animationIndex = binding.animationIndexes[command.clip] ?: continue
            val duration = binding.animator.getAnimationDuration(animationIndex)
            val localSeconds = (planElapsedSeconds - delaySeconds) * command.playbackRate
            val animationTime = when {
                duration <= 0f -> 0f
                command.loop -> localSeconds % duration
                else -> min(localSeconds, duration)
            }
            binding.animator.applyAnimation(animationIndex, animationTime)
            applied = true
        }

        if (!applied) {
            val idleName = defaultIdleClip(actor)
            val idleIndex = idleName?.let(binding.animationIndexes::get)
            if (idleIndex != null) {
                val duration = binding.animator.getAnimationDuration(idleIndex)
                val time = if (duration > 0f) absoluteSeconds % duration else 0f
                binding.animator.applyAnimation(idleIndex, time)
                applied = true
            }
        }

        if (applied) binding.animator.updateBoneMatrices()
    }
}

private fun defaultIdleClip(actor: SceneActorId): String? = when (actor) {
    SceneActorId.POLICE_DOG -> "IdleWork"
    SceneActorId.STAFF_MALE_01,
    SceneActorId.STAFF_MALE_02,
    SceneActorId.STAFF_FEMALE_01,
    SceneActorId.VISITOR_01 -> "IdleDesk"
    SceneActorId.DOOR,
    SceneActorId.PHONE,
    SceneActorId.FILE,
    SceneActorId.CHAIR,
    SceneActorId.PRINTER,
    SceneActorId.COFFEE_CUP -> "Idle"
    SceneActorId.DESK,
    SceneActorId.OFFICE_SHELL,
    SceneActorId.MONITOR,
    SceneActorId.KEYBOARD -> null
}

private fun channelOrder(channel: AnimationChannel): Int = when (channel) {
    AnimationChannel.LOCOMOTION -> 0
    AnimationChannel.BODY -> 1
    AnimationChannel.HANDS -> 2
    AnimationChannel.HEAD -> 3
    AnimationChannel.GAZE -> 4
    AnimationChannel.FACE -> 5
    AnimationChannel.PROP -> 6
}
