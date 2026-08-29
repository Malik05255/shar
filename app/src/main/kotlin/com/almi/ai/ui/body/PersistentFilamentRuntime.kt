package com.almi.ai.ui.body

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import com.almi.ai.data.preferences.BodySideMeasurement
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
import kotlin.math.hypot
import kotlin.math.sin

internal enum class BodyRendererState { LOADING, READY, ERROR }

/** Normalized screen-space point produced from the actual Filament rig. */
internal data class BodyScreenPoint(
    val x: Float,
    val y: Float,
    val visible: Boolean = true,
)

/**
 * Live projection of the real GLB rig into phone coordinates.
 *
 * DressMeasurementActivity uses this rather than guessed x/y constants, so hotspots and guides
 * remain attached to the body while the user rotates, zooms, focuses a measurement, changes weight,
 * or edits left/right limb lengths.
 */
internal data class BodyScreenProjection(
    val points: Map<String, BodyScreenPoint>,
    val yawRadians: Double,
    val cameraDistance: Double,
) {
    operator fun get(name: String): BodyScreenPoint? = points[name]
}

/**
 * Persistent native Filament runtime for the ALMI measurement twin.
 *
 * Design goals:
 * - keep the full high-density MakeHuman mesh and its normal/AO detail;
 * - keep bloom + dynamic resolution disabled because they caused the device edge-flash artifact;
 * - restore one-finger orbit, pinch zoom and double-tap reset;
 * - project the actual skeleton every frame so UI measurement anchors never drift from the model;
 * - make weight/circumference/left-right edits visibly deform the same persistent model.
 */
