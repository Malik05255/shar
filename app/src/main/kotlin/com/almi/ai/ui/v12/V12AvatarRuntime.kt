package com.almi.ai.ui.v12

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.almi.ai.data.preferences.AvatarAppearance
import com.almi.ai.data.preferences.AvatarPresentation
import com.google.android.filament.Colors
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compact local avatar renderer for the v12 identity studio.
 * The scene is deliberately high-key: cyan for masculine and soft rose for feminine.
 */
internal class V12AvatarRuntime(
    private val context: Context,
    private val surfaceView: SurfaceView,
    initialPresentation: AvatarPresentation,
    initialAppearance: AvatarAppearance,
    private val onReady: () -> Unit = {},
) {
    companion object {
        init {
            Filament.init()
            Utils.init()
        }
        private const val MODEL = "almi3d/almi_avatar_lite.glb"
        private const val READY_FRAMES = 4
    }

    private var viewer: ModelViewer? = null
    private var initialized = false
    private var running = false
    private var framePosted = false
    private var ready = false
    private var warmupFrames = 0
    private var baseRootTransform: FloatArray? = null
    private var baseHairTransform: FloatArray? = null

    private var presentation = initialPresentation
    private var appearance = initialAppearance
    private var targetYaw = 0.0
    private var yaw = 0.0

    private var walkStartedNanos = 0L
    private var walkDurationNanos = 0L
    private var walkDirection = 1f

    private var turntableStartedNanos = 0L
    private var turntableDurationNanos = 0L
    private var turntableFromYaw = 0.0

    private val lowPowerDevice: Boolean by lazy {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true || Runtime.getRuntime().availableProcessors() <= 4
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (!running) return
            val current = viewer ?: return
            updateWalk(frameTimeNanos)
            updateTurntable(frameTimeNanos)
            if (turntableDurationNanos <= 0L) yaw += (targetYaw - yaw) * .11
            updateCamera(current)
            current.render(frameTimeNanos)

            if (!ready && current.asset != null && current.progress >= .96f) {
                warmupFrames += 1
                if (warmupFrames >= READY_FRAMES) {
                    ready = true
                    captureBaseTransforms(current)
                    applyPresentation()
                    applyAppearance()
                    surfaceView.post(onReady)
                }
            }
            postFrame()
        }
    }

    fun initialize() {
        if (initialized) return
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)
        surfaceView.setZOrderOnTop(false)
        surfaceView.background = null
        if (surfaceView.holder.surface.isValid) {
            initializeOnSurface()
        } else {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    initializeOnSurface()
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
                AvatarPresentation.MASCULINE -> floatArrayOf(.72f, .90f, .99f)
                AvatarPresentation.FEMININE -> floatArrayOf(.99f, .84f, .90f)
            }
            current.scene.skybox = Skybox.Builder().color(sky[0], sky[1], sky[2], 1f).build(current.engine)
            current.view.renderQuality = current.view.renderQuality.apply {
                hdrColorBuffer = if (lowPowerDevice) View.QualityLevel.LOW else View.QualityLevel.MEDIUM
            }
            current.view.dynamicResolutionOptions = current.view.dynamicResolutionOptions.apply { enabled = true }
            current.view.bloomOptions = current.view.bloomOptions.apply { enabled = false }
            current.view.antiAliasing = View.AntiAliasing.FXAA
            current.view.ambientOcclusionOptions = current.view.ambientOcclusionOptions.apply { enabled = !lowPowerDevice }
            current.view.multiSampleAntiAliasingOptions = current.view.multiSampleAntiAliasingOptions.apply { enabled = !lowPowerDevice }
            current.camera.setExposure(8.6f, 1f / 100f, 100f)
            installLights(current)

            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            require(bytes.size > 1_000_000) { "Avatar GLB is unexpectedly small" }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            current.loadModelGlb(buffer)
            current.transformToUnitCube(Float3(0f, -.03f, 0f))
            updateCamera(current)
            if (running) postFrame()
        }
    }

    private fun installLights(current: ModelViewer) {
        fun directional(intensity: Float, r: Float, g: Float, b: Float, x: Float, y: Float, z: Float) {
            val entity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(r, g, b)
                .intensity(intensity)
                .direction(x, y, z)
                .castShadows(false)
                .build(current.engine, entity)
            current.scene.addEntity(entity)
        }
        directional(66_000f, 1f, .98f, .95f, -.45f, -.72f, -.52f)
        when (presentation) {
            AvatarPresentation.MASCULINE -> directional(27_000f, .66f, .88f, 1f, .65f, -.12f, -.74f)
            AvatarPresentation.FEMININE -> directional(27_000f, 1f, .73f, .83f, .65f, -.12f, -.74f)
        }
        if (!lowPowerDevice) directional(10_000f, .96f, .98f, 1f, -.12f, .30f, .94f)
    }

    fun start() {
        if (running) return
        resetRoot()
        running = true
        if (viewer != null) postFrame()
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePosted = false
    }

    fun update(presentation: AvatarPresentation, appearance: AvatarAppearance) {
        this.presentation = presentation
        this.appearance = appearance
        if (ready) {
            applyPresentation()
            applyAppearance()
        }
    }

    fun faceFront() {
        turntableDurationNanos = 0L
        targetYaw = 0.0
    }

    fun playTurntable(durationMs: Long = 2_400L) {
        turntableStartedNanos = 0L
        turntableDurationNanos = durationMs.coerceIn(1_300L, 4_000L) * 1_000_000L
        turntableFromYaw = yaw
    }

    fun playWalkIn(fromRight: Boolean, durationMs: Long = 900L) {
        walkDirection = if (fromRight) 1f else -1f
        walkStartedNanos = 0L
        walkDurationNanos = durationMs.coerceIn(500L, 1_500L) * 1_000_000L
    }

    private fun resetRoot() {
        val current = viewer ?: return
        val asset = current.asset ?: return
        val base = baseRootTransform ?: return
        val manager = current.engine.transformManager
        val rootInstance = manager.getInstance(asset.root)
        if (rootInstance != 0) runCatching { manager.setTransform(rootInstance, base) }
        walkDurationNanos = 0L
        walkStartedNanos = 0L
    }

    private fun updateWalk(frameTimeNanos: Long) {
        if (walkDurationNanos <= 0L) return
        val current = viewer ?: return
        val asset = current.asset ?: return
        val base = baseRootTransform ?: return
        if (walkStartedNanos == 0L) walkStartedNanos = frameTimeNanos
        val t = ((frameTimeNanos - walkStartedNanos).toDouble() / walkDurationNanos).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        val stride = sin(t * PI * 5.0).toFloat()
        val manager = current.engine.transformManager
        val rootInstance = manager.getInstance(asset.root)
        if (rootInstance != 0) {
            val out = base.copyOf()
            out[12] += walkDirection * (1.0 - eased).toFloat() * .33f
            out[13] += abs(stride) * .008f
            manager.setTransform(rootInstance, out)
        }
        if (t >= 1.0) {
            walkDurationNanos = 0L
            walkStartedNanos = 0L
            if (rootInstance != 0) manager.setTransform(rootInstance, base)
        }
    }

    private fun updateTurntable(frameTimeNanos: Long) {
        if (turntableDurationNanos <= 0L) return
        if (turntableStartedNanos == 0L) turntableStartedNanos = frameTimeNanos
        val t = ((frameTimeNanos - turntableStartedNanos).toDouble() / turntableDurationNanos).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        yaw = turntableFromYaw + eased * PI * 2.0
        if (t >= 1.0) {
            turntableDurationNanos = 0L
            turntableStartedNanos = 0L
            yaw = 0.0
            targetYaw = 0.0
        }
    }

    private fun captureBaseTransforms(current: ModelViewer) {
        val asset = current.asset ?: return
        val manager = current.engine.transformManager
        val rootInstance = manager.getInstance(asset.root)
        if (rootInstance != 0) baseRootTransform = FloatArray(16).also { manager.getTransform(rootInstance, it) }
        val hair = asset.getFirstEntityByName("GrowthTrackHair")
        if (hair != 0) {
            val hairInstance = manager.getInstance(hair)
            if (hairInstance != 0) baseHairTransform = FloatArray(16).also { manager.getTransform(hairInstance, it) }
        }
    }

    private fun applyPresentation() {
        val current = viewer ?: return
        val asset = current.asset ?: return
        val body = asset.getFirstEntityByName("Body")
        if (body == 0) return
        val names = asset.getMorphTargetNames(body)
        if (names.isEmpty()) return
        val weights = FloatArray(names.size)
        fun set(name: String, value: Float) {
            val index = names.indexOf(name)
            if (index >= 0) weights[index] = value.coerceIn(0f, 1f)
        }
        when (presentation) {
            AvatarPresentation.FEMININE -> {
                set("waist_narrow", .42f); set("hip_width", .44f); set("pelvis_width", .38f)
                set("glute_volume", .24f); set("chest_depth", .18f); set("face_roundness", .24f)
                set("clavicle_width", .04f); set("deltoid_width", .02f)
            }
            AvatarPresentation.MASCULINE -> {
                set("waist_narrow", .12f); set("hip_width", .05f); set("pelvis_width", .04f)
                set("chest_depth", .22f); set("clavicle_width", .28f); set("deltoid_width", .18f); set("jaw_width", .17f)
            }
        }
        when (appearance.eyesVariant) { "wide" -> set("eye_size", .38f); "sharp" -> set("brow_depth", .22f) }
        if (appearance.eyebrowsVariant == "defined") set("brow_depth", .30f)
        when (appearance.mouthVariant) { "smile" -> set("smile", .44f); "full" -> set("lip_fullness", .46f) }

        val rm = current.engine.renderableManager
        fun applyWeights(entity: Int) {
            if (entity == 0) return
            val instance = rm.getInstance(entity)
            if (instance != 0) runCatching { rm.setMorphWeights(instance, weights, 0) }
        }
        applyWeights(body)
        applyWeights(asset.getFirstEntityByName("ALMI_BaseLayer"))
    }

    private fun applyAppearance() {
        val current = viewer ?: return
        val asset = current.asset ?: return
        setEntityColor(current, asset.getFirstEntityByName("Body"), appearance.skinColor)
        setEntityColor(current, asset.getFirstEntityByName("GrowthTrackHair"), appearance.hairColor)
        setVisible(current, asset.getFirstEntityByName("PrivateAnatomy"), false)
        setVisible(current, asset.getFirstEntityByName("ALMI_BaseLayer"), true)
        setVisible(current, asset.getFirstEntityByName("GrowthTrackHair"), appearance.hairVariant != "bald")
        applyHairVariant(current)
    }

    private fun applyHairVariant(current: ModelViewer) {
        val asset = current.asset ?: return
        val hair = asset.getFirstEntityByName("GrowthTrackHair")
        val base = baseHairTransform ?: return
        if (hair == 0) return
        val manager = current.engine.transformManager
        val instance = manager.getInstance(hair)
        if (instance == 0) return
        val out = base.copyOf()
        val (radial, vertical, yOffset) = when (appearance.hairVariant) {
            "shortFlat" -> Triple(.88f, .84f, -.012f)
            "shortCurly" -> Triple(1.02f, .96f, .008f)
            "bob" -> Triple(1.06f, 1.03f, -.010f)
            "longButNotTooLong" -> Triple(1.10f, 1.16f, -.028f)
            else -> Triple(1f, 1f, 0f)
        }
        for (row in 0..3) {
            out[row] *= radial
            out[4 + row] *= vertical
            out[8 + row] *= radial
        }
        out[13] += yOffset
        runCatching { manager.setTransform(instance, out) }
    }

    private fun setEntityColor(current: ModelViewer, entity: Int, value: String) {
        if (entity == 0) return
        val color = runCatching { android.graphics.Color.parseColor("#$value") }.getOrNull() ?: return
        val r = android.graphics.Color.red(color) / 255f
        val g = android.graphics.Color.green(color) / 255f
        val b = android.graphics.Color.blue(color) / 255f
        val rm = current.engine.renderableManager
        val instance = rm.getInstance(entity)
        if (instance == 0) return
        repeat(rm.getPrimitiveCount(instance)) { primitive ->
            runCatching {
                rm.getMaterialInstanceAt(instance, primitive).setParameter("baseColorFactor", Colors.RgbaType.SRGB, r, g, b, 1f)
            }
        }
    }

    private fun setVisible(current: ModelViewer, entity: Int, visible: Boolean) {
        if (entity == 0) return
        val rm = current.engine.renderableManager
        val instance = rm.getInstance(entity)
        if (instance != 0) runCatching { rm.setLayerMask(instance, 0xFF, if (visible) 0xFF else 0x00) }
    }

    private fun updateCamera(current: ModelViewer) {
        val distance = 2.72
        current.camera.lookAt(
            sin(yaw) * distance, .04, cos(yaw) * distance,
            0.0, .03, 0.0,
            0.0, 1.0, 0.0,
        )
    }

    private fun postFrame() {
        if (!running || framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
