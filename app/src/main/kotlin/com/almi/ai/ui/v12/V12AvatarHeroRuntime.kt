package com.almi.ai.ui.v12

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.almi.ai.data.preferences.AvatarPresentation
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.View
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

/**
 * Quality-first hero renderer used only on the v12 gender-selection stage.
 *
 * Unlike the customization editor, this does not use the old HM08 lite mannequin. It loads the
 * dedicated male/female PBR bodies and plays their authored skeleton animation. This keeps the
 * first impression realistic while the richer multi-part digital-human editor is integrated.
 */
internal class V12AvatarHeroRuntime(
    private val context: Context,
    private val surfaceView: SurfaceView,
    private val presentation: AvatarPresentation,
    private val onReady: () -> Unit = {},
) {
    companion object {
        init {
            Filament.init()
            Utils.init()
        }

        private const val FEMALE_MODEL = "almi3d/almi_body_female_v12.glb"
        private const val MALE_MODEL = "almi3d/almi_body_male_v12.glb"
        private const val READY_FRAMES = 5
    }

    private var viewer: ModelViewer? = null
    private var initialized = false
    private var running = false
    private var framePosted = false
    private var ready = false
    private var warmupFrames = 0
    private var animationIndex = -1
    private var animationStartNanos = 0L

    private val lowPowerDevice: Boolean by lazy {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true || Runtime.getRuntime().availableProcessors() <= 4
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (!running) return
            val current = viewer ?: return

            if (ready) applyAnimation(current, frameTimeNanos)
            current.render(frameTimeNanos)

            if (!ready && current.asset != null && current.progress >= .96f) {
                warmupFrames += 1
                if (warmupFrames >= READY_FRAMES) {
                    ready = true
                    animationIndex = findAnimation(current, listOf("idle", "breath", "sway"))
                    animationStartNanos = 0L
                    surfaceView.post(onReady)
                }
            }
            postFrame()
        }
    }

    fun initialize() {
        if (initialized) return
        surfaceView.setZOrderOnTop(false)
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)
        surfaceView.background = null

        if (surfaceView.holder.surface.isValid) {
            initializeOnSurface()
        } else {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceView.post { initializeOnSurface() }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
    }

    private fun initializeOnSurface() {
        if (initialized || !surfaceView.holder.surface.isValid) return
        initialized = true

        runCatching {
            val engine = Engine.create(Engine.Backend.OPENGL)
            val current = ModelViewer(surfaceView, engine = engine, manipulator = null)
            viewer = current

            val sky = when (presentation) {
                AvatarPresentation.MASCULINE -> floatArrayOf(.79f, .94f, 1f)
                AvatarPresentation.FEMININE -> floatArrayOf(1f, .88f, .93f)
            }
            current.scene.skybox = Skybox.Builder()
                .color(sky[0], sky[1], sky[2], 1f)
                .build(current.engine)

            current.view.renderQuality = current.view.renderQuality.apply {
                hdrColorBuffer = if (lowPowerDevice) View.QualityLevel.MEDIUM else View.QualityLevel.HIGH
            }
            current.view.dynamicResolutionOptions = current.view.dynamicResolutionOptions.apply {
                enabled = lowPowerDevice
                quality = if (lowPowerDevice) View.QualityLevel.MEDIUM else View.QualityLevel.HIGH
            }
            current.view.antiAliasing = View.AntiAliasing.FXAA
            current.view.multiSampleAntiAliasingOptions = current.view.multiSampleAntiAliasingOptions.apply {
                enabled = !lowPowerDevice
            }
            current.view.ambientOcclusionOptions = current.view.ambientOcclusionOptions.apply {
                enabled = !lowPowerDevice
                quality = View.QualityLevel.HIGH
            }
            current.view.bloomOptions = current.view.bloomOptions.apply {
                enabled = !lowPowerDevice
                strength = .055f
            }

            installLights(current)
            current.camera.setExposure(9.0f, 1f / 125f, 100f)

            val assetName = when (presentation) {
                AvatarPresentation.FEMININE -> FEMALE_MODEL
                AvatarPresentation.MASCULINE -> MALE_MODEL
            }
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            require(bytes.size > 2_000_000) { "v12 hero avatar GLB is unexpectedly small" }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }
            current.loadModelGlb(buffer)
            current.transformToUnitCube(Float3(0f, -.055f, 0f))
            updateCamera(current)
            if (running) postFrame()
        }.onFailure {
            ready = false
            stop()
        }
    }

    private fun installLights(current: ModelViewer) {
        fun directional(
            intensity: Float,
            red: Float,
            green: Float,
            blue: Float,
            x: Float,
            y: Float,
            z: Float,
            shadows: Boolean,
        ) {
            val entity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(red, green, blue)
                .intensity(intensity)
                .direction(x, y, z)
                .castShadows(shadows)
                .build(current.engine, entity)
            current.scene.addEntity(entity)
        }

        directional(74_000f, 1f, .985f, .96f, -.42f, -.74f, -.56f, !lowPowerDevice)
        when (presentation) {
            AvatarPresentation.MASCULINE ->
                directional(31_000f, .63f, .88f, 1f, .67f, -.12f, -.72f, false)
            AvatarPresentation.FEMININE ->
                directional(31_000f, 1f, .73f, .84f, .67f, -.12f, -.72f, false)
        }
        directional(15_000f, 1f, .96f, .91f, -.08f, .28f, .95f, false)
    }

    fun start() {
        if (running) return
        running = true
        if (viewer != null) postFrame()
    }

    fun stop() {
        running = false
        framePosted = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun findAnimation(current: ModelViewer, hints: List<String>): Int {
        val animator = current.animator ?: return -1
        if (animator.animationCount <= 0) return -1
        for (hint in hints) {
            for (index in 0 until animator.animationCount) {
                val name = runCatching { animator.getAnimationName(index) }.getOrDefault("")
                if (name.contains(hint, ignoreCase = true)) return index
            }
        }
        return 0
    }

    private fun applyAnimation(current: ModelViewer, frameTimeNanos: Long) {
        val animator = current.animator ?: return
        val index = animationIndex
        if (index !in 0 until animator.animationCount) return
        if (animationStartNanos == 0L) animationStartNanos = frameTimeNanos

        val elapsed = ((frameTimeNanos - animationStartNanos).toDouble() / 1_000_000_000.0).toFloat()
        val duration = runCatching { animator.getAnimationDuration(index) }.getOrDefault(0f)
        val time = if (duration > .001f) elapsed % duration else elapsed
        animator.applyAnimation(index, time)
        animator.updateBoneMatrices()
    }

    private fun updateCamera(current: ModelViewer) {
        current.camera.lookAt(
            0.0, .035, 2.60,
            0.0, .015, 0.0,
            0.0, 1.0, 0.0,
        )
    }

    private fun postFrame() {
        if (!running || framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