internal class PersistentFilamentRuntime(
    private val context: Context,
    private val surfaceView: SurfaceView,
    private val onStateChanged: (BodyRendererState) -> Unit,
    private val onProjectionChanged: (BodyScreenProjection) -> Unit = {},
) {
    companion object {
        init {
            Filament.init()
            Utils.init()
        }

        private const val BODY_MODEL = "almi3d/almi_humanoid.glb"
        private const val INCH_TO_CM = 2.54f
        private const val POUND_TO_KG = 0.45359237f
        private const val READY_WARMUP_FRAMES = 6
        private const val OVERVIEW_DISTANCE = 3.05

        // The generated GLB uses these exact GrowthTrack bone names. Keep common aliases as fallback.
        private val LEFT_UPPER_ARM = arrayOf("LeftUpperArm", "upperarm01.L", "upperarm.L", "LeftArm", "mixamorig:LeftArm", "DEF-upper_arm.L")
        private val RIGHT_UPPER_ARM = arrayOf("RightUpperArm", "upperarm01.R", "upperarm.R", "RightArm", "mixamorig:RightArm", "DEF-upper_arm.R")
        private val LEFT_LOWER_ARM = arrayOf("LeftForeArm", "lowerarm01.L", "forearm.L", "mixamorig:LeftForeArm", "DEF-forearm.L")
        private val RIGHT_LOWER_ARM = arrayOf("RightForeArm", "lowerarm01.R", "forearm.R", "mixamorig:RightForeArm", "DEF-forearm.R")
        private val LEFT_HAND = arrayOf("LeftHand", "hand.L", "wrist.L", "mixamorig:LeftHand", "DEF-hand.L")
        private val RIGHT_HAND = arrayOf("RightHand", "hand.R", "wrist.R", "mixamorig:RightHand", "DEF-hand.R")
        private val LEFT_UPPER_LEG = arrayOf("LeftUpLeg", "upperleg01.L", "upperleg.L", "thigh.L", "mixamorig:LeftUpLeg", "DEF-thigh.L")
        private val RIGHT_UPPER_LEG = arrayOf("RightUpLeg", "upperleg01.R", "upperleg.R", "thigh.R", "mixamorig:RightUpLeg", "DEF-thigh.R")
        private val LEFT_LOWER_LEG = arrayOf("LeftLeg", "lowerleg01.L", "lowerleg.L", "shin.L", "mixamorig:LeftLeg", "DEF-shin.L")
        private val RIGHT_LOWER_LEG = arrayOf("RightLeg", "lowerleg01.R", "lowerleg.R", "shin.R", "mixamorig:RightLeg", "DEF-shin.R")
        private val LEFT_FOOT = arrayOf("LeftFoot", "foot.L", "mixamorig:LeftFoot", "DEF-foot.L")
        private val RIGHT_FOOT = arrayOf("RightFoot", "foot.R", "mixamorig:RightFoot", "DEF-foot.R")

        private val PROJECTION_BONES = arrayOf(
            "Hips", "Spine", "Spine1", "Spine2", "Neck", "Head",
            "LeftShoulder", "LeftUpperArm", "LeftForeArm", "LeftHand",
            "RightShoulder", "RightUpperArm", "RightForeArm", "RightHand",
            "LeftUpLeg", "LeftLeg", "LeftFoot",
            "RightUpLeg", "RightLeg", "RightFoot",
        )
    }

    private var viewer: ModelViewer? = null
    private var initialized = false
    private var running = false
    private var framePosted = false
    private var loadPosted = false
    private var readySent = false
    private var readyWarmupFrames = 0
    private var verificationRequested = false
    private var baseRootTransform: FloatArray? = null
    private var pendingProfile: BodyProfile? = null
    private val studioLights = mutableListOf<Int>()
    private val baseBoneTransforms = mutableMapOf<Int, FloatArray>()

    private var pendingWidth = 1f
    private var pendingHeight = 1f
    private var pendingDepth = 1f

    private var yaw = 0.0
    private var cameraDistance = OVERVIEW_DISTANCE
    private var targetCameraDistance = OVERVIEW_DISTANCE
    private var overviewDistance = OVERVIEW_DISTANCE
    private var cameraTargetY = -0.03
    private var targetCameraY = -0.03
    private var focused = false
    private var interactionsEnabled = true

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var pinchDistance = 0f
    private var lastTapUpMs = 0L

    private var introStartNanos = 0L
    private var introDurationNanos = 0L
    private var introFromYaw = 0.0
    private var introFinished: (() -> Unit)? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (!running) return
            val current = viewer ?: return

            updateIntroSpin(frameTimeNanos)
            cameraDistance += (targetCameraDistance - cameraDistance) * 0.14
            cameraTargetY += (targetCameraY - cameraTargetY) * 0.14
            updateCamera(current)
            current.render(frameTimeNanos)

            if (readySent) {
                dispatchProjection(current)
            }

            if (!readySent && current.asset != null && current.progress >= 0.96f) {
                val asset = current.asset
                if (asset == null || asset.renderableEntities.isEmpty()) {
                    failRenderer()
                    return
                }
                if (!ensureBodyRenderableVisible(current)) {
                    failRenderer()
                    return
                }

                applyReferenceMaterial(current)
                hideNamedRenderable(current, "GrowthTrackHair")
                hideNamedRenderable(current, "GrowthTrackEyes")
                hideNamedRenderable(current, "PrivateAnatomy")
                applyMorphs()
                applyAsymmetry()

                readyWarmupFrames += 1
                if (readyWarmupFrames >= READY_WARMUP_FRAMES && !verificationRequested) {
                    verificationRequested = true
                    current.debugGetNextFrameCallback { bitmap ->
                        if (hasVisibleBodyPixels(bitmap)) {
                            readySent = true
                            dispatchProjection(current)
                            onStateChanged(BodyRendererState.READY)
                        } else {
                            failRenderer()
                        }
                    }
                }
            }

            postFrame()
        }
    }

    fun initialize() {
        if (initialized) return
        onStateChanged(BodyRendererState.LOADING)

        surfaceView.background = null
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)

        if (!surfaceView.holder.surface.isValid) {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceView.post { initializeOnSurface() }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    refreshOverviewDistance()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        } else {
            initializeOnSurface()
        }
    }

    private fun initializeOnSurface() {
        if (initialized || !surfaceView.holder.surface.isValid) return
        initialized = true

        try {
            surfaceView.background = null
            val engine = Engine.create(Engine.Backend.OPENGL)
            val current = ModelViewer(surfaceView, engine = engine, manipulator = null)
            viewer = current

            // Approved lighter ALMI stage. This is the actual Filament background, not a View behind it.
            current.scene.skybox = Skybox.Builder()
                .color(0.030f, 0.105f, 0.225f, 1f)
                .build(current.engine)

            // Keep full native resolution and full mesh detail. Temporal / variable post-processing is
            // intentionally avoided because the test handset exposed it as a right-edge flash.
            current.view.renderQuality = current.view.renderQuality.apply {
                hdrColorBuffer = View.QualityLevel.HIGH
            }
            current.view.dynamicResolutionOptions = current.view.dynamicResolutionOptions.apply {
                enabled = false
            }
            current.view.bloomOptions = current.view.bloomOptions.apply {
                enabled = false
            }
            current.view.antiAliasing = View.AntiAliasing.FXAA
            current.view.ambientOcclusionOptions = current.view.ambientOcclusionOptions.apply {
                enabled = true
            }
            current.view.multiSampleAntiAliasingOptions =
                current.view.multiSampleAntiAliasingOptions.apply { enabled = true }

            installStudioLights(current)
            current.camera.setExposure(8.8f, 1.0f / 125.0f, 100.0f)

            surfaceView.setOnTouchListener { _, event -> handleTouch(event) }

            if (!loadPosted) {
                loadPosted = true
                surfaceView.postDelayed({ loadHumanoid() }, 120L)
            }
            if (running) postFrame()
        } catch (_: Throwable) {
            failRenderer()
        }
    }

    private fun installStudioLights(current: ModelViewer) {
        if (studioLights.isNotEmpty()) return

        fun addDirectional(
            red: Float,
            green: Float,
            blue: Float,
            intensity: Float,
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
            studioLights += entity
        }

        // Wide key + soft fill + restrained rim. No bloom is needed for silhouette separation.
        addDirectional(1.00f, 0.99f, 0.98f, 58_000f, -0.40f, -0.66f, -0.64f, true)
        addDirectional(0.78f, 0.88f, 1.00f, 25_000f, 0.66f, -0.20f, -0.72f, false)
        addDirectional(0.70f, 0.84f, 1.00f, 11_000f, -0.10f, 0.24f, 0.96f, false)
    }

    private fun loadHumanoid() {
        val current = viewer ?: return
        if (!surfaceView.isAttachedToWindow) return

        try {
            val bytes = context.assets.open(BODY_MODEL).use { it.readBytes() }
            if (bytes.size < 1_000_000) {
                failRenderer()
                return
            }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }

            current.loadModelGlb(buffer)
            current.transformToUnitCube(Float3(0f, 0f, 0f))
            captureBaseTransform(current)
            applyBodyShape()
            applyMorphs()
            applyAsymmetry()
            refreshOverviewDistance()
            updateCamera(current)
        } catch (_: Throwable) {
            failRenderer()
        }
    }

    private fun ensureBodyRenderableVisible(current: ModelViewer): Boolean {
        val asset = current.asset ?: return false
        val bodyEntity = asset.getFirstEntityByName("Body")
        if (bodyEntity == 0) return false
        val renderableManager = current.engine.renderableManager
        val instance = renderableManager.getInstance(bodyEntity)
        if (instance == 0) return false
        return runCatching {
            renderableManager.setLayerMask(instance, 0xFF, 0xFF)
            renderableManager.getPrimitiveCount(instance) > 0
        }.getOrDefault(false)
    }

    private fun applyReferenceMaterial(current: ModelViewer) {
        val asset = current.asset ?: return
        val bodyEntity = asset.getFirstEntityByName("Body")
        if (bodyEntity == 0) return
        val renderableManager = current.engine.renderableManager
        val instance = renderableManager.getInstance(bodyEntity)
        if (instance == 0) return

        val primitiveCount = renderableManager.getPrimitiveCount(instance)
        for (primitive in 0 until primitiveCount) {
            val material = renderableManager.getMaterialInstanceAt(instance, primitive)
            runCatching {
                material.setParameter(
                    "baseColorFactor",
                    Colors.RgbaType.SRGB,
                    0.64f,
                    0.80f,
                    0.98f,
                    1.00f,
                )
                material.setParameter("metallicFactor", 0.00f)
                material.setParameter("roughnessFactor", 0.40f)
                material.setParameter(
                    "emissiveFactor",
                    Colors.RgbType.LINEAR,
                    0.006f,
                    0.014f,
                    0.030f,
                )
                material.setParameter("emissiveStrength", 0.08f)
                material.setParameter("reflectance", 0.30f)
            }
        }
    }

    private fun hasVisibleBodyPixels(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val stepX = (bitmap.width / 72).coerceAtLeast(1)
        val stepY = (bitmap.height / 128).coerceAtLeast(1)
        var samples = 0
        var bodyLike = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)
                if (red >= 78 && green >= 112 && blue >= 150 && blue > red + 36) bodyLike += 1
                samples += 1
                x += stepX
            }
            y += stepY
        }
        return samples > 0 && bodyLike.toFloat() / samples >= 0.0014f
    }

    private fun failRenderer() {
        if (readySent) return
        onStateChanged(BodyRendererState.ERROR)
        stop()
    }

    private fun hideNamedRenderable(current: ModelViewer, name: String) {
        val asset = current.asset ?: return
        val entity = asset.getFirstEntityByName(name)
        if (entity == 0) return
        val renderableManager = current.engine.renderableManager
        val instance = renderableManager.getInstance(entity)
        if (instance != 0) runCatching { renderableManager.setLayerMask(instance, 0xFF, 0x00) }
    }

    fun updateBodyShape(width: Float, height: Float, depth: Float) {
        pendingWidth = width.coerceIn(.70f, 1.46f)
        pendingHeight = height.coerceIn(.76f, 1.28f)
        pendingDepth = depth.coerceIn(.70f, 1.52f)
        applyBodyShape()
        refreshOverviewDistance()
    }

    fun updateProfile(profile: BodyProfile) {
        pendingProfile = profile
        applyMorphs()
        applyAsymmetry()
        refreshOverviewDistance()
    }

    fun playIntroSpin(durationMs: Long = 2_100L, onFinished: () -> Unit) {
        focused = false
        interactionsEnabled = false
        targetCameraY = -0.03
        targetCameraDistance = overviewDistance
        introStartNanos = 0L
        introDurationNanos = durationMs.coerceAtLeast(700L) * 1_000_000L
        introFromYaw = yaw
        introFinished = onFinished
    }

    fun focusOn(normalizedY: Float, distance: Float) {
        focused = true
        targetCameraY = normalizedY.coerceIn(-0.80f, 0.80f).toDouble()
        targetCameraDistance = distance.coerceIn(1.65f, 3.05f).toDouble()
    }

    fun resetFocus() {
        focused = false
        targetCameraY = -0.03
        targetCameraDistance = overviewDistance
    }

    fun resetView() {
        focused = false
        yaw = 0.0
        targetCameraY = -0.03
        targetCameraDistance = overviewDistance
    }

    /** Allows a UI overlay to forward empty-space gestures to the same Filament camera controls. */
    fun onViewportTouch(event: MotionEvent): Boolean = handleTouch(event)

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

    private fun postFrame() {
        if (!running || framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun updateIntroSpin(frameTimeNanos: Long) {
        if (introDurationNanos <= 0L) return
        if (introStartNanos == 0L) introStartNanos = frameTimeNanos
        val progress = ((frameTimeNanos - introStartNanos).toDouble() / introDurationNanos.toDouble()).coerceIn(0.0, 1.0)
        val eased = progress * progress * (3.0 - 2.0 * progress)
        yaw = introFromYaw + eased * PI * 2.0
        if (progress >= 1.0) {
            yaw = 0.0
            introDurationNanos = 0L
            introStartNanos = 0L
            interactionsEnabled = true
            val callback = introFinished
            introFinished = null
            if (callback != null) surfaceView.post(callback)
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (!interactionsEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                pinchDistance = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) pinchDistance = pointerDistance(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val now = pointerDistance(event)
                    if (pinchDistance > 0f && now > 0f) {
                        focused = false
                        targetCameraDistance =
                            (targetCameraDistance * (pinchDistance / now)).coerceIn(1.55, 4.50)
                    }
                    pinchDistance = now
                } else {
                    val dx = event.x - lastX
                    if (abs(dx) > 0.15f) {
                        focused = false
                        yaw += dx.toDouble() * 0.0105
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_UP -> {
                val travel = hypot(event.x - downX, event.y - downY)
                val tapThreshold = context.resources.displayMetrics.density * 18f
                if (travel <= tapThreshold) {
                    val now = event.eventTime
                    if (now - lastTapUpMs in 40L..300L) resetView()
                    lastTapUpMs = now
                }
                pinchDistance = 0f
            }

            MotionEvent.ACTION_CANCEL -> pinchDistance = 0f
        }
        return true
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(
            event.getX(0) - event.getX(1),
            event.getY(0) - event.getY(1),
        )
    }

    private fun updateCamera(current: ModelViewer) {
        val distance = cameraDistance
        current.camera.lookAt(
            sin(yaw) * distance,
            cameraTargetY * 0.18,
            cos(yaw) * distance,
            0.0,
            cameraTargetY,
            0.0,
            0.0,
            1.0,
            0.0,
        )
    }

    private fun dispatchProjection(current: ModelViewer) {
        val asset = current.asset ?: return
        val manager = current.engine.transformManager
        val view = current.camera.getViewMatrix(FloatArray(16))
        val projection = current.camera.getProjectionMatrix(DoubleArray(16))
        val mapped = linkedMapOf<String, BodyScreenPoint>()

        PROJECTION_BONES.forEach { name ->
            val entity = asset.getFirstEntityByName(name)
            if (entity == 0) return@forEach
            val instance = manager.getInstance(entity)
            if (instance == 0) return@forEach
            val world = manager.getWorldTransform(instance, FloatArray(16))
            projectWorld(world[12], world[13], world[14], view, projection)?.let { mapped[name] = it }
        }

        if (mapped.isNotEmpty()) {
            onProjectionChanged(BodyScreenProjection(mapped, yaw, cameraDistance))
        }
    }

    private fun projectWorld(
        x: Float,
        y: Float,
        z: Float,
        view: FloatArray,
        projection: DoubleArray,
    ): BodyScreenPoint? {
        val vx = view[0] * x + view[4] * y + view[8] * z + view[12]
        val vy = view[1] * x + view[5] * y + view[9] * z + view[13]
        val vz = view[2] * x + view[6] * y + view[10] * z + view[14]
        val vw = view[3] * x + view[7] * y + view[11] * z + view[15]

        val cx = projection[0] * vx + projection[4] * vy + projection[8] * vz + projection[12] * vw
        val cy = projection[1] * vx + projection[5] * vy + projection[9] * vz + projection[13] * vw
        val cw = projection[3] * vx + projection[7] * vy + projection[11] * vz + projection[15] * vw
        if (!cw.isFinite() || abs(cw) < 1e-7) return null

        val ndcX = cx / cw
        val ndcY = cy / cw
        if (!ndcX.isFinite() || !ndcY.isFinite()) return null
        val sx = ((ndcX + 1.0) * 0.5).toFloat()
        val sy = ((1.0 - ndcY) * 0.5).toFloat()
        return BodyScreenPoint(
            x = sx,
            y = sy,
            // Filament uses an OpenGL-style forward camera where points in front commonly carry a
            // negative view-space z / clip w. Screen bounds are the reliable visibility gate here.
            visible = sx in -0.15f..1.15f && sy in -0.15f..1.15f,
        )
    }

    private fun refreshOverviewDistance() {
        val profile = pendingProfile
        val sideExtra = if (profile == null) 1f else {
            val leg = sideRatioMagnitude(profile, BodySideMeasurement.LEFT_INSEAM, BodySideMeasurement.RIGHT_INSEAM)
            val arm = sideRatioMagnitude(profile, BodySideMeasurement.LEFT_ARM_LENGTH, BodySideMeasurement.RIGHT_ARM_LENGTH)
            maxOf(leg, arm)
        }
        val widthPenalty = 1f + (pendingWidth - 1f).coerceAtLeast(0f) * .18f
        val heightPenalty = 1f + (pendingHeight - 1f).coerceAtLeast(0f) * .74f
        val shortScreenPenalty = if (surfaceView.width > 0 && surfaceView.height > 0) {
            val aspect = surfaceView.width.toFloat() / surfaceView.height.toFloat()
            if (aspect > .55f) 1.06f else 1f
        } else 1f
        overviewDistance = (OVERVIEW_DISTANCE * widthPenalty * heightPenalty * sideExtra * shortScreenPenalty)
            .coerceIn(2.88, 4.00)
        if (!focused && introDurationNanos <= 0L) targetCameraDistance = overviewDistance
    }

    private fun sideRatioMagnitude(
        profile: BodyProfile,
        leftKey: BodySideMeasurement,
        rightKey: BodySideMeasurement,
    ): Float {
        val left = profile.sideMeasurementsInches[leftKey]
        val right = profile.sideMeasurementsInches[rightKey]
        if (left == null || right == null || left <= 0f || right <= 0f) return 1f
        val average = (left + right) / 2f
        if (average <= 0f) return 1f
        return maxOf(left / average, right / average).coerceIn(1f, 1.18f)
    }

    private fun captureBaseTransform(current: ModelViewer) {
        val asset = current.asset ?: return
        val manager = current.engine.transformManager
        val instance = manager.getInstance(asset.root)
        if (instance == 0) return
        baseRootTransform = FloatArray(16).also { manager.getTransform(instance, it) }
    }

    private fun applyBodyShape() {
        val current = viewer ?: return
        val asset = current.asset ?: return
        val base = baseRootTransform ?: return
        val manager = current.engine.transformManager
        val instance = manager.getInstance(asset.root)
        if (instance == 0) return

        val out = base.copyOf()
        for (row in 0..3) {
            out[row] *= pendingWidth
            out[4 + row] *= pendingHeight
            out[8 + row] *= pendingDepth
        }
        manager.setTransform(instance, out)
    }

    private fun applyAsymmetry() {
        val current = viewer ?: return
        val asset = current.asset ?: return
        val profile = pendingProfile ?: return
        val manager = current.engine.transformManager

        baseBoneTransforms.forEach { (entity, transform) ->
            val instance = manager.getInstance(entity)
            if (instance != 0) runCatching { manager.setTransform(instance, transform) }
        }

        fun ratioPair(
            leftKey: BodySideMeasurement,
            rightKey: BodySideMeasurement,
            fallback: BodyMeasurePoint,
        ): Pair<Float, Float>? {
            val explicitLeft = profile.sideMeasurementsInches[leftKey]
            val explicitRight = profile.sideMeasurementsInches[rightKey]
            if (explicitLeft == null && explicitRight == null) return null
            val generic = profile.measurementsInches[fallback]
            val left = explicitLeft ?: generic ?: explicitRight ?: return null
            val right = explicitRight ?: generic ?: explicitLeft ?: return null
            val average = (left + right) / 2f
            if (average <= 0f) return null
            return (left / average).coerceIn(.82f, 1.18f) to (right / average).coerceIn(.82f, 1.18f)
        }

        ratioPair(
            BodySideMeasurement.LEFT_ARM_LENGTH,
            BodySideMeasurement.RIGHT_ARM_LENGTH,
            BodyMeasurePoint.ARM_LENGTH,
        )?.let { (left, right) ->
            scaleBoneY(asset, manager, LEFT_UPPER_ARM, left)
            scaleBoneY(asset, manager, LEFT_LOWER_ARM, left)
            scaleBoneY(asset, manager, RIGHT_UPPER_ARM, right)
            scaleBoneY(asset, manager, RIGHT_LOWER_ARM, right)
        }

        ratioPair(
            BodySideMeasurement.LEFT_UPPER_ARM,
            BodySideMeasurement.RIGHT_UPPER_ARM,
            BodyMeasurePoint.UPPER_ARM,
        )?.let { (left, right) ->
            scaleBoneRadial(asset, manager, LEFT_UPPER_ARM, left.coerceIn(.86f, 1.16f))
            scaleBoneRadial(asset, manager, RIGHT_UPPER_ARM, right.coerceIn(.86f, 1.16f))
        }

        ratioPair(
            BodySideMeasurement.LEFT_WRIST,
            BodySideMeasurement.RIGHT_WRIST,
            BodyMeasurePoint.WRIST,
        )?.let { (left, right) ->
            scaleBoneRadial(asset, manager, LEFT_LOWER_ARM, left.coerceIn(.90f, 1.12f))
            scaleBoneRadial(asset, manager, RIGHT_LOWER_ARM, right.coerceIn(.90f, 1.12f))
        }

        ratioPair(
            BodySideMeasurement.LEFT_HAND_LENGTH,
            BodySideMeasurement.RIGHT_HAND_LENGTH,
            BodyMeasurePoint.HAND,
        )?.let { (left, right) ->
            scaleBoneY(asset, manager, LEFT_HAND, left)
            scaleBoneY(asset, manager, RIGHT_HAND, right)
        }

        ratioPair(
            BodySideMeasurement.LEFT_INSEAM,
            BodySideMeasurement.RIGHT_INSEAM,
            BodyMeasurePoint.INSEAM,
        )?.let { (left, right) ->
            scaleBoneY(asset, manager, LEFT_UPPER_LEG, left)
            scaleBoneY(asset, manager, LEFT_LOWER_LEG, left)
            scaleBoneY(asset, manager, RIGHT_UPPER_LEG, right)
            scaleBoneY(asset, manager, RIGHT_LOWER_LEG, right)
        }

        ratioPair(
            BodySideMeasurement.LEFT_FOOT_LENGTH,
            BodySideMeasurement.RIGHT_FOOT_LENGTH,
            BodyMeasurePoint.FOOT,
        )?.let { (left, right) ->
            scaleBoneY(asset, manager, LEFT_FOOT, left)
            scaleBoneY(asset, manager, RIGHT_FOOT, right)
        }

        // Filament skinning needs fresh bone matrices after direct TransformManager edits.
        runCatching { current.animator?.updateBoneMatrices() }
    }

    private fun resolveBone(
        asset: com.google.android.filament.gltfio.FilamentAsset,
        manager: com.google.android.filament.TransformManager,
        candidates: Array<String>,
    ): Pair<Int, Int>? {
        val entity = candidates.asSequence()
            .map { asset.getFirstEntityByName(it) }
            .firstOrNull { it != 0 } ?: return null
        val instance = manager.getInstance(entity)
        if (instance == 0) return null
        baseBoneTransforms.getOrPut(entity) {
            FloatArray(16).also { manager.getTransform(instance, it) }
        }
        return entity to instance
    }

    private fun scaleBoneY(
        asset: com.google.android.filament.gltfio.FilamentAsset,
        manager: com.google.android.filament.TransformManager,
        candidates: Array<String>,
        ratio: Float,
    ) {
        val (entity, instance) = resolveBone(asset, manager, candidates) ?: return
        val base = baseBoneTransforms[entity] ?: return
        val out = base.copyOf()
        for (row in 0..3) out[4 + row] *= ratio
        runCatching { manager.setTransform(instance, out) }
    }

    private fun scaleBoneRadial(
        asset: com.google.android.filament.gltfio.FilamentAsset,
        manager: com.google.android.filament.TransformManager,
        candidates: Array<String>,
        ratio: Float,
    ) {
        val (entity, instance) = resolveBone(asset, manager, candidates) ?: return
        val base = baseBoneTransforms[entity] ?: return
        val out = base.copyOf()
        for (row in 0..3) {
            out[row] *= ratio
            out[8 + row] *= ratio
        }
        runCatching { manager.setTransform(instance, out) }
    }

    private fun applyMorphs() {
        val current = viewer ?: return
        val asset = current.asset ?: return
        val profile = pendingProfile ?: return
        val bodyEntity = asset.getFirstEntityByName("Body")
        if (bodyEntity == 0) return

        val names = asset.getMorphTargetNames(bodyEntity)
        if (names.isEmpty()) return
        val weights = FloatArray(names.size)

        fun set(name: String, value: Float) {
            val index = names.indexOf(name)
            if (index >= 0) weights[index] = value.coerceIn(0f, 1f)
        }

        fun cm(point: BodyMeasurePoint): Float? = profile.measurementsInches[point]?.times(INCH_TO_CM)

        val kg = profile.weightPounds * POUND_TO_KG
        val mass = ((kg - 44f) / 92f).coerceIn(0f, 1f)
        val weightBulk = ((kg - 58f) / 82f).coerceIn(0f, 1f)
        val waistVolume = cm(BodyMeasurePoint.WAIST)?.let { ((it - 68f) / 68f).coerceIn(0f, .98f) }
        val abdomenVolume = cm(BodyMeasurePoint.ABDOMEN)?.let { ((it - 72f) / 72f).coerceIn(0f, 1f) }

        // Weight is deliberately visible even before circumferences are entered. Once a real
        // measurement exists, that measurement takes precedence over the weight-derived fallback.
        set("overall_mass", mass)
        set(
            "gut_volume",
            maxOf(
                weightBulk * .78f,
                waistVolume ?: 0f,
                abdomenVolume ?: 0f,
            ),
        )
        set("face_roundness", (mass * .52f).coerceIn(0f, .66f))
        set("shoulder_drop", 0.24f)
        set("hand_splay", 0.05f)

        val chestCm = cm(BodyMeasurePoint.CHEST)
        val underBustCm = cm(BodyMeasurePoint.UNDERBUST)
        if (chestCm != null) {
            set("chest_depth", ((chestCm - 72f) / 66f).coerceIn(0f, 1f))
            set("ribcage_depth", ((chestCm - 70f) / 70f).coerceIn(0f, .94f))
        } else {
            set("chest_depth", weightBulk * .46f)
            set("ribcage_depth", weightBulk * .34f)
        }
        underBustCm?.let {
            set("ribcage_depth", ((it - 62f) / 58f).coerceIn(0f, .95f))
        }

        cm(BodyMeasurePoint.NECK)?.let {
            set("neck_thickness", ((it - 28f) / 26f).coerceIn(0f, .95f))
        }
        cm(BodyMeasurePoint.SHOULDERS)?.let {
            set("clavicle_width", ((it - 32f) / 30f).coerceIn(0f, 1f))
            set("deltoid_width", ((it - 34f) / 34f).coerceIn(0f, .78f))
        }
        cm(BodyMeasurePoint.SHOULDER_LENGTH)?.let {
            set("shoulder_slope", ((it - 9f) / 11f).coerceIn(0f, .72f))
        }
        cm(BodyMeasurePoint.BUST_HEIGHT)?.let {
            set("torso_length", ((it - 18f) / 28f).coerceIn(0f, .70f))
        }
        cm(BodyMeasurePoint.BUST_POINT_DISTANCE)?.let {
            // There is no dedicated breast-apex morph in the current asset. A restrained clavicle
            // adjustment at least preserves the entered upper-torso width without inventing anatomy.
            val existingIndex = names.indexOf("clavicle_width")
            val existing = if (existingIndex >= 0) weights[existingIndex] else 0f
            set("clavicle_width", maxOf(existing, ((it - 14f) / 18f).coerceIn(0f, .45f)))
        }

        cm(BodyMeasurePoint.WAIST)?.let {
            set("waist_narrow", ((86f - it) / 38f).coerceIn(0f, 1f))
            set("oblique_def", ((90f - it) / 44f).coerceIn(0f, .58f))
        }

        val hipsCm = cm(BodyMeasurePoint.HIPS)
        if (hipsCm != null) {
            set("hip_width", ((hipsCm - 76f) / 66f).coerceIn(0f, 1f))
            set("pelvis_width", ((hipsCm - 76f) / 66f).coerceIn(0f, .95f))
            set("glute_volume", ((hipsCm - 80f) / 66f).coerceIn(0f, .90f))
        } else {
            set("hip_width", weightBulk * .34f)
            set("pelvis_width", weightBulk * .28f)
            set("glute_volume", weightBulk * .40f)
        }

        cm(BodyMeasurePoint.ARM_LENGTH)?.let {
            val arm = ((it - 46f) / 44f).coerceIn(0f, .88f)
            set("upper_arm_length", arm * .52f)
            set("forearm_length", arm * .48f)
        }
        val upperArmCm = cm(BodyMeasurePoint.UPPER_ARM)
        if (upperArmCm != null) {
            val girth = ((upperArmCm - 19f) / 35f).coerceIn(0f, .95f)
            set("bicep_peak", girth)
            set("tricep_horse", girth * .86f)
        } else {
            set("bicep_peak", weightBulk * .30f)
            set("tricep_horse", weightBulk * .26f)
        }
        cm(BodyMeasurePoint.WRIST)?.let {
            set("forearm_girth", ((it - 12f) / 20f).coerceIn(0f, .70f))
        }

        // Advanced channels remain honored for older profiles and asymmetry experiments.
        cm(BodyMeasurePoint.HAND)?.let { set("hand_length", ((it - 15f) / 14f).coerceIn(0f, 1f)) }
        cm(BodyMeasurePoint.THIGH)?.let { set("quad_sweep", ((it - 42f) / 48f).coerceIn(0f, .95f)) }
        cm(BodyMeasurePoint.INSEAM)?.let { set("leg_length", ((it - 64f) / 52f).coerceIn(0f, .90f)) }
        cm(BodyMeasurePoint.CALF)?.let { set("calf_diamond", ((it - 27f) / 30f).coerceIn(0f, .95f)) }
        cm(BodyMeasurePoint.FOOT)?.let { set("foot_length", ((it - 20f) / 15f).coerceIn(0f, .95f)) }

        val renderableManager = current.engine.renderableManager
        val instance = renderableManager.getInstance(bodyEntity)
        if (instance != 0) runCatching {
            renderableManager.setMorphWeights(instance, weights, 0)
        }
    }
}
