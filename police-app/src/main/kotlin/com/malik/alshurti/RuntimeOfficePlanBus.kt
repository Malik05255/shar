package com.malik.alshurti

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local clock for the persistent 3D office.
 *
 * Plans are declarative choreography only. Publishing a new plan never recreates the SceneView,
 * reloads models or seeks a video: persistent actor nodes keep existing and only animation targets
 * change. This keeps the world alive independently from the conversation UI.
 */
object RuntimeOfficePlanBus {
    data class Frame(
        val revision: Long,
        val plan: RuntimeScenarioPlan,
        val publishedAtNanos: Long
    )

    private val revision = AtomicLong(0L)
    private val _frames = MutableStateFlow<Frame?>(null)
    val frames: StateFlow<Frame?> = _frames.asStateFlow()

    fun publish(plan: RuntimeScenarioPlan): RuntimeScenarioPlan {
        _frames.value = Frame(
            revision = revision.incrementAndGet(),
            plan = plan,
            publishedAtNanos = System.nanoTime()
        )
        return plan
    }

    fun clear() {
        _frames.value = null
    }
}
