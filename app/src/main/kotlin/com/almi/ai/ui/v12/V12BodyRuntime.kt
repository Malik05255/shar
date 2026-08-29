package com.almi.ai.ui.v12

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.MotionEvent
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal enum class V12BodyRendererState { LOADING, READY, ERROR }

internal data class V12ProjectedPoint(
    val x: Float,
    val y: Float,
    val visible: Boolean,
)

internal data class V12BodyProjection(
    val points: Map<String, V12ProjectedPoint>,
    val yawRadians: Double,
    val cameraDistance: Double,
)

/**
 * Realistic v12 Body Map renderer.
 *
 * The v12 bodies are compact MPFB2/MakeHuman exports with their authored skin/PBR maps intact.
 * Anatomical markers are solved from the game_engine skeleton and a calibrated neck-to-pelvis axis.
 */
internal class V12BodyRuntime(
    private val context: Context,
    private val surfaceView: SurfaceView,
    private val presentation: AvatarPresentation,
    private val onStateChanged: (V12BodyRendererState) -> Unit,
    private val onProjectionChanged: (V12BodyProjection) -> Unit,
) {
    companion object {
        init {
            Filament.init()
            Utils.init()
        }

        private const val FEMALE_MODEL = "almi3d/almi_body_female_v12.glb"
        private const val MALE_MODEL = "almi3d/almi_body_male_v12.glb"
        private const val READY_FRAMES = 5
        private const val DEFAULT_DISTANCE = 2.78

        private val PELVIS = arrayOf("pelvis", "Pelvis", "Hips", "hips")
        private val SPINE_1 = arrayOf("spine_01", "Spine", "spine")
        private val SPINE_2 = arrayOf("spine_02", "Spine1")
        private val SPINE_3 = arrayOf("spine_03", "Spine2")
        private val NECK = arrayOf("neck_01", "Neck", "neck")
        private val HEAD = arrayOf("head", "Head")
        private val LEFT_CLAVICLE = arrayOf("clavicle_l", "LeftShoulder", "shoulder_l")
        private val RIGHT_CLAVICLE = arrayOf("clavicle_r", "RightShoulder", "shoulder_r")
        private val LEFT_UPPER_ARM = arrayOf("upperarm_l", "LeftUpperArm", "LeftArm")
        private val RIGHT_UPPER_ARM = arrayOf("upperarm_r", "RightUpperArm", "RightArm")
        private val LEFT_LOWER_ARM = arrayOf("lowerarm_l", "LeftForeArm", "LeftLowerArm")
        private val RIGHT_LOWER_ARM = arrayOf("lowerarm_r", "RightForeArm", "RightLowerArm")
        private val LEFT_HAND = arrayOf("hand_l", "LeftHand")
        private val RIGHT_HAND = arrayOf("hand_r", "RightHand")
        private val LEFT_THIGH = arrayOf("thigh_l", "LeftUpLeg", "upperleg_l")
        private val RIGHT_THIGH = arrayOf("thigh_r", "RightUpLeg", "upperleg_r")
        private val LEFT_CALF = arrayOf("calf_l", "LeftLeg", "lowerleg_l")
        private val RIGHT_CALF = arrayOf("calf_r", "RightLeg", "lowerleg_r")
        private val LEFT_FOOT = arrayOf("foot_l", "LeftFoot")
        private val RIGHT_FOOT = arrayOf("foot_r", "RightFoot")
    }

    private data class WorldPoint(val x: Float, val y: Float, val z: Float)

    private var viewer: ModelViewer? = null
    private var initialized = false
    private var running = false
    private var framePosted = false
    private var ready = false
    private var warmupFrames = 0
    private var projectionFrame = 0
    private var yaw = 0.0
    private var cameraDistance = DEFAULT_DISTANCE
    private var targetDistance = DEFAULT_DISTANCE
    private var cameraTargetY = -0.02
    private var targetCameraY = -0.02
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var pinchDistance = 0f
    private var lastTapUpMs = 0L

    private val lowPowerDevice: Boolean by lazy {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true || Runtime.getRuntime().availableProcessors() <= 4
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (!running) return
            val current = viewer ?: return

            cameraDistance += (targetDistance - cameraDistance) * .14
            cameraTargetY += (targetCameraY - cameraTargetY) * .14
            updateCamera(current)
            current.render(frameTimeNanos)

            if (!ready && current.asset != null && current.progress >= .96f) {
                val asset = current.asset
                if (asset == null || asset.renderableEntities.isEmpty()) {
                    failRenderer()
                    return
                }
                warmupFrames += 1
                if (warmupFrames >= READY_FRAMES) {
                    ready = true
                    onStateChanged(V12BodyRendererState.READY)
                    dispatchProjection(current)
                }
            } else if (ready) {
                projectionFrame += 1
                if (projectionFrame % 2 == 0) dispatchProjection(current)
            }

            postFrame()
        }
    }

    fun initialize() {
        if (initialized) return
        onStateChanged(V12BodyRendererState.LOADING)
        surfaceView.background = null
        surfaceView.setZOrderOnTop(false)
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)

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

            current.scene.skybox = Skybox.Builder()
                .color(.84f, .94f, .99f, 1f)
                .build(current.engine)

            current.view.renderQuality = current.view.renderQuality.apply {
                hdrColorBuffer = if (lowPowerDevice) View.QualityLevel.MEDIUM else View.QualityLevel.HIGH
            }
            current.view.dynamicResolutionOptions = current.view.dynamicResolutionOptions.apply {
                enabled = lowPowerDevice
            }
            current.view.bloomOptions = current.view.bloomOptions.apply { enabled = false }
            current.view.antiAliasing = View.AntiAliasing.FXAA
            current.view.ambientOcclusionOptions = current.view.ambientOcclusionOptions.apply {
                enabled = !lowPowerDevice
            }
            current.view.multiSampleAntiAliasingOptions =
                current.view.multiSampleAntiAliasingOptions.apply { enabled = false }

            installStudioLights(current)
            current.camera.setExposure(8.9f, 1f / 125f, 100f)
            surfaceView.setOnTouchListener { _, event -> handleTouch(event) }

            val assetName = when (presentation) {
                AvatarPresentation.FEMININE -> FEMALE_MODEL
                AvatarPresentation.MASCULINE -> MALE_MODEL
            }
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            require(bytes.size > 1_000_000) { "v12 Body Map GLB is unexpectedly small" }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }
            current.loadModelGlb(buffer)
            current.transformToUnitCube(Float3(0f, -.025f, 0f))
            updateCamera(current)
            if (running) postFrame()
        }.onFailure {
            failRenderer()
        }
    }

    private fun installStudioLights(current: ModelViewer) {
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

        directional(74_000f, 1f, .99f, .96f, -.48f, -.70f, -.54f, true)
        directional(31_000f, .72f, .90f, 1f, .68f, -.16f, -.72f, false)
        if (!lowPowerDevice) {
            directional(12_000f, .96f, .98f, 1f, -.08f, .30f, .95f, false)
        }
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

    fun resetView() {
        yaw = 0.0
        targetDistance = DEFAULT_DISTANCE
        targetCameraY = -0.02
    }

    fun focusOn(anchorY: Float, distance: Float = 2.28f) {
        targetCameraY = ((.5f - anchorY) * 1.25f).coerceIn(-.58f, .58f).toDouble()
        targetDistance = distance.coerceIn(1.75f, 3.4f).toDouble()
    }

    private fun postFrame() {
        if (!running || framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
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
                        targetDistance = (targetDistance * (pinchDistance / now)).coerceIn(1.55, 4.1)
                    }
                    pinchDistance = now
                } else {
                    val dx = event.x - lastX
                    if (abs(dx) > .15f) yaw += dx * .0105
                    lastX = event.x
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
        current.camera.lookAt(
            sin(yaw) * cameraDistance,
            cameraTargetY * .16,
            cos(yaw) * cameraDistance,
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
        val viewMatrix = current.camera.getViewMatrix(FloatArray(16))
        val projectionMatrix = current.camera.getProjectionMatrix(DoubleArray(16))

        fun world(aliases: Array<String>): WorldPoint? {
            var entity = 0
            for (name in aliases) {
                entity = asset.getFirstEntityByName(name)
                if (entity != 0) break
            }
            if (entity == 0) return null
            val instance = manager.getInstance(entity)
            if (instance == 0) return null
            val transform = manager.getWorldTransform(instance, FloatArray(16))
            return WorldPoint(transform[12], transform[13], transform[14])
        }

        val pelvis = world(PELVIS)
        val spine1 = world(SPINE_1)
        val spine2 = world(SPINE_2)
        val spine3 = world(SPINE_3)
        val neck = world(NECK)
        val head = world(HEAD)
        val leftShoulder = world(LEFT_CLAVICLE)
        val rightShoulder = world(RIGHT_CLAVICLE)
        val leftUpperArm = world(LEFT_UPPER_ARM)
        val rightUpperArm = world(RIGHT_UPPER_ARM)
        val leftLowerArm = world(LEFT_LOWER_ARM)
        val rightLowerArm = world(RIGHT_LOWER_ARM)
        val leftHand = world(LEFT_HAND)
        val rightHand = world(RIGHT_HAND)
        val leftThigh = world(LEFT_THIGH)
        val rightThigh = world(RIGHT_THIGH)
        val leftCalf = world(LEFT_CALF)
        val rightCalf = world(RIGHT_CALF)
        val leftFoot = world(LEFT_FOOT)
        val rightFoot = world(RIGHT_FOOT)

        val anatomical = linkedMapOf<String, WorldPoint>()
        fun put(name: String, point: WorldPoint?) {
            if (point != null) anatomical[name] = point
        }

        put("pelvis", pelvis)
        put("spine1", spine1)
        put("spine2", spine2)
        put("spine3", spine3)
        put("neck", neck)
        put("head", head)
        put("leftShoulder", leftShoulder)
        put("rightShoulder", rightShoulder)
        put("leftUpperArm", leftUpperArm)
        put("rightUpperArm", rightUpperArm)
        put("leftElbow", leftLowerArm)
        put("rightElbow", rightLowerArm)
        put("leftHand", leftHand)
        put("rightHand", rightHand)
        put("leftThigh", leftThigh)
        put("rightThigh", rightThigh)
        put("leftCalf", leftCalf)
        put("rightCalf", rightCalf)
        put("leftFoot", leftFoot)
        put("rightFoot", rightFoot)

        if (neck != null && pelvis != null) {
            val axis = subtract(pelvis, neck)
            anatomical["chest"] = add(neck, scale(axis, .28f))
            anatomical["underbust"] = add(neck, scale(axis, .40f))
            anatomical["waist"] = add(neck, scale(axis, .61f))
            anatomical["abdomen"] = add(neck, scale(axis, .75f))
            anatomical["hips"] = add(neck, scale(axis, .93f))
        }

        if (leftShoulder != null && rightShoulder != null) {
            val center = midpoint(leftShoulder, rightShoulder)
            anatomical["shoulderCenter"] = center
            val half = subtract(rightShoulder, center)
            val chest = anatomical["chest"]
            if (chest != null) {
                anatomical["leftBust"] = add(chest, scale(half, -.40f))
                anatomical["rightBust"] = add(chest, scale(half, .40f))
            }
        }

        if (head != null && neck != null) {
            val headVector = subtract(head, neck)
            anatomical["crown"] = add(head, scale(headVector, .62f))
        }

        val mapped = linkedMapOf<String, V12ProjectedPoint>()
        anatomical.forEach { (name, point) ->
            projectWorld(point, viewMatrix, projectionMatrix)?.let { mapped[name] = it }
        }

        if (mapped.isNotEmpty()) {
            onProjectionChanged(V12BodyProjection(mapped, yaw, cameraDistance))
        }
    }

    private fun projectWorld(
        point: WorldPoint,
        view: FloatArray,
        projection: DoubleArray,
    ): V12ProjectedPoint? {
        val vx = view[0] * point.x + view[4] * point.y + view[8] * point.z + view[12]
        val vy = view[1] * point.x + view[5] * point.y + view[9] * point.z + view[13]
        val vz = view[2] * point.x + view[6] * point.y + view[10] * point.z + view[14]
        val vw = view[3] * point.x + view[7] * point.y + view[11] * point.z + view[15]

        val cx = projection[0] * vx + projection[4] * vy + projection[8] * vz + projection[12] * vw
        val cy = projection[1] * vx + projection[5] * vy + projection[9] * vz + projection[13] * vw
        val cw = projection[3] * vx + projection[7] * vy + projection[11] * vz + projection[15] * vw
        if (!cw.isFinite() || abs(cw) < 1e-7) return null

        val ndcX = cx / cw
        val ndcY = cy / cw
        if (!ndcX.isFinite() || !ndcY.isFinite()) return null
        val sx = ((ndcX + 1.0) * .5).toFloat()
        val sy = ((1.0 - ndcY) * .5).toFloat()
        return V12ProjectedPoint(
            x = sx,
            y = sy,
            visible = sx in -.12f..1.12f && sy in -.12f..1.12f,
        )
    }

    private fun midpoint(a: WorldPoint, b: WorldPoint): WorldPoint = WorldPoint(
        (a.x + b.x) * .5f,
        (a.y + b.y) * .5f,
        (a.z + b.z) * .5f,
    )

    private fun add(a: WorldPoint, b: WorldPoint): WorldPoint =
        WorldPoint(a.x + b.x, a.y + b.y, a.z + b.z)

    private fun subtract(a: WorldPoint, b: WorldPoint): WorldPoint =
        WorldPoint(a.x - b.x, a.y - b.y, a.z - b.z)

    private fun scale(a: WorldPoint, factor: Float): WorldPoint =
        WorldPoint(a.x * factor, a.y * factor, a.z * factor)

    private fun failRenderer() {
        if (ready) return
        onStateChanged(V12BodyRendererState.ERROR)
        stop()
    }
}
