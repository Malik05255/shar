package com.almi.ai.ui.v12

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.Matrix
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.almi.ai.data.preferences.AvatarAppearance
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Fence
import com.google.android.filament.Filament
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Multi-asset digital-human renderer for the quality-first v12 avatar editor.
 *
 * ModelViewer intentionally owns only one glTF scene. This runtime instead owns Filament directly
 * so a high-detail body, FACS head and card hair can coexist in one Scene. Assets are loaded with
 * one shared AssetLoader / ResourceLoader / material provider, then the detached head and hair are
 * driven from the animated body's mixamorig_Head transform every frame.
 *
 * This class is deliberately independent from the current editor until the quality assets are
 * bundled and device-validated. Keeping the integration seam explicit lets us ship the new visual
 * stack without risking Body Map or AI provider paths.
 */
internal class V12DigitalHumanRuntime(
    private val context: Context,
    private val surfaceView: SurfaceView,
    initialAppearance: AvatarAppearance,
    private val onReady: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
) {
    companion object {
        init {
            Filament.init()
            Utils.init()
        }

        const val BODY_ASSET = "almi3d/digital/vitruvian_body.glb"
        const val HEAD_ASSET = "almi3d/digital/vitruvian_head.glb"
        const val HAIR_ASSET = "almi3d/digital/vitruvian_hair.glb"

        private const val HEAD_BONE = "mixamorig_Head"
        private const val READY_FRAMES = 3
    }

    private data class Part(
        val asset: FilamentAsset,
        val rootBase: FloatArray,
    )

    private val lowPowerDevice: Boolean by lazy {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true || Runtime.getRuntime().availableProcessors() <= 4
    }

    private var appearance = initialAppearance
    private var initialized = false
    private var running = false
    private var framePosted = false
    private var ready = false
    private var destroyed = false
    private var warmupFrames = 0

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var filamentView: View? = null
    private var camera: Camera? = null
    private var cameraEntity: Int = 0
    private var swapChain: SwapChain? = null
    private var displayHelper: DisplayHelper? = null
    private var uiHelper: UiHelper? = null

    private var materialProvider: MaterialProvider? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null

    private var body: Part? = null
    private var head: Part? = null
    private var hair: Part? = null
    private var headBoneEntity: Int = 0
    private var headBoneRestWorld: FloatArray? = null
    private var headBoneRestInverse: FloatArray? = null
    private var faceEntity: Int = 0

    private var activeAnimation = -1
    private var animationStartNanos = 0L
    private var yaw = 0.0
    private var targetYaw = 0.0
    private var turntableStartNanos = 0L
    private var turntableDurationNanos = 0L
    private var turntableFromYaw = 0.0

    private val surfaceCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            val currentEngine = engine ?: return
            swapChain?.let(currentEngine::destroySwapChain)
            swapChain = currentEngine.createSwapChain(surface)
            val currentRenderer = renderer ?: return
            displayHelper?.attach(currentRenderer, surfaceView.display)
        }

        override fun onDetachedFromSurface() {
            val currentEngine = engine ?: return
            displayHelper?.detach()
            swapChain?.let {
                currentEngine.destroySwapChain(it)
                currentEngine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            filamentView?.viewport = Viewport(0, 0, width, height)
            camera?.setLensProjection(42.0, width.toDouble() / height.toDouble(), .03, 50.0)
            engine?.let(::synchronizePendingFrames)
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (!running || destroyed) return

            val currentRenderer = renderer ?: return
            val currentView = filamentView ?: return
            val currentSwapChain = swapChain
            if (currentSwapChain == null || uiHelper?.isReadyToRender != true) {
                postFrame()
                return
            }

            updateTurntable(frameTimeNanos)
            yaw += (targetYaw - yaw) * .12
            applyBodyRootYaw()
            applyAnimation(frameTimeNanos)
            attachHeadAndHairToBody()
            updateCamera()

            if (currentRenderer.beginFrame(currentSwapChain, frameTimeNanos)) {
                currentRenderer.render(currentView)
                currentRenderer.endFrame()
            }

            if (!ready) {
                warmupFrames += 1
                if (warmupFrames >= READY_FRAMES && body != null && head != null) {
                    ready = true
                    findFaceEntity()
                    applyAppearance()
                    surfaceView.post(onReady)
                }
            }
            postFrame()
        }
    }

    fun initialize() {
        if (initialized || destroyed) return
        initialized = true
        surfaceView.setZOrderOnTop(false)
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)
        surfaceView.background = null

        runCatching {
            val currentEngine = Engine.create(Engine.Backend.OPENGL)
            engine = currentEngine
            renderer = currentEngine.createRenderer()
            scene = currentEngine.createScene()
            cameraEntity = EntityManager.get().create()
            camera = currentEngine.createCamera(cameraEntity)
            filamentView = currentEngine.createView().also { view ->
                view.scene = scene
                view.camera = camera
                view.renderQuality = view.renderQuality.apply {
                    hdrColorBuffer = if (lowPowerDevice) View.QualityLevel.MEDIUM else View.QualityLevel.HIGH
                }
                view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
                    enabled = lowPowerDevice
                    quality = View.QualityLevel.HIGH
                }
                view.antiAliasing = View.AntiAliasing.FXAA
                view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
                    enabled = !lowPowerDevice
                }
                view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
                    enabled = !lowPowerDevice
                    quality = View.QualityLevel.HIGH
                }
                view.bloomOptions = view.bloomOptions.apply {
                    enabled = !lowPowerDevice
                    strength = .045f
                }
            }

            materialProvider = UbershaderProvider(currentEngine)
            assetLoader = AssetLoader(currentEngine, materialProvider!!, EntityManager.get())
            resourceLoader = ResourceLoader(currentEngine, true)

            displayHelper = DisplayHelper(context)
            uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).also {
                it.renderCallback = surfaceCallback
                it.attachTo(surfaceView)
            }

            scene?.skybox = Skybox.Builder()
                .color(.91f, .965f, 1f, 1f)
                .build(currentEngine)
            camera?.setExposure(9.2f, 1f / 125f, 100f)
            installLights(currentEngine)

            body = loadPart(BODY_ASSET)
            head = loadPart(HEAD_ASSET)
            hair = runCatching { loadPart(HAIR_ASSET) }.getOrNull()

            val bodyAsset = body?.asset ?: error("Digital human body failed to load")
            headBoneEntity = bodyAsset.getFirstEntityByName(HEAD_BONE)
            check(headBoneEntity != 0) { "Digital human head bone '$HEAD_BONE' missing" }
            captureRestHeadTransform()
            activeAnimation = findAnimation(bodyAsset, listOf("Idle", "Sway", "HappyIdle"))
            animationStartNanos = 0L

            if (running) postFrame()
        }.onFailure {
            onFailure(it)
            destroy()
        }
    }

    private fun loadPart(path: String): Part {
        val currentEngine = engine ?: error("Engine not initialized")
        val loader = assetLoader ?: error("AssetLoader not initialized")
        val resources = resourceLoader ?: error("ResourceLoader not initialized")
        val bytes = context.assets.open(path).use { it.readBytes() }
        check(bytes.size > 100_000) { "$path is unexpectedly small" }
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
        val asset = loader.createAsset(buffer) ?: error("Could not parse $path")
        resources.loadResources(asset)
        asset.releaseSourceData()
        scene?.addEntities(asset.renderableEntities)
        if (asset.lightEntities.isNotEmpty()) scene?.addEntities(asset.lightEntities)

        val transformManager = currentEngine.transformManager
        val rootInstance = transformManager.getInstance(asset.root)
        check(rootInstance != 0) { "$path has no root transform" }
        val rootBase = FloatArray(16).also { transformManager.getTransform(rootInstance, it) }
        return Part(asset, rootBase)
    }

    private fun captureRestHeadTransform() {
        val currentEngine = engine ?: return
        val instance = currentEngine.transformManager.getInstance(headBoneEntity)
        if (instance == 0) return
        val rest = FloatArray(16).also {
            currentEngine.transformManager.getWorldTransform(instance, it)
        }
        val inverse = FloatArray(16)
        check(Matrix.invertM(inverse, 0, rest, 0)) { "Could not invert rest head transform" }
        headBoneRestWorld = rest
        headBoneRestInverse = inverse
    }

    private fun applyAnimation(frameTimeNanos: Long) {
        val bodyAsset = body?.asset ?: return
        val animator = bodyAsset.instance.animator
        val index = activeAnimation
        if (index !in 0 until animator.animationCount) return
        if (animationStartNanos == 0L) animationStartNanos = frameTimeNanos
        val elapsed = ((frameTimeNanos - animationStartNanos).toDouble() / 1_000_000_000.0).toFloat()
        val duration = animator.getAnimationDuration(index)
        val time = if (duration > .001f) elapsed % duration else elapsed
        animator.applyAnimation(index, time)
        animator.updateBoneMatrices()
    }

    private fun attachHeadAndHairToBody() {
        val currentEngine = engine ?: return
        val inverseRest = headBoneRestInverse ?: return
        val boneInstance = currentEngine.transformManager.getInstance(headBoneEntity)
        if (boneInstance == 0) return
        val currentHeadWorld = FloatArray(16).also {
            currentEngine.transformManager.getWorldTransform(boneInstance, it)
        }
        val delta = FloatArray(16)
        Matrix.multiplyMM(delta, 0, currentHeadWorld, 0, inverseRest, 0)
        attachPart(head, delta)
        attachPart(hair, delta)
    }

    private fun attachPart(part: Part?, delta: FloatArray) {
        part ?: return
        val currentEngine = engine ?: return
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, delta, 0, part.rootBase, 0)
        val instance = currentEngine.transformManager.getInstance(part.asset.root)
        if (instance != 0) currentEngine.transformManager.setTransform(instance, out)
    }

    private fun applyBodyRootYaw() {
        val part = body ?: return
        val currentEngine = engine ?: return
        val instance = currentEngine.transformManager.getInstance(part.asset.root)
        if (instance == 0) return
        val rotation = FloatArray(16)
        Matrix.setRotateM(rotation, 0, Math.toDegrees(yaw).toFloat(), 0f, 1f, 0f)
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, rotation, 0, part.rootBase, 0)
        currentEngine.transformManager.setTransform(instance, out)
    }

    private fun findFaceEntity() {
        val asset = head?.asset ?: return
        faceEntity = asset.renderableEntities.firstOrNull { entity ->
            val names = runCatching { asset.getMorphTargetNames(entity) }.getOrDefault(emptyArray())
            names.any { it.equals("Happy", true) || it.equals("Jaw_Lower", true) || it.contains("Eyes_Closed", true) }
        } ?: 0
    }

    fun updateAppearance(value: AvatarAppearance) {
        appearance = value
        if (ready) applyAppearance()
    }

    private fun applyAppearance() {
        val asset = head?.asset ?: return
        val entity = faceEntity
        if (entity == 0) return
        val names = asset.getMorphTargetNames(entity)
        if (names.isEmpty()) return
        val weights = FloatArray(names.size)
        fun set(name: String, value: Float) {
            val index = names.indexOfFirst { it.equals(name, ignoreCase = true) }
            if (index >= 0) weights[index] = value.coerceIn(0f, 1f)
        }

        when (appearance.eyesVariant) {
            "wide" -> {
                set("Eyes_Opened_Max_Left", .60f)
                set("Eyes_Opened_Max_Right", .60f)
            }
            "sharp" -> {
                set("Eyes_Squint", .26f)
                set("Eyebrows_Frown_Left", .16f)
                set("Eyebrows_Frown_Right", .16f)
            }
        }
        if (appearance.eyebrowsVariant == "defined") {
            set("Eyebrows_Raised_Left", .14f)
            set("Eyebrows_Raised_Right", .14f)
        }
        when (appearance.mouthVariant) {
            "smile" -> set("Happy", .62f)
            "full" -> set("Lips_Up_Funnel", .24f)
        }

        val currentEngine = engine ?: return
        val renderableManager = currentEngine.renderableManager
        val instance = renderableManager.getInstance(entity)
        if (instance != 0) {
            renderableManager.setMorphWeights(instance, weights, 0)
        }
    }

    fun faceFront() {
        turntableDurationNanos = 0L
        targetYaw = 0.0
    }

    fun playTurntable(durationMs: Long = 2_600L) {
        turntableStartNanos = 0L
        turntableDurationNanos = durationMs.coerceIn(1_400L, 4_200L) * 1_000_000L
        turntableFromYaw = yaw
    }

    private fun updateTurntable(frameTimeNanos: Long) {
        if (turntableDurationNanos <= 0L) return
        if (turntableStartNanos == 0L) turntableStartNanos = frameTimeNanos
        val t = ((frameTimeNanos - turntableStartNanos).toDouble() / turntableDurationNanos).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        yaw = turntableFromYaw + eased * PI * 2.0
        if (t >= 1.0) {
            turntableDurationNanos = 0L
            turntableStartNanos = 0L
            yaw = 0.0
            targetYaw = 0.0
        }
    }

    private fun updateCamera() {
        val distance = 3.05
        camera?.lookAt(
            sin(yaw * .03) * .03,
            1.05,
            distance,
            0.0,
            1.02,
            0.0,
            0.0,
            1.0,
            0.0,
        )
    }

    private fun findAnimation(asset: FilamentAsset, hints: List<String>): Int {
        val animator = asset.instance.animator
        if (animator.animationCount <= 0) return -1
        hints.forEach { hint ->
            for (index in 0 until animator.animationCount) {
                if (animator.getAnimationName(index).contains(hint, ignoreCase = true)) return index
            }
        }
        return 0
    }

    private fun installLights(currentEngine: Engine) {
        fun directional(
            intensity: Float,
            r: Float,
            g: Float,
            b: Float,
            x: Float,
            y: Float,
            z: Float,
            shadows: Boolean,
        ) {
            val entity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(r, g, b)
                .intensity(intensity)
                .direction(x, y, z)
                .castShadows(shadows)
                .build(currentEngine, entity)
            scene?.addEntity(entity)
        }

        directional(78_000f, 1f, .98f, .95f, -.42f, -.76f, -.54f, !lowPowerDevice)
        directional(34_000f, .68f, .88f, 1f, .67f, -.10f, -.73f, false)
        directional(17_000f, 1f, .80f, .88f, -.15f, .26f, .95f, false)
    }

    fun start() {
        if (running || destroyed) return
        running = true
        postFrame()
    }

    fun stop() {
        running = false
        framePosted = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun postFrame() {
        if (!running || framePosted || destroyed) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stop()
        runCatching { uiHelper?.detach() }
        runCatching { displayHelper?.detach() }

        val currentEngine = engine ?: return
        swapChain?.let {
            runCatching { currentEngine.destroySwapChain(it) }
            swapChain = null
        }

        listOfNotNull(body?.asset, head?.asset, hair?.asset).forEach { asset ->
            runCatching { scene?.removeEntities(asset.renderableEntities) }
            runCatching { assetLoader?.destroyAsset(asset) }
        }
        body = null
        head = null
        hair = null

        runCatching { resourceLoader?.destroy() }
        runCatching { materialProvider?.destroyMaterials() }
        runCatching { materialProvider?.destroy() }
        runCatching { assetLoader?.destroy() }

        renderer?.let { runCatching { currentEngine.destroyRenderer(it) } }
        filamentView?.let { runCatching { currentEngine.destroyView(it) } }
        scene?.let { runCatching { currentEngine.destroyScene(it) } }
        camera?.let { runCatching { currentEngine.destroyCameraComponent(it.entity) } }
        if (cameraEntity != 0) EntityManager.get().destroy(cameraEntity)
        runCatching { currentEngine.destroy() }

        engine = null
        renderer = null
        filamentView = null
        scene = null
        camera = null
    }

    private fun synchronizePendingFrames(currentEngine: Engine) {
        val fence = currentEngine.createFence()
        fence.wait(Fence.Mode.FLUSH, Fence.WAIT_FOR_EVER)
        currentEngine.destroyFence(fence)
    }
}
