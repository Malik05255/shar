package com.malik.alshurti

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local clock for the persistent 3D office.
 *
 * Plans carry scene choreography. Facial speech performance is a separate high-frequency channel so
 * visemes never recreate the SceneView or republish the whole office plan for every mouth shape.
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

    private val _viseme = MutableStateFlow(MouthViseme.REST)
    val viseme: StateFlow<MouthViseme> = _viseme.asStateFlow()

    fun publish(plan: RuntimeScenarioPlan): RuntimeScenarioPlan {
        _frames.value = Frame(
            revision = revision.incrementAndGet(),
            plan = plan,
            publishedAtNanos = System.nanoTime()
        )
        return plan
    }

    fun publishViseme(value: MouthViseme) {
        _viseme.value = value
    }

    fun clear() {
        _frames.value = null
        _viseme.value = MouthViseme.REST
    }
}
